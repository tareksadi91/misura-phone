package com.misura.phone

import android.app.Application
import com.misura.phone.notify.AnswerReady
import com.misura.phone.sleep.SleepMode
import com.misura.phone.sleep.Variant
import com.misura.phone.work.WorkingService
import kotlinx.coroutines.launch

class MisuraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { com.misura.phone.notify.ProbeLog.clean(this) }
        MisuraGraph.init(this)
        if (SleepMode.get(this) != Variant.OFF) WorkingService.ensure(this)
        MisuraGraph.scope.launch { MisuraGraph.inbox.prune(); MisuraGraph.controller.recover(); MisuraGraph.agent.recover(); AnswerReady.rearm(this@MisuraApp)
            // Catch up on a routine due while the app was not running (within the late limit), then rearm the alarm.
            runCatching { com.misura.phone.routine.Routines.reconcile() } }
    }
}
