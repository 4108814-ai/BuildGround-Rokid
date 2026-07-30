package com.anezium.rokidbus.glasses

internal object SelfArmSettingsNodePolicy {
    fun isActionTitle(
        visible: Boolean,
        enabled: Boolean,
        viewId: String?,
        className: String?,
        exactLabelMatch: Boolean,
        hasClickableAncestor: Boolean,
    ): Boolean {
        if (!visible || !enabled || !exactLabelMatch || !hasClickableAncestor) return false
        val id = viewId.orEmpty()
        val type = className.orEmpty()
        return id.endsWith(":id/title") ||
            id.endsWith("/title") ||
            (id.isBlank() && type.endsWith("TextView"))
    }

    fun isUsableToggle(
        visible: Boolean,
        enabled: Boolean,
        checkable: Boolean,
        clickable: Boolean,
    ): Boolean =
        visible && enabled && (checkable || clickable)

    fun isWifiScreen(
        settingsPackage: Boolean,
        appBarMatches: Boolean,
        switchTextMatches: Boolean,
    ): Boolean =
        settingsPackage && (appBarMatches || switchTextMatches)
}
