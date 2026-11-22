package com.misura.phone.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.misura.phone.sleep.SleepMode
import com.misura.phone.sleep.Variant
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkingServiceDemandTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `turning the sleep screen off keeps a running send protected`() {
        WorkingService.start(ctx) // a send is in flight
        SleepMode.set(ctx, Variant.ACTIVITY)
        SleepMode.set(ctx, Variant.OFF)
        WorkingService.workWanted shouldBe true
    }
}
