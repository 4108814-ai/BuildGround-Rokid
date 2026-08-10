package com.anezium.rokidbus.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.NativeAppLaunchRequest
import com.anezium.rokidbus.shared.RemoteEditorAction
import com.anezium.rokidbus.shared.RemoteInputCloseReason
import com.anezium.rokidbus.shared.RemoteInputCommand
import com.anezium.rokidbus.shared.RemoteInputContract
import com.anezium.rokidbus.shared.RemoteInputStatusCode
import com.anezium.rokidbus.shared.RemoteNavigationAction
import com.anezium.rokidbus.shared.RemoteNavigationContract
import com.anezium.rokidbus.shared.RemoteNavigationRequest

internal const val INTERNAL_CORE_PERMISSION =
    "com.anezium.rokidbus.phone.permission.INTERNAL_CORE_CONTROL"

/** Owns the private phone-UI edge and translates it to versioned core bus messages. */
internal class PhoneCoreRemoteBridge(
    context: Context,
    private val sendRemote: (BusEnvelope) -> String?,
    private val isConnected: () -> Boolean,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var receiverRegistered = false
    private var inputState = RemoteInputTransportState(connected = false, fieldActive = false)
    private var nextInputSequence = 1L
    private var remoteImeOptions = EditorInfo.IME_ACTION_NONE
    private var nativeAppsState: NativeAppsUiState = NativeAppsUiState.Loading
    private val pendingNativeRequests = linkedSetOf<String>()

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val commandIntent = intent ?: return
            when (commandIntent.action) {
                RemoteInputPhoneContract.ACTION_COMMAND ->
                    RemoteInputPhoneContract.parseCommand(commandIntent)?.let(::handlePhoneInput)
                RemoteInputPhoneContract.ACTION_NAVIGATE ->
                    RemoteInputPhoneContract.parseNavigation(commandIntent)?.let(::handlePhoneNavigation)
                NativeAppsPhoneContract.ACTION_COMMAND ->
                    NativeAppsPhoneContract.parseCommand(commandIntent)?.let(::handlePhoneNativeApps)
            }
        }
    }

    fun start() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(RemoteInputPhoneContract.ACTION_COMMAND)
            addAction(RemoteInputPhoneContract.ACTION_NAVIGATE)
            addAction(NativeAppsPhoneContract.ACTION_COMMAND)
        }
        ContextCompat.registerReceiver(
            appContext,
            commandReceiver,
            filter,
            INTERNAL_CORE_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        onLinkStateChanged(isConnected())
    }

    fun onLinkStateChanged(connected: Boolean) {
        inputState = if (connected) {
            inputState.copy(connected = true)
        } else {
            remoteImeOptions = EditorInfo.IME_ACTION_NONE
            nextInputSequence = 1L
            RemoteInputTransportState(connected = false, fieldActive = false)
        }
        publishInputState()
    }

    fun handleRemote(envelope: BusEnvelope): Boolean = when (envelope.path) {
        RemoteInputContract.SESSION_PATH -> handleRemoteSession(envelope)
        RemoteInputContract.STATUS_PATH -> handleRemoteInputStatus(envelope)
        RemoteNavigationContract.RESULT_PATH ->
            envelope.binary == null && RemoteNavigationContract.parseResult(envelope.payload) != null
        NativeAppContract.RESULT_PATH -> handleRemoteNativeApps(envelope)
        else -> false
    }

    override fun close() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(commandReceiver) }
        receiverRegistered = false
        pendingNativeRequests.clear()
    }

    private fun handlePhoneInput(command: PhoneRemoteCommand) {
        if (command is PhoneRemoteCommand.RequestState) {
            publishInputState()
            return
        }
        val sessionId = command.sessionIdOrNull() ?: return
        if (!inputState.connected || !inputState.fieldActive || inputState.sessionId != sessionId) return

        val sequence = nextInputSequence
        val wireCommand = when (command) {
            PhoneRemoteCommand.RequestState -> return
            is PhoneRemoteCommand.CommitText -> {
                if (command.text.isEmpty()) return
                RemoteInputCommand.CommitText(sessionId, sequence, command.text)
            }
            is PhoneRemoteCommand.SetComposingText ->
                RemoteInputCommand.SetComposingText(sessionId, sequence, command.text)
            is PhoneRemoteCommand.FinishComposing ->
                RemoteInputCommand.FinishComposingText(sessionId, sequence)
            is PhoneRemoteCommand.DeleteSurrounding -> {
                if (command.beforeLength == 0 && command.afterLength == 0) return
                RemoteInputCommand.DeleteSurroundingText(
                    sessionId,
                    sequence,
                    command.beforeLength,
                    command.afterLength,
                )
            }
            is PhoneRemoteCommand.PerformEditorAction -> RemoteInputCommand.PerformEditorAction(
                sessionId,
                sequence,
                if (command.action == RemoteInputPhoneContract.EDITOR_NEXT) {
                    RemoteEditorAction.NEXT
                } else {
                    remoteEditorAction(remoteImeOptions)
                },
            )
            is PhoneRemoteCommand.Close -> RemoteInputCommand.Close(
                sessionId,
                sequence,
                RemoteInputCloseReason.USER_DISMISSED,
            )
        }
        val payload = runCatching { RemoteInputContract.encodeCommand(wireCommand) }.getOrNull() ?: return
        if (sendRemote(BusEnvelope(path = RemoteInputContract.COMMAND_PATH, payload = payload)) == null) {
            nextInputSequence += 1L
        }
    }

    private fun handlePhoneNavigation(navigation: PhoneRemoteNavigation) {
        if (!isConnected()) return
        val action = when (navigation.action) {
            RemoteInputPhoneContract.KEY_PREVIOUS -> RemoteNavigationAction.PREVIOUS
            RemoteInputPhoneContract.KEY_NEXT -> RemoteNavigationAction.NEXT
            RemoteInputPhoneContract.KEY_SELECT -> RemoteNavigationAction.SELECT
            RemoteInputPhoneContract.KEY_BACK -> RemoteNavigationAction.BACK
            else -> return
        }
        val request = runCatching {
            RemoteNavigationContract.request(RemoteNavigationRequest(navigation.requestId, action))
        }.getOrNull() ?: return
        sendRemote(BusEnvelope(path = RemoteNavigationContract.REQUEST_PATH, payload = request))
    }

    private fun handlePhoneNativeApps(command: PhoneNativeAppsCommand) {
        when (command) {
            is PhoneNativeAppsCommand.Install -> {
                publishNativeApps(
                    NativeAppsUiState.Error(
                        "Installing glasses apps is the next step; this screen currently lists and opens them.",
                    ),
                )
            }
            is PhoneNativeAppsCommand.RequestList -> {
                if (!isConnected()) {
                    publishNativeApps(NativeAppsUiState.Error("The glasses are not connected."))
                    return
                }
                publishNativeApps(NativeAppsUiState.Loading)
                val payload = runCatching { NativeAppContract.listRequest(command.requestId) }
                    .getOrNull() ?: return
                if (sendRemote(BusEnvelope(path = NativeAppContract.REQUEST_PATH, payload = payload)) == null) {
                    pendingNativeRequests += command.requestId
                } else {
                    publishNativeApps(NativeAppsUiState.Error("The app request could not reach the glasses."))
                }
            }
            is PhoneNativeAppsCommand.Open -> {
                if (!isConnected()) {
                    publishNativeApps(NativeAppsUiState.Error("The glasses are not connected."))
                    return
                }
                val payload = runCatching {
                    NativeAppContract.launchRequest(
                        NativeAppLaunchRequest(command.requestId, command.appId),
                    )
                }.getOrNull() ?: return
                if (sendRemote(BusEnvelope(path = NativeAppContract.REQUEST_PATH, payload = payload)) == null) {
                    pendingNativeRequests += command.requestId
                }
            }
        }
    }

    private fun handleRemoteSession(envelope: BusEnvelope): Boolean {
        if (envelope.binary != null) return false
        RemoteInputContract.decodeSessionOpen(envelope.payload)?.let { session ->
            remoteImeOptions = session.imeOptions
            nextInputSequence = session.nextSequence
            inputState = RemoteInputTransportState(
                connected = true,
                fieldActive = true,
                password = session.sensitive,
                sessionId = session.sessionId,
                fieldLabel = null,
                imeAction = localImeAction(session.imeOptions),
            )
            publishInputState()
            return true
        }
        val closed = RemoteInputContract.decodeSessionClosed(envelope.payload) ?: return false
        if (closed.sessionId == inputState.sessionId) clearActiveInput()
        return true
    }

    private fun handleRemoteInputStatus(envelope: BusEnvelope): Boolean {
        if (envelope.binary != null) return false
        val status = RemoteInputContract.decodeStatus(envelope.payload) ?: return false
        if (status.sessionId != inputState.sessionId) return true
        if (status.status == RemoteInputStatusCode.CLOSED) {
            clearActiveInput()
        } else if (status.status == RemoteInputStatusCode.REJECTED) {
            status.expectedSequence?.let { nextInputSequence = it }
        }
        return true
    }

    private fun handleRemoteNativeApps(envelope: BusEnvelope): Boolean {
        if (envelope.binary != null) return false
        NativeAppContract.parseListResult(envelope.payload)?.let { result ->
            if (!pendingNativeRequests.remove(result.requestId)) return true
            if (!result.success) {
                publishNativeApps(NativeAppsUiState.Error("The glasses could not list installed apps."))
                return true
            }
            val apps = result.apps.map { app ->
                NativeGlassesApp(
                    id = app.packageName,
                    name = app.label,
                    detail = app.packageName,
                    action = NativeAppAction.OPEN,
                )
            }
            publishNativeApps(
                if (apps.isEmpty()) NativeAppsUiState.Empty else NativeAppsUiState.Content(apps),
            )
            return true
        }
        val result = NativeAppContract.parseLaunchResult(envelope.payload) ?: return false
        if (!pendingNativeRequests.remove(result.requestId)) return true
        if (!result.success) {
            publishNativeApps(NativeAppsUiState.Error("The selected app could not be opened."))
        } else {
            publishNativeApps(nativeAppsState)
        }
        return true
    }

    private fun clearActiveInput() {
        remoteImeOptions = EditorInfo.IME_ACTION_NONE
        nextInputSequence = 1L
        inputState = RemoteInputTransportState(
            connected = isConnected(),
            fieldActive = false,
        )
        publishInputState()
    }

    private fun publishInputState() {
        appContext.sendBroadcast(RemoteInputPhoneContract.stateIntent(appContext, inputState))
    }

    private fun publishNativeApps(state: NativeAppsUiState) {
        nativeAppsState = state
        appContext.sendBroadcast(NativeAppsPhoneContract.stateIntent(appContext, state))
    }

    private fun PhoneRemoteCommand.sessionIdOrNull(): String? = when (this) {
        PhoneRemoteCommand.RequestState -> null
        is PhoneRemoteCommand.CommitText -> sessionId
        is PhoneRemoteCommand.SetComposingText -> sessionId
        is PhoneRemoteCommand.FinishComposing -> sessionId
        is PhoneRemoteCommand.DeleteSurrounding -> sessionId
        is PhoneRemoteCommand.PerformEditorAction -> sessionId
        is PhoneRemoteCommand.Close -> sessionId
    }

    private fun localImeAction(imeOptions: Int): String = when (imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_NEXT -> RemoteInputPhoneContract.IME_ACTION_NEXT
        EditorInfo.IME_ACTION_DONE -> RemoteInputPhoneContract.IME_ACTION_DONE
        EditorInfo.IME_ACTION_NONE -> RemoteInputPhoneContract.IME_ACTION_NONE
        else -> RemoteInputPhoneContract.IME_ACTION_ENTER
    }

    private fun remoteEditorAction(imeOptions: Int): RemoteEditorAction =
        when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> RemoteEditorAction.GO
            EditorInfo.IME_ACTION_SEARCH -> RemoteEditorAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> RemoteEditorAction.SEND
            EditorInfo.IME_ACTION_NEXT -> RemoteEditorAction.NEXT
            EditorInfo.IME_ACTION_DONE -> RemoteEditorAction.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> RemoteEditorAction.PREVIOUS
            EditorInfo.IME_ACTION_NONE -> RemoteEditorAction.NONE
            else -> RemoteEditorAction.UNSPECIFIED
        }
}
