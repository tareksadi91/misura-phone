package com.misura.phone.access

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Directions
import com.misura.core.agent.PlaceFact
import com.misura.phone.MisuraGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The directions tool with the production opener: a protected handler is refused with its own line, nothing starts. New with M1 (no prior opener to run red against). */
@RunWith(AndroidJUnit4::class)
class DirectionsAccessTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val access get() = ctx.getSharedPreferences(AppAccess.PREFS, android.content.Context.MODE_PRIVATE)
    @After fun clear() { access.edit().clear().commit() }

    @Test fun directionsNeverOpenABlockedHandler() = runBlocking<Unit> {
        val url = "https://www.google.com/maps/dir/?api=1&destination=Somewhere&destination_place_id=p1"
        val handlers = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(url)), PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct()
        assumeTrue("no handler for map links on this device", handlers.isNotEmpty())
        handlers.forEach { check(access.edit().putBoolean(it, false).commit()) }
        // The same opener the agent's registered directions tool uses (MisuraGraph).
        val tool = Directions({ MisuraGraph.openDirections(ctx, it) }, mapOf("p1" to PlaceFact("p1", "Somewhere")))
        val r = tool.run(buildJsonObject { put("placeId", JsonPrimitive("p1")) })
        check("blocked" in r["error"].toString()) { r.toString() }
    }
}
