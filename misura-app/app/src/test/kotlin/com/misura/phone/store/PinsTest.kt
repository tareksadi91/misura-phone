package com.misura.phone.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.misura.phone.screen.SheetEntry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("app_sheet", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun pinsAndCountsSurviveANewStoreAndShortcutsUnpinSeparately() {
        val app = SheetEntry("maps", "Maps")
        val shortcut = SheetEntry("maps", "Maps", "home", "Go home")
        Pins(context).apply { toggle(app); toggle(shortcut); launched("maps"); launched("maps") }
        val restored = Pins(context)
        assertEquals(listOf(app, shortcut), restored.entries())
        assertEquals(mapOf("maps" to 2), restored.launchCounts())
        restored.toggle(shortcut)
        assertEquals(listOf(app), Pins(context).entries())
    }

    @Test fun malformedPinsDoNotDropOtherEntriesOrLaunchCounts() {
        prefs.edit().putString("pins", """[{}, {"pkg":"maps","name":"Maps"}]""").putInt("launch:maps", 3).commit()
        assertEquals(listOf(SheetEntry("maps", "Maps")), Pins(context).entries())
        assertEquals(mapOf("maps" to 3), Pins(context).launchCounts())
    }
}
