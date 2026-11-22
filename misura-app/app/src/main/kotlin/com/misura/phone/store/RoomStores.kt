package com.misura.phone.store

import androidx.room.withTransaction
import com.misura.core.approval.Approval
import com.misura.core.approval.covers
import com.misura.core.exec.Attempt
import com.misura.core.exec.OPEN_ATTEMPT
import com.misura.core.exec.PROBLEM
import com.misura.core.exec.TERMINAL
import com.misura.core.exec.TaskStore
import com.misura.core.exec.claimTransition
import com.misura.core.inbox.Expectation
import com.misura.core.inbox.InboxStore
import com.misura.core.inbox.Msg
import com.misura.core.task.Task
import com.misura.core.agent.Session
import com.misura.core.agent.Say
import com.misura.core.agent.upgraded
import com.misura.core.agent.SessionStore
import com.misura.core.agent.Turn
import com.misura.core.task.TaskState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomTaskStore(private val db: MisuraDb, private val cipher: StorageCipher = StorageCipher.shared) : TaskStore {
    private val dao = db.dao()
    private fun Task.row() = TaskRow(id, state.name, cipher.pack(this))
    private fun Attempt.row() = AttemptRow(id, taskId, status.name, cipher.pack(this))

    override suspend fun load(id: String) = withContext(Dispatchers.IO) { dao.task(id)?.let { cipher.unpack<Task>(it.json) } }
    override suspend fun save(t: Task) = db.withTransaction {
        cipher.requireReadable(dao.task(t.id)?.json)
        dao.upsertTask(t.row())
    }
    override suspend fun unfinished() = withContext(Dispatchers.IO) { dao.tasksNotIn(TERMINAL.map { it.name }).mapNotNull { cipher.unpack<Task>(it.json) } }
    override suspend fun unreportedProblems() = withContext(Dispatchers.IO) { dao.tasksIn(PROBLEM.map { it.name }).mapNotNull { cipher.unpack<Task>(it.json) }.filter { it.reportedAs != it.state } }
    override suspend fun saveApproval(a: Approval) = db.withTransaction {
        cipher.requireReadable(dao.approval(a.taskId)?.json)
        dao.upsertApproval(ApprovalRow(a.taskId, cipher.pack(a)))
    }
    override suspend fun approvalFor(taskId: String) = withContext(Dispatchers.IO) { dao.approval(taskId)?.let { cipher.unpack<Approval>(it.json) } }
    override suspend fun updateAttempt(a: Attempt) = db.withTransaction {
        cipher.requireReadable(dao.attempt(a.id)?.json)
        dao.upsertAttempt(a.row())
    }
    override suspend fun attemptsFor(taskId: String) = withContext(Dispatchers.IO) { dao.attemptsOf(taskId).mapNotNull { cipher.unpack<Attempt>(it.json) } }
    override suspend fun openAttempts() = withContext(Dispatchers.IO) { dao.attemptsIn(OPEN_ATTEMPT.map { it.name }).mapNotNull { cipher.unpack<Attempt>(it.json) } }

    override suspend fun claimAttempt(taskId: String, attempt: Attempt, now: Long): Attempt? = db.withTransaction {
        val t = load(taskId) ?: return@withTransaction null
        val a = approvalFor(taskId) ?: return@withTransaction null
        val p = t.payload ?: return@withTransaction null
        if (t.state != TaskState.NEEDS_APPROVAL || !a.covers(taskId, p, now)) return@withTransaction null
        saveApproval(a.copy(consumedByAttempt = attempt.id))
        updateAttempt(attempt)
        save(claimTransition(t, attempt, now))
        attempt
    }
}

class RoomInboxStore(private val db: MisuraDb, private val cipher: StorageCipher = StorageCipher.shared, private val clock: () -> Long = System::currentTimeMillis) : InboxStore {
    private val dao = db.dao()
    private fun ExpectationRow.e() = cipher.unpack<Expectation>(json)

    suspend fun prune() = db.withTransaction {
        val before = clock() - INBOX_RETENTION_MS
        dao.pruneAnswers(before)
        dao.pruneSeen(before)
        dao.pruneExpectations(before)
    }

