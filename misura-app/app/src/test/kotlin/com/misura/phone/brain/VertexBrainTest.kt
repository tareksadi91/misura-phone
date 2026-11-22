package com.misura.phone.brain

import com.misura.core.agent.BrainError
import com.misura.core.agent.BrainReply
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import com.misura.core.agent.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class VertexBrainTest {
    private val tools = listOf(ToolSpec("find_places", "find places", schema("query" to "string")))
    private val turn = Turn("user", Json.parseToJsonElement("""{"role":"user","parts":[{"text":"hi"}]}""").jsonObject)
    private fun brain(http: TinyHttp, token: String? = "t") = VertexBrain({ token }, { "test-project" }, baseUrl = http.base)
    private fun ok(content: String) = 200 to """{"candidates":[{"content":$content}]}"""

    @Test fun sendsSystemToolsAndTurnsInGeminiShape() = runBlocking<Unit> {
        TinyHttp { ok("""{"role":"model","parts":[{"text":"x"}]}""") }.use { http ->
            brain(http).next("SYS", listOf(turn), tools)
            val r = http.requests.single()
            r.method shouldBe "POST"
            r.path shouldBe "/v1/projects/test-project/locations/global/publishers/google/models/gemini-3.8-flash:generateContent"
            r.headers["authorization"] shouldBe "Bearer t"
            r.headers["x-goog-user-project"] shouldBe "test-project"
            val body = Json.parseToJsonElement(r.body).jsonObject
            body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe "SYS"
            body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content shouldBe "find_places"
            body["contents"]!!.jsonArray.single() shouldBe turn.content
        }
    }

    @Test fun thinkingLevelAndModelGoOutOnlyWhenSet() = runBlocking<Unit> {
        TinyHttp { ok("""{"role":"model","parts":[{"text":"x"}]}""") }.use { http ->
            VertexBrain({ "t" }, { "test-project" }, baseUrl = http.base).next("S", listOf(turn), tools)
            Json.parseToJsonElement(http.requests[0].body).jsonObject.containsKey("generationConfig") shouldBe false
            var lv: String? = "LOW"; var m: String? = "other-model"
            val b = VertexBrain({ "t" }, { "test-project" }, baseUrl = http.base, thinkingLevel = { lv }, modelId = { m })
            b.next("S", listOf(turn), tools)
            Json.parseToJsonElement(http.requests[1].body).jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content shouldBe "LOW"
            http.requests[1].path.contains("/models/other-model:generateContent") shouldBe true
            lv = null; m = null; b.next("S", listOf(turn), tools)
            Json.parseToJsonElement(http.requests[2].body).jsonObject.containsKey("generationConfig") shouldBe false
            http.requests[2].path.contains("/models/gemini-3.8-flash:") shouldBe true
        }
    }

    @Test fun theAnswersTokenCountsAndRequestSizeComeBack() = runBlocking<Unit> {
        TinyHttp { 200 to """{"candidates":[{"content":{"role":"model","parts":[{"text":"x"}]}}],"usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":12,"thoughtsTokenCount":340,"cachedContentTokenCount":5}}""" }.use { http ->
            val u = brain(http).next("SYS", listOf(turn), tools).usage!!
            listOf(u.prompt, u.out, u.thoughts, u.cached) shouldBe listOf(900, 12, 340, 5)
            (u.bytes > 100) shouldBe true
        }
    }

    @Test fun theReadTimeoutFollowsTheConstructor() = runBlocking<Unit> {
        TinyHttp { Thread.sleep(1200); ok("""{"role":"model","parts":[{"text":"x"}]}""") }.use { http ->
            shouldThrow<BrainError> { VertexBrain({ "t" }, { "test-project" }, baseUrl = http.base, readTimeoutMs = 400).next("S", listOf(turn), tools) }.code shouldBe "network"
            VertexBrain({ "t" }, { "test-project" }, baseUrl = http.base, readTimeoutMs = 5_000).next("S", listOf(turn), tools).shouldBeInstanceOf<BrainReply.Text>()
        }
    }

    @Test fun functionCallPartsBecomeCalls() = runBlocking<Unit> {
        val content = """{"role":"model","parts":[{"functionCall":{"name":"find_places","args":{"query":"a"},"id":"call_1"},"thoughtSignature":"S"},{"functionCall":{"name":"finish","args":{}}}]}"""
        TinyHttp { ok(content) }.use { http ->
            val r = brain(http).next("S", listOf(turn), tools).shouldBeInstanceOf<BrainReply.Calls>()
            r.calls.map { it.id to it.name } shouldBe listOf("call_1" to "find_places", null to "finish")
            r.content["parts"].toString().contains("thoughtSignature") shouldBe false
            r.content["_native"]!!.jsonObject["items"] shouldBe Json.parseToJsonElement(content).jsonObject["parts"]
        }
    }

    @Test fun renewalPreservesOnlyTheOwningAccountsSignedCall() = runBlocking<Unit> {
        val content = """{"role":"model","parts":[{"functionCall":{"name":"find_places","args":{}},"thoughtSignature":"fixture-signature"}]}"""
        TinyHttp { ok(content) }.use { http ->
            var bearer = "fixture-a"
            val client = VertexBrain(AccountBoundToken { bearer }, { "test-project" }, http.base)
            val call = client.next("S", listOf(turn), tools).shouldBeInstanceOf<BrainReply.Calls>()
            val result = Json.parseToJsonElement("""{"role":"user","parts":[{"functionResponse":{"name":"find_places","response":{}}}]}""").jsonObject
            val history = listOf(turn, Turn("model", call.content), Turn("user", result))
            bearer = "fixture-b"
            client.next("S", history, tools)
            http.requests.last().headers["authorization"] shouldBe "Bearer fixture-b"
            http.requests.last().body.contains("fixture-signature") shouldBe true
            VertexBrain(AccountBoundToken { "other-account" }, { "test-project" }, http.base).next("S", history, tools)
            http.requests.last().body.contains("fixture-signature") shouldBe false
            val legacy = VertexBrain({ bearer }, { "test-project" }, http.base)
            val legacyCall = legacy.next("S", listOf(turn), tools).shouldBeInstanceOf<BrainReply.Calls>()
            bearer = "unknown-identity"
            legacy.next("S", listOf(turn, Turn("model", legacyCall.content), Turn("user", result)), tools)
            http.requests.last().body.contains("fixture-signature") shouldBe false
        }
    }

    @Test fun textPartsBecomeText() = runBlocking<Unit> {
        TinyHttp { ok("""{"role":"model","parts":[{"text":"Lost In "},{"text":"closes at midnight"}]}""") }.use { http ->
            brain(http).next("S", listOf(turn), tools).shouldBeInstanceOf<BrainReply.Text>().text shouldBe "Lost In closes at midnight"
        }
    }

    @Test fun unauthorizedBecomesBrainAuthError() = runBlocking<Unit> {
        TinyHttp { 401 to """{"error":{"message":"bad token"}}""" }.use { http ->
            shouldThrow<BrainError> { brain(http).next("S", listOf(turn), tools) }.auth shouldBe true
        }
    }

    @Test fun missingCredentialsAreAuthErrors() = runBlocking<Unit> {
        TinyHttp { ok("{}") }.use { http ->
            shouldThrow<BrainError> { brain(http, token = null).next("S", listOf(turn), tools) }.auth shouldBe true
            val e = shouldThrow<BrainError> { VertexBrain({ "t" }, { vertexProject(null, null) }, baseUrl = http.base).next("S", listOf(turn), tools) }
            e.auth shouldBe true
            e.code shouldBe "project"
            http.requests.size shouldBe 0
        }
    }

    @Test fun serverErrorIsABrainError() = runBlocking<Unit> {
        TinyHttp { 500 to """{"error":{"message":"oops"}}""" }.use { http ->
            val e = shouldThrow<BrainError> { brain(http).next("S", listOf(turn), tools) }
            e.auth shouldBe false
            e.message!!.contains("500") shouldBe true
        }
    }

    @Test fun stalledServerIsCutAtTheDeadline() = runBlocking<Unit> {
        TinyHttp { null }.use { http ->
            val start = System.currentTimeMillis()
            shouldThrow<TimeoutCancellationException> { withTimeout(2_000) { brain(http).next("S", listOf(turn), tools) } }
            (System.currentTimeMillis() - start < 4_000) shouldBe true
        }
    }
}
