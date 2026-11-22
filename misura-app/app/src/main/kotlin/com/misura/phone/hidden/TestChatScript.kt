package com.misura.phone.hidden

import android.net.Uri
import kotlinx.coroutines.delay

/** ChatScript for :testapp, the fake chat app. */
class TestChatScript(private val hidden: HiddenScreen, private val reader: ScreenReader) : ChatScript {
    override suspend fun openChat(s: DisplaySession, recipient: String): Boolean {
        if (!hidden.launch(s, PKG, deepLink = "testchat://chat/" + Uri.encode(recipient))) return false
        repeat(30) {
            if (reader.read(s)?.find { it.viewId == "$PKG:id/conversation_title" && it.text == recipient } != null) return true
            delay(100)
        }
        return false
    }

    override fun inputViewId() = "$PKG:id/entry"
    override fun sendButton(tree: UiNode) = tree.find { it.viewId == "$PKG:id/send" && it.clickable }

    companion object { const val PKG = "com.misura.testapp" }
}
