package com.misura.phone.hidden

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class DisplaySession(val sessionId: String, val displayId: Int, val createdAt: Long)

/** Content-free timing lines (package, counts, milliseconds) for diagnostics builds; set by the app graph. */
@Volatile var hiddenTiming: (String) -> Unit = {}
private fun msSince(t: Long) = (System.nanoTime() - t) / 1_000_000

/** Serializes whole hand operations; individual shell calls still use their own I/O lock. */
internal val hiddenOperation = Mutex()

@Volatile private var admittedPackage: String? = null

/** Both hands hold hiddenOperation here, from before display setup through final cleanup. */
internal suspend fun <T> withHiddenPackage(pkg: String?, block: suspend () -> T): T {
    admittedPackage = pkg
    return try { block() } finally { admittedPackage = null }
}

interface HiddenScreen {
    suspend fun open(widthPx: Int, heightPx: Int, dpi: Int): DisplaySession
    suspend fun launch(s: DisplaySession, pkg: String, activity: String? = null, deepLink: String? = null): Boolean
    suspend fun taskDisplayOf(pkg: String): Int?
    suspend fun close(s: DisplaySession)
}

/** Hidden display through the Shizuku shell service. Sessions die with the service process. */
open class ShizukuHiddenScreen(private val ctx: Context) : HiddenScreen {
    // Process-wide: every instance shares one service, so they share its sessions and its lock.
    init { com.misura.phone.access.AppAccess.init(ctx) }

    private companion object {
        class ActiveDisplay(val displayId: Int, var pkg: String? = null)
        val sessions = mutableMapOf<String, ActiveDisplay>()
        val lock = Mutex()
        init { ShizukuBridge.onDeath = { synchronized(sessions) { sessions.clear() } } }
    }

    override suspend fun open(widthPx: Int, heightPx: Int, dpi: Int): DisplaySession = io {
        val id = ShizukuBridge.bind(ctx).createDisplay("misura-hidden", widthPx, heightPx, dpi)
        check(id != 0) { "service returned the main display" }
        DisplaySession(UUID.randomUUID().toString(), id, System.currentTimeMillis())
            .also { synchronized(sessions) { sessions[it.sessionId] = ActiveDisplay(it.displayId) } }
    }

    override suspend fun launch(s: DisplaySession, pkg: String, activity: String?, deepLink: String?): Boolean = io {
        // Every hidden launch (first open, relaunch, scripted chat) passes here; a protected app is never started.
        if (!com.misura.phone.access.AppAccess.allows(pkg)) return@io false
        val svc = live(s)
        synchronized(sessions) { sessions[s.sessionId]?.pkg = pkg }
        val target = when {
            deepLink != null -> arrayOf("-a", "android.intent.action.VIEW", "-d", deepLink, "-p", pkg)
            activity != null -> arrayOf("-n", "$pkg/$activity")
            else -> arrayOf("-n", launcherOf(svc, pkg) ?: return@io false)
        }
        val t0 = System.nanoTime()
        val out = svc.exec(arrayOf("am", "start", "-W", "--display", s.displayId.toString()) + target)
        val am = msSince(t0)
        if (out.contains("Error")) return@io false
        // Guard: the app must be on our hidden display, never on the main screen.
        var where: Int? = null; var polls = 0; val t1 = System.nanoTime()
        // ponytail: always 20 queries (~2 s). Stopping at the first match let the hand read the app before its content was ready
        // (14 fixture runs failed); this time is the launch settle until a readiness check is designed and phone-verified.
        repeat(20) { polls++; where = displayOf(svc, pkg); if (where == null) delay(100) }
        hiddenTiming("launch $pkg am=${am}ms polls=$polls poll=${msSince(t1)}ms total=${msSince(t0)}ms ok=${where == s.displayId}")
        if (where != s.displayId) { closeLocked(s); return@io false }
        true
    }

    /** Home can launch unrelated apps immediately, including while another package's display is closing. */
    internal fun ownsPackage(pkg: String) = admittedPackage == pkg || synchronized(sessions) { sessions.values.any { it.pkg == pkg } }

    override suspend fun taskDisplayOf(pkg: String): Int? = io { displayOf(ShizukuBridge.bind(ctx), pkg) }

    /** A key press on the hidden display only (4 = back, 66 = enter). */
    /** False = not pressed: while a permission question is up only Back (4) goes through ([ScreenReader.permissionQuestion]). */
    suspend fun key(s: DisplaySession, code: Int): Boolean {
        if (code != 4 && ScreenReader.permissionQuestion(s.displayId)) return false
        return io { live(s).exec(arrayOf("input", "-d", s.displayId.toString(), "keyevent", code.toString())); true }
    }

    /** JPEG of [bounds] (left, top, right, bottom on the hidden display, like UiNode.bounds), at most [maxWidth] wide; null when there is no frame yet or the region is empty. */
    suspend fun grab(s: DisplaySession, bounds: IntArray, maxWidth: Int = 720): ByteArray? = io { live(s).grab(s.displayId, bounds[0], bounds[1], bounds[2], bounds[3], maxWidth) }

