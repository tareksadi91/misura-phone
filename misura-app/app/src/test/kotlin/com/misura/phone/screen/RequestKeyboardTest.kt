package com.misura.phone.screen

import android.content.Context
import android.graphics.Insets
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.WindowInsetsController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RequestKeyboardTest {
    @Before fun pauseFrames() { org.robolectric.shadows.ShadowChoreographer.setPaused(true) }
    private class Platform {
        val posted = mutableListOf<Runnable>()
        val listeners = mutableListOf<WindowInsetsAnimationControlListener>()
        var nativeShows = 0
        var nativeHides = 0
        var focused = 0
        var blurred = 0
        var enabled = true
        var visible: Boolean? = null
        private val controller = Proxy.newProxyInstance(WindowInsetsController::class.java.classLoader,
            arrayOf(WindowInsetsController::class.java)) { _, method, args ->
            when (method.name) {
                "controlWindowInsetsAnimation" -> { listeners += args!![4] as WindowInsetsAnimationControlListener; null }
                "getSystemBarsAppearance", "getSystemBarsBehavior" -> 0
                else -> null
            }
        } as WindowInsetsController
        val view = object : View(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getWindowInsetsController() = controller
            override fun hasWindowFocus() = true
            override fun getRootWindowInsets(): WindowInsets? = visible?.let {
                WindowInsets.Builder().setVisible(WindowInsets.Type.ime(), it).build()
            }
            override fun postOnAnimation(action: Runnable) { posted += action }
            override fun removeCallbacks(action: Runnable) = posted.remove(action)
        }
        val native = object : SoftwareKeyboardController {
            override fun show() { nativeShows++ }
            override fun hide() { nativeHides++ }
        }
        fun owner() = RequestKeyboard(view, native, { enabled }, { focused++ }, { blurred++ })
        fun frame() { val pending = posted.toList(); posted.clear(); pending.forEach { it.run() } }
    }

    private class Controlled(start: Int = 0) : WindowInsetsAnimationController {
        var insets = Insets.of(0, 0, 0, start)
        var finished: Boolean? = null
        override fun getCurrentInsets() = insets
        override fun getHiddenStateInsets() = Insets.NONE
        override fun getShownStateInsets() = Insets.of(0, 0, 0, 300)
        override fun getCurrentAlpha() = 1f
        override fun getCurrentFraction() = insets.bottom / 300f
        override fun getTypes() = WindowInsets.Type.ime()
        override fun isCancelled() = false
        override fun isFinished() = finished != null
        override fun finish(shown: Boolean) { finished = shown }
        override fun setInsetsAndAlpha(insets: Insets?, alpha: Float, fraction: Float) { this.insets = insets!! }
    }

    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    @Test fun openingWaitsForControlThenEasesInsteadOfJumping() {
        val p = Platform(); val owner = p.owner()
        owner.show(); owner.show(); p.frame()
        assertEquals(1, p.listeners.size)
        assertEquals(0, p.nativeShows)
        val c = Controlled(); p.listeners.single().onReady(c, WindowInsets.Type.ime())
        assertEquals(0, c.insets.bottom)
        advance(100)
        assertTrue("insets=${c.insets.bottom}, finished=${c.finished}, scale=${android.animation.ValueAnimator.getDurationScale()}", c.insets.bottom in 1..299)
        assertNull(c.finished)
        advance(100)
        assertNull(c.finished)
        advance(400)
        assertEquals(true, c.finished)
        assertEquals(1, p.focused)
    }

    @Test fun reverseUsesCurrentInsetsAndBlursOnlyAfterClosing() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame()
        val c = Controlled(); p.listeners.single().onReady(c, WindowInsets.Type.ime())
        advance(150)
        val before = c.insets.bottom
        assertTrue("insets=$before, finished=${c.finished}, scale=${android.animation.ValueAnimator.getDurationScale()}", before in 1..299)
        owner.hide()
        assertEquals(before, c.insets.bottom)
        assertEquals(1, p.listeners.size)
        assertEquals(0, p.blurred)
        advance(500)
        assertEquals(false, c.finished)
        assertEquals(1, p.blurred)
    }

    @Test fun cancelledPendingShowCannotReopenAfterOutsideDismissal() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame(); val listener = p.listeners.single()
        owner.hide(); listener.onCancelled(null)
        assertEquals(0, p.nativeShows)
        assertEquals(1, p.nativeHides)
        assertEquals(1, p.blurred)
    }

    @Test fun lateReadyAfterDismissalDoesNotTrustStaleVisibleInsets() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame(); val listener = p.listeners.single()
        owner.hide(); p.visible = true
        val late = Controlled(); listener.onReady(late, WindowInsets.Type.ime())
        assertEquals(false, late.finished)
        assertEquals(0, p.nativeShows)
    }

    @Test fun lateReadyAfterLeavingHomeRetainsTheDismissalTarget() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame(); val listener = p.listeners.single()
        owner.hide(); p.enabled = false; p.visible = true
        val late = Controlled(); listener.onReady(late, WindowInsets.Type.ime())
        assertEquals(false, late.finished)
    }

    @Test fun handoffAndLateCallbacksNeverStealSheetFocus() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame(); val listener = p.listeners.single()
        p.enabled = false; owner.cancel(transfer = true); listener.onCancelled(null)
        val late = Controlled(); listener.onReady(late, WindowInsets.Type.ime())
        advance(600)
        assertEquals(0, p.nativeShows)
        assertEquals(0, p.nativeHides)
        assertEquals(0, p.blurred)
    }

    @Test fun deniedControlFallsBackOnceWithoutRequestLoop() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame(); p.listeners.single().onCancelled(null)
        advance(600)
        assertEquals(1, p.nativeShows)
        assertEquals(1, p.listeners.size)
    }

    @Test fun readyHandoffKeepsCurrentInsetsUntilTheSheetTakesControl() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame(); val listener = p.listeners.single()
        val c = Controlled(); listener.onReady(c, WindowInsets.Type.ime()); advance(100)
        val before = c.insets.bottom
        p.enabled = false; owner.cancel(transfer = true); advance(500)
        assertEquals(before, c.insets.bottom)
        assertNull(c.finished)
        listener.onCancelled(c)
        assertEquals(0, p.blurred)
        assertEquals(0, p.nativeShows)
    }

    @Test fun systemCancellationDoesNotFightBackByReopeningTheKeyboard() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame(); val listener = p.listeners.single()
        val c = Controlled(); listener.onReady(c, WindowInsets.Type.ime()); advance(100)
        listener.onCancelled(c); advance(500)
        assertEquals(0, p.nativeShows)
        assertEquals(0, p.nativeHides)
    }

    @Test fun lostFocusCancelsTheQueuedOpening() {
        val p = Platform(); val owner = p.owner()
        owner.show(); owner.focusLost(); p.frame()
        assertEquals(0, p.listeners.size)
        assertEquals(0, p.nativeShows)
    }

    @Test fun unavailablePostedFrameDoesNotBlockTheNextOpening() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.enabled = false; p.frame()
        assertEquals(0, p.listeners.size)
        p.enabled = true; owner.show(); p.frame()
        assertEquals(1, p.listeners.size)
    }

    @Test fun leavingHomeDuringCloseCannotReshowTheSubmittedRequestKeyboard() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame()
        p.listeners.last().onReady(Controlled(), WindowInsets.Type.ime()); advance(600)
        owner.hide(); p.frame()
        val closing = Controlled(300)
        p.listeners.last().onReady(closing, WindowInsets.Type.ime()); advance(80)
        p.enabled = false; owner.cancel()
        assertEquals(false, closing.finished)
        assertEquals(0, p.blurred) // The new task owns focus now.
    }

    @Test fun leavingHomeBeforeCloseIsReadyStillHidesTheKeyboard() {
        val p = Platform(); val owner = p.owner()
        p.visible = true
        owner.hide(); p.enabled = false; owner.cancel(); p.frame()
        assertEquals(1, p.nativeHides)
        assertEquals(0, p.blurred) // Cancellation must not clear the new task's focus.
    }

    @Test fun pendingCloseTransferredToSheetDoesNotHideItsKeyboard() {
        val p = Platform(); val owner = p.owner()
        p.visible = true
        owner.hide(); p.enabled = false; owner.cancel(transfer = true); p.frame()
        assertEquals(0, p.nativeHides)
        assertEquals(0, p.blurred)
    }

    @Test fun closeRequestsControlBeforeAnotherFrameCanDiscardIt() {
        val p = Platform(); val owner = p.owner()
        p.visible = true
        owner.hide()
        assertEquals(1, p.listeners.size)
        assertTrue(p.posted.isEmpty())
        p.enabled = false; p.frame(); owner.cancel()
        assertEquals(1, p.nativeHides)
        assertEquals(0, p.blurred)
    }

    @Test fun losingFocusDuringOpeningDoesNotFinishAsShown() {
        val p = Platform(); val owner = p.owner()
        owner.show(); p.frame()
        val c = Controlled(); p.listeners.single().onReady(c, WindowInsets.Type.ime()); advance(80)
        p.visible = true
        owner.focusLost()
        assertEquals(false, c.finished)
        assertEquals(0, p.blurred)
    }
}
