package com.misura.core.agent

import com.misura.core.task.Event
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class DraftTest {
    private class FakeDrafter(val chats: Map<String, String> = mapOf("frienda" to "FriendA")) : Drafter {
        val drafts = mutableListOf<Pair<String, String>>(); val discarded = mutableListOf<String>()
        override suspend fun discard(taskId: String) { discarded += taskId }
        override suspend fun draft(to: String, text: String): Draft {
            drafts += to to text
            val name = chats[to.lowercase()] ?: return Draft.NoChat(to, chats.values.toList(), listOf("Learned chat" to "chat.learned"))
            return Draft.Ready("task-${drafts.size}", name, "Testchat")
        }
    }
    private fun draftCall(to: String, text: String) = call(DRAFT_MESSAGE, obj("to" to JsonPrimitive(to), "text" to JsonPrimitive(text)))

    private fun TestScope.agent(brain: Brain, drafter: Drafter?) = InMemorySessionStore().let { it to Agent(it, brain, emptyList(), { testScheduler.currentTime }, this, drafter = drafter) }

    @Test fun draftShowsApproveCardWithAWillStep() = runTest {
        val d = FakeDrafter()
        val (store, agent) = agent(ScriptedBrain(draftCall("FriendA", "There in 5")), d)
        val id = agent.start("tell FriendA we'll be there in 5", TurnOrigin.OWNER); advanceUntilIdle()
        val s = store.load(id)!!
        s.say shouldBe Say.Approve("task-1", "FriendA", "Testchat")
        s.status shouldBe SessionStatus.IDLE
        s.steps.single().let { it.state shouldBe StepState.WILL; it.taskId shouldBe "task-1"; it.label shouldBe "Send to FriendA" }
        d.drafts shouldBe listOf("FriendA" to "There in 5")
    }

    @Test fun unknownChatGoesBackToTheModelWithTheChatsItCanUse() = runTest {
        val brain = ScriptedBrain(draftCall("Host", "hi"), call("ask", obj("line" to JsonPrimitive("Who's Host?"))))
        val (store, agent) = agent(brain, FakeDrafter())
        val id = agent.start("text Host", TurnOrigin.OWNER); advanceUntilIdle()
        store.load(id)!!.say.shouldBeInstanceOf<Say.Question>()
        val response = brain.seen[1].last().content["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject["response"]!!.jsonObject
        response["error"]!!.jsonPrimitive.content shouldContain "FriendA"
        response["error"]!!.jsonPrimitive.content shouldContain "Learned chat = chat.learned"
    }

    @Test fun anExplicitMessagingAppIsOfferedAndForwardedByPackage() = runTest {
        val drafted = mutableListOf<Triple<String, String, String?>>()
        val drafter = object : Drafter {
            override suspend fun draft(to: String, text: String): Draft = error("explicit app must use the bound overload")
            override suspend fun draft(to: String, text: String, app: String?): Draft {
                drafted += Triple(to, text, app)
                return Draft.Ready("bound", to, "Chat", app)
            }
            override suspend fun discard(taskId: String) {}
        }
        var offered: ToolSpec? = null
        val reply = call(DRAFT_MESSAGE, obj("to" to JsonPrimitive("Friend"), "text" to JsonPrimitive("hi"), "app" to JsonPrimitive("chat.expected")))
        val brain = object : Brain {
            override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
                offered = tools.single { it.name == DRAFT_MESSAGE }
                return reply()
            }
        }
        val (_, agent) = agent(brain, drafter)
        agent.start("message Friend on Chat: hi", TurnOrigin.OWNER); advanceUntilIdle()
        drafted shouldBe listOf(Triple("Friend", "hi", "chat.expected"))
        offered!!.parameters["properties"]!!.jsonObject["app"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "STRING"
        offered!!.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("to", "text")
    }

    @Test fun malformedExplicitAppsNeverBecomeAnUnboundDraft() = runTest {
        for (app in listOf(JsonPrimitive(""), JsonPrimitive("  "), JsonPrimitive(42), JsonNull, obj(), JsonPrimitive("not a package"))) {
            val drafted = mutableListOf<String?>()
            val drafter = object : Drafter {
                override suspend fun draft(to: String, text: String): Draft = error("must use the bound overload")
                override suspend fun draft(to: String, text: String, app: String?): Draft {
                    drafted += app
                    return Draft.Ready("unexpected", to, "Chat", app)
                }
                override suspend fun discard(taskId: String) {}
            }
            val brain = ScriptedBrain(call(DRAFT_MESSAGE, obj("to" to JsonPrimitive("Friend"), "text" to JsonPrimitive("hi"), "app" to app)), text("Which app?"))
            val (store, agent) = agent(brain, drafter)
            val id = agent.start("message Friend on Chat: hi", TurnOrigin.OWNER); advanceUntilIdle()
            drafted shouldBe emptyList()
            store.load(id)!!.say shouldBe Say.Answer("Which app?")
        }
    }

    @Test fun severalDraftsInOneReplyShareOneCardAndARedraftRetiresAllOfThem() = runTest {
        val d = FakeDrafter(mapOf("neighborb" to "NeighborB", "guest" to "Guest"))
        fun two(a: String, b: String) = Calls(listOf(Triple(DRAFT_MESSAGE, obj("to" to JsonPrimitive("NeighborB"), "text" to JsonPrimitive(a)), "c1"), Triple(DRAFT_MESSAGE, obj("to" to JsonPrimitive("Guest"), "text" to JsonPrimitive(b)), "c2")))
        val brain = ScriptedBrain(two("Dinner at 8", "Dinner at 8"), two("Dinner at 9", "Dinner at 9"), text("Done."))
        val (store, agent) = agent(brain, d)
        val id = agent.start("tell NeighborB and Guest dinner is at 8", TurnOrigin.OWNER); advanceUntilIdle()
        // One card, both messages, each its own send task.
        store.load(id)!!.say shouldBe Say.Approve("task-1", "NeighborB", "Testchat", also = listOf(Say.Also("task-2", "Guest")))
        (store.load(id)!!.say as Say.Approve).all shouldBe listOf("task-1", "task-2")
        agent.followUp(id, "make it 9"); advanceUntilIdle()
        // The new pair replaces the old one whole: both old tasks are retired, none can still be tapped.
        store.load(id)!!.say shouldBe Say.Approve("task-3", "NeighborB", "Testchat", also = listOf(Say.Also("task-4", "Guest")))
        d.discarded shouldBe listOf("task-1", "task-2")
        // Some went out, one did not: the model hears which by their place on the card, never a name.
        agent.retryRest(id, went = listOf(0), failed = listOf(1)); advanceUntilIdle()
        val told = brain.seen.last().last().content.toString()
        told shouldContain "number 1 went out"; told shouldContain "number 2 did not"; told.contains("Guest") shouldBe false
    }

    @Test fun withoutADrafterTheToolIsNotOffered() = runTest {
        var offered: List<String> = emptyList()
        val brain = object : Brain { override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply { offered = tools.map { it.name }; return text("ok")() } }
        val (_, agent) = agent(brain, null); agent.start("hi", TurnOrigin.OWNER); advanceUntilIdle()
        offered.contains(DRAFT_MESSAGE) shouldBe false
        val (_, agent2) = agent(brain, FakeDrafter()); agent2.start("hi", TurnOrigin.OWNER); advanceUntilIdle()
        offered shouldContain DRAFT_MESSAGE
    }

    @Test fun redraftDiscardsTheReplacedDraft() = runTest {
        val d = FakeDrafter()
        val (store, agent) = agent(ScriptedBrain(draftCall("FriendA", "There in 5"), draftCall("FriendA", "Running late")), d)
        val id = agent.start("tell FriendA", TurnOrigin.OWNER); advanceUntilIdle()
        agent.followUp(id, "say I'm late"); advanceUntilIdle()
        d.discarded shouldBe listOf("task-1")
        (store.load(id)!!.say as Say.Approve).taskId shouldBe "task-2"
    }

    private fun task(state: TaskState) = Task("t", "message", state, Payload(mapOf("name" to "FriendA", "content" to "There in 5")), emptyList(), 0, null)
    private val will = Step("s", 1, "Send to FriendA", StepState.WILL, null, 0, taskId = "t")

    @Test fun sendStepFollowsItsTask() {
        will.live(task(TaskState.NEEDS_APPROVAL), false).state shouldBe StepState.WILL
        will.live(task(TaskState.EXECUTING), false).let { it.state shouldBe StepState.RUNNING; it.label shouldBe "Sending to FriendA" }
        will.live(task(TaskState.OUTCOME_UNKNOWN), true).state shouldBe StepState.RUNNING
        will.live(task(TaskState.OUTCOME_UNKNOWN), false).let { it.state shouldBe StepState.FAILED; it.result shouldBe "not confirmed" }
        will.live(task(TaskState.DONE), false).let { it.state shouldBe StepState.DONE; it.result shouldBe "To FriendA: “There in 5”" }
        will.live(task(TaskState.FAILED), false).state shouldBe StepState.FAILED
    }

    @Test fun sendingClockStartsAtTheTapNotTheDraft() {
        val tapped = task(TaskState.EXECUTING).copy(history = listOf(Event(66_000, TaskState.NEEDS_APPROVAL, TaskState.EXECUTING, "approved")))
        will.live(tapped, false).startedAt shouldBe 66_000
    }

    @Test fun replacedDraftLeavesNoLineButASentOneKeepsItsTick() {
        val old = will.copy(id = "o", taskId = "old"); val sent = will.copy(id = "x", taskId = "sent"); val now = will.copy(id = "n", taskId = "new")
        val gone = will.copy(id = "g", taskId = "gone")
        val tasks = mapOf("old" to task(TaskState.NEEDS_APPROVAL), "sent" to task(TaskState.DONE), "new" to task(TaskState.NEEDS_APPROVAL), "gone" to task(TaskState.CANCELLED))
        val shown = shownSteps(listOf(sent, old, gone, now), Say.Approve("new", "FriendA", "Testchat"), { tasks[it] }, { false })
        shown.map { it.id to it.state } shouldBe listOf("x" to StepState.DONE) // the one will line repeats the card, so it hides
    }

    private fun draftMore(to: String, text: String) = call(DRAFT_MESSAGE, obj("to" to JsonPrimitive(to), "text" to JsonPrimitive(text), "more" to JsonPrimitive(true)))

    @Test fun aListCarriesOnAfterEachSendInTheSameTurn() = runTest {
        val d = FakeDrafter(mapOf("neighborb" to "NeighborB", "guest" to "Guest"))
        val brain = ScriptedBrain(draftMore("NeighborB", "hi"), draftCall("Guest", "hi"))
        val (store, agent) = agent(brain, d)
        val id = agent.start("say hi to NeighborB and Guest", TurnOrigin.OWNER); advanceUntilIdle()
        store.load(id)!!.say shouldBe Say.Approve("task-1", "NeighborB", "Testchat", more = true)

        agent.sent(id, "nope"); advanceUntilIdle() // not the draft on the card: nothing happens
        brain.seen.size shouldBe 1
        agent.sent(id, "task-1"); advanceUntilIdle()
        val s = store.load(id)!!
        s.say shouldBe Say.Approve("task-2", "Guest", "Testchat")
        s.steps.map { it.turn } shouldBe listOf(1, 1) // one request, one turn: NeighborB's tick stays on screen next to Guest's card
        turnNumber(store.turns(id)) shouldBe 1
        d.discarded shouldBe listOf("task-1") // asked to retire the old draft; the controller leaves a sent one alone

        agent.sent(id, "task-2"); advanceUntilIdle() // last one, nothing more: the model isn't asked again
        brain.seen.size shouldBe 2
    }

    @Test fun aProblemFurtherDownAListNeverSaysNothingWasSent() = runTest {
        val brain = ScriptedBrain(draftMore("NeighborB", "hi"), { throw BrainError("x", code = "400") })
        val (store, agent) = agent(brain, FakeDrafter(mapOf("neighborb" to "NeighborB")))
        val id = agent.start("say hi to NeighborB and Guest", TurnOrigin.OWNER); advanceUntilIdle()
        agent.sent(id, "task-1"); advanceUntilIdle()
        (store.load(id)!!.say as Say.Problem).line shouldBe "Misura can't reach the model (400). The rest is still to do."
    }

    @Test fun tryAgainAfterAFailedSendDownAListSaysItDidNotGoOut() = runTest {
        val brain = ScriptedBrain(draftMore("NeighborB", "hi"), draftCall("Guest", "hi"), draftCall("Guest", "hi"))
        val (store, agent) = agent(brain, FakeDrafter(mapOf("neighborb" to "NeighborB", "guest" to "Guest")))
        val id = agent.start("say hi to NeighborB and Guest", TurnOrigin.OWNER); advanceUntilIdle()
        agent.sent(id, "task-1"); advanceUntilIdle()
        agent.retry(id); advanceUntilIdle() // Guest's send failed; the card's Try again
        val heard = store.turns(id).last { it.role == "user" && it.content["parts"]!!.jsonArray[0].jsonObject.containsKey("text") }
        heard.content["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content shouldContain "did not go out"
        store.load(id)!!.say shouldBe Say.Approve("task-3", "Guest", "Testchat")
    }

    @Test fun aConfirmedSendMovesItsListOnWithoutTheScreen() = runTest {
        val brain = ScriptedBrain(draftMore("NeighborB", "hi"), draftCall("Guest", "hi"))
        val (store, agent) = agent(brain, FakeDrafter(mapOf("neighborb" to "NeighborB", "guest" to "Guest")))
        val id = agent.start("say hi to NeighborB and Guest", TurnOrigin.OWNER); advanceUntilIdle()
        agent.taskSent("someone-else"); advanceUntilIdle()
        brain.seen.size shouldBe 1
        agent.taskSent("task-1"); advanceUntilIdle()
        store.load(id)!!.say shouldBe Say.Approve("task-2", "Guest", "Testchat")
    }

    @Test fun wordsSaidWhileItWorksStopThatWorkAndGoNext() = runTest {
        val slow = FakeTool(ToolSpec("slow", "slow", obj()), label = { "Looking" }) { kotlinx.coroutines.delay(60_000); obj("ok" to JsonPrimitive(true)) }
        val brain = ScriptedBrain(call("slow"), text("Stopped."))
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, listOf(slow), { testScheduler.currentTime }, this)
        val id = agent.start("look it up", TurnOrigin.OWNER); testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        store.load(id)!!.steps.single().state shouldBe StepState.RUNNING
        agent.followUp(id, "stop"); advanceUntilIdle()
        val s = store.load(id)!!
        s.status shouldBe SessionStatus.IDLE
        s.say shouldBe Say.Answer("Stopped.")
        s.steps.first().let { it.state shouldBe StepState.FAILED; it.result shouldBe "stopped" }
        testScheduler.currentTime shouldBe 1_000 // the slow tool was dropped, not waited for
        // The model saw its own call answered as interrupted, then the new words.
        val last = brain.seen.last()
        last[last.size - 2].content.toString() shouldContain "interrupted by the user"
        last.last().content.toString() shouldContain "stop"
    }

    @Test fun aSendIsReportedOnceEvenWhenTheCarryOnWasStopped() = runTest {
        var stopped = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val brain = ScriptedBrain(draftMore("NeighborB", "hi"), { gate.await(); text("never")() }, text("Stopped."))
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, emptyList(), { testScheduler.currentTime }, this, drafter = FakeDrafter(mapOf("neighborb" to "NeighborB")), onStopped = { stopped++ })
        val id = agent.start("say hi to NeighborB and Guest", TurnOrigin.OWNER); advanceUntilIdle()
        agent.taskSent("task-1"); agent.sent(id, "task-1"); testScheduler.runCurrent() // controller and screen both report it
        agent.followUp(id, "stop"); advanceUntilIdle()
        brain.seen.size shouldBe 3 // draft, the one carry-on (stopped), then "stop": the second report never ran
        store.load(id)!!.say shouldBe Say.Answer("Stopped.")
        stopped shouldBe 1
    }
}
