package com.misura.phone.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.misura.core.inbox.AnswerCollector
import com.misura.core.inbox.Msg
import com.misura.core.task.TaskState
import com.misura.core.task.transition
import com.misura.phone.MisuraGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watches the answer to a task's question. When the bot has gone quiet, buzzes once ("Answer ready")
 * and hands the task back to planning so a reply can be drafted.
 */
object AnswerReady {
    const val CHANNEL = "answers"
    const val DEFAULT_QUIET_MS = 20_000L
    private val gate = Mutex()
    private val watching = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val ENDED = setOf(TaskState.DONE, TaskState.FAILED, TaskState.CANCELLED, TaskState.EXPIRED)

    /** Polls until the answer is complete, the task ends, or [until] passes (then the task expires). */
    // ponytail: 500 ms polling per waiting task; fine for a handful of tasks, switch to router callbacks if it grows.
    fun watch(ctx: Context, taskId: String, requestId: String, quietMs: Long, until: Long) = MisuraGraph.scope.launch {
        if (!watching.add(requestId)) return@launch
        try { watchLoop(ctx, taskId, requestId, quietMs, until) } finally { watching.remove(requestId) }
    }

    private suspend fun watchLoop(ctx: Context, taskId: String, requestId: String, quietMs: Long, until: Long) {
        val collector = AnswerCollector(quietMs)
        while (System.currentTimeMillis() < until) {
            val t = MisuraGraph.tasks.load(taskId) ?: return
            // Already answered (maybe before a crash) or over: release the conversation.
            if (t.state in ENDED || t.buzzed) { MisuraGraph.inbox.closeExpectation(requestId); return }
            val parts = MisuraGraph.inbox.answer(requestId)
            if (t.state == TaskState.WAITING && parts.isNotEmpty() && collector.isComplete(parts.maxOf { it.sentAt }, System.currentTimeMillis())) {
                fire(ctx, taskId, requestId, parts); return
            }
            delay(500)
        }
        gate.withLock {
            MisuraGraph.inbox.closeExpectation(requestId)
            MisuraGraph.tasks.load(taskId)?.takeIf { it.state == TaskState.WAITING }
                ?.let { MisuraGraph.tasks.save(it.transition(TaskState.EXPIRED, System.currentTimeMillis(), "no answer in time")) }
        }
    }

    /** After a restart: watch every expectation that is still open. */
    suspend fun rearm(ctx: Context) {
        MisuraGraph.inbox.openExpectations(System.currentTimeMillis()).forEach { watch(ctx, it.taskId, it.requestId, DEFAULT_QUIET_MS, it.until) }
    }

    private suspend fun fire(ctx: Context, taskId: String, requestId: String, parts: List<Msg>) = gate.withLock {
        val t = MisuraGraph.tasks.load(taskId) ?: return@withLock
        if (t.buzzed || t.state != TaskState.WAITING) return@withLock
        // Task first: if we crash before closing the expectation, the next watch sees buzzed and closes it.
        MisuraGraph.tasks.save(t.transition(TaskState.PLANNING, System.currentTimeMillis(), "answer ready (${parts.size} messages)").copy(buzzed = true))
        MisuraGraph.inbox.closeExpectation(requestId)
        notifyOnce(ctx, taskId, parts)
    }

    private fun notifyOnce(ctx: Context, taskId: String, parts: List<Msg>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Answers", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, taskId.hashCode(), taskNotificationIntent(ctx, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentTitle("Answer ready").setContentText(parts.first().text).setContentIntent(open)
            .setOnlyAlertOnce(true).setAutoCancel(true).build()
        nm.notify(taskId.hashCode(), n)
    }
}
