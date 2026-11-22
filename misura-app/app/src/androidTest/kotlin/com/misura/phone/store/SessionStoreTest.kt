package com.misura.phone.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Places
import com.misura.core.agent.Say
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.Turn
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "sessions-test.db"
    private fun open() = MisuraDb.open(ctx, name)

    @Before fun clean() { ctx.deleteDatabase(name) }

    @Test fun sessionAndTurnsSurviveReopen() = runBlocking<Unit> {
        val session = Session("s1", SessionStatus.IDLE, 2, 100, places = Places(listOf("B"), "B"), say = Say.Answer("Prado is quieter"), dismissedAt = 123)
        val turns = (1..3).map { i -> Turn(if (i % 2 == 1) "user" else "model", buildJsonObject { put("role", "user"); put("n", JsonPrimitive(i)) }) }
        open().let { db ->
            val s = RoomSessionStore(db)
            s.save(session)
            turns.forEach { s.append("s1", it) }
            db.close()
        }
        open().let { db ->
            val s = RoomSessionStore(db)
            s.load("s1") shouldBe session
            s.turns("s1") shouldBe turns
            db.close()
        }
    }

    @Test fun deleteRemovesTurnsAndOnlyOwnedItemPhotos() = runBlocking<Unit> {
        val db = open()
        val root = java.io.File(ctx.filesDir, "items").apply { mkdirs() }
        val owned = java.io.File(root, "session-store-test.jpg").apply { writeText("photo") }
        val outside = java.io.File(ctx.filesDir, "session-store-outside.jpg").apply { writeText("keep") }
        val words = java.io.File(root, "i0.json").apply { writeText("{}") } // the item's title and price file goes with it
        try {
            val s = RoomSessionStore(db, root)
            s.save(Session("s", SessionStatus.IDLE, 0, 1))
            val result = buildJsonObject { put("items", kotlinx.serialization.json.buildJsonArray {
                listOf(owned, outside).forEachIndexed { i, f -> add(buildJsonObject { put("id", "i$i"); put("title", "Item"); put("photo", f.path) }) }
            }) }
            s.append("s", Turn("user", buildJsonObject { put("parts", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("functionResponse", buildJsonObject { put("response", result) }) })
            }) }))
            s.delete("s")
            s.load("s") shouldBe null
            s.turns("s") shouldBe emptyList()
            owned.exists() shouldBe false
            words.exists() shouldBe false
            outside.exists() shouldBe true
        } finally { owned.delete(); words.delete(); outside.delete(); db.close() }
    }

    @Test fun recentReturnsNewestFirst() = runBlocking<Unit> {
        val db = open(); val s = RoomSessionStore(db)
        s.save(Session("old", SessionStatus.IDLE, 0, 1)); s.save(Session("new", SessionStatus.IDLE, 0, 2))
        s.recent(10).map { it.id } shouldBe listOf("new", "old")
        db.close()
    }
}
