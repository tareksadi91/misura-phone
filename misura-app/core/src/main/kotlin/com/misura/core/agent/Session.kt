package com.misura.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** WILL = happens on your tap (a draft waiting on its card); never written for anything already running. */
@Serializable enum class StepState { RUNNING, DONE, FAILED, WILL }

/** One line on screen per tool call. Label and result come from the tool's code, never the model. */
@Serializable
data class Step(
    val id: String, val turn: Int, val label: String, val state: StepState, val result: String? = null, val startedAt: Long, val endedAt: Long? = null,
    /** A send's step follows its task: the task is the truth for whether it went out (see [live]). */
    val taskId: String? = null,
    /** Package of the app the step works through (its icon); null = line icon only. */
    val app: String? = null,
)

/** The place cards on screen: ids into the session's find_places results, in the model's order. */
@Serializable data class Places(val placeIds: List<String>, val pick: String? = null)

/** The item cards on screen (things a tool found in an app): ids into the session's tool results, in the model's order. A session shows places or items, never both. */
@Serializable data class Items(val itemIds: List<String>, val pick: String? = null)

/** What Misura says: plain screen text, or a card when you have something to do. */
@Serializable
sealed interface Say {
    @Serializable @SerialName("answer") data class Answer(val line: String, val citations: List<Citation> = emptyList(), val provider: String? = null) : Say
    @Serializable @SerialName("question") data class Question(val line: String, val choices: List<String> = emptyList(),
        /** Set when a tool's own code asked (a tap that commits, stopped for your OK): the tool's id for this question; first choice = go ahead. Never set from the model's `ask`. */
        val gate: String? = null,
        /** A stopped send whose words and recipient were read off the app's screen: the quiet line above ("WhatsApp · FriendA"); [line] is then the exact text. */
        val label: String? = null,
        /** A tool's own card that says in a few words what the tap does ("I'll check if your train is late"); [line] stays the exact thing it acts on. Such a card is never a message card: no spoken "go". */
        val title: String? = null) : Say
    @Serializable @SerialName("problem") data class Problem(val line: String) : Say
    /** A drafted message waiting for your tap. The card draws the task's own payload, so the tap approves what it shows. */
    @Serializable @SerialName("approve") data class Approve(val taskId: String, val name: String, val app: String,
        /** The model has more to do once this is sent: the task isn't finished by this send, and the agent is told when it went out. */
        val more: Boolean = false,
        /** More messages on the same card (one per person): one go sends them all, each as its own tracked task. */
        val also: List<Also> = emptyList()) : Say {
        /** Every send task on this card, in the order drafted. */
        val all: List<String> get() = listOf(taskId) + also.map { it.taskId }
    }
    @Serializable data class Also(val taskId: String, val name: String)
}
