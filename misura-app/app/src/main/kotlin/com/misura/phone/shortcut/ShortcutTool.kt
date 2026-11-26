package com.misura.phone.shortcut

import com.misura.core.agent.RunningFor
import com.misura.core.agent.SessionStore
import com.misura.core.agent.Step
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.schema
import com.misura.core.agent.shownSteps
import com.misura.core.routine.RoutineGate
import com.misura.core.shortcut.Shortcut
import com.misura.core.shortcut.digest
import com.misura.core.shortcut.replaces
import com.misura.core.shortcut.workedSteps
import com.misura.core.task.Task
import com.misura.phone.store.StorageUnavailableException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * A session's steps as the screen shows them: a send's step follows its task (controller state), never the saved WILL line,
 * so only a confirmed send counts as worked and a later failed one cancels it. Same lookups Home uses ([shownSteps]).
 */
internal fun controllerSteps(sessions: SessionStore, task: suspend (String) -> Task?, checking: (String) -> Boolean): suspend (String) -> List<Step> = { id ->
    sessions.load(id)?.let { s ->
        val tasks = s.steps.mapNotNull { it.taskId }.distinct().mapNotNull { task(it) }.associateBy { it.id }
        shownSteps(s.steps, s.say, { tasks[it] }, checking)
    }.orEmpty()
}

/**
 * Home shortcuts: a request the user can run again with one tap. "add" only after a run in this conversation worked (by the
 * app's own record of its steps, never the model's word) and shows the exact shortcut on a card; only the Keep tap stores it
 * ([RoutineGate], prefix "sc"). "remove" also waits for a tap. Title and prompt are never truncated: too long is refused.
 */
class ShortcutTool(
    private val store: ShortcutStore,
    private val steps: suspend (sessionId: String) -> List<Step>,
    private val appLabel: (String) -> String,
    private val gate: RoutineGate = RoutineGate(prefix = "sc"),
    private val clock: () -> Long = System::currentTimeMillis,
) : Tool {
    override val spec = ToolSpec("shortcut",
        "Keep a request on Home so the user can run it again with one tap. Only when the user asks for a shortcut, and only after the task worked in this conversation. " +
            "action=add with title (a few words shown on Home, at most ${Shortcut.MAX_TITLE} characters) and prompt (the request exactly as it should run again, self-contained, at most ${Shortcut.MAX_PROMPT} characters). " +
            "It is refused until a run in this conversation worked in an app. Home keeps at most ${Shortcut.MAX}; when full, the card says which one it replaces. " +
            "This shows a card; the user's tap keeps it. The app then tells you what the tap did: say only that, in two or three words. " +
            "action=list shows kept shortcuts; action=remove with id removes one (also after a tap).",
        schema("action" to "string", "title" to "string", "prompt" to "string", "id" to "string", required = listOf("action")))
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    override fun label(args: JsonObject) = when (args.s("action")) { "add" -> "Making a shortcut"; "remove" -> "Removing a shortcut"; else -> "Checking shortcuts" }
    override fun summary(result: JsonObject) = result.s("summary")
    fun answered(id: String, yes: Boolean) = gate.answered(id, yes)
    fun outcome(id: String) = gate.outcome(id)
    fun stopped() = gate.revoke()
    private fun err(m: String) = buildJsonObject { put("error", m) }

    override suspend fun run(args: JsonObject): JsonObject = when (args.s("action")) {
        "add" -> add(args)
        "list" -> buildJsonObject {
            val all = store.all()
            put("shortcuts", buildJsonArray { all.forEach { s -> add(buildJsonObject { put("id", s.id); put("title", s.title) }) } })
            put("summary", if (all.size == 1) "1 shortcut" else "${all.size} shortcuts")
        }
        "remove" -> remove(args)
        else -> err("action must be add, list or remove")
    }

    private fun card(r: RoutineGate.Result, label: String, title: String, line: String, go: String): JsonObject = when (r) {
        is RoutineGate.Result.Ask -> buildJsonObject {
            put("askUser", buildJsonObject {
                put("id", r.id); put("label", label); put("title", title); put("line", line)
                put("choices", buildJsonArray { add(JsonPrimitive(go)); add(JsonPrimitive("Don't")) })
            })
        }
        is RoutineGate.Result.Done -> buildJsonObject { put("summary", r.summary) }
    }

    private suspend fun add(args: JsonObject): JsonObject {
        val title = args.s("title") ?: return err("title required")
        val prompt = args.s("prompt") ?: return err("prompt required")
        if (title.length > Shortcut.MAX_TITLE) return err("title too long (max ${Shortcut.MAX_TITLE} characters)")
        if (prompt.length > Shortcut.MAX_PROMPT) return err("prompt too long (max ${Shortcut.MAX_PROMPT} characters)")
        val session = coroutineContext[RunningFor]?.session
        // Unknown session or unreadable steps = nothing proven to have worked.
        val app = session?.let { id -> runCatching { workedSteps(steps(id)) }.getOrNull() } ?: return err(REFUSED)
        val replacing = replaces(store.all())
        // The id and time are not part of what the user approves; the digest binds title, prompt, app and the replaced shortcut.
        val draft = Shortcut("new", title, prompt, app, createdAt = 0)
        val r = gate.act("keep", draft.digest(replacing?.id), session) {
            try {
                if (store.keep(draft.copy(id = UUID.randomUUID().toString(), createdAt = clock()), replacing?.id)) "Kept: $title"
                else "Not kept: Home changed, ask again"
            } catch (_: StorageUnavailableException) { STORAGE }
        }
        val line = "“$prompt”" + (replacing?.let { " Replaces “${it.title}”." } ?: "")
        return card(r, "Shortcut · ${appLabel(app)}", title, line, "Keep")
    }

    private suspend fun remove(args: JsonObject): JsonObject {
        val s = args.s("id")?.let { id -> store.all().firstOrNull { it.id == id } } ?: return err("no shortcut with that id (call list first)")
        val r = gate.act("remove", s.id, coroutineContext[RunningFor]?.session) {
            try { if (store.remove(s.id)) "Shortcut removed" else "Not removed: it was already gone" } catch (_: StorageUnavailableException) { STORAGE }
        }
        return card(r, "Shortcut · ${s.app?.let(appLabel) ?: "Home"}", "Remove this shortcut?", "“${s.title}”", "Remove")
    }

    companion object {
        const val REFUSED = "do the task first; only a run that worked can be kept"
        /** Same words Home uses when a save fails (HomeScreen send tap). */
        const val STORAGE = "Not done: Misura couldn't save progress."
    }
}
