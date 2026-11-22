package com.misura.core.agent

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** A tap on a tool's card: what the tap actually did reaches the model as the app's note, so it never guesses the outcome. */
class ChooseNoteTest {
    @Test fun theTapsOutcomeIsInTheNextTurn() = runTest {
        val card = FakeTool(ToolSpec("routine", "r", obj())) { obj("askUser" to obj("id" to JsonPrimitive("rt:1"), "line" to JsonPrimitive("“Is my train late?”"),
            "choices" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Save"), JsonPrimitive("Don't"))))) }
        val seen = mutableListOf<List<Turn>>()
        val brain = object : Brain {
            val q = ArrayDeque(listOf(call("routine"), text("Not saved.")))
            override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply { seen += turns; return q.removeFirst()() }
        }
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, listOf(card), { testScheduler.currentTime }, this, chooseNote = { q, i -> if (q.gate == "rt:1" && i == 0) "Not saved: there are already 20 routines" else null })
        val id = agent.start("every day at 8 check my train"); advanceUntilIdle()
        agent.choose(id, 0, store.load(id)!!.revision).await() shouldBe true
        advanceUntilIdle()
        seen.last().last().content.toString() shouldContain "(the app: Not saved: there are already 20 routines)"
        seen.last().last().content.toString() shouldContain PICK_NOTE
    }
}
