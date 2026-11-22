package com.misura.phone.notify

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.exec.HandResult
import com.misura.core.task.Payload
import com.misura.phone.hands.NotificationReplyHand
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Needs :testapp installed. Drives it through its POST broadcast. */
@RunWith(AndroidJUnit4::class)
class ReplyHandTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val hand = NotificationReplyHand({ MisuraListener.instance }, channel = "testchat", pkg = TESTAPP)

    // Read the output so the command has finished before the next line; a bare close() let `am start` land in later tests.
    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

    private suspend fun post(conv: String, sender: String, text: String, mode: String = "normal", seedSelf: String? = null, shortcut: String? = null) {
        ctx.sendBroadcast(Intent("com.misura.testapp.POST").setPackage(TESTAPP)
            .putExtra("conv", conv).putExtra("sender", sender).putExtra("text", text).putExtra("mode", mode)
            .apply { seedSelf?.let { putExtra("seedSelf", it) }; shortcut?.let { putExtra("shortcut", it) } })
        waitFor { MisuraListener.instance?.liveFrom(TESTAPP)?.any { extract(it).msgs.any { m -> m.text == text } } == true }
    }

    private suspend fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out" }; delay(100) }
    }

    private fun payload(conv: String, text: String) = Payload(mapOf("channel" to "testchat", "recipient" to conv, "content" to text))

    @Before fun setUp() = runBlocking<Unit> {
        com.misura.phone.notify.NotificationAccess(ctx).setBlocked(TESTAPP, false)
        shell("pm grant $TESTAPP android.permission.POST_NOTIFICATIONS")
        // A freshly installed app is "stopped" and receives no broadcasts until it is launched once.
        shell("am start -W -n $TESTAPP/.ChatActivity"); shell("input keyevent KEYCODE_HOME")
        shell("cmd notification allow_listener ${ctx.packageName}/com.misura.phone.notify.MisuraListener")
        waitFor { MisuraListener.instance != null }
    }

    @Test fun replyReachesTestappWithExactUnicodeText() = runBlocking<Unit> {
        post("friend1", "FriendB", "see you at 7:30")
        val t0 = System.currentTimeMillis()
        hand.commit("a1", payload("FriendB", "Grüße 👋")).shouldBeInstanceOf<HandResult.Uncertain>()
        waitFor { runBlocking { hand.check("a1", payload("FriendB", "Grüße 👋"), t0) } is HandResult.Ok }
    }

    @Test fun authRequiredGivesUnavailable() = runBlocking<Unit> {
        post("friend2", "ColleagueA", "hey", mode = "auth")
        hand.commit("a2", payload("ColleagueA", "hi")).shouldBeInstanceOf<HandResult.Unavailable>()
    }

    @Test fun cancelledIntentGivesUnavailable() = runBlocking<Unit> {
        post("friend3", "ColleagueB", "hey", mode = "cancelled")
        hand.commit("a3", payload("ColleagueB", "hi")).shouldBeInstanceOf<HandResult.Unavailable>()
    }

    @Test fun missingNotificationGivesUnavailable() = runBlocking<Unit> {
        hand.commit("a4", payload("nobody", "hi")).shouldBeInstanceOf<HandResult.Unavailable>()
    }

    @Test fun checkIgnoresIdenticalOlderMessage() = runBlocking<Unit> {
        post("friend5", "Guest", "yo", seedSelf = "hi")
        delay(50)
        hand.check("a5", payload("Guest", "hi"), System.currentTimeMillis()).shouldBeInstanceOf<HandResult.Uncertain>()
    }

    @Test fun checkConfirmsNewSelfMessage() = runBlocking<Unit> {
        post("friend6", "NeighborB", "coffee?")
        val t0 = System.currentTimeMillis()
        hand.commit("a6", payload("NeighborB", "yes!"))
        waitFor { runBlocking { hand.check("a6", payload("NeighborB", "yes!"), t0) } is HandResult.Ok }
        hand.check("a6", payload("NeighborB", "yes!"), t0) shouldBe HandResult.Ok("self message \"yes!\" in NeighborB")
    }

    @Test fun genericReplyUsesPayloadPackageAndExactChatId() = runBlocking<Unit> {
        post("generic", "NeighborA", "generic hello")
        val chat = MisuraListener.instance!!.chatsAll().single { it.pkg == TESTAPP && it.name == "NeighborA" }
        val generic = NotificationReplyHand({ MisuraListener.instance })
        generic.channel shouldBe "reply"
        val p = Payload(mapOf("channel" to "reply", "pkg" to chat.pkg, "recipient" to chat.key, "name" to chat.name, "content" to "generic reply"))
        val t0 = System.currentTimeMillis()
        generic.commit("generic", p).shouldBeInstanceOf<HandResult.Uncertain>()
        waitFor { runBlocking { generic.check("generic", p, t0) } is HandResult.Ok }
        val wrongId = Payload(p.fields + ("recipient" to chat.name))
        generic.commit("wrong-id", wrongId).shouldBeInstanceOf<HandResult.Unavailable>()
        val wrongApp = Payload(p.fields + ("pkg" to "com.misura.missing"))
        generic.commit("wrong-app", wrongApp).shouldBeInstanceOf<HandResult.Unavailable>()
    }

    // ---- Protected apps (SYSTEM §5 M1), seeded as raw preferences so they run unchanged on the prior source. ----
    private val access get() = ctx.getSharedPreferences("app_access", android.content.Context.MODE_PRIVATE)
    @After fun clearAccess() { access.edit().clear().commit() }

    /** A reply card made before the app was blocked is refused before dispatch (terminal, no fallback), and check never claims a result. */
    @Test fun aReplyDraftedBeforeBlockingIsNotSent() = runBlocking<Unit> {
        post("blockedconv", "FriendC", "are you there")
        check(access.edit().putBoolean(TESTAPP, false).commit())
        val t0 = System.currentTimeMillis()
        hand.commit("blocked", payload("FriendC", "must not send")).shouldBeInstanceOf<HandResult.Failed>().dispatched shouldBe false
        val generic = NotificationReplyHand({ MisuraListener.instance })
        generic.commit("blocked-generic", Payload(mapOf("channel" to "reply", "pkg" to TESTAPP, "recipient" to "FriendC", "content" to "must not send")))
            .shouldBeInstanceOf<HandResult.Failed>().dispatched shouldBe false
        hand.check("blocked", payload("FriendC", "must not send"), t0).shouldBeInstanceOf<HandResult.Uncertain>()
    }

    /** A blocked app's notifications reach neither read_notifications nor chat discovery. */
    @Test fun aBlockedAppIsNotReadOrOffered() = runBlocking<Unit> {
        post("readconv", "FriendD", "secret code 1234")
        check(access.edit().putBoolean(TESTAPP, false).commit())
        val all = ReadNotifications(ctx).run(kotlinx.serialization.json.buildJsonObject {})
        check(TESTAPP !in all.toString()) { all.toString() }
        val asked = ReadNotifications(ctx).run(kotlinx.serialization.json.buildJsonObject { put("app", kotlinx.serialization.json.JsonPrimitive(TESTAPP)) })
        check("blocked" in asked["error"].toString()) { asked.toString() }
        MisuraListener.instance!!.chatsAll().none { it.pkg == TESTAPP } shouldBe true
    }

    private fun db() = androidx.room.Room.inMemoryDatabaseBuilder(ctx, com.misura.phone.store.MisuraDb::class.java).build()
    /** A second hand on the same channel: it must never run after a policy refusal. */
    private class CountingFallback : com.misura.core.exec.Hand {
        val commits = java.util.concurrent.atomic.AtomicInteger()
        override val id = "fallback"; override val channel = "testchat"; override val lock: String? = null
        override suspend fun prepare(p: Payload) = HandResult.Prepared(p)
        override suspend fun commit(attemptId: String, p: Payload): HandResult { commits.incrementAndGet(); return HandResult.Ok("fallback sent") }
        override suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long) = HandResult.Uncertain("fallback")
        override suspend fun cancel(attemptId: String) = HandResult.Unavailable("no")
    }

    /** The real route: draft → card → approval → Controller. Blocked between draft and tap: the task fails, no fallback hand runs, nothing is sent. */
    @Test fun aCardDraftedBeforeBlockingFailsThroughTheControllerWithoutFallback() = runBlocking<Unit> {
        post("ctlconv", "FriendE", "lunch today?")
        val db = db(); val tasks = com.misura.phone.store.RoomTaskStore(db)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val fallback = CountingFallback()
        val c = com.misura.core.exec.Controller(tasks, listOf(hand, fallback), System::currentTimeMillis, scope)
        val drafter = com.misura.phone.hands.ChatDrafter({ MisuraListener.instance!!.chatsAll() }, { it }, tasks, c, System::currentTimeMillis, { MisuraListener.instance!!.chatPackages() })
        val d = drafter.draft("FriendE", "must not send").shouldBeInstanceOf<com.misura.core.agent.Draft.Ready>()
        check(access.edit().putBoolean(TESTAPP, false).commit())
        c.approve(d.taskId, tasks.load(d.taskId)!!.payload!!.digest())!!
        c.execute(d.taskId)
        try { waitFor(5_000) { runBlocking { tasks.load(d.taskId)!!.state } == com.misura.core.task.TaskState.FAILED } }
        finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); db.close() }
        fallback.commits.get() shouldBe 0
    }

    /** The real route: draft for one person → the same chat id now shows someone else → approval → Controller. Fails before dispatch, no fallback, nothing sent. */
    @Test fun aChatWhoseNameChangedAfterApprovalIsNotSent() = runBlocking<Unit> {
        val conv = "renamed-" + System.nanoTime(); val friend = "FriendF" + System.nanoTime() % 100000
        post(conv, friend, "are you coming? $conv", shortcut = conv) // the app's own chat id, like WhatsApp's: stays when the name changes
        val db = db(); val tasks = com.misura.phone.store.RoomTaskStore(db)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val fallback = CountingFallback()
        val c = com.misura.core.exec.Controller(tasks, listOf(hand, fallback), System::currentTimeMillis, scope)
        val drafter = com.misura.phone.hands.ChatDrafter({ MisuraListener.instance!!.chatsAll() }, { it }, tasks, c, System::currentTimeMillis, { MisuraListener.instance!!.chatPackages() })
        val d = drafter.draft(friend, "must not reach a stranger").shouldBeInstanceOf<com.misura.core.agent.Draft.Ready>()
        val approved = tasks.load(d.taskId)!!.payload!!
        post(conv, "Stranger", "hello from someone else $conv", shortcut = conv) // same conversation id, another person now
        val t0 = System.currentTimeMillis()
        c.approve(d.taskId, approved.digest())!!
        c.execute(d.taskId)
        try { waitFor(5_000) { runBlocking { tasks.load(d.taskId)!!.state }.let { it == com.misura.core.task.TaskState.FAILED || it == com.misura.core.task.TaskState.DONE || it == com.misura.core.task.TaskState.OUTCOME_UNKNOWN || it == com.misura.core.task.TaskState.CHECKING } } }
        finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
        tasks.load(d.taskId)!!.state shouldBe com.misura.core.task.TaskState.FAILED
        db.close()
        fallback.commits.get() shouldBe 0
        delay(1_500)
        hand.check("renamed-check", approved, t0).shouldBeInstanceOf<HandResult.Uncertain>() // no self message arrived
    }

    /** After a possible send, a chat that now shows someone else never confirms it from a matching own message: the outcome stays unknown. */
    @Test fun aRenamedChatNeverConfirmsASend() = runBlocking<Unit> {
        val conv = "confirm-" + System.nanoTime(); val friend = "FriendH" + System.nanoTime() % 100000
        post(conv, friend, "ping $conv", shortcut = conv)
        val p = Payload(mapOf("channel" to "testchat", "recipient" to conv, "name" to friend, "content" to "pong $conv"))
        val t0 = System.currentTimeMillis()
        hand.commit("confirm", p).shouldBeInstanceOf<HandResult.Uncertain>()
        waitFor { MisuraListener.instance?.liveFrom(TESTAPP)?.any { extract(it).msgs.any { m -> m.senderIsSelf && m.text == "pong $conv" } } == true }
        post(conv, "Stranger", "renamed $conv", shortcut = conv)
        hand.check("confirm", p, t0).shouldBeInstanceOf<HandResult.Uncertain>()
    }

    /** Recovery after a possible send in a now-blocked app: never looks again, so the outcome stays unknown instead of being confirmed by reading the app. */
    @Test fun recoveryNeverLooksIntoABlockedApp() = runBlocking<Unit> {
        post("recconv", "FriendF", "hello")
        val p = payload("FriendF", "sent before the block")
        val t0 = System.currentTimeMillis()
        hand.commit("rec", p).shouldBeInstanceOf<HandResult.Uncertain>()
        waitFor { runBlocking { hand.check("rec", p, t0) } is HandResult.Ok }   // it did arrive: an unblocked check would confirm it
        val db = db(); val tasks = com.misura.phone.store.RoomTaskStore(db)
        tasks.save(com.misura.core.task.Task("r1", "message", com.misura.core.task.TaskState.EXECUTING, p, emptyList(), 0, null))
        tasks.updateAttempt(com.misura.core.exec.Attempt("rec", "r1", hand.id, "ap", p.digest(), com.misura.core.exec.AttemptStatus.DISPATCHING, t0, null, null))
        check(access.edit().putBoolean(TESTAPP, false).commit())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val c = com.misura.core.exec.Controller(tasks, listOf(hand), System::currentTimeMillis, scope, reconcileDelays = listOf(50L))
        try { c.recover(); delay(1_500); tasks.load("r1")!!.state shouldNotBe com.misura.core.task.TaskState.DONE }
        finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); db.close() }
    }

    /** A blocked app's new notifications are not captured (no callback into the inbox router). */
    @Test fun aBlockedAppIsNotCaptured() = runBlocking<Unit> {
        check(access.edit().putBoolean(TESTAPP, false).commit())
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val before = MisuraListener.onCaptured
        MisuraListener.onCaptured = { sbn, _ -> seen += sbn.packageName }
        try {
            ctx.sendBroadcast(Intent("com.misura.testapp.POST").setPackage(TESTAPP).putExtra("conv", "capconv").putExtra("sender", "FriendG").putExtra("text", "code 9876").putExtra("mode", "normal"))
            delay(2_500)
            (TESTAPP in seen) shouldBe false
            // Control: unblocked, the same kind of post is captured, so the silence above was the block.
            access.edit().clear().commit()
            ctx.sendBroadcast(Intent("com.misura.testapp.POST").setPackage(TESTAPP).putExtra("conv", "capconv2").putExtra("sender", "FriendG").putExtra("text", "control").putExtra("mode", "normal"))
            waitFor { TESTAPP in seen }
        } finally { MisuraListener.onCaptured = before }
    }

    companion object { const val TESTAPP = "com.misura.testapp" }
}
