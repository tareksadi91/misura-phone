package com.misura.phone.notify

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the existing fake chat app; never sends to a real account. */
@RunWith(AndroidJUnit4::class)
class ReadNotificationsDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val pkg = "com.misura.testapp"

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }

    @Test fun currentNotificationCanBeReadButBlockingHidesItImmediately() = runBlocking<Unit> {
        val access = NotificationAccess(ctx)
        val allowed = access.allows(pkg)
        val text = "Notification reader fixture ${System.nanoTime()}"
        try {
            access.setBlocked(pkg, false)
            shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            shell("am start -W -n $pkg/.ChatActivity")
            shell("input keyevent KEYCODE_HOME")
            shell("cmd notification allow_listener ${ctx.packageName}/com.misura.phone.notify.MisuraListener")
            ctx.sendBroadcast(Intent("com.misura.testapp.POST").setPackage(pkg)
                .putExtra("conv", "notification-reader").putExtra("sender", "Friend")
                .putExtra("text", text))
            val tool = ReadNotifications(ctx)
            val args = buildJsonObject { put("app", pkg) }
            var found = false
            val end = System.currentTimeMillis() + 10_000
            while (!found && System.currentTimeMillis() < end) {
                found = tool.run(args)["notifications"]?.jsonArray?.any {
                    it.jsonObject["text"]!!.jsonPrimitive.content.contains(text)
                } == true
                if (!found) delay(100)
            }
            assertTrue("Posted notification must reach model-facing read tool", found)
            access.setBlocked(pkg, true)
            assertTrue(tool.run(args)["notifications"]!!.jsonArray.isEmpty())
            // Reading did not dismiss or mark the source notification read.
            access.setBlocked(pkg, false)
            assertTrue(tool.run(args)["notifications"]!!.jsonArray.any {
                it.jsonObject["text"]!!.jsonPrimitive.content.contains(text)
            })
        } finally {
            access.setBlocked(pkg, !allowed)
        }
    }
}
