package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal object AccessibilityWindowRoots {
    const val SETTINGS_PACKAGE = "com.android.settings"

    private const val ROKID_SYSCONFIG_PACKAGE = "com.rokid.sysconfig"
    private var lastApplicationPackage = ""

    fun noteEvent(event: AccessibilityEvent?, ownPackage: String) {
        if (event != null) rememberPackage(event.packageName, ownPackage)
    }

    fun getNavigationRoot(
        service: AccessibilityService,
        preferredPackage: String? = null,
    ): AccessibilityNodeInfo? {
        val activeRoot = service.rootInActiveWindow
        val activeRootIsReadable =
            activeRoot != null &&
                !isTinyRoot(activeRoot)
        val activeRootMatchesPreference =
            preferredPackage == null ||
                isPackage(activeRoot, preferredPackage)
        if (
            activeRootIsReadable &&
            activeRootMatchesPreference &&
            !hasTinyFocusedSystemWindow(service)
        ) {
            rememberRoot(activeRoot, service.packageName)
            return activeRoot
        }

        val windowRoot = bestApplicationRoot(service, preferredPackage)
        if (windowRoot != null) {
            activeRoot?.recycle()
            return windowRoot
        }

        if (activeRootIsReadable && activeRootMatchesPreference) {
            rememberRoot(activeRoot, service.packageName)
            return activeRoot
        }
        if (preferredPackage != null) {
            activeRoot?.recycle()
            return null
        }
        return activeRoot
    }

    fun isPackageActive(service: AccessibilityService, packageName: String): Boolean {
        val root = service.rootInActiveWindow
        if (isPackage(root, packageName)) return true
        if (!shouldUseWindowFallback(service, root)) return false
        val windowRoot = bestApplicationRoot(service, packageName)
        if (windowRoot == null) return packageName == lastApplicationPackage
        windowRoot.recycle()
        return true
    }

    fun anyReadableRoot(
        service: AccessibilityService,
        visitor: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null && !isTinyRoot(activeRoot) && visitor(activeRoot)) return true
        val windows = service.windows ?: return false
        windows.forEach { window ->
            if (window == null) return@forEach
            val root = window.root ?: return@forEach
            try {
                if (!isTinyRoot(root) && visitor(root)) return true
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun shouldUseWindowFallback(
        service: AccessibilityService,
        root: AccessibilityNodeInfo?,
    ): Boolean =
        root == null || isTinyRoot(root) || hasTinyFocusedSystemWindow(service)

    private fun hasTinyFocusedSystemWindow(service: AccessibilityService): Boolean {
        val windows = service.windows ?: return false
        windows.forEach { window ->
            if (
                window != null &&
                window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                (window.isActive || window.isFocused)
            ) {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                if (isTiny(bounds)) return true
            }
        }
        return false
    }

    private fun bestApplicationRoot(
        service: AccessibilityService,
        preferredPackage: String?,
    ): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        val candidates = buildList {
            windows.forEachIndexed { index, window ->
                if (
                    window == null ||
                    window.type != AccessibilityWindowInfo.TYPE_APPLICATION
                ) {
                    return@forEachIndexed
                }
                val root = window.root ?: return@forEachIndexed
                val readable = !isTinyRoot(root)
                add(
                    OwnedWindowRootCandidate(
                        root = root,
                        descriptor = AccessibilityWindowRootCandidate(
                            packageName = root.packageName?.toString(),
                            isActive = window.isActive,
                            isFocused = window.isFocused,
                            isReadable = readable,
                            originalIndex = index,
                        ),
                    ),
                )
            }
        }
        val selectedIndex = AccessibilityWindowRootSelectionPolicy.selectIndex(
            candidates.map(OwnedWindowRootCandidate::descriptor),
            preferredPackage,
        )
        candidates.forEachIndexed { index, candidate ->
            if (index != selectedIndex) {
                candidate.root.recycle()
            }
        }
        val selected = selectedIndex?.let(candidates::get) ?: return null
        rememberRoot(selected.root, service.packageName)
        return selected.root
    }

    private data class OwnedWindowRootCandidate(
        val root: AccessibilityNodeInfo,
        val descriptor: AccessibilityWindowRootCandidate,
    )

    private fun isPackage(root: AccessibilityNodeInfo?, packageName: String): Boolean =
        root?.packageName != null && packageName.contentEquals(root.packageName)

    private fun rememberRoot(root: AccessibilityNodeInfo?, ownPackage: String) {
        if (root != null) rememberPackage(root.packageName, ownPackage)
    }

    private fun rememberPackage(packageName: CharSequence?, ownPackage: String) {
        val value = packageName?.toString().orEmpty()
        if (
            value.isBlank() ||
            value == ownPackage ||
            value == ROKID_SYSCONFIG_PACKAGE ||
            value == "com.anezium.rokidbus.glasses"
        ) {
            return
        }
        lastApplicationPackage = value
    }

    private fun isTinyRoot(root: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        return isTiny(bounds)
    }

    private fun isTiny(bounds: Rect): Boolean =
        bounds.isEmpty || (bounds.width() <= 2 && bounds.height() <= 2)
}

internal data class AccessibilityWindowRootCandidate(
    val packageName: String?,
    val isActive: Boolean,
    val isFocused: Boolean,
    val isReadable: Boolean,
    val originalIndex: Int,
)

internal object AccessibilityWindowRootSelectionPolicy {
    fun selectIndex(
        candidates: List<AccessibilityWindowRootCandidate>,
        preferredPackage: String?,
    ): Int? =
        candidates
            .withIndex()
            .filter { (_, candidate) ->
                candidate.isReadable &&
                    (
                        preferredPackage == null ||
                            candidate.packageName == preferredPackage
                    )
            }
            .maxWithOrNull(
                compareBy<IndexedValue<AccessibilityWindowRootCandidate>>(
                    { if (it.value.isActive) 1 else 0 },
                    { if (it.value.isFocused) 1 else 0 },
                    { -it.value.originalIndex },
                ),
            )
            ?.index
}
