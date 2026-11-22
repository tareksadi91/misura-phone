package com.misura.core.agent

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class SessionModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun newSessionRoundTrips() {
        val s = Session("s", SessionStatus.IDLE, 2, 5,
            steps = listOf(Step("st1", 1, "Searching bibimbap", StepState.DONE, "6 places found", 10, 20)),
            places = Places(listOf("A", "B"), pick = "A"),
            say = Say.Question("Which area?", listOf("Kreuzberg", "Mitte")))
        json.decodeFromString(Session.serializer(), json.encodeToString(Session.serializer(), s)) shouldBe s
    }

    @Test fun rowWithoutNewFieldsDecodes() {
        val s = json.decodeFromString(Session.serializer(), """{"id":"s","status":"IDLE","card":null,"revision":1,"createdAt":0}""")
        s.steps shouldBe emptyList()
        s.places shouldBe null
        s.say shouldBe null
        s.dismissedAt shouldBe null
    }

    private fun legacy(card: String) = json.decodeFromString(Session.serializer(), """{"id":"s","status":"IDLE","revision":1,"createdAt":0,"card":$card}""").upgraded()

    @Test fun legacyTextCardBecomesAnswer() {
        val s = legacy("""{"type":"text","line":"Closes at 12"}""")
        s.say shouldBe Say.Answer("Closes at 12")
        s.legacyCard shouldBe null
    }
    @Test fun legacyMapCardBecomesAnswerWithTitle() {
        legacy("""{"type":"map","title":"Lisbon","pins":[]}""").say shouldBe Say.Answer("Lisbon")
    }
    @Test fun legacyProblemStaysProblem() {
        legacy("""{"type":"problem","word":"Problem","line":"No network"}""").say shouldBe Say.Problem("No network")
    }
}
