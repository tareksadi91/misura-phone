package com.misura.phone.hands

import android.content.SharedPreferences
import com.misura.core.exec.OperationHand
import com.misura.phone.hidden.withHiddenPackage
import com.misura.phone.hidden.hiddenOperation
import com.misura.core.exec.HandResult
import com.misura.core.task.Payload
import com.misura.phone.hidden.ChatScript
import com.misura.phone.hidden.DisplaySession
import com.misura.phone.hidden.HiddenInput
import com.misura.phone.hidden.HiddenScreen
import com.misura.phone.hidden.ScreenReader
import com.misura.phone.hidden.ShizukuBridge
import com.misura.phone.hidden.UiNode
import com.misura.phone.hidden.count
import com.misura.phone.hidden.find
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Sends a chat message by using the app on a hidden display. The user never sees the app.
 * Everything before the send tap is undoable (dispatched=false); the tap itself is the commit.
 */
/**
 * The name the hidden screen opens a chat by, or null when the payload names a chat by the app's own id:
 * two people can share a name, so opening by name could pick the wrong chat.
 */
fun hiddenTarget(p: Payload, pkg: String): String? {
    val recipient = p["recipient"] ?: return null
    val name = p["name"] ?: return recipient
    return name.takeIf { recipient == "$pkg:$name" }
}

class HiddenChatHand(
    private val hidden: HiddenScreen,
    private val reader: ScreenReader,
    private val input: HiddenInput,
    override val channel: String,
    val pkg: String,
    private val script: ChatScript,
    /** Matching message count right before each send tap, by attempt id: check() only trusts a rise above it. Saved, so it survives a restart. */
    private val baselines: SharedPreferences,
    /** Told before this opens a hidden screen on [pkg]: the general hand lets go of a screen it parked on the same app. */
    private val onOpening: suspend (String) -> Unit = {},
) : OperationHand {
    override val operation get() = hiddenOperation
    override val id = "hidden_chat:$channel"
    override val lock = "hidden_screen"
    // ponytail: entries are never pruned; a few bytes per hidden send, prune with old attempts if it ever matters.

    override suspend fun prepare(p: Payload): HandResult = blocked() ?: HandResult.Prepared(p)

    override suspend fun commitAdmitted(attemptId: String, p: Payload): HandResult {
        com.misura.phone.access.AppAccess.refusal(pkg)?.let { return HandResult.Failed(it, dispatched = false) }
        blocked()?.let { return it }
        val recipient = p["recipient"] ?: return HandResult.Unavailable("no recipient")
        val target = hiddenTarget(p, pkg) ?: return HandResult.Unavailable("hidden screen opens chats by name; $recipient is an app chat id")
        val text = p["content"] ?: return HandResult.Unavailable("no content")
        return session { s ->
            if (!script.openChat(s, target)) return@session HandResult.Failed("chat with $recipient not found", dispatched = false)
            if (!input.setText(s, script.inputViewId(), text)) return@session HandResult.Failed("could not type the message", dispatched = false)
            // Re-read right before the only step that sends (the tree can lag the typing by a frame or two).
            var tree: UiNode? = null
            repeat(10) { tree = reader.read(s); if (draft(tree) == text) return@repeat; delay(100) }
            val t = tree ?: return@session HandResult.Failed("hidden screen went away", dispatched = false)
            if (draft(t) != text) return@session HandResult.Failed("draft changed", dispatched = false)
            val send = script.sendButton(t) ?: return@session HandResult.Failed("no send button", dispatched = false)
            if (reader.userIsInApp(pkg)) return@session HandResult.Unavailable("user is in the app")
            val before = shown(t, text)
            // Written synchronously before the tap, so a crash right after the tap still leaves it.
            baselines.edit().putInt(attemptId, before).commit()
            val b = send.bounds
            if (!input.tap(s, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2)) return@session HandResult.Uncertain("send tap not confirmed")
            repeat(30) {
                reader.read(s)?.let { if (shown(it, text) > before) return@session HandResult.Ok("message \"$text\" shown in $recipient chat") }
                delay(100)
            }
            HandResult.Uncertain("tapped send, message not seen yet")
        } ?: HandResult.Unavailable("no hidden screen")
    }

    /** Looks without touching: opens the chat and searches for the message. */
    override suspend fun checkAdmitted(attemptId: String, p: Payload, dispatchedAt: Long): HandResult {
        com.misura.phone.access.AppAccess.refusal(pkg)?.let { return HandResult.Uncertain(it) }
        blocked()?.let { return HandResult.Uncertain(it.reason) }
        val recipient = p["recipient"] ?: return HandResult.Uncertain("no recipient")
        val target = hiddenTarget(p, pkg) ?: return HandResult.Uncertain("hidden screen can't open $recipient by id")
        val text = p["content"] ?: return HandResult.Uncertain("no content")
        return session { s ->
            if (!script.openChat(s, target)) return@session HandResult.Uncertain("chat with $recipient not found")
            val tree = reader.read(s) ?: return@session HandResult.Uncertain("hidden screen went away")
            val before = baselines.getInt(attemptId, -1).takeIf { it >= 0 } ?: return@session HandResult.Uncertain("can't tell an older identical message from this send")
            if (shown(tree, text) > before) HandResult.Ok("message \"$text\" in $recipient chat") else HandResult.Uncertain("not seen yet")
        } ?: HandResult.Uncertain("no hidden screen")
    }

    override suspend fun cancel(attemptId: String) = HandResult.Unavailable("a sent message can't be recalled")

    private fun blocked(): HandResult.Unavailable? = when {
        !reader.connected() -> HandResult.Unavailable("accessibility off")
        !ShizukuBridge.ready() -> HandResult.Unavailable("shizuku not ready")
        reader.userIsInApp(pkg) -> HandResult.Unavailable("user is in the app")
        else -> null
    }

    private fun draft(tree: UiNode?) = tree?.find { it.viewId == script.inputViewId() }?.text

    /** Message bubbles with exactly this text, not counting the input field. */
    private fun shown(tree: UiNode, text: String) = tree.count { it.text == text && it.viewId != script.inputViewId() }

    private suspend fun session(block: suspend (DisplaySession) -> HandResult): HandResult? = withHiddenPackage(pkg) {
        onOpening(pkg)
        val s = runCatching { hidden.open(WIDTH, HEIGHT, DPI) }.getOrNull() ?: return@withHiddenPackage null
        try { block(s) } finally { withContext(NonCancellable) { runCatching { hidden.close(s) } } }
    }

    companion object { const val WIDTH = 720; const val HEIGHT = 1600; const val DPI = 320 }
}
