package com.misura.phone.brain

import com.misura.core.agent.*
import kotlinx.serialization.json.*

internal const val GEMINI = "https://generativelanguage.googleapis.com"

internal class GeminiBrain(private val apiKey: String, private val model: String,
    private val readTimeoutMs: Int = 30_000, baseUrl: String = GEMINI) : Brain {
    init { require(Regex("[a-zA-Z0-9._-]+").matches(model)) }
    private val url = fixedOrigin(baseUrl,GEMINI) + "/v1beta/models/$model:generateContent"
    private val state = ProviderTurns("gemini",model)
    override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { put("parts",JsonArray(listOf(visibleText(system)))) }
            putJsonObject("generationConfig") { putJsonObject("thinkingConfig") { put("thinkingLevel","HIGH") } }
            put("contents",googleContents(turns,state))
            if (tools.isNotEmpty()) putJsonArray("tools") { add(buildJsonObject {
                putJsonArray("functionDeclarations") { tools.forEach { add(buildJsonObject {
                    put("name",it.name); put("description",it.description); put("parameters",it.parameters)
                }) } }
            }) }
        }
        return nativePost(url,mapOf("x-goog-api-key" to apiKey),body,readTimeoutMs) { root,bytes,ms ->
            decodeGoogleReply(state,root,bytes,ms)
        }
    }
}

internal fun decodeGoogleReply(state: ProviderTurns, root: JsonObject, bytes: Int, ms: Long,
    tokenMs: Long = 0, legacyFinish: Boolean = false): BrainReply {
    if ((root["promptFeedback"] as? JsonObject)?.containsKey("blockReason") == true) bad("refused")
    val candidates = root["candidates"] as? JsonArray ?: bad("empty")
    val candidate = candidates.singleOrNull()?.objectValue() ?: bad("empty")
    val finish = if (legacyFinish && "finishReason" !in candidate) "STOP" else candidate.string("finishReason")
    when(finish) { "STOP" -> Unit; "SAFETY","BLOCKLIST","PROHIBITED_CONTENT","RECITATION" -> bad("refused"); else -> bad("incomplete") }
    val raw = candidate["content"]?.objectValue()?.array("parts") ?: bad("empty")
    val parts = raw.mapNotNull {
        val p = it.objectValue()
        when {
            "functionCall" in p -> { val f = p.getValue("functionCall").objectValue()
                callPart(f["id"]?.let { f.string("id") },f.string("name"),f["args"]?.objectValue() ?: bad()) }
            p["thought"] == JsonPrimitive(true) -> null
            "text" in p -> visibleText(p.string("text"))
            else -> bad()
        }
    }
    val u = root["usageMetadata"] as? JsonObject
    return state.reply(parts,raw,Usage(u?.count("promptTokenCount") ?: 0,u?.count("candidatesTokenCount") ?: 0,u?.count("thoughtsTokenCount") ?: 0,u?.count("cachedContentTokenCount") ?: 0,bytes,ms,tokenMs))
}
