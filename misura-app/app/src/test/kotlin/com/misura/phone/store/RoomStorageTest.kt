package com.misura.phone.store

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.Say
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.core.agent.Turn
import com.misura.core.approval.Approval
import com.misura.core.approval.ApprovalSource
import com.misura.core.exec.Attempt
import com.misura.core.exec.AttemptStatus
import com.misura.core.inbox.Expectation
import com.misura.core.inbox.Msg
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomStorageTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = StorageCipher({ key }, { })
    private val now = 30 * INBOX_RETENTION_MS
    private fun message(id: String, time: Long) = Msg(id, "app.chat", "chat:Friend", "Friend", false, false, "private words", time)
    private suspend fun rejected(write: suspend () -> Unit) {
        try { write(); fail("Unreadable data was overwritten") }
        catch (e: StorageUnavailableException) { assertEquals("Stored data is unavailable; existing record was preserved", e.message) }
    }

    @Test fun encryptedStoreWorkDoesNotBlockTheCallingThread() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, MisuraDb::class.java).build()
        val keyThreads = java.util.concurrent.ConcurrentLinkedQueue<Thread>()
        val tracked = StorageCipher({ keyThreads.add(Thread.currentThread()); key }, {})
        val sessions = RoomSessionStore(db, cipher = tracked)
        val tasks = RoomTaskStore(db, tracked)
        val inbox = RoomInboxStore(db, tracked) { now }
        val task = Task("t", "message", TaskState.OUTCOME_UNKNOWN, Payload(mapOf("content" to "fixture")), emptyList(), now, null)
        val turn = Turn("user", buildJsonObject { put("text", "fixture") })
        val approval = Approval("a", task.id, task.payload!!.digest(), now, now + 1000, ApprovalSource.BUTTON)
        val attempt = Attempt("attempt", task.id, "hand", approval.id, approval.payloadDigest, AttemptStatus.CLAIMED, now, null, null)
        val expectation = Expectation(task.id, "r", "app.chat", "chat:Friend", "Friend", now, now + 1000)
        try {
            sessions.save(Session("s", SessionStatus.IDLE, 0, now))
            sessions.append("s", turn)
            tasks.save(task); tasks.saveApproval(approval); tasks.updateAttempt(attempt)
            inbox.addExpectation(expectation); inbox.appendAnswer("r", message("m", now))
            val operations: List<Pair<String, suspend () -> Unit>> = listOf(
                "session" to { assertNotNull(sessions.load("s")) },
                "recent" to { assertEquals(1, sessions.recent(10).size) },
                "turns" to { assertEquals(listOf(turn), sessions.turns("s")) },
                "append" to { sessions.append("s", turn) },
                "task" to { assertEquals(task, tasks.load("t")) },
                "unfinished" to { assertEquals(listOf(task), tasks.unfinished()) },
                "problems" to { assertEquals(listOf(task), tasks.unreportedProblems()) },
                "approval" to { assertEquals(approval, tasks.approvalFor("t")) },
                "attempts" to { assertEquals(listOf(attempt), tasks.attemptsFor("t")) },
                "open attempts" to { assertEquals(listOf(attempt), tasks.openAttempts()) },
                "expectations" to { assertEquals(listOf(expectation), inbox.openExpectations(now)) },
                "answer" to { assertEquals(listOf(message("m", now)), inbox.answer("r")) },
                "delete" to { sessions.delete("s") },
            )
            java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
                withContext(caller) {
                    val callingThread = Thread.currentThread()
                    for ((name, operation) in operations) {
                        keyThreads.clear()
                        operation()
                        assertTrue("$name must exercise encrypted data", keyThreads.isNotEmpty())
                        assertFalse("$name performed Keystore work on its caller", keyThreads.any { it === callingThread })
                    }
                }
            }
        } finally { db.close() }
    }

    @Test fun unavailableKeyRejectsEveryReplacementPreservesRawRowsAndRecovers() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, MisuraDb::class.java).build()
        var available = true
        val changing = StorageCipher({ check(available); key }, {})
        try {
            val tasks = RoomTaskStore(db, changing)
            val sessions = RoomSessionStore(db, cipher = changing)
            val inbox = RoomInboxStore(db, changing) { now }
            val t = Task("t", "message", TaskState.NEEDS_APPROVAL, Payload(mapOf("content" to "private words")), emptyList(), now, null)
            val a = Approval("approval", t.id, t.payload!!.digest(), now, now + 1000, ApprovalSource.BUTTON)
            val attempt = Attempt("attempt", t.id, "hand", a.id, a.payloadDigest, AttemptStatus.CLAIMED, now, null, null)
            val s = Session("s", SessionStatus.IDLE, 0, now)
            val e = Expectation(t.id, "request", "app.chat", "chat:Friend", "Friend", now, now + 1000)
            tasks.save(t); tasks.saveApproval(a); tasks.updateAttempt(attempt); sessions.save(s)
            assertTrue(inbox.addExpectation(e))
            inbox.closeExpectation(e.requestId)
            val taskRow = db.dao().task(t.id)
            val approvalRow = db.dao().approval(t.id)
            val attemptRow = db.dao().attempt(attempt.id)
            val sessionRow = db.dao().session(s.id)
            val expectationRow = db.dao().expectation(e.requestId)

            available = false
            assertNull(tasks.load(t.id))
            assertNull(tasks.approvalFor(t.id))
            assertTrue(tasks.attemptsFor(t.id).isEmpty())
            assertNull(sessions.load(s.id))
            rejected { tasks.save(t.copy(state = TaskState.CANCELLED)) }
            rejected { tasks.saveApproval(a.copy(id = "replacement")) }
            rejected { tasks.updateAttempt(attempt.copy(status = AttemptStatus.FAILED)) }
            rejected { sessions.save(s.copy(revision = 1)) }
            rejected { inbox.addExpectation(e.copy(until = now + 2000)) }
            assertEquals(taskRow, db.dao().task(t.id))
            assertEquals(approvalRow, db.dao().approval(t.id))
            assertEquals(attemptRow, db.dao().attempt(attempt.id))
            assertEquals(sessionRow, db.dao().session(s.id))
            assertEquals(expectationRow, db.dao().expectation(e.requestId))

            available = true
            assertEquals(t, tasks.load(t.id))
            assertEquals(a, tasks.approvalFor(t.id))
            assertEquals(listOf(attempt), tasks.attemptsFor(t.id))
            assertEquals(s, sessions.load(s.id))
            tasks.save(t.copy(state = TaskState.CANCELLED))
            tasks.saveApproval(a.copy(id = "replacement"))
            tasks.updateAttempt(attempt.copy(status = AttemptStatus.FAILED))
            sessions.save(s.copy(revision = 1))
            assertTrue(inbox.addExpectation(e.copy(until = now + 2000)))
            assertEquals(TaskState.CANCELLED, tasks.load(t.id)!!.state)
            assertEquals("replacement", tasks.approvalFor(t.id)!!.id)
            assertEquals(AttemptStatus.FAILED, tasks.attemptsFor(t.id).single().status)
            assertEquals(1, sessions.load(s.id)!!.revision)
            assertEquals(listOf(e.copy(until = now + 2000)), inbox.openExpectations(now))
        } finally { db.close() }
    }

    @Test fun unavailableKeyStillAllowsNewRowsAndPlaintextFallbackUpdates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, MisuraDb::class.java).build()
        val unavailable = StorageCipher({ error("test unavailable") }, {})
        try {
            val tasks = RoomTaskStore(db, unavailable)
            val sessions = RoomSessionStore(db, cipher = unavailable)
            val inbox = RoomInboxStore(db, unavailable) { now }
            val t = Task("new", "message", TaskState.PLANNING, null, emptyList(), now, null)
            val a = Approval("approval", t.id, "digest", now, now + 1000, ApprovalSource.BUTTON)
            val attempt = Attempt("attempt", t.id, "hand", a.id, a.payloadDigest, AttemptStatus.CLAIMED, now, null, null)
            val s = Session("new", SessionStatus.IDLE, 0, now)
            val e = Expectation(t.id, "request", "app.chat", "chat:Friend", "Friend", now, now + 1000)
            tasks.save(t); tasks.saveApproval(a); tasks.updateAttempt(attempt); sessions.save(s)
            assertTrue(inbox.addExpectation(e))
            tasks.save(t.copy(state = TaskState.CANCELLED))
            tasks.saveApproval(a.copy(id = "replacement"))
            tasks.updateAttempt(attempt.copy(status = AttemptStatus.FAILED))
            sessions.save(s.copy(revision = 1))
            inbox.closeExpectation(e.requestId)
            assertTrue(inbox.addExpectation(e.copy(until = now + 2000)))
            assertEquals(t.copy(state = TaskState.CANCELLED), tasks.load(t.id))
            assertEquals(a.copy(id = "replacement"), tasks.approvalFor(t.id))
            assertEquals(listOf(attempt.copy(status = AttemptStatus.FAILED)), tasks.attemptsFor(t.id))
            assertEquals(s.copy(revision = 1), sessions.load(s.id))
            assertEquals(listOf(e.copy(until = now + 2000)), inbox.openExpectations(now))
            for (json in listOf(db.dao().task(t.id)!!.json, db.dao().approval(t.id)!!.json, db.dao().attempt(attempt.id)!!.json,
                db.dao().session(s.id)!!.json, db.dao().expectation(e.requestId)!!.json)) assertFalse(json.startsWith(StorageCipher.PREFIX))
        } finally { db.close() }
    }

    @Test fun rejectedAttemptReplacementRollsBackApprovalClaim() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, MisuraDb::class.java).build()
        try {
            val tasks = RoomTaskStore(db, cipher)
            val t = Task("t", "message", TaskState.NEEDS_APPROVAL, Payload(mapOf("content" to "private words")), emptyList(), now, null)
            val a = Approval("approval", t.id, t.payload!!.digest(), now, now + 1000, ApprovalSource.BUTTON)
            val attempt = Attempt("existing", t.id, "hand", a.id, a.payloadDigest, AttemptStatus.CLAIMED, now, null, null)
            tasks.save(t); tasks.saveApproval(a)
            val damaged = AttemptRow(attempt.id, t.id, AttemptStatus.CLAIMED.name, StorageCipher.PREFIX + "broken")
            db.dao().upsertAttempt(damaged)
            val taskRow = db.dao().task(t.id)
            val approvalRow = db.dao().approval(t.id)
            rejected { tasks.claimAttempt(t.id, attempt, now) }
            assertEquals(taskRow, db.dao().task(t.id))
            assertEquals(approvalRow, db.dao().approval(t.id))
            assertEquals(damaged, db.dao().attempt(attempt.id))
            assertNull(tasks.approvalFor(t.id)!!.consumedByAttempt)
        } finally { db.close() }
    }

    @Test fun taskLookupScansPastTwoHundredSessionsSkipsUnreadableAndRequiresExistingTask() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, MisuraDb::class.java).build()
        try {
            val tasks = RoomTaskStore(db, cipher)
            val sessions = RoomSessionStore(db, cipher = cipher)
            val t = Task("task", "message", TaskState.DONE, null, emptyList(), now, null)
            tasks.save(t)
            val target = Session("000-target", SessionStatus.IDLE, 0, now,
                steps = listOf(Step("step", 1, "Sent", StepState.DONE, startedAt = now, taskId = t.id)))
            sessions.save(target)
            repeat(205) { n -> sessions.save(Session("filler-$n", SessionStatus.IDLE, 0, now)) }
            db.dao().upsertSession(SessionRow("z-unreadable", now, StorageCipher.PREFIX + "broken"))
            db.dao().upsertSession(SessionRow("z-malformed", now, "not JSON"))
            assertEquals(target, sessions.sessionForTask(t.id))
            val multi = Session("zz-newer", SessionStatus.IDLE, 0, now,
                say = Say.Approve("first", "Friend", "Testchat", also = listOf(Say.Also(t.id, "Guest"))))
            sessions.save(multi)
            assertEquals(multi, sessions.sessionForTask(t.id))
            assertNull(sessions.sessionForTask("first")) // A matching approval alone cannot revive a missing task.
            sessions.save(multi.copy(say = Say.Approve(t.id, "Friend", "Testchat")))
            assertEquals(multi.copy(say = Say.Approve(t.id, "Friend", "Testchat")), sessions.sessionForTask(t.id))
            tasks.save(t.copy(id = "unmatched"))
            assertNull(sessions.sessionForTask("unmatched"))
        } finally { db.close() }
    }

    @Test fun payloadsEncryptLegacyRowsRemainReadableAndRewriteEncrypted() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, MisuraDb::class.java).build()
        try {
            val sessions = RoomSessionStore(db, cipher = cipher)
            val session = Session("s", SessionStatus.IDLE, 0, now)
            val legacy = codec.encodeToString(Session.serializer(), session)
            db.dao().upsertSession(SessionRow("s", now, legacy))
            assertEquals(session, sessions.load("s"))
            sessions.save(session)
            assertTrue(db.dao().session("s")!!.json.startsWith(StorageCipher.PREFIX))
            assertEquals(session, sessions.load("s"))
            val turn = Turn("user", buildJsonObject { put("text", "private message") })
            sessions.append("s", turn)
            assertTrue(db.dao().turnsOf("s").single().json.startsWith(StorageCipher.PREFIX))
            assertEquals(listOf(turn), sessions.turns("s"))
            val task = Task("t", "message", TaskState.PLANNING, Payload(mapOf("content" to "private message")), emptyList(), now, null)
            val tasks = RoomTaskStore(db, cipher)
            tasks.save(task)
            assertTrue(db.dao().task("t")!!.json.startsWith(StorageCipher.PREFIX))
            assertEquals(task, tasks.load("t"))
            val inbox = RoomInboxStore(db, cipher) { now }
            val e = Expectation("t", "r", "app.chat", "chat:Friend", "Friend", now, now + 1000)
            assertTrue(inbox.addExpectation(e))
            assertFalse(inbox.addExpectation(e.copy(requestId = "duplicate")))
            assertEquals(listOf(e), inbox.openExpectations(now))
            assertEquals(inboxKey(e.conversationKey), db.dao().openFor(e.app, inboxKey(e.conversationKey), now).single().conv)
            inbox.appendAnswer("r", message("m", now))
            assertTrue(db.dao().answers("r").single().json.startsWith(StorageCipher.PREFIX))
            assertEquals(listOf(message("m", now)), inbox.answer("r"))
            val stored = db.dao().session("s")!!.json
            val unavailable = StorageCipher({ throw java.security.KeyStoreException("test unavailable") }, { })
            assertNull(RoomSessionStore(db, cipher = unavailable).load("s"))
            assertEquals(stored, db.dao().session("s")!!.json)
            val damaged = StorageCipher.PREFIX + "broken"
            db.dao().upsertSession(SessionRow("s", now, damaged))
            assertNull(sessions.load("s"))
            assertEquals(damaged, db.dao().session("s")!!.json)
        } finally { db.close() }
    }

    @Test fun startupPruneAndEveryInsertBoundInboxWithoutTouchingNotesOrSessions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, MisuraDb::class.java).build()
        try {
            var time = now
            val inbox = RoomInboxStore(db, cipher) { time }
            val cutoff = now - INBOX_RETENTION_MS
            val stale = message("old", cutoff - 1)
            val boundary = message("boundary", cutoff)
            for (m in listOf(stale, boundary)) db.dao().insertAnswer(AnswerRow(requestId = "r", json = codec.encodeToString(Msg.serializer(), m), sentAt = m.sentAt))
            db.dao().insertSeen(SeenRow(inboxKey("old"), cutoff - 1))
            db.dao().insertSeen(SeenRow(inboxKey("boundary"), cutoff))
            val sessions = RoomSessionStore(db, cipher = cipher)
            val session = Session("keep", SessionStatus.IDLE, 0, cutoff - 1)
            sessions.save(session)
            val prefs = ctx.getSharedPreferences("retention-test", Context.MODE_PRIVATE)
            prefs.putPrivateString("notes", "remember forever", cipher)
            inbox.prune() // Same entry point called at startup.
            assertEquals(listOf(boundary), inbox.answer("r"))
            assertFalse(inbox.markSeen("boundary"))
            assertTrue(inbox.markSeen("old"))
            inbox.appendAnswer("r", stale) // Late delivery cannot reintroduce expired text.
            assertEquals(listOf(boundary), inbox.answer("r"))
            time++
            inbox.markSeen("new") // Insert prunes the former boundary, too.
            assertTrue(inbox.answer("r").isEmpty())
            assertEquals(session, sessions.load("keep"))
            assertEquals("remember forever", prefs.privateString("notes", cipher))
        } finally { db.close() }
    }

    @Test fun versionTwoUpgradePreservesPlaintextAndBackfillsRetentionWithoutDroppingTables() = runBlocking {
        val name = "migration-${System.nanoTime()}.db"
        val old = message("old", now - INBOX_RETENTION_MS - 1)
        val fresh = message("fresh", now)
        val e = Expectation("t", "r", "app.chat", "chat:Friend", "Friend", now, now + 1000)
        val session = Session("s", SessionStatus.IDLE, 0, now)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Exact v2 table definitions from the checked-in Room schema; Room validates the migrated result.
                    listOf(
                        "tasks (`id` TEXT NOT NULL, `state` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
                        "approvals (`taskId` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`taskId`))",
                        "attempts (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `status` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
                        "expectations (`requestId` TEXT NOT NULL, `app` TEXT NOT NULL, `conv` TEXT NOT NULL, `until` INTEGER NOT NULL, `closed` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`requestId`))",
                        "seen_messages (`id` TEXT NOT NULL, PRIMARY KEY(`id`))",
                        "answers (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `requestId` TEXT NOT NULL, `json` TEXT NOT NULL)",
                        "sessions (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
                        "turns (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `json` TEXT NOT NULL)",
                    ).forEach { db.execSQL("CREATE TABLE $it") }
                    db.execSQL("INSERT INTO sessions VALUES (?, ?, ?)", arrayOf<Any?>(session.id, now, codec.encodeToString(Session.serializer(), session)))
                    for (m in listOf(old, fresh)) db.execSQL("INSERT INTO answers (requestId, json) VALUES (?, ?)", arrayOf<Any?>("r", codec.encodeToString(Msg.serializer(), m)))
                    db.execSQL("INSERT INTO seen_messages VALUES (?)", arrayOf<Any?>("fresh"))
                    db.execSQL("INSERT INTO expectations VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any?>("r", e.app, e.conversationKey, e.until, 0, codec.encodeToString(Expectation.serializer(), e)))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            helper.writableDatabase
            helper.close()
            val db = MisuraDb.open(ctx, name)
            try {
                val inbox = RoomInboxStore(db, cipher) { now }
                assertEquals(session, RoomSessionStore(db, cipher = cipher).load("s"))
                assertEquals(listOf(old, fresh), inbox.answer("r"))
                assertEquals(listOf(e), inbox.openExpectations(now))
                assertFalse(inbox.addExpectation(e.copy(requestId = "duplicate")))
                assertFalse(inbox.markSeen("fresh"))
                inbox.prune()
                assertEquals(listOf(fresh), inbox.answer("r"))
                inbox.appendAnswer("r", fresh)
                assertTrue(db.dao().answers("r").last().json.startsWith(StorageCipher.PREFIX))
            } finally { db.close() }
        } finally { helper.close(); ctx.deleteDatabase(name) }
    }
}
