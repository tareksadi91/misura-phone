package com.misura.core.task

import com.misura.core.task.TaskState.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TaskTest {
    private fun task(state: TaskState) = Task(id = "t1", kind = "test", state = state, payload = null, history = emptyList(), createdAt = 0, deadline = null)

    @Test fun `executing to outcome unknown allowed`() {
        task(EXECUTING).transition(OUTCOME_UNKNOWN, 1, "crash").state shouldBe OUTCOME_UNKNOWN
    }

    @Test fun `outcome unknown cannot go to executing`() {
        assertThrows<IllegalTransition> { task(OUTCOME_UNKNOWN).transition(EXECUTING, 1, "retry") }
    }

    @Test fun `done is terminal`() {
        assertThrows<IllegalTransition> { task(DONE).transition(PLANNING, 1, "again") }
    }

    @Test fun `checking can go to waiting after a send`() {
        task(CHECKING).transition(WAITING, 1, "sent, waiting for reply").state shouldBe WAITING
    }

    @Test fun `executing to planning allowed`() {
        task(EXECUTING).transition(PLANNING, 1, "nothing dispatched").state shouldBe PLANNING
    }

    @Test fun `history records transitions in order`() {
        val t = task(PLANNING).transition(NEEDS_APPROVAL, 1, "a").transition(EXECUTING, 2, "b", evidence = "e")
        t.history.map { it.to } shouldBe listOf(NEEDS_APPROVAL, EXECUTING)
        t.history.last().from shouldBe NEEDS_APPROVAL
        t.history.last().evidence shouldBe "e"
    }

    @Test fun `digest ignores key order`() {
        Payload(mapOf("a" to "1", "b" to "2")).digest() shouldBe Payload(mapOf("b" to "2", "a" to "1")).digest()
    }

    @Test fun `multiline values do not collide`() {
        Payload(mapOf("a" to "x\nb=y", "b" to "z")).digest() shouldNotBe Payload(mapOf("a" to "x", "b" to "y\nb=z")).digest()
    }
}
