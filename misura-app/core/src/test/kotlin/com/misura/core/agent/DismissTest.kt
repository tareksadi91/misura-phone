package com.misura.core.agent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class DismissTest {
    @Test fun thresholdUsesDistanceNotVelocity() {
        pastDismissThreshold(50f, 100f) shouldBe false
        pastDismissThreshold(50.1f, 100f) shouldBe true
        pastDismissThreshold(500f, 0f) shouldBe false
        pastDismissThreshold(-1f, 100f) shouldBe false
    }

    @Test fun dismissedFlagSurvivesJsonAndSharesTheAgeFilter() {
        val s = Session("s", SessionStatus.IDLE, 0, 100, say = Say.Question("Which?"))
        onHome(s, 101) shouldBe true
        onHome(s, 100 + 24L * 3_600_000) shouldBe false
        val hidden = s.copy(dismissedAt = 101)
        val decoded = Json.decodeFromString(Session.serializer(), Json.encodeToString(Session.serializer(), hidden))
        decoded.dismissedAt shouldBe 101L
        onHome(decoded, 102) shouldBe false
        onHome(hidden.copy(status = SessionStatus.THINKING), 102) shouldBe false
    }

    @Test fun failedLeavesHomeAfterAnHourUnlessItIsAMessage() {
        val h = 3_600_000L
        val failed = Session("f", SessionStatus.FAILED, 0, 100)
        onHome(failed, 100 + 59 * h / 60) shouldBe true
        onHome(failed, 100 + 61 * h / 60) shouldBe false
        // A failed send may not have gone out: it keeps the day.
        val msg = Session("m", SessionStatus.IDLE, 0, 100, say = Say.Approve("t", "Recipient", "WhatsApp"))
        onHome(msg, 100 + 23 * h, HomeKind.FAILED) shouldBe true
        onHome(msg, 100 + 25 * h, HomeKind.FAILED) shouldBe false
        onHome(Session("w", SessionStatus.IDLE, 0, 100, say = Say.Question("Which?")), 100 + 23 * h) shouldBe true
        onHome(Session("r", SessionStatus.THINKING, 0, 100), 100 + 48 * h) shouldBe true
    }

    @Test fun dismissBeforeWorkerStartsDoesNotStartTheModel() = runTest {
        val store = InMemorySessionStore()
        val agent = Agent(store, ScriptedBrain(), emptyList(), { 5L }, this)
        val id = agent.start("work", TurnOrigin.OWNER)
        agent.dismiss(id).await()
        store.load(id)!!.say shouldBe Say.Answer("Stopped.")
        store.load(id)!!.dismissedAt shouldBe 5L
        store.turns(id) shouldBe emptyList()
    }

    @Test fun dismissStopsRunningStepAndNewWordsReviveIt() = runTest {
        val store = InMemorySessionStore()
        val entered = CompletableDeferred<Unit>()
        val tool = object : Tool {
            override val spec = ToolSpec("working", "work", JsonObject(emptyMap()))
            override fun label(args: JsonObject) = "Working"
            override fun summary(result: JsonObject): String? = null
            override suspend fun run(args: JsonObject): JsonObject { entered.complete(Unit); awaitCancellation() }
        }
        val agent = Agent(store, ScriptedBrain(call("working"), text("New answer")), listOf(tool), { testScheduler.currentTime }, this)
        val id = agent.start("work", TurnOrigin.OWNER); runCurrent(); entered.await()
        agent.dismiss(id).await()
        store.load(id)!!.let { s ->
            s.status shouldBe SessionStatus.IDLE
            s.steps.single().result shouldBe "stopped"
            onHome(s, testScheduler.currentTime) shouldBe false
        }
        agent.followUp(id, "new words"); advanceUntilIdle()
        store.load(id)!!.dismissedAt shouldBe null
        store.load(id)!!.say shouldBe Say.Answer("New answer")
    }

    @Test fun onlyAFreshGoOnTheSameDraftResumesADismissedList() = runTest {
        val store = InMemorySessionStore()
        val agent = Agent(store, ScriptedBrain(text("Next thing")), emptyList(), { 5L }, this)
        val s = Session("s", SessionStatus.IDLE, 0, 1, say = Say.Approve("t", "Person", "Chat", more = true), dismissedAt = 2)
        store.save(s)
        agent.sent(s.id, "t").join()
        store.load(s.id)!!.dismissedAt shouldBe 2L
        agent.resumeDraft(s.id, "old-card").await()
        store.load(s.id)!!.dismissedAt shouldBe 2L
        agent.resumeDraft(s.id, "t").await()
        agent.sent(s.id, "t").join()
        store.load(s.id)!!.say shouldBe Say.Answer("Next thing")
        store.load(s.id)!!.dismissedAt shouldBe null
    }

    @Test fun dismissKeepsDraftAndDeleteDiscardsItBeforeRemovingTurns() = runTest {
        val store = InMemorySessionStore()
        val discarded = mutableListOf<String>()
        val drafter = object : Drafter {
            override suspend fun draft(to: String, text: String): Draft = error("unused")
            override suspend fun discard(taskId: String) { discarded += taskId }
        }
        val agent = Agent(store, ScriptedBrain(text("unused")), emptyList(), { 5L }, this, drafter = drafter)
        val s = Session("s", SessionStatus.IDLE, 0, 1, say = Say.Approve("t", "Person", "Chat", also = listOf(Say.Also("t2", "Other"))))
        store.save(s); store.append(s.id, Turn("user", JsonObject(emptyMap())))
        agent.dismiss(s.id).await()
        store.load(s.id)!!.say shouldBe s.say
        discarded shouldBe emptyList()
        agent.delete(s.id).await()
        discarded shouldBe listOf("t", "t2")
        store.load(s.id) shouldBe null
        store.turns(s.id) shouldBe emptyList()
        agent.followUp(s.id, "late words"); advanceUntilIdle()
        store.load(s.id) shouldBe null
    }
}
