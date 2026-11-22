package com.misura.phone.screen

import android.graphics.Insets
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun sheetImeInsets(hidden: Insets, shown: Insets, progress: Float): Insets {
    val p = progress.coerceIn(0f, 1f)
    fun edge(a: Int, b: Int) = (a + (b - a) * p).roundToInt()
    return Insets.of(edge(hidden.left, shown.left), edge(hidden.top, shown.top),
        edge(hidden.right, shown.right), edge(hidden.bottom, shown.bottom))
}

/** The sheet's only focus/IME writer. Platform callbacks belong to one opening attempt. */
internal class SheetKeyboard(
    private val view: View,
    private val scope: CoroutineScope,
    private val progress: Animatable<Float, AnimationVector1D>,
    private val requestFocus: () -> Unit,
    private val clearFocus: () -> Unit,
) : SoftwareKeyboardController {
    var engaged by mutableStateOf(false); private set
    private class Opening {
        val started = SystemClock.uptimeMillis()
        val signal = CancellationSignal()
        var controller: WindowInsetsAnimationController? = null
        var readyMs = -1L
        var cancelled = false
        var target: Boolean? = null
        var blending = false
        var blend: Job? = null
        var logged = false
        var requested = false
    }
    private var opening: Opening? = null
    private var focusHeld = false
    private var visible = false
    private var focusJob: Job? = null
    private var fieldApplied = false
    private var showPending = false

    fun beginOpen() {
        stopControl(false)
        engaged = true
        visible = true
        val attempt = Opening()
        opening = attempt
        fieldApplied = false
        showPending = false
        focusJob?.cancel()
    }

    /** Called after the field has applied its keyboard options and local controller. */
    fun fieldReady() {
        fieldApplied = true
        val attempt = opening
        if (attempt == null) {
            if (showPending) { showPending = false; show() }
            return
        }
        if (attempt.requested || attempt.cancelled) return
        attempt.requested = true
        focusJob = scope.launch {
            if (opening !== attempt) return@launch
            focusOnce()
            val controller = view.windowInsetsController
            if (controller == null) { attempt.cancelled = true; return@launch }
            controller.controlWindowInsetsAnimation(WindowInsets.Type.ime(), -1L, null, attempt.signal,
                object : WindowInsetsAnimationControlListener {
                    override fun onReady(controller: WindowInsetsAnimationController, types: Int) {
                        if (opening !== attempt || attempt.cancelled) {
                            if (controller.isReady) controller.finish(false)
                            return
                        }
                        attempt.controller = controller
                        attempt.readyMs = SystemClock.uptimeMillis() - attempt.started
                        val from = controller.currentInsets
                        attempt.blending = true
                        attempt.blend = scope.launch {
                            repeat(3) { frame ->
                                withFrameNanos { }
                                if (opening !== attempt || !controller.isReady) return@launch
                                val p = progress.value.coerceIn(0f, 1f)
                                val target = sheetImeInsets(controller.hiddenStateInsets, controller.shownStateInsets, p)
                                val t = (frame + 1) / 3f
                                val ease = t * t * (3f - 2f * t)
                                controller.setInsetsAndAlpha(sheetImeInsets(from, target, ease), 1f, p)
                            }
                            attempt.blending = false
                            follow(progress.value)
                        }
                    }

                    override fun onCancelled(controller: WindowInsetsAnimationController?) {
                        if (opening !== attempt) return
                        attempt.cancelled = true
                        attempt.controller = null
                        attempt.blend?.cancel()
                        attempt.target?.let { fallback(attempt, it) }
                    }

                    override fun onFinished(controller: WindowInsetsAnimationController) = Unit
                })
        }
    }

    fun follow(value: Float) {
        val attempt = opening ?: return
        val controller = attempt.controller ?: return
        if (attempt.blending || !controller.isReady) return
        val p = value.coerceIn(0f, 1f)
        controller.setInsetsAndAlpha(sheetImeInsets(controller.hiddenStateInsets, controller.shownStateInsets, p), 1f, p)
    }

    fun release(open: Boolean) {
        val attempt = opening
        if (attempt == null) {
            if (open) show() else hide()
            return
        }
        attempt.target = open
        if (attempt.cancelled || attempt.controller?.isReady != true) fallback(attempt, open)
    }

    fun settled(open: Boolean) {
        stopControl(open)
        if (!open) {
            if (focusHeld) { focusHeld = false; clearFocus() }
            visible = false
            engaged = false
            fieldApplied = false
            showPending = false
        }
    }

    override fun show() {
        if (opening != null) return
        if (engaged && !fieldApplied) { showPending = true; visible = true; return }
        if (focusJob?.isActive == true) return
        visible = true
        focusJob?.cancel()
        focusJob = scope.launch {
            withFrameNanos { }
            if (!visible) return@launch
            focusOnce()
            ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun hide() {
        showPending = false
        focusJob?.cancel()
        val controlled = opening?.controller?.isReady == true
        stopControl(false)
        if (visible && !controlled) ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
        visible = false
        if (focusHeld) { focusHeld = false; clearFocus() }
    }

    private fun focusOnce() {
        if (!focusHeld) { requestFocus(); focusHeld = true }
    }

    private fun fallback(attempt: Opening, open: Boolean) {
        if (opening !== attempt) return
        opening = null
        attempt.blend?.cancel()
        attempt.signal.cancel()
        log(attempt, true)
        if (!open) { hide(); return }
        // Reuse the one-shot tap path; never request control again for this opening.
        focusJob?.cancel()
        visible = false
        show()
    }

    private fun stopControl(shown: Boolean) {
        val attempt = opening ?: return
        opening = null // finish/cancel can call back synchronously.
        attempt.blend?.cancel()
        val controller = attempt.controller
        if (controller?.isReady == true) controller.finish(shown)
        else attempt.signal.cancel()
        log(attempt, false)
    }

    private fun log(attempt: Opening, fallback: Boolean) {
        if (attempt.logged) return
        attempt.logged = true
        // ready (0/1), drag-to-ready milliseconds (-1 if unavailable), fallback (0/1).
        Log.i("MisuraSheetIme", "${if (attempt.readyMs >= 0) 1 else 0} ${attempt.readyMs} ${if (fallback) 1 else 0}")
    }
}
