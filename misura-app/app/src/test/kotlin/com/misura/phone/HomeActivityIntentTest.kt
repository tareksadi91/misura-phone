package com.misura.phone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.phone.notify.taskNotificationIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeActivityIntentTest {
    private fun request(activity: HomeActivity): OpenRequest? =
        HomeActivity::class.java.getDeclaredMethod("getRequest").apply { isAccessible = true }.invoke(activity) as OpenRequest?

    private fun awaitRequest(activity: HomeActivity, session: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (request(activity)?.sessionId == session) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertEquals("Activity did not receive the resolved task session", session, request(activity)?.sessionId)
    }

    @Test fun existingVertexTokenAndProjectSkipSetupOnColdHomeLaunch() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
        prefs.edit().putString(com.misura.phone.brain.PrefsToken.KEY, "synthetic-token")
            .putString("project", "synthetic-project").commit()
        val home = Robolectric.buildActivity(HomeActivity::class.java).create()
        try {
            assertNull(shadowOf(home.get()).nextStartedActivity)
            assertFalse(prefs.contains("model_provider"))
        } finally {
            home.destroy()
        }
    }

    @Test fun taskNotificationDeliveryAndRecreationUseActualActivityAndSessionLookup() = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        MisuraGraph.init(ctx)
        val suffix = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val tasks = (1..2).map { Task("intent-task-$it-$suffix", "message", TaskState.DONE, null, emptyList(), now, null) }
        val sessions = tasks.map { task ->
            Session("intent-session-${task.id}", SessionStatus.IDLE, 0, now,
                steps = listOf(Step("step-${task.id}", 1, "Sent", StepState.DONE, startedAt = now, taskId = task.id)))
        }
        val controllers = mutableListOf<ActivityController<HomeActivity>>()
        val release = CompletableDeferred<Unit>()
        var transaction: Job? = null
        fun notification(task: Task) = Intent(taskNotificationIntent(ctx, task.id)).setClass(ctx, HomeActivity::class.java)
        fun create(intent: Intent, saved: Bundle? = null): ActivityController<HomeActivity> =
            Robolectric.buildActivity(HomeActivity::class.java, intent).also { controllers += it }.create(saved)
        fun save(controller: ActivityController<HomeActivity>) = Bundle().also { controller.saveInstanceState(it) }
        fun destroy(controller: ActivityController<HomeActivity>) { controller.destroy(); controllers.remove(controller) }
        try {
            withContext(Dispatchers.IO) {
                tasks.forEach { MisuraGraph.tasks.save(it) }
                sessions.forEach { MisuraGraph.sessions.save(it) }
            }
            val cold = create(notification(tasks[0]))
            awaitRequest(cold.get(), sessions[0].id)
            val first = request(cold.get())!!
            cold.newIntent(notification(tasks[1]))
            assertEquals(tasks[1].id, cold.get().intent.getStringExtra("task"))
            awaitRequest(cold.get(), sessions[1].id)
            assertNotEquals(first.nonce, request(cold.get())!!.nonce)
            val completedState = save(cold)
            assertNull(completedState.getString("pending-task"))
            destroy(cold)

            // A real Room transaction holds up sessionForTask, including lookups started after recreation.
            val entered = CompletableDeferred<Unit>()
            transaction = launch(Dispatchers.IO) {
                MisuraGraph.db.withTransaction { entered.complete(Unit); release.await() }
            }
            withTimeout(5_000) { entered.await() }
            val completed = create(notification(tasks[1]), completedState)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(request(completed.get()))
            assertNull(save(completed).getString("pending-task"))

            completed.newIntent(notification(tasks[0]))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(request(completed.get()))
            val pendingState = save(completed)
            assertEquals(tasks[0].id, pendingState.getString("pending-task"))
            destroy(completed)

            val pending = create(notification(tasks[1]), pendingState)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(request(pending.get()))
            assertEquals(tasks[0].id, save(pending).getString("pending-task"))
            release.complete(Unit)
            withTimeout(5_000) { transaction?.join() }
            awaitRequest(pending.get(), sessions[0].id)
            val resolvedState = save(pending)
            assertNull(resolvedState.getString("pending-task"))
            destroy(pending)

            val restored = create(notification(tasks[0]), resolvedState)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(request(restored.get()))
            assertNull(save(restored).getString("pending-task"))
        } finally {
            release.complete(Unit)
            try { controllers.asReversed().forEach { it.destroy() } }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    withTimeout(5_000) { transaction?.join() }
                    MisuraGraph.db.withTransaction {
                        sessions.forEach { MisuraGraph.db.dao().deleteSession(it.id) }
                        MisuraGraph.db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM tasks WHERE id IN (?, ?)", tasks.map { it.id }.toTypedArray())
                    }
                }
            }
        }
    }

    @Test fun homeInFrontSilencesAlertsClearsShadeCopiesAndATapOpensItsSession() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("brain", Context.MODE_PRIVATE).edit().putString(com.misura.phone.brain.PrefsToken.KEY, "synthetic-token")
            .putString("project", "synthetic-project").commit()
        MisuraGraph.init(ctx)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("results", "Results", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(com.misura.phone.notify.SessionAlerts.resultTag("s-old"), 1,
            android.app.Notification.Builder(ctx, "results").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        com.misura.phone.notify.SessionAlerts.front(ctx, false)
        // The tap target a result posts: Home on that session.
        val tap = shadowOf(com.misura.phone.notify.SessionAlerts.open(ctx, "s-tapped")).savedIntent
        val home = Robolectric.buildActivity(HomeActivity::class.java, tap).create().start().resume().visible()
        try {
            home.get().onWindowFocusChanged(true)
            assertTrue(com.misura.phone.notify.SessionAlerts.homeInFront)
            assertTrue(nm.activeNotifications.none { it.tag == com.misura.phone.notify.SessionAlerts.resultTag("s-old") })
            awaitRequest(home.get(), "s-tapped")
            home.pause()
            assertFalse(com.misura.phone.notify.SessionAlerts.homeInFront)
        } finally {
            home.destroy(); com.misura.phone.notify.SessionAlerts.front(ctx, false)
        }
    }
}
