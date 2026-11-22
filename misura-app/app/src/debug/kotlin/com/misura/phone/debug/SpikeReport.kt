package com.misura.phone.debug

import android.content.Context
import android.util.Log
import com.misura.phone.MisuraGraph
import com.misura.phone.notify.MisuraListener
import com.misura.phone.notify.extract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One experiment run as markdown for docs/spikes: states with timings, send attempts, the answer as captured,
 * and what the app's notifications carry. Contains real message text: read it before committing.
 */
object SpikeReport {
    suspend fun write(ctx: Context, taskId: String): File {
        val g = MisuraGraph
        val t = g.tasks.load(taskId)
        val p = t?.payload
        val pkg = p?.get("pkg") ?: when (p?.get("channel")) { "whatsapp" -> "com.whatsapp"; else -> "com.misura.testapp" }
        val t0 = t?.createdAt ?: 0
        fun rel(at: Long) = "+%.1fs".format((at - t0) / 1000.0)
        val out = buildString {
            appendLine("# Experiment run ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t0))}")
            appendLine(); appendLine("- task `$taskId`, channel `${p?.get("channel")}`, final state **${t?.state}**")
            appendLine("- to: ${p?.get("name")} (`${p?.get("recipient")}`)")
            appendLine(); appendLine("## States"); t?.history?.forEach { appendLine("- ${rel(it.at)} ${it.from} → ${it.to}: ${it.note}${it.evidence?.let { e -> " ($e)" } ?: ""}") }
            appendLine(); appendLine("## Send attempts")
            g.tasks.attemptsFor(taskId).forEach { a -> appendLine("- ${a.handId} ${a.status} claimed ${rel(a.claimedAt)}${a.dispatchedAt?.let { d -> " dispatched ${rel(d)}" } ?: ""}: ${a.evidence ?: ""}") }
            val answer = g.inbox.answer("$taskId:ask")
            appendLine(); appendLine("## Answer (${answer.size} messages)")
            answer.forEach { m -> appendLine("- ${rel(m.sentAt)} ${m.sender}, ${m.text.length} chars: ${m.text.replace("\n", " ⏎ ")}") }
            appendLine(); appendLine("## Live notifications from $pkg")
            MisuraListener.instance?.liveFrom(pkg)?.forEach { sbn ->
                val c = runCatching { extract(sbn) }.getOrNull() ?: return@forEach
                appendLine("- `${c.conversationKey}` msgs=${c.msgs.size} group=${c.msgs.any { it.isGroup }} reply=${c.replyActionIndex != null} authRequired=${c.replyAuthRequired} shortcut=${sbn.notification.shortcutId} extras=${c.rawExtras.keys.sorted()}")
            } ?: appendLine("- listener not connected")
        }
        val f = File(ctx.getExternalFilesDir("spikes"), "run-${System.currentTimeMillis()}.md").apply { writeText(out) }
        Log.i("MisuraDebug", "spike report: ${f.absolutePath}")
        return f
    }
}
