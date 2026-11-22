package com.misura.phone.routine

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Agent
import com.misura.core.agent.Brain
import com.misura.core.agent.BrainReply
import com.misura.core.agent.Say
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolCall
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import com.misura.core.routine.Routine
import com.misura.core.routine.Trigger
import com.misura.phone.notify.SessionAlerts
import com.misura.phone.store.MisuraDb
import com.misura.phone.store.RoomSessionStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Routines through the production route: Agent → routine tool → Save card → store → alarm; alarm tick → claimed run → denied tools → result alert. */
class RoutinesDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val dbName = "routines-test.db"
    private lateinit var db: MisuraDb
    private lateinit var sessions: RoomSessionStore
    private lateinit var store: RoutineStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Script(vararg replies: () -> BrainReply) : Brain {
        private val q = ArrayDeque(replies.toList())
        override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply = q.removeFirst()()
    }
    private fun text(t: String) = { BrainReply.Text(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { put("text", t) }) }) }, t) }
    private fun call(name: String, args: String = "{}") = {
        val a = Json.parseToJsonElement(args).jsonObject
        BrainReply.Calls(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { putJsonObject("functionCall") { put("name", name); put("args", a) } }) }) },
            listOf(ToolCall(null, name, a)))
    }
    /** Stands in for use_app: counts calls that got through. */
    private val hand = object : Tool {
        val runs = AtomicInteger()
        override val spec = ToolSpec("use_app", "operate an app", JsonObject(emptyMap()))
        override suspend fun run(args: JsonObject): JsonObject { runs.incrementAndGet(); return buildJsonObject { put("ok", true) } }
        override fun label(args: JsonObject) = "Using an app"
        override fun summary(result: JsonObject): String? = null
    }
    private val starts = AtomicInteger()
    private fun agent(tool: RoutineTool, vararg replies: () -> BrainReply): Agent {
        val (alerts, allSent) = SessionAlerts.hooks(ctx, sessions, scope)
        lateinit var a: Agent
        a = Agent(sessions, Script(*replies), listOf(hand, tool), System::currentTimeMillis, scope,
            onChoose = { q, i -> q.gate?.let { if (it.startsWith("rt:")) tool.answered(it, yes = i == 0) } },
            onSettled = { s -> alerts(s); Routines.settled(s) }, onAllSent = allSent)
        Routines.init(ctx, start = { t, deny -> starts.incrementAndGet(); a.start(t, deny) }, store = store)
        return a
    }
    private fun shell(cmd: String) = String(inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() })
    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
        return cond()
    }
    private fun alarmArmed() = shell("dumpsys alarm").lines().any { "com.misura.phone" in it && "ROUTINE_TICK" in it }
    private fun due(minutesAgo: Long, prompt: String = "Is my train late?"): Routine {
        // Due [minutesAgo] (the trigger's time is that minute today); no cooldown, so only the claim can stop a second run (Codex P2).
        val at = java.time.ZonedDateTime.now().minusMinutes(minutesAgo).withSecond(0).withNano(0)
        val r = Routine("r-" + System.nanoTime(), "Train", Trigger.Time(at.hour, at.minute), prompt, com.misura.core.routine.Conditions(cooldownMin = 0))
        store.put(Saved(r, nextAt = at.toInstant().toEpochMilli()))
        return r
    }

    @Before fun setUp() {
        ctx.deleteDatabase(dbName); db = MisuraDb.open(ctx, dbName); sessions = RoomSessionStore(db)
        ctx.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        store = RoutineStore(ctx)
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        SessionAlerts.front(ctx, false); SessionAlerts.clearAll(ctx)
    }
    @After fun tearDown() {
        ctx.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { Routines.reconcile() } // cancels the alarm: nothing stored
        SessionAlerts.clearAll(ctx); db.close()
    }

    @Test fun aRoutineIsSavedOnlyByTheSaveTapAndArmsTheAlarm() = runBlocking<Unit> {
        val add = """{"action":"add","title":"Train","time":"08:00","days":"weekdays","prompt":"Is my train late?"}"""
        val tool = RoutineTool(store, changed = { Routines.resync() })
        val a = agent(tool, call("routine", add), text("Saved.")) // after the tap the model only confirms (as on the phone, 22 Nov)
        val id = a.start("every weekday at 8 check if my train is late")
        waitFor(5_000) { runBlocking { sessions.load(id)?.status == SessionStatus.IDLE } } shouldBe true
        val card = sessions.load(id)!!.say as Say.Question
        (card.gate!!.startsWith("rt:")) shouldBe true
        card.label shouldBe "Routine · weekdays at 08:00"
        store.all() shouldBe emptyList() // shown, not saved
        a.choose(id, 0, sessions.load(id)!!.revision).await() shouldBe true
        waitFor(5_000) { store.all().size == 1 } shouldBe true
        store.all().single().let { it.routine.prompt shouldBe "Is my train late?"; (it.nextAt!! > System.currentTimeMillis()) shouldBe true }
        waitFor(3_000) { alarmArmed() } shouldBe true
    }

    @Test fun aDueRoutineRunsOnceWithoutAppControlAndItsResultArrives() = runBlocking<Unit> {
        val tool = RoutineTool(store)
        agent(tool, call("use_app", """{"app":"com.android.chrome","goal":"check trains"}"""), text("Your train is on time."))
        val r = due(minutesAgo = 1)
        val occurrence = store.all().single().nextAt
        Routines.tick()
        val session = waitFor(5_000) { store.all().single().routine.lastSession != null }.let { store.all().single().routine.lastSession!! }
        waitFor(8_000) { nm.activeNotifications.any { it.tag == SessionAlerts.resultTag(session) } } shouldBe true
        hand.runs.get() shouldBe 0 // use_app refused inside a routine run
        sessions.turns(session).toString().contains("tool unavailable this turn") shouldBe true
        store.all().single().lastOccurrence shouldBe occurrence // claimed
        // The same occurrence again (a repeated alarm): already claimed, never a second run.
        store.update(r.id) { it.copy(nextAt = occurrence) }.let { Routines.tick() }
        Thread.sleep(1_000)
        starts.get() shouldBe 1
        store.all().single().routine.lastSession shouldBe session
        r.id shouldBe store.all().single().routine.id
    }

    @Test fun aLateOccurrenceIsSkippedNotRun() = runBlocking<Unit> {
        val tool = RoutineTool(store)
        agent(tool, text("must not run"))
        due(minutesAgo = 20)
        Routines.tick()
        Thread.sleep(1_000)
        store.all().single().let { it.routine.lastSession shouldBe null; (it.nextAt!! > System.currentTimeMillis()) shouldBe true }
    }
}
