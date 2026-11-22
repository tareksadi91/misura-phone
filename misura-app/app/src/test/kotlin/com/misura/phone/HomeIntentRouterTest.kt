package com.misura.phone

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.misura.phone.notify.taskNotificationIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeIntentRouterTest {
    @Test fun notificationIntentRoutesTaskAndKeepsHashCollisionsDistinct() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val intent = taskNotificationIntent(ctx, "Aa")
        assertEquals(if (BuildConfig.DEBUG) "com.misura.phone.debug.ExperimentActivity" else HomeActivity::class.java.name, intent.component!!.className)
        assertNotEquals(intent.data, taskNotificationIntent(ctx, "BB").data)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val first = PendingIntent.getActivity(ctx, "Aa".hashCode(), intent, flags)
        val collision = PendingIntent.getActivity(ctx, "BB".hashCode(), taskNotificationIntent(ctx, "BB"), flags)
        assertNotEquals(first, collision)
        first.send()
        val delivered = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(intent.component, delivered.component)
        assertEquals("Aa", delivered.getStringExtra("task"))
        val opened = mutableListOf<String?>()
        val router = HomeIntentRouter(this, { "session-$it" }, opened::add)
        router.follow(delivered)
        assertEquals("Aa", router.pendingTask)
        runCurrent()
        assertNull(router.pendingTask)
        assertEquals(listOf("session-Aa"), opened)
        router.follow(Intent().putExtra("session", "direct").putExtra("task", "other"))
        assertEquals(listOf("session-Aa", "direct"), opened)
    }

    @Test fun newerTapAndHomeWinOverSlowLookupAndMissingTaskReturnsHome() = runTest {
        val late = CompletableDeferred<String?>()
        val opened = mutableListOf<String?>()
        val router = HomeIntentRouter(this, { if (it == "slow") withContext(NonCancellable) { late.await() } else null }, opened::add)
        router.follow(Intent().putExtra("task", "slow")); runCurrent()
        assertEquals("slow", router.pendingTask)
        router.follow(Intent().putExtra("session", "new"))
        assertNull(router.pendingTask)
        late.complete("old"); runCurrent()
        assertEquals(listOf("new"), opened)
        router.follow(Intent().putExtra("task", "missing")); runCurrent()
        assertEquals(listOf("new", null), opened)
        val pending = CompletableDeferred<String?>()
        val homeRouter = HomeIntentRouter(this, { withContext(NonCancellable) { pending.await() } }, opened::add)
        homeRouter.follow(Intent().putExtra("task", "slow")); runCurrent()
        homeRouter.follow(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        pending.complete("late"); runCurrent()
        assertEquals(listOf("new", null, null), opened)
    }
}
