// Scheduling adapted from OpenPhoneUse (https://github.com/joungminsung/OpenPhoneUse), app/.../routine/RoutineScheduler.kt,
// RoutineStore.kt and RoutineSystem.kt. Copyright (c) 2026 joungminsung. MIT License; see licenses/OpenPhoneUse-MIT.txt.
package com.misura.phone.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.misura.core.agent.Session
import com.misura.core.agent.TurnOrigin
import com.misura.core.routine.Decision
import com.misura.core.routine.NextRun
import com.misura.core.routine.Routine
import com.misura.core.routine.RoutineEngine
import com.misura.core.routine.RoutineEvent
import com.misura.phone.MisuraGraph
import com.misura.phone.work.WorkingService
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A routine as stored, with its scheduler state. [nextAt] = the occurrence the alarm waits for; [lastOccurrence] = the last one claimed. */
@Serializable data class Saved(val routine: Routine, val nextAt: Long? = null, val lastOccurrence: Long? = null)

/** Private preferences, one JSON list. An unreadable list reads as empty and blocks every write, so it is never overwritten (Codex P2). */
class RoutineStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("routines", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Saved.serializer())

    /** Null = stored but unreadable. */
    // One lock for every instance (settings, tool, scheduler): whole-list writes never interleave (Codex P2).
    private inline fun <T> locked(f: () -> T): T = synchronized(LOCK, f)
    private fun read(): List<Saved>? = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: if (prefs.contains(KEY)) null else emptyList()
    fun all(): List<Saved> = locked { read().orEmpty() }
    private fun writable(): List<Saved> = checkNotNull(read()) { "saved routines are unreadable; nothing is changed" }
    private fun write(list: List<Saved>) { check(prefs.edit().putString(KEY, json.encodeToString(ser, list)).commit()) { "routines not saved" } }
    /** Adds a new routine unless [max] are already stored (checked in the same lock as the write). */
    fun add(s: Saved, max: Int): Boolean = locked { writable().let { l -> if (l.size >= max) false else { write(l + s); true } } }
    fun put(s: Saved) = locked { writable().let { l -> write(l.filterNot { it.routine.id == s.routine.id } + s) } }
    fun remove(id: String): Boolean = locked { writable().let { l -> l.any { it.routine.id == id }.also { if (it) write(l.filterNot { s -> s.routine.id == id }) } } }
    fun update(id: String, f: (Saved) -> Saved): Saved? = locked {
        val l = writable()
        val s = l.firstOrNull { it.routine.id == id } ?: return@locked null
        f(s).also { n -> write(l.map { if (it.routine.id == id) n else it }) }
    }

    private companion object { const val KEY = "all"; val LOCK = Any() }
}

/**
 * Runs saved routines on time: one exact alarm for the soonest occurrence; when it rings, each due routine's occurrence
 * is claimed before its run starts (a repeated alarm never runs it twice), then the prompt goes to the agent as a new
 * session that may not control apps, write memory or make routines ([DENIED]). Results arrive like any other.
 */
object Routines {
    /** A run is unattended: nothing that takes over the screen, operates an app, writes memory or schedules more. */
    val DENIED = setOf("use_app", "open_app", "directions", "remember", "routine")
    private const val TAG = "MisuraRoutine"
    private const val ACTION_TICK = "com.misura.phone.ROUTINE_TICK"
    /** Covers the longest turn (300 s) plus its final call; released earlier when the run settles. */
    private const val WAKE_MS = 6 * 60_000L

    lateinit var store: RoutineStore; private set
    private lateinit var app: Context
    private val ticking = Mutex()
    private val scope get() = MisuraGraph.scope

    /** [start] opens a session for a prompt, with its origin and the tools denied (the agent's start); a test passes its own agent. */
    private lateinit var start: suspend (String, TurnOrigin, Set<String>) -> String
    fun init(ctx: Context, start: suspend (String, TurnOrigin, Set<String>) -> String, store: RoutineStore = RoutineStore(ctx)) { app = ctx.applicationContext; this.store = store; this.start = start }

