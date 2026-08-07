package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi
import kotlin.math.abs

/** Fullscreen screenshot viewer: swipe to move between captures, tap to leave. */
class StoreScreenshotViewerActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var counter: TextView
    private lateinit var iconLoader: StoreIconLoader
    private lateinit var urls: List<String>
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        if (urls.isEmpty()) {
            finish()
            return
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.lastIndex)
        iconLoader = StoreIconLoader(applicationContext)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Screenshot"
        }
        counter = NexusUi.metaLabel(this, "", NexusUi.INK3).apply {
            textSize = 11f
            letterSpacing = 0.16f
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                image,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            if (urls.size > 1) {
                addView(
                    counter,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                    ).apply { bottomMargin = NexusUi.dp(this@StoreScreenshotViewerActivity, 34) },
                )
            }
        }
        setContentView(root)

        val gestures = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                // Consuming DOWN is what routes the rest of the gesture here.
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    finish()
                    return true
                }

                override fun onFling(
                    down: MotionEvent?,
                    up: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (abs(velocityX) < abs(velocityY)) return false
                    show(if (velocityX < 0) index + 1 else index - 1)
                    return true
                }
            },
        )
        root.setOnTouchListener { view, event ->
            gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        show(index)
    }

    private fun show(target: Int) {
        val next = target.coerceIn(0, urls.lastIndex)
        index = next
        counter.text = "${index + 1} / ${urls.size}"
        val url = urls[index]
        image.tag = url
        image.setImageDrawable(null)
        iconLoader.load(url) { bitmap ->
            if (isFinishing || isDestroyed || image.tag != url) return@load
            image.setImageBitmap(bitmap)
        }
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_INDEX = "index"

        fun intent(context: Context, urls: List<String>, index: Int): Intent =
            Intent(context, StoreScreenshotViewerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                .putExtra(EXTRA_INDEX, index)
    }
}
