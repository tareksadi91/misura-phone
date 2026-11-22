package com.misura.phone.debug

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.misura.phone.cards.CardFrame
import com.misura.phone.cards.CardView
import com.misura.phone.cards.Field
import com.misura.phone.cards.Kind
import com.misura.phone.cards.M
import com.misura.phone.cards.MButton
import com.misura.phone.cards.MisuraSurface
import com.misura.phone.cards.cardFor
import com.misura.core.approval.covers
import com.misura.core.inbox.Expectation
import com.misura.core.inbox.Msg
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.phone.MisuraGraph
import com.misura.phone.notify.AnswerReady
import com.misura.phone.notify.Chat
import com.misura.phone.notify.MisuraListener
import com.misura.phone.cards.OptionRow
import com.misura.phone.cards.T
import com.misura.phone.cards.u
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The Demo 1 experiment: ask a bot, wait for its answer, send a reply to a friend.
 * Buttons are the only approvals; each approves exactly the payload on the card.
 */
class ExperimentFlow(
    private val ctx: Context,
    private val quietMs: Long = AnswerReady.DEFAULT_QUIET_MS,
    private val channel: String = "testchat",
    private val app: String = "com.misura.testapp",
) {
    /** One line for the card: "Waiting for unlock to send", "Approval expired, tap again", ... */
    val note = MutableStateFlow("")
    private val g = MisuraGraph

    /** Testchat without a shortcut id keys chats as "app:name". */
    suspend fun startAsk(bot: String, request: String) = startAsk(Chat("$app:$bot", bot, app), request)

    /** [bot] comes from a live notification, so its key is the app's own conversation id (WhatsApp: the number). */
    suspend fun startAsk(bot: Chat, request: String): String {
        val id = UUID.randomUUID().toString()
        g.tasks.save(Task(id, "experiment", TaskState.PLANNING, null, emptyList(), now(), null))
        g.controller.prepare(id, Payload(mapOf("channel" to channel, "recipient" to bot.key, "name" to bot.name, "content" to request, "expectsReply" to "true")))
        android.util.Log.i("MisuraDebug", "experiment task $id ($channel, ${bot.name})") // for `cmd export --es task <id>`
        return id
    }

    /** Ask button: approve the request, start expecting the answer, send. */
    suspend fun ask(id: String, seenDigest: String) {
        val p = requireNotNull(g.tasks.load(id)?.payload)
        val conv = requireNotNull(p["recipient"])
        val bot = p["name"] ?: conv
        val now = now()
        // Asking again (e.g. after an approval expired while locked) reuses this task's open expectation.
        if (g.inbox.openExpectations(now).none { it.taskId == id }) {
            // A dead task can't hold the conversation forever.
            g.inbox.openExpectations(now).filter { it.app == app && it.conversationKey == conv }
                .filter { !holdsConversation(it.taskId, now) }.forEach { g.inbox.closeExpectation(it.requestId) }
            // One open ask per conversation, or answers would split between tasks.
            if (!g.inbox.addExpectation(Expectation(id, requestId(id), app, conv, null, now, now + WAIT_MS))) { note.value = "Already waiting on $bot"; return }
        }
        AnswerReady.watch(ctx, id, requestId(id), quietMs, now + WAIT_MS)
        send(id, seenDigest)
        val after = g.tasks.load(id)!!.state
        if (after == TaskState.FAILED || after == TaskState.PLANNING) g.inbox.closeExpectation(requestId(id))
    }

    suspend fun answer(id: String): List<Msg> = g.inbox.answer(requestId(id))

    /** Draft a reply to a friend: the card shows exactly this text. */
    suspend fun draft(id: String, friend: String, text: String) = draft(id, Chat("$app:$friend", friend, app), text)

    suspend fun draft(id: String, friend: Chat, text: String) {
        g.controller.prepare(id, Payload(mapOf("channel" to channel, "recipient" to friend.key, "name" to friend.name, "content" to text)))
    }

    /** Send button. The tap is the approval; when locked, nothing is claimed until unlock. */
    suspend fun send(id: String, seenDigest: String) {
        if (g.controller.approve(id, seenDigest) == null) { note.value = "The message changed. Check it and tap again"; return }
        if (!locked()) { note.value = ""; g.controller.execute(id); return }
        note.value = "Waiting for unlock to send"
        ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                c.unregisterReceiver(this)
                g.scope.launch {
                    val t = g.tasks.load(id) ?: return@launch
                    val a = g.tasks.approvalFor(id)
                    if (t.payload != null && a != null && a.covers(id, t.payload!!, now())) { note.value = ""; g.controller.execute(id) }
                    else note.value = "Approval expired, tap again"
                }
            }
        }, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    /** Busy tasks hold their conversation; so does an approved ask waiting for unlock, until its approval expires. */
    private suspend fun holdsConversation(taskId: String, now: Long): Boolean {
        val t = g.tasks.load(taskId) ?: return false
        if (t.state in BUSY) return true
        val a = g.tasks.approvalFor(taskId)
        return t.state == TaskState.NEEDS_APPROVAL && a != null && a.consumedByAttempt == null && now <= a.expiresAt
    }

    // ponytail: waits whenever locked, even if the notification reply could send; narrow to hidden-only routes if that bites.
    private fun locked() = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun now() = System.currentTimeMillis()
    private fun requestId(id: String) = "$id:ask"

    companion object {
        const val WAIT_MS = 30 * 60_000L
        private val BUSY = setOf(TaskState.EXECUTING, TaskState.CHECKING, TaskState.OUTCOME_UNKNOWN, TaskState.WAITING)
    }
}

