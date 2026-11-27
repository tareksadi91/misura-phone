package com.misura.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.misura.phone.store.putPrivateString
import com.misura.core.exec.Attempt
import com.misura.core.exec.AttemptStatus
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.core.task.canGo
import com.misura.core.task.transition
import com.misura.phone.MisuraGraph
import com.misura.phone.hidden.count
import kotlinx.coroutines.launch

/**
 * Debug only. `seed`: a task whose attempt was mid-send (DISPATCHING). `dump`: logs a task.
 * `sleep` + `variant` (OFF|ACTIVITY|OVERLAY): picks the sleep screen.
 * `survival` + `n`: task W<n> waiting on Bot<n> (open expectation) and task U<n> unsure whether it sent.
 * `token` + `value`: sets the Vertex access token (empty value clears it). See scripts/push-token.sh.
 * `project` + `value`: sets the Vertex project for the adb-token route (empty clears it).
 * `about` + `value64`: the user's briefing as base64 text (empty clears it; the built-in default applies). See scripts/push-about.sh.
 * `sa` + `value64`: the service account key (JSON, base64) the phone signs in with by itself (empty clears it). See scripts/push-key.sh. Never in git.
 * `exa` + `value`: the Exa key for look_up (empty clears it; Google grounding then does the look-ups). Never in git.
 * `notifications` + `app` + `blocked` (boolean): block/unblock one exact package; default allows all.
 * `notes`: logs how many notes Misura keeps; with `clear` = "yes", forgets them all.
 * `export` + `task`: writes a spike report (markdown) to the app's external files, path in logcat.
 * `shot` + `app` [+ `w`, `h`, `dpi`, `scrolls`, `link` = a URL opened inside the app, `hold` = seconds to keep the display open afterwards]: opens the app on a hidden display of that size, writes shot-<pkg>-<w>.jpg and tree-<pkg>-<w>.txt (full node tree) to the app's files dir. Measurement spike.
 * `reset`: closes every open expectation and cancels every unfinished task that can be cancelled (manual testing).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (!com.misura.phone.BuildConfig.DIAGNOSTICS) return
        val pending = goAsync(); MisuraGraph.init(ctx)
        val id = i.getStringExtra("task") ?: "debug1"
        MisuraGraph.scope.launch {
            try {
                when (i.getStringExtra("cmd")) {
                    "notifications" -> i.getStringExtra("app")?.let { pkg ->
                        com.misura.phone.notify.NotificationAccess(ctx).setBlocked(pkg, i.getBooleanExtra("blocked", false))
                    }
                    "reset" -> {
                        val now = System.currentTimeMillis()
                        MisuraGraph.inbox.openExpectations(now).forEach { MisuraGraph.inbox.closeExpectation(it.requestId) }
                        MisuraGraph.tasks.unfinished().filter { it.canGo(TaskState.CANCELLED) }
                            .forEach { MisuraGraph.tasks.save(it.transition(TaskState.CANCELLED, now, "debug reset")) }
                        Log.i("MisuraDebug", "reset")
                    }
                    "token" -> ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
                        .putPrivateString(com.misura.phone.brain.PrefsToken.KEY, i.getStringExtra("value").orEmpty())
                    "project" -> ctx.getSharedPreferences("brain", Context.MODE_PRIVATE).let { prefs ->
                        synchronized(prefs) { prefs.edit().putString("project", i.getStringExtra("value").orEmpty()).apply() }
                    }
                    "about" -> ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
                        .putPrivateString(ABOUT_KEY, runCatching { String(android.util.Base64.decode(i.getStringExtra("value64").orEmpty(), android.util.Base64.DEFAULT)) }.getOrDefault(""))
                    "sa" -> ctx.getSharedPreferences("brain", Context.MODE_PRIVATE).let { prefs ->
                        val sa = runCatching { String(android.util.Base64.decode(i.getStringExtra("value64").orEmpty(), android.util.Base64.DEFAULT)) }.getOrDefault("")
                        synchronized(prefs) {
                            prefs.putPrivateString(SA_KEY, sa)
                            // A pushed key is the active one: a service account selects Vertex (other providers' keys stay saved).
                            if (sa.isNotBlank()) prefs.edit().putString("model_provider", com.misura.phone.brain.ModelProvider.VERTEX.name).commit()
                        }
                    }
                    "hand_think", "main_think", "hand_model" -> {
                        val cmd = i.getStringExtra("cmd")!!; val key = when (cmd) { "hand_think" -> HAND_THINK_KEY; "main_think" -> MAIN_THINK_KEY; else -> HAND_MODEL_KEY }
                        val v = i.getStringExtra("value").orEmpty().trim().let { if (cmd == "hand_model") it else it.uppercase() }
                        val prefs = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
                        synchronized(prefs) {
                            val editor = prefs.edit()
                            if (v.isEmpty() || v.equals("off", true)) editor.remove(key) else editor.putString(key, v)
                            editor.apply()
                        }
                        Log.i("MisuraAgent", "switch $cmd = ${if (v.isEmpty() || v.equals("off", true)) "off (default)" else v}")
                    }
                    "exa" -> ctx.getSharedPreferences("brain", Context.MODE_PRIVATE).putPrivateString(EXA_KEY, i.getStringExtra("value").orEmpty())
                    "apikey", "searchkey" -> {
                        val cmd = i.getStringExtra("cmd")!!
                        val name = i.getStringExtra("provider").orEmpty().uppercase()
                        val known = (com.misura.phone.brain.ModelProvider.entries.map { it.name } + com.misura.phone.brain.SearchProvider.entries.map { it.name }).let { if (name in it) name else "unknown" }
                        val settings = com.misura.phone.setup.CredentialSettings(ctx.getSharedPreferences("brain", Context.MODE_PRIVATE))
                        val saved = runCatching {
                            val value = requireNotNull(i.getStringExtra("value"))
                            if (cmd == "apikey") settings.importApiKey(com.misura.phone.brain.ModelProvider.valueOf(name), value)
                            else settings.importSearchKey(com.misura.phone.brain.SearchProvider.valueOf(name), value)
                        }.isSuccess
                        // Fixed words only: never the value, the intent or the exception (it could carry input).
                        Log.i("MisuraDebug", "$cmd $known: ${if (saved) "saved and selected" else "rejected, nothing changed"}")
                    }
                    "notes" -> ctx.getSharedPreferences("brain", Context.MODE_PRIVATE).let { p ->
                        if (i.getStringExtra("clear") == "yes") p.edit().remove(com.misura.phone.brain.NOTES_KEY).apply()
                        Log.i("MisuraDebug", "notes: ${com.misura.phone.brain.notes(p).size}") // the count only: notes are personal
                    }
                    "tree0" -> {
                        val pkg = i.getStringExtra("app").orEmpty()
                        val t = com.misura.phone.hidden.ScreenReader.readMain(pkg)
                        if (t != null) java.io.File(ctx.filesDir, "tree0-$pkg.txt").writeText(ScreenShot.dump(t))
                        Log.i("MisuraShot", "tree0 $pkg " + (if (t == null) "no window on the main screen" else "written"))
                    }
                    "shot" -> ScreenShot.run(ctx, i.getStringExtra("app").orEmpty(), i.getIntExtra("w", 720), i.getIntExtra("h", 1600), i.getIntExtra("dpi", 320), i.getIntExtra("scrolls", 0), i.getStringExtra("link"), i.getIntExtra("hold", 0)) { runCatching { pending.finish() } }
                    "export" -> SpikeReport.write(ctx, id)
                    // Asks Misura as if typed, and opens the task: live runs without fighting the keyboard over adb.
                    "ask" -> i.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { t ->
                        val session = MisuraGraph.agent.start(t, com.misura.core.agent.TurnOrigin.DEBUG) // diagnostic input, not the owner's words
                        ctx.startActivity(android.content.Intent(ctx, com.misura.phone.HomeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("session", session))
                    }
                    "seed" -> {
                        val p = Payload(mapOf("channel" to "testchat", "recipient" to "Nobody", "content" to "debug send"))
                        MisuraGraph.tasks.save(Task(id, "debug", TaskState.EXECUTING, p, emptyList(), System.currentTimeMillis(), null))
                        MisuraGraph.tasks.updateAttempt(Attempt("att-$id", id, "notification_reply:testchat", "none", p.digest(), AttemptStatus.DISPATCHING, System.currentTimeMillis(), System.currentTimeMillis(), null))
                        Log.i("MisuraDebug", "seeded $id")
                    }
                    "survival" -> {
                        val n = i.getStringExtra("n") ?: "0"; val now = System.currentTimeMillis()
                        val app = "com.misura.testapp"
                        val pw = Payload(mapOf("channel" to "testchat", "recipient" to "Bot$n", "content" to "survival ask $n", "expectsReply" to "true"))
                        MisuraGraph.tasks.save(Task("W$n", "ask", TaskState.WAITING, pw, emptyList(), now, null))
                        MisuraGraph.inbox.addExpectation(com.misura.core.inbox.Expectation("W$n", "W$n:ask", app, "$app:Bot$n", "Bot$n", now, now + 2 * 3_600_000))
                        val pu = Payload(mapOf("channel" to "testchat", "recipient" to "Friend$n", "content" to "survival unsure $n"))
                        MisuraGraph.tasks.save(Task("U$n", "debug", TaskState.OUTCOME_UNKNOWN, pu, emptyList(), now, null))
                        MisuraGraph.tasks.updateAttempt(Attempt("att-U$n", "U$n", "notification_reply:testchat", "none", pu.digest(), AttemptStatus.UNCERTAIN, now, now, null))
                        com.misura.phone.notify.AnswerReady.watch(ctx, "W$n", "W$n:ask", com.misura.phone.notify.AnswerReady.DEFAULT_QUIET_MS, now + 2 * 3_600_000)
                        Log.i("MisuraDebug", "survival seeded $n")
                    }
                    "sleep" -> com.misura.phone.sleep.Variant.entries.firstOrNull { it.name == i.getStringExtra("variant") }
                        ?.let { com.misura.phone.sleep.SleepMode.set(ctx, it) } ?: Log.w("MisuraDebug", "sleep: variant must be OFF, ACTIVITY or OVERLAY")
                    else -> {
                        val t = MisuraGraph.tasks.load(id)
                        val answers = MisuraGraph.inbox.answer("$id:ask").size
                        Log.i("MisuraDebug", "task $id state=${t?.state} buzzed=${t?.buzzed} answers=$answers history=${t?.history?.joinToString(" | ") { "${it.from}->${it.to}: ${it.note}" }}")
                    }
                }
            } finally { runCatching { pending.finish() } }
        }
    }
}

/** Debug only: screenshot + full tree of an app on a hidden display, for measuring what generic item cards can rely on. */
object ScreenShot {
    /** One line per node: depth, class, bounds, clickable, editable, text, desc. */
    fun dump(root: com.misura.phone.hidden.UiNode): String {
        val sb = StringBuilder()
        fun walk(n: com.misura.phone.hidden.UiNode, d: Int) {
            sb.append("  ".repeat(d)).append(n.cls ?: "?").append(' ').append(n.bounds.joinToString(",", "[", "]"))
                .append(" click=").append(n.clickable).append(" edit=").append(n.editable)
                .append(" text=").append(n.text?.replace("\n", " ")?.take(80)?.let { "\"$it\"" } ?: "-")
                .append(" desc=").append(n.desc?.replace("\n", " ")?.take(80)?.let { "\"$it\"" } ?: "-").append('\n')
            n.children.forEach { walk(it, d + 1) }
        }
        walk(root, 0)
        return sb.toString()
    }

