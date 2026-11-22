package com.misura.core.exec

import com.misura.core.approval.Approval
import com.misura.core.approval.covers
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class InMemoryTaskStore : TaskStore {
    private val mutex = Mutex()
    val tasks = mutableMapOf<String, Task>()
    val approvals = mutableMapOf<String, Approval>()
    val attempts = mutableMapOf<String, Attempt>()

    override suspend fun load(id: String) = mutex.withLock { tasks[id] }
    override suspend fun save(t: Task) = mutex.withLock { tasks[t.id] = t }
    override suspend fun unfinished() = mutex.withLock { tasks.values.filter { it.state !in TERMINAL } }
    override suspend fun unreportedProblems() = mutex.withLock { tasks.values.filter { it.state in PROBLEM && it.reportedAs != it.state } }
    override suspend fun saveApproval(a: Approval) = mutex.withLock { approvals[a.taskId] = a }
    override suspend fun approvalFor(taskId: String) = mutex.withLock { approvals[taskId] }
    override suspend fun claimAttempt(taskId: String, attempt: Attempt, now: Long): Attempt? = mutex.withLock {
        val t = tasks[taskId] ?: return@withLock null
        val a = approvals[taskId] ?: return@withLock null
        val p = t.payload ?: return@withLock null
        if (t.state != TaskState.NEEDS_APPROVAL || !a.covers(taskId, p, now)) return@withLock null
        approvals[taskId] = a.copy(consumedByAttempt = attempt.id)
        attempts[attempt.id] = attempt
        tasks[taskId] = claimTransition(t, attempt, now)
        attempt
    }
    override suspend fun updateAttempt(a: Attempt) = mutex.withLock { attempts[a.id] = a }
    override suspend fun openAttempts() = mutex.withLock { attempts.values.filter { it.status in OPEN_ATTEMPT } }
    override suspend fun attemptsFor(taskId: String) = mutex.withLock { attempts.values.filter { it.taskId == taskId } }
}

/** Scriptable hand. Results are taken from the queues in order; the last one repeats. */
class FakeHand(
    override val id: String,
    override val channel: String = "whatsapp",
    override val lock: String? = null,
    private val shared: Concurrency = Concurrency(),
    private val commitDelayMs: Long = 0,
    private val checkDelayMs: Long = 0,
) : Hand {
    val commitResults = ArrayDeque<HandResult>(listOf(HandResult.Ok("sent")))
    val checkResults = ArrayDeque<HandResult>(listOf(HandResult.Ok("seen in chat")))
    var commits = 0; var checks = 0
    val committedPayloads = mutableListOf<Payload>()

    override suspend fun prepare(p: Payload) = HandResult.Prepared(p)
    override suspend fun commit(attemptId: String, p: Payload): HandResult {
        commits++; committedPayloads += p
        shared.enter(); try { delay(commitDelayMs) } finally { shared.exit() }
        return if (commitResults.size > 1) commitResults.removeFirst() else commitResults.first()
    }
    override suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long): HandResult {
        checks++
        shared.enter(); try { delay(checkDelayMs) } finally { shared.exit() }
        return if (checkResults.size > 1) checkResults.removeFirst() else checkResults.first()
    }
    override suspend fun cancel(attemptId: String) = HandResult.Unavailable("not supported")
}

class Concurrency {
    private val now = AtomicInteger(0)
    @Volatile var max = 0
    fun enter() { val n = now.incrementAndGet(); if (n > max) max = n }
    fun exit() { now.decrementAndGet() }
}
