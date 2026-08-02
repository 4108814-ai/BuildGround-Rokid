package com.anezium.rokidbus.plugin.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotCallbacks
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.client.plugin.NexusSnapshotSession
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AssistantPluginService : NexusPluginService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authStore by lazy { CodexAuthStore(applicationContext) }
    private val threadStore by lazy { AssistantThreadStore(applicationContext) }
    private val conversationThreading by lazy { AssistantConversationThreading(threadStore) }
    private val openAiClient by lazy { OpenAiApiClient(authStore::apiKey) }
    private val openAiProvider by lazy {
        OpenAiProvider(
            apiClient = openAiClient,
            apiKeyConfigured = authStore::hasApiKey,
            modelProvider = authStore::model,
        )
    }
    private val chatGptCodexClient by lazy {
        ChatGptCodexApiClient(
            tokenProvider = authStore::oauthTokens,
            refreshTokens = {
                CodexChatGptOAuth.refreshStoredTokens(applicationContext)
            },
        )
    }
    private val chatGptCodexProvider by lazy {
        ChatGptCodexProvider(
            apiClient = chatGptCodexClient,
            oauthConfigured = { authStore.oauthTokens() != null },
            toolExecutor = AssistantToolExecutor(::executeAssistantTool),
            modelProvider = authStore::chatGptModel,
            reasoningEffortProvider = authStore::chatGptReasoningEffort,
        )
    }
    private val providerRouter by lazy {
        ProviderRouter(
            providers = listOf(openAiProvider, chatGptCodexProvider),
            defaultProviderId = OpenAiProvider.ID,
        )
    }
    private val transcriber by lazy { OpenAiTranscriber(authStore::apiKey) }

    private var surface: NexusSurfaceSession? = null
    private var speechSession: NexusSpeechSession? = null
    private var audioSession: NexusAudioSession? = null
    private var snapshotSession: NexusSnapshotSession? = null
    private var captureGeneration = 0L
    private var captureActive = false
    private var fallbackTranscribePending = false
    private var fallbackStopJob: Job? = null
    private var audioFormat: NexusAudioFormat? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var pipelineJob: Job? = null
    private var currentRequestId: String? = null
    private var currentAssistantGeneration: Long? = null
    private var photoAttemptRequestId: String? = null
    private var photoCapturedRequestId: String? = null
    private var photoJpegForCompletedTurn: ByteArray? = null
    private var currentLinkState = 0
    private val captureTriggerGate = AssistantCaptureTriggerGate()
    private val uiController = AssistantUiController(
        scope = serviceScope,
        renderer = object : AssistantUiRenderer {
            override val supportsNoticeSurface: Boolean
                get() = nexusClient?.supportsNoticeSurface == true

            override fun showNotice(notice: NexusNotice): NexusSdkResult =
                nexusClient?.showNotice(notice) ?: NexusSdkResult.NOT_REGISTERED

            override fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult =
                nexusClient?.updateNotice(update) ?: NexusSdkResult.NOT_REGISTERED

            override fun hideNotice(): NexusSdkResult =
                nexusClient?.hideNotice() ?: NexusSdkResult.NOT_REGISTERED

            override fun showCard(
                lines: List<String>,
                forceShow: Boolean,
            ): NexusSdkResult = renderCard(lines, forceShow)
        },
        cancelPipeline = ::cancelPipeline,
        resetCapture = ::resetCapture,
    )

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        uiController.onOpen()
    }

    override fun onNexusClose() {
        uiController.onClose()
        captureTriggerGate.resetSession()
        resetCapture()
        cancelPipeline()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            cancelPipeline()
            resetCapture()
            surface?.hide()
            uiController.onSurfaceHidden()
        }
    }

    override fun onNexusLinkState(state: Int) {
        currentLinkState = state
    }

    override fun onNexusNoticeClosed(reason: NexusNoticeCloseReason) {
        uiController.onNoticeClosed(reason)
    }

    override fun onNexusGlassesAiButton(active: Boolean) {
        if (!isNexusSessionOpen) return
        if (active) {
            if (captureTriggerGate.claimButtonStart()) startCaptureOnce()
        } else {
            captureTriggerGate.onButtonStop()
        }
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) {
        if (path != AI_ASSIST_OPEN_PATH ||
            payload.optString("type") != AI_ASSIST_OPEN_TYPE ||
            !isNexusSessionOpen
        ) {
            return
        }
        val gestureId = payload.optString("gestureId")
        if (!captureTriggerGate.claimGestureOpen(gestureId)) return
        uiController.cancelLauncherHint()
        startCaptureOnce()
        if (!payload.optBoolean("buttonActive", true)) {
            captureTriggerGate.onButtonStop()
        }
    }

    override fun onDestroy() {
        uiController.onClose()
        resetCapture()
        cancelPipeline()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCaptureOnce() {
        if (captureActive) return
        beginCapture()
    }

    private fun beginCapture() {
        uiController.beginGestureFlow()
        if (captureActive) return
        if (!authStore.hasUsableAuth()) {
            resetCapture()
            uiController.showError(
                body = "Connect your ChatGPT account in settings",
                legacyForceShow = true,
            )
            return
        }

        cancelPipeline()
        captureGeneration += 1
        val generation = captureGeneration
        captureActive = true
        fallbackTranscribePending = false
        fallbackStopJob?.cancel()
        fallbackStopJob = null
        audioFormat = null
        pcmBuffer = ByteArrayOutputStream()
        uiController.showTransient("Listening…", legacyForceShow = true)
        startSpeechCapture(generation)
    }

    private fun startSpeechCapture(generation: Long) {
        var createdSpeech: NexusSpeechSession? = null
        var finalDelivered = false
        val callbacks = object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) {
                if (generation != captureGeneration || !captureActive) {
                    createdSpeech?.stop()
                    return
                }
                uiController.showTransient("Listening…")
            }

            override fun onSpeechState(state: NexusSpeechState) = Unit

            override fun onSpeechPartial(text: String) {
                if (generation != captureGeneration || !captureActive || finalDelivered) return
                uiController.showTranscript(text)
            }

            override fun onSpeechFinal(text: String) {
                if (generation != captureGeneration || !captureActive || finalDelivered) return
                val transcript = normalizeTranscript(text)
                if (transcript.isEmpty()) return
                finalDelivered = true
                launchAssistantPipeline(transcript)
            }

            override fun onSpeechStopped(
                reason: NexusSpeechStopReason,
                error: NexusSpeechError?,
            ) {
                if (speechSession === createdSpeech) speechSession = null
                if (generation != captureGeneration) return
                captureActive = false
                if (finalDelivered) return
                if (shouldUseRawCaptureFallback(reason)) {
                    startFallbackCapture(generation)
                    return
                }
                handleSpeechFailure(reason, error)
            }
        }
        val session = nexusSpeechSession(callbacks)
        createdSpeech = session
        speechSession = session
        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (result != NexusSdkResult.SENT) {
            if (speechSession === session) speechSession = null
            if (generation != captureGeneration) return
            captureActive = false
            if (shouldUseRawCaptureFallback(result)) {
                startFallbackCapture(generation)
            } else {
                Log.w(TAG, "Hub speech start rejected: $result")
                uiController.showError("Speech unavailable. Try again.")
            }
        }
    }

    private fun startFallbackCapture(generation: Long) {
        if (generation != captureGeneration || captureActive) return
        captureActive = true
        fallbackTranscribePending = false
        audioFormat = null
        pcmBuffer = ByteArrayOutputStream()
        uiController.showTransient("Listening…")

        var createdAudio: NexusAudioSession? = null
        val callbacks = object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                if (generation != captureGeneration || !captureActive) {
                    createdAudio?.stop()
                    return
                }
                audioFormat = format
                fallbackStopJob?.cancel()
                fallbackStopJob = serviceScope.launch {
                    delay(FALLBACK_CAPTURE_DURATION_MS)
                    if (generation != captureGeneration ||
                        !captureActive ||
                        audioSession !== createdAudio
                    ) {
                        return@launch
                    }
                    fallbackTranscribePending = true
                    uiController.showTransient("Transcribing…")
                    createdAudio?.stop()
                }
            }

            override fun onAudioFrame(
                pcm: ByteArray,
                seq: Long,
                elapsedRealtimeMs: Long,
            ) {
                if (generation != captureGeneration ||
                    !captureActive ||
                    fallbackTranscribePending
                ) {
                    return
                }
                pcmBuffer.write(pcm)
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) {
                if (audioSession === createdAudio) audioSession = null
                if (generation != captureGeneration) return
                fallbackStopJob?.cancel()
                fallbackStopJob = null
                val shouldTranscribe =
                    fallbackTranscribePending && reason == NexusAudioStopReason.RELEASED
                fallbackTranscribePending = false
                captureActive = false
                if (shouldTranscribe) {
                    launchFallbackAssistantPipeline(
                        pcm = pcmBuffer.toByteArray(),
                        format = audioFormat,
                    )
                } else if (reason != NexusAudioStopReason.RELEASED) {
                    Log.w(TAG, "Fallback microphone stopped: $reason")
                    uiController.showError("Speech unavailable. Try again.")
                }
            }
        }
        val session = nexusAudioSession(callbacks)
        createdAudio = session
        audioSession = session
        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (result != NexusSdkResult.SENT) {
            if (audioSession === session) audioSession = null
            if (generation != captureGeneration) return
            captureActive = false
            Log.w(TAG, "Fallback microphone start rejected: $result")
            uiController.showError(
                body = if (result == NexusSdkResult.CAPABILITY_NOT_GRANTED) {
                    "Grant Speech to text in Nexus settings."
                } else {
                    "Speech unavailable. Try again."
                },
            )
        }
    }

    private fun launchFallbackAssistantPipeline(
        pcm: ByteArray,
        format: NexusAudioFormat?,
    ) {
        if (pcm.isEmpty() || format == null) {
            uiController.showError("Didn't catch that")
            return
        }
        launchPipeline {
            val transcript = transcriber.transcribe(pcm, format).trim()
            if (transcript.isEmpty()) {
                uiController.showError("Didn't catch that")
                return@launchPipeline
            }
            streamAssistantAnswer(transcript)
        }
    }

    private fun launchAssistantPipeline(transcript: String) {
        val normalized = normalizeTranscript(transcript)
        if (normalized.isEmpty()) {
            uiController.showError("Didn't catch that")
            return
        }
        launchPipeline {
            streamAssistantAnswer(normalized)
        }
    }

    private fun launchPipeline(block: suspend () -> Unit) {
        pipelineJob?.cancel()
        val launched = serviceScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Assistant pipeline failed: ${error.javaClass.simpleName}")
                showError(error.conciseProviderMessage("Request failed. Try again."))
            } finally {
                if (pipelineJob === currentCoroutineContext()[Job]) {
                    pipelineJob = null
                }
            }
        }
        pipelineJob = launched
    }

    private suspend fun streamAssistantAnswer(transcript: String) {
        val noticeBandMode = uiController.isNoticeBandMode
        uiController.showTransient("Thinking…")
        val keepConversation = authStore.keepConversation()
        val keepPhotosInConversations = authStore.keepPhotosInConversations()
        val conversationContext = withContext(Dispatchers.IO) {
            if (!keepPhotosInConversations && threadStore.hasStoredPhotos()) {
                threadStore.deleteAllPhotos()
            }
            conversationThreading.prepare(
                keepConversation = keepConversation,
                idleWindowMinutes = authStore.conversationIdleWindowMinutes(),
            )
        }
        val request = ChatRequest(
            userText = transcript,
            systemPrompt = NexusAgentPolicy.buildSystemPrompt(
                noticeBand = noticeBandMode,
                memory = authStore.assistantMemory(),
            ),
            history = conversationContext.history,
            model = when (authStore.authMode()) {
                CodexAuthStore.AUTH_MODE_CHATGPT -> authStore.chatGptModel()
                else -> authStore.model()
            },
        )
        currentRequestId = request.requestId
        currentAssistantGeneration = captureGeneration
        photoAttemptRequestId = null
        photoCapturedRequestId = null
        photoJpegForCompletedTurn = null
        val answer = StringBuilder()
        var lastHudUpdateMs = 0L
        var completed = false
        var failed = false
        var finalAnswer: String? = null
        val providerId = when (authStore.authMode()) {
            CodexAuthStore.AUTH_MODE_CHATGPT -> ChatGptCodexProvider.ID
            else -> OpenAiProvider.ID
        }
        try {
            providerRouter.providerFor(providerId).streamEvents(request).collect { event ->
                when (event) {
                    is AiProviderEvent.Started -> Unit
                    is AiProviderEvent.TextReset -> {
                        answer.clear()
                        lastHudUpdateMs = 0L
                    }
                    is AiProviderEvent.TextDelta -> {
                        answer.append(event.delta)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastHudUpdateMs >= HUD_UPDATE_INTERVAL_MS) {
                            showAnswer(answer.toString())
                            lastHudUpdateMs = now
                        }
                    }
                    is AiProviderEvent.MessageDone -> {
                        completed = true
                        val finalText = event.message.content.ifBlank { answer.toString() }
                        if (finalText.isBlank()) {
                            uiController.showError("No answer received. Try again.")
                        } else {
                            finalAnswer = finalText
                            showAnswer(finalText)
                        }
                    }
                    is AiProviderEvent.Failed -> {
                        completed = true
                        failed = true
                        showError(event.message)
                    }
                }
            }
            if (!completed) {
                if (answer.isBlank()) {
                    uiController.showError("No answer received. Try again.")
                } else {
                    finalAnswer = answer.toString()
                    showAnswer(finalAnswer.orEmpty())
                }
            }
            if (!failed) {
                currentCoroutineContext().ensureActive()
                try {
                    val keepPhotosAtCompletion = authStore.keepPhotosInConversations()
                    withContext(Dispatchers.IO) {
                        if (!keepPhotosAtCompletion && threadStore.hasStoredPhotos()) {
                            threadStore.deleteAllPhotos()
                        }
                        conversationThreading.recordCompletedTurn(
                            context = conversationContext,
                            userText = transcript,
                            assistantText = finalAnswer,
                            hadPhoto = photoCapturedRequestId == request.requestId,
                            photoJpeg = photoJpegForCompletedTurn
                                ?.takeIf { keepPhotosAtCompletion },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Conversation persistence failed: ${error.javaClass.simpleName}")
                }
            }
        } finally {
            if (currentRequestId == request.requestId) {
                currentRequestId = null
                currentAssistantGeneration = null
                photoJpegForCompletedTurn = null
            }
        }
    }

    private suspend fun executeAssistantTool(call: AssistantToolCall): AssistantToolResult {
        val startedAt = SystemClock.elapsedRealtime()
        val requestId = currentRequestId
        val loggedToolName = call.name.takeIf { it == TAKE_PHOTO_TOOL_NAME } ?: "invalid"
        var photoStateShown = false

        fun error(
            code: String,
            captureMs: Long = 0L,
            processMs: Long = 0L,
        ): AssistantToolResult.Error {
            if (
                photoStateShown &&
                currentRequestId == requestId &&
                pipelineJob?.isActive == true
            ) {
                uiController.showTransient("Thinking…")
            }
            logToolOutcome(
                requestId = requestId,
                toolName = loggedToolName,
                outcome = code,
                byteCount = 0,
                width = 0,
                height = 0,
                captureMs = captureMs,
                processMs = processMs,
                totalMs = SystemClock.elapsedRealtime() - startedAt,
            )
            return AssistantToolResult.Error(code)
        }

        if (!isValidTakePhotoCall(call)) return error(TOOL_ERROR_INVALID_CALL)
        val generation = currentAssistantGeneration
            ?: return error(TOOL_ERROR_CANCELLED)
        if (
            requestId == null ||
            generation != captureGeneration ||
            pipelineJob?.isActive != true ||
            !isNexusSessionOpen
        ) {
            return error(TOOL_ERROR_CANCELLED)
        }

        val client = nexusClient
        if (client?.hasCapability(PluginCapability.CAMERA) != true) {
            return error(TOOL_ERROR_NOT_AUTHORIZED)
        }
        if (currentLinkState and LinkStateBits.SPP_DATA_UP == 0) {
            return error(TOOL_ERROR_GLASSES_DISCONNECTED)
        }
        if (snapshotSession != null) return error(TOOL_ERROR_CAMERA_BUSY)
        if (photoAttemptRequestId == requestId) return error(TOOL_ERROR_ALREADY_USED)

        currentCoroutineContext().ensureActive()
        uiController.showTransient("Photo…")
        photoStateShown = true
        currentCoroutineContext().ensureActive()
        if (
            currentRequestId != requestId ||
            currentAssistantGeneration != generation ||
            generation != captureGeneration ||
            pipelineJob?.isActive != true
        ) {
            throw CancellationException("Assistant generation is stale.")
        }

        // The budget is consumed immediately before the first hardware-facing call.
        photoAttemptRequestId = requestId
        val captureStartedAt = SystemClock.elapsedRealtime()
        val jpeg = try {
            withTimeoutOrNull(SNAPSHOT_CAPTURE_TIMEOUT_MS) {
                captureSnapshotJpeg()
            } ?: return error(
                code = TOOL_ERROR_CAPTURE_FAILED,
                captureMs = SystemClock.elapsedRealtime() - captureStartedAt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SnapshotCaptureFailure) {
            return error(
                code = failure.code,
                captureMs = SystemClock.elapsedRealtime() - captureStartedAt,
            )
        } catch (_: Throwable) {
            return error(
                code = TOOL_ERROR_CAPTURE_FAILED,
                captureMs = SystemClock.elapsedRealtime() - captureStartedAt,
            )
        }
        val captureMs = SystemClock.elapsedRealtime() - captureStartedAt
        photoCapturedRequestId = requestId

        currentCoroutineContext().ensureActive()
        val processStartedAt = SystemClock.elapsedRealtime()
        val image = withContext(Dispatchers.Default) {
            prepareToolImage(jpeg)
        } ?: return error(
            code = TOOL_ERROR_CAPTURE_FAILED,
            captureMs = captureMs,
            processMs = SystemClock.elapsedRealtime() - processStartedAt,
        )
        val processMs = SystemClock.elapsedRealtime() - processStartedAt
        currentCoroutineContext().ensureActive()
        if (
            currentRequestId != requestId ||
            currentAssistantGeneration != generation ||
            generation != captureGeneration
        ) {
            throw CancellationException("Assistant generation is stale.")
        }
        photoJpegForCompletedTurn = image.jpeg

        uiController.showTransient("Thinking…")
        logToolOutcome(
            requestId = requestId,
            toolName = TAKE_PHOTO_TOOL_NAME,
            outcome = "ok",
            byteCount = image.byteCount,
            width = image.width,
            height = image.height,
            captureMs = captureMs,
            processMs = processMs,
            totalMs = SystemClock.elapsedRealtime() - startedAt,
        )
        return AssistantToolResult.Image(
            mimeType = "image/jpeg",
            base64 = image.base64,
        )
    }

    private suspend fun captureSnapshotJpeg(): ByteArray =
        suspendCancellableCoroutine { continuation ->
            var createdSession: NexusSnapshotSession? = null
            val callbacks = object : NexusSnapshotCallbacks {
                override fun onSnapshotCaptured(jpeg: ByteArray) {
                    if (snapshotSession === createdSession) snapshotSession = null
                    if (!continuation.isActive) return
                    continuation.resume(jpeg)
                }

                override fun onSnapshotError(error: NexusSnapshotError) {
                    if (snapshotSession === createdSession) snapshotSession = null
                    if (!continuation.isActive) return
                    continuation.resumeWithException(
                        SnapshotCaptureFailure(snapshotToolErrorCode(error)),
                    )
                }
            }
            val session = nexusSnapshotSession(callbacks)
            createdSession = session
            if (session == null) {
                continuation.resumeWithException(
                    SnapshotCaptureFailure(TOOL_ERROR_CAPTURE_FAILED),
                )
                return@suspendCancellableCoroutine
            }
            snapshotSession = session
            continuation.invokeOnCancellation {
                if (snapshotSession === session) snapshotSession = null
                session.cancel()
            }
            val result = session.capture()
            if (result != NexusSdkResult.SENT && continuation.isActive) {
                if (snapshotSession === session) snapshotSession = null
                session.cancel()
                continuation.resumeWithException(
                    SnapshotCaptureFailure(snapshotStartToolErrorCode(result)),
                )
            }
        }

    private fun prepareToolImage(jpeg: ByteArray): PreparedToolImage? {
        if (jpeg.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val sourceLongEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (sourceLongEdge / (sampleSize * 2) >= MAX_TOOL_IMAGE_LONG_EDGE_PX) {
            sampleSize *= 2
        }
        var bitmap = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        try {
            val decodedLongEdge = maxOf(bitmap.width, bitmap.height)
            if (decodedLongEdge > MAX_TOOL_IMAGE_LONG_EDGE_PX) {
                val scale = MAX_TOOL_IMAGE_LONG_EDGE_PX.toFloat() / decodedLongEdge
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            while (true) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, TOOL_IMAGE_JPEG_QUALITY, output)) {
                    return null
                }
                val bytes = output.toByteArray()
                if (bytes.size <= MAX_TOOL_IMAGE_JPEG_BYTES) {
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    if (base64.length > MAX_TOOL_IMAGE_BASE64_CHARS) return null
                    return PreparedToolImage(
                        jpeg = bytes,
                        base64 = base64,
                        byteCount = bytes.size,
                        width = bitmap.width,
                        height = bitmap.height,
                    )
                }

                val sizeScale =
                    (sqrt(MAX_TOOL_IMAGE_JPEG_BYTES.toDouble() / bytes.size) * 0.9)
                        .coerceIn(0.5, 0.9)
                val nextWidth = (bitmap.width * sizeScale).roundToInt().coerceAtLeast(1)
                val nextHeight = (bitmap.height * sizeScale).roundToInt().coerceAtLeast(1)
                if (nextWidth == bitmap.width && nextHeight == bitmap.height) return null
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    nextWidth,
                    nextHeight,
                    true,
                )
                if (scaled === bitmap) return null
                bitmap.recycle()
                bitmap = scaled
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun logToolOutcome(
        requestId: String?,
        toolName: String,
        outcome: String,
        byteCount: Int,
        width: Int,
        height: Int,
        captureMs: Long,
        processMs: Long,
        totalMs: Long,
    ) {
        Log.i(
            TAG,
            "tool requestId=${requestId ?: "none"} name=$toolName outcome=$outcome " +
                "bytes=$byteCount dimensions=${width}x$height " +
                "captureMs=$captureMs processMs=$processMs totalMs=$totalMs",
        )
    }

    private fun handleSpeechFailure(
        reason: NexusSpeechStopReason,
        error: NexusSpeechError?,
    ) {
        when (reason) {
            NexusSpeechStopReason.COMPLETED,
            NexusSpeechStopReason.NO_SPEECH,
            -> uiController.showError("Didn't catch that")
            NexusSpeechStopReason.CANCELLED -> Unit
            NexusSpeechStopReason.DENIED_BUSY ->
                uiController.showError("Speech is busy. Try again.")
            NexusSpeechStopReason.DENIED_NO_LINK,
            NexusSpeechStopReason.LINK_LOST,
            -> uiController.showError("Glasses microphone unavailable.")
            NexusSpeechStopReason.REVOKED ->
                uiController.showError("Speech access was revoked.")
            NexusSpeechStopReason.DENIED_NOT_READY,
            NexusSpeechStopReason.DENIED_START_FAILED,
            NexusSpeechStopReason.DENIED_INVALID,
            NexusSpeechStopReason.ERROR,
            -> uiController.showError("Speech recognition failed. Try again.")
        }
        if (reason != NexusSpeechStopReason.COMPLETED &&
            reason != NexusSpeechStopReason.NO_SPEECH &&
            reason != NexusSpeechStopReason.CANCELLED
        ) {
            Log.w(TAG, "Hub speech stopped: $reason (${error?.kind ?: "no detail"})")
        }
    }

    private fun showAnswer(text: String) {
        uiController.showAnswer(
            body = text,
            legacyCardLines = wrapHudText(text),
        )
    }

    private fun showError(message: String) {
        val concise = message
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_ERROR_CHARS)
            .ifBlank { "Request failed. Try again." }
        uiController.showError(
            body = concise,
            legacyCardLines = wrapHudText(concise, maxLines = 3),
        )
    }

    private fun renderCard(
        lines: List<String>,
        forceShow: Boolean,
    ): NexusSdkResult {
        val session = surface ?: return NexusSdkResult.NOT_REGISTERED
        val card = NexusCard(
            title = "Assistant",
            lines = lines.take(MAX_HUD_LINES).map { it.take(MAX_CARD_LINE_CHARS) },
            handlesBack = true,
        )
        return if (forceShow) {
            session.showCard(card)
        } else {
            session.updateCard(card)
        }
    }

    private fun cancelPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
        val activeSnapshot = snapshotSession
        snapshotSession = null
        activeSnapshot?.cancel()
        currentRequestId?.let { requestId ->
            openAiClient.cancel(requestId)
            chatGptCodexClient.cancel(requestId)
        }
        currentRequestId = null
        currentAssistantGeneration = null
        photoJpegForCompletedTurn = null
    }

    private fun resetCapture() {
        captureGeneration += 1
        captureActive = false
        fallbackTranscribePending = false
        fallbackStopJob?.cancel()
        fallbackStopJob = null
        val activeSpeech = speechSession
        speechSession = null
        activeSpeech?.stop()
        val activeAudio = audioSession
        audioSession = null
        activeAudio?.stop()
        audioFormat = null
        pcmBuffer = ByteArrayOutputStream()
    }

    private companion object {
        const val TAG = "NexusAssistant"
        const val SURFACE_ID = "assistant"
        const val AI_ASSIST_OPEN_PATH = "/system/plugin/ai-assist"
        const val AI_ASSIST_OPEN_TYPE = "ai_assist"
        const val FALLBACK_CAPTURE_DURATION_MS = 6_000L
        const val SNAPSHOT_CAPTURE_TIMEOUT_MS = 8_000L
        const val HUD_UPDATE_INTERVAL_MS = 250L
        const val MAX_TOOL_IMAGE_LONG_EDGE_PX = 1_024
        const val TOOL_IMAGE_JPEG_QUALITY = 80
        const val MAX_TOOL_IMAGE_BASE64_CHARS = 1_500_000
        const val MAX_TOOL_IMAGE_JPEG_BYTES = MAX_TOOL_IMAGE_BASE64_CHARS / 4 * 3
        const val MAX_HUD_LINES = 6
        const val MAX_HUD_LINE_CHARS = 42
        const val MAX_CARD_LINE_CHARS = 240
        const val MAX_ERROR_CHARS = 180
    }
}

