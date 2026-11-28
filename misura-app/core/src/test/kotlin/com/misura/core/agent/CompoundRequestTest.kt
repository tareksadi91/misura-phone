package com.misura.core.agent

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class CompoundRequestTest {
    private val found = obj("places" to JsonArray(listOf(obj("placeId" to JsonPrimitive("cafe"), "label" to JsonPrimitive("Test Café")))))
    private val show = obj("placeIds" to JsonArray(listOf(JsonPrimitive("cafe"))))
    private val question = obj("line" to JsonPrimitive("Which messaging app should I check for FriendA and FriendB?"))

    @Test fun exhaustedLookupBudgetCanStillShowFoundPlacesAndAskForMissingChatApp() = runTest {
        val requests = mutableListOf<Set<String>>()
        val script = ScriptedBrain(call("find_places"), *Array(7) { call("contacts") }, Calls(listOf(
            Triple("show_places", show, null), Triple("ask", question, null),
        )))
        val brain = object : Brain {
            override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
                requests += tools.map { it.name }.toSet()
                return script.next(system, turns, tools)
            }
        }
        val contacts = FakeTool(ToolSpec("contacts", "read address book", obj())) { obj("people" to JsonArray(emptyList())) }
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, listOf(FakeTool(ToolSpec("find_places", "find places", obj())) { found }, contacts), { 0 }, this)
        val id = agent.start("Find a café and tell FriendA and FriendB to meet there", TurnOrigin.OWNER)
        advanceUntilIdle()
        store.load(id)!!.status shouldBe SessionStatus.IDLE
        store.load(id)!!.places shouldBe Places(listOf("cafe"), "cafe")
        store.load(id)!!.say shouldBe Say.Question("Which messaging app should I check for FriendA and FriendB?", emptyList())
        contacts.runs shouldBe 7
        requests.last() shouldBe setOf("show_places", "show_items", "ask", "answer")
        store.load(id)!!.pendingCalls shouldBe null
    }

    @Test fun finalResponseCannotRunAnotherActionEvenIfModelCallsUnadvertisedTool() = runTest {
        val tool = FakeTool(ToolSpec("effect", "action", obj())) { obj("ok" to JsonPrimitive(true)) }
        val store = InMemorySessionStore()
        val agent = Agent(store, ScriptedBrain(call("effect"), call("effect")), listOf(tool), { 0 }, this, maxCalls = 1)
        val id = agent.start("Do something", TurnOrigin.OWNER)
        advanceUntilIdle()
        tool.runs shouldBe 1
        store.load(id)!!.status shouldBe SessionStatus.FAILED
        store.turns(id).last().content.toString() shouldContain "not started"
    }

    @Test fun batchCrossingBudgetPreservesCompletedSearchAndOffersFinalPresentation() = runTest {
        val tool = FakeTool(ToolSpec("find_places", "find places", obj())) { found }
        val brain = ScriptedBrain(Calls(listOf(Triple("find_places", obj(), "search"), Triple("show_places", show, "display"))), call("show_places", show))
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, listOf(tool), { 0 }, this, maxCalls = 1)
        val id = agent.start("Find a café", TurnOrigin.OWNER)
        advanceUntilIdle()
        tool.runs shouldBe 1
        brain.seen.size shouldBe 2
        brain.seen.last().last().content.toString() shouldContain "not started: work call budget exhausted"
        store.load(id)!!.status shouldBe SessionStatus.IDLE
        store.load(id)!!.places shouldBe Places(listOf("cafe"), "cafe")
        store.load(id)!!.pendingCalls shouldBe null
    }

    @Test fun invalidFinalPresentationCannotStartAnotherModelLoop() = runTest {
        val store = InMemorySessionStore()
        val brain = ScriptedBrain(call("find_places"), call("show_places", obj("placeIds" to JsonArray(listOf(JsonPrimitive("invented"))))))
        val tool = FakeTool(ToolSpec("find_places", "find places", obj())) { found }
        val agent = Agent(store, brain, listOf(tool), { 0 }, this, maxCalls = 1)
        val id = agent.start("Find a café", TurnOrigin.OWNER)
        advanceUntilIdle()
        brain.seen.size shouldBe 2
        store.load(id)!!.status shouldBe SessionStatus.FAILED
        store.load(id)!!.places shouldBe null
    }

    @Test fun budgetSkippedFriendContinuesAfterConfirmedFirstSendWithoutRedraftingIt() = runTest {
        val drafted = mutableListOf<String>()
        val drafter = object : Drafter {
            override suspend fun draft(to: String, text: String): Draft {
                drafted += to; return Draft.Ready(to, to, "Testchat")
            }
            override suspend fun discard(taskId: String) = Unit
        }
        fun draft(to: String) = Triple(DRAFT_MESSAGE, obj("to" to JsonPrimitive(to), "text" to JsonPrimitive("Meet at Test Café")), null)
        val brain = ScriptedBrain(Calls(listOf(draft("FriendA"), draft("FriendB"))), Calls(listOf(draft("FriendB"))))
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, emptyList(), { 0 }, this, drafter = drafter, maxCalls = 1)
        val id = agent.start("Tell FriendA and FriendB to meet at Test Café", TurnOrigin.OWNER)
        advanceUntilIdle()
        drafted shouldBe listOf("FriendA")
        (store.load(id)!!.say as Say.Approve).more shouldBe true
        agent.taskSent("FriendA"); advanceUntilIdle()
        drafted shouldBe listOf("FriendA", "FriendB")
        (store.load(id)!!.say as Say.Approve).all shouldBe listOf("FriendB")
    }

    @Test fun showingCafeCannotEndRequestWhenAllMessageRecipientsAreUnresolved() = runTest {
        val drafter = object : Drafter {
            override suspend fun draft(to: String, text: String): Draft = Draft.NoChat(to, emptyList())
            override suspend fun discard(taskId: String) = Unit
        }
        val brain = ScriptedBrain(call("find_places"), Calls(listOf(
            Triple("show_places", show, null),
            Triple(DRAFT_MESSAGE, obj("to" to JsonPrimitive("FriendA"), "text" to JsonPrimitive("Meet at Test Café")), null),
            Triple(DRAFT_MESSAGE, obj("to" to JsonPrimitive("FriendB"), "text" to JsonPrimitive("Meet at Test Café")), null),
        )), call("ask", question))
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, listOf(FakeTool(ToolSpec("find_places", "find places", obj())) { found }), { 0 }, this, drafter = drafter)
        val id = agent.start("Find a café and tell FriendA and FriendB to meet there", TurnOrigin.OWNER)
        advanceUntilIdle()
        brain.seen.size shouldBe 3
        store.load(id)!!.status shouldBe SessionStatus.IDLE
        store.load(id)!!.places shouldBe Places(listOf("cafe"), "cafe")
        store.load(id)!!.say shouldBe Say.Question("Which messaging app should I check for FriendA and FriendB?", emptyList())
    }

    @Test fun cafeThenGroupedDraftsWaitAndContinueOnlyOnceAfterBothConfirmedSends() = runTest {
        val drafted = mutableListOf<String>()
        val drafter = object : Drafter {
            override suspend fun draft(to: String, text: String): Draft {
                drafted += to
                return Draft.Ready(to, to, "Testchat")
            }
            override suspend fun discard(taskId: String) = Unit
        }
        fun draft(to: String) = Triple(DRAFT_MESSAGE, obj("to" to JsonPrimitive(to), "text" to JsonPrimitive("Meet at Test Café"), "more" to JsonPrimitive(true)), null)
        val brain = ScriptedBrain(call("find_places"), Calls(listOf(draft("FriendA"), draft("FriendB"))), call("show_places", show))
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, listOf(FakeTool(ToolSpec("find_places", "find places", obj())) { found }), { 0 }, this, drafter = drafter)
        val id = agent.start("Find a café, tell FriendA and FriendB, then show me the café", TurnOrigin.OWNER)
        advanceUntilIdle()
        drafted shouldBe listOf("FriendA", "FriendB")
        (store.load(id)!!.say as Say.Approve).all shouldBe listOf("FriendA", "FriendB")
        brain.seen.size shouldBe 2
        agent.taskSent("FriendA"); advanceUntilIdle()
        brain.seen.size shouldBe 2
        agent.taskSent("FriendB") { it == "FriendA" }; advanceUntilIdle()
        store.load(id)!!.places shouldBe Places(listOf("cafe"), "cafe")
        agent.taskSent("FriendB") { true }; advanceUntilIdle()
        brain.seen.size shouldBe 3
        drafted shouldBe listOf("FriendA", "FriendB")
    }

    @Test fun oneReachableFriendDoesNotSilentlyDropTheUnresolvedFriend() = runTest {
        var foundApp = false
        val drafted = mutableListOf<String>()
        val drafter = object : Drafter {
            override suspend fun draft(to: String, text: String): Draft {
                drafted += to
                return if (to == "FriendB" && !foundApp) Draft.NoChat(to, emptyList()) else Draft.Ready(to, to, "Testchat")
            }
            override suspend fun discard(taskId: String) = Unit
        }
        fun draft(to: String) = Triple(DRAFT_MESSAGE, obj("to" to JsonPrimitive(to), "text" to JsonPrimitive("Meet at Test Café")), null)
        val discovery = FakeTool(ToolSpec("use_app", "Find existing chats", obj())) {
            foundApp = true; obj("result" to JsonPrimitive("FriendB has a chat in Testchat"))
        }
        val brain = ScriptedBrain(call("find_places"), Calls(listOf(draft("FriendA"), draft("FriendB"))), call("use_app"), Calls(listOf(draft("FriendB"))))
        val store = InMemorySessionStore()
        val agent = Agent(store, brain, listOf(FakeTool(ToolSpec("find_places", "find places", obj())) { found }, discovery), { 0 }, this, drafter = drafter)
        val id = agent.start("Find a café and tell FriendA and FriendB to meet there", TurnOrigin.OWNER)
        advanceUntilIdle()
        (store.load(id)!!.say as Say.Approve).more shouldBe true
        discovery.runs shouldBe 0
        agent.taskSent("FriendA"); advanceUntilIdle()
        discovery.runs shouldBe 1
        (store.load(id)!!.say as Say.Approve).all shouldBe listOf("FriendB")
        drafted shouldBe listOf("FriendA", "FriendB", "FriendB")
    }
}
