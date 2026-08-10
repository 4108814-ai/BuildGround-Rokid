package com.anezium.rokidbus.phone

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Private UI/service edge for native applications installed on the glasses. */
object NativeAppsPhoneContract {
    const val VERSION = 1
    const val ACTION_COMMAND = "com.anezium.rokidbus.phone.nativeapps.COMMAND"
    const val ACTION_STATE = "com.anezium.rokidbus.phone.nativeapps.STATE"

    const val COMMAND_REQUEST_LIST = "request_list"
    const val COMMAND_OPEN = "open"
    const val COMMAND_INSTALL = "install"

    private const val EXTRA_VERSION = "version"
    private const val EXTRA_COMMAND = "command"
    private const val EXTRA_REQUEST_ID = "request_id"
    private const val EXTRA_APP_ID = "app_id"
    private const val EXTRA_STATE = "state"
    private const val EXTRA_APPS = "apps"
    private const val EXTRA_ERROR = "error"

    fun requestList(context: Context, requestId: String): Intent =
        command(context, COMMAND_REQUEST_LIST, requestId)

    fun open(context: Context, requestId: String, appId: String): Intent =
        command(context, COMMAND_OPEN, requestId).putExtra(EXTRA_APP_ID, appId.take(MAX_ID_LENGTH))

    fun install(context: Context, requestId: String, appId: String): Intent =
        command(context, COMMAND_INSTALL, requestId).putExtra(EXTRA_APP_ID, appId.take(MAX_ID_LENGTH))

    fun parseCommand(intent: Intent): PhoneNativeAppsCommand? {
        if (intent.action != ACTION_COMMAND || intent.getIntExtra(EXTRA_VERSION, -1) != VERSION) {
            return null
        }
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            ?.trim()
            ?.take(MAX_REQUEST_ID_LENGTH)
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_REQUEST_LIST -> PhoneNativeAppsCommand.RequestList(requestId)
            COMMAND_OPEN,
            COMMAND_INSTALL,
            -> {
                val appId = intent.getStringExtra(EXTRA_APP_ID)
                    ?.trim()
                    ?.take(MAX_ID_LENGTH)
                    ?.takeIf(String::isNotEmpty)
                    ?: return null
                if (intent.getStringExtra(EXTRA_COMMAND) == COMMAND_OPEN) {
                    PhoneNativeAppsCommand.Open(requestId, appId)
                } else {
                    PhoneNativeAppsCommand.Install(requestId, appId)
                }
            }
            else -> null
        }
    }

    fun stateIntent(context: Context, state: NativeAppsUiState): Intent {
        val intent = Intent(ACTION_STATE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_VERSION, VERSION)
        return when (state) {
            NativeAppsUiState.Loading -> intent.putExtra(EXTRA_STATE, STATE_LOADING)
            NativeAppsUiState.Empty -> intent.putExtra(EXTRA_STATE, STATE_EMPTY)
            is NativeAppsUiState.Error -> intent
                .putExtra(EXTRA_STATE, STATE_ERROR)
                .putExtra(EXTRA_ERROR, state.message.take(MAX_ERROR_LENGTH))
            is NativeAppsUiState.Content -> intent
                .putExtra(EXTRA_STATE, STATE_READY)
                .putExtra(EXTRA_APPS, NativeAppsCodec.encode(state.apps))
        }
    }

    fun parseState(intent: Intent): NativeAppsUiState? {
        if (intent.action != ACTION_STATE || intent.getIntExtra(EXTRA_VERSION, -1) != VERSION) {
            return null
        }
        return when (intent.getStringExtra(EXTRA_STATE)) {
            STATE_LOADING -> NativeAppsUiState.Loading
            STATE_EMPTY -> NativeAppsUiState.Empty
            STATE_ERROR -> NativeAppsUiState.Error(
                intent.getStringExtra(EXTRA_ERROR)
                    ?.trim()
                    ?.take(MAX_ERROR_LENGTH)
                    ?.takeIf(String::isNotEmpty)
                    ?: "The glasses did not return an app list.",
            )
            STATE_READY -> NativeAppsCodec.decode(intent.getStringExtra(EXTRA_APPS))
            else -> null
        }
    }

    private fun command(context: Context, command: String, requestId: String): Intent =
        Intent(ACTION_COMMAND)
            .setPackage(context.packageName)
            .putExtra(EXTRA_VERSION, VERSION)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_REQUEST_ID, requestId.take(MAX_REQUEST_ID_LENGTH))

    private const val STATE_LOADING = "loading"
    private const val STATE_EMPTY = "empty"
    private const val STATE_ERROR = "error"
    private const val STATE_READY = "ready"
    private const val MAX_REQUEST_ID_LENGTH = 128
    private const val MAX_ERROR_LENGTH = 160
    private const val MAX_ID_LENGTH = 160
}

sealed interface PhoneNativeAppsCommand {
    data class RequestList(val requestId: String) : PhoneNativeAppsCommand
    data class Open(val requestId: String, val appId: String) : PhoneNativeAppsCommand
    data class Install(val requestId: String, val appId: String) : PhoneNativeAppsCommand
}

enum class NativeAppAction(val wireName: String) {
    OPEN("open"),
    INSTALL("install"),
    NONE("none");

    companion object {
        fun fromWireName(value: String?): NativeAppAction =
            entries.firstOrNull { it.wireName == value } ?: NONE
    }
}

data class NativeGlassesApp(
    val id: String,
    val name: String,
    val detail: String,
    val action: NativeAppAction,
)

sealed interface NativeAppsUiState {
    data object Loading : NativeAppsUiState
    data object Empty : NativeAppsUiState
    data class Error(val message: String) : NativeAppsUiState
    data class Content(val apps: List<NativeGlassesApp>) : NativeAppsUiState
}

object NativeAppsCodec {
    fun encode(apps: List<NativeGlassesApp>): String = JSONArray().apply {
        apps.take(MAX_APPS).forEach { app ->
            put(
                JSONObject()
                    .put("id", app.id.take(MAX_TEXT_LENGTH))
                    .put("name", app.name.take(MAX_TEXT_LENGTH))
                    .put("detail", app.detail.take(MAX_TEXT_LENGTH))
                    .put("action", app.action.wireName),
            )
        }
    }.toString()

    fun decode(raw: String?): NativeAppsUiState {
        if (raw.isNullOrBlank()) return NativeAppsUiState.Empty
        return runCatching {
            val array = JSONArray(raw)
            val apps = buildList {
                for (index in 0 until minOf(array.length(), MAX_APPS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim().take(MAX_TEXT_LENGTH)
                    val name = item.optString("name").trim().take(MAX_TEXT_LENGTH)
                    if (id.isEmpty() || name.isEmpty()) continue
                    add(
                        NativeGlassesApp(
                            id = id,
                            name = name,
                            detail = item.optString("detail").trim().take(MAX_TEXT_LENGTH),
                            action = NativeAppAction.fromWireName(item.optString("action")),
                        ),
                    )
                }
            }
            if (apps.isEmpty()) NativeAppsUiState.Empty else NativeAppsUiState.Content(apps)
        }.getOrElse {
            NativeAppsUiState.Error("The app list returned by the glasses was not valid.")
        }
    }

    private const val MAX_APPS = 100
    private const val MAX_TEXT_LENGTH = 160
}
