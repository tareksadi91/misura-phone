package com.misura.core.agent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LookupBindingTest {
    private val id = UUID.randomUUID().toString()
    private val exact = Say.Answer("😀Fact", listOf(Citation("Report", "https://example.org/report", 2, 6)), "OpenAI")
    private fun lookup(resultId: String = id) = buildJsonObject {
        put("status","complete"); put("lookup_id",resultId); put("provider",exact.provider)
        put("answer",exact.line); put("citations",Json.encodeToJsonElement(exact.citations))
    }
    private val spec = ToolSpec("look_up","Search",schema())
    private fun args() = buildJsonObject { put("line","different words"); put("lookup_id",id) }

    @Test fun exactAnswerAndSourceSurviveReopenAndPlainFollowUpHasNoSource() = runTest {
        val store = InMemorySessionStore()
        val brain = ScriptedBrain(call("look_up"), call("answer", args()), text("Plain."))
        val agent = Agent(store,brain,listOf(FakeTool(spec) { lookup() }),{ 0L },this)
        val session = agent.start("Find the fact"); advanceUntilIdle()
        val reopened = Json.decodeFromString<Session>(Json.encodeToString(store.load(session)!!))
        reopened.say shouldBe exact
        val turns = Json.decodeFromString<List<Turn>>(Json.encodeToString(store.turns(session)))
        thread(turns).single().citedAnswer shouldBe exact
        thread(turns).single().answer shouldBe exact.line
        agent.followUp(session,"Say hello"); advanceUntilIdle()
        thread(store.turns(session)).last().citedAnswer shouldBe null
        store.load(session)!!.say shouldBe Say.Answer("Plain.")
    }

    @Test fun rawParaphraseAfterLookupCannotBecomeTheDisplayedOrReopenedAnswer() = runTest {
        val store = InMemorySessionStore()
        val agent = Agent(store, ScriptedBrain(call("look_up"),text("made up")),listOf(FakeTool(spec) { lookup() }),{0L},this)
        val session = agent.start("Find a fact"); advanceUntilIdle()
        val visible = store.load(session)!!.say as Say.Answer
        (visible.line != "made up") shouldBe true
        visible.citations shouldBe emptyList()
        thread(store.turns(session)).single().answer shouldBe visible.line
    }

    @Test fun resolverExcludesForeignMalformedIncompleteAndEvictedRecords() {
        fun response(result: JsonObject) = Turn("user", buildJsonObject {
            putJsonArray("parts") { addJsonObject { putJsonObject("functionResponse") { put("name","look_up"); put("response",result) } } }
        })
        val first = response(lookup())
        val more = List(16) { response(lookup(UUID.randomUUID().toString())) }
        sourcedLookups(more).containsKey(id) shouldBe false
        sourcedLookups(listOf(first) + more).containsKey(id) shouldBe false
        sourcedLookups(listOf(first.copy(role = "model"), response(lookup("fake")), response(JsonObject(lookup() + ("status" to JsonPrimitive("partial")))))).size shouldBe 0
    }

    @Test fun unavailableToolsAreExcludedFromSpecsAndDispatchUntilTheNextTurn() = runTest {
        val names = mutableListOf<Set<String>>()
        var calls = 0
        val brain = object : Brain {
            override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
                names += tools.map { it.name }.toSet()
                return if (calls++ % 2 == 0) Calls(listOf(Triple("find_places",schema(),null),Triple("show_places",schema(),null)))() else text("Done.")()
            }
        }
        val tool = FakeTool(ToolSpec("find_places","Find",schema())) { schema() }
        var disabled = setOf("find_places","show_places","directions")
        val agent = Agent(InMemorySessionStore(),brain,listOf(tool),{0L},this,unavailableTools = { disabled })
        val session = agent.start("First"); advanceUntilIdle()
        tool.runs shouldBe 0
        names.all { "show_places" !in it && "find_places" !in it && "answer" in it } shouldBe true
        disabled = emptySet()
        agent.followUp(session,"Second"); advanceUntilIdle()
        tool.runs shouldBe 1
        names.last().containsAll(listOf("show_places","find_places")) shouldBe true
    }
}
