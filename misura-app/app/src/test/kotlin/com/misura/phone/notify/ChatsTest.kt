package com.misura.phone.notify

import com.misura.core.inbox.Msg
import io.kotest.matchers.shouldBe
import org.junit.Test

class ChatsTest {
    private fun cap(key: String, sender: String, group: Boolean = false, self: Boolean = false) =
        Captured(listOf(Msg("$key|1", "com.whatsapp", key, sender, self, group, "hi", 1)), emptyMap(), 0, false, key, messagingStyle = true)

    @Test fun oneEntryPerChatNamedByTheOtherPerson() {
        chatsIn(listOf(cap("4917@s.whatsapp.net", "FriendB"), cap("4917@s.whatsapp.net", "FriendB"), cap("4930@s.whatsapp.net", "Instinct"))) shouldBe
            listOf(Chat("4917@s.whatsapp.net", "FriendB", "com.whatsapp", 1), Chat("4930@s.whatsapp.net", "Instinct", "com.whatsapp", 1))
    }

    @Test fun groupsAreNeverOffered() {
        chatsIn(listOf(cap("g1@g.us", "FriendB", group = true))) shouldBe emptyList()
    }

    @Test fun chatsWithoutReplyButtonAreNotOffered() {
        chatsIn(listOf(cap("4917@s.whatsapp.net", "FriendB").copy(replyActionIndex = null))) shouldBe emptyList()
    }

    @Test fun aChatIdNeverMatchesAnotherChatByName() {
        val other = cap("4911@s.whatsapp.net", "4917@s.whatsapp.net")
        other.isChat("4917@s.whatsapp.net") shouldBe false
        cap("4917@s.whatsapp.net", "FriendB").isChat("4917@s.whatsapp.net") shouldBe true
    }

    @Test fun aGroupIsNeverTheChat() {
        cap("com.whatsapp:ContactA", "ContactA", group = true).isChat("com.whatsapp:ContactA") shouldBe false
    }

    @Test fun whenTheAppHasItsOwnIdsChatsWithoutOneAreNotOffered() {
        chatsIn(listOf(cap("com.whatsapp:ContactA", "ContactA"), cap("4917@s.whatsapp.net", "FriendB")), nameKeyPrefix = "com.whatsapp:") shouldBe
            listOf(Chat("4917@s.whatsapp.net", "FriendB", "com.whatsapp", 1))
    }

    @Test fun plainNotificationsWithReplyActionsAreNotChats() {
        val mail = cap("mail-thread", "FriendB").copy(messagingStyle = false)
        mail.isChatNotification() shouldBe false
        chatsIn(listOf(mail)) shouldBe emptyList()
    }

    @Test fun newestMessageIncludesSelfAndDuplicateNotificationsKeepNewest() {
        val old = cap("id", "FriendB")
        val newer = old.copy(msgs = old.msgs + old.msgs.single().copy(senderIsSelf = true, sentAt = 20))
        chatsIn(listOf(old, newer)).single() shouldBe Chat("id", "FriendB", "com.whatsapp", 20)
    }

    @Test fun sameConversationIdInTwoAppsStaysSeparate() {
        val first = cap("id", "FriendB")
        val second = first.copy(msgs = first.msgs.map { it.copy(app = "chat.two") })
        chatsIn(listOf(first, second)).map { it.pkg } shouldBe listOf("com.whatsapp", "chat.two")
    }

    @Test fun groupOnlyAppIsLearnedWithoutOfferingGroupChats() {
        val group = cap("group", "FriendB", group = true)
        group.isChatNotification() shouldBe true
        chatsIn(listOf(group)) shouldBe emptyList()
    }

    /** A chat showing two names (renamed, or a name and a number) can't pass the send-time check, so it is not offered on the quick-reply route. */
    @Test fun aChatShowingTwoNamesIsNotOffered() {
        val mixed = Captured(listOf(Msg("k|1", "com.whatsapp", "4917@s.whatsapp.net", "FriendB", false, false, "hi", 1),
            Msg("k|2", "com.whatsapp", "4917@s.whatsapp.net", "+49 151 000", false, false, "again", 2)), emptyMap(), 0, false, "4917@s.whatsapp.net", messagingStyle = true)
        chatsIn(listOf(mixed)) shouldBe emptyList()
    }
}
