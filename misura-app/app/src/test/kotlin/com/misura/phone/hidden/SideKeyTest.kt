package com.misura.phone.hidden

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SideKeyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun key(scan: Int, code: Int = KeyEvent.KEYCODE_UNKNOWN, action: Int = KeyEvent.ACTION_DOWN) =
        KeyEvent(0L, 0L, action, code, 0, 0, 0, scan)

    @Test fun learnsOnlyAnUnknownDownAndRemembersOnlyThatScan() {
        val prefs = ctx.getSharedPreferences("keys", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit(); SideKey.load(ctx)
            assertFalse(SideKey.matches(ctx, key(250, KeyEvent.KEYCODE_VOLUME_UP)))
            assertFalse(SideKey.matches(ctx, key(250, KeyEvent.KEYCODE_POWER)))
            assertFalse(SideKey.matches(ctx, key(0)))
            assertFalse(SideKey.matches(ctx, key(250, action = KeyEvent.ACTION_UP)))
            assertEquals(-1, prefs.getInt("scan", -1))
            assertTrue(SideKey.matches(ctx, key(250)))
            assertEquals(250, prefs.getInt("scan", -1))
            assertTrue(SideKey.matches(ctx, key(250, action = KeyEvent.ACTION_UP)))
            assertFalse(SideKey.matches(ctx, key(251)))
            assertFalse(SideKey.matches(ctx, key(250, KeyEvent.KEYCODE_VOLUME_DOWN)))
            prefs.edit().putInt("scan", 251).putBoolean("side", true).commit(); SideKey.load(ctx)
            assertTrue(SideKey.seen)
            assertFalse(SideKey.matches(ctx, key(250)))
            assertTrue(SideKey.matches(ctx, key(251)))
        } finally {
            prefs.edit().clear().commit()
            SideKey.load(ctx)
        }
    }
}
