package com.misura.phone.sleep

import android.app.KeyguardManager
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.phone.hidden.ScreenReader
import io.kotest.matchers.shouldBe
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.phone.MisuraGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Turns on a swipe lock screen for the test and restores "no lock screen" afterwards. */
@RunWith(AndroidJUnit4::class)
class SleepScreenTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val keyguard = ctx.getSystemService(KeyguardManager::class.java)

    private fun shell(cmd: String): String =
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(cmd)
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    /** Screen off, then on; returns ms from wake until the sleep screen showed, or -1. */
    private fun sleepAndWake(): Long {
        shell("input keyevent KEYCODE_SLEEP")
        check(waitFor(5_000) { keyguard.isKeyguardLocked }) { "lock screen never engaged" }
        waitFor(3_000) { !SleepScreen.visible } // screen off pauses the activity (the overlay stays)
        Thread.sleep(500)
        val woke = System.currentTimeMillis()
        shell("input keyevent KEYCODE_WAKEUP")
        return if (waitFor(3_000) { SleepScreen.visible && SleepScreen.shownAt >= woke }) (SleepScreen.shownAt - woke) else -1
    }

    @Before fun setUp() {
        shell("locksettings set-disabled false")
        shell("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
        if (!ScreenReader.connected()) {
            shell("settings put secure enabled_accessibility_services null"); Thread.sleep(300)
            shell("settings put secure enabled_accessibility_services $A11Y")
            shell("settings put secure accessibility_enabled 1")
        }
        check(waitFor(5_000) { ScreenReader.connected() }) { "accessibility service not connected" }
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        shell("input keyevent KEYCODE_HOME")
    }

    @After fun tearDown() {
        SleepMode.set(ctx, Variant.OFF)
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        shell("locksettings set-disabled true")
    }

    private fun showsOnWake(v: Variant) {
        SleepMode.set(ctx, v)
        val delay = sleepAndWake()
        Log.i("MisuraSleep", "variant=$v delay=${delay}ms")
        (delay >= 0) shouldBe true
        keyguard.isKeyguardLocked shouldBe true // the system lock is still in force behind it
    }

    @Test fun activityVariantShowsOverLockScreenOnWake() = showsOnWake(Variant.ACTIVITY)

    @Test fun overlayVariantShowsOverLockScreenOnWake() = showsOnWake(Variant.OVERLAY)

    @Test fun wakingFromAnotherAppShowsSleepScreen() {
        shell("am start -W -n com.misura.testapp/.ChatActivity")
        showsOnWake(Variant.ACTIVITY)
    }

    @Test fun unlockingHidesSleepScreen() {
        showsOnWake(Variant.OVERLAY)
        shell("wm dismiss-keyguard")
        waitFor(3_000) { !SleepScreen.visible } shouldBe true
    }

    @Test fun modeOffShowsNothing() {
        SleepMode.set(ctx, Variant.OFF)
        sleepAndWake() shouldBe -1L
    }

    @Test fun lateLaunchAfterUnlockDoesNotCoverThePhone() {
        SleepMode.set(ctx, Variant.ACTIVITY)
        keyguard.isKeyguardLocked shouldBe false
        SleepScreen.showActivity(ctx) // as if SCREEN_ON's launch landed after a fast unlock
        Thread.sleep(1_500)
        SleepScreen.visible shouldBe false
        shell("dumpsys activity activities").lines().none { "topResumedActivity" in it && "SleepActivity" in it } shouldBe true
    }

    @Test fun aWaitingApprovalNeverPutsItsRecipientOnTheLockScreen() = runBlocking<Unit> {
        SleepMode.set(ctx, Variant.ACTIVITY)
        (sleepAndWake() >= 0) shouldBe true
        val who = "Fresh" + System.nanoTime()
        MisuraGraph.tasks.save(Task("fresh-$who", "ask", TaskState.NEEDS_APPROVAL,
            Payload(mapOf("channel" to "testchat", "recipient" to who, "content" to "hi")), emptyList(), System.currentTimeMillis(), null))
        val before = SleepScreen.built
        (sleepAndWake() >= 0) shouldBe true
        // A screen built by this wake finished drawing (not an earlier wake's, not a guess): check the text it put on screen.
        waitFor(5_000) { SleepScreen.drawn.first > before } shouldBe true
        SleepScreen.visible shouldBe true
        SleepScreen.drawn.second.none { who in it || "Needs you" in it } shouldBe true
    }

    @Test fun overlayComesBackAfterAccessibilityReconnects() {
        SleepMode.set(ctx, Variant.OVERLAY)
        (sleepAndWake() >= 0) shouldBe true
        shell("settings put secure enabled_accessibility_services null")
        waitFor(5_000) { !ScreenReader.connected() } shouldBe true
        shell("settings put secure enabled_accessibility_services $A11Y")
        waitFor(5_000) { ScreenReader.connected() } shouldBe true
        SleepScreen.showOverlay(ctx)
        waitFor(3_000) { SleepOverlay.attached() } shouldBe true
    }

    companion object { const val A11Y = "com.misura.phone/com.misura.phone.hidden.MisuraAccessibility" }
}
