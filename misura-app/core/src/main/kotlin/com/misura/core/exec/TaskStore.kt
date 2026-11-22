package com.misura.core.exec

import com.misura.core.approval.Approval
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.core.task.transition
import kotlinx.serialization.Serializable

enum class AttemptStatus { CLAIMED, DISPATCHING, UNCERTAIN, CONFIRMED, NOT_SENT, FAILED }

/** One try at committing an approved payload. Saved before anything is sent. */
@Serializable
data class Attempt(
    val id: String,
    val taskId: String,
    val handId: String,
    val approvalId: String,
    val payloadDigest: String,
    val status: AttemptStatus,
    val claimedAt: Long,
    val dispatchedAt: Long?,
    val evidence: String?,
)

val TERMINAL = setOf(TaskState.DONE, TaskState.PARTIAL, TaskState.FAILED, TaskState.CANCELLED, TaskState.EXPIRED)
/** States someone has to look at. */
val PROBLEM = setOf(TaskState.FAILED, TaskState.PARTIAL, TaskState.OUTCOME_UNKNOWN)
val OPEN_ATTEMPT = setOf(AttemptStatus.CLAIMED, AttemptStatus.DISPATCHING, AttemptStatus.UNCERTAIN)

/** The task change a store applies inside [TaskStore.claimAttempt]. */
fun claimTransition(t: Task, attempt: Attempt, now: Long): Task = t.transition(TaskState.EXECUTING, now, "attempt ${attempt.id} claimed")

interface TaskStore {
    suspend fun load(id: String): Task?
    suspend fun save(t: Task)
    suspend fun unfinished(): List<Task>
    /** Tasks in a [PROBLEM] state the user hasn't been told about yet. */
    suspend fun unreportedProblems(): List<Task>
    suspend fun saveApproval(a: Approval)
    suspend fun approvalFor(taskId: String): Approval?
    /**
     * One transaction: the task is NEEDS_APPROVAL, its approval is unconsumed and covers the payload,
     * the approval is marked consumed, the attempt inserted and the task moved to EXECUTING.
     * Returns null if any check fails.
     */
    suspend fun claimAttempt(taskId: String, attempt: Attempt, now: Long): Attempt?
    suspend fun updateAttempt(a: Attempt)
    suspend fun openAttempts(): List<Attempt>
    suspend fun attemptsFor(taskId: String): List<Attempt>
}