    override suspend fun addExpectation(e: Expectation): Boolean = db.withTransaction {
        cipher.requireReadable(dao.expectation(e.requestId)?.json)
        prune()
        if (dao.openFor(e.app, inboxKey(e.conversationKey), e.notBefore).isNotEmpty()) return@withTransaction false
        dao.insertExpectation(ExpectationRow(e.requestId, e.app, inboxKey(e.conversationKey), e.until, false, cipher.pack(e)))
        true
    }
    override suspend fun openExpectations(now: Long) = withContext(Dispatchers.IO) { dao.openExpectations(now).mapNotNull { it.e() } }
    override suspend fun closeExpectation(requestId: String) = dao.close(requestId)
    override suspend fun markSeen(msgId: String): Boolean = db.withTransaction {
        prune()
        dao.insertSeen(SeenRow(inboxKey(msgId), clock())) != -1L
    }
    override suspend fun appendAnswer(requestId: String, m: Msg) = db.withTransaction {
        prune()
        if (m.sentAt >= clock() - INBOX_RETENTION_MS) dao.insertAnswer(AnswerRow(requestId = requestId, json = cipher.pack(m), sentAt = m.sentAt))
    }
    override suspend fun answer(requestId: String) = withContext(Dispatchers.IO) { dao.answers(requestId).mapNotNull { cipher.unpack<Msg>(it.json) } }
}

// Room dispatches SQL, not the Keystore/JSON work after it; keep both off the caller's UI thread.
class RoomSessionStore(private val db: MisuraDb, private val itemDir: java.io.File? = null, private val cipher: StorageCipher = StorageCipher.shared) : SessionStore {
    private val dao = db.dao()
    override suspend fun load(id: String) = withContext(Dispatchers.IO) { dao.session(id)?.let { cipher.unpack<Session>(it.json)?.upgraded() } }
    override suspend fun save(s: Session) = db.withTransaction {
        cipher.requireReadable(dao.session(s.id)?.json)
        dao.upsertSession(SessionRow(s.id, s.createdAt, cipher.pack(s)))
    }
    suspend fun sessionForTask(taskId: String): Session? = db.withTransaction {
        if (dao.task(taskId) == null) return@withTransaction null
        var offset = 0
        var page: List<SessionRow>
        var found: Session?
        do {
            page = dao.sessionPage(100, offset)
            found = page.mapNotNull { row -> runCatching { cipher.unpack<Session>(row.json)?.upgraded() }.getOrNull() }.firstOrNull { session ->
                session.steps.any { it.taskId == taskId } || taskId in (session.say as? Say.Approve)?.all.orEmpty()
            }
            offset += page.size
        } while (found == null && page.size == 100)
        found
    }
    override suspend fun turns(id: String) = withContext(Dispatchers.IO) { dao.turnsOf(id).mapNotNull { cipher.unpack<Turn>(it.json) } }
    override suspend fun append(id: String, t: Turn) = withContext(Dispatchers.IO) { dao.insertTurn(TurnRow(sessionId = id, json = cipher.pack(t))) }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val root = itemDir?.canonicalFile
        // ponytail: only photos saved in turns have session ownership; unreturned captures keep the existing seven-day cache ceiling.
        val items = turns(id).flatMap { com.misura.core.agent.itemFacts(listOf(it)).values }.distinctBy { it.id }
        // Each item's photo and its words file (title, price). Only files directly inside the recorder's folder may go, never an arbitrary path from a tool result.
        val files = items.mapNotNull { it.photo?.let { p -> java.io.File(p) } } + (if (itemDir == null) emptyList() else items.map { java.io.File(itemDir, "${it.id}.json") })
        files.map { it.canonicalFile }.distinct().forEach { file ->
            if (root != null && file.parentFile == root && file.exists()) check(file.delete()) { "Could not remove item file" }
        }
        db.withTransaction { dao.deleteTurns(id); dao.deleteSession(id) }
    }
    override suspend fun recent(limit: Int) = withContext(Dispatchers.IO) { dao.recentSessions(limit).mapNotNull { cipher.unpack<Session>(it.json)?.upgraded() } }
}

/** Inbox only: user notes and session/controller evidence have separate lifetimes. */
const val INBOX_RETENTION_MS = 14L * 24 * 60 * 60 * 1000

private inline fun <reified T> StorageCipher.pack(value: T) = encrypt(codec.encodeToString(value))
private inline fun <reified T> StorageCipher.unpack(json: String): T? = decrypt(json)?.let { codec.decodeFromString<T>(it) }
class StorageUnavailableException : IllegalStateException("Stored data is unavailable; existing record was preserved")

// Unreadable rows stop the current job; other failures keep normal uncaught handling.
internal val storageFailureHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
    if (error !is StorageUnavailableException) throw error
    android.util.Log.w("Misura", "Stored data unavailable; task stopped")
}

internal fun storageChildScope(parent: kotlinx.coroutines.CoroutineScope) = kotlinx.coroutines.CoroutineScope(
    parent.coroutineContext + kotlinx.coroutines.SupervisorJob(parent.coroutineContext[kotlinx.coroutines.Job]) + storageFailureHandler)

private fun StorageCipher.requireReadable(json: String?) {
    if (json != null && decrypt(json) == null) throw StorageUnavailableException()
}
