package com.misura.core.agent

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

/** The owner's words, as the shortcut tool reads them: only the speech part of turns the owner said. */
class OwnerUtterancesTest {
    private fun t(role: String, vararg parts: JsonObject) = Turn(role, buildJsonObject { put("role", role); put("parts", JsonArray(parts.toList())) })
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
        ownerUtterances(listOf(Turn("user", buildJsonObject { put("role", "user"); put("parts", JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive(5)) }))) }))) shouldBe emptyList()
    }
}