private data class PreparedToolImage(
    val jpeg: ByteArray,
    val base64: String,
    val byteCount: Int,
    val width: Int,
    val height: Int,
)

private class SnapshotCaptureFailure(
    val code: String,
) : IllegalStateException()

internal fun shouldUseRawCaptureFallback(result: NexusSdkResult): Boolean =
    result == NexusSdkResult.CAPABILITY_NOT_GRANTED ||
        result == NexusSdkResult.CAPABILITY_NOT_AVAILABLE

internal fun shouldUseRawCaptureFallback(reason: NexusSpeechStopReason): Boolean =
    reason == NexusSpeechStopReason.DENIED_NOT_READY

internal fun snapshotErrorMessage(error: NexusSnapshotError): String = when (error) {
    NexusSnapshotError.BUSY -> "Camera is busy. Close Lens and try again."
    NexusSnapshotError.LINK_DOWN -> "Glasses camera is unavailable."
    NexusSnapshotError.TIMEOUT -> "Camera timed out. Try again."
    NexusSnapshotError.CAPTURE_FAILED -> "Camera capture failed. Try again."
    NexusSnapshotError.CANCELLED -> "Camera request was cancelled."
    NexusSnapshotError.ERROR -> "Camera request failed. Try again."
}

internal fun snapshotStartErrorMessage(result: NexusSdkResult): String = when (result) {
    NexusSdkResult.CAPABILITY_NOT_GRANTED -> "Grant Camera access in Nexus settings."
    NexusSdkResult.NOT_REGISTERED -> "Assistant is not connected to Nexus."
    else -> "Glasses camera is unavailable."
}

