package com.misura.phone.shortcut

import android.content.Context
import com.misura.core.shortcut.Shortcut
import com.misura.core.shortcut.Target
import com.misura.core.shortcut.key
import com.misura.phone.store.StorageUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What [ShortcutStore.keep] did. [TargetChanged] is never produced by the store: the keeper (Task 2b) returns it when its validate callback finds the target no longer resolves as shown. */
enum class KeepResult { Kept, TargetChanged, Duplicate, Full, StaleReplacement }

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

    /** The kept shortcut whose DIRECT target equals [target] (same [key]); prompt-only shortcuts never match, whatever app they used. */
    fun keptFor(target: Target): Shortcut? = locked { read().orEmpty().firstOrNull { it.target?.key() == target.key() } }

    /**
     * Adds [s]. [replacing] is the id of the kept shortcut the user chose to replace; it is removed in the same write.
     * [KeepResult.StaleReplacement] if that id is no longer stored; [KeepResult.Duplicate] if another kept shortcut has the same direct
     * target (prompt-only never match); [KeepResult.Full] at [Shortcut.MAX] with nothing to replace. Nothing is written unless [KeepResult.Kept].
     */
    fun keep(s: Shortcut, replacing: String?): KeepResult = locked {
        val l = writable()
        if (replacing != null && l.none { it.id == replacing }) return@locked KeepResult.StaleReplacement
        val rest = l.filterNot { it.id == replacing }
        val key = s.target?.key()
        if (key != null && rest.any { it.target?.key() == key }) return@locked KeepResult.Duplicate
        if (replacing == null && l.size >= Shortcut.MAX) return@locked KeepResult.Full
        write(rest + s)
        KeepResult.Kept
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
