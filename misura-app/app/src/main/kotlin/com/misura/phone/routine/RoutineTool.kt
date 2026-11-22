// make_routine flow adapted from OpenPhoneUse (https://github.com/joungminsung/OpenPhoneUse), app/.../agent/RoutineModule.kt.
// Copyright (c) 2026 joungminsung. MIT License; see licenses/OpenPhoneUse-MIT.txt.
package com.misura.phone.routine

import com.misura.core.agent.RunningFor
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.schema
import com.misura.core.routine.NextRun
import com.misura.core.routine.Routine
import com.misura.core.routine.RoutineGate
import com.misura.core.routine.Trigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Parses "daily" / "weekdays" / "weekends" / "mon,wed,fri"; null = not understood. */
fun parseDays(text: String?): Set<DayOfWeek>? {
    val t = text?.trim()?.lowercase().orEmpty()
    if (t.isEmpty() || t == "daily" || t == "every day" || t == "everyday") return emptySet()
    if (t == "weekdays") return setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    if (t == "weekends") return setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    return t.split(',', ' ').filter { it.isNotBlank() }.map { w -> DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(w.take(3)) && w.length >= 3 } ?: return null }.toSet()
}

/** "every day at 08:00", "weekdays at 08:00", "Mon, Wed at 18:30". */
fun describe(t: Trigger.Time): String {
    val time = "%02d:%02d".format(t.hour, t.minute)
    val days = when (t.days) {
        emptySet<DayOfWeek>() -> "every day"
        parseDays("weekdays") -> "weekdays"
        parseDays("weekends") -> "weekends"
        else -> t.days.sorted().joinToString(", ") { it.name.take(1) + it.name.substring(1, 3).lowercase() }
    }
    return "$days at $time"
}

/**
 * Scheduled requests. "add" shows the exact routine on a card; only the Save tap stores it ([RoutineGate]).
 * "list" reads stored ones; "remove" and "run" also wait for a tap, so outside text can never add, remove or start a routine.
 */
class RoutineTool(private val store: RoutineStore, private val gate: RoutineGate = RoutineGate(), private val clock: () -> Long = System::currentTimeMillis,
                  private val changed: () -> Unit = {}, private val runNow: suspend (Routine) -> String? = { null },
                  /** Starts a run tapped on a card off the tap's thread. */
                  private val launch: (suspend () -> Unit) -> Unit = {}) : Tool {
    override val spec = ToolSpec("routine",
        "Schedule a request to run by itself later, repeating at a time of day (\"every weekday at 8, tell me if my train is late\"). action=add with title (short), time (HH:MM, 24 h), " +
            "days (daily, weekdays, weekends, or e.g. mon,wed,fri), prompt (the request exactly as it should run then, self-contained) and summary (what you will do each time, in your own voice, at most 8 words: \"I'll check if your train is late\"). " +
            "This shows a card; the user's tap stores it. The app then tells you what the tap did: say only that, in two or three words. " +
            "A routine run can search, look things up, read the calendar, contacts and notifications and draft messages (a draft waits for the user's tap); it cannot operate apps. " +
            "action=list shows saved routines; action=remove with id deletes one; action=run with id runs one now.",
        schema("action" to "string", "title" to "string", "time" to "string", "days" to "string", "prompt" to "string", "summary" to "string", "id" to "string", required = listOf("action")))
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    override fun label(args: JsonObject) = when (args.s("action")) { "add" -> "Setting up a routine"; "remove" -> "Removing a routine"; "run" -> "Running a routine"; else -> "Checking routines" }
    override fun summary(result: JsonObject) = result.s("summary")
    fun answered(id: String, yes: Boolean) = gate.answered(id, yes)
    fun outcome(id: String) = gate.outcome(id)
    fun stopped() = gate.revoke()
    private fun err(m: String) = buildJsonObject { put("error", m) }

    override suspend fun run(args: JsonObject): JsonObject = when (args.s("action")) {
        "add" -> add(args)
        "list" -> buildJsonObject {
            val all = store.all()
            put("routines", buildJsonArray { all.forEach { s -> add(buildJsonObject {
                put("id", s.routine.id); put("title", s.routine.title); put("when", describe(s.routine.trigger as Trigger.Time))
                put("prompt", s.routine.prompt); put("enabled", s.routine.enabled)
            }) } })
            put("summary", if (all.size == 1) "1 routine" else "${all.size} routines")
        }
        "remove", "run" -> stored(args)
        else -> err("action must be add, list, remove or run")
    }

    /** label = when; title = what it does, in a few words; line = the exact thing the tap acts on (design system: the card shows what the tap does). */
    private fun card(r: RoutineGate.Result, label: String, title: String, line: String, go: String): JsonObject = when (r) {
        is RoutineGate.Result.Ask -> buildJsonObject {
            put("askUser", buildJsonObject {
                put("id", r.id); put("label", label); put("title", title); put("line", line)
                put("choices", buildJsonArray { add(JsonPrimitive(go)); add(JsonPrimitive("Don't")) })
            })
        }
        is RoutineGate.Result.Done -> buildJsonObject { put("summary", r.summary) }
    }

    private suspend fun stored(args: JsonObject): JsonObject {
        val what = args.s("action")!!
        val s = args.s("id")?.let { id -> store.all().firstOrNull { it.routine.id == id } } ?: return err("no routine with that id (call list first)")
        val r = gate.act(what, s.routine.id, coroutineContext[RunningFor]?.session) {
            if (what == "remove") { store.remove(s.routine.id); changed(); "Routine removed" }
            else { launch { runNow(s.routine) }; "Started; its result arrives on its own" }
        }
        return card(r, "Routine · ${describe(s.routine.trigger as Trigger.Time)}", if (what == "remove") "Remove this routine?" else "Run it now?",
            "“${s.routine.prompt}”", if (what == "remove") "Remove" else "Run")
    }

    private suspend fun add(args: JsonObject): JsonObject {
        val title = args.s("title")?.take(Routine.MAX_TITLE) ?: return err("title required")
        val prompt = args.s("prompt") ?: return err("prompt required")
        if (prompt.length > Routine.MAX_PROMPT) return err("prompt too long (max ${Routine.MAX_PROMPT} characters)")
        val time = args.s("time")?.let { Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(it) } ?: return err("time must be HH:MM (24 h)")
        val days = parseDays(args.s("days")) ?: return err("days must be daily, weekdays, weekends or day names like mon,wed")
        if (store.all().size >= Routine.MAX_ROUTINES) return err("at most ${Routine.MAX_ROUTINES} routines")
        val trigger = Trigger.Time(time.groupValues[1].toInt(), time.groupValues[2].toInt(), days)
        // The id is not part of what the user approves; a fixed placeholder keeps the digest equal across the ask and the save.
        val draft = Routine("new", title, trigger, prompt)
        val r = gate.save(draft, coroutineContext[RunningFor]?.session) { d ->
            val saved = d.copy(id = UUID.randomUUID().toString(), createdAt = clock())
            if (!store.add(Saved(saved, NextRun.compute(saved, ZonedDateTime.now(ZoneId.systemDefault()))?.toInstant()?.toEpochMilli()), Routine.MAX_ROUTINES))
                return@save "Not saved: there are already ${Routine.MAX_ROUTINES} routines"
            changed()
            "Saved: $title, ${describe(trigger)}"
        }
        val summary = args.s("summary")?.take(80) ?: title
        return card(r, "Routine · ${describe(trigger)}", summary, "“$prompt”", "Save")
    }
}
