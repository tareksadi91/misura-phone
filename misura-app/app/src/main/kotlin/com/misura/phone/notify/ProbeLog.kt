package com.misura.phone.notify

import android.content.Context
import com.misura.phone.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** Debug diagnostics only: no message text, sender, conversation id or raw extras. */
object ProbeLog {
    internal const val MAX_BYTES = 256 * 1024
    internal const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private fun file(ctx: Context) = File(ctx.filesDir, "probe/notification-metadata.jsonl")

    @Synchronized fun clean(ctx: Context) {
        // The previous format contains plaintext conversations; never show or retain it.
        val legacy = File(ctx.filesDir, "probe/notifications.jsonl")
        runCatching { if (legacy.exists()) legacy.delete() } // best effort; diagnostics must never crash the app
        clean(file(ctx), BuildConfig.DIAGNOSTICS, System.currentTimeMillis())
    }

    @Synchronized fun append(ctx: Context, pkg: String, c: Captured) {
        clean(ctx)
        append(file(ctx), pkg, c, BuildConfig.DIAGNOSTICS, System.currentTimeMillis())
    }

    internal fun append(file: File, pkg: String, c: Captured, debug: Boolean, now: Long) {
        clean(file, debug, now)
        if (!debug) return
        val line = buildJsonObject {
            put("at", now); put("pkg", pkg.take(255))
            put("replyAction", c.replyActionIndex ?: -1); put("authRequired", c.replyAuthRequired)
            put("messageCount", c.msgs.size)
        }.toString() + "\n"
        file.parentFile?.mkdirs()
        if (file.length() + line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) file.writeText("")
        file.appendText(line)
    }

    internal fun clean(file: File, debug: Boolean, now: Long) {
        if (!file.exists()) return
        // Check size before reading even one line of a possibly old/corrupt file.
        val expired = !debug || file.length() > MAX_BYTES || runCatching {
            val first = file.bufferedReader().use { it.readLine() } ?: return@runCatching true
            val at = Json.parseToJsonElement(first).jsonObject.getValue("at").jsonPrimitive.content.toLong()
            at > now || now - at >= MAX_AGE_MS
        }.getOrDefault(true)
        check(!expired || file.delete()) { "probe cleanup failed" }
    }

    @Synchronized fun tail(ctx: Context, n: Int = 50): List<String> {
        clean(ctx)
        if (!BuildConfig.DIAGNOSTICS) return emptyList()
        return file(ctx).takeIf { it.exists() }?.readLines()?.takeLast(n.coerceIn(0, 50))?.reversed().orEmpty()
    }
}
