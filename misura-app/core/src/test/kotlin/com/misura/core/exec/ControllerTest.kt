package com.misura.core.exec

import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.core.task.TaskState.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ControllerTest {
    private val payload = Payload(mapOf("channel" to "whatsapp", "recipient" to "friendb", "content" to "Running 10 late"))

    private fun TestScope.controller(store: TaskStore, vararg hands: Hand) =
        Controller(store, hands.toList(), clock = { testScheduler.currentTime }, scope = backgroundScope)

    private suspend fun seed(store: InMemoryTaskStore, id: String = "t1", state: TaskState = NEEDS_APPROVAL, p: Payload = payload) =
        store.save(Task(id, "message", state, p, emptyList(), 0, null))

    /** The tap approves what the card showed: the stored draft at render time. */
    private suspend fun Controller.approveSeen(store: TaskStore, id: String) = approve(id, store.load(id)!!.payload!!.digest())

    @Test fun `waiting for a busy hand leaves no recoverable attempt and expired consent cannot commit`() = runTest {
        val store = InMemoryTaskStore()
        val ttl = com.misura.core.approval.APPROVAL_TTL_MS
        val hand = FakeHand("hidden", lock = "hidden_screen", commitDelayMs = ttl + 10)
        val c = controller(store, hand)
        for (id in listOf("first", "waiting", "cancelled")) {
            seed(store, id)
            c.approveSeen(store, id)
        }
        val first = launch { c.execute("first") }
        runCurrent()
        val waiting = async { c.execute("waiting") }
        val cancelled = launch { c.execute("cancelled") }
        runCurrent()
        store.attemptsFor("waiting").size shouldBe 0
        store.load("waiting")!!.state shouldBe NEEDS_APPROVAL
        cancelled.cancel()
        cancelled.join()
        store.approvalFor("cancelled")!!.consumedByAttempt shouldBe null
        first.join()
        waiting.await().state shouldBe NEEDS_APPROVAL
        hand.commits shouldBe 1
        c.approveSeen(store, "waiting")
        c.execute("waiting").state shouldBe DONE
        hand.commits shouldBe 2
    }

    @Test fun `an externally held operation allows discard and recovery before any route is claimed`() = runTest {
        val store = InMemoryTaskStore()
        val hidden = FakeHand("hidden", lock = "hidden_screen")
        val lease = kotlinx.coroutines.sync.Mutex(locked = true)
        val hand = object : OperationHand, Hand by hidden {
            override val operation = lease
            override suspend fun commitAdmitted(attemptId: String, p: Payload) = hidden.commit(attemptId, p)
            override suspend fun checkAdmitted(attemptId: String, p: Payload, dispatchedAt: Long) = hidden.check(attemptId, p, dispatchedAt)
            override suspend fun commit(attemptId: String, p: Payload) = super<OperationHand>.commit(attemptId, p)
            override suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long) = super<OperationHand>.check(attemptId, p, dispatchedAt)
        }
        val first = FakeHand("notification")
        seed(store)
        seed(store, "edited")
        val c = controller(store, first, hand)
        c.approveSeen(store, "t1")
        c.approveSeen(store, "edited")
        val waiting = async { c.execute("t1") }
        val edited = async { c.execute("edited") }
        runCurrent()
        store.attemptsFor("t1") shouldBe emptyList()
        controller(store, first, hand).recover() shouldBe emptyList()
        c.discard("t1")!!.state shouldBe CANCELLED
        val changed = Payload(payload.fields + ("content" to "replacement text"))
        c.prepare("edited", changed)
        val replacement = c.approveSeen(store, "edited")
        lease.unlock()
        waiting.await().state shouldBe CANCELLED
        edited.await().state shouldBe NEEDS_APPROVAL
        store.approvalFor("edited") shouldBe replacement
        store.attemptsFor("edited") shouldBe emptyList()
        first.commits shouldBe 0
        hidden.commits shouldBe 0
    }

    @Test fun `consent expiring while dispatch intent is saved prevents invocation`() = runTest {
        val backing = InMemoryTaskStore()
        seed(backing)
        val store = object : TaskStore by backing {
            override suspend fun updateAttempt(a: Attempt) {
                backing.updateAttempt(a)
                if (a.status == AttemptStatus.DISPATCHING) delay(com.misura.core.approval.APPROVAL_TTL_MS + 1)
            }
        }
        val hand = FakeHand("notification")
        val c = controller(store, hand)
        c.approveSeen(store, "t1")
        c.execute("t1").state shouldBe NEEDS_APPROVAL
        store.attemptsFor("t1").single().status shouldBe AttemptStatus.NOT_SENT
        hand.commits shouldBe 0
    }

    @Test fun `an unavailable route cannot pass expired consent to its fallback`() = runTest {
        val store = InMemoryTaskStore()
        val first = FakeHand("notification", commitDelayMs = com.misura.core.approval.APPROVAL_TTL_MS + 1)
        first.commitResults.apply { clear(); add(HandResult.Unavailable("no route")) }
        val fallback = FakeHand("hidden", lock = "hidden_screen")
        seed(store)
        val c = controller(store, first, fallback)
        c.approveSeen(store, "t1")
        c.execute("t1").state shouldBe NEEDS_APPROVAL
        store.attemptsFor("t1").single().status shouldBe AttemptStatus.NOT_SENT
        fallback.commits shouldBe 0
    }

    @Test fun `approving a draft the user did not see is refused`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n")
        seed(store); val c = controller(store, hand)
        val seen = Payload(mapOf("channel" to "whatsapp", "recipient" to "friendb", "content" to "Running 5 late")).digest()
        c.approve("t1", seen) shouldBe null
        store.approvalFor("t1") shouldBe null
        c.execute("t1").state shouldBe NEEDS_APPROVAL
        hand.commits shouldBe 0
    }

    @Test fun `discarded draft refuses old approval and never sends`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n")
        seed(store); val c = controller(store, hand)
        c.approveSeen(store, "t1")
        c.discard("t1")!!.state shouldBe CANCELLED
        c.approveSeen(store, "t1") shouldBe null
        c.execute("t1").state shouldBe CANCELLED
        hand.commits shouldBe 0
    }

    @Test fun `no approval no commit`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n")
        seed(store)
        controller(store, hand).execute("t1").state shouldBe NEEDS_APPROVAL
        hand.commits shouldBe 0
    }

    @Test fun `write failure before dispatch releases live attempt for safe recovery`() = runTest {
        val backing = InMemoryTaskStore(); seed(backing)
        var fail = true
        val store = object : TaskStore by backing {
            override suspend fun updateAttempt(a: Attempt) {
                if (fail && a.status == AttemptStatus.DISPATCHING) { fail = false; error("storage offline") }
                backing.updateAttempt(a)
            }
        }
        val hand = FakeHand("n"); val c = controller(store, hand)
        val live = mutableListOf<Int>(); c.onLiveChanged = { live += it }
        c.approveSeen(store, "t1")
        runCatching { c.execute("t1") }.exceptionOrNull()?.message shouldBe "storage offline"
        live.last() shouldBe 0
        hand.commits shouldBe 0
        c.recover()
        store.load("t1")!!.state shouldBe NEEDS_APPROVAL
        c.execute("t1")
        hand.commits shouldBe 0
    }

    @Test fun `write failure after commit allows check-only recovery without resend`() = runTest {
        val backing = InMemoryTaskStore(); seed(backing)
        var fail = true
        val store = object : TaskStore by backing {
            override suspend fun save(t: Task) {
                if (fail && t.state == CHECKING) { fail = false; error("storage offline") }
                backing.save(t)
            }
        }
        val hand = FakeHand("n"); val c = controller(store, hand)
        c.approveSeen(store, "t1")
        runCatching { c.execute("t1") }.exceptionOrNull()?.message shouldBe "storage offline"
        hand.commits shouldBe 1
        c.recover()
        advanceTimeBy(120_000); runCurrent()
        store.load("t1")!!.state shouldBe DONE
        hand.commits shouldBe 1
    }

    @Test fun `prepare stores payload and needs approval`() = runTest {
        val store = InMemoryTaskStore(); seed(store, state = PLANNING, p = Payload(emptyMap()))
        val t = controller(store, FakeHand("n")).prepare("t1", payload)
        t.state shouldBe NEEDS_APPROVAL
        t.payload shouldBe payload
    }

    @Test fun `two concurrent executes commit once`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n", commitDelayMs = 100)
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        listOf(async { c.execute("t1") }, async { c.execute("t1") }).awaitAll()
        hand.commits shouldBe 1
    }

    @Test fun `recover racing execute commits once`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n", commitDelayMs = 100)
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        listOf(async { c.execute("t1") }, async { c.recover(); null }).awaitAll()
        advanceTimeBy(120_000); runCurrent()
        hand.commits shouldBe 1
        store.load("t1")!!.state shouldBe DONE
    }

    @Test fun `uncertain then confirmed by reconcile without restart`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n")
        hand.commitResults.apply { clear(); add(HandResult.Uncertain("dispatched")) }
        hand.checkResults.apply { clear(); add(HandResult.Uncertain("not yet")); add(HandResult.Ok("seen")) }
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        c.execute("t1").state shouldBe OUTCOME_UNKNOWN
        advanceTimeBy(120_000); runCurrent()
        store.load("t1")!!.state shouldBe DONE
        hand.commits shouldBe 1
    }

    @Test fun `crash after claim recovers to needs approval without commit`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n")
        seed(store, state = EXECUTING)
        store.attempts["a1"] = Attempt("a1", "t1", "n", "ap1", payload.digest(), AttemptStatus.CLAIMED, 0, null, null)
        controller(store, hand).recover()
        store.load("t1")!!.state shouldBe NEEDS_APPROVAL
        store.attempts["a1"]!!.status shouldBe AttemptStatus.NOT_SENT
        hand.commits shouldBe 0
    }

    @Test fun `crash after dispatching recovers to check never commit`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n")
        seed(store, state = EXECUTING)
        store.attempts["a1"] = Attempt("a1", "t1", "n", "ap1", payload.digest(), AttemptStatus.DISPATCHING, 0, 0, null)
        controller(store, hand).recover()
        advanceTimeBy(120_000); runCurrent()
        hand.commits shouldBe 0
        (hand.checks >= 1) shouldBe true
        store.load("t1")!!.state shouldBe DONE
    }

    @Test fun `unavailable falls through to next hand with identical payload`() = runTest {
        val store = InMemoryTaskStore()
        val a = FakeHand("notification").apply { commitResults.apply { clear(); add(HandResult.Unavailable("no notification")) } }
        val b = FakeHand("hidden")
        seed(store); val c = controller(store, a, b); c.approveSeen(store, "t1")
        c.execute("t1")
        b.committedPayloads shouldBe listOf(payload)
        store.load("t1")!!.state shouldBe DONE
    }

    @Test fun `all hands unavailable goes to planning`() = runTest {
        val store = InMemoryTaskStore()
        val a = FakeHand("notification").apply { commitResults.apply { clear(); add(HandResult.Unavailable("none")) } }
        seed(store); val c = controller(store, a); c.approveSeen(store, "t1")
        c.execute("t1").state shouldBe PLANNING
    }

    @Test fun `consumed approval cannot authorize a second attempt`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n")
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        c.execute("t1")
        store.save(store.load("t1")!!.copy(state = NEEDS_APPROVAL))
        c.execute("t1")
        hand.commits shouldBe 1
    }

    @Test fun `hidden-screen hands on different apps never commit concurrently`() = runTest {
        val store = InMemoryTaskStore(); val shared = Concurrency()
        val wa = FakeHand("hidden_wa", channel = "whatsapp", lock = "hidden_screen", shared = shared, commitDelayMs = 100)
        val uber = FakeHand("hidden_uber", channel = "uber", lock = "hidden_screen", shared = shared, commitDelayMs = 100)
        seed(store, "t1"); seed(store, "t2", p = Payload(mapOf("channel" to "uber", "recipient" to "uber", "content" to "ride")))
        val c = controller(store, wa, uber); c.approveSeen(store, "t1"); c.approveSeen(store, "t2")
        listOf(async { c.execute("t1") }, async { c.execute("t2") }).awaitAll()
        shared.max shouldBe 1
    }

    @Test fun `checks share the hand lock with commits`() = runTest {
        val store = InMemoryTaskStore(); val shared = Concurrency()
        val hand = FakeHand("h", lock = "hidden_screen", shared = shared, commitDelayMs = 100, checkDelayMs = 100)
        seed(store, "t1"); seed(store, "t2"); val c = controller(store, hand); c.approveSeen(store, "t1"); c.approveSeen(store, "t2")
        listOf(async { c.execute("t1") }, async { delay(150); c.execute("t2") }).awaitAll()
        shared.max shouldBe 1
    }

    @Test fun `cancelled caller still finishes the dispatch`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n", commitDelayMs = 100)
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        val job = launch { c.execute("t1") }
        advanceTimeBy(50); job.cancel()
        advanceTimeBy(1_000); runCurrent()
        store.load("t1")!!.state shouldBe DONE
        store.openAttempts() shouldBe emptyList()
    }

    @Test fun `recovery finishes a task left checking after its attempt was confirmed`() = runTest {
        val store = InMemoryTaskStore(); seed(store, state = CHECKING)
        store.updateAttempt(Attempt("a1", "t1", "n", "ap", payload.digest(), AttemptStatus.CONFIRMED, 0, 1, "seen"))
        controller(store, FakeHand("n")).recover()
        store.load("t1")!!.state shouldBe DONE
    }

    @Test fun `recovery fails a task left executing after its attempt failed`() = runTest {
        val store = InMemoryTaskStore(); seed(store, state = EXECUTING)
        store.updateAttempt(Attempt("a1", "t1", "n", "ap", payload.digest(), AttemptStatus.FAILED, 0, 1, "no chat"))
        controller(store, FakeHand("n")).recover()
        store.load("t1")!!.state shouldBe FAILED
    }

    private fun FakeHand.uncertainThen(vararg checks: HandResult) = apply {
        commitResults.apply { clear(); add(HandResult.Uncertain("dispatched")) }
        checkResults.apply { clear(); addAll(checks) }
    }

    @Test fun `a task is checking while reconcile runs and not after`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n").uncertainThen(HandResult.Uncertain("not yet"), HandResult.Ok("seen"))
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        c.execute("t1").state shouldBe OUTCOME_UNKNOWN
        c.isChecking("t1") shouldBe true
        advanceTimeBy(120_000); runCurrent()
        c.isChecking("t1") shouldBe false
    }

    @Test fun `giving up on checks reports a problem once`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n").uncertainThen(HandResult.Uncertain("not yet"))
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        val problems = mutableListOf<Task>(); c.onProblem = { problems += it }
        c.execute("t1")
        problems shouldBe emptyList()
        advanceTimeBy(120_000); runCurrent()
        problems.map { it.state } shouldBe listOf(OUTCOME_UNKNOWN)
    }

    @Test fun `a failed send reports a problem`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n")
        hand.commitResults.apply { clear(); add(HandResult.Failed("no chat", dispatched = false)) }
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        val problems = mutableListOf<Task>(); c.onProblem = { problems += it }
        c.execute("t1")
        problems.map { it.state } shouldBe listOf(FAILED)
    }

    @Test fun `a send confirmed by reconcile reports no problem`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n").uncertainThen(HandResult.Uncertain("not yet"), HandResult.Ok("seen"))
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        val problems = mutableListOf<Task>(); c.onProblem = { problems += it }
        c.execute("t1"); advanceTimeBy(120_000); runCurrent()
        problems shouldBe emptyList()
    }

    @Test fun `recovery reports a failure the app died before reporting, once`() = runTest {
        val store = InMemoryTaskStore()
        store.save(Task("t1", "message", FAILED, payload, emptyList(), 0, null))
        val problems = mutableListOf<Task>()
        controller(store).apply { onProblem = { problems += it } }.recover()
        controller(store).apply { onProblem = { problems += it } }.recover()
        problems.map { it.id } shouldBe listOf("t1")
    }

    @Test fun `a give-up is not reported again after a restart`() = runTest {
        val store = InMemoryTaskStore(); val hand = FakeHand("n").uncertainThen(HandResult.Uncertain("not yet"))
        seed(store); val c = controller(store, hand); c.approveSeen(store, "t1")
        val problems = mutableListOf<Task>(); c.onProblem = { problems += it }
        c.execute("t1"); advanceTimeBy(120_000); runCurrent()
        val restarted = controller(store, hand).apply { onProblem = { problems += it } }
        restarted.recover(); advanceTimeBy(120_000); runCurrent()
        problems.map { it.state } shouldBe listOf(OUTCOME_UNKNOWN)
    }
}
