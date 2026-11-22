package com.misura.testapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/** A fake chat app: conversations live in memory and show as MessagingStyle notifications with a reply action. */
object Chat {
    data class Line(val fromSelf: Boolean, val sender: String, val text: String, val at: Long)
    class Conv(val key: String, var sender: String, var shortcut: String? = null, var mode: String = "normal", var autoAnswer: Int = 0, val lines: MutableList<Line> = mutableListOf())

    val convs = mutableMapOf<String, Conv>()
    const val CHANNEL = "chat"
    const val KEY_TEXT = "reply_text"
    private val me = Person.Builder().setName("Me").setKey("me").build()

    fun receive(ctx: Context, conv: String, sender: String, text: String, mode: String?, seedSelf: String?, autoAnswer: Int, shortcut: String? = null) {
        val c = convs.getOrPut(conv) { Conv(conv, sender) }.also { if (shortcut != null) it.shortcut = shortcut }
        c.sender = sender; mode?.let { c.mode = it }; if (autoAnswer > 0) c.autoAnswer = autoAnswer
        seedSelf?.let { c.lines += Line(true, "Me", it, System.currentTimeMillis() - 60_000) }
        c.lines += Line(false, sender, text, System.currentTimeMillis())
        post(ctx, c)
    }

    fun replied(ctx: Context, conv: String, text: String) {
        val c = convs[conv] ?: return
        c.lines += Line(true, "Me", text, System.currentTimeMillis())
        post(ctx, c)
        if (c.autoAnswer > 0) {
            val h = Handler(Looper.getMainLooper())
            repeat(c.autoAnswer) { i -> h.postDelayed({ receive(ctx, conv, c.sender, "answer part ${i + 1} to: $text", null, null, 0) }, 1_000L * (i + 1)) }
        }
    }

    fun post(ctx: Context, c: Conv) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chat", NotificationManager.IMPORTANCE_DEFAULT))
        val other = Person.Builder().setName(c.sender).setKey(c.key).build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(c.sender)
        c.lines.forEach { style.addMessage(it.text, it.at, if (it.fromSelf) null else other) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (c.mode == "immutable") PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(ctx, c.key.hashCode(), Intent(ctx, ReplyReceiver::class.java).putExtra("conv", c.key), flags)
        val action = NotificationCompat.Action.Builder(0, "Reply", pi)
            .addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel("Reply").build())
            .setAuthenticationRequired(c.mode == "auth").setAllowGeneratedReplies(false).build()
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style).addAction(action).setOnlyAlertOnce(true).also { b -> c.shortcut?.let { b.setShortcutId(it) } }.build()
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) nm.notify(c.key.hashCode(), n)
        if (c.mode == "cancelled") pi.cancel()
    }
}
