package com.misura.phone.brain

import com.misura.core.agent.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.time.LocalDate

internal class LookUp(
    private val tokens: TokenProvider,
    private val project: () -> String,
    private val vertexBase: String = VERTEX_BASE,
    private val model: String = ModelProvider.VERTEX.defaultModel,
    private val today: () -> LocalDate = LocalDate::now,
    private val exaKey: () -> String? = { null },
    private val exaBase: String = "https://api.exa.ai",
    private val route: ModelRoute? = null,
    private val testOrigin: String? = null,
    private val deadlineMs: Long = 55_000,
) : Tool {
    constructor(context: ModelRouteContext) : this(context.google.tokens, { context.google.project }, model = context.route.model, route = context.route)

    override val app = WEB
    override val spec = ToolSpec("look_up", "Search the web for this question. Use the returned lookup_id for a sourced answer. no_results means the search finished without matches. needs_browser means direct search was unavailable.",
        schema("question" to "string", required = listOf("question")))
    override fun label(args: JsonObject) = "Looking up " + (args.field("question")?.trim()?.take(60) ?: "that")
    override fun summary(result: JsonObject) = when (result.field("status")) {
        "complete" -> result.field("provider")
        "no_results" -> "No results"
        else -> "Direct search unavailable"
    }

    override suspend fun run(args: JsonObject): JsonObject {
        val q = args.field("question")?.trim()?.takeIf { it.length in 1..4000 }
            ?: return buildJsonObject { put("error", "A question of at most 4000 characters is required") }
        val query = "Today is ${today()}.\nQuestion: $q"
        return (withTimeoutOrNull(deadlineMs) {
            val key = if (route == null) exaKey() else route.searchKey
            val selected = route?.searchProvider ?: if (key.isNullOrBlank()) SearchProvider.NATIVE else SearchProvider.EXA
            if (selected != SearchProvider.NATIVE && !key.isNullOrBlank()) {
                val first = attempt { external(selected, key, query) }
                if (first is LookupResult.Complete || first == LookupResult.Empty) return@withTimeoutOrNull first
            }
            attempt { nativeSearch(route?.provider ?: ModelProvider.VERTEX, route?.apiKey, model, query, route?.mainThinking, tokens, project, testOrigin, vertexBase) }
        } ?: LookupResult.Failed).reply()
    }

    private suspend fun attempt(search: suspend () -> LookupResult): LookupResult = try {
        withTimeoutOrNull(32_000) { search() } ?: LookupResult.Failed
    } catch (e: CancellationException) { throw e } catch (_: Exception) { LookupResult.Failed }

    private suspend fun external(selected: SearchProvider, key: String, query: String): LookupResult {
        val bearer = mapOf("Authorization" to "Bearer $key")
        return when (selected) {
            SearchProvider.EXA -> {
                val r = searchPost(fixedOrigin(testOrigin ?: exaBase, "https://api.exa.ai") + "/answer", mapOf("x-api-key" to key),
                    buildJsonObject { put("query", query); put("text", false) })
                complete(r.field("answer"), r.objects("citations").map { source(it.string("url"), it.field("title")) }, "Exa")
            }
            SearchProvider.TAVILY -> {
                val base = "https://api.tavily.com"
                val r = searchPost(fixedOrigin(testOrigin ?: base, base) + "/search", bearer, buildJsonObject {
                    put("query", query); put("include_answer", true); put("search_depth", "basic"); put("max_results", 5)
                })
                val rows = r.objects("results")
                if (rows.isEmpty() && r.field("answer").isNullOrBlank()) LookupResult.Empty else
                    complete(r.field("answer"), rows.map { source(it.string("url"), it.field("title")) }, "Tavily")
            }
            SearchProvider.CONTEXT_DEV -> {
                val base = "https://api.context.dev"
                val r = searchPost(fixedOrigin(testOrigin ?: base, base) + "/v1/web/answers", bearer, buildJsonObject {
                    put("task", query); put("mode", "fast"); putJsonObject("json_format") { put("answer", "") }
                    putJsonObject("timeoutOpts") { put("milliseconds", 20000); put("behavior", "return-partial") }
                })
                if ("partial" in r && r["partial"] != JsonPrimitive(false)) LookupResult.Partial else
                    complete(r["json_content"]?.jsonObject?.field("answer"), r.getValue("sources").jsonArray.map { source(it.jsonPrimitive.content, null) }, "Context.dev")
            }
            SearchProvider.NATIVE -> LookupResult.Failed
        }
    }
}

internal suspend fun searchPost(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
    val (code, response) = http("POST", url, headers + ("Content-Type" to "application/json"), body.toString(), timeoutMs = 30000, maxChars = 512_000)
    require(code in 200..299)
    return Json.parseToJsonElement(response).jsonObject
}
