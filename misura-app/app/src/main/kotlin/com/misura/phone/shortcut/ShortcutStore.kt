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
    /** Null = stored but unreadable. */
    private fun read(): List<Shortcut>? = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: if (prefs.contains(KEY)) null else emptyList()
    private fun writable(): List<Shortcut> = read() ?: throw StorageUnavailableException()
    private fun write(list: List<Shortcut>) {
        check(prefs.edit().putString(KEY, json.encodeToString(ser, list)).commit()) { "shortcuts not saved" }
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
