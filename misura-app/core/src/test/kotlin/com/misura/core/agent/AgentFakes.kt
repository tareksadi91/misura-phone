package com.misura.core.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement

class InMemorySessionStore : SessionStore {
    private val m = Mutex()
    val sessions = mutableMapOf<String, Session>()
    val turnLog = mutableMapOf<String, MutableList<Turn>>()
    override suspend fun load(id: String) = m.withLock { sessions[id]?.upgraded() }
    override suspend fun save(s: Session) = m.withLock { sessions[s.id] = s }
    override suspend fun turns(id: String) = m.withLock { turnLog[id].orEmpty().toList() }
    override suspend fun append(id: String, t: Turn) = m.withLock { turnLog.getOrPut(id) { mutableListOf() } += t }
    override suspend fun delete(id: String) { m.withLock { sessions.remove(id); turnLog.remove(id) } }
    override suspend fun recent(limit: Int) = m.withLock { sessions.values.sortedByDescending { it.createdAt }.take(limit).map { it.upgraded() } }
}

/** Replays replies in order and records the transcript it was shown each time. */
class ScriptedBrain(vararg replies: suspend () -> BrainReply) : Brain {
    private val queue = ArrayDeque(replies.toList())
    val seen = mutableListOf<List<Turn>>()
    override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
        seen += turns
        return queue.removeFirst()()
    }
}

fun call(name: String, args: JsonObject = JsonObject(emptyMap()), id: String? = null, signature: String? = null) =
    Calls(listOf(Triple(name, args, id)), signature)

/** A model step with several function calls; [signature] adds a thoughtSignature part like Gemini 3. */
fun Calls(calls: List<Triple<String, JsonObject, String?>>, signature: String? = null): suspend () -> BrainReply = {
    val content = buildJsonObject {
        put("role", "model")
        put("parts", buildJsonArray {
            calls.forEachIndexed { i, (name, args, id) ->
                add(buildJsonObject {
                    putJsonObject("functionCall") { put("name", name); put("args", args); id?.let { put("id", it) } }
                    if (i == 0 && signature != null) put("thoughtSignature", signature)
                })
            }
        })
    }
    BrainReply.Calls(content, calls.map { (n, a, id) -> ToolCall(id, n, a) })
}

fun text(t: String): suspend () -> BrainReply = {
    BrainReply.Text(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { put("text", t) }) }) }, t)
}

class FakeTool(
    override val spec: ToolSpec,
    val label: (JsonObject) -> String = { "Working on it" },
    val summary: (JsonObject) -> String? = { null },
    val result: suspend (JsonObject) -> JsonObject,
) : Tool {
    var runs = 0
    override suspend fun run(args: JsonObject): JsonObject { runs++; return result(args) }
    override fun label(args: JsonObject) = label.invoke(args)
    override fun summary(result: JsonObject) = summary.invoke(result)
}

fun obj(vararg kv: Pair<String, JsonElement>) = JsonObject(mapOf(*kv))
