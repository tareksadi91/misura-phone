package com.misura.phone.notify

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.misura.phone.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProbeLogDiagnosticsTest {
    @Test fun debugVariantRetainsMetadataEvenWhenFastDisablesDebugging() {
        assertTrue(BuildConfig.DIAGNOSTICS)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val file = File(ctx.filesDir, "probe/notification-metadata.jsonl")
        file.delete()
        try {
            ProbeLog.append(ctx, "app.test", Captured(emptyList(), mapOf("private-extra" to "private-value"), null, false, "private-chat"))
            val line = ProbeLog.tail(ctx).single()
            assertTrue(line.contains("\"messageCount\":0"))
            assertFalse(line.contains("private-"))
        } finally { file.delete() }
    }
}