    /**
     * Lets [pkg] post notifications, so its first-run "Allow notifications?" prompt never shows: on the hidden screen
     * nobody can answer it, and a system permission prompt can't be read by an accessibility service.
     */
    suspend fun allowNotifications(pkg: String) = io { if (!com.misura.phone.access.AppAccess.allows(pkg)) return@io; ShizukuBridge.bind(ctx).exec(arrayOf("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS")); Unit }

    /** Packages with a launcher icon: what [launch] can open. */
    suspend fun launchable(): List<String> = io {
        ShizukuBridge.bind(ctx).exec(arrayOf("cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"))
            .lines().map { it.trim() }.filter { '/' in it && ' ' !in it }.map { it.substringBefore('/') }.distinct()
    }

    /**
     * Moves [pkg]'s task from the hidden display to the main screen, the same task and activity, so its page is kept. Releasing
     * the hidden display afterwards then no longer takes the app with it. True when the task is on display 0 afterwards.
     */
    suspend fun moveToMain(s: DisplaySession, pkg: String): Boolean = io {
        if (!com.misura.phone.access.AppAccess.allows(pkg)) return@io false
        val svc = live(s)
        val root = parseRootTask(svc.exec(arrayOf("cmd", "activity", "stack", "list")), pkg, s.displayId) ?: return@io false
        val t0 = System.nanoTime()
        svc.exec(arrayOf("cmd", "activity", "display", "move-stack", root.toString(), "0"))
        var where: Int? = null; var polls = 0; val t1 = System.nanoTime()
        pollDisplay({ displayOf(svc, pkg) }, 0).let { where = it.first; polls = it.second }
        hiddenTiming("move $pkg polls=$polls poll=${msSince(t1)}ms total=${msSince(t0)}ms ok=${where == 0}")
        where == 0
    }

    override suspend fun close(s: DisplaySession) = io { closeLocked(s) }

    private fun displayOf(svc: IShellService, pkg: String) = parseTaskDisplay(svc.exec(arrayOf("dumpsys", "activity", "activities")), pkg)

    private suspend fun closeLocked(s: DisplaySession) {
        val known = synchronized(sessions) { sessions[s.sessionId] } ?: return
        if (ShizukuBridge.alive()) runCatching { ShizukuBridge.bind(ctx).releaseDisplay(known.displayId) }
        if (synchronized(sessions) { sessions.remove(s.sessionId); sessions.isEmpty() }) ShizukuBridge.unbind()
    }

    private suspend fun live(s: DisplaySession): IShellService {
        require(s.displayId != 0) { "display 0 is the main screen" }
        val known = synchronized(sessions) { sessions[s.sessionId] }
        check(known != null && known.displayId == s.displayId) { "unknown or closed session ${s.sessionId}" }
        if (!ShizukuBridge.alive()) { synchronized(sessions) { sessions.clear() }; throw IllegalStateException("hidden screen service died") }
        return ShizukuBridge.bind(ctx)
    }

    private fun launcherOf(svc: IShellService, pkg: String): String? =
        svc.exec(arrayOf("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg))
            .lines().map { it.trim() }.lastOrNull { it.startsWith("$pkg/") }

    // ponytail: one global mutex, hidden-screen actions run one at a time by design (spec §6.1).
    private suspend fun <T> io(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }
}

/**
 * Asks [where] until it answers [want], at most [max] times with [gapMs] between asks. Returns the last answer and how many asks it took.
 * (The move loop this replaced used `return@repeat`, which acts as `continue`: every move ran all 20 dumpsys queries, ~2.2 s.)
 */
internal suspend fun pollDisplay(where: suspend () -> Int?, want: Int, max: Int = 20, gapMs: Long = 100): Pair<Int?, Int> {
    var last: Int? = null
    for (i in 1..max) {
        last = where()
        if (last == want) return last to i
        if (i < max) delay(gapMs)
    }
    return last to max
}

private val DISPLAY_HEADER = Regex("""^\s*Display #(\d+)""")
private val HIST = Regex("""\* Hist\s+#\d+: ActivityRecord\{\S+ u\d+ ([^/\s]+)/""")

/** Display id holding the first activity of [pkg] in `dumpsys activity activities`, or null. */
fun parseTaskDisplay(dump: String, pkg: String): Int? {
    var display: Int? = null
    for (line in dump.lineSequence()) {
        val header = DISPLAY_HEADER.find(line)
        if (header != null) { display = header.groupValues[1].toInt(); continue }
        val m = HIST.find(line) ?: continue
        if (m.groupValues[1] == pkg) return display
    }
    return null
}

private val ROOT_TASK = Regex("""^\s*RootTask id=(\d+).* displayId=(\d+)""")
private val TASK_OF = Regex("""^\s*taskId=\d+: ([^/\s]+)/""")

/** Root task id holding a task of [pkg] on display [displayId] in `cmd activity stack list` ("RootTask id=N ... displayId=D", then "  taskId=T: pkg/.Activity ..."), or null. */
fun parseRootTask(dump: String, pkg: String, displayId: Int): Int? {
    var root: Int? = null
    var display: Int? = null
    for (line in dump.lineSequence()) {
        ROOT_TASK.find(line)?.let { root = it.groupValues[1].toInt(); display = it.groupValues[2].toInt(); return@let }
            ?: TASK_OF.find(line)?.let { if (it.groupValues[1] == pkg && display == displayId) return root }
    }
    return null
}
