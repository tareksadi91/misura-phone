package com.misura.core.agent

import com.misura.core.task.Task
import com.misura.core.task.TaskState

/**
 * Turns the model's draft into a task waiting for the user's tap (controller NEEDS_APPROVAL).
 * It never sends: only the card's Send tap approves and executes.
 */
interface Drafter {
    suspend fun draft(to: String, text: String): Draft
    /** An explicit destination must never silently fall back to another app. */
    suspend fun draft(to: String, text: String, app: String?): Draft =
        if (app == null) draft(to, text) else Draft.NoChat(to, emptyList())
    /** The draft on the card was replaced: retire it if nobody tapped it yet. */
    suspend fun discard(taskId: String)
}

sealed interface Draft {
    data class Ready(val taskId: String, val name: String, val app: String, val pkg: String? = null) : Draft
    /** No usable chat for [to]; [known] are reachable chats, newest first; [apps] are learned (label, package) pairs. */
    data class NoChat(val to: String, val known: List<String>, val apps: List<Pair<String, String>> = emptyList()) : Draft
}

const val DRAFT_MESSAGE = "draft_message"

private val GO = setOf("go", "send", "send it", "send them", "yes", "yes send", "yes send it", "yes send them")
/**
 * Whether spoken words are a go for the card in front: the whole utterance, nothing more. "Go ahead and tell her…" or
 * "no" are not. Matched here in code, so the model never decides that the user meant go.
 */
fun isGo(said: String): Boolean = said.lowercase().filter { it.isLetter() || it == ' ' }.trim().replace(Regex(" +"), " ") in GO

/**
 * A send's step as it is now. The task decides, never the saved step: waiting on the card = will, going out = working,
 * confirmed = ✓, anything that may not have gone out = ✕ (the card under it says what to do).
 * [checking] = the controller is still re-checking an uncertain send, which reads as still sending.
 */
fun Step.live(t: Task?, checking: Boolean): Step {
    if (taskId == null) return this
    val name = t?.payload?.get("name") ?: label.removePrefix("Send to ")
    val tapped = t?.history?.lastOrNull { it.to == TaskState.EXECUTING }?.at ?: startedAt // clock starts at the Send tap
    return when (t?.state) {
        null, TaskState.NEEDS_APPROVAL, TaskState.PLANNING -> copy(state = StepState.WILL, label = "Send to $name")
        TaskState.EXECUTING, TaskState.CHECKING -> copy(state = StepState.RUNNING, label = "Sending to $name", startedAt = tapped)
        TaskState.OUTCOME_UNKNOWN -> if (checking) copy(state = StepState.RUNNING, label = "Sending to $name", startedAt = tapped)
            else copy(state = StepState.FAILED, label = "Send to $name", result = "not confirmed")
        // The ✓ line carries the fact: what went out, to whom (mockup: "Sent, seen by FriendB").
        TaskState.DONE, TaskState.WAITING -> copy(state = StepState.DONE, label = "Sent to $name",
            result = t.payload?.get("content")?.let { "To $name: “$it”" }) // "Sent." above says it went out; the line says what
        TaskState.FAILED -> copy(state = StepState.FAILED, label = "Send to $name", result = "failed")
        TaskState.PARTIAL -> copy(state = StepState.FAILED, label = "Send to $name", result = "partly sent")
        TaskState.CANCELLED, TaskState.EXPIRED -> copy(state = StepState.FAILED, label = "Send to $name", result = "not sent")
    }
}

/**
 * Steps as shown: send steps follow their tasks; a will step shows only for the draft on the card now
 * (a draft that was replaced never happens, so it leaves no line, whether still pending or already discarded).
 */
fun shownSteps(steps: List<Step>, say: Say?, task: (String) -> Task?, checking: (String) -> Boolean): List<Step> {
    val current = (say as? Say.Approve)?.all.orEmpty().toSet()
    val live = steps.map { s -> s.taskId?.let { id -> s.live(task(id), checking(id)) } ?: s }
        .filter { s -> s.taskId == null || s.taskId in current || (s.state != StepState.WILL && task(s.taskId)?.state != TaskState.CANCELLED) }
    // The card already shows every message on it (who, where, the exact words), one or several: a line per message saying
    // it waits would repeat the card. Once sent, each message has its own ✓ or ✕ line.
    // (Only drafts: a tool's own waiting line, taskId null, always shows; it carries the goal its card is about.)
    return live.filterNot { it.state == StepState.WILL && it.taskId in current }
}
