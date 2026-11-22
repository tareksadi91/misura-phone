package com.misura.core.routine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.DayOfWeek

class RoutineGateTest {
    private var now = 0L
    private val gate = RoutineGate(clock = { now })
    private val r = Routine("new", "Train", Trigger.Time(8, 0, setOf(DayOfWeek.MONDAY)), "Is my train late?")
    private val saved = mutableListOf<Routine>()
    private fun save(x: Routine = r, session: String? = "s1") = gate.save(x, session) { saved += it; "Saved" }

    @Test fun askingSavesNothing() {
        save().shouldBeInstanceOf<RoutineGate.Result.Ask>()
        save().shouldBeInstanceOf<RoutineGate.Result.Ask>()
        saved shouldBe emptyList()
    }

    @Test fun theSaveTapItselfSavesExactlyWhatTheCardShowedOnce() {
        val ask = save(r) as RoutineGate.Result.Ask
        gate.answered(ask.id, yes = true)
        saved shouldBe listOf(r) // no second model call needed
        gate.answered(ask.id, yes = true) // a double tap
        saved.size shouldBe 1
        save(r) shouldBe RoutineGate.Result.Done("Saved") // the model calling again gets the outcome, not a second card
        saved.size shouldBe 1
    }

    @Test fun onlyTheNewestCardInASessionCounts() {
        val old = save(r) as RoutineGate.Result.Ask
        save(r.copy(prompt = "Book me a taxi")).shouldBeInstanceOf<RoutineGate.Result.Ask>()
        gate.answered(old.id, yes = true) // a tap on a card that was replaced
        saved shouldBe emptyList()
    }

    @Test fun noExpiryAndRevokeDoNothing() {
        val a = save() as RoutineGate.Result.Ask
        gate.answered(a.id, yes = false)
        val b = save() as RoutineGate.Result.Ask
        now += 5 * 60_000 + 1
        gate.answered(b.id, yes = true)
        val c = save() as RoutineGate.Result.Ask
        gate.revoke()
        gate.answered(c.id, yes = true)
        saved shouldBe emptyList()
    }

    @Test fun removingAndRunningAlsoWaitForTheTap() {
        var removed = 0
        val a = gate.act("remove", "r1", "s1") { removed++; "Routine removed" } as RoutineGate.Result.Ask
        removed shouldBe 0
        gate.answered(a.id, yes = true)
        removed shouldBe 1
    }
}
