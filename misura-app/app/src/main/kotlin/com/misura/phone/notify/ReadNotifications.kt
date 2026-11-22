package com.misura.phone.notify

import android.content.Context
import android.app.Notification
import android.service.notification.StatusBarNotification
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Reads the current Android notification shade only when the agent asks. */
class ReadNotifications(
    private val ctx: Context,
    private val active: () -> List<StatusBarNotification>? = { MisuraListener.instance?.activeNotifications?.toList() },
) : Tool {
    override val spec = ToolSpec("read_notifications", "Read up to 10 current Android notifications, newest first. Each body includes up to 5 incoming messages, newest first, capped at 2000 characters; textTruncated reports omitted text/messages. Optional app is an exact package name; omit for all allowed apps. Dismissed notifications and older inbox history are unavailable. Read-only: does not reply, dismiss or mark messages read.", schema("app" to "string"))
    override fun label(args: JsonObject) = "Reading notifications"
    override fun summary(result: JsonObject) = result["summary"]?.jsonPrimitive?.content
    override suspend fun run(args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val requested = args["app"]
        if (requested != null && (requested !is JsonPrimitive || !requested.isString)) {
            return@withContext error("app must be a package name")
        }
        val pkg = (requested as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        com.misura.phone.access.AppAccess.init(ctx)
        pkg?.let { p -> com.misura.phone.access.AppAccess.refusal(p)?.let { return@withContext error("$it.") } }
        try {
            val live = active() ?: return@withContext error("notification listener is not connected")
            val access = NotificationAccess(ctx)
            val rows = live.filter {
                it.packageName != ctx.packageName && access.allows(it.packageName) && com.misura.phone.access.AppAccess.allows(it.packageName) &&
                    (pkg == null || it.packageName == pkg) && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
            }.sortedByDescending { it.postTime }.mapNotNull { sbn ->
                runCatching {
                    val captured = extract(sbn)
                    val incoming = captured.msgs.filter { !it.senderIsSelf }.sortedByDescending { it.sentAt }
                    val text = incoming.take(5).joinToString("\n") { "${it.sender}: ${it.text}" }
                    val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(200)
                    if (title.isBlank() && text.isBlank()) null else buildJsonObject {
                        put("package", sbn.packageName)
                        put("app", runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName))
                        put("title", title)
                        put("text", text.take(2000))
                        put("textTruncated", incoming.size > 5 || text.length > 2000)
                        put("postedAt", sbn.postTime)
                    }
                }.getOrNull()
            }
            buildJsonObject {
                put("notifications", JsonArray(rows.take(10)))
                put("total", rows.size)
                put("truncated", rows.size > 10)
                put("summary", if (rows.size == 1) "1 notification" else "${rows.size} notifications")
            }
        } catch (e: SecurityException) {
            error("notification access is not granted")
        } catch (e: RuntimeException) {
            if (e is java.util.concurrent.CancellationException) throw e
            error("notifications temporarily unavailable")
        }
    }

    private fun error(line: String) = buildJsonObject { put("error", line) }
}
