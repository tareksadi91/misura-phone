package com.misura.core.agent

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** The two moments the app may tell the user about (M3): a turn settled with its result saved, and a finished list fully sent. */
class SettledTest {
    private class Drafts(val chats: Map<String, String>) : Drafter {
        var n = 0
        override suspend fun discard(taskId: String) {}
        override suspend fun draft(to: String, text: String): Draft {
            val name = chats[to.lowercase()] ?: return Draft.NoChat(to, chats.values.toList(), emptyList())
            return Draft.Ready("task-${++n}", name, "Testchat")
        }
    }
    private fun draft(to: String, more: Boolean = false) = Triple(DRAFT_MESSAGE,
        obj("to" to JsonPrimitive(to), "text" to JsonPrimitive("hi"), "more" to JsonPrimitive(more)), null as String?)

    private class Run(val agent: Agent, val store: InMemorySessionStore, val settled: MutableList<Session>, val allSent: MutableList<Session>)
    private fun TestScope.run(brain: Brain, throwing: Boolean = false): Run {
        val store = InMemorySessionStore(); val settled = mutableListOf<Session>(); val allSent = mutableListOf<Session>()
        val agent = Agent(store, brain, emptyList(), { testScheduler.currentTime }, this,
            drafter = Drafts(mapOf("neighborb" to "NeighborB", "guest" to "Guest")),
            onSettled = { settled += it; if (throwing) error("alert failed") },
            onAllSent = { allSent += it; if (throwing) error("alert failed") })
        return Run(agent, store, settled, allSent)
    }

    @Test fun anAnswerSettlesOnceWithTheSavedResult() = runTest {
        val r = run(ScriptedBrain(text("Hornbach closes at 20:00.")))
        val id = r.agent.start("when does hornbach close", TurnOrigin.OWNER); advanceUntilIdle()
        r.settled.size shouldBe 1
        r.settled.single().let { it.id shouldBe id; it.status shouldBe SessionStatus.IDLE; it.say shouldBe Say.Answer("Hornbach closes at 20:00.") }
        r.settled.single() shouldBe r.store.load(id)
    }

    @Test fun aWaitingDraftSettlesAsAnApprovalAndIsNotAllSent() = runTest {
        val r = run(ScriptedBrain(Calls(listOf(draft("NeighborB")))))
        r.agent.start("say hi to NeighborB", TurnOrigin.OWNER); advanceUntilIdle()
        r.settled.single().say.shouldBeInstanceOf<Say.Approve>()
        r.allSent shouldBe emptyList()
    }

    @Test fun aFinishedCardIsAllSentOnlyOnceEveryMessageWent() = runTest {
        val r = run(ScriptedBrain(Calls(listOf(draft("NeighborB"), draft("Guest")))))
        val id = r.agent.start("say hi to NeighborB and Guest", TurnOrigin.OWNER); advanceUntilIdle()
        val sent = mutableSetOf<String>()
        sent += "task-1"; r.agent.taskSent("task-1") { it in sent }; advanceUntilIdle()
        r.allSent shouldBe emptyList()
        sent += "task-2"; r.agent.taskSent("task-2") { it in sent }; advanceUntilIdle()
        r.allSent.single().id shouldBe id
    }

    @Test fun aListWithMoreToDoCarriesOnInsteadOfAllSent() = runTest {
        val brain = ScriptedBrain(Calls(listOf(draft("NeighborB", more = true))), Calls(listOf(draft("Guest"))))
        val r = run(brain)
        val id = r.agent.start("say hi to NeighborB, then Guest", TurnOrigin.OWNER); advanceUntilIdle()
        r.agent.taskSent("task-1"); advanceUntilIdle()
        r.allSent shouldBe emptyList()
        r.store.load(id)!!.say shouldBe Say.Approve("task-2", "Guest", "Testchat")
    }

    @Test fun anUnknownTaskIsNeverAllSent() = runTest {
        val r = run(ScriptedBrain(Calls(listOf(draft("NeighborB")))))
        r.agent.start("say hi to NeighborB", TurnOrigin.OWNER); advanceUntilIdle()
        r.agent.taskSent("someone-else"); advanceUntilIdle()
        r.allSent shouldBe emptyList()
    }

    @Test fun aFailingAlertNeverBreaksTheWork() = runTest {
        val r = run(ScriptedBrain(text("first"), text("second"), Calls(listOf(draft("NeighborB")))), throwing = true)
        val id = r.agent.start("one", TurnOrigin.OWNER); advanceUntilIdle()
        r.store.load(id)!!.let { it.status shouldBe SessionStatus.IDLE; it.say shouldBe Say.Answer("first") }
        r.agent.followUp(id, "two"); advanceUntilIdle()
        r.store.load(id)!!.say shouldBe Say.Answer("second")
        r.agent.followUp(id, "three"); advanceUntilIdle()
        r.agent.taskSent("task-1"); advanceUntilIdle()
        r.allSent.size shouldBe 1
        r.settled.size shouldBe 3
    }

    @Test fun dismissDeleteAndFailureAreReportedSoStaleAlertsGo() = runTest {
        val r = run(ScriptedBrain(Calls(listOf(draft("NeighborB"))), text("kept"), { error("model down") }))
        val a = r.agent.start("say hi to NeighborB", TurnOrigin.OWNER); advanceUntilIdle()
        r.agent.dismiss(a).await()
        r.settled.last().let { it.id shouldBe a; (it.dismissedAt != null) shouldBe true }
        val b = r.agent.start("keep", TurnOrigin.OWNER); advanceUntilIdle()
        r.agent.delete(b).await()
        r.settled.last().id shouldBe b
        r.store.load(b) shouldBe null
        val c = r.agent.start("fails", TurnOrigin.OWNER); advanceUntilIdle()
        r.settled.last().let { it.id shouldBe c; it.status shouldBe SessionStatus.FAILED }
    }

    @Test fun aDismissedFinishedCardIsNeverAnnouncedAsSent() = runTest {
        val r = run(ScriptedBrain(Calls(listOf(draft("NeighborB")))))
        val id = r.agent.start("say hi to NeighborB", TurnOrigin.OWNER); advanceUntilIdle()
        r.agent.dismiss(id).await()
        r.agent.taskSent("task-1"); advanceUntilIdle()
        r.allSent shouldBe emptyList()
    }
}
