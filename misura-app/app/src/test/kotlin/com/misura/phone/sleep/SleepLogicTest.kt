package com.misura.phone.sleep

import io.kotest.matchers.shouldBe
import org.junit.Test

class SleepLogicTest {
    @Test fun `screen on while locked shows the chosen variant`() {
        wakeAction(WakeEvent.SCREEN_ON, locked = true, Variant.ACTIVITY) shouldBe WakeAction.SHOW_ACTIVITY
        wakeAction(WakeEvent.SCREEN_ON, locked = true, Variant.OVERLAY) shouldBe WakeAction.SHOW_OVERLAY
    }

    @Test fun `screen on while unlocked or mode off does nothing`() {
        wakeAction(WakeEvent.SCREEN_ON, locked = false, Variant.ACTIVITY) shouldBe WakeAction.NONE
        wakeAction(WakeEvent.SCREEN_ON, locked = true, Variant.OFF) shouldBe WakeAction.NONE
    }

    @Test fun `unlock hides the sleep screen`() {
        wakeAction(WakeEvent.USER_PRESENT, locked = false, Variant.OVERLAY) shouldBe WakeAction.HIDE
    }

    @Test fun `no task line reaches the lock screen`() {
        // The sleep screen draws above the lock without Android's notification privacy policy: names and states stay off it.
        sleepCards() shouldBe emptyList()
    }

    @Test fun `shizuku off is the only card`() {
        sleepCards(shizukuUp = false) shouldBe
            listOf("Hidden sends paused: Shizuku is off")
    }

    @Test fun `shizuku up adds nothing`() {
        sleepCards(shizukuUp = true) shouldBe emptyList()
    }
}
