package com.anezium.rokidbus.plugin.sample

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusImage
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeImage
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusPin
import com.anezium.rokidbus.client.plugin.NexusPinEmphasis
import com.anezium.rokidbus.client.plugin.NexusPinLine
import com.anezium.rokidbus.client.plugin.NexusPinSize
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class HelloPluginService : NexusPluginService() {
    private fun log(message: String) = android.util.Log.i("ROKIDBUS", message)

    /**
     * Pushes the demo band after a delay, so a wake can be observed on a screen
     * that went dark on its own. Every other route into a notice starts with the
     * wearer tapping something, which lights the display before the notice can
     * ask for it — useless for judging what a notice does to a dark one.
     *
     *     adb shell am start-foreground-service \
     *       -n com.anezium.rokidbus.plugin.sample/.HelloPluginService \
     *       -a com.anezium.rokidbus.plugin.sample.DEMO_NOTICE --ei delayMs 12000
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DEMO_NOTICE) {
            val delayMs = intent.getIntExtra("delayMs", 10_000).toLong().coerceIn(0L, 120_000L)
            log("demo notice scheduled in ${delayMs}ms")
            Handler(Looper.getMainLooper()).postDelayed({
                log("demo notice push result=${nexusClient?.showNotice(DEMO_NOTICE_BAND)}")
            }, delayMs)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private val state = HelloPluginState()
    private var surface: NexusSurfaceSession? = null
    private var speech: NexusSpeechSession? = null
    private var stopSpeechWhenStarted = false
    private var showingImage = false
    private var pinStep = PIN_HIDDEN
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
        pinStep = PIN_HIDDEN
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
                    HelloPluginAction.RENDER -> cycleDemoHud()
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

    /**
     * The answer to a tap on a band that offers no choice. The demo notice
     * below carries actions, so this is here as the reference for the simpler
     * shape: one gesture, no row.
     */
    override fun onNexusNoticeInput(event: NexusInputEvent) {
        nexusClient?.updateNotice(NexusNoticeUpdate(footer = "Answered. Back to dismiss"))
    }

    /**
     * The wearer picked one of the band's answers, with no surface involved.
     * This is what the notice tier is for: until now a plugin could only be
     * reached while it held a screen open, and only ever with one answer.
     */
    override fun onNexusNoticeAction(id: String) {
        val label = DEMO_NOTICE_BAND.actions.firstOrNull { it.id == id }?.label ?: id
        nexusClient?.updateNotice(NexusNoticeUpdate(footer = "$label · Back to dismiss"))
    }

    /**
     * Walks the ambient HUD tiers: both pin sizes, then a notice, then nothing.
     * The notice needs no hide step of its own -- it expires on its own deadline,
     * which is the difference between the two tiers in one gesture.
     */
    /**
     * A brightness ramp, a frame and a disc, drawn rather than shipped as an
     * asset so the sample stays one file. The ramp is the useful part: on
     * additive monochrome optics it shows immediately which levels reach the
     * eye and which ones disappear into the background.
     */
    private fun demoImageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(
            DEMO_IMAGE_WIDTH,
            DEMO_IMAGE_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val steps = 8
        val stepWidth = DEMO_IMAGE_WIDTH / steps.toFloat()
        for (step in 0 until steps) {
            val level = 255 * (step + 1) / steps
            paint.color = Color.rgb(level, level, level)
            canvas.drawRect(
                step * stepWidth,
                0f,
                (step + 1) * stepWidth,
                DEMO_IMAGE_HEIGHT * 0.55f,
                paint,
            )
        }
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRect(
            1.5f,
            1.5f,
            DEMO_IMAGE_WIDTH - 1.5f,
            DEMO_IMAGE_HEIGHT - 1.5f,
            paint,
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(
            DEMO_IMAGE_WIDTH * 0.5f,
            DEMO_IMAGE_HEIGHT * 0.78f,
            DEMO_IMAGE_HEIGHT * 0.16f,
            paint,
        )
        val encoded = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, encoded)
        bitmap.recycle()
        return encoded.toByteArray()
    }

    private fun cycleDemoHud() {
        val client = nexusClient ?: return
        val next = (pinStep + 1) % 6
        val result = when (next) {
            PIN_SMALL -> client.showPin(SMALL_PIN)
            PIN_MEDIUM -> client.showPin(MEDIUM_PIN)
            DEMO_NOTICE -> {
                client.hidePin()
                client.showNotice(DEMO_NOTICE_BAND)
            }
            DEMO_NOTICE_PAGED -> client.showNotice(DEMO_NOTICE_LONG)
            DEMO_NOTICE_IMAGE -> client.showNotice(DEMO_NOTICE_PICTURE, demoImageBytes())
            else -> client.hidePin()
        }
        if (result != NexusSdkResult.SENT) log("HUD demo step $next refused: $result")
        // Advance whatever happened. A step that could not go out leaves nothing on
        // screen, and a reference plugin that wedges on it is worse than one that
        // moves to the next thing on the next press.
        pinStep = next
    }

    private companion object {
        const val ACTION_DEMO_NOTICE = "com.anezium.rokidbus.plugin.sample.DEMO_NOTICE"
        const val SURFACE_ID = "main"
        const val PIN_HIDDEN = 0
        const val PIN_SMALL = 1
        const val PIN_MEDIUM = 2
        const val DEMO_NOTICE = 3
        const val DEMO_NOTICE_PAGED = 4
        const val DEMO_NOTICE_IMAGE = 5

        /**
         * A band with more to say than one page holds. It offers no actions on
         * purpose: a notice is paged or it is answerable, never both, so this is
         * also the demo of what forward and backward mean when nothing is being
         * asked. The glasses decide how many pages this is -- they are the only
         * side that knows how wide a line runs.
         */
        val DEMO_NOTICE_LONG = NexusNotice(
            title = "Long notice",
            body = "This band carries more text than a single page can hold, which " +
                "is the whole point of it: a relayed message is as long as whoever " +
                "wrote it decided, and a tier that only ever showed the first four " +
                "lines was not relaying anything. Scroll forward to turn the page " +
                "and backward to go back. The first turn ends the countdown, so " +
                "the band now waits on the reader instead of on a deadline, and " +
                "leaves on its own once the gestures stop. Nothing here scrolls: " +
                "each page replaces the one before it, because a HUD that scrolls " +
                "asks the wearer to aim at something while walking.",
            footer = "Scroll to turn pages",
        )

        /**
         * The picture rides in the same message as the text, so the band cannot
         * appear before its image. Deliberately a test card rather than a photo:
         * the optics are additive and monochrome, and a brightness ramp shows
         * what actually survives that far better than a nice picture does.
         */
        val DEMO_NOTICE_PICTURE = NexusNotice(
            title = "Notice with an image",
            body = "The picture and this text arrived as one message.",
            footer = "Back to dismiss",
            image = NexusNoticeImage(
                contentKey = "sample-test-card-1",
                mimeType = ImageSurfaceContract.MIME_JPEG,
                pixelWidth = DEMO_IMAGE_WIDTH,
                pixelHeight = DEMO_IMAGE_HEIGHT,
            ),
        )

        const val DEMO_IMAGE_WIDTH = 480
        const val DEMO_IMAGE_HEIGHT = 160

        /**
         * A band that asks something. Scroll steps along the answers, tap fires
         * the selected one, and Back still dismisses the whole thing without
         * the plugin ever hearing about it.
         *
         * No `interactive` flag: carrying actions is already asking for an
         * answer. The full TTL, because a band worth choosing from is a band
         * worth being able to read first.
         */
        val DEMO_NOTICE_BAND = NexusNotice(
            title = "Nexus notice",
            body = "A band that arrives, asks one thing, and leaves on its own deadline.",
            footer = "Scroll to choose · Back to dismiss",
            actions = listOf(
                NexusNoticeAction(id = "reply", glyph = "phone", label = "Reply"),
                NexusNoticeAction(id = "later", glyph = "timer", label = "Later"),
                NexusNoticeAction(id = "ignore", glyph = "stop", label = "Ignore"),
            ),
            ttlMs = NoticeSurfaceContract.MAX_TTL_MS,
            // The demo band is the one notice a wearer asks for deliberately, so
            // it is also the honest place to show what the opt-in does on a dark
            // display. The hub still owns whether it is granted.
            wakeDisplay = true,
        )

        /** No `ttlMs` of its own, so it takes the hub's 30-minute default. */
        val SMALL_PIN = NexusPin(
            title = "NEXUS PIN",
            lines = listOf("sample overlay"),
        )

        /**
         * Shows the three emphasis levels, and carries a short `ttlMs` so the demo
         * also exercises self-expiry — the only thing bounding a pin whose owner
         * pushed it and went dormant without ever coming back to hide it.
         */
        val MEDIUM_PIN = NexusPin(
            title = "NEXUS PIN · MEDIUM",
            size = NexusPinSize.MEDIUM,
            ttlMs = 20_000L,
            richLines = listOf(
                NexusPinLine("bright headline row", NexusPinEmphasis.BRIGHT),
                NexusPinLine("default body row"),
                NexusPinLine("clears itself in 20s", NexusPinEmphasis.DIM),
            ),
        )
    }
}
