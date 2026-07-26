package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

/**
 * Spike driver for HUD motion. Not a product surface: it exists so the
 * animation vocabulary can be filmed through the optics and measured on the
 * real overlay window before any of it is wired to a protocol.
 *
 * What it deliberately does the production way:
 *  - a single `TYPE_ACCESSIBILITY_OVERLAY` window sized to the union of every
 *    state, animating child bounds inside it. Animating the window's own
 *    layout params is the documented way to make this hardware flicker.
 *  - one number drives an entire morph. Bounds and content crossfade are
 *    lerped from the same progress value, so they cannot drift apart and a
 *    retarget mid-flight resumes from wherever the eye already is.
 *
 * What is faked, and would be real later: the waveform is fed a synthetic
 * speech envelope rather than the microphone. The question here is whether a
 * ~30 Hz custom redraw holds inside an accessibility overlay, and a real mic
 * would only make that harder to reproduce frame for frame.
 */
object MotionSpikeRenderer {

    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var panel: SpikePanelView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var chip = Rect()
    private var banner = Rect()
    private var loops = false
    private var running: String? = null

    /** 0 = chip at its anchor, 1 = full banner. Drives bounds and crossfade. */
    private val morph = HudMotionValue(0f) { progress -> applyMorph(progress) }

    /** Vertical offset in px used for arrivals and exits, applied on top. */
    private val slide = HudMotionValue(0f) { offset ->
        panel?.translationY = offset
    }

