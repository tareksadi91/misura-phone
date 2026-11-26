package com.misura.phone.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.misura.core.agent.PlaceFact
import com.misura.phone.cards.M
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory

const val MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"
const val MAP_TAG = "map"

/** Holds the map once its style is loaded; changes that arrive earlier wait in [pending]. */
private class MapHolder {
    var map: MapLibreMap? = null
    var pending: (() -> Unit)? = null
    /** What the camera was last framed for: every pin's id and coordinates plus the "you" point; any change frames again. */
    var framedFor: List<Any?> = emptyList()
    /** Marker id → place id, so a tap on a pin selects that place's card (item 7, Codex P2). */
    val byMarker = mutableMapOf<Long, String>()
}

/**
 * MapLibre map: paper pins for options, orange for the card you're on, blue for [you] (design-system §4 Map). Re-applied whenever the
 * places or the selection change. Tapping it calls [onTap] (full screen); tapping a pin selects its place ([onSelect]). TalkBack: "Select <name>" per place.
 * [you] (a near-me set's own fix, never stored) is drawn only beside pins, and the camera frames it with them.
 */
@Composable
fun PlaceMap(places: List<PlaceFact>, selected: String?, onTap: () -> Unit, onSelect: (String) -> Unit, modifier: Modifier = Modifier, you: Pair<Double, Double>? = null) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { MapHolder() }
    val tap by rememberUpdatedState(onTap)
    val select by rememberUpdatedState(onSelect)
    val pinned = places.filter { it.lat != null && it.lng != null }
    val view = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply {
            onCreate(null)
            getMapAsync { map ->
                map.uiSettings.isAttributionEnabled = true; map.uiSettings.isLogoEnabled = false
                map.addOnMapClickListener { tap(); true }
                // A pin tap is the annotation's, not the map's: it selects that place and never opens the full map.
                map.setOnMarkerClickListener { m -> holder.byMarker[m.id]?.let(select); true }
                map.setStyle(MAP_STYLE) { style -> legible(style); holder.map = map; holder.pending?.invoke(); holder.pending = null }
            }
        }
    }
    val apply = {
        holder.map?.let { map ->
            map.clear(); holder.byMarker.clear()
            val icons = IconFactory.getInstance(ctx)
            pinned.forEach { p ->
                val on = p.id == selected
                val m = map.addMarker(MarkerOptions().position(LatLng(p.lat!!, p.lng!!)).title(p.label).icon(icons.fromBitmap(dot(if (on) M.orange.toArgb() else M.paper.toArgb(), if (on) 44 else 34))))
                holder.byMarker[m.id] = p.id
            }
            // The user's own spot: not a place, so no title, no card and no tap; under the pins' selection, never in byMarker.
            if (you != null && pinned.isNotEmpty()) map.addMarker(MarkerOptions().position(LatLng(you.first, you.second)).icon(icons.fromBitmap(dot(M.you.toArgb(), 30))))
            // Framed again whenever a pin moves or the "you" point comes or goes, not only when the ids change (Codex P2).
            val frame = pinned.map { listOf(it.id, it.lat, it.lng) } + listOf(you.takeIf { pinned.isNotEmpty() })
            if (frame != holder.framedFor && pinned.isNotEmpty()) {
                holder.framedFor = frame
                val points = pinned.map { LatLng(it.lat!!, it.lng!!) } + listOfNotNull(you?.let { LatLng(it.first, it.second) })
                map.moveCamera(if (points.size == 1) CameraUpdateFactory.newLatLngZoom(points[0], 15.0)
                    else CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 120))
            }
        }
        Unit
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart(); Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause(); Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            // MapLibre releases its network/file activations in onStop, not onDestroy: balance the calls first.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }
    AndroidView({ view }, modifier.testTag(MAP_TAG).semantics {
        stateDescription = pinned.firstOrNull { it.id == selected }?.label ?: ""
        customActions = pinned.map { p -> CustomAccessibilityAction("Select ${p.label}") { onSelect(p.id); true } }
    }, update = { if (holder.map != null) apply() else holder.pending = apply })
}

/**
 * Keep the existing real map and attribution; repaint its known layers as a lighter sage plane.
 * Dark labels and dark-edged pins separate map reading from the surrounding dark cards.
 */
private fun legible(style: Style) {
    fun paint(id: String, vararg p: org.maplibre.android.style.layers.PropertyValue<*>) { style.getLayer(id)?.setProperties(*p) }
    paint("background", PropertyFactory.backgroundColor("#B5BDB1"))
    paint("water", PropertyFactory.fillColor("#7E9AA5")); paint("waterway", PropertyFactory.lineColor("#7E9AA5"))
    listOf("landuse_park", "landcover_wood").forEach { paint(it, PropertyFactory.fillColor("#9BAD91")) }
    paint("landuse_residential", PropertyFactory.fillColor("#BCC2B4")); paint("building", PropertyFactory.fillColor("#AAB49F"))
    paint("highway_path", PropertyFactory.lineColor("#DCE0D3"))
    listOf("highway_minor", "highway_major_inner", "highway_major_subtle", "highway_motorway_subtle").forEach { paint(it, PropertyFactory.lineColor("#EFF0E3")) }
    listOf("highway_major_casing", "highway_motorway_casing").forEach { paint(it, PropertyFactory.lineColor("#929D87")) }
    listOf("railway", "railway_transit", "railway_minor").forEach { paint(it, PropertyFactory.lineColor("#7E8878")) }
    listOf("highway_name_other", "highway_name_motorway").forEach { paint(it, PropertyFactory.textColor("#344131"), PropertyFactory.textHaloColor("#B5BDB1")) }
    style.layers.filter { it.id.startsWith("place_") }.forEach { paint(it.id, PropertyFactory.textColor("#283325"), PropertyFactory.textHaloColor("#B5BDB1")) }
}

private fun dot(color: Int, px: Int): Bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
    val c = Canvas(b); val r = px / 2f
    c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = M.ink.toArgb() })
    c.drawCircle(r, r, r - px * 0.12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
}
