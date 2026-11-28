package com.misura.phone.shortcut

import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.shortcut.Shortcut
import com.misura.core.shortcut.Target
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

    private fun direct(id: String, t: Target) = Shortcut(id, "Title $id", null, null, target = t, createdAt = 1)

    @Before fun fresh() { prefs.edit().clear().commit(); store = ShortcutStore(ctx) }

    @Test fun keepAppendsInOrder() {
        val before = store.changes.value
        store.keep(sc("a"), null) shouldBe KeepResult.Kept
        store.keep(sc("b"), null) shouldBe KeepResult.Kept
        store.all().map { it.id } shouldBe listOf("a", "b")
        (store.changes.value > before) shouldBe true
    }

    @Test fun keepAtMaxNeedsReplacement() {
        listOf("a", "b", "c").forEach { store.keep(sc(it), null) shouldBe KeepResult.Kept }
        store.keep(sc("d"), null) shouldBe KeepResult.Full
        store.all().map { it.id } shouldBe listOf("a", "b", "c")
        store.keep(sc("d"), "b") shouldBe KeepResult.Kept
        store.all().map { it.id } shouldBe listOf("a", "c", "d")
    }

    @Test fun keepWithStaleReplacementFails() {
        listOf("a", "b", "c").forEach { store.keep(sc(it), null) }
        store.keep(sc("d"), "gone") shouldBe KeepResult.StaleReplacement
        store.keep(sc("e"), "gone") shouldBe KeepResult.StaleReplacement
        store.all().map { it.id } shouldBe listOf("a", "b", "c")
        store.remove("c") shouldBe true
        store.keep(sc("d"), "gone") shouldBe KeepResult.StaleReplacement
        store.all().map { it.id } shouldBe listOf("a", "b")
    }

    @Test fun legacyRecordStillReadsAsAgentShortcut() {
        prefs.edit().putString("all", """[{"id":"old","title":"Club","prompt":"check me in","app":"com.example.club","createdAt":5}]""").commit()
        val s = store.all().single()
        s.target shouldBe null
        s.prompt shouldBe "check me in"
        s.app shouldBe "com.example.club"
        store.keep(sc("n"), null) shouldBe KeepResult.Kept
        store.all().map { it.id } shouldBe listOf("old", "n")
    }

    @Test fun duplicateTargetRefused() {
        val t = Target.Settings(com.misura.core.shortcut.SettingsPage.WIFI)
        store.keep(direct("a", t), null) shouldBe KeepResult.Kept
        store.keep(direct("b", Target.Settings(com.misura.core.shortcut.SettingsPage.WIFI)), null) shouldBe KeepResult.Duplicate
        store.all().map { it.id } shouldBe listOf("a")
        // Also refused at the cap and when a replacement is chosen: it would still be a second copy.
        store.keep(sc("x"), null); store.keep(sc("y"), null)
        store.keep(direct("c", t), null) shouldBe KeepResult.Duplicate
        store.keep(direct("c", t), "x") shouldBe KeepResult.Duplicate
        store.all().map { it.id } shouldBe listOf("a", "x", "y")
        // Replacing the very shortcut that holds the target is not a duplicate.
        store.keep(direct("d", t), "a") shouldBe KeepResult.Kept
        store.all().map { it.id } shouldBe listOf("x", "y", "d")
    }

    @Test fun promptOnlySameAppIsNotKeptFor() {
        store.keep(Shortcut("p", "Club", "check me in", "com.example.club", createdAt = 1), null)
        store.keptFor(Target.App("com.example.club")) shouldBe null
        // ...and a prompt-only shortcut never blocks a direct one for the same app.
        store.keep(direct("d", Target.App("com.example.club")), null) shouldBe KeepResult.Kept
        store.keptFor(Target.App("com.example.club"))!!.id shouldBe "d"
        store.keptFor(Target.AppShortcut("com.example.club", "x")) shouldBe null
    }

    @Test fun replaceChosenIdOnly() {
        listOf("a", "b", "c").forEach { store.keep(sc(it), null) }
        store.keep(sc("d"), "b") shouldBe KeepResult.Kept
        store.all().map { it.id } shouldBe listOf("a", "c", "d")
        store.keep(sc("e"), "b") shouldBe KeepResult.StaleReplacement // b is gone: never evicts another
        store.all().map { it.id } shouldBe listOf("a", "c", "d")
        store.keep(sc("e"), null) shouldBe KeepResult.Full
        store.all().map { it.id } shouldBe listOf("a", "c", "d")
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
