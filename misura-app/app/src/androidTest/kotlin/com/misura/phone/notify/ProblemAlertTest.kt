package com.misura.phone.notify

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.task.Payload
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProblemAlertTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aProblemPostsTheCardsWordAndLine() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS").close()
        val t = Task("p1", "experiment", TaskState.FAILED, Payload(mapOf("channel" to "testchat", "recipient" to "Friend", "content" to "x")), emptyList(), 0, null, true)
        ProblemAlert.show(ctx, t)
        Thread.sleep(500)
        val n = ctx.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == ProblemAlert.id(t.id) }.notification
        n.extras.getString(Notification.EXTRA_TITLE) shouldBe "Failed · Friend"
        n.visibility shouldBe Notification.VISIBILITY_PRIVATE
        n.extras.getCharSequence(Notification.EXTRA_TEXT).toString() shouldBe "Check the chat before trying again."
    }
}
