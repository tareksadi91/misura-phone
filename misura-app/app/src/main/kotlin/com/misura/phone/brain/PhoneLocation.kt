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
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in lm.getProviders(true) } ?: return null
        val fix = try {
            withTimeoutOrNull(WAIT_MS) {
                suspendCancellableCoroutine<Location?> { c ->
                    val cancel = CancellationSignal()
                    c.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { c.resume(it) }
                }
            } ?: lm.getLastKnownLocation(provider)
        } catch (_: SecurityException) { null } // permission withdrawn mid-read
        return fix?.takeIf { fresh(SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) }?.let { it.latitude to it.longitude }
    }

    internal fun fresh(ageNanos: Long) = ageNanos in 0..MAX_AGE_MS * 1_000_000
}
