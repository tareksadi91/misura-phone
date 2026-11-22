package com.misura.core.exec

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

import com.misura.core.approval.APPROVAL_TTL_MS
import com.misura.core.approval.Approval
import com.misura.core.approval.covers
import com.misura.core.approval.ApprovalSource
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.core.task.canGo
import com.misura.core.task.transition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A hand whose resource is also used outside the controller. Direct callers acquire the same lease. */
interface OperationHand : Hand {
    val operation: Mutex
    suspend fun commitAdmitted(attemptId: String, p: Payload): HandResult
    suspend fun checkAdmitted(attemptId: String, p: Payload, dispatchedAt: Long): HandResult
    override suspend fun commit(attemptId: String, p: Payload) =
        operation.withLock { commitAdmitted(attemptId, p) }
    override suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long) =
        operation.withLock { checkAdmitted(attemptId, p, dispatchedAt) }
}

/** Delays between checks after an uncertain send: +5 s, +20 s, +60 s from the send. */
val RECONCILE_DELAYS_MS = listOf(5_000L, 15_000L, 40_000L)

/**
 * Owns every task and every side effect (spec §6.1). The model proposes, cards display,
 * the controller decides what runs. It never commits twice and never retries an uncertain commit.
 */
class Controller(
    private val store: TaskStore,
    private val hands: List<Hand>,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val reconcileDelays: List<Long> = RECONCILE_DELAYS_MS,
) {
    /** Called with the number of attempts still being driven; the app uses it to run its "working" service. */
    @Volatile var onLiveChanged: ((Int) -> Unit)? = null
    private fun liveChanged() { onLiveChanged?.invoke(live.size) }

    /** Called when a task fails, or when checks give up without knowing whether it sent. Nothing fails silently. */
    @Volatile var onProblem: ((Task) -> Unit)? = null

    /** Called once a send is confirmed (right away or by a later re-check). Where a list hears it can move on. */
    @Volatile var onSent: ((Task) -> Unit)? = null

    /** Caller holds the task lock. Tell first, then mark: a crash in between repeats the alert rather than losing it. */
    private suspend fun problem(taskId: String) {
        val t = store.load(taskId) ?: return
        if (t.state !in PROBLEM || t.reportedAs == t.state) return
        runCatching { onProblem?.invoke(t) }
        store.save(t.copy(reportedAs = t.state))
    }

    /** Tasks whose uncertain send is still being checked; the card shows "sending" until the checks end. */
    private val reconciling: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun isChecking(taskId: String) = taskId in reconciling

    private val taskLocks = ConcurrentHashMap<String, Mutex>()
    private val handLocks = ConcurrentHashMap<String, Mutex>().apply {
        for (hand in hands.filterIsInstance<OperationHand>()) {
            val key = requireNotNull(hand.lock)
            val previous = putIfAbsent(key, hand.operation)
            require(previous == null || previous === hand.operation) { "conflicting operation locks for $key" }
        }
    }
    /** Attempts this process is still driving (dispatching or reconciling); recovery leaves them alone. */
    private val live: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val recoveryGate = Mutex()
    private var recovery: Deferred<List<Task>>? = null

    private suspend fun <T> withTask(id: String, block: suspend () -> T): T = taskLocks.getOrPut(id) { Mutex() }.withLock { block() }

    private fun lockFor(hand: Hand): Mutex? = hand.lock?.let { handLocks.getOrPut(it) { Mutex() } }

    /** Reserve fallback resources before consuming consent. Stable order prevents opposite-route deadlocks. */
    private suspend fun <T> withRoute(route: List<Hand>, block: suspend () -> T): T {
        val locks = route.sortedBy { it.lock }.mapNotNull(::lockFor).distinct()
        var acquired = 0
        try {
            for (lock in locks) { lock.lock(); acquired++ }
            return block()
        } finally {
            for (i in acquired - 1 downTo 0) locks[i].unlock()
        }
    }

    private fun handsFor(p: Payload) = hands.filter { it.channel == p["channel"] }

    suspend fun prepare(taskId: String, payload: Payload): Task = withTask(taskId) {
        val t = requireNotNull(store.load(taskId)) { "no task $taskId" }
        val prepared = (handsFor(payload).firstOrNull()?.prepare(payload) as? HandResult.Prepared)?.payload ?: payload
        val next = t.copy(payload = prepared).let { if (it.state == TaskState.NEEDS_APPROVAL) it else it.transition(TaskState.NEEDS_APPROVAL, clock(), "prepared") }
        store.save(next); next
    }

    /** A draft replaced before anyone tapped it: cancelled, never sent. Under the task lock, so it can't race a Send claim. */
    suspend fun discard(taskId: String): Task? = withTask(taskId) {
        val t = store.load(taskId) ?: return@withTask null
        if (t.state != TaskState.NEEDS_APPROVAL) return@withTask t
        t.transition(TaskState.CANCELLED, clock(), "replaced by a new draft").also { store.save(it) }
    }

    /** The tap approves exactly what the card showed. Null when the stored draft changed since: show it again. */
    suspend fun approve(taskId: String, seenDigest: String): Approval? = withTask(taskId) {
        val t = requireNotNull(store.load(taskId)) { "no task $taskId" }
        if (t.state != TaskState.NEEDS_APPROVAL) return@withTask null
        val digest = requireNotNull(t.payload).digest()
        if (digest != seenDigest) return@withTask null
        val now = clock()
        Approval(UUID.randomUUID().toString(), taskId, digest, now, now + APPROVAL_TTL_MS, ApprovalSource.BUTTON).also { store.saveApproval(it) }
    }

    suspend fun execute(taskId: String): Task {
        val t = withTask(taskId) { requireNotNull(store.load(taskId)) { "no task $taskId" } }
        val p = t.payload ?: return t
        val approval = store.approvalFor(taskId) ?: return t
        if (t.state != TaskState.NEEDS_APPROVAL || !approval.covers(taskId, p, clock())) return t
        val route = handsFor(p).ifEmpty { return t }
        return withRoute(route) {
            withTask(taskId) {
                val current = requireNotNull(store.load(taskId))
                if (current.payload != p || store.approvalFor(taskId)?.id != approval.id) return@withTask current
                val now = clock()
                val draft = Attempt(UUID.randomUUID().toString(), taskId, route.first().id, approval.id, p.digest(), AttemptStatus.CLAIMED, now, null, null)
                val claimed = store.claimAttempt(taskId, draft, now) ?: return@withTask store.load(taskId)!!
                live += claimed.id; liveChanged()
                // Once claimed, the send runs to a saved outcome even if the caller (a screen) goes away.
                try { withContext(NonCancellable) { dispatch(claimed, p, route, approval.expiresAt) } }
                catch (error: Throwable) {
                    live -= claimed.id; liveChanged()
                    throw error
                }
            }
        }
    }

    /** Runs under the task lock. Marks the attempt DISPATCHING before any hand is called. */
    private suspend fun dispatch(claimed: Attempt, p: Payload, route: List<Hand>, expiresAt: Long): Task {
        var a = claimed
        for (hand in route) {
            val now = clock()
            if (now > expiresAt) return expiredBeforeCommit(a)
            a = a.copy(handId = hand.id, status = AttemptStatus.DISPATCHING, dispatchedAt = now)
            store.updateAttempt(a)
            if (clock() > expiresAt) return expiredBeforeCommit(a)
            val r = try { if (hand is OperationHand) hand.commitAdmitted(a.id, p) else hand.commit(a.id, p) } catch (e: CancellationException) { throw e } catch (e: Exception) { HandResult.Uncertain("commit threw: ${e.message}") }
            when (r) {
                is HandResult.Unavailable -> {
                    a = a.copy(status = AttemptStatus.CLAIMED, dispatchedAt = null)
                    store.updateAttempt(a)
                    continue
                }
                is HandResult.Ok -> {
                    move(a.taskId, TaskState.CHECKING, "sent via ${hand.id}", r.evidence)
                    return afterCheck(a, p, hand, safeCheck(hand, a, p, admitted = true))
                }
                is HandResult.Failed -> if (!r.dispatched) {
                    store.updateAttempt(a.copy(status = AttemptStatus.FAILED, evidence = r.reason)); live -= a.id; liveChanged(); liveChanged()
                    return move(a.taskId, TaskState.FAILED, "failed via ${hand.id}: ${r.reason}")
                } else return unknown(a, p, hand, r.reason)
                is HandResult.Uncertain -> return unknown(a, p, hand, r.reason)
                is HandResult.Prepared -> return unknown(a, p, hand, "hand returned Prepared on commit")
            }
        }
        store.updateAttempt(a.copy(status = AttemptStatus.NOT_SENT, evidence = "no route")); live -= a.id; liveChanged(); liveChanged()
        return move(a.taskId, TaskState.PLANNING, "no route: every hand unavailable")
    }

    private suspend fun expiredBeforeCommit(a: Attempt): Task {
        store.updateAttempt(a.copy(status = AttemptStatus.NOT_SENT, dispatchedAt = null, evidence = "approval expired"))
        live -= a.id; liveChanged()
        move(a.taskId, TaskState.PLANNING, "not sent: approval expired")
        return move(a.taskId, TaskState.NEEDS_APPROVAL, "needs a fresh tap")
    }

    /** Checks take the hand's lock too: a hidden-screen check must not run during another send. */
    private suspend fun safeCheck(hand: Hand, a: Attempt, p: Payload, admitted: Boolean = false): HandResult {
        suspend fun check(): HandResult {
            val at = a.dispatchedAt ?: a.claimedAt
            return if (hand is OperationHand) hand.checkAdmitted(a.id, p, at) else hand.check(a.id, p, at)
        }
        return try {
            if (admitted) check() else withRoute(listOf(hand)) { check() }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { HandResult.Uncertain("check threw: ${e.message}") }
    }

    /** Result of a check while the task is CHECKING. */
    private suspend fun afterCheck(a: Attempt, p: Payload, hand: Hand, r: HandResult): Task = when (r) {
        is HandResult.Ok -> confirm(a, p, r.evidence)
        is HandResult.Failed -> { store.updateAttempt(a.copy(status = AttemptStatus.FAILED, evidence = r.reason)); live -= a.id; liveChanged(); move(a.taskId, TaskState.FAILED, r.reason) }
        else -> unknown(a, p, hand, (r as? HandResult.Uncertain)?.reason ?: "check inconclusive")
    }

    private suspend fun confirm(a: Attempt, p: Payload, evidence: String): Task {
        store.updateAttempt(a.copy(status = AttemptStatus.CONFIRMED, evidence = evidence)); live -= a.id; liveChanged(); liveChanged()
        if (store.load(a.taskId)!!.state == TaskState.OUTCOME_UNKNOWN) move(a.taskId, TaskState.CHECKING, "re-checking", null)
        val next = if (p["expectsReply"] == "true") TaskState.WAITING else TaskState.DONE
        return move(a.taskId, next, "confirmed", evidence).also { t -> runCatching { onSent?.invoke(t) } }
    }

    private suspend fun unknown(a: Attempt, p: Payload, hand: Hand, reason: String): Task {
        store.updateAttempt(a.copy(status = AttemptStatus.UNCERTAIN, evidence = reason))
        val t = store.load(a.taskId)!!
        val result = if (t.state == TaskState.OUTCOME_UNKNOWN) t else move(a.taskId, TaskState.OUTCOME_UNKNOWN, reason)
        reconcileLater(a, p, hand)
        return result
    }

    /** Bounded in-process checks; never commits. Leaves the task OUTCOME_UNKNOWN if nothing confirms. */
    private fun reconcileLater(a: Attempt, p: Payload, hand: Hand) {
        live += a.id; liveChanged(); reconciling += a.taskId
        scope.launch {
            try {
                for (d in reconcileDelays) {
                    delay(d)
                    val r = safeCheck(hand, a, p)
                    if (r is HandResult.Ok || r is HandResult.Failed) {
                        withTask(a.taskId) {
                            if (store.load(a.taskId)!!.state == TaskState.OUTCOME_UNKNOWN) {
                                move(a.taskId, TaskState.CHECKING, "reconcile", null)
                                afterCheck(a, p, hand, r)
                            }
                        }
                        return@launch
                    }
                }
                // Every check came back inconclusive: the task stays OUTCOME_UNKNOWN and someone has to look.
                reconciling -= a.taskId
                withTask(a.taskId) { problem(a.taskId) }
            } finally { reconciling -= a.taskId; live -= a.id; liveChanged(); liveChanged() }
        }
    }

    private suspend fun move(taskId: String, to: TaskState, note: String, evidence: String? = null): Task {
        val t = store.load(taskId)!!
        val next = t.transition(to, clock(), note, evidence)
        store.save(next)
        if (to == TaskState.FAILED || to == TaskState.PARTIAL) { problem(taskId); return store.load(taskId)!! }
        return next
    }

    /** Single-flight startup recovery. Never calls commit. */
    suspend fun recover(): List<Task> {
        val d = recoveryGate.withLock { recovery ?: scope.async { doRecover() }.also { recovery = it } }
        return d.await()
    }

    private suspend fun doRecover(): List<Task> {
        val touched = mutableListOf<Task>()
        for (a in store.openAttempts()) {
            if (a.id in live) continue
            withTask(a.taskId) {
                val t = store.load(a.taskId) ?: return@withTask
                val hand = hands.firstOrNull { it.id == a.handId }
                when (a.status) {
                    AttemptStatus.CLAIMED -> {
                        // Never dispatched: back to approval; the consumed approval forces a fresh tap.
                        store.updateAttempt(a.copy(status = AttemptStatus.NOT_SENT, evidence = "recovered before dispatch"))
                        if (t.state == TaskState.EXECUTING) { move(t.id, TaskState.PLANNING, "recovered: never sent"); touched += move(t.id, TaskState.NEEDS_APPROVAL, "needs a fresh tap") }
                    }
                    else -> {
                        store.updateAttempt(a.copy(status = AttemptStatus.UNCERTAIN))
                        if (t.state != TaskState.OUTCOME_UNKNOWN && t.canGo(TaskState.OUTCOME_UNKNOWN)) move(t.id, TaskState.OUTCOME_UNKNOWN, "recovered: may have been sent")
                        touched += store.load(t.id)!!
                        if (hand != null) reconcileLater(a, store.load(t.id)!!.payload!!, hand)
                    }
                }
            }
        }
        // A crash between saving an attempt's outcome and moving its task leaves the task stuck.
        for (t0 in store.unfinished()) {
            if (t0.state != TaskState.EXECUTING && t0.state != TaskState.CHECKING) continue
            withTask(t0.id) {
                val t = store.load(t0.id) ?: return@withTask
                if (t.state != TaskState.EXECUTING && t.state != TaskState.CHECKING) return@withTask
                val attempts = store.attemptsFor(t.id)
                if (attempts.any { it.status in OPEN_ATTEMPT || it.id in live }) return@withTask
                val last = attempts.maxByOrNull { it.claimedAt } ?: return@withTask
                when (last.status) {
                    AttemptStatus.CONFIRMED -> {
                        if (t.state == TaskState.EXECUTING) move(t.id, TaskState.CHECKING, "recovered", null)
                        touched += move(t.id, if (t.payload?.get("expectsReply") == "true") TaskState.WAITING else TaskState.DONE, "recovered: confirmed", last.evidence)
                    }
                    AttemptStatus.FAILED -> touched += move(t.id, TaskState.FAILED, "recovered: ${last.evidence ?: "failed"}")
                    AttemptStatus.NOT_SENT -> touched += move(t.id, TaskState.PLANNING, "recovered: not sent")
                    else -> Unit
                }
            }
        }
        // Problems nobody was told about: the app died before alerting, or a send has no hand left to check it.
        for (t in store.unreportedProblems()) {
            if (t.state == TaskState.OUTCOME_UNKNOWN && isChecking(t.id)) continue
            withTask(t.id) { problem(t.id) }
        }
        return touched
    }
}
