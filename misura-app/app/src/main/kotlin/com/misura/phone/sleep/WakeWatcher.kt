package com.misura.phone.sleep

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.misura.phone.work.WorkingService

enum class Variant { OFF, ACTIVITY, OVERLAY }
enum class WakeEvent { SCREEN_ON, USER_PRESENT }
enum class WakeAction { NONE, SHOW_ACTIVITY, SHOW_OVERLAY, HIDE }

fun wakeAction(e: WakeEvent, locked: Boolean, v: Variant): WakeAction = when {
    e == WakeEvent.USER_PRESENT -> WakeAction.HIDE
    !locked || v == Variant.OFF -> WakeAction.NONE
    v == Variant.ACTIVITY -> WakeAction.SHOW_ACTIVITY
    else -> WakeAction.SHOW_OVERLAY
}

/** Which sleep screen to use. Anything but OFF keeps WorkingService running so the watcher stays registered. */
object SleepMode {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sleep", Context.MODE_PRIVATE)
    fun get(ctx: Context): Variant = runCatching { Variant.valueOf(prefs(ctx).getString("variant", null)!!) }.getOrDefault(Variant.OFF)
    fun set(ctx: Context, v: Variant) {
        prefs(ctx).edit().putString("variant", v.name).commit()
        if (v == Variant.OFF) { WakeWatcher.unregister(ctx); SleepScreen.hide(); WorkingService.stopIfUnneeded(ctx) } else WorkingService.ensure(ctx)
    }
}

/** SCREEN_ON can only be received by a runtime receiver, so WorkingService holds this one. */
object WakeWatcher {
    private var receiver: BroadcastReceiver? = null

    @Synchronized fun register(ctx: Context) {
        if (receiver != null) return
        val app = ctx.applicationContext
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val e = if (i.action == Intent.ACTION_SCREEN_ON) WakeEvent.SCREEN_ON else WakeEvent.USER_PRESENT
                val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                when (wakeAction(e, locked, SleepMode.get(app))) {
                    WakeAction.SHOW_ACTIVITY -> SleepScreen.showActivity(app)
                    WakeAction.SHOW_OVERLAY -> SleepScreen.showOverlay(app)
                    WakeAction.HIDE -> SleepScreen.hide()
                    WakeAction.NONE -> Unit
                }
            }
        }
        app.registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
        Log.i("MisuraSleep", "watcher registered")
    }

    @Synchronized fun unregister(ctx: Context) {
        receiver?.let { runCatching { ctx.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }
}
