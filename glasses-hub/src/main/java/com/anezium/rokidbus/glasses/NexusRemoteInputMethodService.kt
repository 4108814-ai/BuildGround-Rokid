package com.anezium.rokidbus.glasses

import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Space

/** A system-wide IME endpoint with no on-glasses keyboard surface. */
class NexusRemoteInputMethodService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        GlassesHub.start(applicationContext)
    }

    override fun onCreateInputView(): View = Space(this).apply {
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        minimumWidth = 1
        minimumHeight = 1
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return false
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentTopInsets = 0
        outInsets.visibleTopInsets = 0
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(Region())
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        RemoteInputController.onInputStarted(this, attribute)
    }

    override fun onFinishInput() {
        RemoteInputController.onInputFinished(this)
        super.onFinishInput()
    }

    override fun onUnbindInput() {
        RemoteInputController.onInputFinished(this)
        super.onUnbindInput()
    }

    override fun onDestroy() {
        RemoteInputController.onInputFinished(this)
        super.onDestroy()
    }

    internal fun closeRemoteInputSession() {
        currentInputConnection?.finishComposingText()
        requestHideSelf(0)
        RemoteInputController.onInputFinished(this)
    }
}
