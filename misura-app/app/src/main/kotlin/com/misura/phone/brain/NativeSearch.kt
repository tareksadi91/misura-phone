package com.misura.phone.brain

import com.misura.core.agent.Citation
import kotlinx.serialization.json.*

/** Hosted tools only enter this question-only request. No conversation, function dispatch or provider continuation. */
internal suspend fun nativeSearch(provider: ModelProvider, key: String?, model: String, query: String, vertexThinking: String?,
    tokens: TokenProvider, project: () -> String, testOrigin: String?, vertexBase: String): LookupResult {
    val prompt = "$query\nUse web search. Give a concise sourced answer."
    return when (provider) {
        ModelProvider.OPENAI -> {
            val base = "https://api.openai.com"
            val r = searchPost(fixedOrigin(testOrigin ?: base, base) + "/v1/responses", mapOf("Authorization" to "Bearer ${requireNotNull(key)}"), buildJsonObject {
                put("model", model); put("input", prompt); put("store", false); put("max_tool_calls", 1)
                putJsonObject("reasoning") { put("effort", "high") }
                putJsonArray("tools") { add(buildJsonObject { put("type", "web_search") }) }
                putJsonObject("tool_choice") { put("type", "web_search") }
            })
            openAiSearchResult(r)
        }
        ModelProvider.ANTHROPIC -> {
            val base = "https://api.anthropic.com"
            val r = searchPost(fixedOrigin(testOrigin ?: base, base) + "/v1/messages",
                mapOf("x-api-key" to requireNotNull(key), "anthropic-version" to "2026-10-21"), buildJsonObject {
                    put("model", model); put("max_tokens", 16384)
                    putJsonObject("thinking") { put("type", "adaptive") }
                    putJsonObject("output_config") { put("effort", "high") }
                    putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
                    putJsonArray("tools") { add(buildJsonObject { put("type", "web_search_20250305"); put("name", "web_search"); put("max_uses", 1) }) }
                })
            anthropicSearchResult(r)
        }
        ModelProvider.GEMINI, ModelProvider.VERTEX -> {
            val (url, headers) = if (provider == ModelProvider.GEMINI) {
                val base = "https://generativelanguage.googleapis.com"
                fixedOrigin(testOrigin ?: base, base) + "/v1beta/models/$model:generateContent" to mapOf("x-goog-api-key" to requireNotNull(key))
            } else {
                val token = tokens.token() ?: return LookupResult.Failed
                val billingProject = project()
                fixedOrigin(testOrigin ?: vertexBase, VERTEX_BASE) + vertexPath(model, billingProject) to googleHeaders(token, billingProject)
            }
            val level = if (provider == ModelProvider.GEMINI) "HIGH" else vertexThinking
            val r = searchPost(url, headers, buildJsonObject {
                level?.let { putJsonObject("generationConfig") { putJsonObject("thinkingConfig") { put("thinkingLevel", it) } } }
                putJsonArray("contents") { add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) } }) }
                putJsonArray("tools") { add(buildJsonObject { putJsonObject("googleSearch") {} }) }
            })
            googleSearchResult(r, provider.label)
        }
    }
}

private fun openAiSearchResult(r: JsonObject): LookupResult {
    val output = r.objects("output")
    if (r.field("status") != "completed" || output.none { it.field("type") == "web_search_call" && it.field("status") == "completed" }) return LookupResult.Partial
    val text = StringBuilder()
    val citations = mutableListOf<Citation>()
    for (item in output) {
        when (item.field("type")) {
            "message" -> {
                if (item.field("status") != "completed" || item.field("role") != "assistant") return LookupResult.Partial
                for (part in item.objects("content")) {
                    if (part.field("type") != "output_text") return LookupResult.Partial
                    val block = part.string("text")
                    for (c in part.objects("annotations")) {
                        if (c.field("type") != "url_citation") return LookupResult.Partial
                        val start = block.offsetByCodePoints(0, c.getValue("start_index").jsonPrimitive.int)
                        val end = block.offsetByCodePoints(0, c.getValue("end_index").jsonPrimitive.int)
                        citations += source(c.string("url"), c.field("title"), text.length + start, text.length + end)
                    }
                    text.append(block)
                }
            }
            "reasoning" -> Unit
            "web_search_call" -> if (item.field("status") != "completed") return LookupResult.Partial
            else -> return LookupResult.Partial
        }
    }
    return complete(text.toString(), citations, "OpenAI")
}

