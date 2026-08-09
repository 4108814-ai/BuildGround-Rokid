package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.ColorFilter
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.anezium.rokidbus.ink.RenderNode
import kotlin.math.abs

internal class InkLottieView(
    context: Context,
    private val palette: InkColorPalette,
    private val frameGate: InkFrameGate,
) : FrameLayout(context), InkAnimatedLeaf, InkFrameClient {
    private val player = LottieAnimationView(context)
    private var source = ""
    private var autoPlay = true
    private var loop = true
    private var speed = 1f
    private var manualProgress: Float? = null
    private var durationNanos = 0L
    private var playbackNanos = 0L
    private var lastFrameNanos = 0L
    private var explicitlyVisible = true

    init {
        setBackgroundColor(palette.black)
        addView(player, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        player.cancelAnimation()
        player.addLottieOnCompositionLoadedListener { composition ->
            durationNanos = (composition.duration * 1_000_000.0).toLong().coerceAtLeast(1L)
            applyPlaybackState()
        }
        player.addValueCallback(
            KeyPath("**"),
            LottieProperty.COLOR_FILTER,
            LottieValueCallback<ColorFilter>(SimpleColorFilter(palette.phosphor)),
        )
    }

    fun updateNode(node: RenderNode) {
        val nextSource = node.attributes["src"]?.toString().orEmpty()
        autoPlay = node.attributes["auto-play"] as? Boolean ?: true
        loop = node.attributes["loop"] as? Boolean ?: true
        speed = ((node.attributes["speed"] as? Number)?.toFloat() ?: 1f).coerceIn(-4f, 4f)
        manualProgress = (node.attributes["progress"] as? Number)?.toFloat()?.coerceIn(0f, 1f)
        if (nextSource != source) {
            stopRecurring()
            source = nextSource
            playbackNanos = if (speed < 0f) durationNanos else 0L
            durationNanos = 0L
            if (source.isBlank()) {
                player.clearAnimation()
            } else {
                player.setAnimationFromJson(source, null)
            }
        }
        applyPlaybackState()
        contentDescription = "Ink Lottie animation"
    }

    override fun onInkFrame(frameTimeNanos: Long): Boolean {
        if (!shouldRun() || durationNanos <= 0L) return false
        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameTimeNanos
            return true
        }
        val elapsed = frameTimeNanos - lastFrameNanos
        lastFrameNanos = frameTimeNanos
        playbackNanos += (elapsed * speed).toLong()
        if (loop) {
            playbackNanos = ((playbackNanos % durationNanos) + durationNanos) % durationNanos
        } else if (playbackNanos !in 0..durationNanos) {
            playbackNanos = playbackNanos.coerceIn(0L, durationNanos)
            player.progress = playbackNanos.toFloat() / durationNanos
            return false
        }
        player.progress = playbackNanos.toFloat() / durationNanos
        return true
    }

    override fun onInkVisibilityChanged(visible: Boolean) {
        explicitlyVisible = visible
        applyPlaybackState()
    }

    override fun cancelInkAnimation() {
        stopRecurring()
        player.cancelAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyPlaybackState()
    }

    override fun onDetachedFromWindow() {
        cancelInkAnimation()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        applyPlaybackState()
    }

    private fun applyPlaybackState() {
        player.cancelAnimation()
        manualProgress?.let {
            stopRecurring()
            player.progress = it
            playbackNanos = (durationNanos * it).toLong()
            return
        }
        if (shouldRun() && durationNanos > 0L && abs(speed) > 0f) {
            frameGate.add(this)
        } else {
            stopRecurring()
        }
    }

    private fun shouldRun(): Boolean = autoPlay && explicitlyVisible && isAttachedToWindow && windowVisibility == VISIBLE

    private fun stopRecurring() {
        frameGate.remove(this)
        lastFrameNanos = 0L
    }
}
