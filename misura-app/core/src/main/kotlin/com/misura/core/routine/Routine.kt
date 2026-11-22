// Adapted from OpenPhoneUse (https://github.com/joungminsung/OpenPhoneUse), app/.../routine/Routine.kt,
// NextRun.kt and RoutineEngine.kt. Copyright (c) 2026 joungminsung. MIT License; see licenses/OpenPhoneUse-MIT.txt.
package com.misura.core.routine

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** What starts a routine. Launch scope: a time of day on chosen weekdays (OpenPhoneUse also has place, notification, device and before-event). */
@Serializable
sealed interface Trigger {
    /** [days] empty = every day. */
    @Serializable data class Time(val hour: Int, val minute: Int, val days: Set<DayOfWeek> = emptySet()) : Trigger
}

/**
 * When a triggered routine may actually run. [days]/[timeFrom]–[timeTo] narrow it (the window may wrap past midnight);
 * [cooldownMin] keeps a trigger that fires again soon from repeating it; [ONCE_A_DAY] or more means once per calendar day.
 */
@Serializable
data class Conditions(
    val days: Set<DayOfWeek>? = null,
    @Serializable(with = LocalTimeText::class) val timeFrom: LocalTime? = null,
    @Serializable(with = LocalTimeText::class) val timeTo: LocalTime? = null,
    val cooldownMin: Int = DEFAULT_COOLDOWN_MIN,
) {
    companion object {
        const val DEFAULT_COOLDOWN_MIN = 60
        const val ONCE_A_DAY = 24 * 60
    }
}

/**
 * A saved routine: at its time, [prompt] goes to Misura as a new request, as if the user had asked it then. Its result
 * arrives like any other (a notification when Misura is not in front); a message it drafts waits on its card for a tap.
 * Unlike OpenPhoneUse there is no send-without-confirm switch: a routine never sends on its own.
 */
@Serializable
data class Routine(
    val id: String,
    val title: String,
    val trigger: Trigger,
    val prompt: String,
    val conditions: Conditions = Conditions(),
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    /** The last run a trigger started; the cooldown counts from it. */
    val cooldownFrom: Long? = null,
    /** The session the last run opened (where its result is). */
    val lastSession: String? = null,
) {
    companion object {
        const val MAX_TITLE = 40
        const val MAX_PROMPT = 1000
        const val MAX_ROUTINES = 20
    }
}

/** When a time routine runs next. Pure. */
object NextRun {
    private const val DAYS_AHEAD = 8L

    /**
     * The first time after [now] (strictly) at the trigger's hour and minute on a day both the trigger's days and the
     * conditions' days allow. Null for a disabled routine or days that never meet. A time that does not exist that day
     * (DST gap) moves forward, as [ZonedDateTime] does.
     */
    fun compute(routine: Routine, now: ZonedDateTime): ZonedDateTime? {
        if (!routine.enabled) return null
        val t = routine.trigger as? Trigger.Time ?: return null
        val clock = LocalTime.of(t.hour, t.minute)
        for (offset in 0..DAYS_AHEAD) {
            val day = now.toLocalDate().plusDays(offset)
            val dow = day.dayOfWeek
            if (t.days.isNotEmpty() && dow !in t.days) continue
            if (routine.conditions.days?.let { dow !in it } == true) continue
            val at = ZonedDateTime.of(day, clock, now.zone)
            if (at.isAfter(now)) return at
        }
        return null
    }

    /** The soonest run among [routines], for the one alarm. */
    fun soonest(routines: List<Routine>, now: ZonedDateTime): Pair<Routine, ZonedDateTime>? =
        routines.mapNotNull { r -> compute(r, now)?.let { r to it } }.minByOrNull { it.second }
}

/** Something that may start a routine. */
sealed interface RoutineEvent {
    /** The alarm found [routineId]'s time due. */
    data class TimeDue(val routineId: String) : RoutineEvent
    /** "Run now" from the list: no trigger, condition or cooldown check. */
    data object Manual : RoutineEvent
}

enum class Decision { RUN, DEFER, SKIP_COOLDOWN, SKIP_CONDITION }

/** Whether a routine runs for an event (pure). Order: trigger matches, then conditions, then cooldown; a busy runner defers. */
object RoutineEngine {
    /** How long a deferred run may wait for the runner to become free. */
    const val DEFER_MAX_MS = 30 * 60_000L
    /** An alarm that rings this late (phone off, Doze) still runs; later than this it is skipped until next time. */
    const val LATE_MAX_MS = 15 * 60_000L

    fun shouldRun(routine: Routine, event: RoutineEvent, now: ZonedDateTime, runnerBusy: Boolean = false): Decision {
        if (event != RoutineEvent.Manual) {
            if (!routine.enabled || !matches(routine, event)) return Decision.SKIP_CONDITION
            if (!conditionsHold(routine.conditions, now)) return Decision.SKIP_CONDITION
            if (coolingDown(routine, now)) return Decision.SKIP_COOLDOWN
        }
        if (runnerBusy) return Decision.DEFER
        return Decision.RUN
    }

    fun stillDue(queuedAt: Long, now: Long): Boolean = now - queuedAt <= DEFER_MAX_MS

    fun matches(routine: Routine, event: RoutineEvent): Boolean = when (routine.trigger) {
        is Trigger.Time -> event is RoutineEvent.TimeDue && event.routineId == routine.id
    }

    private fun conditionsHold(c: Conditions, now: ZonedDateTime): Boolean {
        if (c.days != null && now.dayOfWeek !in c.days) return false
        if (c.timeFrom != null && c.timeTo != null && !inWindow(now.toLocalTime(), c.timeFrom, c.timeTo)) return false
        return true
    }

    /** [from]–[to] inclusive of from, exclusive of to; wraps past midnight when from is after to. */
    private fun inWindow(t: LocalTime, from: LocalTime, to: LocalTime): Boolean =
        if (!from.isAfter(to)) !t.isBefore(from) && t.isBefore(to) else !t.isBefore(from) || t.isBefore(to)

    private fun coolingDown(routine: Routine, now: ZonedDateTime): Boolean {
        val last = routine.cooldownFrom ?: return false
        val cooldown = routine.conditions.cooldownMin
        if (cooldown <= 0) return false
        if (cooldown >= Conditions.ONCE_A_DAY) return Instant.ofEpochMilli(last).atZone(now.zone).toLocalDate() == now.toLocalDate()
        return now.toInstant().toEpochMilli() - last < cooldown * 60_000L
    }
}

object LocalTimeText : kotlinx.serialization.KSerializer<LocalTime> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("LocalTime", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: LocalTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}
