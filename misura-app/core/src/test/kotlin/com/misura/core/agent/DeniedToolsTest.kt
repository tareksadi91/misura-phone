package com.misura.core.agent

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

/** A session can be denied tools (a routine run: no app control, no memory, no new routines), even when the model calls them anyway. */
class DeniedToolsTest {
    @Test fun aDeniedToolIsHiddenAndRefusedForThatSessionOnly() = runTest {
        val hand = FakeTool(ToolSpec("use_app", "operate an app", JsonObject(emptyMap()))) { obj() }
        val seen = mutableListOf<List<String>>()
        val brain = object : Brain {
            val q = ArrayDeque(listOf(call("use_app"), text("done"), call("use_app"), text("done")))
            override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply { seen += tools.map { it.name }; return q.removeFirst()() }
        }
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, listOf(hand), { testScheduler.currentTime }, this)
        val restricted = agent.start("x", deny = setOf("use_app")); advanceUntilIdle()
        hand.runs shouldBe 0
        ("use_app" in seen[0]) shouldBe false
        store.turns(restricted).toString() shouldContain "tool unavailable this turn"
        agent.followUp(restricted, "now do it yourself"); advanceUntilIdle() // the owner's own words: every tool again
        hand.runs shouldBe 1
    }
}
