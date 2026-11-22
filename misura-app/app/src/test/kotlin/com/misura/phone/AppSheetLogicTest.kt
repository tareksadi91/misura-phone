package com.misura.phone

import com.misura.phone.screen.SheetEntry
import com.misura.phone.screen.sheetDragProgress
import com.misura.phone.screen.sheetRows
import com.misura.phone.screen.sheetRowAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSheetLogicTest {
    @Test fun rowsAboveThumbAreLitWithASoftEdgeBelowIt() {
        assertEquals(1f, sheetRowAlpha(1f, 300f, 200f, 120f, 0f), .00001f)
        assertEquals(1f, sheetRowAlpha(1f, 300f, 300f, 120f, 0f), .00001f)
        assertEquals(.5f, sheetRowAlpha(1f, 300f, 360f, 120f, 0f), .00001f)
        assertEquals(0f, sheetRowAlpha(1f, 300f, 420f, 120f, 0f), .00001f)
        assertEquals(0f, sheetRowAlpha(1f, 300f, 500f, 120f, 0f), .00001f)
    }

    @Test fun releaseLightsAllRowsContinuouslyAndClosingUsesOnlySheetFade() {
        assertEquals(.5f, sheetRowAlpha(1f, 300f, 500f, 120f, .5f), .00001f)
        assertEquals(.75f, sheetRowAlpha(1f, 300f, 360f, 120f, .5f), .00001f)
        assertEquals(1f, sheetRowAlpha(1f, 300f, 500f, 120f, 1f), .00001f)
        assertEquals(.5f, sheetRowAlpha(.335f, Float.POSITIVE_INFINITY, 500f, 120f, 0f), .00001f)
        assertEquals(0f, sheetRowAlpha(0f, Float.POSITIVE_INFINITY, 500f, 120f, 1f), .00001f)
        assertEquals(1f, sheetRowAlpha(1.1f, Float.POSITIVE_INFINITY, 500f, 120f, 0f), .00001f)
    }

    @Test fun dragTracksLinearlyWithQuarterSpeedStretchAtBothEnds() {
        listOf(0f, .12f, .5f, .55f, 1f).forEach { p -> assertEquals(p, sheetDragProgress(p), .00001f) }
        assertEquals(-.1f, sheetDragProgress(-.4f), .00001f)
        assertEquals(1.1f, sheetDragProgress(1.4f), .00001f)
        assertEquals(.95f, sheetDragProgress(.95f), .00001f)
    }

    private val apps = listOf("Maps" to "maps", "Chrome" to "chrome", "Google Chrome" to "google", "Calendar" to "calendar")

    @Test fun pinnedAppsAreExcludedFromAllAppsAndPinsKeepTheirOrder() {
        val pins = listOf(SheetEntry("maps", "Maps"), SheetEntry("chrome", "Chrome"))
        val rows = sheetRows(apps, pins, emptyMap(), "")
        assertEquals(listOf("maps", "chrome"), rows.pinned.map { it.pkg })
        assertEquals(listOf("calendar", "google"), rows.allApps.map { it.pkg })
    }

    @Test fun pinnedShortcutKeepsItsAppInAllApps() {
        val pin = SheetEntry("maps", "Maps", "home", "Go home")
        val rows = sheetRows(apps, listOf(pin), emptyMap(), "")
        assertEquals(listOf(pin), rows.pinned)
        assertTrue(rows.allApps.any { it.pkg == "maps" })
    }

    @Test fun filterUsesWordPrefixesAndNameStartsComeFirstAcrossSections() {
        val rows = sheetRows(apps, listOf(SheetEntry("google", "Google Chrome")), emptyMap(), " Ch ")
        assertEquals(listOf("Chrome", "Google Chrome"), rows.entries.map { it.name })
        assertTrue(sheetRows(apps, emptyList(), emptyMap(), "hrom").entries.isEmpty())
    }

    @Test fun launchCountsSortDescendingWithAlphabeticalTies() {
        val rows = sheetRows(apps.reversed(), emptyList(), mapOf("maps" to 4, "google" to 2, "chrome" to 2), "")
        assertEquals(listOf("Maps", "Chrome", "Google Chrome", "Calendar"), rows.allApps.map { it.name })
    }

    @Test fun noMatchReturnsNoRows() {
        val rows = sheetRows(apps, listOf(SheetEntry("maps", "Maps")), emptyMap(), "missing")
        assertEquals(emptyList<SheetEntry>(), rows.entries)
    }

    @Test fun filterStartsAtTwoLettersAndDoesNotCapResults() {
        val many = (1..8).map { "App $it" to "pkg$it" }
        assertEquals(null, sheetRows(many, emptyList(), emptyMap(), "a").filtered)
        assertEquals(8, sheetRows(many, emptyList(), emptyMap(), "ap").entries.size)
    }

    @Test fun shortcutLabelsAndNamesWithSpacesMatch() {
        val pins = listOf(SheetEntry("maps", "Maps", "home", "Go home"))
        assertEquals("home", sheetRows(apps, pins, emptyMap(), "ho").entries.single().shortcutId)
        assertEquals("Google Chrome", sheetRows(apps, pins, emptyMap(), "google c").entries.single().name)
    }
}
