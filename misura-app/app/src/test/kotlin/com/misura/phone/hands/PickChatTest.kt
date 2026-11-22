package com.misura.phone.hands

import com.misura.phone.notify.Chat
import io.kotest.matchers.shouldBe
import org.junit.Test

class PickChatTest {
    private val frienda = Chat("1", "FriendA Example", "chat.one", 10); private val sender = Chat("2", "Sender", "chat.one"); private val host = Chat("3", "Host K", "chat.one")

    @Test fun exactNameWinsAnyCase() { pickChat("sender", listOf(frienda, sender)) shouldBe sender }
    @Test fun firstNameMatchesWhenOnlyOne() { pickChat("FriendA", listOf(frienda, host)) shouldBe frienda }
    @Test fun twoFirstNameMatchesIsNoGuess() { pickChat("Host", listOf(host, Chat("4", "Host L", "chat.two", 100))) shouldBe null }
    @Test fun noMatchIsNull() { pickChat("FriendB", listOf(frienda, host)) shouldBe null }

    @Test fun sameNameAcrossAppsPicksNewestRegardlessOfOrder() {
        val newer = frienda.copy(key = "other", name = " frienda example ", pkg = "chat.two", sentAt = 20)
        pickChat("FriendA Example", listOf(frienda, newer)) shouldBe newer
        pickChat("FriendA", listOf(newer, frienda)) shouldBe newer
    }

    @Test fun exactNameBeatsNewerFirstWordMatch() {
        val exact = Chat("5", "FriendA", "chat.two", 1)
        pickChat("FriendA", listOf(frienda, exact)) shouldBe exact
    }

    @Test fun exactNameAcrossAppsBeatsAnotherPersonWithSameFirstWord() {
        val exact = Chat("5", "FriendA", "chat.two", 1)
        val newestExact = exact.copy(pkg = "chat.three", sentAt = 5)
        pickChat("FriendA", listOf(frienda, exact, newestExact)) shouldBe newestExact
    }

    @Test fun sameNameInTwoConversationsOfOneAppIsNoGuess() {
        pickChat("FriendA", listOf(frienda, frienda.copy(key = "other", sentAt = 20))) shouldBe null
    }
}
