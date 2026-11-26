package com.misura.core.shortcut

import com.misura.core.agent.NOTE
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.core.agent.WEB
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class ShortcutRulesTest {
    private fun step(state: StepState, app: String?) = Step("s", 0, "x", state, startedAt = 0, app = app)
    private fun sc(id: String, created: Long, used: Long? = null) = Shortcut(id, "t$id", "p$id", "com.example.club", created, used)

    @Test fun webAndNoteAreNotAppEvidence() {
        isAppEvidence(WEB) shouldBe false
        isAppEvidence(NOTE) shouldBe false
        isAppEvidence(null) shouldBe false
        isAppEvidence("com.example.club") shouldBe true
    }

    @Test fun workedStepsIsLastDoneApp() {
        workedSteps(listOf(step(StepState.DONE, "com.example.maps"), step(StepState.DONE, "com.example.usc"), step(StepState.DONE, NOTE))) shouldBe "com.example.usc"
    }

    @Test fun laterFailureCancels() {
        workedSteps(listOf(step(StepState.DONE, "com.example.usc"), step(StepState.FAILED, null))) shouldBe null
    }

    @Test fun noAppEvidenceIsNull() {
        workedSteps(listOf(step(StepState.DONE, WEB), step(StepState.DONE, NOTE))) shouldBe null
    }

    @Test fun willAndRunningAreNeitherDoneNorFailed() {
        workedSteps(listOf(step(StepState.DONE, "a"), step(StepState.WILL, "b"), step(StepState.RUNNING, "c"))) shouldBe "a"
    }

    @Test fun budgetCapsTasksToo() {
        homeFit(3, 2, 1, 3, 9) shouldBe HomeFit(3, 1, false, 0)
    }

    @Test fun shortcutsFillRemainder() {
        homeFit(1, 0, 0, 3, 9) shouldBe HomeFit(1, 0, false, 3)
    }

    @Test fun measuredSlotsWin() {
        homeFit(0, 0, 0, 3, 2) shouldBe HomeFit(0, 0, false, 2)
    }

    @Test fun runningTakesOneLine() {
        homeFit(1, 0, 3, 3, 9) shouldBe HomeFit(1, 0, true, 2)
    }

    @Test fun replacesLeastRecentlyUsedWhenFull() {
        replaces(listOf(sc("a", 1), sc("b", 2))) shouldBe null
        val full = listOf(sc("a", 1, used = 50), sc("b", 2), sc("c", 3, used = 9))
        replaces(full)?.id shouldBe "b"
        replaces(listOf(sc("a", 5), sc("b", 5), sc("c", 7)))?.id shouldBe "a"
    }

    @Test fun digestBindsReplacement() {
        val s = sc("a", 1)
        s.digest(null) shouldNotBe s.digest("b")
        s.digest("b") shouldBe s.digest("b")
    }
}
