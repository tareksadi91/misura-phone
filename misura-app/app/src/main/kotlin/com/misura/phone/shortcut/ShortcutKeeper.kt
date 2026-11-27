package com.misura.phone.shortcut

import android.util.Log
import com.misura.core.shortcut.Shortcut
import com.misura.core.shortcut.Target

/** The launcher's pinned shortcuts (Task 3 implements it over LauncherApps). */
interface ShortcutPins {
    /** Every pinned id of [pkg] for this app's user, disabled ones included; null = the query failed (never read that as empty). */
    fun rawPinned(pkg: String): Set<String>?
    /** Makes [ids] the whole pinned set of [pkg] (Android's pinShortcuts: the list replaces the set). False = it failed. */
    fun pin(pkg: String, ids: Set<String>): Boolean
}

/**
 * The one owner of every kept-shortcut mutation: Home swipe, long-press, the app list's menu and the `shortcut` tool all end here.
 * Synchronous (the tap gate runs its action and reads the outcome right after, RoutineGate / Agent.choose): callers are off the main
 * thread, UI callers inside `withContext(Dispatchers.IO)`. One process-wide lock covers the store write and the pin changes, so two
 * entry points never interleave a read-modify-write of the pinned set. [pins] null = no pin work (store only).
 *
 * Order: store write first, then pin the new target, then unpin the replaced or removed one. A pin failure is logged and never
 * reverses a save; a failed pinned-set query skips that pin change instead of reading as an empty set (which would unpin everything else).
 * Only [Target.AppShortcut] targets are pinned, and an id another kept shortcut still uses is never unpinned.
 */
class ShortcutKeeper(private val store: ShortcutStore, private val pins: ShortcutPins?) {
    /**
     * [validate] runs after the lock is taken, immediately before the store write: it re-resolves the target and the labels the user was
     * shown. False = [KeepResult.TargetChanged], nothing written. Throws [com.misura.phone.store.StorageUnavailableException] like the store.
     */
    fun keep(s: Shortcut, replacingId: String?, validate: () -> Boolean): KeepResult = synchronized(LOCK) {
        if (!validate()) return@synchronized KeepResult.TargetChanged
        val replaced = replacingId?.let { id -> store.all().firstOrNull { it.id == id } }
        val r = store.keep(s, replacingId)
        if (r != KeepResult.Kept) return@synchronized r
        (s.target as? Target.AppShortcut)?.let { change(it.pkg) { ids -> ids + it.id } }
        (replaced?.target as? Target.AppShortcut)?.let { unpinIfUnused(it) }
        KeepResult.Kept
    }

    /** False when [id] was already gone. */
    fun remove(id: String): Boolean = synchronized(LOCK) {
        val gone = store.all().firstOrNull { it.id == id }
        val removed = store.remove(id)
        if (removed) (gone?.target as? Target.AppShortcut)?.let { unpinIfUnused(it) }
        removed
    }

    private fun unpinIfUnused(t: Target.AppShortcut) {
        if (store.all().none { it.target == t }) change(t.pkg) { ids -> ids - t.id }
    }

    private fun change(pkg: String, f: (Set<String>) -> Set<String>) {
        val p = pins ?: return
        try {
            val raw = p.rawPinned(pkg)
            if (raw == null) { Log.w(TAG, "pinned query failed; pin change skipped"); return }
            if (!p.pin(pkg, f(raw))) Log.w(TAG, "pin change failed")
        } catch (e: Exception) { Log.w(TAG, "pin change failed: ${e.javaClass.simpleName}") }
    }

    private companion object { const val TAG = "ShortcutKeeper"; val LOCK = Any() }
}
