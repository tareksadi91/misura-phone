package com.misura.core.agent

import io.kotest.matchers.shouldBe
import com.misura.core.task.TaskState as T
import org.junit.jupiter.api.Test

class NavTest {
    @Test fun backFromDetailReturnsToRootKeepingIndex() {
        val n = Nav().select(2).open(Screen.Detail("P3"))
        n.top shouldBe Screen.Detail("P3")
        val back = n.back()!!
        back.top shouldBe Screen.Root
        back.index shouldBe 2
    }

    @Test fun backAtRootReturnsNull() { Nav().back() shouldBe null }

    @Test fun resetClearsStack() {
        Nav().select(3).open(Screen.FullMap).reset() shouldBe Nav()
    }

    private fun s(status: SessionStatus = SessionStatus.IDLE, places: Places? = null, say: Say? = null) = Session("s", status, 1, 0, places = places, say = say)

    @Test fun homeLineForPlaces() { homeLine(s(places = Places(List(6) { "P$it" })), "bibimbap") shouldBe "bibimbap · 6 places" }
    @Test fun homeLineForOnePlace() { homeLine(s(places = Places(listOf("P"))), "bibimbap") shouldBe "bibimbap · 1 place" }
    @Test fun homeLineForAnswer() { homeLine(s(say = Say.Answer("Lost In closes at midnight, so you have time for one more drink")), "Lost In") shouldBe "Lost In · Lost In closes at midnight, so you have…" }
    @Test fun homeLineForFailed() { homeLine(s(SessionStatus.FAILED, say = Say.Problem("x")), "bibimbap") shouldBe "bibimbap · Failed" }
    @Test fun homeLineSaysSentOnceTheMessageWentOut() {
        val a = s(say = Say.Approve("t3", "ColleagueA", "Testchat"))
        homeLine(a, "say hi") shouldBe "say hi · Message to ColleagueA"
        homeLine(a, "say hi", sent = setOf("t3")) shouldBe "say hi · Sent to ColleagueA"
        homeLine(a, "say hi", sent = setOf("t1", "t2", "t3")) shouldBe "say hi · 3 messages sent"
        homeLine(a, "say hi", sent = setOf("t1", "t2")) shouldBe "say hi · Message to ColleagueA" // two went out, ColleagueA's still waits for you
    }
    @Test fun homeKindSortsTasksIntoWhatHomeShows() {
        homeKind(s(SessionStatus.THINKING)) shouldBe HomeKind.RUNNING
        homeKind(s(SessionStatus.FAILED, say = Say.Problem("x"))) shouldBe HomeKind.FAILED
        homeKind(s(say = Say.Answer("Paris."))) shouldBe HomeKind.DONE
        homeKind(s(say = Say.Question("Which area?"))) shouldBe HomeKind.YOURS
        val draft = s(say = Say.Approve("t1", "NeighborB", "Testchat"))
        homeKind(draft) shouldBe HomeKind.YOURS
        homeKind(draft, com.misura.core.task.TaskState.NEEDS_APPROVAL) shouldBe HomeKind.YOURS
        homeKind(draft, com.misura.core.task.TaskState.EXECUTING) shouldBe HomeKind.RUNNING
        homeKind(draft, com.misura.core.task.TaskState.DONE) shouldBe HomeKind.DONE
        // A send that failed or whose outcome is unknown is never "ready for your go".
        homeKind(draft, com.misura.core.task.TaskState.OUTCOME_UNKNOWN) shouldBe HomeKind.FAILED
        homeKind(draft, com.misura.core.task.TaskState.FAILED) shouldBe HomeKind.FAILED
        homeKind(s(say = Say.Approve("t1", "NeighborB", "Testchat", more = true)), com.misura.core.task.TaskState.DONE) shouldBe HomeKind.RUNNING
        lastActive(s().copy(createdAt = 5, steps = listOf(Step("a", 1, "x", StepState.DONE, null, 10, 20), Step("b", 1, "x", StepState.WILL, null, 30)))) shouldBe 30
        homeName(draft, "say hi to NeighborB") shouldBe "Message to NeighborB"
    }

    @Test fun onlyAWholeGoCountsAsGo() {
        listOf("go", "Go.", "send it", "Yes, send them!", " SEND ").forEach { isGo(it) shouldBe true }
        listOf("no", "go ahead and tell her I'm late", "don't send", "", "going", "send it to Guest instead").forEach { isGo(it) shouldBe false }
    }

    @Test fun aCardOfSeveralMessagesHasOneStateForHome() {
        cardState(listOf(T.NEEDS_APPROVAL, T.NEEDS_APPROVAL)) shouldBe T.NEEDS_APPROVAL
        cardState(listOf(T.DONE, T.EXECUTING)) shouldBe T.EXECUTING
        cardState(listOf(T.DONE, T.WAITING)) shouldBe T.DONE
        cardState(listOf(T.DONE, T.FAILED, T.EXECUTING)) shouldBe T.FAILED       // a failure needs you, whatever else is going on
        cardState(listOf(T.DONE, T.NEEDS_APPROVAL)) shouldBe T.PARTIAL           // half went out: never shown as "ready"
        homeKind(s(say = Say.Approve("t1", "NeighborB", "Testchat", also = listOf(Say.Also("t2", "Guest")))), cardState(listOf(T.DONE, T.NEEDS_APPROVAL))) shouldBe HomeKind.FAILED
    }

    @Test fun homeLineForWorking() { homeLine(s(SessionStatus.THINKING), "bibimbap") shouldBe "bibimbap · Working" }

    @Test fun backLabelCountsPlaces() {
        val session = s(places = Places(listOf("A", "B", "C")))
        val facts = mapOf("A" to PlaceFact("A", "Namsan"))
        backLabel(session, facts, Nav().open(Screen.Detail("A"))) shouldBe "3 places"
        backLabel(session, facts, Nav()) shouldBe null
        backLabel(session, facts, Nav().open(Screen.FullMap)) shouldBe "3 places"
        backLabel(session, facts, Nav().open(Screen.Detail("A")).open(Screen.FullMap)) shouldBe "Namsan"
        backLabel(s(), facts, Nav().open(Screen.History)) shouldBe "Back"
    }

    @Test fun homeLineHidesOldAnswers() {
        homeLine(s(say = Say.Answer("Namsan opens at 12")), "bibimbap", stale = true) shouldBe "bibimbap · Older answer"
        homeLine(s(places = Places(listOf("P"))), "bibimbap", stale = true) shouldBe "bibimbap · 1 place"
    }
}
