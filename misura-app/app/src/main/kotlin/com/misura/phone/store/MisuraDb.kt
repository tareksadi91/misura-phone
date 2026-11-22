package com.misura.phone.store

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** Rows keep the full object as JSON plus the few columns queries need. */
@Entity(tableName = "tasks") data class TaskRow(@PrimaryKey val id: String, val state: String, val json: String)
@Entity(tableName = "approvals") data class ApprovalRow(@PrimaryKey val taskId: String, val json: String)
@Entity(tableName = "attempts") data class AttemptRow(@PrimaryKey val id: String, val taskId: String, val status: String, val json: String)
@Entity(tableName = "expectations") data class ExpectationRow(@PrimaryKey val requestId: String, val app: String, val conv: String, val until: Long, val closed: Boolean, val json: String)
@Entity(tableName = "seen_messages") data class SeenRow(@PrimaryKey val id: String, @ColumnInfo(defaultValue = "0") val seenAt: Long = 0)
@Entity(tableName = "sessions") data class SessionRow(@PrimaryKey val id: String, val createdAt: Long, val json: String)
@Entity(tableName = "turns") data class TurnRow(@PrimaryKey(autoGenerate = true) val rowId: Long = 0, val sessionId: String, val json: String)
@Entity(tableName = "answers") data class AnswerRow(@PrimaryKey(autoGenerate = true) val rowId: Long = 0, val requestId: String, val json: String, @ColumnInfo(defaultValue = "0") val sentAt: Long = 0)

@Dao
interface MisuraDao {
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): TaskRow?
    @Upsert suspend fun upsertTask(r: TaskRow)
    @Query("SELECT * FROM tasks WHERE state IN (:states)") suspend fun tasksIn(states: List<String>): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE state NOT IN (:terminal)") suspend fun tasksNotIn(terminal: List<String>): List<TaskRow>
    @Query("SELECT * FROM approvals WHERE taskId = :taskId") suspend fun approval(taskId: String): ApprovalRow?
    @Upsert suspend fun upsertApproval(r: ApprovalRow)
    @Query("SELECT * FROM attempts WHERE id = :id") suspend fun attempt(id: String): AttemptRow?
    @Upsert suspend fun upsertAttempt(r: AttemptRow)
    @Query("SELECT * FROM attempts WHERE taskId = :taskId") suspend fun attemptsOf(taskId: String): List<AttemptRow>
    @Query("SELECT * FROM attempts WHERE status IN (:statuses)") suspend fun attemptsIn(statuses: List<String>): List<AttemptRow>
    @Query("SELECT * FROM expectations WHERE closed = 0 AND app = :app AND conv = :conv AND until >= :from") suspend fun openFor(app: String, conv: String, from: Long): List<ExpectationRow>
    @Query("SELECT * FROM expectations WHERE closed = 0 AND until >= :now") suspend fun openExpectations(now: Long): List<ExpectationRow>
    @Query("SELECT * FROM expectations WHERE requestId = :requestId") suspend fun expectation(requestId: String): ExpectationRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertExpectation(r: ExpectationRow) // re-asking reopens the same request
    @Query("UPDATE expectations SET closed = 1 WHERE requestId = :requestId") suspend fun close(requestId: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSeen(r: SeenRow): Long
    @Insert suspend fun insertAnswer(r: AnswerRow)
    @Query("DELETE FROM answers WHERE sentAt < :before") suspend fun pruneAnswers(before: Long)
    @Query("DELETE FROM seen_messages WHERE seenAt < :before") suspend fun pruneSeen(before: Long)
    @Query("DELETE FROM expectations WHERE until < :before") suspend fun pruneExpectations(before: Long)
    @Query("SELECT * FROM answers WHERE requestId = :requestId ORDER BY rowId") suspend fun answers(requestId: String): List<AnswerRow>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): SessionRow?
    @Upsert suspend fun upsertSession(r: SessionRow)
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC LIMIT :limit") suspend fun recentSessions(limit: Int): List<SessionRow>
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset") suspend fun sessionPage(limit: Int, offset: Int): List<SessionRow>
    @Query("DELETE FROM turns WHERE sessionId = :id") suspend fun deleteTurns(id: String)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: String)
    @Insert suspend fun insertTurn(r: TurnRow)
    @Query("SELECT * FROM turns WHERE sessionId = :sessionId ORDER BY rowId") suspend fun turnsOf(sessionId: String): List<TurnRow>
}

@Database(entities = [TaskRow::class, ApprovalRow::class, AttemptRow::class, ExpectationRow::class, SeenRow::class, AnswerRow::class, SessionRow::class, TurnRow::class], version = 3, exportSchema = true)
abstract class MisuraDb : RoomDatabase() {
    abstract fun dao(): MisuraDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE seen_messages ADD COLUMN seenAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE answers ADD COLUMN sentAt INTEGER NOT NULL DEFAULT 0")
                val now = System.currentTimeMillis()
                // Legacy seen ids carry no reliable timestamp; give them one retention window from upgrade.
                db.execSQL("UPDATE seen_messages SET seenAt = ?", arrayOf(now))
                db.query("SELECT id FROM seen_messages").use { rows ->
                    val ids = mutableListOf<String>()
                    while (rows.moveToNext()) ids += rows.getString(0)
                    ids.forEach { db.execSQL("UPDATE seen_messages SET id = ? WHERE id = ?", arrayOf(inboxKey(it), it)) }
                }
                db.query("SELECT requestId, conv FROM expectations").use { rows ->
                    while (rows.moveToNext()) db.execSQL("UPDATE expectations SET conv = ? WHERE requestId = ?", arrayOf(inboxKey(rows.getString(1)), rows.getString(0)))
                }
                db.query("SELECT rowId, json FROM answers").use { rows ->
                    while (rows.moveToNext()) {
                        val sentAt = runCatching { (Json.parseToJsonElement(rows.getString(1)).jsonObject["sentAt"] as? JsonPrimitive)?.longOrNull }.getOrNull() ?: now
                        db.execSQL("UPDATE answers SET sentAt = ? WHERE rowId = ?", arrayOf(sentAt, rows.getLong(0)))
                    }
                }
            }
        }
        // ponytail: v1 never left the emulator, so it's rebuilt instead of migrated. From v2 on, schemas are exported and migrations get tests.
        fun open(ctx: Context, name: String = "misura.db"): MisuraDb =
            Room.databaseBuilder(ctx, MisuraDb::class.java, name).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigrationFrom(true, 1).build()
    }
}

internal val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Lookup columns need equality, not the sender/title embedded in legacy notification ids. */
internal fun inboxKey(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
