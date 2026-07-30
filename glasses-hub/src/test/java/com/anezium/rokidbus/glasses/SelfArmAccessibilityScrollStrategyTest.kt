package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmAccessibilityScrollStrategyTest {
    private val window = SelfArmScreenBounds(0, 0, 480, 640)

    @Test
    fun `settings recycler wins over a larger generic container and another package`() {
        val tinySettingsRecycler = node(
            id = "com.android.settings:id/recycler_view",
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = SelfArmScreenBounds(0, 636, 480, 640),
            scrollable = true,
        )
        val genericSettingsContainer = node(
            id = "com.android.settings:id/main_content",
            className = "android.view.View",
            bounds = window,
            scrollable = true,
        )
        val foreignRecycler = node(
            packageName = "com.example.overlay",
            id = "com.example.overlay:id/recycler_view",
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = window,
            scrollable = true,
        )

        val selected = SelfArmAccessibilityScrollStrategy.selectBestScrollable(
            listOf(genericSettingsContainer, foreignRecycler, tinySettingsRecycler),
            window,
        )

        assertEquals(tinySettingsRecycler, selected)
    }

    @Test
    fun `selection ignores invisible empty and off-window scrollables`() {
        val invisible = node(bounds = window, visible = false, scrollable = true)
        val empty = node(bounds = SelfArmScreenBounds(0, 640, 480, 640), scrollable = true)
        val offWindow = node(
            bounds = SelfArmScreenBounds(0, 700, 480, 900),
            scrollable = true,
        )

        assertNull(
            SelfArmAccessibilityScrollStrategy.selectBestScrollable(
                listOf(invisible, empty, offWindow),
                window,
            ),
        )
    }

    @Test
    fun `bounds break ties between otherwise equivalent settings lists`() {
        val narrow = node(
            id = null,
            className = "android.widget.ScrollView",
            bounds = SelfArmScreenBounds(100, 100, 380, 500),
            scrollable = true,
        )
        val fullWidth = narrow.copy(bounds = SelfArmScreenBounds(0, 100, 480, 500))

        assertEquals(
            fullWidth,
            SelfArmAccessibilityScrollStrategy.selectBestScrollable(
                listOf(narrow, fullWidth),
                window,
            ),
        )
    }

    @Test
    fun `recycler class without an id beats a named generic content container`() {
        val generic = node(
            id = "com.android.settings:id/main_content",
            className = "android.view.View",
            bounds = window,
            scrollable = true,
        )
        val recycler = node(
            id = null,
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = SelfArmScreenBounds(0, 636, 480, 640),
            scrollable = true,
        )

        assertEquals(
            recycler,
            SelfArmAccessibilityScrollStrategy.selectBestScrollable(
                listOf(generic, recycler),
                window,
            ),
        )
    }

    @Test
    fun `four pixel developer options viewport anchors at 639 and uses settings window`() {
        val target = SelfArmScreenBounds(0, 636, 480, 640)

        val swipe = SelfArmAccessibilityScrollStrategy.forwardSwipe(target, window)

        assertNotNull(swipe)
        assertEquals(
            SelfArmAccessibilitySwipe(
                startX = 240,
                startY = 639,
                endX = 240,
                endY = 84,
                durationMs = 180L,
                usesWindowFallback = true,
            ),
            swipe,
        )
        assertTrue(target.contains(swipe!!.startX, swipe.startY))
        assertTrue(window.contains(swipe.endX, swipe.endY))
        assertTrue(swipe.startY - swipe.endY > 500)
    }

    @Test
    fun `normal viewport keeps both swipe endpoints inside the scroll target`() {
        val target = SelfArmScreenBounds(0, 180, 480, 640)

        val swipe = SelfArmAccessibilityScrollStrategy.forwardSwipe(target, window)!!

        assertFalse(swipe.usesWindowFallback)
        assertTrue(target.contains(swipe.startX, swipe.startY))
        assertTrue(target.contains(swipe.endX, swipe.endY))
        assertEquals(639, swipe.startY)
        assertEquals(180, swipe.endY)
    }

    @Test
    fun `backward swipe stays inside a normal settings recycler`() {
        val target = SelfArmScreenBounds(0, 319, 480, 640)

        val swipe = SelfArmAccessibilityScrollStrategy.backwardSwipe(target, window)!!

        assertFalse(swipe.usesWindowFallback)
        assertEquals(240, swipe.startX)
        assertEquals(319, swipe.startY)
        assertEquals(240, swipe.endX)
        assertEquals(639, swipe.endY)
        assertTrue(target.contains(swipe.startX, swipe.startY))
        assertTrue(target.contains(swipe.endX, swipe.endY))
    }

    @Test
    fun `four pixel viewport rejects backward gesture and relies on accessibility action`() {
        assertNull(
            SelfArmAccessibilityScrollStrategy.backwardSwipe(
                targetBounds = SelfArmScreenBounds(0, 636, 480, 640),
                windowBounds = window,
            ),
        )
    }

    @Test
    fun `swipe is rejected when upward travel cannot reach useful distance`() {
        assertNull(
            SelfArmAccessibilityScrollStrategy.forwardSwipe(
                targetBounds = SelfArmScreenBounds(0, 0, 480, 4),
                windowBounds = window,
            ),
        )
    }

    @Test
    fun `content signature is stable across traversal order and hides content`() {
        val title = node(
            id = "android:id/title",
            className = "android.widget.TextView",
            bounds = SelfArmScreenBounds(24, 120, 420, 170),
            content = "Wireless debugging",
        )
        val switch = node(
            id = "android:id/switch_widget",
            className = "android.widget.Switch",
            bounds = SelfArmScreenBounds(420, 120, 476, 170),
            content = "Off",
        )

        val first = SelfArmAccessibilityScrollStrategy.contentSignature(
            listOf(title, switch),
            window,
        )
        val reversed = SelfArmAccessibilityScrollStrategy.contentSignature(
            listOf(switch, title),
            window,
        )

        assertEquals(first, reversed)
        assertEquals(64, first!!.sha256.length)
        assertFalse(first.sha256.contains("Wireless", ignoreCase = true))
    }

    @Test
    fun `foreign and invisible nodes do not perturb settings signature`() {
        val settings = node(bounds = SelfArmScreenBounds(0, 100, 480, 200), content = "Settings")
        val foreign = settings.copy(packageName = "com.example.overlay", contentToken = "volatile")
        val invisible = settings.copy(visibleToUser = false, contentToken = "also volatile")

        val baseline = SelfArmAccessibilityScrollStrategy.contentSignature(listOf(settings), window)
        val noisy = SelfArmAccessibilityScrollStrategy.contentSignature(
            listOf(settings, foreign, invisible),
            window,
        )

        assertEquals(baseline, noisy)
    }

    @Test
    fun `before and after signatures identify movement and no progress`() {
        val beforeNode = node(
            bounds = SelfArmScreenBounds(0, 636, 480, 640),
            content = "Developer options",
            scrollable = true,
        )
        val afterNode = beforeNode.copy(bounds = SelfArmScreenBounds(0, 300, 480, 640))
        val before = SelfArmAccessibilityScrollStrategy.contentSignature(listOf(beforeNode), window)
        val same = SelfArmAccessibilityScrollStrategy.contentSignature(listOf(beforeNode), window)
        val after = SelfArmAccessibilityScrollStrategy.contentSignature(listOf(afterNode), window)

        assertEquals(
            SelfArmAccessibilityScrollProgress.NO_PROGRESS,
            SelfArmAccessibilityScrollStrategy.compareProgress(before, same),
        )
        assertNotEquals(before, after)
        assertEquals(
            SelfArmAccessibilityScrollProgress.MOVED,
            SelfArmAccessibilityScrollStrategy.compareProgress(before, after),
        )
        assertEquals(
            SelfArmAccessibilityScrollProgress.UNKNOWN,
            SelfArmAccessibilityScrollStrategy.compareProgress(null, after),
        )
    }

    @Test
    fun `signature is unknown without visible settings content`() {
        val foreign = node(packageName = "com.example", bounds = window)

        assertNull(SelfArmAccessibilityScrollStrategy.contentSignature(listOf(foreign), window))
        assertEquals(
            SelfArmAccessibilityScrollProgress.UNKNOWN,
            SelfArmAccessibilityScrollStrategy.compareProgress(null, null),
        )
    }

    private fun node(
        packageName: String = SelfArmAccessibilityScrollStrategy.SETTINGS_PACKAGE,
        id: String? = "com.android.settings:id/content",
        className: String? = "android.view.View",
        bounds: SelfArmScreenBounds,
        visible: Boolean = true,
        scrollable: Boolean = false,
        content: String? = null,
    ) = SelfArmAccessibilityNodeSnapshot(
        packageName = packageName,
        viewIdResourceName = id,
        className = className,
        bounds = bounds,
        visibleToUser = visible,
        scrollable = scrollable,
        contentToken = content,
    )
}
