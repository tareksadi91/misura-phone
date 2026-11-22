package com.misura.phone.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.misura.core.task.Task
import com.misura.phone.cards.Card
import com.misura.phone.cards.cardFor

/** A task that failed or couldn't be confirmed; detailed history is debug-only. */
object ProblemAlert {
    private const val CHANNEL = "problems"
    fun id(taskId: String) = ("problem:$taskId").hashCode()

    fun show(ctx: Context, t: Task) {
        if (com.misura.phone.BuildConfig.DEBUG) Log.w("Misura", "problem ${t.id} ${t.state}: " + t.history.joinToString(" | ") { "${it.at} ${it.from}->${it.to} ${it.note} ${it.evidence ?: ""}".trim() })
        val c = cardFor(t, emptyList()) as? Card.Problem ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Problems", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, id(t.id), taskNotificationIntent(ctx, t.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_error)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentTitle("${c.word} · ${c.title}").setContentText(c.line).setContentIntent(open).setAutoCancel(true).build()
        nm.notify(id(t.id), n)
    }

    /** An agent card that failed: same channel, opens Home on that card. */
    fun showSession(ctx: Context, sessionId: String, line: String) {
        if (com.misura.phone.BuildConfig.DEBUG) Log.w("Misura", "agent problem $sessionId: $line")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Problems", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, id(sessionId), Intent(ctx, com.misura.phone.HomeActivity::class.java).putExtra("session", sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id(sessionId), Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_error)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentTitle("Misura couldn't answer").setContentText(line).setContentIntent(open).setAutoCancel(true).build())
    }
}
