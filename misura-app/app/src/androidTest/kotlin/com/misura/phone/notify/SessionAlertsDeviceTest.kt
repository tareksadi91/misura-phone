package com.misura.phone.notify

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Agent
import com.misura.core.agent.Brain
import com.misura.core.agent.BrainReply
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.ToolCall
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import com.misura.phone.store.MisuraDb
import com.misura.phone.store.RoomSessionStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test

/** M3 through the production route: Agent → the same hooks MisuraGraph wires → posted notification → its tap target. */
class SessionAlertsDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val dbName = "alerts-test.db"
    private lateinit var db: MisuraDb
    private lateinit var store: RoomSessionStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Script(vararg replies: () -> BrainReply) : Brain {
        private val q = ArrayDeque(replies.toList())
        override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply = q.removeFirst()()
    }
    private fun text(t: String) = { BrainReply.Text(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { put("text", t) }) }) }, t) }
    private fun call(name: String, args: String) = {
        val a = Json.parseToJsonElement(args).jsonObject
        BrainReply.Calls(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { putJsonObject("functionCall") { put("name", name); put("args", a) } }) }) },
            listOf(ToolCall(null, name, a)))
    }
    private fun agent(vararg replies: () -> BrainReply): Agent {
        val (settled, allSent) = SessionAlerts.hooks(ctx, store, scope)
        return Agent(store, Script(*replies), emptyList(), System::currentTimeMillis, scope, onSettled = settled, onAllSent = allSent)
    }
    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    private fun posted(tag: String): StatusBarNotification? = nm.activeNotifications.firstOrNull { it.tag == tag }
    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
        return cond()
    }
    private fun settledOn(id: String) = waitFor(5_000) { runBlocking { store.load(id)?.status == SessionStatus.IDLE } }
    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.line get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Before fun setUp() {
        ctx.deleteDatabase(dbName); db = MisuraDb.open(ctx, dbName); store = RoomSessionStore(db)
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        SessionAlerts.front(ctx, false)
        SessionAlerts.clearAll(ctx)
    }
    @After fun tearDown() {
        SessionAlerts.front(ctx, false)
        SessionAlerts.clearAll(ctx); db.close()
    }

    @Test fun aBackgroundAnswerPostsAPrivateResultThatOpensItsSession() = runBlocking<Unit> {
        val id = agent(text("Hornbach closes at 20:00.")).start("when does hornbach close")
        waitFor(5_000) { posted(SessionAlerts.resultTag(id)) != null } shouldBe true
        val n = posted(SessionAlerts.resultTag(id))!!.notification
        n.channelId shouldBe SessionAlerts.RESULTS
        n.title shouldBe "when does hornbach close"
        n.line shouldBe "Hornbach closes at 20:00."
        n.visibility shouldBe Notification.VISIBILITY_PRIVATE
        n.publicVersion!!.title shouldBe "Result ready"
        n.publicVersion!!.line shouldBe "Open Misura to view"
        n.actions shouldBe null // navigation only
        // The tap target is Home on exactly this session (same PendingIntent identity as SessionAlerts.open).
        (n.contentIntent == SessionAlerts.open(ctx, id)) shouldBe true
        nm.getNotificationChannel(SessionAlerts.RESULTS).importance shouldBe NotificationManager.IMPORTANCE_DEFAULT
    }

    @Test fun aQuestionNeedsYouAndItsAnswerReplacesIt() = runBlocking<Unit> {
        val a = agent(call("ask", """{"line":"Tuesday or Thursday?","choices":["Tuesday","Thursday"]}"""), text("Booked for Thursday."))
        val id = a.start("book the dentist")
        waitFor(5_000) { posted(SessionAlerts.needsTag(id)) != null } shouldBe true
        posted(SessionAlerts.needsTag(id))!!.notification.let { it.title shouldBe "Needs your input"; it.line shouldBe "Tuesday or Thursday?"; it.channelId shouldBe SessionAlerts.NEEDS }
        posted(SessionAlerts.resultTag(id)) shouldBe null
        a.followUp(id, "Thursday")
        waitFor(5_000) { posted(SessionAlerts.resultTag(id)) != null } shouldBe true
        waitFor(2_000) { posted(SessionAlerts.needsTag(id)) == null } shouldBe true
        posted(SessionAlerts.resultTag(id))!!.notification.let { it.title shouldBe "Thursday"; it.line shouldBe "Booked for Thursday." }
    }

    @Test fun twoSessionsGetTwoAlertsWithTheirOwnTargets() = runBlocking<Unit> {
        val one = agent(text("first")).start("one")
        val two = agent(text("second")).start("two")
        waitFor(5_000) { posted(SessionAlerts.resultTag(one)) != null && posted(SessionAlerts.resultTag(two)) != null } shouldBe true
        (posted(SessionAlerts.resultTag(one))!!.notification.contentIntent == posted(SessionAlerts.resultTag(two))!!.notification.contentIntent) shouldBe false
    }

    @Test fun aFreshResultAfterDismissalAlertsAgain() = runBlocking<Unit> {
        val a = agent(text("first"), text("second"))
        val id = a.start("one")
        waitFor(5_000) { posted(SessionAlerts.resultTag(id)) != null } shouldBe true
        nm.cancel(SessionAlerts.resultTag(id), posted(SessionAlerts.resultTag(id))!!.id) // swiped away
        a.followUp(id, "again")
        waitFor(5_000) { posted(SessionAlerts.resultTag(id))?.notification?.line == "second" } shouldBe true
    }

    @Test fun nothingWhileTheUserIsInMisura() = runBlocking<Unit> {
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard"); Thread.sleep(500)
        SessionAlerts.front(ctx, true)
        val id = agent(text("seen already")).start("look")
        settledOn(id) shouldBe true
        Thread.sleep(1_000)
        posted(SessionAlerts.resultTag(id)) shouldBe null
    }

    @Test fun comingIntoMisuraClearsTheShadeCopies() = runBlocking<Unit> {
        val id = agent(text("done")).start("x")
        waitFor(5_000) { posted(SessionAlerts.resultTag(id)) != null } shouldBe true
        SessionAlerts.clearAll(ctx)
        waitFor(2_000) { posted(SessionAlerts.resultTag(id)) == null } shouldBe true
    }

    @Test fun dismissingAWaitingQuestionTakesItsAlertAway() = runBlocking<Unit> {
        val a = agent(call("ask", """{"line":"Which one?","choices":["A","B"]}"""))
        val id = a.start("pick")
        waitFor(5_000) { posted(SessionAlerts.needsTag(id)) != null } shouldBe true
        a.dismiss(id).await()
        waitFor(3_000) { posted(SessionAlerts.needsTag(id)) == null } shouldBe true
    }

    @Test fun deletingASessionTakesItsResultAway() = runBlocking<Unit> {
        val a = agent(text("done"))
        val id = a.start("x")
        waitFor(5_000) { posted(SessionAlerts.resultTag(id)) != null } shouldBe true
        a.delete(id).await()
        waitFor(3_000) { posted(SessionAlerts.resultTag(id)) == null } shouldBe true
    }

    @Test fun aStaleEventNeverPostsOverNewerState() = runBlocking<Unit> {
        val (settled, _) = SessionAlerts.hooks(ctx, store, scope)
        val id = agent(text("old answer")).start("x")
        settledOn(id) shouldBe true
        waitFor(5_000) { posted(SessionAlerts.resultTag(id)) != null } shouldBe true
        val seen = store.load(id)!!
        SessionAlerts.clearAll(ctx); waitFor(2_000) { posted(SessionAlerts.resultTag(id)) == null } shouldBe true
        store.save(seen.copy(status = SessionStatus.THINKING)) // new work started on it
        settled(seen) // the old turn's event arrives late
        Thread.sleep(1_000)
        posted(SessionAlerts.resultTag(id)) shouldBe null
    }

    @Test fun aDraftThatNoLongerWaitsNeverAsksForYourOk() = runBlocking<Unit> {
        var waits = true
        val (settled, _) = SessionAlerts.hooks(ctx, store, scope) { waits }
        val s = com.misura.core.agent.Session("draft-" + System.nanoTime(), SessionStatus.IDLE, 1, System.currentTimeMillis(),
            say = com.misura.core.agent.Say.Approve("t-1", "NeighborB", "Testchat"))
        store.save(s)
        waits = false // it went out (or failed) before this late event was handled
        settled(s)
        Thread.sleep(1_000)
        posted(SessionAlerts.needsTag(s.id)) shouldBe null
        waits = true
        settled(s)
        waitFor(3_000) { posted(SessionAlerts.needsTag(s.id))?.notification?.line == "Message to NeighborB" } shouldBe true
    }

    @Test fun aSendFailingWhileItsAlertIsDecidedNeverLeavesItBehind() = runBlocking<Unit> {
        val checking = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (settled, _) = SessionAlerts.hooks(ctx, store, scope) { checking.complete(Unit); release.await(); true } // still waiting when checked
        val s = com.misura.core.agent.Session("fail-" + System.nanoTime(), SessionStatus.IDLE, 1, System.currentTimeMillis(),
            say = com.misura.core.agent.Say.Approve("t-9", "NeighborB", "Testchat"))
        store.save(s)
        settled(s); checking.await()
        val cleaned = kotlinx.coroutines.CompletableDeferred<Unit>()
        SessionAlerts.sendFailed(ctx, scope, "t-9") { val r = if (it == "t-9") store.load(s.id) else null; cleaned.complete(Unit); r } // fails mid-decision
        // Give an out-of-turn cleanup every chance to run first (in turn, it waits for the decision and runs after it).
        kotlinx.coroutines.withTimeoutOrNull(1_000) { cleaned.await() }; Thread.sleep(200)
        release.complete(Unit)
        Thread.sleep(1_500)
        posted(SessionAlerts.needsTag(s.id)) shouldBe null
    }

    @Test fun anOldFailedSendLeavesANewerCardsAlertAlone() = runBlocking<Unit> {
        val (settled, _) = SessionAlerts.hooks(ctx, store, scope)
        val s = com.misura.core.agent.Session("newer-" + System.nanoTime(), SessionStatus.IDLE, 2, System.currentTimeMillis(),
            say = com.misura.core.agent.Say.Approve("t-new", "Guest", "Testchat"))
        store.save(s)
        settled(s)
        waitFor(3_000) { posted(SessionAlerts.needsTag(s.id)) != null } shouldBe true
        SessionAlerts.sendFailed(ctx, scope, "t-old") { store.load(s.id) } // an earlier draft of this session failed late
        Thread.sleep(1_000)
        (posted(SessionAlerts.needsTag(s.id)) != null) shouldBe true
    }
}
