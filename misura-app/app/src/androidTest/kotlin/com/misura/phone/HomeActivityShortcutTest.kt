package com.misura.phone

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.shortcut.Shortcut
import com.misura.core.shortcut.Target
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Codex final re-check 2, through the real HomeActivity (real MisuraGraph store, agent and opener seam): a shortcut's open is held,
 * the system Home intent arrives (what the home gesture delivers), then the open answers false. The fallback must not start: the
 * Home intent counts as navigation the moment the activity receives it, before any Compose effect runs.
 */
class HomeActivityShortcutTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val prefs get() = ctx.getSharedPreferences("shortcuts", android.content.Context.MODE_PRIVATE)
    private var saved: String? = null
    private var priorOpener: ((android.content.Context, String) -> Boolean)? = null
    private val title = "Held open test"

    @Before fun setUp() { saved = prefs.getString("all", null); prefs.edit().remove("all").commit(); priorOpener = MisuraGraph.appOpener }
    @After fun tearDown() {
        priorOpener?.let { MisuraGraph.appOpener = it }
        prefs.edit().apply { if (saved == null) remove("all") else putString("all", saved) }.commit()
    }

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

    /** The shortcut line as the accessibility tree shows it (Compose semantics), in any window. */
    private val seen = mutableSetOf<String>()
    private fun findLine(): android.view.accessibility.AccessibilityNodeInfo? {
        val roots = listOfNotNull(inst.uiAutomation.rootInActiveWindow) + inst.uiAutomation.windows.mapNotNull { it.root }
        for (r in roots) {
            val q = ArrayDeque(listOf(r))
            while (q.isNotEmpty()) {
                val n = q.removeFirst()
                val d = n.contentDescription?.toString().orEmpty() + " " + n.text?.toString().orEmpty()
                seen += d
                if (d.contains(title) && d.contains(", shortcut")) {
                    // The semantics node that carries the description, or the nearest ancestor that takes the click.
                    var c: android.view.accessibility.AccessibilityNodeInfo? = n
                    while (c != null && c.actionList.none { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }) c = c.parent
                    if (c != null) return c
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
            }
        }
        return null
    }

    @Test fun homeIntentWhileOpenIsHeldStartsNoFallback() {
        val release = CountDownLatch(1); val asked = CountDownLatch(1); val answered = CountDownLatch(1)
        MisuraGraph.appOpener = { _, _ -> asked.countDown(); release.await(10, TimeUnit.SECONDS); answered.countDown(); false }
        val before = runBlocking { MisuraGraph.sessions.recent(500).map { it.id }.toSet() }
        check(MisuraGraph.shortcuts.keep(Shortcut("held-open", title, "say the word fallback", null, Target.App("com.misura.testapp"),
            createdAt = System.currentTimeMillis()), null) == com.misura.phone.shortcut.KeepResult.Kept)
        shell("am force-stop com.android.settings"); shell("am force-stop com.misura.testapp"); shell("input keyevent KEYCODE_HOME")
        // The real activity with the real dispatcher (no Compose test clock): the order of the late answer and Compose's frame is production's.
        val scenario = androidx.test.core.app.ActivityScenario.launch(HomeActivity::class.java)
        var activity: HomeActivity? = null
        scenario.onActivity { activity = it }
        var node: android.view.accessibility.AccessibilityNodeInfo? = null
        val end = System.currentTimeMillis() + 10_000
        while (node == null && System.currentTimeMillis() < end) { node = findLine(); if (node == null) Thread.sleep(200) }
        check(node != null) { "the shortcut line never showed: " + String(shell("dumpsys activity activities")).lines().firstOrNull { "topResumedActivity" in it } +
            " seen=" + seen.joinToString(" | ") }
        check(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        check(asked.await(5, TimeUnit.SECONDS)) { "the opener was never asked" }
        val home = Intent(ctx, HomeActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // On the main thread, in one go: the Home intent arrives, then the held open answers false and its resumption is queued
        // before Compose gets a frame (what a gesture racing a slow handoff looks like).
        inst.runOnMainSync {
            HomeActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(activity, home)
            release.countDown()
            check(answered.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
        }
        Thread.sleep(2_000); inst.waitForIdleSync()
        val made = runBlocking { MisuraGraph.sessions.recent(500).map { it.id }.toSet() } - before
        made.forEach { id -> runBlocking { MisuraGraph.agent.delete(id).await() } }
        made shouldBe emptySet()
    }
}
