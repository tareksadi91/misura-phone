package com.misura.phone.cards

import com.misura.core.task.Event
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.core.task.TaskState.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class CardForTest {
    private fun task(state: TaskState, recipient: String, content: String, buzzed: Boolean = false, history: List<Event> = emptyList()) =
        Task("t", "experiment", state, Payload(mapOf("channel" to "testchat", "recipient" to recipient, "content" to content)), history, 0, null, buzzed)

    @Test fun anUnaskedRequestIsAnApproveCardThatAsksTheBot() {
        cardFor(task(NEEDS_APPROVAL, "Bot", "Dinner?"), emptyList()) shouldBe Card.Approve("Testchat", "Bot", "Dinner?", "Ask Bot")
    }

    @Test fun waitingForTheBotIsProgressSinceTheAsk() {
        val c = cardFor(task(WAITING, "Bot", "Dinner?", history = listOf(Event(42, EXECUTING, WAITING, ""))), emptyList())
        c shouldBe Card.Progress("Testchat", "Bot", "Asking", 42)
    }

    @Test fun anAnswerBecomesChoicesWithOneRowPerMessage() {
        cardFor(task(PLANNING, "Bot", "Dinner?", buzzed = true), listOf("Lupo", "Mimi", "Ora")) shouldBe
            Card.Choices("Testchat", "Bot", listOf("Lupo", "Mimi", "Ora"))
    }

    @Test fun aDraftToTheFriendIsAnApproveCardNamingTheEffect() {
        cardFor(task(NEEDS_APPROVAL, "Friend", "Lupo at 8?", buzzed = true), emptyList()) shouldBe
            Card.Approve("Testchat", "Friend", "Lupo at 8?", "Send to Friend")
    }

    @Test fun sendingIsProgress() {
        cardFor(task(EXECUTING, "Friend", "x", buzzed = true), emptyList()).shouldBeInstanceOf<Card.Progress>().line shouldBe "Sending"
    }

    @Test fun doneShowsSentWithTheTimeItFinished() {
        cardFor(task(DONE, "Friend", "x", true, listOf(Event(7, CHECKING, DONE, ""))), emptyList()) shouldBe Card.Done("Testchat", "Friend", "Sent", 7)
    }

    @Test fun anUnknownOutcomeStillBeingCheckedLooksLikeSendingSinceTheTap() {
        val h = listOf(Event(10, NEEDS_APPROVAL, EXECUTING, ""), Event(12, EXECUTING, OUTCOME_UNKNOWN, ""))
        cardFor(task(OUTCOME_UNKNOWN, "Friend", "x", true, h), emptyList(), checking = true) shouldBe Card.Progress("Testchat", "Friend", "Sending", 10)
    }

    @Test fun checkingAfterASendCountsFromTheTap() {
        val h = listOf(Event(10, NEEDS_APPROVAL, EXECUTING, ""), Event(12, EXECUTING, CHECKING, ""))
        cardFor(task(CHECKING, "Friend", "x", true, h), emptyList()) shouldBe Card.Progress("Testchat", "Friend", "Sending", 10)
    }

    @Test fun anUnknownOutcomeAfterChecksGaveUpIsUnconfirmedAndPromisesNoResend() {
        val c = cardFor(task(OUTCOME_UNKNOWN, "Friend", "x", true), emptyList(), checking = false).shouldBeInstanceOf<Card.Problem>()
        c.word shouldBe "Unconfirmed"
        c.line shouldBe "Couldn't see it in the chat. Check before sending again; nothing will be resent."
    }

    @Test fun cancellingAfterAnUnknownOutcomeDoesNotClaimNothingWasSent() {
        val c = cardFor(task(CANCELLED, "Friend", "x", true, listOf(Event(1, EXECUTING, OUTCOME_UNKNOWN, ""), Event(2, OUTCOME_UNKNOWN, CANCELLED, ""))), emptyList())
        c.shouldBeInstanceOf<Card.Problem>().line shouldBe "Stopped tracking. It may have sent."
    }

    @Test fun anExpiredAskSaysNoAnswerCame() {
        val c = cardFor(task(EXPIRED, "Bot", "x", history = listOf(Event(1, WAITING, EXPIRED, ""))), emptyList())
        c.shouldBeInstanceOf<Card.Problem>().line shouldBe "No answer came."
    }

    @Test fun cancellingAfterAConfirmedAskDoesNotClaimNothingWasSent() {
        val h = listOf(Event(1, NEEDS_APPROVAL, EXECUTING, ""), Event(2, EXECUTING, CHECKING, ""), Event(3, CHECKING, WAITING, ""), Event(4, WAITING, CANCELLED, ""))
        cardFor(task(CANCELLED, "Bot", "x", history = h), emptyList()).shouldBeInstanceOf<Card.Problem>().line shouldBe "Stopped tracking. It may have sent."
    }
}
