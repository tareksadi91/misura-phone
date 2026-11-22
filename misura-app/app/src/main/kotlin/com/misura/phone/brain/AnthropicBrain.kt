package com.misura.phone.brain

import com.misura.core.agent.*
import kotlinx.serialization.json.*

internal const val ANTHROPIC = "https://api.anthropic.com"

internal class AnthropicBrain(private val apiKey: String, private val model: String,
    private val readTimeoutMs: Int = 30_000, baseUrl: String = ANTHROPIC) : Brain {
    private val url = fixedOrigin(baseUrl, ANTHROPIC) + "/v1/messages"
    private val state = ProviderTurns("anthropic",model)
    override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
        val body = buildJsonObject {
            put("model",model); put("system",system); put("max_tokens",16384)
            putJsonObject("thinking") { put("type","adaptive") }
            putJsonObject("output_config") { put("effort","high") }
            putJsonArray("tools") { tools.forEach { add(buildJsonObject {
                put("name",it.name); put("description",it.description); put("input_schema",nativeSchema(it.parameters))
            }) } }
            putJsonArray("messages") {
                for (t in providerHistory(turns)) add(buildJsonObject {
                    put("role",if (t.role == "model") "assistant" else "user")
                    put("content",state.opaque(t) ?: buildJsonArray {
                        for (part in t.content.array("parts")) {
                            val p = part.objectValue()
                            add(buildJsonObject {
                                when {
                                    "text" in p -> { put("type","text"); put("text",p.string("text")) }
                                    "functionCall" in p -> { val f = p.getValue("functionCall").objectValue()
                                        put("type","tool_use"); put("id",f.string("id")); put("name",f.string("name")); put("input",f.getValue("args")) }
                                    else -> { val r = p.getValue("functionResponse").objectValue()
                                        put("type","tool_result"); put("tool_use_id",r.string("id")); put("content",r.getValue("response").toString()) }
                                }
                            })
                        }
                    })
                })
            }
        }
        return nativePost(url,mapOf("x-api-key" to apiKey,"anthropic-version" to "2026-10-21"),body,readTimeoutMs) { root,bytes,ms ->
            val stop = root.string("stop_reason")
            when(stop) { "refusal" -> bad("refused"); "tool_use","end_turn","stop_sequence" -> Unit; else -> bad("incomplete") }
            val raw = root.array("content")
            val parts = raw.mapNotNull {
                val p = it.objectValue()
                when (p.string("type")) {
                    "thinking","redacted_thinking" -> null
                    "text" -> visibleText(p.string("text"))
                    "tool_use" -> callPart(p.string("id"),p.string("name"),p["input"]?.objectValue() ?: bad())
                    else -> bad()
                }
            }
            if ((stop == "tool_use") != parts.any { "functionCall" in it }) bad()
            val u = root["usage"] as? JsonObject
            state.reply(parts,raw,Usage(u?.count("input_tokens") ?: 0,u?.count("output_tokens") ?: 0,0,u?.count("cache_read_input_tokens") ?: 0,bytes,ms))
        }
    }
}
