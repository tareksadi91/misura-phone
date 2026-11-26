package com.misura.phone.shortcut

import android.content.Context
import com.misura.core.shortcut.Shortcut
import com.misura.phone.store.StorageUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Kept Home shortcuts, one JSON list in private preferences. Synchronous like RoutineStore: the tap gate runs its action
 * and reads the outcome right after. An unreadable list reads as empty and every write throws [StorageUnavailableException],
 * so the stored text is never overwritten.
 */
class ShortcutStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Shortcut.serializer())

    /** Bumps after every successful write, so Home can reload. */
    val changes: StateFlow<Int> get() = CHANGES

    // One lock for every instance: whole-list read-modify-write never interleaves.
    private inline fun <T> locked(f: () -> T): T = synchronized(LOCK, f)
    /** Null = stored but unreadable: broken JSON, or a value of another type under the key (getString throws then, Codex P2). */
    private fun read(): List<Shortcut>? = if (!prefs.contains(KEY)) emptyList()
        else runCatching { json.decodeFromString(ser, prefs.getString(KEY, null)!!) }.getOrNull()
    private fun writable(): List<Shortcut> = read() ?: throw StorageUnavailableException()
    private fun write(list: List<Shortcut>) {
        if (!prefs.edit().putString(KEY, json.encodeToString(ser, list)).commit()) throw StorageUnavailableException()
        CHANGES.value += 1
    }

    fun all(): List<Shortcut> = locked { read().orEmpty() }

    /** Adds [s]. At [Shortcut.MAX] it needs [replacing], an id still stored, which it removes. False if the cap is hit without one, or [replacing] is no longer stored. */
    fun keep(s: Shortcut, replacing: String?): Boolean = locked {
        val l = writable()
        if (replacing != null && l.none { it.id == replacing }) return@locked false
        if (l.size >= Shortcut.MAX && replacing == null) return@locked false
        write(l.filterNot { it.id == replacing } + s)
        true
    }

    fun remove(id: String): Boolean = locked {
        val l = writable()
        l.any { it.id == id }.also { if (it) write(l.filterNot { s -> s.id == id }) }
    }

    fun used(id: String, at: Long) = locked {
        val l = writable()
        if (l.any { it.id == id }) write(l.map { if (it.id == id) it.copy(lastUsedAt = at) else it })
    }

    private companion object { const val KEY = "all"; val LOCK = Any(); val CHANGES = MutableStateFlow(0) }
}
