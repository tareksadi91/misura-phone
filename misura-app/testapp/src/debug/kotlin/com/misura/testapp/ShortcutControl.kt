package com.misura.testapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle
import java.io.File

/**
 * adb/test entry (debug builds only): action com.misura.testapp.SHORTCUTS, extra op =
 * publish (dynamic fx_a and fx_b, labels "Fixture A" / "Fixture B"), label (fx_a gets the label in extra label), remove (all dynamic shortcuts).
 */
class ShortcutControl : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        when (i.getStringExtra("op")) {
            "publish" -> sm.setDynamicShortcuts(listOf(shortcut(ctx, "fx_a", "Fixture A"), shortcut(ctx, "fx_b", "Fixture B")))
            "label" -> sm.updateShortcuts(listOf(shortcut(ctx, "fx_a", i.getStringExtra("label") ?: "Fixture A")))
            "remove" -> sm.removeAllDynamicShortcuts()
        }
    }

    private fun shortcut(ctx: Context, id: String, label: String) = ShortcutInfo.Builder(ctx, id).setShortLabel(label)
        .setIntent(Intent(Intent.ACTION_VIEW).setClass(ctx, FixtureTargetActivity::class.java).putExtra("id", id)).build()
}

/** The page a fixture shortcut opens: it records `opened=<id>` in files/opened.txt (read with `run-as`) and stays up. */
class FixtureTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        File(filesDir, "opened.txt").writeText("opened=${intent.getStringExtra("id")}")
    }
}

/** Handles `spotify:` VIEW as a decoy for the link-scoping test: records `decoy=<uri>` in files/decoy.txt. */
class DecoyLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        File(filesDir, "decoy.txt").writeText("decoy=${intent.dataString}")
    }
}
