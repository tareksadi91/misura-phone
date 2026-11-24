package com.misura.phone.brain

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.phone.setup.SetupActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The real LocationManager path behind "near me" searches, fed by a test provider. */
class PhoneLocationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    /** Reads to the end, so the command has finished before the next step. */
    private fun shell(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)).use { it.readBytes().decodeToString() }

    @Test fun allowedAndInFrontGivesTheFix() = runBlocking {
        shell("pm grant ${ctx.packageName} android.permission.ACCESS_COARSE_LOCATION")
        shell("cmd location set-location-enabled true")
        shell("appops set com.android.shell android:mock_location allow")
        shell("cmd location providers add-test-provider fused")
        shell("cmd location providers set-test-provider-enabled fused true")
        shell("cmd location providers set-test-provider-location fused --location 48.137,11.575")
        // While-in-use location: Android answers only while one of Misura's screens is in front, as in real use.
        val front = ActivityScenario.launch(SetupActivity::class.java)
        try {
            val (lat, lng) = PhoneLocation.now(ctx)!!
            assertEquals(48.137, lat, 0.05); assertEquals(11.575, lng, 0.05) // approximate permission may blur it a little
        } finally { shell("cmd location providers remove-test-provider fused"); front.close() }
    }

    @Test fun locationOffGivesNothing() = runBlocking {
        shell("pm grant ${ctx.packageName} android.permission.ACCESS_COARSE_LOCATION")
        shell("cmd location set-location-enabled false")
        try { assertNull(PhoneLocation.now(ctx)) } finally { shell("cmd location set-location-enabled true") }
    }
}
