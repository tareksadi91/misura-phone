package com.misura.phone.screen

import android.content.Context
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.WindowInsetsController
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.BroadcastFrameClock
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SheetKeyboardTest {
    private class Platform {
        var requests = 0
        var shows = 0
        var hides = 0
        lateinit var listener: WindowInsetsAnimationControlListener
        private val controller = Proxy.newProxyInstance(WindowInsetsController::class.java.classLoader,
            arrayOf(WindowInsetsController::class.java)) { _, method, args ->
            when (method.name) {
                "controlWindowInsetsAnimation" -> { requests++; listener = args!![4] as WindowInsetsAnimationControlListener; null }
                "show" -> { if ((args!![0] as Int) and WindowInsets.Type.ime() != 0) shows++; null }
                "hide" -> { if ((args!![0] as Int) and WindowInsets.Type.ime() != 0) hides++; null }
                "getSystemBarsAppearance", "getSystemBarsBehavior" -> 0
                else -> null
            }
        } as WindowInsetsController
        val view = object : View(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getWindowInsetsController() = controller
        }
    }

    private class Controlled : WindowInsetsAnimationController {
        var insets = Insets.of(0, 0, 0, 60)
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

    @Test fun readyDoesNotJumpThenTracksClampedProgressAndFinishesAfterSettle() = runTest {
        val platform = Platform()
        val clock = BroadcastFrameClock()
        val progress = Animatable(.8f)
        var focuses = 0
        val owner = SheetKeyboard(platform.view, CoroutineScope(coroutineContext + clock), progress, { focuses++ }, {})
        owner.beginOpen()
        assertEquals(0, platform.requests)
        owner.fieldReady()
        runCurrent(); clock.sendFrame(1L); runCurrent()
        val control = Controlled()
        platform.listener.onReady(control, WindowInsets.Type.ime())
        assertEquals(60, control.insets.bottom)
        repeat(3) { frame -> runCurrent(); clock.sendFrame((frame + 2).toLong()); runCurrent() }
        assertEquals(240, control.insets.bottom)
        owner.release(true)
        assertEquals(null, control.finished)
        owner.follow(1.2f)
        assertEquals(300, control.insets.bottom)
        owner.follow(-.2f)
        assertEquals(0, control.insets.bottom)
        owner.settled(true)
        assertEquals(true, control.finished)
        assertEquals(1, focuses)
        assertEquals(1, platform.requests)
        assertEquals(0, platform.shows)
        owner.hide()
    }

    @Test fun releaseBeforeReadyFallsBackOnceAndLateCallbacksCannotReopenControl() = runTest {
        val platform = Platform()
        val clock = BroadcastFrameClock()
        var focuses = 0
        var clears = 0
        val owner = SheetKeyboard(platform.view, CoroutineScope(coroutineContext + clock), Animatable(0f),
            { focuses++ }, { clears++ })
        owner.beginOpen(); owner.fieldReady()
        runCurrent(); clock.sendFrame(1L); runCurrent()
        owner.release(true)
        runCurrent(); clock.sendFrame(2L); runCurrent()
        platform.listener.onCancelled(null)
        val late = Controlled()
        platform.listener.onReady(late, WindowInsets.Type.ime())
        owner.fieldReady(); owner.settled(true)
        assertEquals(false, late.finished)
        assertEquals(1, platform.requests)
        assertEquals(1, platform.shows)
        assertEquals(1, focuses)
        owner.hide(); owner.hide()
        assertEquals(1, platform.hides)
        assertEquals(1, clears)
        assertTrue(owner.engaged) // Hiding the keyboard never closes the sheet.
    }

    @Test fun cancellationWaitsForTargetAndBackFinishesActiveControlWithoutExtraHide() = runTest {
        val platform = Platform()
        val clock = BroadcastFrameClock()
        val owner = SheetKeyboard(platform.view, CoroutineScope(coroutineContext + clock), Animatable(.4f), {}, {})
        owner.beginOpen(); owner.fieldReady()
        runCurrent(); clock.sendFrame(1L); runCurrent()
        platform.listener.onCancelled(null)
        assertEquals(0, platform.shows)
        owner.release(true)
        runCurrent(); clock.sendFrame(2L); runCurrent()
        assertEquals(1, platform.shows)
        owner.hide(); owner.settled(false)

        val hides = platform.hides
        owner.beginOpen(); owner.fieldReady()
        runCurrent(); clock.sendFrame(3L); runCurrent()
        val control = Controlled()
        platform.listener.onReady(control, WindowInsets.Type.ime())
        owner.hide(); owner.hide()
        platform.listener.onCancelled(control)
        assertEquals(false, control.finished)
        assertEquals(hides, platform.hides)
        assertEquals(1, platform.shows)
    }
}
