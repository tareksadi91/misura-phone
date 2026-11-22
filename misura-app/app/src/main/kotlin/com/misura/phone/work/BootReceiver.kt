package com.misura.phone.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.misura.phone.MisuraGraph
import com.misura.phone.sleep.SleepMode
import com.misura.phone.sleep.Variant
import kotlinx.coroutines.launch

/** After boot: restart the sleep screen service, and wait for the same single-flight recovery the app start launched. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        MisuraGraph.init(ctx)
        // Boot is an allowed reason to start a foreground service; an earlier start from app launch may have been refused.
        if (SleepMode.get(ctx) != Variant.OFF) WorkingService.ensure(ctx)
        MisuraGraph.scope.launch {
            try { Log.i("Misura", "recover: ${MisuraGraph.controller.recover().size} tasks") } finally { pending.finish() }
        }
    }
}