    suspend fun run(ctx: Context, pkg: String, w: Int, h: Int, dpi: Int, scrolls: Int, link: String? = null, holdSec: Int = 0, released: () -> Unit = {}) {
        val hidden = com.misura.phone.hidden.ShizukuHiddenScreen(ctx)
        var s: com.misura.phone.hidden.DisplaySession? = null
        try {
            s = hidden.open(w, h, dpi)
            check(hidden.launch(s, pkg, deepLink = link)) { "launch failed" }
            var root: com.misura.phone.hidden.UiNode? = null
            repeat(160) { if (root == null) { root = com.misura.phone.hidden.ScreenReader.read(s); if (root == null) kotlinx.coroutines.delay(250) } }
            checkNotNull(root) { "no readable screen" }
            kotlinx.coroutines.delay(6000)
            repeat(scrolls) {
                com.misura.phone.hidden.HiddenInput.swipe(s, w / 2, h * 2 / 3, w / 2, h / 3); kotlinx.coroutines.delay(2500)
            }
            if (scrolls > 0) kotlinx.coroutines.delay(3000)
            val tr = System.nanoTime()
            val tree = com.misura.phone.hidden.ScreenReader.read(s) ?: error("no tree after scroll")
            Log.i("MisuraShot", "read ${(System.nanoTime() - tr) / 1_000_000} ms nodes=${tree.count { true }}")
            val sb = StringBuilder(dump(tree))
            java.io.File(ctx.filesDir, "tree-$pkg-$w.txt").writeText(sb.toString())
            val jpg = hidden.grab(s, intArrayOf(0, 0, w, h), w)
            if (jpg != null) java.io.File(ctx.filesDir, "shot-$pkg-$w.jpg").writeBytes(jpg)
            if (holdSec > 0) { released() /* the receiver must not wait: a held broadcast is killed as an ANR, display and all */; Log.i("MisuraShot", "holding display ${s.displayId}"); kotlinx.coroutines.delay(holdSec * 1000L) }
            Log.i("MisuraShot", "done $pkg ${w}x$h nodes=${sb.count { it == '\n' }} jpg=${jpg?.size}")
        } catch (e: Throwable) {
            Log.e("MisuraShot", "error $pkg ${w}x$h: ${e.javaClass.simpleName} ${e.message?.take(80)}")
        } finally { s?.let { runCatching { hidden.close(it) } } }
    }
}
