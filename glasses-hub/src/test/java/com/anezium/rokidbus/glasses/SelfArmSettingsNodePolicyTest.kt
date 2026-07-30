package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmSettingsNodePolicyTest {
    @Test
    fun `action requires exact visible enabled title with clickable row`() {
        val eligible = SelfArmSettingsNodePolicy.isActionTitle(
            visible = true,
            enabled = true,
            viewId = "android:id/title",
            className = "android.widget.TextView",
            exactLabelMatch = true,
            hasClickableAncestor = true,
        )

        assertTrue(eligible)
        assertFalse(eligibleAction(visible = false))
        assertFalse(eligibleAction(enabled = false))
        assertFalse(eligibleAction(exactLabelMatch = false))
        assertFalse(eligibleAction(hasClickableAncestor = false))
    }

    @Test
    fun `summary containing target is rejected even when clickable`() {
        assertFalse(
            SelfArmSettingsNodePolicy.isActionTitle(
                visible = true,
                enabled = true,
                viewId = "android:id/summary",
                className = "android.view.View",
                exactLabelMatch = false,
                hasClickableAncestor = true,
            ),
        )
    }

    @Test
    fun `toggle ignores hidden disabled and inert duplicate ids`() {
        assertTrue(
            SelfArmSettingsNodePolicy.isUsableToggle(
                visible = true,
                enabled = true,
                checkable = true,
                clickable = false,
            ),
        )
        assertFalse(
            SelfArmSettingsNodePolicy.isUsableToggle(
                visible = false,
                enabled = true,
                checkable = true,
                clickable = true,
            ),
        )
        assertFalse(
            SelfArmSettingsNodePolicy.isUsableToggle(
                visible = true,
                enabled = false,
                checkable = true,
                clickable = true,
            ),
        )
        assertFalse(
            SelfArmSettingsNodePolicy.isUsableToggle(
                visible = true,
                enabled = true,
                checkable = false,
                clickable = false,
            ),
        )
    }

    @Test
    fun `generic settings main switch is not enough to identify wifi`() {
        assertFalse(
            SelfArmSettingsNodePolicy.isWifiScreen(
                settingsPackage = true,
                appBarMatches = false,
                switchTextMatches = false,
            ),
        )
        assertFalse(
            SelfArmSettingsNodePolicy.isWifiScreen(
                settingsPackage = false,
                appBarMatches = true,
                switchTextMatches = true,
            ),
        )
        assertTrue(
            SelfArmSettingsNodePolicy.isWifiScreen(
                settingsPackage = true,
                appBarMatches = false,
                switchTextMatches = true,
            ),
        )
    }

    private fun eligibleAction(
        visible: Boolean = true,
        enabled: Boolean = true,
        exactLabelMatch: Boolean = true,
        hasClickableAncestor: Boolean = true,
    ): Boolean =
        SelfArmSettingsNodePolicy.isActionTitle(
            visible = visible,
            enabled = enabled,
            viewId = "android:id/title",
            className = "android.widget.TextView",
            exactLabelMatch = exactLabelMatch,
            hasClickableAncestor = hasClickableAncestor,
        )
}
