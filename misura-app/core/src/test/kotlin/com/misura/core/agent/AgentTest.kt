package com.misura.core.agent

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class AgentTest {
    private val placesSpec = ToolSpec("find_places", "find places", JsonObject(emptyMap()))
    private val twoPlaces = Json.parseToJsonElement(
        """{"places":[{"label":"Taberna","placeId":"A","mapsUri":"https://maps/a"},{"label":"Prado","placeId":"B"}]}""").jsonObject
    private val showBoth = Json.parseToJsonElement("""{"placeIds":["A","B"],"pick":"B","line":"Prado is quieter"}""").jsonObject
    private val askArea = Json.parseToJsonElement("""{"line":"Which area?","choices":["Kreuzberg","Mitte"]}""").jsonObject
    private fun ans(t: String) = obj("line" to JsonPrimitive(t))
    private fun places() = FakeTool(placesSpec) { twoPlaces }

    private class Run(val agent: Agent, val store: InMemorySessionStore, val problems: MutableList<String>)
    private fun TestScope.agent(brain: Brain, vararg tools: Tool): Run {
        val store = InMemorySessionStore(); val problems = mutableListOf<String>()
        return Run(Agent(store, brain, tools.toList(), clock = { testScheduler.currentTime }, scope = this, onProblem = { _, why -> problems += why }), store, problems)
    }
    private suspend fun Run.session(id: String) = store.load(id)!!
    private fun Turn.parts() = content["parts"]!!.jsonArray.map { it.jsonObject }


    @Test fun parallelTurnsRetainTheirOwnContextDuringToolRuns() = runTest {
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val observed = mutableListOf<Pair<String?, String?>>()
        var selection = "old"
        var captures = 0
        val tool = FakeTool(placesSpec) {
            val session = kotlin.coroutines.coroutineContext[RunningFor]?.session
            if (!firstStarted.isCompleted) {
                firstStarted.complete(Unit)
                release.await()
            }
            observed += session to kotlin.coroutines.coroutineContext[kotlinx.coroutines.CoroutineName]?.name
            twoPlaces
        }
        val brain = ScriptedBrain(call("find_places"), call("find_places"), text("second"), text("first"))
        val agent = Agent(InMemorySessionStore(), brain, listOf(tool), { testScheduler.currentTime }, this,
            turnContext = { captures++; kotlinx.coroutines.CoroutineName(selection) })
        val oldSession = agent.start("first")
        firstStarted.await()
        selection = "new"
        val newSession = agent.start("second")
        testScheduler.runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        observed shouldBe listOf(newSession to "new", oldSession to "old")
        captures shouldBe 2
    }

    @Test fun directionsUsesOnlyThisSessionsPlaces() = runTest {
        val opened = mutableListOf<String>()
        val r = agent(ScriptedBrain(call("find_places"), call("directions", obj("placeId" to JsonPrimitive("A"))), text("Opened.")), places(), Directions({ opened += it }))
        val id = r.agent.start("navigate me there"); advanceUntilIdle()
        opened shouldBe listOf(directionsUrl(PlaceFact("A", "Taberna")))
        r.session(id).steps.last().label shouldBe "Maps: directions to Taberna"
        r.session(id).say shouldBe Say.Answer("Opened.")
        val unknown = agent(ScriptedBrain(call("directions", obj("placeId" to JsonPrimitive("A"))), text("Unknown place.")), Directions({ opened += it }))
        val other = unknown.agent.start("directions"); advanceUntilIdle()
        opened.size shouldBe 1
        unknown.session(other).steps.single().state shouldBe StepState.FAILED
        unknown.store.turns(other).toString() shouldContain "placeId must come from this session"
    }

    @Test fun plainTextEndsAsAnswerAndIdle() = runTest {
        val r = agent(ScriptedBrain(text("Lost In closes at midnight")))
        val id = r.agent.start("when does Lost In close?"); advanceUntilIdle()
        r.session(id).status shouldBe SessionStatus.IDLE
        r.session(id).say shouldBe Say.Answer("Lost In closes at midnight")
        r.session(id).revision shouldBe 1
    }

    @Test fun findThenShowPlacesSetsPlacesAndLine() = runTest {
        val r = agent(ScriptedBrain(call("find_places"), call("show_places", showBoth)), places())
        val id = r.agent.start("restaurants"); advanceUntilIdle()
        r.session(id).places shouldBe Places(listOf("A", "B"), "B")
        r.session(id).say shouldBe Say.Answer("Prado is quieter")
        r.session(id).status shouldBe SessionStatus.IDLE
    }

    @Test fun findAndShowInOneReplyWorks() = runTest {
        val brain = ScriptedBrain(Calls(listOf(Triple("find_places", obj(), "a"), Triple("show_places", showBoth, "b"))))
        val r = agent(brain, places())
        val id = r.agent.start("x"); advanceUntilIdle()
        r.session(id).places shouldBe Places(listOf("A", "B"), "B")
    }

    @Test fun terminalToolStillAnswersEveryCallInTheReply() = runTest {
        val brain = ScriptedBrain(Calls(listOf(Triple("find_places", obj(), "a"), Triple("show_places", showBoth, "b"), Triple("answer", ans("done"), "c"))), text("unused"))
        val r = agent(brain, places())
        val id = r.agent.start("x"); advanceUntilIdle()
        val responses = r.store.turns(id).last().parts().map { it["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content }
        responses shouldBe listOf("a", "b", "c")
        r.session(id).status shouldBe SessionStatus.IDLE
    }

    @Test fun answerAfterShowPlacesKeepsPlaces() = runTest {
        val r = agent(ScriptedBrain(call("find_places"), call("show_places", showBoth), call("answer", ans("Prado opens at 19:00"))), places())
        val id = r.agent.start("restaurants"); advanceUntilIdle()
        r.agent.followUp(id, "when does Prado open?"); advanceUntilIdle()
        r.session(id).places shouldBe Places(listOf("A", "B"), "B")
        r.session(id).say shouldBe Say.Answer("Prado opens at 19:00")
    }

    @Test fun followUpSendsCompleteHistory() = runTest {
        val brain = ScriptedBrain(call("find_places", id = "s"), call("show_places", showBoth, id = "f"), text("Prado opens at 19:00"))
        val r = agent(brain, places())
        val id = r.agent.start("restaurants"); advanceUntilIdle()
        r.agent.followUp(id, "opening times for the second one?"); advanceUntilIdle()
        brain.seen[2].map { it.role } shouldBe listOf("user", "model", "user", "model", "user", "user")
    }

    @Test fun showPlacesWithUnknownIdsIsRejectedThenProblem() = runTest {
        val bad = Json.parseToJsonElement("""{"placeIds":["Z"]}""").jsonObject
        val brain = ScriptedBrain(call("find_places"), call("show_places", bad), call("show_places", bad))
        val r = agent(brain, places())
        val id = r.agent.start("x"); advanceUntilIdle()
        brain.seen[2].last().parts().single()["functionResponse"]!!.jsonObject["response"]!!.jsonObject.containsKey("error") shouldBe true
        r.session(id).status shouldBe SessionStatus.FAILED
        r.session(id).say.shouldBeInstanceOf<Say.Problem>()
        r.session(id).places shouldBe null
        r.problems.size shouldBe 1
    }

    @Test fun askSetsQuestion() = runTest {
        val r = agent(ScriptedBrain(call("ask", askArea)))
        val id = r.agent.start("dinner"); advanceUntilIdle()
        r.session(id).say shouldBe Say.Question("Which area?", listOf("Kreuzberg", "Mitte"))
        r.session(id).status shouldBe SessionStatus.IDLE
    }

    @Test fun chooseAgainstOldRevisionIsRefused() = runTest {
        val r = agent(ScriptedBrain(call("ask", askArea)))
        val id = r.agent.start("x"); advanceUntilIdle()
        val before = r.store.turns(id).size
        r.agent.choose(id, 0, seenRevision = 0).await() shouldBe false
        r.store.turns(id).size shouldBe before
    }

    @Test fun chooseAddsTheLabelAsAUserTurn() = runTest {
        val r = agent(ScriptedBrain(call("ask", askArea), text("Mitte it is")))
        val id = r.agent.start("x"); advanceUntilIdle()
        r.agent.choose(id, 1, seenRevision = 1).await() shouldBe true
        val t = r.store.turns(id).last { it.role == "user" && it.parts().first().containsKey("text") }.parts().map { it["text"]!!.jsonPrimitive.content }
        t shouldBe listOf("I picked Mitte", PICK_NOTE) // marked as a tap, so the screen keeps echoing what was said
        r.session(id).say shouldBe Say.Answer("Mitte it is")
    }

    @Test fun aChoiceKeepsRunningWhenTheScreenGoesAway() = runTest {
        val r = agent(ScriptedBrain(call("ask", askArea), { delay(5_000); text("Mitte it is")() }))
        val id = r.agent.start("x"); advanceUntilIdle()
        val screen = launch { r.agent.choose(id, 1, seenRevision = 1).await() }
        advanceTimeBy(1_000); screen.cancel(); advanceUntilIdle()
        r.session(id).status shouldBe SessionStatus.IDLE
        r.session(id).say shouldBe Say.Answer("Mitte it is")
    }

    @Test fun retryResendsLastUserText() = runTest {
        val brain = ScriptedBrain({ throw BrainError("x", code = "400") }, text("worked")) // not a blip: blips are retried on their own
        val r = agent(brain)
        val id = r.agent.start("bibimbap please"); advanceUntilIdle()
        r.agent.retry(id); advanceUntilIdle()
        val texts = r.store.turns(id).filter { it.role == "user" }.mapNotNull { it.parts().first()["text"]?.jsonPrimitive?.content }
        texts shouldBe listOf("bibimbap please", "bibimbap please")
        r.session(id).say shouldBe Say.Answer("worked")
    }

    @Test fun recoverEndsInterruptedSessions() = runTest {
        val r = agent(ScriptedBrain())
        r.store.save(Session("s1", SessionStatus.THINKING, 0, 0))
        r.agent.recover()
        r.session("s1").status shouldBe SessionStatus.FAILED
        r.session("s1").say shouldBe Say.Problem("Misura stopped mid-answer. Ask again.")
    }
    @Test fun toolResultGoesBackToTheModel() = runTest {
        val brain = ScriptedBrain(call("find_places", obj("query" to JsonPrimitive("x")), id = "c1"), text("none"))
        val r = agent(brain, FakeTool(placesSpec) { obj("places" to kotlinx.serialization.json.JsonArray(emptyList())) })
        r.agent.start("x"); advanceUntilIdle()
        val last = brain.seen[1].last()
        last.role shouldBe "user"
        val fr = last.parts().single()["functionResponse"]!!.jsonObject
        fr["name"]!!.jsonPrimitive.content shouldBe "find_places"
        fr["id"]!!.jsonPrimitive.content shouldBe "c1"
        fr["response"]!!.jsonObject.containsKey("places") shouldBe true
    }

    @Test fun noIdOnTheWireWhenTheModelGaveNone() = runTest {
        val brain = ScriptedBrain(call("find_places"), text("none"))
        val r = agent(brain, FakeTool(placesSpec) { obj() })
        r.agent.start("x"); advanceUntilIdle()
        brain.seen[1].last().parts().single()["functionResponse"]!!.jsonObject.containsKey("id") shouldBe false
    }

    @Test fun modelContentIsKeptVerbatim() = runTest {
        val first = call("find_places", signature = "SIG123")
        val brain = ScriptedBrain(first, text("ok"))
        val r = agent(brain, FakeTool(placesSpec) { obj() })
        r.agent.start("x"); advanceUntilIdle()
        val model = brain.seen[1].first { it.role == "model" }
        model.parts().first()["thoughtSignature"]!!.jsonPrimitive.content shouldBe "SIG123"
    }

    @Test fun tooManyToolCallsEndsInProblem() = runTest {
        val brain = ScriptedBrain(*Array(9) { call("find_places") })
        val r = agent(brain, FakeTool(placesSpec) { obj() })
        val id = r.agent.start("x"); advanceUntilIdle()
        r.session(id).status shouldBe SessionStatus.FAILED
        r.session(id).say.shouldBeInstanceOf<Say.Problem>().line shouldContain "too many steps"
    }

    @Test fun brainErrorEndsInProblem() = runTest {
        for ((code, reason) in listOf("401" to "credentials rejected: 401", "project" to "no Cloud project set")) {
            val r = agent(ScriptedBrain({ throw BrainError(code, auth = true, code = code) }))
            val id = r.agent.start("x"); advanceUntilIdle()
            r.session(id).say.shouldBeInstanceOf<Say.Problem>().line shouldBe "Misura can't reach the model ($reason)."
            r.problems.size shouldBe 1
        }
    }

    @Test fun accessDenialAndTokenExchangeFailureAreNotReportedAsExpiredSignIn() = runTest {
        for (code in listOf("403", "google_oauth_400", "google_oauth_network", "google_credentials_invalid")) {
            val r = agent(ScriptedBrain({ throw BrainError("secret fixture", auth = true, code = code) }))
            val id = r.agent.start("x"); advanceUntilIdle()
            val line = r.session(id).say.shouldBeInstanceOf<Say.Problem>().line
            line shouldContain code
            line.contains("sign-in expired") shouldBe false
            line.contains("secret fixture") shouldBe false
        }
    }

    // Like any client of a hosted model: a dropped connection or a 5xx is tried again (after 1.5 s, then 4 s). Only the model
    // call repeats: a tool that already ran is not run again, because nothing runs until a reply has arrived.
    @Test fun aNetworkBlipOnTheModelCallIsRetriedWithoutRerunningTools() = runTest {
        val tool = places()
        val brain = ScriptedBrain(call("find_places"), { throw BrainError("x", code = "network") },
            { throw BrainError("x", code = "503") }, text("done"))
        val r = agent(brain, tool)
        val id = r.agent.start("x"); advanceUntilIdle()
        r.session(id).say shouldBe Say.Answer("done")
        tool.runs shouldBe 1
        brain.seen.size shouldBe 4
    }

    @Test fun retriesAreBoundedAndSignInErrorsAreNotRetried() = runTest {
        val down = ScriptedBrain(*Array(4) { { throw BrainError("x", code = "google_oauth_network") } })
        val r = agent(down)
        val id = r.agent.start("x"); advanceUntilIdle()
        r.session(id).say.shouldBeInstanceOf<Say.Problem>().line shouldContain "google_oauth_network"
        down.seen.size shouldBe 3 // the first try and two retries
        val denied = ScriptedBrain({ throw BrainError("x", auth = true, code = "401") }, text("never"))
        val r2 = agent(denied)
        r2.agent.start("x"); advanceUntilIdle()
        denied.seen.size shouldBe 1
    }

    @Test fun slowModelTimesOut() = runTest {
        val r = agent(ScriptedBrain({ delay(31_000); text("late")() }))
        val id = r.agent.start("x"); advanceUntilIdle()
        r.session(id).status shouldBe SessionStatus.FAILED
        r.session(id).say.shouldBeInstanceOf<Say.Problem>()
    }

    @Test fun turnsOnOneSessionRunInOrder() = runTest {
        val brain = ScriptedBrain(text("a"), { delay(1_000); text("b")() }, text("c"))
        val r = agent(brain)
        val id = r.agent.start("one"); advanceUntilIdle()
        r.agent.followUp(id, "two"); r.agent.followUp(id, "three"); advanceUntilIdle()
        val userTexts = r.store.turns(id).filter { it.role == "user" }.map { it.parts().single()["text"]!!.jsonPrimitive.content }
        userTexts shouldBe listOf("one", "two", "three")
        brain.seen[2].last().parts().single()["text"]!!.jsonPrimitive.content shouldBe "three"
        r.session(id).say shouldBe Say.Answer("c")
    }

    @Test fun toolExceptionEndsInProblem() = runTest {
        val r = agent(ScriptedBrain(call("find_places")), FakeTool(placesSpec) { error("boom") })
        val id = r.agent.start("x"); advanceUntilIdle()
        r.session(id).status shouldBe SessionStatus.FAILED
        r.problems.size shouldBe 1
    }

    @Test fun aFailedTurnClosesItsOpenCallsSoTheNextTurnWorks() = runTest {
        val brain = ScriptedBrain(call("find_places", id = "c1"), text("second try worked"))
        val r = agent(brain, FakeTool(placesSpec) { if (it.isEmpty()) error("boom") else obj() })
        val id = r.agent.start("x"); advanceUntilIdle()
        r.agent.followUp(id, "try again"); advanceUntilIdle()
        val shown = brain.seen[1]
        shown.map { it.role } shouldBe listOf("user", "model", "user", "user")
        shown[2].parts().single()["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content shouldBe "c1"
        r.session(id).say shouldBe Say.Answer("second try worked")
    }

    @Test fun followUpsRunInTheOrderTheyWereSent() = runBlocking<Unit> {
        val store = InMemorySessionStore()
        val echo = object : Brain {
            override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply =
                text("ok")()
        }
        val agent = Agent(store, echo, emptyList(), System::currentTimeMillis, CoroutineScope(Dispatchers.Default))
        val id = agent.start("0")
        (1..40).map { agent.followUp(id, "$it") }.joinAll()
        while (store.load(id)!!.status == SessionStatus.THINKING) delay(10)
        val texts = store.turns(id).filter { it.role == "user" }.mapNotNull { it.parts().single()["text"]?.jsonPrimitive?.content }
        texts shouldBe (0..40).map { "$it" }
    }

    @Test fun recoverNeverOverwritesALiveTurn() = runTest {
        val r = agent(ScriptedBrain({ delay(5_000); text("the answer")() }))
        val id = r.agent.start("x"); advanceTimeBy(1_000)
        r.agent.recover(); advanceUntilIdle()
        r.session(id).say shouldBe Say.Answer("the answer")
        r.session(id).status shouldBe SessionStatus.IDLE
    }

    @Test fun stepLogsNeverContainWhatTheUserTyped() = runTest {
        val steps = mutableListOf<String>()
        val store = InMemorySessionStore()
        val agent = Agent(store, ScriptedBrain(text("ok")), emptyList(), { 0 }, this, onStep = { steps += it })
        agent.start("my bank pin is 4711"); advanceUntilIdle()
        steps.none { "4711" in it } shouldBe true
    }
}
