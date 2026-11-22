package com.misura.core.agent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class CalendarGateTest {
    private var now = 0L
    private val gate = CalendarGate(clock = { now })
    private val event = CalendarCreate("Calendar test", Instant.parse("2026-11-19T12:00:00Z"), Instant.parse("2026-11-19T13:00:00Z"), 7, "UTC")
    private val inserted = mutableListOf<CalendarCreate>()
    private fun create(e: CalendarCreate = event, session: String = "one") = gate.create(e, session) { inserted += it; 42L }
    private fun ask(e: CalendarCreate = event, session: String = "one") = (create(e, session) as CalendarGate.Result.Ask).id

    @Test fun firstCallAsksThenYesInsertsExactlyOnce() {
        val id = ask()
        id.startsWith("cal:") shouldBe true
        inserted shouldBe emptyList()
        gate.answered(id, true)
        create() shouldBe CalendarGate.Result.Added(42)
        inserted shouldBe listOf(event)
        (create() is CalendarGate.Result.Ask) shouldBe true
        gate.answered(id, true)
        (create() is CalendarGate.Result.Ask) shouldBe true
        inserted.size shouldBe 1
    }

    @Test fun noYesOrNoOrInventedIdNeverInserts() {
        ask()
        (create() is CalendarGate.Result.Ask) shouldBe true
        gate.answered("cal:invented", true)
        val id = ask()
        gate.answered(id, false)
        gate.answered(id, true)
        (create() is CalendarGate.Result.Ask) shouldBe true
        inserted shouldBe emptyList()
    }

    @Test fun everyChangedFieldNeedsANewCardAndRetiresOldYes() {
        listOf(event.copy(title = "Other"), event.copy(start = event.start.plusSeconds(60)), event.copy(end = event.end.plusSeconds(60)),
            event.copy(calendarId = 8), event.copy(zone = "Europe/Berlin"), event.copy(location = "Test room"), event.copy(description = "Test note")).forEach { changed ->
            gate.answered(ask(), true)
            (create(changed) is CalendarGate.Result.Ask) shouldBe true
            (create() is CalendarGate.Result.Ask) shouldBe true
        }
        inserted shouldBe emptyList()
    }

    @Test fun anotherSessionCannotUseYesButOwnerStillCan() {
        gate.answered(ask(), true)
        (create(session = "two") is CalendarGate.Result.Ask) shouldBe true
        create() shouldBe CalendarGate.Result.Added(42)
        inserted shouldBe listOf(event)
    }

    @Test fun expiresAtFiveMinutesAndOldCardsCannotGrant() {
        gate.answered(ask(), true)
        now = 300_000
        (create() is CalendarGate.Result.Ask) shouldBe true
        val stale = ask()
        now += 300_000
        gate.answered(stale, true)
        (create() is CalendarGate.Result.Ask) shouldBe true
        inserted shouldBe emptyList()
    }

    @Test fun stillWorksImmediatelyBeforeExpiry() {
        gate.answered(ask(), true)
        now = 299_999
        create() shouldBe CalendarGate.Result.Added(42)
    }

    @Test fun stopRevokesBothYesAndUnansweredCards() {
        val id = ask()
        gate.revoke()
        gate.answered(id, true)
        (create() is CalendarGate.Result.Ask) shouldBe true
        gate.answered(ask(), true)
        gate.revoke()
        (create() is CalendarGate.Result.Ask) shouldBe true
        inserted shouldBe emptyList()
    }

    @Test fun providerFailureConsumesYesSoRetryAsks() {
        gate.answered(ask(), true)
        assertThrows(IllegalStateException::class.java) { gate.create(event, "one") { throw IllegalStateException("provider failed") } }
        (create() is CalendarGate.Result.Ask) shouldBe true
        inserted shouldBe emptyList()
    }

    @Test fun duplicateYesDoesNotExtendExpiry() {
        val id = ask()
        gate.answered(id, true)
        now = 299_999
        gate.answered(id, true)
        now = 300_000
        (create() is CalendarGate.Result.Ask) shouldBe true
        inserted shouldBe emptyList()
    }

    @Test fun noRevokesAnUnusedYes() {
        val id = ask()
        gate.answered(id, true)
        gate.answered(id, false)
        (create() is CalendarGate.Result.Ask) shouldBe true
        inserted shouldBe emptyList()
    }
}
