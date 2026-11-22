package com.misura.phone.store

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.approval.APPROVAL_TTL_MS
import com.misura.core.approval.Approval
import com.misura.core.approval.ApprovalSource
import com.misura.core.exec.Attempt
import com.misura.core.exec.AttemptStatus
import com.misura.core.exec.Controller
import com.misura.core.exec.Hand
import com.misura.core.exec.HandResult
import com.misura.core.inbox.Expectation
import com.misura.core.task.Event
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StoreRecoveryTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test-${System.nanoTime()}.db"
    private fun open() = Room.databaseBuilder(ctx, MisuraDb::class.java, name).build()
    private val payload = Payload(mapOf("channel" to "testchat", "recipient" to "FriendB", "content" to "Grüße\nline 2"))
    private val task = Task("t1", "message", TaskState.NEEDS_APPROVAL, payload, listOf(Event(1, TaskState.PLANNING, TaskState.NEEDS_APPROVAL, "prepared", null)), 0, null)
    private fun approval(now: Long = System.currentTimeMillis()) = Approval("ap1", "t1", payload.digest(), now, now + APPROVAL_TTL_MS, ApprovalSource.BUTTON)
    private fun attempt(id: String, status: AttemptStatus = AttemptStatus.CLAIMED) = Attempt(id, "t1", "h", "ap1", payload.digest(), status, 1, null, null)

    @After fun tearDown() { ctx.deleteDatabase(name) }

    @Test fun fileBackedDbReopenRestoresEverything() = runBlocking<Unit> {
        open().also { db ->
            val s = RoomTaskStore(db); val inbox = RoomInboxStore(db)
            s.save(task); s.saveApproval(approval()); s.updateAttempt(attempt("a1", AttemptStatus.UNCERTAIN))
            inbox.addExpectation(Expectation("t1", "r1", "whatsapp", "wa:instinct", "Instinct", 0, Long.MAX_VALUE))
            db.close()
        }
        val db = open(); val s = RoomTaskStore(db); val inbox = RoomInboxStore(db)
        s.load("t1") shouldBe task
        s.approvalFor("t1")!!.payloadDigest shouldBe payload.digest()
        s.openAttempts().single().status shouldBe AttemptStatus.UNCERTAIN
        inbox.openExpectations(10).single().requestId shouldBe "r1"
        inbox.addExpectation(Expectation("t9", "r9", "whatsapp", "wa:instinct", null, 5, Long.MAX_VALUE)) shouldBe false
        db.close()
    }

    @Test fun claimAttemptIsAtomicUnderTwoCoroutines() = runBlocking<Unit> {
        val db = open(); val s = RoomTaskStore(db)
        s.save(task); s.saveApproval(approval())
        val now = System.currentTimeMillis()
        val results = (1..2).map { i -> async(Dispatchers.IO) { s.claimAttempt("t1", attempt("a$i"), now) } }.awaitAll()
        results.count { it != null } shouldBe 1
        s.load("t1")!!.state shouldBe TaskState.EXECUTING
        s.approvalFor("t1")!!.consumedByAttempt shouldBe results.single { it != null }!!.id
        db.close()
    }

    @Test fun bootAndAppStartRecoveringTogetherRunRecoveryOnce() = runBlocking<Unit> {
        val db = open(); val s = RoomTaskStore(db)
        s.save(task.copy(state = TaskState.EXECUTING)); s.updateAttempt(attempt("a1", AttemptStatus.DISPATCHING).copy(handId = "count"))
        val checks = AtomicInteger(); val commits = AtomicInteger()
        val hand = object : Hand {
            override val id = "count"; override val channel = "testchat"; override val lock: String? = null
            override suspend fun prepare(p: Payload) = HandResult.Prepared(p)
            override suspend fun commit(attemptId: String, p: Payload): HandResult { commits.incrementAndGet(); return HandResult.Ok("x") }
            override suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long): HandResult { checks.incrementAndGet(); return HandResult.Ok("seen") }
            override suspend fun cancel(attemptId: String) = HandResult.Unavailable("no")
        }
        val c = Controller(s, listOf(hand), System::currentTimeMillis, CoroutineScope(SupervisorJob() + Dispatchers.IO), reconcileDelays = listOf(50L))
        listOf(async { c.recover() }, async { c.recover() }).awaitAll()
        delay(1_000)
        checks.get() shouldBe 1
        commits.get() shouldBe 0
        s.load("t1")!!.state shouldBe TaskState.DONE
        db.close()
    }
}
