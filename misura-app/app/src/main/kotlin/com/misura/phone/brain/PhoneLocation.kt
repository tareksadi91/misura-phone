package com.misura.phone.brain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is right now, for "near me" searches (SYSTEM §3 Location). Read only when a search asks for it;
 * nothing is stored. Null when location is not allowed, switched off, or no fix younger than [MAX_AGE_MS] arrives:
 * an old fix from another city is worse than none (it recreates the "results in the wrong city" bug).
 * Approximate (coarse) permission is enough.
 */
object PhoneLocation {
    const val MAX_AGE_MS = 10 * 60_000L
    private const val WAIT_MS = 5_000L

    suspend fun now(ctx: Context): Pair<Double, Double>? {
        fun allowed(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (!allowed(Manifest.permission.ACCESS_COARSE_LOCATION) && !allowed(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        if (!lm.isLocationEnabled) return null
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) return null
        val fix = try {
            // A fresh cached fix from any provider first (no wait); else ask the best provider once, within WAIT_MS.
            providers.mapNotNull { lm.getLastKnownLocation(it) }.filter { fresh(age(it)) }.maxByOrNull { it.elapsedRealtimeNanos }
                ?: withTimeoutOrNull(WAIT_MS) {
                    val best = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers } ?: providers.first()
                    suspendCancellableCoroutine<Location?> { c ->
                        val cancel = CancellationSignal()
                        c.invokeOnCancellation { cancel.cancel() }
                        lm.getCurrentLocation(best, cancel, ctx.mainExecutor) { c.resume(it) }
                    }
                }
        } catch (_: SecurityException) { null } // permission withdrawn mid-read
        return fix?.takeIf { fresh(age(it)) }?.let { it.latitude to it.longitude }
    }

    /**
     * The city ([android.location.Address.getLocality]) at [at], for the hand's "near the user" runs. Null when there is no geocoder, no
     * locality (a county or district is not a city), an error or no answer within [WAIT_MS]. The platform geocoder may ask its provider
     * (Google on GMS phones) over the network: the coordinates go there, never to the model (OSS_AUDIT data boundary).
     */
    suspend fun city(ctx: Context, at: Pair<Double, Double>): String? {
        if (!android.location.Geocoder.isPresent()) return null
        return withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine<String?> { c ->
                try {
                    android.location.Geocoder(ctx).getFromLocation(at.first, at.second, 1, object : android.location.Geocoder.GeocodeListener {
                        override fun onGeocode(found: MutableList<android.location.Address>) { if (c.isActive) c.resume(found.firstOrNull()?.locality?.trim()?.takeIf { it.isNotEmpty() }) }
                        override fun onError(msg: String?) { if (c.isActive) c.resume(null) }
                    })
                } catch (_: Exception) { if (c.isActive) c.resume(null) }
            }
        }
    }

    private fun age(l: Location) = SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos
    internal fun fresh(ageNanos: Long) = ageNanos in 0..MAX_AGE_MS * 1_000_000
}