internal fun snapshotToolErrorCode(error: NexusSnapshotError): String = when (error) {
    NexusSnapshotError.BUSY -> TOOL_ERROR_CAMERA_BUSY
    NexusSnapshotError.LINK_DOWN -> TOOL_ERROR_GLASSES_DISCONNECTED
    NexusSnapshotError.CANCELLED -> TOOL_ERROR_CANCELLED
    NexusSnapshotError.TIMEOUT,
    NexusSnapshotError.CAPTURE_FAILED,
    NexusSnapshotError.ERROR,
    -> TOOL_ERROR_CAPTURE_FAILED
}

internal fun snapshotStartToolErrorCode(result: NexusSdkResult): String = when (result) {
    NexusSdkResult.CAPABILITY_NOT_GRANTED -> TOOL_ERROR_NOT_AUTHORIZED
    NexusSdkResult.NOT_REGISTERED -> TOOL_ERROR_GLASSES_DISCONNECTED
    NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> TOOL_ERROR_CAMERA_BUSY
    else -> TOOL_ERROR_CAPTURE_FAILED
}

private fun normalizeTranscript(text: String): String =
    text.replace(Regex("\\s+"), " ").trim()

internal fun wrapHudText(
    text: String,
    maxLines: Int = 6,
    maxLineChars: Int = 42,
): List<String> {
    require(maxLines > 0)
    require(maxLineChars > 1)
    val wrapped = mutableListOf<String>()
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .forEach { paragraph ->
            val words = paragraph.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (words.isEmpty()) return@forEach
            var line = ""
            words.forEach { word ->
                val pieces = if (word.length > maxLineChars) {
                    word.chunked(maxLineChars)
                } else {
                    listOf(word)
                }
                pieces.forEach { piece ->
                    val candidate = if (line.isEmpty()) piece else "$line $piece"
                    if (candidate.length <= maxLineChars) {
                        line = candidate
                    } else {
                        if (line.isNotEmpty()) wrapped += line
                        line = piece
                    }
                }
            }
            if (line.isNotEmpty()) wrapped += line
        }
    if (wrapped.isEmpty()) return listOf("…")
    if (wrapped.size <= maxLines) return wrapped
    return wrapped.take(maxLines).toMutableList().apply {
        val lastIndex = lastIndex
        this[lastIndex] = this[lastIndex].take(maxLineChars - 1).trimEnd() + "…"
    }
}
