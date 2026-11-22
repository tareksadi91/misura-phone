// Cases adapted from OpenPhoneUse NextRunTest/RoutineEngineTest. Copyright (c) 2026 joungminsung. MIT License; see licenses/OpenPhoneUse-MIT.txt.
package com.misura.core.routine

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutineTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    // 2026-11-06 is a Friday.
    private val fridayFivePm = ZonedDateTime.of(2026, 11, 6, 17, 0, 0, 0, berlin)
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private fun routine(trigger: Trigger = Trigger.Time(8, 0), conditions: Conditions = Conditions(), enabled: Boolean = true, cooldownFrom: Long? = null) =
        Routine("r1", "t", trigger, "check the trains", conditions, enabled, cooldownFrom = cooldownFrom)

    @Test fun laterTodayOrTomorrow() {
        NextRun.compute(routine(Trigger.Time(23, 0)), fridayFivePm) shouldBe ZonedDateTime.of(2026, 11, 6, 23, 0, 0, 0, berlin)
        NextRun.compute(routine(Trigger.Time(7, 30)), fridayFivePm) shouldBe ZonedDateTime.of(2026, 11, 7, 7, 30, 0, 0, berlin)
        // Exactly now is not "next": it already fired.
        NextRun.compute(routine(Trigger.Time(17, 0)), fridayFivePm) shouldBe ZonedDateTime.of(2026, 11, 7, 17, 0, 0, 0, berlin)
    }

    @Test fun weekdaysOnlySkipTheWeekend() {
        NextRun.compute(routine(Trigger.Time(8, 0, weekdays)), fridayFivePm) shouldBe ZonedDateTime.of(2026, 11, 9, 8, 0, 0, 0, berlin)
        NextRun.compute(routine(Trigger.Time(8, 0), Conditions(days = weekdays)), fridayFivePm) shouldBe ZonedDateTime.of(2026, 11, 9, 8, 0, 0, 0, berlin)
        NextRun.compute(routine(Trigger.Time(9, 0, setOf(DayOfWeek.SATURDAY))), fridayFivePm) shouldBe ZonedDateTime.of(2026, 11, 7, 9, 0, 0, 0, berlin)
    }

    @Test fun noRunWhenDaysNeverMeetOrDisabled() {
        NextRun.compute(routine(Trigger.Time(8, 0, setOf(DayOfWeek.MONDAY)), Conditions(days = setOf(DayOfWeek.TUESDAY))), fridayFivePm) shouldBe null
        NextRun.compute(routine(Trigger.Time(23, 0), enabled = false), fridayFivePm) shouldBe null
    }

    @Test fun daylightSavingGapMovesForward() {
        // 2026-03-29 02:30 does not exist in Berlin; the run is at 03:30 that day.
        val before = ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, berlin)
        NextRun.compute(routine(Trigger.Time(2, 30)), before) shouldBe ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, berlin)
    }

    @Test fun soonestPicksTheEarliestEnabled() {
        val a = routine(Trigger.Time(23, 0)).copy(id = "a"); val b = routine(Trigger.Time(18, 0)).copy(id = "b"); val c = routine(Trigger.Time(17, 30), enabled = false).copy(id = "c")
        NextRun.soonest(listOf(a, b, c), fridayFivePm)!!.first.id shouldBe "b"
        NextRun.soonest(emptyList(), fridayFivePm) shouldBe null
    }

    @Test fun runsWhenDueAndDefersWhenBusy() {
        RoutineEngine.shouldRun(routine(), RoutineEvent.TimeDue("r1"), fridayFivePm) shouldBe Decision.RUN
        RoutineEngine.shouldRun(routine(), RoutineEvent.TimeDue("r1"), fridayFivePm, runnerBusy = true) shouldBe Decision.DEFER
        RoutineEngine.shouldRun(routine(), RoutineEvent.TimeDue("other"), fridayFivePm) shouldBe Decision.SKIP_CONDITION
        RoutineEngine.shouldRun(routine(enabled = false), RoutineEvent.TimeDue("r1"), fridayFivePm) shouldBe Decision.SKIP_CONDITION
    }

    @Test fun conditionsNarrowDaysAndHours() {
        RoutineEngine.shouldRun(routine(conditions = Conditions(days = setOf(DayOfWeek.MONDAY))), RoutineEvent.TimeDue("r1"), fridayFivePm) shouldBe Decision.SKIP_CONDITION
        RoutineEngine.shouldRun(routine(conditions = Conditions(timeFrom = LocalTime.of(9, 0), timeTo = LocalTime.of(12, 0))), RoutineEvent.TimeDue("r1"), fridayFivePm) shouldBe Decision.SKIP_CONDITION
        // A window that wraps past midnight.
        RoutineEngine.shouldRun(routine(conditions = Conditions(timeFrom = LocalTime.of(16, 0), timeTo = LocalTime.of(2, 0))), RoutineEvent.TimeDue("r1"), fridayFivePm) shouldBe Decision.RUN
    }

    @Test fun cooldownSkipsARepeatAndOnceADayMeansCalendarDay() {
        val tenMinAgo = fridayFivePm.minusMinutes(10).toInstant().toEpochMilli()
        RoutineEngine.shouldRun(routine(cooldownFrom = tenMinAgo), RoutineEvent.TimeDue("r1"), fridayFivePm) shouldBe Decision.SKIP_COOLDOWN
        val morning = fridayFivePm.withHour(1).toInstant().toEpochMilli()
        RoutineEngine.shouldRun(routine(conditions = Conditions(cooldownMin = Conditions.ONCE_A_DAY), cooldownFrom = morning), RoutineEvent.TimeDue("r1"), fridayFivePm) shouldBe Decision.SKIP_COOLDOWN
        val yesterday = fridayFivePm.minusDays(1).toInstant().toEpochMilli()
        RoutineEngine.shouldRun(routine(conditions = Conditions(cooldownMin = Conditions.ONCE_A_DAY), cooldownFrom = yesterday), RoutineEvent.TimeDue("r1"), fridayFivePm) shouldBe Decision.RUN
    }

    @Test fun manualRunSkipsTriggerConditionsAndCooldown() {
        RoutineEngine.shouldRun(routine(enabled = false, cooldownFrom = fridayFivePm.toInstant().toEpochMilli()), RoutineEvent.Manual, fridayFivePm) shouldBe Decision.RUN
    }

    @Test fun deferredRunsExpire() {
        RoutineEngine.stillDue(0, RoutineEngine.DEFER_MAX_MS) shouldBe true
        RoutineEngine.stillDue(0, RoutineEngine.DEFER_MAX_MS + 1) shouldBe false
    }

    @Test fun savedRoutinesRoundTrip() {
        val r = routine(Trigger.Time(8, 15, weekdays), Conditions(timeFrom = LocalTime.of(6, 0), timeTo = LocalTime.of(10, 0), cooldownMin = Conditions.ONCE_A_DAY))
        Json.decodeFromString(Routine.serializer(), Json.encodeToString(Routine.serializer(), r)) shouldBe r
    }
}
