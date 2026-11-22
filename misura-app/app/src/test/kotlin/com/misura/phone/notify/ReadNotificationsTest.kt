package com.misura.phone.notify

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadNotificationsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearBlocks() {
        ctx.getSharedPreferences("notification_blocks", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun readsCurrentTextNewestFirstWithoutExposingExtras() = runBlocking {
        val older = notification("app.mail", 100, "Colleague", "Meeting moved")
        val newer = notification("app.chat", 200, "Friend", "Lunch?")
        newer.notification.extras.putString("private-extra", "must-not-leave")
        val result = ReadNotifications(ctx) { listOf(older, newer) }.run(JsonObject(emptyMap()))
        val rows = result["notifications"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals("app.chat", rows[0].jsonObject["package"]!!.jsonPrimitive.content)
        assertEquals("Friend", rows[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue(rows[0].jsonObject["text"]!!.jsonPrimitive.content.contains("Lunch?"))
        assertEquals("app.mail", rows[1].jsonObject["package"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("must-not-leave"))
    }

    @Test fun respectsBlocksSelfAndGroupSummariesAndRechecksBlocksEachTime() = runBlocking {
        val visible = notification("app.chat", 100, "Friend", "Hello")
        val blocked = notification("app.blocked", 200, "Private", "hidden-content")
        val summary = notification("app.chat", 300, "Chats", "summary-content").apply {
            notification.flags = notification.flags or Notification.FLAG_GROUP_SUMMARY
        }
        val own = notification(ctx.packageName, 400, "Misura", "own-content")
        val access = NotificationAccess(ctx)
        access.setBlocked("app.blocked", true)
        val tool = ReadNotifications(ctx) { listOf(visible, blocked, summary, own) }
        assertEquals(1, tool.run(JsonObject(emptyMap()))["notifications"]!!.jsonArray.size)
        access.setBlocked("app.chat", true)
        assertTrue(tool.run(JsonObject(emptyMap()))["notifications"]!!.jsonArray.isEmpty())
    }

    @Test fun filtersOneAppAndBoundsReturnedTextAndCount() = runBlocking {
        val many = (1L..15L).map { notification("app.chat", it, "Friend", "x".repeat(5000)) }
        val tool = ReadNotifications(ctx) { many + notification("app.mail", 100, "Mail", "Other app") }
        val result = tool.run(buildJsonObject { put("app", "app.chat") })
        assertTrue(result["truncated"]!!.jsonPrimitive.boolean)
        val rows = result["notifications"]!!.jsonArray
        assertTrue(rows.size in 1 until 15)
        assertTrue(rows.all { it.jsonObject["package"]!!.jsonPrimitive.content == "app.chat" })
        assertTrue(rows.all { it.jsonObject["text"]!!.jsonPrimitive.content.length <= 2000 })
    }

    @Test fun distinguishesDisconnectedDeniedAndEmptyWithoutLeakingExceptionText() = runBlocking {
        assertNotNull(ReadNotifications(ctx) { null }.run(JsonObject(emptyMap()))["error"])
        val denied = ReadNotifications(ctx) { throw SecurityException("private-provider-details") }.run(JsonObject(emptyMap()))
        assertNotNull(denied["error"])
        assertFalse(denied.toString().contains("private-provider-details"))
        val empty = ReadNotifications(ctx) { emptyList() }.run(JsonObject(emptyMap()))
        assertNull(empty["error"])
        assertTrue(empty["notifications"]!!.jsonArray.isEmpty())
    }

    @Test fun invalidAppFilterDoesNotReadEveryApp() = runBlocking {
        var read = false
        val result = ReadNotifications(ctx) { read = true; emptyList() }
            .run(buildJsonObject { put("app", 42) })
        assertNotNull(result["error"])
        assertFalse(read)
    }

    @Suppress("DEPRECATION")
    @Test fun newestMessageSurvivesLongOlderMessagesAndReportsClipping() = runBlocking {
        val self = Person.Builder().setName("Me").setKey("self").build()
        val friend = Person.Builder().setName("Friend").setKey("friend").build()
        val style = NotificationCompat.MessagingStyle(self)
            .addMessage("x".repeat(1000), 100, friend)
            .addMessage("y".repeat(1000), 101, friend)
            .addMessage("Latest urgent message", 200, friend)
        val notification = NotificationCompat.Builder(ctx, "test").setStyle(style).build()
        val sbn = StatusBarNotification("app.chat", "app.chat", 1, null, Process.myUid(), 0, 0,
            notification, Process.myUserHandle(), 200)
        val result = ReadNotifications(ctx) { listOf(sbn) }.run(JsonObject(emptyMap()))
        val row = result["notifications"]!!.jsonArray.single().jsonObject
        assertTrue(row["text"]!!.jsonPrimitive.content.contains("Latest urgent message"))
        assertTrue(row["textTruncated"]!!.jsonPrimitive.boolean)
    }

    @Suppress("DEPRECATION")
    private fun notification(pkg: String, at: Long, title: String, text: String) =
        StatusBarNotification(pkg, pkg, at.toInt(), null, Process.myUid(), 0, 0,
            Notification.Builder(ctx, "test").setContentTitle(title).setContentText(text).build(),
            Process.myUserHandle(), at)
}
