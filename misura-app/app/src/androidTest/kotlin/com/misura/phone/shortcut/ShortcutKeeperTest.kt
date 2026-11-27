package com.misura.phone.shortcut

import com.misura.core.agent.TurnOrigin
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Agent
import com.misura.core.agent.Brain
import com.misura.core.agent.BrainReply
import com.misura.core.agent.Say
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolCall
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import com.misura.core.shortcut.Shortcut
import com.misura.core.shortcut.Target
import com.misura.phone.HomeScreen
import com.misura.phone.cardGates
import com.misura.phone.hands.Calendar
import com.misura.phone.hands.UseApp
import com.misura.phone.hidden.ShizukuHiddenScreen
import com.misura.phone.routine.RoutineStore
import com.misura.phone.routine.RoutineTool
import com.misura.phone.store.MisuraDb
import com.misura.phone.store.RoomSessionStore
import com.misura.phone.store.RoomTaskStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The keeper: one owner for every shortcut mutation (store write, then pin new, then unpin the replaced or removed one). */
class ShortcutKeeperTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "shortcut-keeper-test.db"
    private lateinit var db: MisuraDb
    private lateinit var sessions: RoomSessionStore
    private lateinit var tasks: RoomTaskStore
    private lateinit var store: ShortcutStore
    private lateinit var pins: FakePins
    private lateinit var keeper: ShortcutKeeper
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pkg = "com.example.club"
    /** Cancels the agent scope (its gate workers and turns) and waits, bounded, so nothing outlives the test. */
    private fun stopScope() {
        val job = scope.coroutineContext[kotlinx.coroutines.Job]!!
        val done = runBlocking { kotlinx.coroutines.withTimeoutOrNull<Unit>(10_000) { job.cancelAndJoin() } }
        check(done != null && job.isCompleted) { "agent scope did not stop within 10 s: a worker would outlive the test" }
    }

    /** The launcher's pinned set per package: `pin` replaces the whole set (Android's pinShortcuts), like the real thing. */
    private class FakePins : ShortcutPins {
        val pinned = mutableMapOf<String, Set<String>>()
        @Volatile var queryFails = false
        @Volatile var pinFails = false
        @Volatile var slowQueryMs = 0L
        /** Each pin() call: the set written and the kept shortcut ids in the store at that moment. */
        data class Call(val ids: Set<String>, val storeIds: List<String>)
        @Volatile var storeIds: () -> List<String> = { emptyList() }
        val pinCalls = java.util.concurrent.CopyOnWriteArrayList<Call>()
        override fun rawPinned(pkg: String): Set<String>? {
            if (queryFails) return null
            val seen = synchronized(this) { pinned[pkg].orEmpty() }
            if (slowQueryMs > 0) Thread.sleep(slowQueryMs) // a window in which an unlocked caller would lose the other write
            return seen
        }
        override fun pin(pkg: String, ids: Set<String>): Boolean {
            pinCalls += Call(ids, storeIds())
            if (pinFails) return false
            synchronized(this) { pinned[pkg] = ids }
            return true
        }
        fun of(pkg: String): Set<String> = synchronized(this) { pinned[pkg].orEmpty() }
    }

    private fun ap(id: String, sid: String = "sid-$id", p: String = pkg) =
        Shortcut(id, "Title $id", null, null, target = Target.AppShortcut(p, sid), createdAt = 1)
    private fun keep(s: Shortcut, replacing: String? = null) = keeper.keep(s, replacing) { true }

    @Before fun setUp() {
        ctx.deleteDatabase(dbName); db = MisuraDb.open(ctx, dbName); sessions = RoomSessionStore(db); tasks = RoomTaskStore(db)
        ctx.getSharedPreferences("shortcuts", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("first-use", 0).edit().putBoolean("apps-opened", true).commit()
        store = ShortcutStore(ctx); pins = FakePins(); pins.storeIds = { store.all().map { it.id } }; keeper = ShortcutKeeper(store, pins)
    }
    @After fun tearDown() {
        stopScope() // agents and gate workers stop before prefs and the DB go
        ctx.getSharedPreferences("shortcuts", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("first-use", 0).edit().clear().commit()
        db.close()
    }

    @Test fun keepPinsWithoutDroppingOtherPins() {
        pins.pinned[pkg] = setOf("someone-elses", "disabled-one") // other pins, including a disabled one the launcher still lists raw
        keep(ap("a")) shouldBe KeepResult.Kept
        pins.of(pkg) shouldBe setOf("someone-elses", "disabled-one", "sid-a")
        store.all().map { it.id } shouldBe listOf("a")
    }

    @Test fun removeUnpinsOnlyItsId() {
        pins.pinned[pkg] = setOf("someone-elses")
        keep(ap("a")); keep(ap("b"))
        keeper.remove("a") shouldBe true
        pins.of(pkg) shouldBe setOf("someone-elses", "sid-b")
        store.all().map { it.id } shouldBe listOf("b")
        keeper.remove("a") shouldBe false // already gone: nothing else is touched
        pins.of(pkg) shouldBe setOf("someone-elses", "sid-b")
    }

    @Test fun replaceUnpinsTheOldOnlyAfterTheWriteAndOnlyIfNoOneElseKeepsIt() {
        keep(ap("a")); keep(ap("b"))
        keep(ap("c"), replacing = "a") shouldBe KeepResult.Kept
        pins.of(pkg) shouldBe setOf("sid-b", "sid-c")
        // the same app shortcut kept twice (a replace to itself): its pin stays
        keep(ap("d", sid = "sid-c"), replacing = "c") shouldBe KeepResult.Kept
        pins.of(pkg) shouldBe setOf("sid-b", "sid-c")
    }

    /** Write first, then pin the new one, then unpin the old: the store already holds the new list at both pin callbacks. */
    @Test fun replaceOrdersWriteThenPinNewThenUnpinOld() {
        keep(ap("a"))
        pins.pinCalls.clear()
        keep(ap("c"), replacing = "a") shouldBe KeepResult.Kept
        pins.pinCalls.toList() shouldBe listOf(
            FakePins.Call(setOf("sid-a", "sid-c"), listOf("c")), // pin new: the replacement is already written, the old still pinned
            FakePins.Call(setOf("sid-c"), listOf("c")))           // then unpin the old
        keep(ap("d"))
        pins.pinCalls.clear()
        keeper.remove("c") shouldBe true
        pins.pinCalls.toList() shouldBe listOf(FakePins.Call(setOf("sid-d"), listOf("d"))) // removal: unpinned after the write
    }

    @Test fun onlyAppShortcutsArePinned() {
        keeper.keep(Shortcut("p", "Prompt", "words", null, createdAt = 1), null) { true } shouldBe KeepResult.Kept
        keeper.keep(Shortcut("x", "App", null, null, target = Target.App(pkg), createdAt = 1), null) { true } shouldBe KeepResult.Kept
        pins.pinCalls shouldBe emptyList()
        keeper.remove("x") shouldBe true; keeper.remove("p") shouldBe true
        pins.pinCalls shouldBe emptyList()
    }

    @Test fun pinQueryFailureSkipsPinChange() {
        pins.pinned[pkg] = setOf("someone-elses")
        pins.queryFails = true
        keep(ap("a")) shouldBe KeepResult.Kept
        store.all().map { it.id } shouldBe listOf("a")
        pins.pinCalls shouldBe emptyList() // never "pin only mine", which would unpin everything else
        pins.of(pkg) shouldBe setOf("someone-elses")
        keeper.remove("a") shouldBe true
        pins.pinCalls shouldBe emptyList()
    }

    @Test fun pinFailureKeepsSave() {
        pins.pinFails = true
        keep(ap("a")) shouldBe KeepResult.Kept
        store.all().map { it.id } shouldBe listOf("a")
        keeper.remove("a") shouldBe true
        store.all() shouldBe emptyList()
    }

    @Test fun concurrentKeepsBothPinned() {
        pins.slowQueryMs = 150
        val results = java.util.concurrent.CopyOnWriteArrayList<KeepResult>()
        val go = CountDownLatch(1)
        val ts = listOf("a", "b").map { id -> thread { go.await(); results += keep(ap(id)) } }
        go.countDown(); ts.forEach { it.join(10_000) }
        results.toList() shouldBe listOf(KeepResult.Kept, KeepResult.Kept)
        pins.of(pkg) shouldBe setOf("sid-a", "sid-b")
    }

    private fun waitBlocked(t: Thread) { val end = System.currentTimeMillis() + 5_000; while (t.state != Thread.State.BLOCKED && System.currentTimeMillis() < end) Thread.sleep(10); t.state shouldBe Thread.State.BLOCKED }

    @Test fun labelChangeQueuedBehindLockIsRefused() {
        var label = "Club"
        val inside = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = thread { keeper.keep(ap("a"), null) { inside.countDown(); release.await(10, TimeUnit.SECONDS); true } }
        inside.await(5, TimeUnit.SECONDS) shouldBe true
        var queued: KeepResult? = null
        val shown = label // what the user was shown
        val b = thread { queued = keeper.keep(ap("b"), null) { label == shown } }
        waitBlocked(b)
        label = "Club (renamed)" // the app changed the label while B waited for the lock
        release.countDown(); holder.join(10_000); b.join(10_000)
        queued shouldBe KeepResult.TargetChanged
        store.all().map { it.id } shouldBe listOf("a")
        pins.of(pkg) shouldBe setOf("sid-a")
    }

    @Test fun interleavedToolAndMenuKeepHonourFull() {
        keep(ap("a")); keep(ap("b"))
        val inside = CountDownLatch(1); val release = CountDownLatch(1)
        val tool = thread { keeper.keep(ap("tool"), null) { inside.countDown(); release.await(10, TimeUnit.SECONDS); true } }
        inside.await(5, TimeUnit.SECONDS) shouldBe true
        var menu: KeepResult? = null
        val m = thread { menu = keeper.keep(ap("menu"), null) { true } } // the menu saw "not full" before the tool filled Home
        waitBlocked(m)
        release.countDown(); tool.join(10_000); m.join(10_000)
        menu shouldBe KeepResult.Full
        store.all().map { it.id } shouldBe listOf("a", "b", "tool")
        pins.of(pkg) shouldBe setOf("sid-a", "sid-b", "sid-tool")
    }

    // ---- through the production route ----

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
    private val hand = object : Tool {
        override val spec = ToolSpec("use_app", "operate an app", JsonObject(emptyMap()))
        override suspend fun run(args: JsonObject): JsonObject = buildJsonObject { put("ok", true) }
        override fun label(args: JsonObject) = "Using an app"
        override fun summary(result: JsonObject): String? = null
        override fun appFor(args: JsonObject) = (args["app"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
    private val noBrain = object : Brain { override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply = error("not used") }
    private fun toolAgent(vararg replies: () -> BrainReply): Pair<Agent, ShortcutTool> {
        val tool = ShortcutTool(store, keeper, controllerSteps(sessions, tasks::load) { false }, { it }, ShortcutLauncher(ctx), { emptyList() })
        val routineTool = RoutineTool(RoutineStore(ctx))
        val gates = cardGates(Calendar(ctx, com.misura.core.agent.CalendarGate()), routineTool, tool, UseApp(ShizukuHiddenScreen(ctx), noBrain))
        return Agent(sessions, Script(*replies), listOf(hand, routineTool, tool), System::currentTimeMillis, scope,
            onChoose = gates.onChoose, chooseNote = gates.chooseNote, onStopped = gates.onStopped) to tool
    }
    private suspend fun settled(id: String): Session {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end && sessions.load(id)?.status == SessionStatus.THINKING) Thread.sleep(50)
        return sessions.load(id)!!
    }

    /**
     * The tool's Keep tap waits behind another keeper call (held by a latch, not a sleep). The outcome note the model is told must not
     * arrive before the write: the next Brain call, made when that note arrives, looks at the store itself.
     */
    @Test fun delayedWriteThroughRealAgentChoose() = runBlocking<Unit> {
        val add = """{"action":"add","title":"Book my class","prompt":"Book the 7 pm class at my club"}"""
        var titlesWhenNoteArrived: List<String>? = null
        val finalReply = { titlesWhenNoteArrived = store.all().map { it.title }; text("Kept.")() }
        val (a, _) = toolAgent(call("use_app", """{"app":"$pkg","goal":"book"}"""), call("shortcut", add), finalReply)
        val s = settled(a.start("book my class and keep it as a shortcut", TurnOrigin.OWNER))
        (s.say as Say.Question).gate!! shouldContain "sc:"
        val inside = CountDownLatch(1); val release = CountDownLatch(1)
        var slow: Thread? = null
        try {
            slow = thread { keeper.keep(ap("other"), null) { inside.countDown(); release.await(10, TimeUnit.SECONDS); true } } // someone else is mid-write
            inside.await(5, TimeUnit.SECONDS) shouldBe true
            val tap = a.choose(s.id, 0, s.revision)
            Thread.sleep(500)
            tap.isCompleted shouldBe false // the tap is still waiting for the write
            titlesWhenNoteArrived shouldBe null // and no note has reached the model
            release.countDown()
            kotlinx.coroutines.withTimeout(10_000) { tap.await() } shouldBe true
            slow.join(10_000); slow.isAlive shouldBe false
            titlesWhenNoteArrived shouldBe listOf("Title other", "Book my class") // the write was there when the note arrived
            sessions.turns(s.id).toString() shouldContain "(the app: Kept: Book my class)"
        } finally { // a failed assertion must not leave the holder blocked or a worker alive for the next test
            release.countDown()
            slow?.join(10_000)
            check(slow?.isAlive != true) { "the lock-holder thread did not finish within 10 s" }
            stopScope()
        }
    }

    @Test fun everyRemovalRouteUnpins() = runBlocking<Unit> {
        // tool: Agent → registered tool → Remove card → CardGates tap
        keep(ap("t")); keep(ap("sw"))
        pins.of(pkg) shouldBe setOf("sid-t", "sid-sw")
        val (a, _) = toolAgent(call("shortcut", """{"action":"remove","id":"t"}"""), text("Removed."))
        val s = settled(a.start("remove that shortcut", TurnOrigin.OWNER))
        a.choose(s.id, 0, s.revision).await() shouldBe true
        store.all().map { it.id } shouldBe listOf("sw")
        pins.of(pkg) shouldBe setOf("sid-sw")
        // swipe: the real HomeScreen with the keeper
        val agent = Agent(sessions, noBrain, listOf(hand), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, sessions, null, null, shortcutStore = store, shortcutKeeper = keeper) }
        val line = hasText("Title sw") and hasContentDescription(", shortcut", substring = true)
        rule.waitUntil(8_000) { rule.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(line).performTouchInput { swipeLeft(startX = width * .85f, endX = width * .1f, durationMillis = 300) }
        rule.waitUntil(8_000) { store.all().isEmpty() }
        rule.waitUntil(8_000) { pins.of(pkg).isEmpty() }
        pins.of(pkg) shouldBe emptySet()
        // Home long-press (Task 5) and the app-list menu (Task 6) route through keeper.remove as well; each adds its own assertion with its UI.
    }
}
