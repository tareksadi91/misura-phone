package com.misura.core.agent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class FreeSlotsTest {
    private val zone = ZoneId.of("UTC")
    private fun at(time: String) = Instant.parse("2026-11-19T$time:00Z")
    private fun busy(start: String, end: String) = CalendarBusy(at(start), at(end))
    private fun slot(start: String, end: String) = FreeSlot(at(start), at(end))

    @Test fun mergesOverlappingNestedAndTouchingEvents() {
        freeSlots(at("08:00"), at("14:00"), listOf(busy("10:00", "12:00"), busy("09:00", "11:00"),
            busy("09:30", "10:00"), busy("12:00", "13:00")), zone) shouldBe listOf(slot("08:00", "09:00"), slot("13:00", "14:00"))
    }

    @Test fun clipsEventsAndHoursToRangeAndTreatsEdgesAsExclusive() {
        freeSlots(at("07:00"), at("21:00"), listOf(busy("06:00", "08:00"), busy("20:00", "22:00")), zone) shouldBe listOf(slot("08:00", "20:00"))
        freeSlots(at("08:30"), at("19:30"), listOf(busy("07:00", "09:00"), busy("19:00", "21:00")), zone) shouldBe listOf(slot("09:00", "19:00"))
        freeSlots(at("08:00"), at("20:00"), listOf(busy("07:00", "21:00")), zone) shouldBe emptyList()
        freeSlots(at("01:00"), at("07:00"), emptyList(), zone) shouldBe emptyList()
    }

    @Test fun ignoresAllDayAndAvailableInstances() {
        freeSlots(at("08:00"), at("20:00"), listOf(busy("08:00", "20:00").copy(allDay = true),
            busy("08:00", "20:00").copy(busy = false)), zone) shouldBe listOf(slot("08:00", "20:00"))
    }

    @Test fun includesExactMinimumButDropsShorterGaps() {
        freeSlots(at("08:00"), at("10:00"), listOf(busy("08:30", "09:31")), zone, minutes = 30) shouldBe listOf(slot("08:00", "08:30"))
        freeSlots(at("08:00"), at("10:00"), listOf(busy("08:29", "09:30")), zone, minutes = 30) shouldBe listOf(slot("09:30", "10:00"))
    }

    @Test fun splitsDaysAndUsesLocalHoursAcrossDaylightSavingChange() {
        val berlin = ZoneId.of("Europe/Berlin")
        val from = Instant.parse("2026-10-24T00:00:00Z"); val to = Instant.parse("2026-10-26T00:00:00Z")
        freeSlots(from, to, emptyList(), berlin, dayStart = LocalTime.of(9, 0), dayEnd = LocalTime.of(10, 0)) shouldBe listOf(
            FreeSlot(Instant.parse("2026-10-24T07:00:00Z"), Instant.parse("2026-10-24T08:00:00Z")),
            FreeSlot(Instant.parse("2026-10-25T08:00:00Z"), Instant.parse("2026-10-25T09:00:00Z")))
    }

    @Test fun rejectsInvalidBoundsAndMinimum() {
        assertThrows(IllegalArgumentException::class.java) { freeSlots(at("10:00"), at("09:00"), emptyList(), zone) }
        assertThrows(IllegalArgumentException::class.java) { freeSlots(at("08:00"), at("09:00"), emptyList(), zone, minutes = 0) }
        assertThrows(IllegalArgumentException::class.java) { freeSlots(at("08:00"), at("09:00"), emptyList(), zone, dayStart = LocalTime.of(20, 0), dayEnd = LocalTime.of(8, 0)) }
    }
}
