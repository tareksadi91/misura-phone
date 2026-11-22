package com.misura.phone.brain

import com.misura.core.agent.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class AnthropicBrainTest {
    @Test fun mixedMultipleCallsAndSignatures() = runBlocking<Unit> {
        val raw = """[{"type":"thinking","thinking":"private","signature":"signed-fixture"},{"type":"text","text":"looking"},{"type":"tool_use","id":"a","name":"x","input":{}},{"type":"tool_use","id":"b","name":"x","input":{}}]"""
        TinyHttp { 200 to """{"stop_reason":"tool_use","content":$raw}""" }.use { h ->
            val client = AnthropicBrain("fixture","claude-opus-5-5",baseUrl=h.base)
            fun turn(role: String, parts: String) = Turn(role,Json.parseToJsonElement("""{"role":"$role","parts":$parts}""").jsonObject)
            val user = turn("user","""[{"text":"fixture"}]""")
            val reply = client.next("s",listOf(user),emptyList()) as BrainReply.Calls
            reply.calls.map { it.id } shouldBe listOf("a","b")
            val history = listOf(user,Turn("model",reply.content)) + listOf("b","a").map {
                turn("user","""[{"functionResponse":{"id":"$it","name":"x","response":{"result":"$it"}}}]""")
            }
            client.next("s",history,emptyList())
            val req = h.requests.last()
            req.path shouldBe "/v1/messages"
            req.headers["x-api-key"] shouldBe "fixture"
            req.headers["anthropic-version"] shouldBe "2026-10-21"
            listOf("authorization","x-goog-api-key","x-goog-user-project").none(req.headers::containsKey) shouldBe true
            val body = Json.parseToJsonElement(req.body).jsonObject
            body["max_tokens"] shouldBe JsonPrimitive(16384)
            body["thinking"]!!.jsonObject["type"] shouldBe JsonPrimitive("adaptive")
            body["output_config"]!!.jsonObject["effort"] shouldBe JsonPrimitive("high")
            val messages = body["messages"]!!.jsonArray
            messages[1].jsonObject["content"] shouldBe Json.parseToJsonElement(raw)
            messages.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content } shouldBe listOf("a","b")
            shouldThrow<BrainError> { client.next("s",history.dropLast(1),emptyList()) }.code shouldBe "history"
            h.requests.size shouldBe 2
        }
    }
    @Test fun truncatedAndNonobjectInputRejected() = runBlocking<Unit> {
        for ((stop,input,expected) in listOf(Triple("max_tokens","{}","incomplete"),Triple("refusal","{}","refused"),Triple("tool_use","[]","malformed")))
            TinyHttp { 200 to """{"stop_reason":"$stop","content":[{"type":"tool_use","id":"a","name":"x","input":$input}]}""" }.use { h ->
                shouldThrow<BrainError> { AnthropicBrain("k","m",baseUrl=h.base).next("s",emptyList(),emptyList()) }.code shouldBe expected
            }
    }
}