private fun anthropicSearchResult(r: JsonObject): LookupResult {
    if (r.field("stop_reason") != "end_turn") return LookupResult.Partial
    val text = StringBuilder()
    val citations = mutableListOf<Citation>()
    val ids = mutableSetOf<String>()
    val urls = mutableSetOf<String>()
    var returned = false
    for (part in r.objects("content")) when (part.field("type")) {
        "thinking", "redacted_thinking" -> Unit
        "server_tool_use" -> {
            if (part.field("name") != "web_search") return LookupResult.Partial
            ids += part.string("id")
        }
        "web_search_tool_result" -> {
            if (part.field("tool_use_id") !in ids || part["content"] !is JsonArray) return LookupResult.Failed
            returned = true
            for (row in part.objects("content")) {
                if (row.field("type") != "web_search_result") return LookupResult.Partial
                urls += row.string("url")
            }
        }
        "text" -> {
            val start = text.length
            text.append(part.string("text"))
            for (c in (part["citations"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                if (c.field("type") != "web_search_result_location" || c.field("url") !in urls) return LookupResult.Partial
                citations += source(c.string("url"), c.field("title"), start, text.length)
            }
        }
        else -> return LookupResult.Partial
    }
    if (!returned) return LookupResult.Partial
    if (urls.isEmpty() && citations.isEmpty()) return LookupResult.Empty
    return complete(text.toString(), citations, "Anthropic")
}

private fun googleSearchResult(r: JsonObject, provider: String): LookupResult {
    val candidate = r.objects("candidates").singleOrNull() ?: return LookupResult.Partial
    if (candidate.field("finishReason") != "STOP") return LookupResult.Partial
    val meta = candidate["groundingMetadata"] as? JsonObject ?: return LookupResult.Partial
    // Native Compose has no compliant renderer for Google's HTML suggestion entry point.
    if ("searchEntryPoint" in meta || (meta["webSearchQueries"] as? JsonArray).orEmpty().isNotEmpty()) return LookupResult.SuggestionsUnsupported
    val parts = candidate.getValue("content").jsonObject.objects("parts")
    val textFields = setOf("text", "thought", "thoughtSignature")
    if (parts.any { part -> part.keys.any { it !in textFields } ||
            ("thought" in part && part["thought"] !in setOf(JsonPrimitive(true), JsonPrimitive(false))) }) return LookupResult.Partial
    val visible = parts.withIndex().singleOrNull { it.value["thought"] != JsonPrimitive(true) } ?: return LookupResult.Partial
    val text = visible.value.string("text")
    val bytes = text.toByteArray()
    val sources = meta.objects("groundingChunks")
    val citations = meta.objects("groundingSupports").flatMap { support ->
        val segment = support.getValue("segment").jsonObject
        if ((segment["partIndex"]?.jsonPrimitive?.int ?: 0) != visible.index) return LookupResult.Partial
        val startByte = (segment["startIndex"] as? JsonPrimitive)?.int ?: 0
        val endByte = segment.getValue("endIndex").jsonPrimitive.int
        if (startByte !in 0 until endByte || endByte > bytes.size) return LookupResult.Partial
        val start = bytes.decodeToString(0, startByte, throwOnInvalidSequence = true).length
        val quote = bytes.decodeToString(startByte, endByte, throwOnInvalidSequence = true)
        if (quote != segment.field("text")) return LookupResult.Partial
        support.getValue("groundingChunkIndices").jsonArray.map { index ->
            val web = sources[index.jsonPrimitive.int].getValue("web").jsonObject
            source(web.string("uri"), web.field("title"), start, start + quote.length)
        }
    }
    return complete(text, citations, provider)
}
