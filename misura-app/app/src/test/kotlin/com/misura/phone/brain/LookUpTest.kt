package com.misura.phone.brain

import com.misura.core.agent.sourcedAnswer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

class LookUpTest {
    private val q = buildJsonObject { put("question", "Fixture question?") }
    private val url = "https://example.org/report"
    private val exa = """{"answer":"Fact [1].","citations":[{"url":"$url"}],"lookup_id":"attacker","askUser":"yes","remember":"bad","functionCall":{"name":"send"}}"""
    private val openai = """{"status":"completed","output":[{"type":"web_search_call","status":"completed"},{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"😀Fact","annotations":[{"type":"url_citation","start_index":1,"end_index":5,"url":"$url","title":"Report"}]}]}]}"""
    private val anthropic = """{"stop_reason":"end_turn","content":[{"type":"server_tool_use","name":"web_search","id":"srv"},{"type":"web_search_tool_result","tool_use_id":"srv","content":[{"type":"web_search_result","url":"$url"}]},{"type":"text","text":"Fact","citations":[{"type":"web_search_result_location","url":"$url","title":"Report"}]}]}"""
    private val google = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"😀Fact"}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"$url","title":"Report"}}],"groundingSupports":[{"segment":{"startIndex":4,"endIndex":8,"text":"Fact"},"groundingChunkIndices":[0]}]}}]}"""
    private fun lookup(http: TinyHttp, provider: ModelProvider, search: SearchProvider = SearchProvider.NATIVE,
        googleAvailable: Boolean = false, credential: String? = "fixture", deadline: Long = 5000, thinking: String? = null): LookUp {
        val route = ModelRoute(provider, provider.defaultModel, "model-key", provider.defaultModel, thinking, "LOW", null,
            if (googleAvailable) "token" else null, if (googleAvailable) "project" else "", search, credential, false, false)
        return LookUp({ check(googleAvailable && provider == ModelProvider.VERTEX); "token" }, { check(googleAvailable); "project" },
            model = route.model, route = route, testOrigin = http.base, deadlineMs = deadline)
    }

    @Test fun nativeRequestsPreserveMainThinkingPolicy() = runBlocking<Unit> {
        for (provider in ModelProvider.entries) for (level in listOf(null,"LOW","MEDIUM","HIGH")) {
            TinyHttp { 403 to "{}" }.use { http ->
                lookup(http, provider, googleAvailable = true, thinking = level).run(q)
                val body = Json.parseToJsonElement(http.requests.single().body).jsonObject
                when (provider) {
                    ModelProvider.OPENAI -> body.getValue("reasoning").jsonObject.field("effort") shouldBe "high"
                    ModelProvider.ANTHROPIC -> {
                        body.getValue("thinking").jsonObject.field("type") shouldBe "adaptive"
                        body.getValue("output_config").jsonObject.field("effort") shouldBe "high"
                        body.getValue("max_tokens").jsonPrimitive.int shouldBe 16384
                    }
                    else -> (body["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject?.field("thinkingLevel")) shouldBe
                        if (provider == ModelProvider.GEMINI) "HIGH" else level
                }
            }
        }
    }

    @Test fun thoughtsAreInertAndCitationsMustAddressVisibleParts() = runBlocking<Unit> {
        val googleThought = google.replace("\"parts\":[", "\"parts\":[{\"thought\":true,\"text\":\"😀Fact hidden\"},").replace("\"startIndex\":4", "\"partIndex\":1,\"startIndex\":4")
        val claudeThought = anthropic.replace("\"content\":[{\"type\":\"server_tool_use", "\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"},{\"type\":\"redacted_thinking\",\"data\":\"opaque\"},{\"type\":\"server_tool_use")
        for ((provider, payload, expected) in listOf(
            Triple(ModelProvider.GEMINI, googleThought, "😀Fact"),
            Triple(ModelProvider.GEMINI, googleThought.replace("\"partIndex\":1", "\"partIndex\":0"), null),
            Triple(ModelProvider.GEMINI, googleThought.replace("\"thought\":true", "\"functionCall\":{},\"thought\":true"), null),
            Triple(ModelProvider.ANTHROPIC, claudeThought, "Fact"),
            Triple(ModelProvider.ANTHROPIC, claudeThought.replace("redacted_thinking","unknown_action"), null))) {
            TinyHttp { 200 to payload }.use { http ->
                val answer = lookup(http, provider).run(q).sourcedAnswer()
                answer?.line shouldBe expected
                if (answer != null) answer.citations.single().start shouldBe if (provider == ModelProvider.GEMINI) 2 else 0
            }
        }
    }

    @Test fun successfulExaLookupNeverResolvesGoogleCredentials() = runBlocking<Unit> {
        TinyHttp { 200 to exa }.use { http ->
            val lookup = LookUp({ error("unexpected Google token lookup") }, { error("unexpected Google project lookup") },
                exaKey = { "fixture" }, exaBase = http.base)
            val result = lookup.run(q)
            result.sourcedAnswer()!!.line shouldBe "Fact [1]."
            result.keys shouldBe setOf("status","lookup_id","provider","answer","citations")
            (result.field("lookup_id") != "attacker") shouldBe true
            http.requests.single().headers["x-api-key"] shouldBe "fixture"
        }
    }

    @Test fun longSourceTitleDoesNotDiscardSuccessfulExaAnswer() = runBlocking<Unit> {
        val payload = exa.replace("\"url\":", "\"title\":\"${"Long source title ".repeat(30)}\",\"url\":")
        TinyHttp { 200 to payload }.use { http ->
            val result = lookup(http, ModelProvider.VERTEX, SearchProvider.EXA).run(q)
            result.sourcedAnswer()?.provider shouldBe "Exa"
            http.requests.size shouldBe 1
        }
    }

    @Test fun selectionAndGoogleAvailabilityMatrixDoesNotCrossAccounts() = runBlocking<Unit> {
        for (provider in ModelProvider.entries) for (available in listOf(false,true)) for (external in listOf(false,true)) {
            TinyHttp { req -> 200 to when (req.path) {
                "/answer" -> exa
                "/v1/responses" -> openai
                "/v1/messages" -> anthropic
                else -> google
            } }.use { http ->
                val result = lookup(http, provider, SearchProvider.EXA, available, if (external) "fixture" else null).run(q)
                if (provider == ModelProvider.VERTEX && !available && !external) {
                    result.field("status") shouldBe "needs_browser"
                    http.requests.size shouldBe 0
                } else {
                    val answer = result.sourcedAnswer()!!
                    answer.provider shouldBe if (external) "Exa" else provider.label
                    val req = http.requests.single()
                    req.body shouldContain "Fixture question?"
                    when {
                        external -> req.path shouldBe "/answer"
                        provider == ModelProvider.ANTHROPIC -> { req.body shouldContain "web_search_20250305"; req.headers["x-api-key"] shouldBe "model-key" }
                        provider == ModelProvider.OPENAI -> { req.body shouldContain "web_search"; req.headers["authorization"] shouldBe "Bearer model-key" }
                        provider == ModelProvider.GEMINI -> req.headers["x-goog-api-key"] shouldBe "model-key"
                        else -> req.headers["authorization"] shouldBe "Bearer token"
                    }
                    if (!external && provider != ModelProvider.ANTHROPIC) {
                        answer.citations.single().start shouldBe 2; answer.citations.single().end shouldBe 6
                    }
                }
            }
        }
    }

    @Test fun externalServiceContractsAndTerminalEmptyResults() = runBlocking<Unit> {
        val cases = listOf(
            Triple(SearchProvider.TAVILY, """{"answer":"Fact","results":[{"title":"Report","url":"$url"}]}""", "/search"),
            Triple(SearchProvider.CONTEXT_DEV, """{"json_content":{"answer":"Fact"},"sources":["$url"],"partial":false}""", "/v1/web/answers"))
        for ((service, payload, path) in cases) TinyHttp { 200 to payload }.use { http ->
            val result = lookup(http, ModelProvider.OPENAI, service).run(q)
            result.sourcedAnswer()!!.line shouldBe "Fact"
            val request = http.requests.single()
            request.path shouldBe path
            request.headers["authorization"] shouldBe "Bearer fixture"
            request.body shouldContain if (service == SearchProvider.TAVILY) "include_answer" else "\"fast\""
        }
        TinyHttp { 200 to """{"results":[],"answer":null}""" }.use { http ->
            lookup(http, ModelProvider.OPENAI, SearchProvider.TAVILY).run(q).field("status") shouldBe "no_results"
            http.requests.single().path shouldBe "/search"
        }
        TinyHttp { 200 to """{"stop_reason":"end_turn","content":[{"type":"server_tool_use","name":"web_search","id":"srv"},{"type":"web_search_tool_result","tool_use_id":"srv","content":[]}]}""" }.use { http ->
            lookup(http, ModelProvider.ANTHROPIC).run(q).field("status") shouldBe "no_results"
        }
    }

    @Test fun errorsAndIncompleteResultsTryOnlyTheSelectedNativeProvider() = runBlocking<Unit> {
        for ((code, payload) in listOf(401 to "{}",403 to "{}",429 to "{}",200 to "{",200 to """{"partial":true}""",
            200 to """{"json_content":{"answer":"unsupported"},"sources":[]}""",
            200 to """{"json_content":{"answer":"unsafe"},"sources":["javascript:alert(1)"]}""")) {
            TinyHttp { if (it.path == "/v1/responses") 200 to openai else code to payload }.use { http ->
                lookup(http, ModelProvider.OPENAI, SearchProvider.CONTEXT_DEV).run(q).sourcedAnswer()!!.provider shouldBe "OpenAI"
                http.requests.map { it.path } shouldBe listOf("/v1/web/answers","/v1/responses")
            }
        }
        TinyHttp { 403 to "{}" }.use { http ->
            lookup(http, ModelProvider.GEMINI, SearchProvider.EXA).run(q).field("status") shouldBe "needs_browser"
            http.requests.size shouldBe 2
        }
    }

    @Test fun unsafeUnattributedAndTruncatedNativeResultsCannotIssueIds() = runBlocking<Unit> {
        for ((provider,payload) in listOf(ModelProvider.OPENAI to openai.replace(url,"javascript:alert(1)"),
            ModelProvider.OPENAI to openai.replace("end_index\":5","end_index\":52"),
            ModelProvider.ANTHROPIC to anthropic.replace("end_turn","max_tokens"),
            ModelProvider.GEMINI to google.replace("\"groundingChunks\":","\"searchEntryPoint\":{\"renderedContent\":\"<script>bad()</script>\"},\"groundingChunks\":"))) {
            TinyHttp { 200 to payload }.use { http ->
                val r = lookup(http,provider).run(q)
                r.field("status") shouldBe "needs_browser"
                r.keys shouldBe setOf("status","reason")
            }
        }
    }

    @Test fun sharedDeadlineAndCallerCancellationStopHttp() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        TinyHttp { started.complete(Unit); null }.use { http ->
            lookup(http, ModelProvider.OPENAI, SearchProvider.EXA, deadline = 150).run(q).field("status") shouldBe "needs_browser"
            http.requests.size shouldBe 1
        }
        val waiting = CompletableDeferred<Unit>()
        TinyHttp { waiting.complete(Unit); null }.use { http ->
            val job = async { lookup(http,ModelProvider.OPENAI,SearchProvider.EXA).run(q) }
            withTimeout(3000) { waiting.await() }
            job.cancelAndJoin()
            job.isCancelled shouldBe true
            http.requests.size shouldBe 1
        }
    }
}
