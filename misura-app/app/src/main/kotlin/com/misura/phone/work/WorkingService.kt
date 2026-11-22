package com.misura.phone.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.misura.phone.sleep.SleepMode
import com.misura.phone.sleep.Variant
import com.misura.phone.sleep.WakeWatcher

/**
 * Foreground while an attempt is being sent or checked, so Android doesn't kill it.
 * With a sleep screen on, it stays up for good and holds the wake watcher.
 */
class WorkingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Misura working", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("Misura is working").setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        instance = this; lastStartId = startId
        val sleeping = SleepMode.get(this) != Variant.OFF
        if (sleeping) WakeWatcher.register(this)
        // idle() came before this start finished: stop only now that startForeground ran, or Android crashes the app.
        if (!wanted && routines.get() == 0 && !sleeping) stopSelfResult(startId)
        return if (sleeping) START_STICKY else START_NOT_STICKY
    }

    private var lastStartId = 0

    override fun onDestroy() { if (instance === this) instance = null; WakeWatcher.unregister(this); super.onDestroy() }

    companion object {
        const val CHANNEL = "working"
        /** A send or check needs the service. The sleep screen's need is read from [SleepMode]. */
        @Volatile private var wanted = false
        val workWanted get() = wanted
        @Volatile private var instance: WorkingService? = null
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        /** A send or check started. */
        fun start(ctx: Context) { wanted = true; launch(ctx) }

        /** The sleep screen needs the watcher; leaves the send demand alone. */
        fun ensure(ctx: Context) = launch(ctx)

        private fun launch(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, WorkingService::class.java)) }
                .onFailure { if (com.misura.phone.BuildConfig.DEBUG) android.util.Log.w("Misura", "working service start refused", it) else android.util.Log.w("Misura", "working service start refused: ${it.javaClass.simpleName}") }
        }
        /** Scheduled routine runs in progress (each until its session settles); counted apart from sends. */
        private val routines = java.util.concurrent.atomic.AtomicInteger()
        fun routineStarted(ctx: Context) { routines.incrementAndGet(); launch(ctx) }
        fun routineDone(ctx: Context) { if (routines.decrementAndGet() < 0) routines.set(0); stopIfUnneeded(ctx) }

        /** Nothing left to send or check: stop, unless a sleep screen needs the watcher. */
        fun idle(ctx: Context) { wanted = false; stopIfUnneeded(ctx) }

        /**
         * Never stopService(): a start still in flight would then never reach startForeground and Android crashes the app.
         * On the main thread, like onStartCommand; stopSelfResult ignores the stop if a newer start already arrived.
         */
        fun stopIfUnneeded(ctx: Context) = main.post {
            val s = instance ?: return@post
            if (!wanted && routines.get() == 0 && SleepMode.get(ctx) == Variant.OFF) s.stopSelfResult(s.lastStartId)
        }
    }
}