/** Debug card for the experiment (Phase B harness). Polls the task twice a second. */
class ExperimentActivity : ComponentActivity() {
    private var taskId by mutableStateOf<String?>(null)
    private var task by mutableStateOf<Task?>(null)
    private var checking by mutableStateOf(false)
    private var answer by mutableStateOf<List<Msg>>(emptyList())
    private var chats by mutableStateOf<List<Chat>>(emptyList())
    private var whatsapp by mutableStateOf(false)

    private val flows by lazy { mapOf(false to ExperimentFlow(this), true to ExperimentFlow(this, channel = "whatsapp", app = WHATSAPP)) }
    private fun flowFor(t: Task?) = flows.getValue(t?.payload?.get("channel")?.let { it == "whatsapp" } ?: whatsapp)
    private fun pkg() = if (whatsapp) WHATSAPP else TESTAPP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = intent.getStringExtra("task")
        lifecycleScope.launch {
            while (true) {
                taskId?.let { id -> task = MisuraGraph.tasks.load(id); checking = MisuraGraph.controller.isChecking(id); answer = flowFor(task).answer(id) }
                chats = MisuraListener.instance?.chats(pkg()).orEmpty()
                delay(500)
            }
        }
        setContent {
            val t = task
            val flow = flowFor(t)
            val note by flow.note.collectAsState()
            var picked by remember(t?.id) { mutableStateOf<String?>(null) }
            MisuraSurface {
                // Bottom of the screen: the card that needs you stays in thumb reach (§6).
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(M.m), verticalArrangement = Arrangement.Bottom) {
                    when {
                        t == null -> AskCard(whatsapp, { whatsapp = it }, chats) { bot, request -> lifecycleScope.launch { taskId = flowFor(null).startAsk(bot, request) } }
                        // Picking an answer only selects; choosing who to tell is its own step (design-system §9.3).
                        picked != null -> ChatPicker("Message someone about it", chats, onCancel = { picked = null }) { friend ->
                            val text = picked!!; picked = null
                            lifecycleScope.launch { flow.draft(t.id, friend, text) }
                        }
                        else -> {
                            val card = cardFor(t, answer.map { it.text }, checking)
                            // The tap carries the digest of the draft on screen (§10.1). Working until the task moves.
                            var tapped by remember(t.state, t.payload) { mutableStateOf(false) }
                            val seen = t.payload?.digest()
                            CardView(
                                card, note, working = tapped, commitKey = seen,
                                onCommit = seen?.let { d -> {
                                    tapped = true
                                    lifecycleScope.launch { if (t.buzzed) flow.send(t.id, d) else flow.ask(t.id, d); tapped = false }
                                } },
                                onPick = { picked = it },
                                onEdit = if (t.buzzed) { text -> lifecycleScope.launch {
                                    val p = t.payload!!; flow.draft(t.id, Chat(p["recipient"]!!, p["name"] ?: p["recipient"]!!, if (p["channel"] == "whatsapp") WHATSAPP else TESTAPP), text)
                                } } else null,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TESTAPP = "com.misura.testapp"
        const val WHATSAPP = "com.whatsapp"
    }
}

/** Only chats with a live notification and a reply button can be used: that's the route that sends without opening the app. */
@Composable
private fun ChatList(chats: List<Chat>, selected: Chat?, onPick: (Chat) -> Unit) {
    if (chats.isEmpty()) Text("No chats with a notification yet. Get a message in the chat first, then it shows here.", style = T.line, modifier = Modifier.padding(horizontal = 6.u))
    chats.forEach { c -> OptionRow(c.name, selected == c) { onPick(c) } }
}

@Composable
private fun ChatPicker(title: String, chats: List<Chat>, onCancel: () -> Unit, onPick: (Chat) -> Unit) {
    var sel by remember { mutableStateOf<Chat?>(null) }
    CardFrame("Misura", title, tiles = {
        ChatList(chats, sel) { sel = it }
        Row(horizontalArrangement = Arrangement.spacedBy(M.p)) {
            MButton("Draft message", Kind.Forward, Modifier.weight(1.6f), enabled = sel != null) { sel?.let(onPick) }
            MButton("Cancel", Kind.Secondary, Modifier.weight(0.8f), onClick = onCancel)
        }
    })
}

/** First card: which app, which chat to ask, and what. Nothing goes out until the Ask card's Commit. */
@Composable
private fun AskCard(whatsapp: Boolean, onApp: (Boolean) -> Unit, chats: List<Chat>, onPrepare: (Chat, String) -> Unit) {
    var bot by remember(whatsapp) { mutableStateOf<Chat?>(null) }
    var request by remember { mutableStateOf("") }
    CardFrame(if (whatsapp) "WhatsApp" else "Testchat", "New ask", tiles = {
        Row(horizontalArrangement = Arrangement.spacedBy(M.gap)) {
            Box(Modifier.weight(1f)) { OptionRow("Testchat", !whatsapp) { onApp(false) } }
            Box(Modifier.weight(1f)) { OptionRow("WhatsApp", whatsapp) { onApp(true) } }
        }
        ChatList(chats, bot) { bot = it }
        Field(request, { request = it }, "What to ask")
        MButton("Review ask", Kind.Forward, Modifier.fillMaxWidth(), enabled = bot != null && request.isNotBlank()) { onPrepare(bot!!, request) }
    })
}
