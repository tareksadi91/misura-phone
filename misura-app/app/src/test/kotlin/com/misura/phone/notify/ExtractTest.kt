package com.misura.phone.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtractTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val me = Person.Builder().setName("Me").setKey("me").build()
    private val instinct = Person.Builder().setName("Instinct").setKey("instinct").build()

    private fun sbn(n: Notification, flags: Int = 0) = @Suppress("DEPRECATION")
        StatusBarNotification("com.whatsapp", "com.whatsapp", 7, null, Process.myUid(), 0, 0, n.apply { this.flags = this.flags or flags }, Process.myUserHandle(), 1_000L)

    private fun chat(vararg msgs: Pair<Person?, String>, reply: NotificationCompat.Action? = null): Notification {
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle("Instinct")
        msgs.forEachIndexed { i, (p, t) -> style.addMessage(t, 100L + i, p) }
        return NotificationCompat.Builder(ctx, "c").setSmallIcon(android.R.drawable.sym_def_app_icon).setStyle(style)
            .apply { reply?.let { addAction(it) } }.build()
    }

    private fun replyAction(auth: Boolean = false): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent("reply").setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Action.Builder(0, "Reply", pi).addRemoteInput(RemoteInput.Builder("text").build())
            .setAuthenticationRequired(auth).build()
    }

    @Test fun `messaging style keeps every message with timestamps`() {
        val c = extract(sbn(chat(instinct to "1. Fabrica", instinct to "2. Dear Breakfast")))
        c.msgs.map { it.text } shouldBe listOf("1. Fabrica", "2. Dear Breakfast")
        c.msgs.map { it.sentAt } shouldBe listOf(100L, 101L)
        c.msgs.first().sender shouldBe "Instinct"
    }

    @Test fun `self messages flagged`() {
        val c = extract(sbn(chat(instinct to "Hi", null to "coffee in Lisbon?")))
        c.msgs.map { it.senderIsSelf } shouldBe listOf(false, true)
    }

    @Test fun `summary notification skipped`() {
        extract(sbn(chat(instinct to "Hi"), Notification.FLAG_GROUP_SUMMARY)).msgs shouldBe emptyList()
    }

    @Test fun `auth required action reported`() {
        val c = extract(sbn(chat(instinct to "Hi", reply = replyAction(auth = true))))
        c.replyActionIndex shouldBe 0
        c.replyAuthRequired shouldBe true
    }

    @Test fun `plain notification falls back to big text`() {
        val n = NotificationCompat.Builder(ctx, "c").setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle("ColleagueB")
            .setStyle(NotificationCompat.BigTextStyle().bigText("long text")).build()
        extract(sbn(n)).msgs.single().text shouldBe "long text"
    }

    @Test fun `a group message from FriendB is not FriendB's own chat`() {
        val friendb = Person.Builder().setName("FriendB").setKey("friendb").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle("Climbing").setGroupConversation(true)
        style.addMessage("who's in?", 100L, friendb)
        val n = NotificationCompat.Builder(ctx, "c").setSmallIcon(android.R.drawable.sym_def_app_icon).setStyle(style).build()
        extract(sbn(n)).isConversation("FriendB") shouldBe false
        extract(sbn(n)).isConversation("Climbing") shouldBe true
    }

    @Test fun `plain notification with reply action is not a chat`() {
        val n = NotificationCompat.Builder(ctx, "c").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("ColleagueB").setContentText("mail").addAction(replyAction()).build()
        val c = extract(sbn(n))
        c.replyActionIndex shouldBe 0
        c.isChatNotification() shouldBe false
        chatsIn(listOf(c)) shouldBe emptyList()
    }

    @Test fun `data only reply action does not teach a chat app`() {
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent("data").setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE)
        val action = NotificationCompat.Action.Builder(0, "Attach", pi)
            .addRemoteInput(RemoteInput.Builder("image").setAllowFreeFormInput(false).setAllowDataType("image/png", true).build()).build()
        extract(sbn(chat(instinct to "Hi", reply = action))).isChatNotification() shouldBe false
        extract(sbn(chat(instinct to "Hi", reply = replyAction()))).isChatNotification() shouldBe true
    }
}
