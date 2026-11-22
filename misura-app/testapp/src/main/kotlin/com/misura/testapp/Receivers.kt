package com.misura.testapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** adb/test entry: action com.misura.testapp.POST with conv, sender, text, mode, seedSelf, autoAnswer, shortcut (imitates WhatsApp's own chat id). */
class PostReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Chat.receive(ctx, i.getStringExtra("conv") ?: "c1", i.getStringExtra("sender") ?: "Friend", i.getStringExtra("text") ?: "hi",
            i.getStringExtra("mode"), i.getStringExtra("seedSelf"), i.getIntExtra("autoAnswer", 0), i.getStringExtra("shortcut"))
    }
}

/** Receives notification replies and appends them as our own message. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(Chat.KEY_TEXT)?.toString() ?: return
        Chat.replied(ctx, i.getStringExtra("conv") ?: return, text)
    }
}

/** adb/test entry: action com.misura.testapp.FLIP turns the gate page's Preview button into Submit, in place. */
class FlipReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) = ChatActivity.flipNow(i.getBooleanExtra("swap", false))
}
