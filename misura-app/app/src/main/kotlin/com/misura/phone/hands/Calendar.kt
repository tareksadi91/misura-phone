package com.misura.phone.hands

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import com.misura.core.agent.CalendarBusy
import com.misura.core.agent.CalendarCreate
import com.misura.core.agent.CalendarGate
import com.misura.core.agent.RunningFor
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.freeSlots
import com.misura.core.agent.schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

/** Android's synced calendar store. No app launch, account credentials, or permission prompt. */
class Calendar(private val ctx: Context, private val gate: CalendarGate = CalendarGate()) : Tool {
    override val spec = ToolSpec("calendar",
        "Use the phone's visible calendars. action=events lists up to 30 expanded instances; free returns gaps of at least minutes (default 30), within dayStart/dayEnd (HH:mm, default 08:00/20:00). from/to are ISO local date-times, default today midnight to the next midnight. action=create needs title, start and end (or minutes); start/end are ISO local date-times. Optional location, description, calendar (display name). It always asks on a card first; after Add, repeat exactly the same args to insert once. No creates on Don't.",
        schema("action" to "string", "from" to "string", "to" to "string", "minutes" to "integer",
            "dayStart" to "string", "dayEnd" to "string", "title" to "string", "start" to "string", "end" to "string",
            "location" to "string", "description" to "string", "calendar" to "string", required = listOf("action")))

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.minutes(): Int = if ("minutes" !in this) 30 else {
        requireNotNull((this["minutes"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }) { "minutes must be a positive integer" }
    }
    override fun label(args: JsonObject) = when (args.s("action")) {
        "create" -> "Adding ${args.s("title") ?: "an event"}"
        "free" -> "Finding free time"
        else -> "Reading calendar"
    }
    override fun summary(result: JsonObject) = result.s("summary")
    /** The phone's own calendar app, so the step shows its mark (not the place pin). */
    override val app: String? by lazy {
        ctx.packageManager.resolveActivity(android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_CALENDAR), 0)?.activityInfo?.packageName
    }
    fun answered(id: String, yes: Boolean) = gate.answered(id, yes)
    fun stopped() = gate.revoke()

