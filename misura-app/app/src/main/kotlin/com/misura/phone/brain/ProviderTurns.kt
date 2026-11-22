package com.misura.phone.brain

import com.misura.core.agent.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.UUID

internal fun bad(code: String = "malformed"): Nothing = throw BrainError(code, code = code)
internal fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad()
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: bad()
internal fun JsonElement.objectValue() = this as? JsonObject ?: bad()
internal fun part(key: String, value: JsonElement) = JsonObject(mapOf(key to value))
internal fun visibleText(text: String) = part("text", JsonPrimitive(text))
internal fun callPart(id: String?, name: String, args: JsonObject) = buildJsonObject {
    putJsonObject("functionCall") { id?.let { put("id", it) }; put("name", name); put("args", args) }
}
internal fun JsonObject.count(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: 0

/** Only schema keywords are transformed. Property names and enum literals are user data. */
internal fun nativeSchema(schema: JsonObject): JsonObject = buildJsonObject {
    schema.forEach { (k,v) ->
        put(k, when (k) {
            "type" -> when (v) {
                is JsonPrimitive -> JsonPrimitive(v.content.lowercase())
                is JsonArray -> JsonArray(v.map { JsonPrimitive(it.jsonPrimitive.content.lowercase()) })
                else -> bad()
            }
            "properties", "patternProperties", "\$defs", "definitions" -> JsonObject(v.objectValue().mapValues { nativeSchema(it.value.objectValue()) })
            "items", "additionalProperties", "not", "contains" -> if (v is JsonObject) nativeSchema(v) else v
            "anyOf", "oneOf", "allOf", "prefixItems" -> JsonArray(v.jsonArray.map { nativeSchema(it.objectValue()) })
            else -> v
        })
    }
}

internal class ProviderTurns(private val provider: String, private val model: String) {
    private val scope = UUID.randomUUID().toString()
    fun opaque(turn: Turn): JsonElement? {
        val meta = turn.content["_native"] as? JsonObject ?: return null
        return meta["items"].takeIf { meta["provider"] == JsonPrimitive(provider) && meta["model"] == JsonPrimitive(model) && meta["scope"] == JsonPrimitive(scope) }
    }
    fun reply(parts: List<JsonObject>, raw: JsonElement, usage: Usage): BrainReply {
        val calls = parts.mapNotNull { (it["functionCall"] as? JsonObject)?.let { f ->
            ToolCall(f["id"]?.jsonPrimitive?.content, f.string("name").takeIf(String::isNotBlank) ?: bad(), f["args"]?.objectValue() ?: bad())
        } }
        val ids = calls.mapNotNull { it.id }
        if (ids.any(String::isBlank) || ids.distinct().size != ids.size) bad()
        val content = buildJsonObject {
            put("role","model"); put("parts",JsonArray(parts))
            putJsonObject("_native") { put("provider",provider); put("model",model); put("scope",scope); put("items",raw) }
        }
        if (calls.isNotEmpty()) return BrainReply.Calls(content,calls,usage)
        val text = parts.mapNotNull { (it["text"] as? JsonPrimitive)?.content }.joinToString("")
        if (text.isBlank()) bad("empty")
        return BrainReply.Text(content,text,usage)
    }
}

/** Normalize ordered legacy id-less calls and split result turns before encoding provider history. */
internal fun providerHistory(turns: List<Turn>): List<Turn> {
    val output = mutableListOf<Turn>()
    val waiting = mutableListOf<JsonObject>()
    val results = mutableMapOf<String,JsonObject>()
    for ((index,t) in splitMixed(turns).withIndex()) {
        val parts = t.content.array("parts").map { it.objectValue() }
        if (t.role == "user" && parts.any { "functionResponse" in it }) {
            for (p in parts) {
                val r = p["functionResponse"]?.objectValue() ?: bad("history")
                if (r["response"] !is JsonObject) bad("history")
                val id = r["id"]?.let { r.string("id") }
                val c = waiting.firstOrNull { it.string("name") == r.string("name") && it.string("id") !in results && (id == null || it.string("id") == id) } ?: bad("history")
                val resolved = c.string("id")
                results[resolved] = part("functionResponse", JsonObject(r + ("id" to JsonPrimitive(resolved))))
            }
            if (waiting.size == results.size) {
                val batch = waiting.map { results.getValue(it.string("id")) }
                output += Turn("user", buildJsonObject { put("role","user"); put("parts",JsonArray(batch)) })
                waiting.clear(); results.clear()
            }
        } else {
            if (waiting.isNotEmpty() || t.role !in setOf("user","model")) bad("history")
            val normalized = parts.mapIndexedNotNull { n,p ->
                val f = p["functionCall"] as? JsonObject
                when {
                    f != null && t.role == "model" -> {
                        if (f["args"] !is JsonObject || f.string("name").isBlank()) bad("history")
                        val id = JsonPrimitive(f["id"]?.let { f.string("id") } ?: "legacy_${index}_$n")
                        val call = JsonObject(f + ("id" to id))
                        if (waiting.any { it["id"] == id }) bad("history")
                        waiting += call
                        part("functionCall",call)
                    }
                    "text" in p && p["thought"] != JsonPrimitive(true) -> visibleText(p.string("text"))
                    p["thought"] == JsonPrimitive(true) || "thoughtSignature" in p -> null
                    else -> bad("history")
                }
            }
            output += Turn(t.role,JsonObject(t.content + ("parts" to JsonArray(normalized))))
        }
    }
    if (waiting.isNotEmpty()) bad("history")
    return output
}

/** GenerateContent may omit ids on native calls. Echo the same omission on their paired results. */
internal fun googleContents(turns: List<Turn>, state: ProviderTurns): JsonArray = buildJsonArray {
    var idless = emptySet<String>()
    for (t in providerHistory(turns)) {
        val normalized = t.content.array("parts")
        val native = state.opaque(t) as? JsonArray
        if (t.role == "model") {
            val calls = normalized.mapNotNull { it.objectValue()["functionCall"] as? JsonObject }
            val raw = native.orEmpty().mapNotNull { it.objectValue()["functionCall"] as? JsonObject }
            idless = calls.zip(raw).mapNotNull { (c,r) -> c.string("id").takeIf { "id" !in r } }.toSet()
        }
        val parts = native ?: JsonArray(normalized.map { p ->
            val r = p.objectValue()["functionResponse"] as? JsonObject
            if (r != null && r.string("id") in idless) part("functionResponse",JsonObject(r - "id")) else p
        })
        add(buildJsonObject { put("role",t.role); put("parts",parts) })
    }
}

internal fun fixedOrigin(base: String, official: String): String {
    require(base == official || Regex("http://127\\.0\\.0\\.1:[0-9]+").matches(base))
    return base
}

internal suspend fun nativePost(url: String, headers: Map<String,String>, body: JsonObject, timeout: Int,
    parse: (JsonObject, Int, Long) -> BrainReply): BrainReply {
    val start = System.nanoTime()
    val payload = body.toString()
    val (code, text) = try { http("POST",url,headers + ("Content-Type" to "application/json"),payload,timeout) }
        catch (_: IOException) { bad("network") }
    if (code !in 200..299) throw BrainError("HTTP $code",auth = code in setOf(401,403),code = code.toString())
    return try { parse(Json.parseToJsonElement(text).objectValue(),payload.toByteArray().size,(System.nanoTime()-start)/1_000_000) }
        catch (_: IllegalArgumentException) { bad() }
        catch (_: IllegalStateException) { bad() }
}
