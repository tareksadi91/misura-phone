package com.misura.phone.hidden

import io.kotest.matchers.shouldBe
import org.junit.Test

class RunBoundedTest {
    @Test fun aCommandThatHangsIsKilledAtTheLimit() {
        val start = System.currentTimeMillis()
        runBounded(arrayOf("sleep", "30"), timeoutMs = 500)
        (System.currentTimeMillis() - start < 5_000) shouldBe true
    }

    @Test fun aQuickCommandReturnsItsOutput() {
        runBounded(arrayOf("echo", "hi"), timeoutMs = 5_000) shouldBe "hi\n"
    }
}