    override suspend fun run(args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val action = args.s("action")
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            (action == "create" && ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)) {
            return@withContext permissionError()
        }
        try {
            val zone = ZoneId.systemDefault()
            when (action) {
                "events", "free" -> {
                    val from = args.s("from")?.let { local(it, zone) } ?: LocalDate.now(zone).atStartOfDay(zone).toInstant()
                    val to = args.s("to")?.let { local(it, zone) } ?: from.atZone(zone).plusDays(1).toInstant()
                    require(from < to) { "from must be before to" }
                    if (action == "events") events(from, to, zone) else {
                        val slots = freeSlots(from, to, instances(from, to, busyOnly = true).map { CalendarBusy(it.start, it.end) }, zone,
                            args.minutes(), args.s("dayStart")?.let(LocalTime::parse) ?: LocalTime.of(8, 0),
                            args.s("dayEnd")?.let(LocalTime::parse) ?: LocalTime.of(20, 0))
                        buildJsonObject {
                            put("slots", buildJsonArray { slots.forEach { slot -> add(buildJsonObject {
                                put("start", stamp(slot.start, zone)); put("end", stamp(slot.end, zone))
                            }) } })
                            put("summary", if (slots.size == 1) "1 free slot" else "${slots.size} free slots")
                        }
                    }
                }
                "create" -> create(args, zone)
                else -> error("action must be events, free or create")
            }
        } catch (e: SecurityException) {
            permissionError()
        } catch (e: DateTimeException) {
            error("invalid local date/time")
        } catch (e: IllegalArgumentException) {
            error(e.message ?: "invalid calendar arguments")
        } catch (e: RuntimeException) {
            if (e is java.util.concurrent.CancellationException) throw e
            Log.w("MisuraCalendar", "calendar provider failed (${e.javaClass.simpleName})")
            error("calendar provider failed")
        }
    }

    private data class Instance(val id: Long, val title: String, val start: Instant, val end: Instant, val allDay: Boolean, val location: String, val calendar: String)

    private fun instances(from: Instant, to: Instant, busyOnly: Boolean = false, limit: Int? = null): List<Instance> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli()); ContentUris.appendId(it, to.toEpochMilli())
        }.build()
        val columns = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION, Instances.CALENDAR_DISPLAY_NAME)
        val selection = "${Instances.VISIBLE}=1 AND ${Instances.BEGIN}<? AND ${Instances.END}>?" +
            if (busyOnly) " AND ${Instances.ALL_DAY}=0 AND ${Instances.AVAILABILITY}=${Events.AVAILABILITY_BUSY}" else ""
        return buildList {
            val cursor = checkNotNull(ctx.contentResolver.query(uri, columns, selection,
                arrayOf(to.toEpochMilli().toString(), from.toEpochMilli().toString()), "${Instances.BEGIN} ASC, ${Instances.EVENT_ID} ASC"))
            cursor.use { c ->
                while ((limit == null || size < limit) && c.moveToNext()) {
                    add(Instance(c.getLong(0), c.getString(1).orEmpty(), Instant.ofEpochMilli(c.getLong(2)), Instant.ofEpochMilli(c.getLong(3)),
                        c.getInt(4) != 0, c.getString(5).orEmpty(), c.getString(6).orEmpty()))
                }
            }
        }
    }

    private fun events(from: Instant, to: Instant, zone: ZoneId): JsonObject {
        val found = instances(from, to, limit = 30)
        return buildJsonObject {
            put("events", buildJsonArray { found.forEach { e -> add(buildJsonObject {
                put("id", e.id); put("title", e.title)
                val eventZone = if (e.allDay) ZoneOffset.UTC else zone
                put("start", stamp(e.start, eventZone)); put("end", stamp(e.end, eventZone))
                put("allDay", e.allDay); put("location", e.location); put("calendar", e.calendar)
            }) } })
            put("summary", "${found.size} ${if (found.size == 1) "event" else "events"}${if (found.size == 30) " (up to 30 shown)" else ""}")
        }
    }

    private data class Writable(val id: Long, val name: String, val primary: Boolean)
    private fun writable(name: String?): Writable {
        val found = buildList {
            val cursor = checkNotNull(ctx.contentResolver.query(Calendars.CONTENT_URI,
                arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.IS_PRIMARY),
                "${Calendars.VISIBLE}=1 AND ${Calendars.CALENDAR_ACCESS_LEVEL}>=?",
                arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "${Calendars._ID} ASC"))
            cursor.use { c -> while (c.moveToNext()) add(Writable(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2) != 0)) }
        }
        if (name != null) {
            val matches = found.filter { it.name.equals(name, ignoreCase = true) }
            require(matches.size == 1) { "calendar name must match one visible writable calendar" }
            return matches.single()
        }
        return requireNotNull(found.firstOrNull { it.primary } ?: found.firstOrNull()) { "no visible writable calendar" }
    }

    private suspend fun create(args: JsonObject, zone: ZoneId): JsonObject {
        val title = requireNotNull(args.s("title")) { "title required" }
        val start = local(requireNotNull(args.s("start")) { "start required" }, zone)
        val end = args.s("end")?.let { local(it, zone) } ?: run {
            require("minutes" in args) { "end or minutes required" }
            start.plusSeconds(args.minutes().toLong() * 60)
        }
        require(start < end) { "start must be before end" }
        val calendar = writable(args.s("calendar"))
        val event = CalendarCreate(title, start, end, calendar.id, zone.id, args.s("location"), args.s("description"))
        coroutineContext.ensureActive()
        return when (val result = gate.create(event, coroutineContext[RunningFor]?.session) { insert(it) }) {
            is CalendarGate.Result.Ask -> buildJsonObject {
                put("askUser", buildJsonObject {
                    put("id", result.id); put("line", "Add ${describe(event, calendar.name, zone)}")
                    put("choices", buildJsonArray { add(JsonPrimitive("Add")); add(JsonPrimitive("Don't")) })
                })
            }
            is CalendarGate.Result.Added -> buildJsonObject {
                put("id", result.id); put("summary", "Added “${event.title}”")
            }
        }
    }

    private fun insert(event: CalendarCreate): Long {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, event.calendarId); put(Events.TITLE, event.title)
            put(Events.DTSTART, event.start.toEpochMilli()); put(Events.DTEND, event.end.toEpochMilli())
            put(Events.EVENT_TIMEZONE, event.zone); put(Events.ALL_DAY, 0); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            event.location?.let { put(Events.EVENT_LOCATION, it) }; event.description?.let { put(Events.DESCRIPTION, it) }
        }
        return ContentUris.parseId(checkNotNull(ctx.contentResolver.insert(Events.CONTENT_URI, values)))
    }

    private fun describe(event: CalendarCreate, calendar: String, zone: ZoneId): String {
        val start = event.start.atZone(zone); val end = event.end.atZone(zone)
        val date = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.ENGLISH)
        val until = if (start.toLocalDate() == end.toLocalDate()) DateTimeFormatter.ofPattern("HH:mm") else date
        return "“${event.title}” ${start.format(date)}–${end.format(until)} to $calendar" +
            (event.location?.let { " · $it" } ?: "") + "?" + (event.description?.let { "\n$it" } ?: "")
    }

    private fun local(value: String, zone: ZoneId): Instant {
        // The model sometimes adds an offset or Z; honour it rather than refuse.
        runCatching { return java.time.OffsetDateTime.parse(value).toInstant() }
        val local = LocalDateTime.parse(value)
        require(zone.rules.getValidOffsets(local).isNotEmpty()) { "local date/time falls in a daylight-saving gap" }
        // shortcut: an ambiguous local time uses the earlier offset; add an offset argument if users need the second occurrence.
        return local.atZone(zone).toInstant()
    }
    private fun stamp(time: Instant, zone: ZoneId) = time.atZone(zone).toOffsetDateTime().toString()
    private fun error(message: String) = buildJsonObject { put("error", message) }
    private fun permissionError(): JsonObject {
        Log.w("MisuraCalendar", "calendar permission not granted")
        return error("calendar permission not granted")
    }
}
