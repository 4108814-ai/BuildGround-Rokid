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
    fun `wifi screen requires the launched route and a stable resource id`() {
        assertFalse(
            SelfArmSettingsNodePolicy.isWifiScreen(
                settingsPackage = true,
                openedByAutomator = false,
                wifiActivityObserved = true,
                hasStableToggleId = true,
            ),
        )
        assertFalse(
            SelfArmSettingsNodePolicy.isWifiScreen(
                settingsPackage = false,
                openedByAutomator = true,
                wifiActivityObserved = true,
                hasStableToggleId = true,
            ),
        )
        assertTrue(
            SelfArmSettingsNodePolicy.isWifiScreen(
                settingsPackage = true,
                openedByAutomator = true,
                wifiActivityObserved = true,
                hasStableToggleId = true,
            ),
        )
        assertTrue(SelfArmSettingsNodePolicy.isWifiToggleId("com.android.settings:id/switch_text"))
        assertFalse(SelfArmSettingsNodePolicy.isWifiToggleId("com.android.settings:id/title"))
        assertTrue(
            SelfArmSettingsNodePolicy.isWifiActivityClass(
                "com.android.settings.Settings\$WifiSettingsActivity",
            ),
        )
        assertFalse(
            SelfArmSettingsNodePolicy.isWifiActivityClass(
                "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity",
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
