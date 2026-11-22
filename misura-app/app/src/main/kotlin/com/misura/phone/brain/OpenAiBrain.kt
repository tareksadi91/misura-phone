package com.misura.phone.brain

import com.misura.core.agent.*
import kotlinx.serialization.json.*

internal const val OPENAI = "https://api.openai.com"

internal class OpenAiBrain(private val apiKey: String, private val model: String,
    private val readTimeoutMs: Int = 30_000, baseUrl: String = OPENAI) : Brain {
    private val url = fixedOrigin(baseUrl, OPENAI) + "/v1/responses"
    private val state = ProviderTurns("openai", model)

    override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
        val body = buildJsonObject {
            put("model",model); put("instructions",system); put("store",false)
            putJsonObject("reasoning") { put("effort","high") }
            putJsonArray("input") {
                for (t in providerHistory(turns)) {
                    val native = state.opaque(t)
                    if (native != null) { native.jsonArray.forEach(::add); continue }
                    for (part in t.content.array("parts")) {
                        val p = part.objectValue()
                        add(buildJsonObject {
                            when {
                                "text" in p -> { put("role",if (t.role == "model") "assistant" else "user"); put("content",p.string("text")) }
                                "functionCall" in p -> { val f = p.getValue("functionCall").objectValue()
                                    put("type","function_call"); put("call_id",f.string("id")); put("name",f.string("name")); put("arguments",f.getValue("args").toString()) }
                                else -> { val r = p.getValue("functionResponse").objectValue()
                                    put("type","function_call_output"); put("call_id",r.string("id")); put("output",r.getValue("response").toString()) }
                            }
                        })
                    }
                }
            }
            putJsonArray("tools") { tools.forEach { add(buildJsonObject {
                put("type","function"); put("name",it.name); put("description",it.description); put("parameters",nativeSchema(it.parameters)); put("strict",false)
            }) } }
        }
        return nativePost(url,mapOf("Authorization" to "Bearer $apiKey"),body,readTimeoutMs) { root,bytes,ms ->
            if (root.string("status") != "completed") bad("incomplete")
            val output = root.array("output")
            val parts = buildList {
                for (item in output) {
                    val b = item.objectValue()
                    if (b["status"] != null && b.string("status") != "completed") bad("incomplete")
                    when (b.string("type")) {
                        "reasoning" -> Unit
                        "function_call" -> add(callPart(b.string("call_id"),b.string("name"),Json.parseToJsonElement(b.string("arguments")).objectValue()))
                        "message" -> b.array("content").forEach {
                            val p = it.objectValue()
                            when (p.string("type")) {
                                "refusal" -> bad("refused")
                                "output_text" -> add(visibleText(p.string("text")))
                                else -> bad()
                            }
                        }
                        else -> bad()
                    }
                }
            }
            val u = root["usage"] as? JsonObject
            state.reply(parts,output,Usage(u?.count("input_tokens") ?: 0,u?.count("output_tokens") ?: 0,
                (u?.get("output_tokens_details") as? JsonObject)?.count("reasoning_tokens") ?: 0,
                (u?.get("input_tokens_details") as? JsonObject)?.count("cached_tokens") ?: 0,bytes,ms))
        }
    }
}