    private val fade = HudMotionValue(0f) { alpha ->
        panel?.alpha = alpha
    }

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        stop()
        this.service = null
        windowManager = null
    }

    fun play(sequence: String): String {
        val activeService = service ?: return "motion spike unavailable: accessibility service not connected"
        if (sequence == "off" || sequence == "stop") {
            stop()
            return "motion spike stopped"
        }
        val known = sequence in setOf("relay", "taxi", "taxi-scale", "pulse", "loop")
        if (!known) {
            return "unknown motion sequence '$sequence'; use relay, taxi, taxi-scale, pulse, loop, or off"
        }
        ensureWindow(activeService)
        handler.removeCallbacksAndMessages(null)
        loops = sequence == "loop"
        runSequence(if (loops) "relay" else sequence)
        return "motion spike playing '$sequence'"
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        loops = false
        running = null
        morph.cancel()
        slide.cancel()
        fade.cancel()
        HudFrameMeter.stop()
        val root = container ?: return
        runCatching { windowManager?.removeView(root) }
            .onFailure { logError("Motion spike window removal failed", it) }
        container = null
        panel = null
    }

    // ---------------------------------------------------------------- window

    private fun ensureWindow(service: AccessibilityService) {
        if (container != null) return
        val manager = windowManager
            ?: service.getSystemService(WindowManager::class.java)
            ?: return
        val root = FrameLayout(service)
        val view = SpikePanelView(service)
        root.addView(view, FrameLayout.LayoutParams(0, 0))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (runCatching { manager.addView(root, params) }.isFailure) {
            logError("Motion spike window could not be added")
            return
        }
        container = root
        panel = view
        computeGeometry(service)
        view.alpha = 0f
    }

    private fun computeGeometry(context: Context) {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val margin = BusTheme.dp(context, EDGE_MARGIN_DP)

        val chipWidth = (screenWidth * CHIP_WIDTH_FRACTION).toInt()
        val chipHeight = BusTheme.dp(context, CHIP_HEIGHT_DP)
        chip = Rect(
            screenWidth - margin - chipWidth,
            screenHeight - margin - chipHeight,
            screenWidth - margin,
            screenHeight - margin,
        )

        val bannerWidth = (screenWidth * BANNER_WIDTH_FRACTION).toInt()
        val bannerHeight = BusTheme.dp(context, BANNER_HEIGHT_DP)
        val bannerLeft = (screenWidth - bannerWidth) / 2
        banner = Rect(bannerLeft, margin, bannerLeft + bannerWidth, margin + bannerHeight)
    }

    private fun applyMorph(progress: Float) {
        val view = panel ?: return
        val left = lerp(chip.left, banner.left, progress)
        val top = lerp(chip.top, banner.top, progress)
        val width = lerp(chip.width(), banner.width(), progress)
        val height = lerp(chip.height(), banner.height(), progress)
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.width = width
        params.height = height
        params.leftMargin = left
        params.topMargin = top
        view.layoutParams = params
        view.onMorph(progress)
    }

    // -------------------------------------------------------------- sequences

    private fun runSequence(sequence: String) {
        running = sequence
        HudFrameMeter.start(sequence)
        when (sequence) {
            "relay" -> playRelay()
            "taxi" -> playFlare(scaled = false)
            "taxi-scale" -> playFlare(scaled = true)
            "pulse" -> playPulse()
        }
    }

    private fun finish(next: String?, gapMs: Long = INTER_SEQUENCE_GAP_MS) {
        HudFrameMeter.stop()
        if (!loops) {
            running = null
            return
        }
        handler.postDelayed({ runSequence(next ?: "relay") }, gapMs)
    }

    /**
     * The Relay reply, end to end: banner arrives, hands over to the waveform
     * while the wearer speaks, settles into the transcript, and leaves on send.
     */
    private fun playRelay() {
        val view = panel ?: return
        morph.snapTo(1f)
        view.showMessage(
            "Marie",
            "Je suis en route, dix minutes. Tu as toujours besoin du chargeur ?",
            "Tap pour répondre · Retour pour ignorer",
        )
        slide.snapTo(-banner.height().toFloat())
        fade.snapTo(0f)

        slide.animateTo(0f, HudMotion.STANDARD_MS, HudMotion.enter)
        fade.animateTo(1f, HudMotion.STANDARD_MS, HudMotion.enter)

        handler.postDelayed({
            view.showListening("J'écoute…")
            startWaveform()
        }, RELAY_READ_MS)

        var elapsed = RELAY_READ_MS + RELAY_SPEAK_MS
        handler.postDelayed({
            stopWaveform()
            // The wearer's own words come back bright: this is the thing they
            // are about to send, not someone else's message.
            view.showTranscript("Marie", "Ok j'arrive dans cinq minutes")
        }, elapsed)

        // The retry window, counted out loud. Three seconds of "you can still
        // take this back" is the whole reason the flow needs no confirmation
        // screen, so it should be visible rather than implied.
        for (remaining in 3 downTo 1) {
            handler.postDelayed({ view.setCountdown(remaining) }, elapsed)
            elapsed += RELAY_COUNTDOWN_STEP_MS
        }

        handler.postDelayed({ view.showSent() }, elapsed)
        elapsed += RELAY_SENT_HOLD_MS

        handler.postDelayed({
            slide.animateTo(-banner.height().toFloat(), HudMotion.EXIT_MS, HudMotion.exit)
            fade.animateTo(0f, HudMotion.EXIT_MS, HudMotion.exit) { finish("taxi") }
        }, elapsed)
    }

    /**
     * The taxi flare: a chip that has been sitting in the corner grows into the
     * banner because something happened, holds, and collapses back.
     *
     * [scaled] swaps real bounds animation for scale plus crossfade. Same
     * intent, a fraction of the cost, and blurrier text — which of the two the
     * optics can actually tell apart is the point of having both.
     */
    private fun playFlare(scaled: Boolean) {
        val view = panel ?: return
        view.scaleMorph = scaled
        morph.snapTo(0f)
        view.showChip("Taxi", "8 min")
        slide.snapTo(0f)
        fade.snapTo(0f)
        fade.animateTo(1f, HudMotion.STANDARD_MS, HudMotion.enter)

        handler.postDelayed({
            view.showMessage("Votre taxi est arrivé", "Mercedes Classe E · GP-482-KR", "Il vous attend 2 min")
            morph.animateTo(1f, HudMotion.STANDARD_MS, HudMotion.enter)
        }, FLARE_CHIP_HOLD_MS)

        handler.postDelayed({
            view.showChip("Taxi", "Arrivé")
            morph.animateTo(0f, HudMotion.EXIT_MS, HudMotion.exit)
        }, FLARE_CHIP_HOLD_MS + HudMotion.HOLD_MS)

        handler.postDelayed({
            fade.animateTo(0f, HudMotion.EXIT_MS, HudMotion.exit) {
                view.scaleMorph = false
                finish(if (scaled) "pulse" else "pulse")
            }
        }, FLARE_CHIP_HOLD_MS + HudMotion.HOLD_MS + HudMotion.EXIT_MS + FLARE_TAIL_MS)
    }

    /** A number ticking over in the corner. The cheapest motion we have. */
    private fun playPulse() {
        val view = panel ?: return
        morph.snapTo(0f)
        view.showChip("Taxi", "8 min")
        slide.snapTo(0f)
        fade.snapTo(0f)
        fade.animateTo(1f, HudMotion.STANDARD_MS, HudMotion.enter)

        val values = listOf("7 min", "6 min", "4 min", "3 min", "1 min")
        values.forEachIndexed { index, value ->
            handler.postDelayed({
                view.setChipValue(value)
                view.pulseValue()
            }, PULSE_FIRST_MS + index * PULSE_INTERVAL_MS)
        }

        handler.postDelayed({
            fade.animateTo(0f, HudMotion.EXIT_MS, HudMotion.exit) { finish("relay") }
        }, PULSE_FIRST_MS + values.size * PULSE_INTERVAL_MS)
    }

    // -------------------------------------------------------------- waveform

    private var waveformSeed = 0x5DEECE66DL
    private var waveformElapsed = 0L
    private val waveformTick = object : Runnable {
        override fun run() {
            val view = panel ?: return
            view.pushAmplitude(nextAmplitude())
            waveformElapsed += WAVEFORM_TICK_MS
            handler.postDelayed(this, WAVEFORM_TICK_MS)
        }
    }

    private fun startWaveform() {
        waveformElapsed = 0L
        waveformSeed = 0x5DEECE66DL
        panel?.resetWaveform()
        handler.post(waveformTick)
    }

    private fun stopWaveform() {
        handler.removeCallbacks(waveformTick)
    }

    /**
     * A deterministic stand-in for speech: bursts of a few hundred ms with
     * short gaps between them, jittered so it does not read as a metronome.
     * Deterministic matters — every film of this sequence is the same film.
     */
    private fun nextAmplitude(): Float {
        waveformSeed = (waveformSeed * 0x5DEECE66DL + 0xB) and ((1L shl 48) - 1)
        val jitter = ((waveformSeed shr 20) and 0xFFF).toFloat() / 0xFFF.toFloat()
        val phase = waveformElapsed % WORD_CYCLE_MS
        if (phase > WORD_VOICED_MS) return jitter * 0.05f
        val shape = kotlin.math.sin(Math.PI * phase / WORD_VOICED_MS).toFloat()
        return (shape * (0.55f + jitter * 0.45f)).coerceIn(0f, 1f)
    }

    private fun lerp(from: Int, to: Int, progress: Float): Int =
        (from + (to - from) * progress).toInt()

    // ------------------------------------------------------------------ view

    private class SpikePanelView(context: Context) : LinearLayout(context) {

        private val title = row(bold = true, sizeSp = 14f)
        private val body = row(bold = false, sizeSp = 12f).apply { maxLines = 2; isSingleLine = false }
        private val footer = row(bold = false, sizeSp = 10f)
        private val waveform = HudWaveformView(context)

        /** When true the flare scales instead of re-laying out its bounds. */
        var scaleMorph = false

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val horizontal = BusTheme.dp(context, 8)
            val vertical = BusTheme.dp(context, 6)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // Pure black. The optics emit nothing for black, so the fill
                // reads as transparent and the border is what the eye tracks
                // through a morph.
                setColor(0xFF000000.toInt())
                setStroke(BusTheme.dp(context, 1), BusTheme.hairline)
                cornerRadius = BusTheme.dp(context, 7).toFloat()
            }
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                body,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 3)
                },
            )
            addView(
                waveform,
                LayoutParams(LayoutParams.MATCH_PARENT, BusTheme.dp(context, 22)).apply {
                    topMargin = BusTheme.dp(context, 4)
                },
            )
            addView(
                footer,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 3)
                },
            )
            waveform.visibility = GONE
            footer.visibility = GONE
            // Both rows are full width but their text is left-aligned, so a
            // centre-pivot pulse would slide the words sideways instead of
            // growing them where they sit.
            body.pivotX = 0f
            footer.pivotX = 0f
        }

        fun showChip(label: String, value: String) {
            title.text = label
            title.textSize = 10f
            body.text = value
            body.textSize = 15f
            body.setTextColor(BusTheme.phosphor)
            body.visibility = VISIBLE
            waveform.visibility = GONE
            footer.visibility = GONE
        }

        fun setChipValue(value: String) {
            body.text = value
        }

        fun pulseValue() {
            HudMotion.pulse(body)
        }

        fun showMessage(titleText: String, bodyText: String, footerText: String?) {
            title.text = titleText
            title.textSize = 14f
            body.text = bodyText
            body.textSize = 12f
            body.setTextColor(BusTheme.muted)
            body.visibility = VISIBLE
            waveform.visibility = GONE
            setFooter(footerText)
        }

        fun showListening(footerText: String) {
            body.visibility = GONE
            waveform.visibility = VISIBLE
            setFooter(footerText)
        }

        /** The wearer's own dictated words, read back for approval. */
        fun showTranscript(titleText: String, text: String) {
            title.text = titleText
            title.textSize = 14f
            body.text = text
            body.textSize = 13f
            body.setTextColor(BusTheme.phosphor)
            body.visibility = VISIBLE
            waveform.visibility = GONE
        }

        fun setCountdown(remaining: Int) {
            setFooter("Envoi dans $remaining")
            HudMotion.pulse(footer, peak = 1.06f)
        }

        /**
         * The send has to land. A 10sp muted "sent" is a whisper at the one
         * moment the wearer is waiting for an answer, so the resolution takes
         * over the bright role and the message they just sent steps back.
         */
        fun showSent() {
            body.setTextColor(BusTheme.muted)
            setFooter("✓ Envoyé", bright = true)
            HudMotion.pulse(footer)
        }

        fun setFooter(footerText: String?, bright: Boolean = false) {
            footer.text = footerText.orEmpty()
            footer.textSize = if (bright) 14f else 10f
            footer.setTextColor(if (bright) BusTheme.phosphor else BusTheme.muted)
            footer.typeface = Typeface.create(
                Typeface.MONOSPACE,
                if (bright) Typeface.BOLD else Typeface.NORMAL,
            )
            footer.visibility = if (footerText.isNullOrEmpty()) GONE else VISIBLE
        }

        fun pushAmplitude(amplitude: Float) {
            waveform.push(amplitude)
        }

        fun resetWaveform() {
            waveform.reset()
        }

        /**
         * Called on every frame of a morph. The content crossfade is driven by
         * the same progress value as the bounds, which is what stops the text
         * from arriving before the box it lives in.
         */
        fun onMorph(progress: Float) {
            title.alpha = progress.coerceIn(0.35f, 1f)
            body.alpha = 1f
            footer.alpha = progress
            if (scaleMorph) {
                pivotX = width.toFloat()
                pivotY = height.toFloat()
                val scale = 0.7f + 0.3f * progress
                scaleX = scale
                scaleY = scale
            } else {
                scaleX = 1f
                scaleY = 1f
            }
        }

        private fun row(bold: Boolean, sizeSp: Float) =
            TextView(context).apply {
                setTextColor(if (bold) BusTheme.phosphor else BusTheme.muted)
                textSize = sizeSp
                typeface = Typeface.create(
                    Typeface.MONOSPACE,
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
    }

    private const val EDGE_MARGIN_DP = 12
    private const val CHIP_WIDTH_FRACTION = 0.42f
    private const val CHIP_HEIGHT_DP = 46
    private const val BANNER_WIDTH_FRACTION = 0.86f
    private const val BANNER_HEIGHT_DP = 84

    private const val RELAY_READ_MS = 1_600L
    private const val RELAY_SPEAK_MS = 3_400L
    private const val RELAY_COUNTDOWN_STEP_MS = 800L
    private const val RELAY_SENT_HOLD_MS = 1_100L

    private const val FLARE_CHIP_HOLD_MS = 1_400L
    private const val FLARE_TAIL_MS = 600L

    private const val PULSE_FIRST_MS = 900L
    private const val PULSE_INTERVAL_MS = 1_100L

    private const val WAVEFORM_TICK_MS = 33L
    private const val WORD_CYCLE_MS = 520L
    private const val WORD_VOICED_MS = 380L

    private const val INTER_SEQUENCE_GAP_MS = 1_500L
}
