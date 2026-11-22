package com.misura.phone.brain

import com.misura.core.agent.*
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class ProviderTurnsTest {
    val user = Turn("user", Json.parseToJsonElement("""{"role":"user","parts":[{"text":"fixture"}]}""").jsonObject)
    @Test fun roundTripVisibleCallsAndAccountBoundOpaqueState() = runBlocking<Unit> {
        val result = """{"status":"completed","output":[{"type":"reasoning","encrypted_content":"opaque-fixture"},{"type":"function_call","call_id":"exact","name":"lookup","arguments":"{}"}]}"""
        TinyHttp { 200 to result }.use { http ->
            val client = OpenAiBrain("fixture","model",baseUrl=http.base)
            val reply = client.next("s", listOf(user), emptyList()) as BrainReply.Calls
            val history = listOf(user, Turn("model",reply.content),Turn("user",Json.parseToJsonElement("""{"role":"user","parts":[{"functionResponse":{"name":"lookup","id":"exact","response":{"ok":true}}}]}""").jsonObject))
            client.next("s",history,emptyList())
            OpenAiBrain("other","model",baseUrl=http.base).next("s",history,emptyList())
            http.requests[1].body.contains("opaque-fixture") shouldBe true
            http.requests[2].body.contains("opaque-fixture") shouldBe false
            http.requests[2].body.contains("exact") shouldBe true
            Unit
        }
    }
    @Test fun malformedLastCallRejectsWholeReply() = runBlocking<Unit> {
        TinyHttp { 200 to """{"status":"completed","output":[{"type":"function_call","call_id":"a","name":"first","arguments":"{}"},{"type":"function_call","call_id":"b","name":"second","arguments":"[]"}]}""" }.use {
            shouldThrow<BrainError> { OpenAiBrain("k","m",baseUrl=it.base).next("s", listOf(user),emptyList()) }.code shouldBe "malformed"
        }
    }
}
