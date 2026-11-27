package com.misura.core.agent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

/** The owner's words, as the shortcut tool reads them: only the speech part of turns code stored with origin OWNER. */
class OwnerUtterancesTest {
    private fun t(role: String, vararg parts: JsonObject, origin: TurnOrigin? = if (role == "user") TurnOrigin.OWNER else null) =
        Turn(role, buildJsonObject { put("role", role); put("parts", JsonArray(parts.toList())) }, origin)
    private fun text(s: String) = buildJsonObject { put("text", s) }
    private val response = buildJsonObject { putJsonObject("functionResponse") { put("name", "read_page"); putJsonObject("response") { put("result", "LINK") } } }

    @Test fun onlyOwnerSpeechCounts() {
        ownerUtterances(listOf(
            t("user", text("keep LINK1")),                                  // typed
            t("model", text("LINK2")),                                      // the model's text
            t("user", response),                                            // a tool response stored as "user"
            t("user", text("I picked LINK3"), text(PICK_NOTE), text("(the app: Kept)")), // a card tap
            t("user", text("Sent. Go on"), text(SENT_NOTE)),                // the app after a send
            t("user", text("said while working"), text(STEER_NOTE), text("(the app, while opening it: LINK4)")), // annotation after speech
        )) shouldBe listOf("keep LINK1", "said while working")
    }

    @Test fun nonStringTextIsNotSpeech() {
        ownerUtterances(listOf(Turn("user", origin = TurnOrigin.OWNER, content = buildJsonObject { put("role", "user"); put("parts", JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive(5)) }))) }))) shouldBe emptyList()
    }

    /** Codex P1: a saved shortcut's or routine's prompt, a debug ask, the app's own turns and legacy turns are never the owner's words. */
    @Test fun onlyOwnerOriginCounts() {
        ownerUtterances(listOf(
            t("user", text("LINK shortcut"), origin = TurnOrigin.SHORTCUT),
            t("user", text("LINK routine"), origin = TurnOrigin.ROUTINE),
            t("user", text("LINK debug"), origin = TurnOrigin.DEBUG),
            t("user", text("LINK app"), origin = TurnOrigin.APP),
            t("user", text("LINK legacy"), origin = null),
            t("user", text("LINK owner")),
        )) shouldBe listOf("LINK owner")
    }

    /** A turn stored before origins existed reads back with none (untrusted here), under the app's codec settings. */
    @Test fun legacyRowDecodesWithoutOrigin() {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = """{"role":"user","content":{"role":"user","parts":[{"text":"keep LINK"}]}}"""
        val turn = codec.decodeFromString(Turn.serializer(), legacy)
        turn.origin shouldBe null
        ownerUtterances(listOf(turn)) shouldBe emptyList()
        codec.decodeFromString(Turn.serializer(), codec.encodeToString(Turn.serializer(), turn.copy(origin = TurnOrigin.OWNER))).origin shouldBe TurnOrigin.OWNER
    }

    /** Every writer of a user turn sets its origin in code: start passes it through, follow-up and steered words are OWNER, taps and notes APP, retry keeps it. */
    private fun reply(t: String) = com.misura.core.agent.text(t)

    @Test fun agentSetsOrigins() = runTest {
        val store = InMemorySessionStore()
        val agent = Agent(store, ScriptedBrain({ throw BrainError("x", code = "400") }, reply("b"), call("ask", buildJsonObject { put("line", "Which?"); put("choices", JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("B")))) }), reply("d"), { throw BrainError("x", code = "400") }, reply("f")), emptyList(), { 0 }, this)
        val id = agent.start("saved LINK", TurnOrigin.SHORTCUT); advanceUntilIdle()
        agent.retry(id); advanceUntilIdle()   // the saved prompt tried again: still SHORTCUT
        agent.followUp(id, "owner words"); advanceUntilIdle()
        val s = store.load(id)!!
        agent.choose(id, 0, s.revision).await(); advanceUntilIdle()
        agent.followUp(id, "owner again"); advanceUntilIdle()
        agent.retry(id); advanceUntilIdle()   // owner words tried again: still OWNER
        val users = store.turns(id).filter { it.role == "user" && (it.content["parts"] as JsonArray).any { p -> (p as JsonObject).containsKey("text") } }
        users.map { it.origin } shouldBe listOf(TurnOrigin.SHORTCUT, TurnOrigin.SHORTCUT, TurnOrigin.OWNER, TurnOrigin.APP, TurnOrigin.OWNER, TurnOrigin.OWNER)
        ownerUtterances(store.turns(id)) shouldBe listOf("owner words", "owner again", "owner again")
    }
}
