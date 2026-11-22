package com.misura.phone.hands

import android.app.UiAutomation
import android.content.ContentUris
import android.content.ContentValues
import android.os.ParcelFileDescriptor
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.RunningFor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CalendarTest {
    @Test fun approvedCreateReadsBackAndBlocksFreeTime() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        listOf("READ_CALENDAR", "WRITE_CALENDAR").forEach { permission ->
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("pm grant ${ctx.packageName} android.permission.$permission")).use { it.readBytes() }
        }
        val resolver = ctx.contentResolver
        var createdCalendar: android.net.Uri? = null
        val calendar = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME),
            "${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.VISIBLE}=1 AND ${Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "${Calendars._ID} ASC")!!.use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
        }
        val name = calendar?.second ?: "Misura test ${UUID.randomUUID()}"
        val title = "Calendar round trip ${UUID.randomUUID()}"
        var eventId: Long? = null
        try {
            if (calendar == null) {
                val uri = Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(Calendars.ACCOUNT_NAME, name)
                    .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
                createdCalendar = resolver.insert(uri, ContentValues().apply {
                    put(Calendars.ACCOUNT_NAME, name); put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(Calendars.NAME, name); put(Calendars.CALENDAR_DISPLAY_NAME, name)
                    put(Calendars.OWNER_ACCOUNT, name); put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                    put(Calendars.VISIBLE, 1); put(Calendars.SYNC_EVENTS, 1); put(Calendars.CALENDAR_COLOR, 0xff888888.toInt())
                    put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
                })
                assertNotNull("local test calendar created", createdCalendar)
            }
            val tool = Calendar(ctx)
            val start = LocalDate.now().plusDays(10).atTime(11, 0)
            val end = start.plusHours(1)
            val create = buildJsonObject {
                put("action", "create"); put("title", title); put("start", start.toString()); put("minutes", 60)
                put("calendar", name); put("location", "Test room"); put("description", "Instrumentation fixture")
            }
            withContext(RunningFor("calendar-test")) {
                val first = tool.run(create)
                assertTrue(first.toString(), "askUser" in first)
                assertFalse("first call did not insert", "id" in first)
                resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.TITLE}=?", arrayOf(title), null)!!.use {
                    assertEquals("no event before Add", 0, it.count)
                }
                val question = first.getValue("askUser").jsonObject
                assertEquals(listOf("Add", "Don't"), question.getValue("choices").jsonArray.map { it.jsonPrimitive.content })
                val gate = question.getValue("id").jsonPrimitive.content
                assertTrue(gate.startsWith("cal:"))
                tool.answered(gate, yes = true)
                val added = tool.run(create)
                assertFalse(added.toString(), "error" in added)
                eventId = added.getValue("id").jsonPrimitive.content.toLong()
                val events = tool.run(buildJsonObject {
                    put("action", "events"); put("from", start.minusMinutes(30).toString()); put("to", end.plusMinutes(30).toString())
                })
                val saved = events.getValue("events").jsonArray.map { it.jsonObject }.single { it["id"] == JsonPrimitive(eventId!!) }
                assertEquals(title, saved.getValue("title").jsonPrimitive.content)
                assertEquals("Test room", saved.getValue("location").jsonPrimitive.content)
                assertEquals(name, saved.getValue("calendar").jsonPrimitive.content)
                assertEquals("false", saved.getValue("allDay").jsonPrimitive.content)
                val zone = ZoneId.systemDefault()
                assertEquals(start.atZone(zone).toInstant(), OffsetDateTime.parse(saved.getValue("start").jsonPrimitive.content).toInstant())
                assertEquals(end.atZone(zone).toInstant(), OffsetDateTime.parse(saved.getValue("end").jsonPrimitive.content).toInstant())
                val free = tool.run(buildJsonObject {
                    put("action", "free"); put("from", start.toString()); put("to", end.toString()); put("minutes", 30)
                })
                assertEquals("busy event occupies the whole requested range", emptyList<JsonObject>(), free.getValue("slots").jsonArray.toList())
                assertTrue("second insert needs another card", "askUser" in tool.run(create))
                resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.TITLE}=?", arrayOf(title), null)!!.use {
                    assertEquals("one event after one Add", 1, it.count)
                }
            }
        } finally {
            // Clean by unique fixture title too, if insertion succeeded before its result was read.
            try {
                eventId?.let { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, it), null, null) }
                resolver.delete(Events.CONTENT_URI, "${Events.TITLE}=?", arrayOf(title))
            } finally {
                createdCalendar?.let { uri ->
                    resolver.delete(uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(Calendars.ACCOUNT_NAME, name)
                        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build(), null, null)
                }
            }
        }
    }
}
