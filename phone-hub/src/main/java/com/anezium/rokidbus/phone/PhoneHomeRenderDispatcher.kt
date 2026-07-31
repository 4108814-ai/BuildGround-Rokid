package com.anezium.rokidbus.phone

/** Keeps every phone-home view mutation on Android's main thread. */
internal class PhoneHomeRenderDispatcher(
    private val isMainThread: () -> Boolean,
    private val postToMain: (() -> Unit) -> Unit,
    private val render: () -> Unit,
) {
    fun requestRender() {
        if (isMainThread()) {
            render()
        } else {
            postToMain(render)
        }
    }
}
