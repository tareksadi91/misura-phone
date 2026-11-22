package com.misura.core.agent

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class StepTest {
    private val spec = ToolSpec("find_places", "find places", JsonObject(emptyMap()))
    private fun places(n: Int) = obj("places" to JsonArray(List(n) { obj("label" to JsonPrimitive("P$it")) }))
    private fun search(result: suspend (JsonObject) -> JsonObject) = FakeTool(spec,
        label = { a -> "Searching ${(a["query"] as? JsonPrimitive)?.content}" },
        summary = { r -> "${(r["places"] as? JsonArray)?.size} places found" }, result = result)
    private val q = obj("query" to JsonPrimitive("bibimbap in Kreuzberg"))

    private class Run(val agent: Agent, val store: InMemorySessionStore, val logs: MutableList<String>, val problems: MutableList<String>)
    private fun TestScope.agent(brain: Brain, vararg tools: Tool): Run {
        val store = InMemorySessionStore(); val logs = mutableListOf<String>(); val problems = mutableListOf<String>()
        return Run(Agent(store, brain, tools.toList(), clock = { testScheduler.currentTime }, scope = this,
            onProblem = { _, why -> problems += why }, onStep = { logs += it }), store, logs, problems)
    }

    @Test fun toolCallMakesRunningThenDoneStep() = runTest {
        val r = agent(ScriptedBrain(call("find_places", q), text("ok")), search { places(2) })
        val id = r.agent.start("x"); advanceUntilIdle()
        val st = r.store.load(id)!!.steps.single()
        st.label shouldBe "Searching bibimbap in Kreuzberg"
        st.state shouldBe StepState.DONE
        st.result shouldBe "2 places found"
        (st.endedAt != null) shouldBe true
    }

    @Test fun toolNoteShowsOnTheRunningStepAndThePerCallApp() = runTest {
        var during: Step? = null
        lateinit var r: Run
        val tool = object : Tool {
            override val spec = ToolSpec("use_app", "use an app", JsonObject(emptyMap()))
            override suspend fun run(args: JsonObject) = run(args) {}
            override suspend fun run(args: JsonObject, note: suspend (String) -> Unit): JsonObject {
                note("Clock: tap “Alarm”")
                during = r.store.sessions.values.single().steps.single()
                return obj("result" to JsonPrimitive("set"))
            }
            override fun label(args: JsonObject) = "Clock: set an alarm"
            override fun summary(result: JsonObject): String? = null
            override fun appFor(args: JsonObject) = (args["app"] as? JsonPrimitive)?.content
        }
        r = agent(ScriptedBrain(call("use_app", obj("app" to JsonPrimitive("com.clock"))), text("ok")), tool)
        val id = r.agent.start("x"); advanceUntilIdle()
        during!!.state shouldBe StepState.RUNNING
        during!!.result shouldBe "Clock: tap “Alarm”"
        val st = r.store.load(id)!!.steps.single()
        st.state shouldBe StepState.DONE
        st.result shouldBe null // the note doesn't outlive the step: done shows the label
        st.app shouldBe "com.clock"
    }

    @Test fun toolThatStopsForYourOkAsksOnItsOwnCardAndShowsWillNotDone() = runTest {
        val chosen = mutableListOf<Pair<String?, Int>>()
        val store = InMemorySessionStore()
        val tool = FakeTool(ToolSpec("use_app", "x", JsonObject(emptyMap())), label = { "Clock: delete the alarm" }) {
            obj("stopped" to JsonPrimitive("before tapping"), "askUser" to obj("id" to JsonPrimitive("q1"), "line" to JsonPrimitive("Clock: tap “Delete”?"), "choices" to JsonArray(listOf(JsonPrimitive("Delete"), JsonPrimitive("Don't")))))
        }
        val agent = Agent(store, ScriptedBrain(Calls(listOf(Triple("use_app", JsonObject(emptyMap()), null), Triple("answer", obj("line" to JsonPrimitive("All done.")), null))), text("Left it.")), listOf(tool), clock = { testScheduler.currentTime }, scope = this, onChoose = { q, i -> chosen += q.gate to i })
        val id = agent.start("delete my alarm"); advanceUntilIdle()
        val s = store.load(id)!!
        s.say shouldBe Say.Question("Clock: tap “Delete”?", listOf("Delete", "Don't"), gate = "q1") // the answer in the same reply did not take the card's place
        s.status shouldBe SessionStatus.IDLE
        s.steps.single().state shouldBe StepState.WILL
        shownSteps(s.steps, s.say, { null }, { false }).single().state shouldBe StepState.WILL // the line shows above the card
        agent.choose(id, 1, s.revision).await() shouldBe true
        chosen shouldBe listOf("q1" to 1)
        store.load(id)!!.steps.none { it.state == StepState.WILL } shouldBe true
    }

    @Test fun theModelsOwnAskIsNeverAGate() = runTest {
        val r = agent(ScriptedBrain(call("ask", obj("line" to JsonPrimitive("Go?"), "choices" to JsonArray(listOf(JsonPrimitive("Delete"), JsonPrimitive("Don't")))))))
        val id = r.agent.start("x"); advanceUntilIdle()
        (r.store.load(id)!!.say as Say.Question).gate shouldBe null
    }

    @Test fun errorResultClosesStepAsFailed() = runTest {
        val r = agent(ScriptedBrain(call("find_places", q), text("sorry")), search { obj("error" to JsonPrimitive("quota exceeded\nmore")) })
        val id = r.agent.start("x"); advanceUntilIdle()
        val st = r.store.load(id)!!.steps.single()
        st.state shouldBe StepState.FAILED
        st.result shouldBe "quota exceeded"
    }

    @Test fun toolExceptionClosesStepAsFailed() = runTest {
        val r = agent(ScriptedBrain(call("find_places", q)), search { throw IllegalStateException("boom") })
        val id = r.agent.start("x"); advanceUntilIdle()
        val s = r.store.load(id)!!
        s.steps.first().state shouldBe StepState.FAILED
        s.status shouldBe SessionStatus.FAILED
    }

    @Test fun timeoutFailsOpenStepAndAddsSyntheticStep() = runTest {
        val r = agent(ScriptedBrain(call("find_places", q)), search { delay(200_000); places(1) })
        val id = r.agent.start("x"); advanceUntilIdle()
        val steps = r.store.load(id)!!.steps
        steps.map { it.state } shouldBe listOf(StepState.FAILED, StepState.FAILED)
        steps[0].label shouldBe "Searching bibimbap in Kreuzberg"
        steps[1].label shouldContain "took too long"
    }

    @Test fun recoverFailsRunningSteps() = runTest {
        val r = agent(ScriptedBrain())
        r.store.save(Session("old", SessionStatus.THINKING, 1, 0, steps = listOf(Step("a", 1, "Searching", StepState.RUNNING, null, 0))))
        r.agent.recover(); advanceUntilIdle()
        val s = r.store.load("old")!!
        s.status shouldBe SessionStatus.FAILED
        s.steps.first().state shouldBe StepState.FAILED
        s.steps.first().result shouldBe "Interrupted"
    }

    @Test fun stepsCarryTurnIndex() = runTest {
        val r = agent(ScriptedBrain(call("find_places", q), text("a"), call("find_places", q), text("b")), search { places(1) })
        val id = r.agent.start("x"); advanceUntilIdle()
        r.agent.followUp(id, "y"); advanceUntilIdle()
        r.store.load(id)!!.steps.map { it.turn } shouldBe listOf(1, 2)
    }

    @Test fun stepLabelsNeverLogged() = runTest {
        val r = agent(ScriptedBrain(call("find_places", q), text("ok")), search { places(1) })
        r.agent.start("bibimbap in Kreuzberg"); advanceUntilIdle()
        r.logs.forEach { it shouldNotContain "bibimbap" }
    }

    @Test fun backendErrorTextNeverShownOrLogged() = runTest {
        val r = agent(ScriptedBrain({ throw BrainError("400 bibimbap in Kreuzberg is invalid", code = "400") }))
        val id = r.agent.start("bibimbap in Kreuzberg"); advanceUntilIdle()
        val s = r.store.load(id)!!
        val shown = listOf((s.say as Say.Problem).line) + s.steps.map { it.label + " " + it.result } + r.logs + r.problems
        shown.joinToString(" ") shouldContain "400"
        shown.forEach { it shouldNotContain "bibimbap" }
    }

    @Test fun structuredErrorClosesStepAsFailed() = runTest {
        val r = agent(ScriptedBrain(call("find_places", q), text("sorry")), search { obj("error" to obj("message" to JsonPrimitive("quota exceeded"))) })
        val id = r.agent.start("x"); advanceUntilIdle()
        val st = r.store.load(id)!!.steps.single()
        st.state shouldBe StepState.FAILED
        st.result shouldBe "quota exceeded"
    }
}
