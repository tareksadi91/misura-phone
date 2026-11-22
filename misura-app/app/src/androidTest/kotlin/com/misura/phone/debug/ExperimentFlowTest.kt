package com.misura.phone.debug

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.task.TaskState
import com.misura.phone.MisuraGraph
import com.misura.phone.notify.AnswerReady
import com.misura.phone.notify.MisuraListener
import com.misura.phone.notify.extract
import com.misura.phone.notify.isConversation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** testapp plays both the bot (answers in a 3-message burst) and the friend. */
@RunWith(AndroidJUnit4::class)
class ExperimentFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    /** Waits for the command to finish (closing the pipe early lets commands race). */
    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

    private suspend fun waitFor(ms: Long, what: String, cond: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out: $what" }; delay(200) }
    }

    private suspend fun post(conv: String, sender: String, text: String, autoAnswer: Int = 0, shortcut: String? = null) {
        ctx.sendBroadcast(Intent("com.misura.testapp.POST").setPackage(TESTAPP)
            .putExtra("conv", conv).putExtra("sender", sender).putExtra("text", text).putExtra("autoAnswer", autoAnswer).putExtra("shortcut", shortcut))
        waitFor(10_000, "testapp posted $text") { MisuraListener.instance?.liveFrom(TESTAPP)?.any { extract(it).msgs.any { m -> m.text == text } } == true }
    }

    @Before fun setUp() = runBlocking<Unit> {
        com.misura.phone.notify.NotificationAccess(ctx).setBlocked(TESTAPP, false)
        shell("am force-stop $TESTAPP") // clears its chats and notifications
        shell("pm grant $TESTAPP android.permission.POST_NOTIFICATIONS")
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        shell("am start -W -n $TESTAPP/.ChatActivity"); shell("input keyevent KEYCODE_HOME")
        shell("cmd notification allow_listener ${ctx.packageName}/com.misura.phone.notify.MisuraListener")
        waitFor(10_000, "listener") { MisuraListener.instance != null }
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        // Leftover asks (from manual testing) would hold the Bot conversation for 30 minutes.
        MisuraGraph.inbox.openExpectations(System.currentTimeMillis()).forEach { MisuraGraph.inbox.closeExpectation(it.requestId) }
    }

    @Test fun fullFlowWithTestappAsBotAndFriend() = runBlocking<Unit> {
        val run = System.currentTimeMillis().toString().takeLast(5)
        post("bot", "Bot", "Hi, ask me anything", autoAnswer = 3)
        post("friend", "Friend", "dinner later?")
        val flow = ExperimentFlow(ctx, quietMs = 3_000)

        val id = flow.startAsk("Bot", "Recommend a place for dinner $run")
        flow.ask(id, seen(id))
        waitFor(60_000, "answer ready") { MisuraGraph.tasks.load(id)!!.let { it.buzzed && it.state == TaskState.PLANNING } }
        flow.answer(id).size shouldBe 3
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Posting is asynchronous in the system; the flag is saved first so a crash can't buzz twice.
        waitFor(5_000, "answer-ready notification") { nm.activeNotifications.count { it.notification.channelId == AnswerReady.CHANNEL } == 1 }

        val reply = "Let's go there $run 👋"
        flow.draft(id, "Friend", reply)
        flow.send(id, seen(id))
        waitFor(60_000, "task done") { MisuraGraph.tasks.load(id)!!.state == TaskState.DONE }

        val friendSelf = MisuraListener.instance!!.liveFrom(TESTAPP).map { extract(it) }.filter { it.isConversation("Friend") }
            .flatMap { it.msgs }.count { it.senderIsSelf && it.text == reply }
        friendSelf shouldBe 1
        val t = MisuraGraph.tasks.load(id)!!
        t.history.any { it.to == TaskState.WAITING } shouldBe true
        MisuraGraph.tasks.attemptsFor(id).size shouldBe 2
        nm.activeNotifications.count { it.notification.channelId == AnswerReady.CHANNEL } shouldBe 1
    }

    @Test fun secondAskToABusyBotShowsANoteInsteadOfCrashing() = runBlocking<Unit> {
        post("bot", "Bot", "Hi, ask me anything") // no auto-answer: the first ask stays WAITING
        val flow = ExperimentFlow(ctx, quietMs = 3_000)
        val first = flow.startAsk("Bot", "first ${System.nanoTime()}")
        flow.ask(first, seen(first))
        waitFor(30_000, "first waiting") { MisuraGraph.tasks.load(first)!!.state == TaskState.WAITING }

        val second = flow.startAsk("Bot", "second ${System.nanoTime()}")
        flow.ask(second, seen(second)) // must not throw
        flow.note.value shouldBe "Already waiting on Bot"
        MisuraGraph.tasks.load(second)!!.state shouldBe TaskState.NEEDS_APPROVAL
    }

    /** Real WhatsApp keys chats by their own id (the contact's number), not "app:name". */
    @Test fun aBotWhoseChatHasItsOwnIdStillGetsItsAnswer() = runBlocking<Unit> {
        val run = System.currentTimeMillis().toString().takeLast(5)
        post("bot$run", "Bot$run", "Hi, ask me anything", autoAnswer = 2, shortcut = "4930$run@s.whatsapp.net")
        val bot = MisuraListener.instance!!.chats(TESTAPP).single { it.name == "Bot$run" }
        bot.key shouldBe "4930$run@s.whatsapp.net"
        val flow = ExperimentFlow(ctx, quietMs = 3_000)
        val id = flow.startAsk(bot, "where to eat $run")
        flow.ask(id, seen(id))
        waitFor(60_000, "answer ready") { MisuraGraph.tasks.load(id)!!.let { it.buzzed && it.state == TaskState.PLANNING } }
        flow.answer(id).size shouldBe 2
    }

    /** What the card shows: the stored draft. */
    private suspend fun seen(id: String) = MisuraGraph.tasks.load(id)!!.payload!!.digest()

    /** A channel with no hands: the task stays approved-but-unsent, like a locked phone. */
    private fun unsendable() = ExperimentFlow(ctx, quietMs = 3_000, channel = "nochannel")

    @Test fun askingAgainForTheSameTaskDoesNotCrash() = runBlocking<Unit> {
        val flow = unsendable()
        val id = flow.startAsk("Bot2-" + System.nanoTime(), "again?")
        flow.ask(id, seen(id))
        flow.ask(id, seen(id))
        MisuraGraph.inbox.openExpectations(System.currentTimeMillis()).count { it.taskId == id } shouldBe 1
    }

    @Test fun aWaitingApprovedAskKeepsItsConversation() = runBlocking<Unit> {
        val flow = unsendable()
        val bot = "Bot3-" + System.nanoTime()
        val a = flow.startAsk(bot, "first")
        flow.ask(a, seen(a))
        val b = flow.startAsk(bot, "second")
        flow.ask(b, seen(b))
        flow.note.value shouldBe "Already waiting on $bot"
        MisuraGraph.inbox.openExpectations(System.currentTimeMillis()).any { it.taskId == a } shouldBe true
    }

    @Test fun restartAfterAnswerReadyClosesTheExpectation() = runBlocking<Unit> {
        val id = "crash-" + System.currentTimeMillis()
        val now = System.currentTimeMillis()
        MisuraGraph.tasks.save(com.misura.core.task.Task(id, "experiment", TaskState.PLANNING, null, emptyList(), now, null, buzzed = true))
        MisuraGraph.inbox.addExpectation(com.misura.core.inbox.Expectation(id, "$id:ask", TESTAPP, "$TESTAPP:Bot4", "Bot4", now, now + 60_000)) shouldBe true
        AnswerReady.rearm(ctx)
        waitFor(5_000, "expectation closed") { MisuraGraph.inbox.openExpectations(System.currentTimeMillis()).none { it.taskId == id } }
    }

    @Test fun sendWhileLockedWaitsForUnlockThenSendsOnce() = runBlocking<Unit> {
        val run = System.currentTimeMillis().toString().takeLast(5)
        val friend = "Friend$run"
        post(friend, friend, "free tonight?")
        val flow = ExperimentFlow(ctx, quietMs = 3_000)
        val id = "locked-$run"
        MisuraGraph.tasks.save(com.misura.core.task.Task(id, "experiment", TaskState.PLANNING, null, emptyList(), System.currentTimeMillis(), null))
        val reply = "yes, 8pm $run"
        fun sent() = MisuraListener.instance!!.liveFrom(TESTAPP).map { extract(it) }.filter { it.isConversation(friend) }
            .flatMap { it.msgs }.count { it.senderIsSelf && it.text == reply }
        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        try {
            shell("locksettings set-disabled false"); shell("input keyevent KEYCODE_SLEEP")
            waitFor(5_000, "locked") { keyguard.isKeyguardLocked }
            flow.draft(id, friend, reply)
            flow.send(id, seen(id))
            delay(2_000)
            MisuraGraph.tasks.load(id)!!.state shouldBe TaskState.NEEDS_APPROVAL // approved, nothing claimed
            MisuraGraph.tasks.attemptsFor(id).size shouldBe 0
            sent() shouldBe 0

            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            waitFor(30_000, "sent after unlock") { MisuraGraph.tasks.load(id)!!.state == TaskState.DONE }
            sent() shouldBe 1
            MisuraGraph.tasks.attemptsFor(id).size shouldBe 1
        } finally {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard"); shell("locksettings set-disabled true")
        }
    }

    companion object { const val TESTAPP = "com.misura.testapp" }
}
