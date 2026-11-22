package com.misura.phone.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkingServiceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** Android crashes the app if a started foreground service is stopped before it called startForeground. */
    @Test fun stopRightAfterStartDoesNotCrash() {
        repeat(20) { WorkingService.start(ctx); WorkingService.idle(ctx) }
        // Past the system's startForeground deadline: a crash here kills the test process. (Whether it ends stopped depends on rechecks the app may be running.)
        Thread.sleep(12_000)
    }
}
