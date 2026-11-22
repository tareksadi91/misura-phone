package com.misura.phone.notify

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationAccessTest {
    @Test fun frameworkPackageCanBeBlockedAndUnblocked() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val access = NotificationAccess(ctx)
        access.setBlocked("android", true)
        assertFalse(access.allows("android"))
        assertTrue("android" in access.blockedPackages())
        access.setBlocked("android", false)
        assertTrue(access.allows("android"))
        assertFalse("android" in access.blockedPackages())
    }

    @Test fun defaultAllowsExactBlockRevokesAndUnblockRestoresCapture() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("notification_blocks", Context.MODE_PRIVATE).edit().clear().commit()
        // Old opt-in settings, including explicit false, must not restrict an upgraded install.
        ctx.getSharedPreferences("notification_access", Context.MODE_PRIVATE).edit().putBoolean("app.test", false).commit()
        val access = NotificationAccess(ctx)
        assertTrue(access.allows("app.test"))
        assertThrows(IllegalArgumentException::class.java) { access.setBlocked("app.*", true) }
        assertThrows(IllegalArgumentException::class.java) { access.setBlocked(" app.test", true) }
        val controller = Robolectric.buildService(MisuraListener::class.java).create()
        val service = controller.get()
        var captures = 0
        MisuraListener.onCaptured = { _, _ -> captures++ }
        @Suppress("DEPRECATION")
        fun notification(pkg: String = "app.test") = StatusBarNotification(pkg, pkg, 1, null, Process.myUid(), 0, 0,
            Notification.Builder(ctx, "test").setContentTitle("Sender").setContentText("private-message").build(),
            Process.myUserHandle(), 100)
        try {
            service.onNotificationPosted(notification())
            assertEquals(1, captures)
            access.setBlocked("app.test", true)
            assertFalse(NotificationAccess(ctx).allows("app.test"))
            assertTrue(access.allows("app.test.other"))
            assertTrue(access.allows("app.Test"))
            service.onNotificationPosted(notification())
            assertEquals(1, captures)
            assertTrue(service.liveFrom("app.test").isEmpty())
            access.setBlocked("app.test", false)
            assertTrue(access.allows("app.test"))
            service.onNotificationPosted(notification())
            assertEquals(2, captures)
            service.onNotificationPosted(notification(service.packageName))
            assertEquals(2, captures)
            assertTrue(service.liveFrom(service.packageName).isEmpty())
        } finally {
            MisuraListener.onCaptured = null
            controller.destroy()
        }
    }
}
