package com.misura.phone.shortcut

import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Agent
import com.misura.core.agent.Brain
import com.misura.core.agent.CalendarCreate
import com.misura.core.agent.CalendarGate
import com.misura.core.agent.RunningFor
import com.misura.core.agent.BrainReply
import com.misura.core.agent.Say
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolCall
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import com.misura.core.shortcut.Shortcut
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.phone.CardGates
import com.misura.phone.GateOwner
import com.misura.phone.cardGates
import com.misura.phone.hands.Calendar
import com.misura.phone.hands.CommitGate
import com.misura.phone.hands.UseApp
import com.misura.phone.hidden.ShizukuHiddenScreen
import com.misura.phone.routine.RoutineStore
import com.misura.phone.routine.RoutineTool
import com.misura.phone.store.MisuraDb
import com.misura.phone.store.RoomSessionStore
import com.misura.phone.store.RoomTaskStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * The shortcut tool through the production route: a scripted model → real [Agent] → registered tool → card → [CardGates] (the
 * same routing MisuraGraph passes the Agent) → tap → store. Steps reach the tool through [controllerSteps], as in MisuraGraph.
 */
class ShortcutToolTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "shortcut-tool-test.db"
    private lateinit var db: MisuraDb
    private lateinit var sessions: RoomSessionStore
    private lateinit var tasks: RoomTaskStore
    private lateinit var store: ShortcutStore
    private lateinit var routines: RoutineStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val otherTaps = mutableListOf<String>()

    /** A scripted model: one reply per call, in order. Call number [holdAt] (0-based) waits on [hold] first; [entered] says it got there. */
    private class Script(vararg replies: () -> BrainReply, val holdAt: Int = -1, val hold: CompletableDeferred<Unit>? = null) : Brain {
        private val q = ArrayDeque(replies.toList())
        private var n = 0
        val entered = CompletableDeferred<Unit>()
        override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
            if (n++ == holdAt) { entered.complete(Unit); hold!!.await() }
            return q.removeFirst()()
        }
    }
    private fun text(t: String) = { BrainReply.Text(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { put("text", t) }) }) }, t) }
    private fun call(name: String, args: String = "{}") = {
        val a = Json.parseToJsonElement(args).jsonObject
        BrainReply.Calls(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { putJsonObject("functionCall") { put("name", name); put("args", a) } }) }) },
            listOf(ToolCall(null, name, a)))
    }
    /** Stands in for use_app: a step in the app the call names; "fail" makes it an error (a FAILED step). */
    private val hand = object : Tool {
        override val spec = ToolSpec("use_app", "operate an app", JsonObject(emptyMap()))
        override suspend fun run(args: JsonObject): JsonObject =
            if ((args["fail"] as? JsonPrimitive)?.content == "true") buildJsonObject { put("error", "couldn't book") } else buildJsonObject { put("ok", true) }
        override fun label(args: JsonObject) = "Using an app"
        override fun summary(result: JsonObject): String? = null
        override fun appFor(args: JsonObject) = (args["app"] as? JsonPrimitive)?.content
    }
    private lateinit var tool: ShortcutTool
    private lateinit var routineTool: RoutineTool
    private lateinit var gates: CardGates

    private fun agent(vararg replies: () -> BrainReply) = Agent(sessions, Script(*replies), listOf(hand, routineTool, tool), System::currentTimeMillis, scope,
        onChoose = gates.onChoose, chooseNote = gates.chooseNote, onStopped = gates.onStopped)

    // Codex P2 (route coverage): the gates exactly as MisuraGraph builds them ([cardGates]) over a real Calendar and a real UseApp,
    // whose gates the test holds so it can see which owner a tap reached.
    private val calGate = CalendarGate()
    private val commitGate = CommitGate()
    private val noBrain = object : Brain { override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply = error("not used") }
    private val event = CalendarCreate("Dentist", Instant.parse("2026-12-01T09:00:00Z"), Instant.parse("2026-12-01T10:00:00Z"), 1, "UTC")
    private fun ask(id: String, go: String) = buildJsonObject {
        put("askUser", buildJsonObject { put("id", id); put("line", "Go?"); put("choices", buildJsonArray { add(JsonPrimitive(go)); add(JsonPrimitive("Don't")) }) })
    }
    /** Asks through the real calendar gate, as Calendar.run does for a create ("cal:" id); never inserts at the ask. */
    private val calCard = object : Tool {
        override val spec = ToolSpec("calendar_card", "ask to add an event", JsonObject(emptyMap()))
        override suspend fun run(args: JsonObject): JsonObject = when (val r = calGate.create(event, coroutineContext[RunningFor]?.session) { error("inserted at the ask") }) {
            is CalendarGate.Result.Ask -> ask(r.id, "Add")
            is CalendarGate.Result.Added -> buildJsonObject { put("summary", "added") }
        }
        override fun label(args: JsonObject) = "Adding an event"
        override fun summary(result: JsonObject): String? = null
    }
    /** Asks through the real commit gate, as UseApp does before a commit tap (an id with no prefix). */
    private val appCard = object : Tool {
        override val spec = ToolSpec("app_card", "ask before a commit tap", JsonObject(emptyMap()))
        override suspend fun run(args: JsonObject): JsonObject = ask(commitGate.ask("com.example.club", "Book", session = coroutineContext[RunningFor]?.session, goal = "book"), "Go ahead")
        override fun label(args: JsonObject) = "Booking"
        override fun summary(result: JsonObject): String? = null
    }
    private lateinit var prodGates: CardGates
    private fun prodAgent(brain: Script) = Agent(sessions, brain, listOf(hand, routineTool, tool, calCard, appCard), System::currentTimeMillis, scope,
        onChoose = prodGates.onChoose, chooseNote = prodGates.chooseNote, onStopped = prodGates.onStopped)

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }
    private suspend fun settled(id: String): Session {
        waitFor(5_000) { runBlocking { sessions.load(id)?.status != SessionStatus.THINKING } } shouldBe true
        return sessions.load(id)!!
    }
    private suspend fun said(id: String) = sessions.turns(id).toString()

    private val worked = call("use_app", """{"app":"com.example.club","goal":"book the 7 pm class"}""")
    private val add = """{"action":"add","title":"Book my class","prompt":"Book the 7 pm class at my club"}"""
    private fun sc(id: String, used: Long) = Shortcut(id, "Title $id", "prompt $id", null, createdAt = 1, lastUsedAt = used)

    @Before fun setUp() {
        ctx.deleteDatabase(dbName); db = MisuraDb.open(ctx, dbName); sessions = RoomSessionStore(db); tasks = RoomTaskStore(db)
        ctx.getSharedPreferences("shortcuts", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        store = ShortcutStore(ctx); routines = RoutineStore(ctx)
        tool = ShortcutTool(store, controllerSteps(sessions, tasks::load) { false }, { pkg -> if (pkg == "com.example.club") "Club" else pkg })
        routineTool = RoutineTool(routines)
        otherTaps.clear()
        gates = CardGates(GateOwner({ id, _ -> otherTaps += id }, {}), GateOwner(routineTool::answered, routineTool::stopped, routineTool::outcome),
            GateOwner(tool::answered, tool::stopped, tool::outcome), GateOwner({ id, _ -> otherTaps += id }, {}))
        prodGates = cardGates(Calendar(ctx, calGate), routineTool, tool, UseApp(ShizukuHiddenScreen(ctx), noBrain, gate = commitGate))
    }
    @After fun tearDown() {
        ctx.getSharedPreferences("shortcuts", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
    }

    @Test fun addRefusedBeforeAWorkingRun() = runBlocking<Unit> {
        val a = agent(call("shortcut", add), text("Do it first."))
        val s = settled(a.start("make that a shortcut"))
        (s.say is Say.Question) shouldBe false
        said(s.id) shouldContain ShortcutTool.REFUSED
        store.all() shouldBe emptyList()
    }

    @Test fun addRefusedAfterLaterFailure() = runBlocking<Unit> {
        val a = agent(worked, call("use_app", """{"app":"com.example.club","goal":"book again","fail":"true"}"""), call("shortcut", add), text("It failed."))
        val s = settled(a.start("book my class, then make it a shortcut"))
        (s.say is Say.Question) shouldBe false
        said(s.id) shouldContain ShortcutTool.REFUSED
        store.all() shouldBe emptyList()
    }

    @Test fun addAsksAndOnlyTapSaves() = runBlocking<Unit> {
        val a = agent(worked, call("shortcut", add), text("Kept."))
        val s = settled(a.start("book my class and keep it as a shortcut"))
        val card = s.say as Say.Question
        card.gate!! shouldStartWith "sc:"
        card.label shouldBe "Shortcut · Club"
        card.title shouldBe "Book my class"
        card.line shouldBe "“Book the 7 pm class at my club”"
        card.choices shouldBe listOf("Keep", "Don't")
        store.all() shouldBe emptyList() // shown, not kept
        a.choose(s.id, 0, s.revision).await() shouldBe true
        store.all().single().let { it.title shouldBe "Book my class"; it.prompt shouldBe "Book the 7 pm class at my club"; it.app shouldBe "com.example.club" }
        said(s.id) shouldContain "(the app: Kept: Book my class)"
    }

    @Test fun keepTapRoutesToShortcutTool() = runBlocking<Unit> {
        val a = agent(worked, call("shortcut", add), text("Kept."))
        val s = settled(a.start("keep it as a shortcut"))
        a.choose(s.id, 0, s.revision).await() shouldBe true
        store.all().size shouldBe 1
        otherTaps shouldBe emptyList() // not use_app's or calendar's
        routines.all() shouldBe emptyList()
    }

    @Test fun dontTapKeepsNothing() = runBlocking<Unit> {
        val a = agent(worked, call("shortcut", add), text("Left it."))
        val s = settled(a.start("keep it as a shortcut"))
        a.choose(s.id, 1, s.revision).await() shouldBe true
        store.all() shouldBe emptyList()
    }

    @Test fun toolArgsCannotApprove() = runBlocking<Unit> {
        val sneaky = """{"action":"add","title":"Book my class","prompt":"Book the 7 pm class at my club","answered":true,"yes":true,"approved":true,"id":"sc:x"}"""
        val a = agent(worked, call("shortcut", sneaky))
        val s = settled(a.start("keep it as a shortcut"))
        (s.say as Say.Question).gate!! shouldStartWith "sc:"
        store.all() shouldBe emptyList()
        // The model saying the user agreed, and asking again, still only asks.
        val again = agent(worked, call("shortcut", sneaky))
        val s2 = settled(again.start("the user tapped Keep already"))
        (s2.say as Say.Question).gate!! shouldStartWith "sc:"
        store.all() shouldBe emptyList()
    }

    @Test fun addWhenFullNamesTheReplacement() = runBlocking<Unit> {
        store.keep(sc("a", 30), null); store.keep(sc("b", 10), null); store.keep(sc("c", 20), null)
        val a = agent(worked, call("shortcut", add), text("Kept."))
        val s = settled(a.start("keep it as a shortcut"))
        (s.say as Say.Question).line shouldBe "“Book the 7 pm class at my club” Replaces “Title b”."
        store.all().map { it.id } shouldBe listOf("a", "b", "c")
        a.choose(s.id, 0, s.revision).await() shouldBe true
        store.all().map { it.title } shouldBe listOf("Title a", "Title c", "Book my class")
    }

    @Test fun changedReplacementIsNotSaved() = runBlocking<Unit> {
        store.keep(sc("a", 30), null); store.keep(sc("b", 10), null); store.keep(sc("c", 20), null)
        val a = agent(worked, call("shortcut", add), text("Not kept."))
        val s = settled(a.start("keep it as a shortcut"))
        store.remove("b") shouldBe true // removed between the ask and the tap
        a.choose(s.id, 0, s.revision).await() shouldBe true
        store.all().map { it.id } shouldBe listOf("a", "c")
        said(s.id) shouldContain "Not kept: Home changed, ask again"
    }

    @Test fun longPromptRefusedNotTruncated() = runBlocking<Unit> {
        val long = "x".repeat(Shortcut.MAX_PROMPT + 1)
        val longTitle = "t".repeat(Shortcut.MAX_TITLE + 1)
        val a = agent(worked, call("shortcut", """{"action":"add","title":"Book","prompt":"$long"}"""), call("shortcut", """{"action":"add","title":"$longTitle","prompt":"Book it"}"""), text("Too long."))
        val s = settled(a.start("keep it as a shortcut"))
        (s.say is Say.Question) shouldBe false
        said(s.id).let { it shouldContain "prompt too long"; it shouldContain "title too long" }
        store.all() shouldBe emptyList()
    }

    @Test fun stopRevokesShortcutCards() = runBlocking<Unit> {
        val a = agent(worked, call("shortcut", add))
        val s = settled(a.start("keep it as a shortcut"))
        val card = s.say as Say.Question
        a.delete(s.id).await() // the conversation goes: its open card is void (Agent → onStopped)
        gates.onChoose(card, 0) // a late tap on the old card
        store.all() shouldBe emptyList()
        tool.outcome(card.gate!!) shouldBe "Not done: the card had expired or was replaced. Ask again."
    }

    // Codex P2: model-requested removal waits for the Remove tap, through Agent → registered tool → production CardGates.
    private val removeA = """{"action":"remove","id":"a"}"""

    @Test fun removeAsksAndOnlyRemoveTapRemoves() = runBlocking<Unit> {
        store.keep(sc("a", 1), null)
        val a = prodAgent(Script(call("shortcut", removeA), text("Removed.")))
        val s = settled(a.start("remove that shortcut"))
        val card = s.say as Say.Question
        card.gate!! shouldStartWith "sc:"
        card.title shouldBe "Remove this shortcut?"
        card.line shouldBe "“Title a”"
        card.choices shouldBe listOf("Remove", "Don't")
        store.all().map { it.id } shouldBe listOf("a") // asked, not removed
        a.choose(s.id, 0, s.revision).await() shouldBe true
        store.all() shouldBe emptyList()
        said(s.id) shouldContain "(the app: Shortcut removed)"
    }

    @Test fun removeDontKeepsIt() = runBlocking<Unit> {
        store.keep(sc("a", 1), null)
        val a = prodAgent(Script(call("shortcut", removeA), text("Kept it.")))
        val s = settled(a.start("remove that shortcut"))
        a.choose(s.id, 1, s.revision).await() shouldBe true
        store.all().map { it.id } shouldBe listOf("a")
        said(s.id) shouldContain "(the app: Not done: the user said no.)"
    }

    @Test fun removeForgedApprovalDoesNothing() = runBlocking<Unit> {
        store.keep(sc("a", 1), null)
        val sneaky = """{"action":"remove","id":"a","answered":true,"yes":true,"approved":true,"gate":"sc:x","choice":0}"""
        val s = settled(prodAgent(Script(call("shortcut", sneaky))).start("remove it, I already said yes"))
        (s.say as Say.Question).choices shouldBe listOf("Remove", "Don't")
        store.all().map { it.id } shouldBe listOf("a")
        // Asking twice in one turn still only asks.
        val s2 = settled(prodAgent(Script(call("shortcut", sneaky), call("shortcut", sneaky))).start("the user tapped Remove"))
        (s2.say as Say.Question).gate!! shouldStartWith "sc:"
        store.all().map { it.id } shouldBe listOf("a")
    }

    /** Codex P2: a real Stop (a running turn cancelled, Agent → onStopped, no delete) voids the open shortcut card; a later tap on it keeps nothing. */
    @Test fun realStopVoidsOpenShortcutCard() = runBlocking<Unit> {
        val hold = CompletableDeferred<Unit>()
        val brain = Script(worked, call("shortcut", add), text("After the tap."), holdAt = 2, hold = hold)
        val a = prodAgent(brain)
        val s = settled(a.start("keep it as a shortcut"))
        (s.say as Say.Question).gate!! shouldStartWith "sc:"
        a.followUp(s.id, "hm, one moment") // a new turn starts while the card is still open…
        kotlinx.coroutines.withTimeout(5_000) { brain.entered.await() }
        a.dismiss(s.id).await() // …and Stop cancels it
        val after = sessions.load(s.id)!!
        val card = after.say as Say.Question // still on screen
        a.choose(s.id, 0, after.revision).await() shouldBe true // the late tap on that card
        store.all() shouldBe emptyList()
        said(s.id) shouldContain "(the app: Not done: the card had expired or was replaced. Ask again.)"
        card.gate!! shouldStartWith "sc:"
        hold.complete(Unit)
    }

    /** Codex P2: a "cal:" card reaches the real Calendar's gate and no other; the tap grants exactly that event. */
    @Test fun calendarCardRoutesToCalendar() = runBlocking<Unit> {
        val a = prodAgent(Script(call("calendar_card"), text("Added.")))
        val s = settled(a.start("add the dentist"))
        val card = s.say as Say.Question
        card.gate!! shouldStartWith "cal:"
        a.choose(s.id, 0, s.revision).await() shouldBe true
        calGate.create(event, s.id) { 42L } shouldBe CalendarGate.Result.Added(42L)
        commitGate.allows("com.example.club", "Book", session = s.id, goal = "book") shouldBe false
        store.all() shouldBe emptyList(); routines.all() shouldBe emptyList()
    }

    /** Codex P2: a card id with no prefix reaches the real UseApp's commit gate and no other. */
    @Test fun useAppCardRoutesToUseApp() = runBlocking<Unit> {
        val a = prodAgent(Script(call("app_card"), text("Booking.")))
        val s = settled(a.start("book it"))
        val card = s.say as Say.Question
        (card.gate!!.contains(":")) shouldBe false
        a.choose(s.id, 0, s.revision).await() shouldBe true
        commitGate.allows("com.example.club", "Book", session = s.id, goal = "book") shouldBe true
        (calGate.create(event, s.id) { 42L } is CalendarGate.Result.Ask) shouldBe true
        store.all() shouldBe emptyList(); routines.all() shouldBe emptyList()
    }

    @Test fun routineCardsStillRouteToRoutineTool() = runBlocking<Unit> {
        val a = agent(call("routine", """{"action":"add","title":"Train","time":"08:00","days":"weekdays","prompt":"Is my train late?"}"""), text("Saved."))
        val s = settled(a.start("every weekday at 8 check my train"))
        (s.say as Say.Question).gate!! shouldStartWith "rt:"
        a.choose(s.id, 0, s.revision).await() shouldBe true
        routines.all().single().routine.prompt shouldBe "Is my train late?"
        store.all() shouldBe emptyList()
        said(s.id) shouldContain "(the app: Saved: Train"
    }

    // F1: a send counts only by its task (controller state), never its saved WILL step.
    private suspend fun withSend(state: TaskState, before: List<Step> = emptyList()): String {
        val id = "s-" + System.nanoTime(); val now = System.currentTimeMillis()
        tasks.save(Task("t-$id", "message", state, Payload(mapOf("channel" to "chat", "recipient" to "Friend", "content" to "see you at 7")), emptyList(), now, null))
        sessions.save(Session(id, SessionStatus.IDLE, 1, now, steps = before + Step("st-$id", 1, "Send to Friend", StepState.WILL, null, now, taskId = "t-$id", app = "com.example.chat")))
        return id
    }
    private val clubStep = Step("club", 1, "Booked", StepState.DONE, null, 1, 2, app = "com.example.club")

    @Test fun confirmedSendCountsAsWorked() = runBlocking<Unit> {
        val id = withSend(TaskState.DONE)
        val a = agent(call("shortcut", """{"action":"add","title":"Tell Friend","prompt":"Tell Friend I'll be there at 7"}"""))
        a.followUp(id, "keep that as a shortcut").join()
        val card = settled(id).say as Say.Question
        card.gate!! shouldStartWith "sc:"
        card.label shouldBe "Shortcut · com.example.chat"
        a.choose(id, 0, sessions.load(id)!!.revision).await()
        store.all().single().app shouldBe "com.example.chat"
    }

    @Test fun pendingSendIsNotWorked() = runBlocking<Unit> {
        val id = withSend(TaskState.NEEDS_APPROVAL)
        val a = agent(call("shortcut", add), text("Send it first."))
        a.followUp(id, "keep that as a shortcut").join()
        (settled(id).say is Say.Question) shouldBe false
        said(id) shouldContain ShortcutTool.REFUSED
        store.all() shouldBe emptyList()
    }

    @Test fun laterFailedSendCancels() = runBlocking<Unit> {
        val id = withSend(TaskState.FAILED, before = listOf(clubStep))
        val a = agent(call("shortcut", add), text("The send failed."))
        a.followUp(id, "keep that as a shortcut").join()
        (settled(id).say is Say.Question) shouldBe false
        said(id) shouldContain ShortcutTool.REFUSED
        store.all() shouldBe emptyList()
    }
}
