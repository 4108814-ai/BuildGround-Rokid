package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityWindowRootSelectionPolicyTest {
    @Test
    fun preferredPackageSearchContinuesPastNonMatchingRoot() {
        val selected = AccessibilityWindowRootSelectionPolicy.selectIndex(
            candidates = listOf(
                candidate(packageName = "com.example.overlay", originalIndex = 0),
                candidate(
                    packageName = AccessibilityWindowRoots.SETTINGS_PACKAGE,
                    originalIndex = 1,
                ),
            ),
            preferredPackage = AccessibilityWindowRoots.SETTINGS_PACKAGE,
        )

        assertEquals(1, selected)
    }

    @Test
    fun activeThenFocusedWindowsArePreferred() {
        val selected = AccessibilityWindowRootSelectionPolicy.selectIndex(
            candidates = listOf(
                candidate(
                    packageName = AccessibilityWindowRoots.SETTINGS_PACKAGE,
                    isFocused = true,
                    originalIndex = 0,
                ),
                candidate(
                    packageName = AccessibilityWindowRoots.SETTINGS_PACKAGE,
                    isActive = true,
                    originalIndex = 1,
                ),
                candidate(
                    packageName = AccessibilityWindowRoots.SETTINGS_PACKAGE,
                    isActive = true,
                    isFocused = true,
                    originalIndex = 2,
                ),
            ),
            preferredPackage = AccessibilityWindowRoots.SETTINGS_PACKAGE,
        )

        assertEquals(2, selected)
    }

    @Test
    fun unreadableAndWrongPackageRootsAreIgnored() {
        val selected = AccessibilityWindowRootSelectionPolicy.selectIndex(
            candidates = listOf(
                candidate(
                    packageName = AccessibilityWindowRoots.SETTINGS_PACKAGE,
                    isActive = true,
                    isReadable = false,
                    originalIndex = 0,
                ),
                candidate(
                    packageName = "com.example.other",
                    isActive = true,
                    isReadable = true,
                    originalIndex = 1,
                ),
            ),
            preferredPackage = AccessibilityWindowRoots.SETTINGS_PACKAGE,
        )

        assertNull(selected)
    }

    @Test
    fun defaultSelectionKeepsStableWindowOrderWhenPriorityIsEqual() {
        val selected = AccessibilityWindowRootSelectionPolicy.selectIndex(
            candidates = listOf(
                candidate(packageName = "com.example.first", originalIndex = 3),
                candidate(packageName = "com.example.second", originalIndex = 7),
            ),
            preferredPackage = null,
        )

        assertEquals(0, selected)
    }

    private fun candidate(
        packageName: String,
        isActive: Boolean = false,
        isFocused: Boolean = false,
        isReadable: Boolean = true,
        originalIndex: Int,
    ): AccessibilityWindowRootCandidate =
        AccessibilityWindowRootCandidate(
            packageName = packageName,
            isActive = isActive,
            isFocused = isFocused,
            isReadable = isReadable,
            originalIndex = originalIndex,
        )
}
