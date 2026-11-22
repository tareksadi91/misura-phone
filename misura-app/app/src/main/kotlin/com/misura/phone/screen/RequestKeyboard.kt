package com.misura.phone.screen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.CancellationSignal
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.animation.PathInterpolator
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlin.math.abs

/** Keyboard owner for tap-to-compose, separate from the accepted app-sheet owner. */
internal class RequestKeyboard(
    private val view: View,
    private val native: SoftwareKeyboardController?,
    private val enabled: () -> Boolean,
    private val focus: () -> Unit,
    private val blur: () -> Unit,
) : SoftwareKeyboardController {
    private class Attempt(var shown: Boolean) {
        val signal = CancellationSignal()
        var control: WindowInsetsAnimationController? = null
        var motion: ValueAnimator? = null
        var pending: Runnable? = null
        var transferred = false
    }
    private var attempt: Attempt? = null
    private var lastShown = false
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val back = OnBackInvokedCallback { hide() }
    private val easing = PathInterpolator(.4f, 0f, .2f, 1f)

    override fun show() = move(true)
    override fun hide() = move(false)

    fun focusLost() {
        releaseBack()
        if (attempt?.shown == true) { attempt?.shown = false; cancel() }
    }

    private fun ownBack() {
        if (backDispatcher != null) return
        view.findOnBackInvokedDispatcher()?.let {
            // Pending IME control can otherwise consume Back and still complete its opening afterward.
            it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back)
            backDispatcher = it
        }
    }

    private fun releaseBack() {
        backDispatcher?.unregisterOnBackInvokedCallback(back)
        backDispatcher = null
    }

    private fun move(shown: Boolean) {
        if (!enabled() || !view.hasWindowFocus()) return
        val previous = attempt
        if (previous?.transferred == true) cancel()
        if (shown) ownBack()
        if (previous != null && !previous.transferred) {
            if (previous.shown == shown) return
            previous.shown = shown
            if (previous.control != null) animate(previous)
            else if (!shown) { cancel(); blur() }
            return
        }
        val visible = view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: lastShown
        if (shown && visible) { focus(); return }
        if (!shown && !visible) { fallback(false); return }
        val next = Attempt(shown)
        attempt = next
        if (shown) focus()
        val pending = Runnable {
            next.pending = null
            if (attempt !== next) return@Runnable
            if (!enabled() || !view.hasWindowFocus()) { attempt = null; return@Runnable }
            val platform = view.windowInsetsController
            if (platform == null) { attempt = null; fallback(next.shown); return@Runnable }
            platform.controlWindowInsetsAnimation(WindowInsets.Type.ime(), if (next.shown) 340L else 300L, null,
                next.signal, object : WindowInsetsAnimationControlListener {
                    override fun onReady(control: WindowInsetsAnimationController, types: Int) {
                        if (attempt !== next || !enabled()) {
                            val shown = if (next.transferred) view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: lastShown
                                else attempt?.shown ?: next.shown
                            if (control.isReady) control.finish(shown)
                            return
                        }
                        next.control = control
                        animate(next)
                    }
                    override fun onFinished(control: WindowInsetsAnimationController) {
                        if (attempt !== next) return
                        attempt = null; stopMotion(next)
                    }
                    override fun onCancelled(control: WindowInsetsAnimationController?) {
                        if (attempt !== next) return
                        attempt = null; stopMotion(next)
                        if (next.transferred || !enabled() || !view.hasWindowFocus()) return
                        if (control == null) fallback(next.shown)
                        else if (view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true) blur()
                    }
                })
        }
        next.pending = pending
        // Opening needs the editor's new options/connection; closing must start before navigation can discard it.
        if (shown) view.postOnAnimation(pending) else pending.run()
    }

    private fun animate(next: Attempt) {
        val control = next.control ?: return
        if (!control.isReady) return
        stopMotion(next)
        val hidden = control.hiddenStateInsets
        val shown = control.shownStateInsets
        val span = (shown.bottom - hidden.bottom).coerceAtLeast(1)
        val start = ((control.currentInsets.bottom - hidden.bottom).toFloat() / span).coerceIn(0f, 1f)
        val target = if (next.shown) 1f else 0f
        val finishShown = next.shown
        val motion = ValueAnimator.ofFloat(start, target).apply {
            duration = ((if (finishShown) 340L else 300L) * abs(target - start)).toLong().coerceAtLeast(1)
            interpolator = easing
        }
        next.motion = motion
        motion.addUpdateListener {
            if (attempt !== next || next.motion !== motion || !enabled() || !control.isReady) return@addUpdateListener
            val progress = it.animatedValue as Float
            control.setInsetsAndAlpha(sheetImeInsets(hidden, shown, progress), 1f, progress)
        }
        motion.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (attempt !== next || next.motion !== motion || !enabled() || !control.isReady) return
                attempt = null; next.motion = null; lastShown = finishShown
                control.finish(finishShown)
                if (!finishShown) { releaseBack(); blur() }
            }
        })
        motion.start()
    }

    /** Stop before a different editor/owner takes over; never clear the sheet's field focus. */
    fun cancel(transfer: Boolean = false) {
        releaseBack()
        val current = attempt ?: return
        stopMotion(current)
        current.transferred = current.transferred || transfer
        if (transfer && current.control?.isReady == true) {
            // The sheet's control request cancels this controller and inherits its current insets without a snap.
            return
        }
        attempt = null
        current.pending?.let(view::removeCallbacks)
        val control = current.control
        if (control?.isReady == true) control.finish(current.shown)
        else if (!current.transferred && !current.shown) { lastShown = false; native?.hide() }
        current.signal.cancel()
    }

    private fun stopMotion(current: Attempt) {
        val motion = current.motion
        current.motion = null
        motion?.cancel()
    }

    private fun fallback(shown: Boolean) {
        if (!enabled() || !view.hasWindowFocus()) return
        lastShown = shown
        if (shown) native?.show() else { releaseBack(); native?.hide(); blur() }
    }
}
