package com.misura.phone.shortcut

import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.shortcut.Shortcut
import com.misura.phone.store.StorageUnavailableException
import io.kotest.matchers.shouldBe
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Kept Home shortcuts: order, the 3-shortcut cap with an atomic replacement, and an unreadable store that blocks writes without touching the text. */
class ShortcutStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = ctx.getSharedPreferences("shortcuts", android.content.Context.MODE_PRIVATE)
    private lateinit var store: ShortcutStore

    private fun sc(id: String) = Shortcut(id, "Title $id", "prompt $id", null, createdAt = 1)

    @Before fun fresh() { prefs.edit().clear().commit(); store = ShortcutStore(ctx) }

    @Test fun keepAppendsInOrder() {
        val before = store.changes.value
        store.keep(sc("a"), null) shouldBe true
        store.keep(sc("b"), null) shouldBe true
        store.all().map { it.id } shouldBe listOf("a", "b")
        (store.changes.value > before) shouldBe true
    }

    @Test fun keepAtMaxNeedsReplacement() {
        listOf("a", "b", "c").forEach { store.keep(sc(it), null) shouldBe true }
        store.keep(sc("d"), null) shouldBe false
        store.all().map { it.id } shouldBe listOf("a", "b", "c")
        store.keep(sc("d"), "b") shouldBe true
        store.all().map { it.id } shouldBe listOf("a", "c", "d")
    }

    @Test fun keepWithStaleReplacementFails() {
        listOf("a", "b", "c").forEach { store.keep(sc(it), null) }
        store.keep(sc("d"), "gone") shouldBe false
        store.keep(sc("e"), "gone") shouldBe false
        store.all().map { it.id } shouldBe listOf("a", "b", "c")
        store.remove("c") shouldBe true
        store.keep(sc("d"), "gone") shouldBe false
        store.all().map { it.id } shouldBe listOf("a", "b")
    }

    @Test fun removeById() {
        store.keep(sc("a"), null); store.keep(sc("b"), null)
        store.remove("a") shouldBe true
        store.remove("a") shouldBe false
        store.all().map { it.id } shouldBe listOf("b")
        store.used("b", 42)
        store.all().single().lastUsedAt shouldBe 42L
    }

    @Test fun unreadableStoreBlocksWrites() {
        prefs.edit().putString("all", "{not json").commit()
        store.all() shouldBe emptyList()
        assertThrows(StorageUnavailableException::class.java) { store.keep(sc("a"), null) }
        assertThrows(StorageUnavailableException::class.java) { store.remove("a") }
        assertThrows(StorageUnavailableException::class.java) { store.used("a", 1) }
        prefs.getString("all", null) shouldBe "{not json"
    }

    /** Codex P2: a value of the wrong type under the key (not a string at all) is unreadable too, not a crash. */
    @Test fun wrongTypeStoreBlocksWrites() {
        prefs.edit().putInt("all", 7).commit()
        store.all() shouldBe emptyList()
        assertThrows(StorageUnavailableException::class.java) { store.keep(sc("a"), null) }
        assertThrows(StorageUnavailableException::class.java) { store.remove("a") }
        assertThrows(StorageUnavailableException::class.java) { store.used("a", 1) }
        prefs.all["all"] shouldBe 7
    }
}
