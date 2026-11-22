package com.misura.phone.hidden

/** How one chat app looks on the hidden screen: how to open a chat, where to type, what sends. */
interface ChatScript {
    /** Opens the chat and returns true only once the screen shows that recipient's chat. */
    suspend fun openChat(s: DisplaySession, recipient: String): Boolean
    fun inputViewId(): String
    fun sendButton(tree: UiNode): UiNode?
}
