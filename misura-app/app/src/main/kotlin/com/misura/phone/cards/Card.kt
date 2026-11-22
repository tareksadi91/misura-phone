package com.misura.phone.cards

import com.misura.core.task.Task
import com.misura.core.task.TaskState.*

/** What one task looks like right now (v1: archive/card-system-v1.html; target: design-system.md §4, §9.5). Header = source + title. */
sealed interface Card {
    val source: String
    val title: String

    data class Progress(override val source: String, override val title: String, val line: String, val since: Long) : Card
    data class Choices(override val source: String, override val title: String, val options: List<String>) : Card
    /** Exact outgoing content; [commit] names the effect of the one Commit button. */
    data class Approve(override val source: String, override val title: String, val content: String, val commit: String) : Card
    data class Done(override val source: String, override val title: String, val word: String, val at: Long) : Card
    data class Problem(override val source: String, override val title: String, val word: String, val line: String) : Card
}

fun cardFor(t: Task, answer: List<String>, checking: Boolean = false): Card {
    val src = t.payload?.get("channel")?.replaceFirstChar { it.uppercase() } ?: "Misura"
    val who = t.payload?.get("name") ?: t.payload?.get("recipient") ?: ""
    val content = t.payload?.get("content") ?: ""
    val enteredAt = t.history.lastOrNull { it.to == t.state }?.at ?: t.createdAt
    // Sending, checking and still-being-checked are one working state to the user, timed from the tap.
    val tappedAt = t.history.lastOrNull { it.to == EXECUTING }?.at ?: enteredAt
    // ponytail: "buzzed" separates the ask from the reply in the experiment; a real task kind replaces it.
    return when (t.state) {
        NEEDS_APPROVAL -> Card.Approve(src, who, content, if (t.buzzed) "Send to $who" else "Ask $who")
        WAITING -> Card.Progress(src, who, "Asking", enteredAt)
        EXECUTING, CHECKING -> Card.Progress(src, who, "Sending", tappedAt)
        PLANNING -> if (t.buzzed) Card.Choices(src, who, answer) else Card.Progress(src, who, "Drafting", enteredAt)
        DONE -> Card.Done(src, who, "Sent", enteredAt)
        OUTCOME_UNKNOWN -> if (checking) Card.Progress(src, who, "Sending", tappedAt)
            else Card.Problem(src, who, "Unconfirmed", "Couldn't see it in the chat. Check before sending again; nothing will be resent.")
        FAILED -> Card.Problem(src, who, "Failed", "Check the chat before trying again.")
        PARTIAL -> Card.Problem(src, who, "Partly sent", "Check the chat; nothing will be resent.")
        EXPIRED -> Card.Problem(src, who, "Expired", if (cameFrom(t, WAITING)) "No answer came." else "Nothing was sent.")
        // Cancel after a dispatch only stops tracking (design-system.md §5, long-press).
        CANCELLED -> Card.Problem(src, who, "Cancelled", if (dispatched(t)) "Stopped tracking. It may have sent." else "Nothing was sent.")
    }
}

private fun cameFrom(t: Task, s: com.misura.core.task.TaskState) = t.history.lastOrNull()?.from == s

/** Left EXECUTING other than back to PLANNING, which the controller only allows when nothing went out. */
private fun dispatched(t: Task) = t.history.any { it.from == EXECUTING && it.to != PLANNING }
