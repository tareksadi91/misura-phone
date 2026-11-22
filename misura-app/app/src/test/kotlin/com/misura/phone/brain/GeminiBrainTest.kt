package com.misura.phone.brain

import com.misura.core.agent.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class GeminiBrainTest {
    @Test fun nativeIdlessSignedCallStaysIdlessAndCrossProviderHistoryIsPortable() = runBlocking<Unit> {
        val parts = """[{"functionCall":{"name":"test","args":{}},"thoughtSignature":"signed-fixture"}]"""
        TinyHttp { 200 to """{"candidates":[{"finishReason":"STOP","content":{"parts":$parts}}]}""" }.use { h ->
            val client = GeminiBrain("fixture","gemini-3.8-flash",baseUrl=h.base)
            val reply = client.next("s",emptyList(),emptyList()) as BrainReply.Calls
            val result = Turn("user",Json.parseToJsonElement("""{"role":"user","parts":[{"functionResponse":{"name":"test","response":{"ok":true}}}]}""").jsonObject)
            val history = listOf(Turn("model",reply.content),result)
            client.next("s",history,emptyList())
            val req = h.requests.last()
            req.path shouldBe "/v1beta/models/gemini-3.8-flash:generateContent"
            req.headers["x-goog-api-key"] shouldBe "fixture"
            listOf("authorization","x-api-key","x-goog-user-project").none(req.headers::containsKey) shouldBe true
            req.body.contains("_native") shouldBe false
            req.body.contains("legacy_") shouldBe false
            req.body.contains("signed-fixture") shouldBe true
            val b = Json.parseToJsonElement(req.body).jsonObject
            b["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"] shouldBe JsonPrimitive("HIGH")
            TinyHttp { 200 to """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""" }.use { other ->
                val open = OpenAiBrain("other","m",baseUrl=other.base)
                repeat(2) { open.next("s",history,emptyList()) }
                val body = other.requests.first().body
                other.requests.last().body shouldBe body
                body.contains("signed-fixture") shouldBe false
                body.contains("legacy_0_0") shouldBe true
            }
        }
    }
    @Test fun malformedTruncatedAndBlockedResponses() = runBlocking<Unit> {
        for ((finish,args,expected) in listOf(Triple("MAX_TOKENS","{}","incomplete"),Triple("SAFETY","{}","refused"),Triple("STOP","[]","malformed")))
            TinyHttp { 200 to """{"candidates":[{"finishReason":"$finish","content":{"parts":[{"functionCall":{"name":"test","args":$args}}]}}]}""" }.use { h ->
                shouldThrow<BrainError> { GeminiBrain("k","m",baseUrl=h.base).next("s",emptyList(),emptyList()) }.code shouldBe expected
            }
    }
}
