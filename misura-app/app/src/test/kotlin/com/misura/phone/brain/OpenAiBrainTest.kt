package com.misura.phone.brain

import com.misura.core.agent.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

class OpenAiBrainTest {
    private val turn = Turn("user", Json.parseToJsonElement("""{"role":"user","parts":[{"text":"fixture"}]}""").jsonObject)
    private val ok = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":3,"output_tokens":2}}"""
    @Test fun authSchemaBudgetAndUsage() = runBlocking<Unit> {
        val schema = Json.parseToJsonElement("""{"type":"OBJECT","properties":{"type":{"type":"STRING","enum":["KEEP"]}}}""").jsonObject
        TinyHttp { 200 to ok }.use { h ->
            val r = OpenAiBrain("fixture","gpt-6.2-astra",baseUrl=h.base).next("sys",listOf(turn),listOf(ToolSpec("test","",schema)))
            val req = h.requests.single()
            req.path shouldBe "/v1/responses"
            req.headers["authorization"] shouldBe "Bearer fixture"
            listOf("x-api-key","x-goog-api-key","x-goog-user-project").none(req.headers::containsKey) shouldBe true
            val b = Json.parseToJsonElement(req.body).jsonObject
            b["store"] shouldBe JsonPrimitive(false)
            b["reasoning"]!!.jsonObject["effort"] shouldBe JsonPrimitive("high")
            val f = b["tools"]!!.jsonArray.single().jsonObject
            f["strict"] shouldBe JsonPrimitive(false)
            val prop = f["parameters"]!!.jsonObject["properties"]!!.jsonObject["type"]!!.jsonObject
            prop["type"] shouldBe JsonPrimitive("string")
            prop["enum"]!!.jsonArray.single() shouldBe JsonPrimitive("KEEP")
            r.usage!!.prompt shouldBe 3
        }
    }
    @Test fun boundedErrorsRedirectTimeoutAndCancellation() = runBlocking<Unit> {
        val variants = listOf(
            (200 to "{") to "malformed", (200 to """{"status":"completed","output":[]}""") to "empty",
            (200 to """{"status":"incomplete","output":[]}""") to "incomplete",
            (200 to """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"private-fixture"}]}]}""") to "refused"
        ) + listOf(401,403,429,500).map { (it to "private-fixture") to it.toString() }
        for ((reply,expected) in variants) TinyHttp { reply }.use { h ->
            val e = shouldThrow<BrainError> { OpenAiBrain("k","m",baseUrl=h.base).next("s",listOf(turn),emptyList()) }
            e.code shouldBe expected
            e.message.orEmpty().contains("private-fixture") shouldBe false
        }
        TinyHttp { 200 to ok }.use { dest ->
            TinyHttp(mapOf("Location" to dest.base)) { 307 to "" }.use { h ->
                shouldThrow<BrainError> { OpenAiBrain("k","m",baseUrl=h.base).next("s",listOf(turn),emptyList()) }.code shouldBe "307"
                dest.requests.size shouldBe 0
            }
        }
        TinyHttp { null }.use { h ->
            shouldThrow<BrainError> { OpenAiBrain("k","m",100,h.base).next("s",listOf(turn),emptyList()) }.code shouldBe "network"
            shouldThrow<TimeoutCancellationException> { withTimeout(100) { OpenAiBrain("k","m",5000,h.base).next("s",listOf(turn),emptyList()) } }
        }
    }
}
