package com.misura.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable enum class SessionStatus { THINKING, IDLE, FAILED }

/** One conversation with the agent. [revision] goes up whenever [places], [items] or [say] change (what a tap was based on). */
@Serializable
data class Session(
    val id: String, val status: SessionStatus, val revision: Int, val createdAt: Long,
    val steps: List<Step> = emptyList(), val places: Places? = null, val say: Say? = null, val items: Items? = null,
    /** Rows written before the step-stream refactor kept a model-painted card here; [upgraded] turns it into [say]. */
    @SerialName("card") val legacyCard: JsonObject? = null,
    /** Hidden from home, still kept in Earlier. New words clear this. */
    val dismissedAt: Long? = null,
    val pendingCalls: PendingCalls? = null,
)

/** Durable evidence until the ordered result turn is appended. Older sessions have no evidence. */
@Serializable data class PendingCalls(val started: Int = 0, val outcomes: List<JsonObject> = emptyList())

fun Session.upgraded(): Session {
    val c = legacyCard ?: return this
    fun f(k: String) = (c[k] as? JsonPrimitive)?.content
    val say = when (f("type")) {
        "text" -> f("line")?.let { Say.Answer(it) }
        "problem" -> f("line")?.let { Say.Problem(it) }
        "choices", "map" -> f("title")?.let { Say.Answer(it) }
        else -> null
    } ?: Say.Answer("Older answer")
    return copy(say = say, legacyCard = null)
}

/** One Gemini `Content` object, kept verbatim (thoughtSignature included). */
@Serializable data class Turn(val role: String, val content: JsonObject)

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)
/** [id] is the model's own call id; null when the model gave none (then none goes back on the wire). */
data class ToolCall(val id: String?, val name: String, val args: JsonObject)

/** What one model call cost, as numbers only: tokens the model reported, the request's size, and ms from sending to the full answer (token fetch separate). */
data class Usage(val prompt: Int, val out: Int, val thoughts: Int, val cached: Int, val bytes: Int, val ms: Long, val tokenMs: Long = 0) {
    fun line() = "tok prompt=$prompt out=$out think=$thoughts cached=$cached bytes=$bytes api=${ms}ms auth=${tokenMs}ms"
}

sealed interface BrainReply {
    val content: JsonObject
    val usage: Usage?
    data class Calls(override val content: JsonObject, val calls: List<ToolCall>, override val usage: Usage? = null) : BrainReply
    data class Text(override val content: JsonObject, val text: String, override val usage: Usage? = null) : BrainReply
}

/** [code] is what users and logs see (HTTP status, "network"…); [message] can echo the user's words, so it stays internal. */
class BrainError(message: String, val auth: Boolean = false, val code: String = "error") : Exception(message)

/** Worth one more try: the network dropped (the model's or the sign-in's, "google_oauth_network") or the provider had a 5xx. Sign-in and other 4xx answers are not blips. */
val BrainError.blip get() = !auth && (code.endsWith("network") || (code.toIntOrNull() ?: 0) >= 500)

/** Stands in for an app package on a step that worked on the web, not in an app: the step shows the search icon. */
const val WEB = "web"
/** Same, for a step that wrote to or took from Misura's own notes. */
const val NOTE = "note"

interface Brain { suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply }
/**
 * Which session a tool call runs for. The agent puts it in the coroutine context around every tool run ([Agent]), so a tool that keeps state
 * between calls (UseApp's parked screen) can tell whose it is without every tool's signature changing. Null (a call made outside the agent) = nobody's.
 */
class RunningFor(val session: String) : kotlin.coroutines.AbstractCoroutineContextElement(RunningFor) { companion object Key : kotlin.coroutines.CoroutineContext.Key<RunningFor> }

interface Tool {
    val spec: ToolSpec
    suspend fun run(args: JsonObject): JsonObject
    /** Same, for tools that take a while: [note] puts what the tool is doing right now on its step line. */
    suspend fun run(args: JsonObject, note: suspend (String) -> Unit): JsonObject = run(args)
    /** The step line while it runs ("Searching bibimbap in Kreuzberg"): plain words, never the tool's name. */
    fun label(args: JsonObject): String
    /** One-line result once done ("6 places found"), or null. */
    fun summary(result: JsonObject): String?
    /** Package of the app this tool works through; its step shows that app's mark. */
    val app: String? get() = null
    /** For a tool that works through whichever app the call names. */
    fun appFor(args: JsonObject): String? = app
    /** Acts only on the user's own word (it writes memory): refused once another tool brought outside text into the same turn. */
    val userOnly: Boolean get() = false
    /**
     * Words the user said while this tool is running. True = the tool took them and carries on from where it is;
     * false = it can't (the default): then the running work is stopped and the words start a new turn.
     */
    fun steer(text: String): Boolean = false
}

interface SessionStore {
    suspend fun load(id: String): Session?
    suspend fun save(s: Session)
    suspend fun turns(id: String): List<Turn>
    suspend fun append(id: String, t: Turn)
    suspend fun delete(id: String)
    suspend fun recent(limit: Int): List<Session>
}
