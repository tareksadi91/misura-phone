package com.misura.phone.hands

import com.misura.core.agent.Draft
import com.misura.core.agent.Drafter
import com.misura.core.exec.Controller
import com.misura.core.exec.TaskStore
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.phone.notify.Chat
import java.text.Normalizer
import java.util.UUID

/**
 * draft_message → a message task waiting on its card (NEEDS_APPROVAL). Nothing is sent here.
 * [chats] = chats with a live notification and a reply button: the route that sends without opening the app.
 */
class ChatDrafter(
    private val chats: () -> List<Chat>,
    private val appLabel: (String) -> String,
    private val tasks: TaskStore,
    private val controller: Controller,
    private val clock: () -> Long,
    private val chatPackages: () -> List<String>,
    /** Launcher labels as (label, package), including apps without a current notification. */
    private val installedApps: () -> List<Pair<String, String>> = { emptyList() },
) : Drafter {
    override suspend fun draft(to: String, text: String): Draft = draft(to, text, null)

    override suspend fun draft(to: String, text: String, app: String?): Draft {
        // Chats in apps the agent may not operate are never offered, named or drafted (an explicit protected app finds no chat).
        val all = chats().filter { com.misura.phone.access.AppAccess.allows(it.pkg) }
        val packages = chatPackages().distinct().filter { com.misura.phone.access.AppAccess.allows(it) }
        fun label(pkg: String) = safeAppLabel(runCatching { appLabel(pkg) }.getOrDefault(""))
        val labels = (installedApps() + (packages + all.map { it.pkg }).distinct().map { label(it) to it })
            .map { (name, pkg) -> safeAppLabel(name) to pkg }
        fun identity(pkg: String): String = appIdentity(pkg, label(pkg), labels)
        val eligible = all.filter { app == null || it.pkg == app }
        val c = pickChat(to, eligible) ?: return Draft.NoChat(to,
            eligible.sortedByDescending { it.sentAt }.map { "${it.name} (${identity(it.pkg)} = ${it.pkg})" },
            packages.filter { app == null || it == app }.map { identity(it) to it })
        val destination = identity(c.pkg)
        val channel = if (c.pkg == "com.misura.testapp") "testchat" else "reply"
        val id = UUID.randomUUID().toString()
        // ponytail: a draft whose turn fails later stays NEEDS_APPROVAL with no card; nothing can tap it. Expire them if they pile up.
        tasks.save(Task(id, "message", TaskState.PLANNING, null, emptyList(), clock(), null))
        controller.prepare(id, Payload(mapOf("channel" to channel, "recipient" to c.key, "name" to c.name, "content" to text, "pkg" to c.pkg, "app" to destination)))
        return Draft.Ready(id, c.name, destination, c.pkg)
    }

    override suspend fun discard(taskId: String) { controller.discard(taskId) }
}

/**
 * How an app is named to the user: its sanitized [name] ([safeAppLabel]), with the package added when another app in [labels]
 * (sanitized label to package) reads the same; a blank name or one equal to the package is the package. Shared with the shortcut card.
 */
internal fun appIdentity(pkg: String, name: String, labels: List<Pair<String, String>>): String {
    if (name.isBlank() || name == pkg) return pkg
    val key = labelKey(name)
    val ambiguous = labels.any { (other, otherPkg) -> otherPkg != pkg &&
        (labelKey(otherPkg) == key || other.isNotBlank() && (labelKey(other) == key || labelKey("$other ($otherPkg)") == key)) }
    return if (ambiguous) "$name ($pkg)" else name
}

/** An app-supplied label as text to show: ISO controls, FORMAT characters and line/paragraph separators dropped, trimmed, capped. */
internal fun safeAppLabel(label: String) = label.filterNot {
    Character.isISOControl(it) || Character.getType(it) in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())
}.trim().take(80)

private fun labelKey(label: String) = Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase().replace(Regex("\\s+"), " ")

/** Exact name first; the same full name across apps picks the newest message. Different people = no guess. */
fun pickChat(to: String, chats: List<Chat>): Chat? {
    fun name(c: Chat) = c.name.trim().lowercase()
    val t = to.trim().lowercase()
    if (t.isEmpty()) return null
    val exact = chats.filter { name(it) == t }
    val matches = exact.ifEmpty { chats.filter { name(it).substringBefore(' ') == t } }
    if (matches.groupBy { it.pkg }.values.any { cs -> cs.map { it.key }.distinct().size > 1 }) return null
    return matches.takeIf { it.map(::name).distinct().size == 1 }?.maxByOrNull { it.sentAt }
}
