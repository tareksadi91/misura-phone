package com.misura.core.agent

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class CalendarBusy(val start: Instant, val end: Instant, val allDay: Boolean = false, val busy: Boolean = true)
data class FreeSlot(val start: Instant, val end: Instant)

/** Clips to each local day's working hours, merging busy intervals before finding gaps. */
fun freeSlots(
    from: Instant, to: Instant, instances: List<CalendarBusy>, zone: ZoneId,
    minutes: Int = 30, dayStart: LocalTime = LocalTime.of(8, 0), dayEnd: LocalTime = LocalTime.of(20, 0),
): List<FreeSlot> {
    require(from < to) { "from must be before to" }
    require(minutes > 0) { "minutes must be positive" }
    require(dayStart < dayEnd) { "dayStart must be before dayEnd" }
    val minimum = Duration.ofMinutes(minutes.toLong())
    val busy = instances.filter { it.busy && !it.allDay && it.start < it.end && it.start < to && it.end > from }.sortedBy { it.start }
    return buildList {
        var day = from.atZone(zone).toLocalDate()
        val last = to.atZone(zone).toLocalDate()
        while (day <= last) {
            var cursor = maxOf(from, day.atTime(dayStart).atZone(zone).toInstant())
            val end = minOf(to, day.atTime(dayEnd).atZone(zone).toInstant())
            for (event in busy) {
                if (cursor >= end || event.start >= end) break
                if (event.end <= cursor) continue
                val gapEnd = minOf(event.start, end)
                if (Duration.between(cursor, gapEnd) >= minimum) add(FreeSlot(cursor, gapEnd))
                cursor = maxOf(cursor, event.end)
            }
            if (Duration.between(cursor, end) >= minimum) add(FreeSlot(cursor, end))
            day = day.plusDays(1)
        }
    }
}
