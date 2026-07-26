package com.anezium.rokidbus.plugin.sample

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusImage
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class HelloPluginService : NexusPluginService() {
    private val state = HelloPluginState()
    private var surface: NexusSurfaceSession? = null
    private var speech: NexusSpeechSession? = null
    private var stopSpeechWhenStarted = false
    private var showingImage = false
    private val speechCallbacks = object : NexusSpeechCallbacks {
        override fun onSpeechStarted(realtime: Boolean) {
            val currentSpeech = speech ?: return
            if (stopSpeechWhenStarted || state.mode != HelloPluginMode.DICTATION_LIVE) {
                currentSpeech.stop()
                return
            }
            if (state.onSpeechStarted(realtime)) render(show = false)
        }

        override fun onSpeechState(state: NexusSpeechState) {
            if (this@HelloPluginService.state.onSpeechState(state)) render(show = false)
        }

        override fun onSpeechPartial(text: String) {
            if (state.onSpeechPartial(text)) render(show = false)
        }

        override fun onSpeechFinal(text: String) {
            if (state.onSpeechFinal(text)) render(show = false)
        }

        override fun onSpeechStopped(
            reason: NexusSpeechStopReason,
            error: NexusSpeechError?,
        ) {
            speech = null
            stopSpeechWhenStarted = false
            if (state.onSpeechStopped(reason, error)) render(show = false)
        }
    }

    override fun onNexusOpen() {
        state.resetToMenu()
        if (speech != null) {
            stopSpeechWhenStarted = true
            speech?.stop()
        } else {
            stopSpeechWhenStarted = false
        }
        surface = nexusSurfaceSession(SURFACE_ID)
        showingImage = showBundledImage()
        if (!showingImage) render(show = true)
    }

    override fun onNexusClose() {
        state.resetToMenu()
        stopSpeechWhenStarted = true
        speech?.stop()
        speech = null
        stopSpeechWhenStarted = false
        surface?.hide()
        surface = null
        showingImage = false
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                if (!state.move(1)) return
                showingImage = false
                render(show = false)
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> {
                if (!state.move(-1)) return
                showingImage = false
                render(show = false)
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                when (state.activate()) {
                    HelloPluginAction.RENDER -> Unit
                    HelloPluginAction.START_SPEECH -> startSpeech()
                    HelloPluginAction.STOP_SPEECH -> {
                        stopSpeechWhenStarted = true
                        speech?.stop()
                        return
                    }
                    else -> return
                }
                showingImage = false
                render(show = false)
            }
            KeyEvent.KEYCODE_BACK -> handleBack()
            else -> return
        }
    }

    private fun handleBack() {
        when (state.back()) {
            HelloPluginAction.HIDE_SURFACE -> surface?.hide()
            HelloPluginAction.STOP_SPEECH_AND_SHOW_MENU -> {
                stopSpeechWhenStarted = true
                speech?.stop()
                showingImage = false
                render(show = false)
            }
            HelloPluginAction.SHOW_MENU -> {
                showingImage = false
                render(show = false)
            }
            else -> Unit
        }
    }

    private fun startSpeech() {
        stopSpeechWhenStarted = false
        val newSpeech = nexusSpeechSession(speechCallbacks)
        if (newSpeech == null) {
            state.onSpeechStartResult(null)
            return
        }
        speech = newSpeech
        val result = newSpeech.start()
        state.onSpeechStartResult(result)
        if (result != NexusSdkResult.SENT) {
            speech = null
        }
    }

    private fun showBundledImage(): Boolean {
        if (nexusClient?.supportsImageSurface != true) return false
        val imageResource = resources.getIdentifier("image_surface_sample", "raw", packageName)
        if (imageResource == 0) return false
        val bytes = resources.openRawResource(imageResource).use { it.readBytes() }
        val image = NexusImage(
            contentKey = "sample-tree-v1",
            mimeType = ImageSurfaceContract.MIME_JPEG,
            pixelWidth = 480,
            pixelHeight = 480,
            title = "Hello Nexus image",
            caption = "Bundled JPEG over the SPP data plane",
            footer = "swipe for card demo · back",
            handlesBack = true,
        )
        return surface?.showImage(image, bytes) == NexusSdkResult.SENT
    }

    private fun render(show: Boolean) {
        val presentation = state.presentation()
        val card = NexusCard(
            title = presentation.title,
            lines = presentation.lines,
            footer = presentation.footer,
            contentKey = presentation.contentKey,
            handlesBack = presentation.handlesBack,
        )
        if (show) surface?.showCard(card) else surface?.updateCard(card)
    }

    private companion object {
        const val SURFACE_ID = "main"
    }
}
