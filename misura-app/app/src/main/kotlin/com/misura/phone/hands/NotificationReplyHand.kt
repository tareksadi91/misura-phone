package com.misura.phone.hands

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import com.misura.core.exec.Hand
import com.misura.core.exec.HandResult
import com.misura.core.task.Payload
import com.misura.phone.access.AppAccess
import com.misura.phone.notify.MisuraListener
import com.misura.phone.notify.extract
import com.misura.phone.notify.otherNames
import com.misura.phone.notify.isChat
import com.misura.phone.notify.isConversation

/**
 * Replies through a live notification's reply button, so the app never opens.
 * Dispatching is not proof of delivery: commit returns Uncertain, check looks for our own message.
 */
class NotificationReplyHand(
    private val listener: () -> MisuraListener?,
    override val channel: String = "reply",
    private val pkg: String? = null,
) : Hand {
    override val id = "notification_reply:$channel"
    override val lock: String? = null

    override suspend fun prepare(p: Payload) = HandResult.Prepared(p)

    override suspend fun commit(attemptId: String, p: Payload): HandResult {
        val pkg = this.pkg ?: p["pkg"]?.takeIf { it.isNotBlank() } ?: return HandResult.Unavailable("no package")
        // A card drafted before the app was blocked: refused, and no other hand tries (Failed, not Unavailable). Before the listener check, so a disconnected listener can't turn it into a fallback.
        AppAccess.refusal(pkg)?.let { return HandResult.Failed(it, dispatched = false) }
        val l = listener() ?: return HandResult.Unavailable("listener not connected")
        val recipient = p["recipient"] ?: return HandResult.Unavailable("no recipient")
        val text = p["content"] ?: return HandResult.Unavailable("no content")
        val sbn = l.liveFrom(pkg).lastOrNull { matches(extract(it), p, recipient) } ?: return HandResult.Unavailable("no live notification for $recipient")
        val c = extract(sbn)
        // The card showed a person: the chat must still show exactly them. Same id, someone else = stop here, and no other hand tries (Failed, not Unavailable).
        p["name"]?.let { approved ->
            if (c.otherNames() != listOf(approved))
                return HandResult.Failed("the chat no longer shows $approved; draft it again", dispatched = false)
        }
        val idx = c.replyActionIndex ?: return HandResult.Unavailable("no reply action")
        if (c.replyAuthRequired) return HandResult.Unavailable("reply needs unlock")
        val action = sbn.notification.actions[idx]
        // The reply intent is what actually runs: it must belong to the notification's own (allowed) app.
        val creator = action.actionIntent.creatorPackage
        AppAccess.refusal(creator)?.let { return HandResult.Failed(it, dispatched = false) }
        if (creator != pkg) return HandResult.Unavailable("reply button belongs to another app")
        val fill = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply { action.remoteInputs.filter { !it.isDataOnly }.forEach { putCharSequence(it.resultKey, text) } })
        return try {
            action.actionIntent.send(l, 0, fill)
            HandResult.Uncertain("dispatched")
        } catch (e: PendingIntent.CanceledException) {
            HandResult.Unavailable("reply intent cancelled")
        }
    }

    override suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long): HandResult {
        val pkg = this.pkg ?: p["pkg"]?.takeIf { it.isNotBlank() } ?: return HandResult.Uncertain("no package")
        // After a possible send: never claim it failed, never look again.
        AppAccess.refusal(pkg)?.let { return HandResult.Uncertain(it) }
        val l = listener() ?: return HandResult.Uncertain("listener not connected")
        val recipient = p["recipient"] ?: return HandResult.Uncertain("no recipient")
        // A chat that now shows someone else never confirms: the outcome stays unknown, never failed, never resent.
        val hit = l.liveFrom(pkg).asSequence().map { extract(it) }.filter { matches(it, p, recipient) && p["name"].let { n -> n == null || it.otherNames() == listOf(n) } }
            .flatMap { it.msgs }.any { it.senderIsSelf && it.text == p["content"] && it.sentAt >= dispatchedAt }
        return if (hit) HandResult.Ok("self message \"${p["content"]}\" in $recipient") else HandResult.Uncertain("not seen yet")
    }

    /** A picked chat (payload has a name) matches by exact id only; a bare name keeps the old lookup. */
    private fun matches(c: com.misura.phone.notify.Captured, p: Payload, recipient: String) =
        if (p["name"] != null) c.isChat(recipient) else c.isConversation(recipient)

    override suspend fun cancel(attemptId: String) = HandResult.Unavailable("a sent message can't be recalled")
}
