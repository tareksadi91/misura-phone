package com.misura.phone.brain

import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import com.misura.core.agent.BrainError
import com.misura.core.agent.BrainReply
import com.misura.core.agent.Turn
import com.misura.core.agent.blip
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ServiceAccountTokenTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun keyJson(uri: String) = """{"client_email":"service-account@example.invalid","token_uri":"$uri","private_key":"-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(pair.private.encoded)}\n-----END PRIVATE KEY-----\n"}"""

    @Test fun deniedExchangeKeepsItsStatusThroughCooldownWithoutLeakingResponse() {
        var now = 0L
        var accepted = false
        TinyHttp { if (accepted) 200 to """{"access_token":"fixture-token","expires_in":3600}"""
            else 400 to """{"error":"invalid_grant","error_description":"private-assertion-fixture"}""" }.use { http ->
            val token = ServiceAccountToken({ keyJson("ignored") }, { null }, { now }, http.base)
            repeat(2) {
                val error = shouldThrow<BrainError> { token.token() }
                error.code shouldBe "google_oauth_400"
                error.toString().contains("private-assertion-fixture") shouldBe false
                error.cause shouldBe null
            }
            http.requests.size shouldBe 1
            accepted = true
            now += 31_000
            token.token() shouldBe "fixture-token"
        }
    }

    @Test fun malformedCredentialAndNetworkFailureRemainDistinct() {
        shouldThrow<BrainError> { ServiceAccountToken({ "invalid-private-fixture" }, { null }).token() }
            .code shouldBe "google_credentials_invalid"
        val closed = TinyHttp { 200 to "{}" }
        closed.close()
        val blip = shouldThrow<BrainError> { ServiceAccountToken({ keyJson("ignored") }, { null }, tokenUrl = closed.base).token() }
        blip.code shouldBe "google_oauth_network"
        blip.auth shouldBe false // a dropped connection is not a sign-in problem: the hand retries network errors, never auth ones
    }

    @Test fun aDroppedConnectionIsRetriedOnTheNextCallWithoutTheCooldown() {
        var up = false
        // Exchanges die without an answer (the socket closes) until the network is back; the next call, right after, succeeds.
        TinyHttp { if (!up) throw IllegalStateException("drop") else 200 to """{"access_token":"tok","expires_in":3600}""" }.use { http ->
            val t = ServiceAccountToken({ keyJson("x") }, { null }, { 0L }, http.base)
            shouldThrow<BrainError> { t.token() }.code shouldBe "google_oauth_network"
            up = true
            t.token() shouldBe "tok" // same instant: no 30 s refusal after a blip
        }
    }

    @Test fun malformedSuccessDoesNotBecomeCredentialRejectionOrExposeTokenBody() {
        for (body in listOf("{private-response-fixture", """{"access_token":"","expires_in":3600}""",
            """{"access_token":"private-token-fixture","expires_in":-1}""")) {
            TinyHttp { 200 to body }.use { http ->
                val error = shouldThrow<BrainError> {
                    ServiceAccountToken({ keyJson("ignored") }, { null }, tokenUrl = http.base).token()
                }
                error.code shouldBe "google_oauth_response"
                error.toString().contains("private-") shouldBe false
                error.cause shouldBe null
            }
        }
    }

    @Test fun signsAJwtTheServerCanVerifyThenReusesTheTokenUntilItIsNearlyOut() {
        var now = 1_000_000_000_000L; var n = 0
        TinyHttp { 200 to """{"access_token":"tok${++n}","expires_in":3600}""" }.use { http ->
            val t = ServiceAccountToken({ keyJson("ignored") }, { "pushed" }, { now }, http.base + "/token")
            t.token() shouldBe "tok1"
            val jwt = URLDecoder.decode(http.requests.single().body, "UTF-8").substringAfter("assertion=").split(".")
            Signature.getInstance("SHA256withRSA").run { initVerify(pair.public); update("${jwt[0]}.${jwt[1]}".toByteArray()); verify(Base64.getUrlDecoder().decode(jwt[2])) } shouldBe true
            String(Base64.getUrlDecoder().decode(jwt[1])).contains("\"iss\":\"service-account@example.invalid\"") shouldBe true
            now += 50 * 60_000; t.token() shouldBe "tok1"   // still good: no second call
            now += 6 * 60_000; t.token() shouldBe "tok2"    // inside the last five minutes: a new one
        }
    }

    @Test fun withoutAKeyOrWhenTheKeyFailsThePushedTokenIsUsed() {
        ServiceAccountToken({ null }, { "pushed" }).token() shouldBe "pushed"
        ServiceAccountToken({ "not json" }, { "pushed" }).token() shouldBe "pushed"
        var now = 0L; var up = false
        TinyHttp { if (up) 200 to """{"access_token":"tok","expires_in":3600}""" else 400 to """{"error":"invalid_grant"}""" }.use { http ->
            val t = ServiceAccountToken({ keyJson("x") }, { null }, { now }, http.base)
            shouldThrow<BrainError> { t.token() }.code shouldBe "google_oauth_400"
            shouldThrow<BrainError> { t.token() }; http.requests.size shouldBe 1 // cooling down: no second exchange
            up = true; now += 31_000; t.token() shouldBe "tok"
            up = false; now += 57 * 60_000; t.token() shouldBe "tok"          // renewal failed, but the token has 3 minutes left
            now += 4 * 60_000; shouldThrow<BrainError> { t.token() }          // now it is really out
        }
    }

    @Test fun aDroppedRenewalKeepsTheOldTokenAndWaitsOnlyWhileItWorks() {
        var now = 0L; var up = true
        TinyHttp { if (up) 200 to """{"access_token":"tok","expires_in":3600}""" else throw IllegalStateException("drop") }.use { http ->
            val t = ServiceAccountToken({ keyJson("x") }, { null }, { now }, http.base)
            t.token() shouldBe "tok"
            up = false; now += 56 * 60_000
            t.token() shouldBe "tok"; val asked = http.requests.size
            now += 10_000; t.token() shouldBe "tok"; http.requests.size shouldBe asked // still covered: no exchange per call during an outage
            now += 4 * 60_000                                                          // the old token is out
            shouldThrow<BrainError> { t.token() }.code shouldBe "google_oauth_network"
            up = true; t.token() shouldBe "tok"                                        // same instant: asked again, no 30 s wait
        }
    }

    // Route: the model call the hand makes. A sign-in renewal that loses its connection must surface as a blip (the hand's
    // retry rule), and the retry must reach Google and then the model, not a 30 s refusal. Never replays an app action: only the model call.
    @Test fun aSignInBlipDuringAModelCallIsRetryableAndTheRetryReachesTheModel() = runBlocking<Unit> {
        var up = false
        TinyHttp { r -> when {
            r.path == "/token" && !up -> throw IllegalStateException("drop")
            r.path == "/token" -> 200 to """{"access_token":"tok","expires_in":3600}"""
            else -> 200 to """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}"""
        } }.use { http ->
            val brain = VertexBrain(ServiceAccountToken({ keyJson("x") }, { null }, { 0L }, http.base + "/token"), { "p" }, baseUrl = http.base)
            val turn = Turn("user", Json.parseToJsonElement("""{"role":"user","parts":[{"text":"hi"}]}""").jsonObject)
            shouldThrow<BrainError> { brain.next("S", listOf(turn), emptyList()) }.blip shouldBe true
            up = true
            (brain.next("S", listOf(turn), emptyList()) as BrainReply.Text).text shouldBe "ok"
        }
    }
}
