package com.misura.phone.notify

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.misura.core.inbox.Msg

/** What one notification carries, read defensively. All of it is untrusted. */
data class Captured(
    val msgs: List<Msg>,
    val rawExtras: Map<String, String>,
    val replyActionIndex: Int?,
    val replyAuthRequired: Boolean,
    val conversationKey: String,
    val messagingStyle: Boolean = false,
)

fun extract(sbn: StatusBarNotification): Captured {
    val n = sbn.notification
    val extras = n.extras
    val raw = if (com.misura.phone.BuildConfig.DEBUG) extras.keySet().associateWith { k -> runCatching { @Suppress("DEPRECATION") extras.get(k)?.toString() ?: "null" }.getOrDefault("?") } else emptyMap()
    val actionIndex = n.actions?.indexOfFirst { a -> a.remoteInputs?.any { !it.isDataOnly } == true }?.takeIf { it >= 0 }
    val authRequired = actionIndex?.let { n.actions[it].isAuthenticationRequired } ?: false
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
    val title = style?.conversationTitle?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val firstOther = style?.messages?.firstOrNull { it.person != null && it.person?.key != style.user.key }?.person?.name?.toString()
    val conv = n.shortcutId ?: "${sbn.packageName}:${title ?: firstOther ?: sbn.key}"
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return Captured(emptyList(), raw, actionIndex, authRequired, conv, style != null)

    val msgs = if (style != null) {
        val group = style.isGroupConversation
        style.messages.map { m ->
            val self = m.person == null || (m.person?.key != null && m.person?.key == style.user.key)
            val sender = if (self) style.user.name?.toString() ?: "me" else m.person?.name?.toString() ?: "?"
            Msg("${sbn.key}|${m.timestamp}|$sender", sbn.packageName, conv, sender, self, group, m.text?.toString().orEmpty(), m.timestamp)
        }
    } else {
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (text == null) emptyList() else listOf(Msg("${sbn.key}|${sbn.postTime}|$title", sbn.packageName, conv, title ?: "?", false, false, text, sbn.postTime))
    }
    return Captured(msgs, raw, actionIndex, authRequired, conv, style != null)
}

/** True if this notification belongs to the conversation named by a payload recipient. */
fun Captured.isConversation(recipient: String): Boolean {
    if (conversationKey == recipient || conversationKey.substringAfter(':') == recipient) return true
    // In a group, a member's name is not their direct chat: never reply into the group for them.
    return msgs.none { it.isGroup } && msgs.any { !it.senderIsSelf && it.sender == recipient }
}

/** The distinct names the other side shows in this notification (self messages left out). */
fun Captured.otherNames(): List<String> = msgs.filter { !it.senderIsSelf }.map { it.sender }.distinct()

/** A one-to-one chat Misura can reply into: its app's own conversation id and the other person's name. */
data class Chat(val key: String, val name: String, val pkg: String, val sentAt: Long = 0)

/** Only MessagingStyle with a text reply action teaches us a chat app, including group-only apps. */
fun Captured.isChatNotification() = messagingStyle && replyActionIndex != null

/** A chat picked by id is that exact one-to-one conversation: no name fallback, never a group. */
fun Captured.isChat(key: String) = conversationKey == key && msgs.none { it.isGroup }

/**
 * Chats with a live reply button, one per conversation; groups are never offered (spec: no replying into groups for a person).
 * [nameKeyPrefix]: for apps with their own chat ids (WhatsApp), skip chats keyed by title, which can collide.
 */
fun chatsIn(captures: List<Captured>, nameKeyPrefix: String? = null): List<Chat> = captures
    .filter { c -> c.isChatNotification() && c.msgs.isNotEmpty() && c.msgs.none { it.isGroup } }
    // One person by one name: a chat showing two (renamed, name and number) would fail the send-time check (NotificationReplyHand).
    .filter { c -> c.otherNames().size == 1 }
    .filter { c -> nameKeyPrefix == null || !c.conversationKey.startsWith(nameKeyPrefix) }
    .mapNotNull { c -> c.msgs.firstOrNull { !it.senderIsSelf }?.let { Chat(c.conversationKey, it.sender, it.app, c.msgs.maxOf { m -> m.sentAt }) } }
    .sortedByDescending { it.sentAt }
    .distinctBy { it.pkg to it.key }