    private fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())

    /**
     * After a save, removal, boot, update or clock change: run what is due, recompute the rest from now, set the one alarm.
     * In the same lock as the alarm's tick, so a save never moves a due, unclaimed occurrence away (Codex P2).
     */
    fun resync() { scope.launch { reconcile() } }
    suspend fun reconcile() = ticking.withLock { tickLocked(); schedule() }

    private fun schedule() {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, 7100, Intent(app, RoutineAlarmReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val next = store.all().filter { it.routine.enabled }.mapNotNull { it.nextAt }.minOrNull()
        if (next == null) { am.cancel(pi); return }
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) }
        catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) } // ponytail: inexact fallback if exact alarms were taken away
    }

    /** The alarm rang: run what is due, claim it, set the next alarm. */
    suspend fun tick() = ticking.withLock { tickLocked(); schedule() }

    /**
     * For each enabled routine: the latest occurrence within the late limit (not just the stored one, so a phone off over
     * a day still runs today's, Codex P2), run once if not claimed yet; then the next occurrence from now. Claimed before the run.
     */
    private suspend fun tickLocked() {
        val now = now(); val nowMs = now.toInstant().toEpochMilli()
        for (s in store.all()) {
            val r = s.routine
            val next = NextRun.compute(r, now)?.toInstant()?.toEpochMilli()
            val due = NextRun.compute(r, now.minusNanos((RoutineEngine.LATE_MAX_MS + 1) * 1_000_000))?.takeIf { !it.isAfter(now) }
            val at = due?.toInstant()?.toEpochMilli()
            // Not due, already claimed, or from before the routine existed (saved at 11:16 for 11:10: first run tomorrow).
            if (!r.enabled || at == null || s.lastOccurrence == at || at < r.createdAt) {
                if (s.nextAt != next) store.update(r.id) { it.copy(nextAt = next) }
                continue
            }
            val claimed = store.update(r.id) { it.copy(lastOccurrence = at, nextAt = next) } ?: continue
            when (RoutineEngine.shouldRun(claimed.routine, RoutineEvent.TimeDue(r.id), due)) {
                Decision.RUN -> run(claimed.routine, nowMs)
                else -> Log.i(TAG, "occurrence skipped by its conditions")
            }
        }
    }

    /** Starts the run: the service and a bounded wake lock keep it alive until the session settles, at the latest [WAKE_MS]. Returns the session id. */
    suspend fun run(r: Routine, nowMs: Long = System.currentTimeMillis(), manual: Boolean = false): String? {
        val token = Run()
        return try {
            WorkingService.routineStarted(app); token.counted = true
            token.lock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "misura:routine").apply { acquire(WAKE_MS) }
            main.postDelayed({ end(token) }, WAKE_MS) // the deadline: whatever happens, the demand ends
            // A routine's prompt is saved text (possibly model-written), never the owner's words of this run.
            val id = start(r.prompt, TurnOrigin.ROUTINE, DENIED)
            token.session = id
            // Atomic with settled(): either it sees the token, or we see that it already settled.
            val early = synchronized(handshake) { running[id] = token; recentlySettled.remove(id) }
            if (early) end(token)
            store.update(r.id) { s -> s.copy(routine = s.routine.copy(lastSession = id, cooldownFrom = if (manual) s.routine.cooldownFrom else nowMs)) }
            Log.i(TAG, "run started")
            id
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) { end(token); throw e }
            Log.w(TAG, "run failed to start: ${e.javaClass.simpleName}"); end(token); null
        }
    }

    /** One run's hold on the phone; [end] releases it exactly once. */
    private class Run { @Volatile var lock: PowerManager.WakeLock? = null; @Volatile var counted = false; @Volatile var session: String? = null; val ended = java.util.concurrent.atomic.AtomicBoolean(false) }
    private val running = java.util.concurrent.ConcurrentHashMap<String, Run>()
    private val recentlySettled: MutableSet<String> = java.util.Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 50
    }).let { java.util.Collections.synchronizedSet(it) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun end(t: Run) {
        if (!t.ended.compareAndSet(false, true)) return
        t.session?.let { running.remove(it) }
        runCatching { t.lock?.let { if (it.isHeld) it.release() } }
        if (t.counted) WorkingService.routineDone(app)
    }

    /** A session settled, failed, was dismissed or deleted: if it was a routine run, let the phone sleep again. */
    fun settled(s: Session) {
        val t = synchronized(handshake) { running[s.id].also { if (it == null) recentlySettled += s.id } } ?: return
        end(t)
    }
    private val handshake = Any()
}

/** The routine alarm: an explicit intent to this non-exported receiver. */
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = handle(ctx, resync = false)
}

/** Boot, app update, clock and alarm-permission changes: protected system broadcasts only. */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        handle(ctx, resync = true)
    }
}

private fun BroadcastReceiver.handle(ctx: Context, resync: Boolean) {
    val pending = goAsync()
    MisuraGraph.init(ctx)
    MisuraGraph.scope.launch {
        try {
            // A clock change: what is due now runs (within the late limit), then everything is recomputed from the new time.
            if (resync) Routines.reconcile() else Routines.tick()
        } catch (e: Exception) { Log.w("MisuraRoutine", "tick failed: ${e.javaClass.simpleName}") } finally { pending.finish() }
    }
}
