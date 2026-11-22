package com.misura.core.inbox

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnswerCollectorTest {
    @Test fun `burst completes after quiet period`() {
        val c = AnswerCollector(quietMs = 20_000)
        c.isComplete(lastMsgAt = 0, now = 19_999) shouldBe false
        c.isComplete(lastMsgAt = 0, now = 20_000) shouldBe true
    }
}
