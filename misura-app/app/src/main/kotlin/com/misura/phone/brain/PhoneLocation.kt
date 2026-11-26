package com.misura.phone.brain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** The last fresh fix this process read, in memory only: every near-me path reuses it while it is fresh. */
    internal val held = WarmFix()

    /** A fresh fix: the one held if still fresh (no new request), else one read (and held). Location off or not allowed: none, held or not. */
    suspend fun now(ctx: Context): Pair<Double, Double>? = if (!allowed(ctx)) null else held.get { read(ctx, balanced = false) }

    /**
     * A Misura screen came to the front (owner 26 Nov: location simply there when a near-me request comes, like Google Maps): when location
     * is allowed and on and no fresh fix is held, one fix is read in the background at balanced power (no continuous updates) and held.
     */
    fun warm(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
        if (held.fresh() != null || !allowed(ctx)) return
        scope.launch { runCatching { held.get { read(ctx, balanced = true) } } }
    }

    private fun allowed(ctx: Context): Boolean {
        fun ok(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return (ok(Manifest.permission.ACCESS_COARSE_LOCATION) || ok(Manifest.permission.ACCESS_FINE_LOCATION)) && ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    }

    /** One fix and its time (elapsedRealtimeNanos), or null. [balanced] = the background warm-up's lower-power request. */
    private suspend fun read(ctx: Context, balanced: Boolean): Pair<Pair<Double, Double>, Long>? {
        if (!allowed(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
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
                        if (balanced) lm.getCurrentLocation(best, android.location.LocationRequest.Builder(0).setQuality(android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                            .setDurationMillis(WAIT_MS).build(), cancel, ctx.mainExecutor) { c.resume(it) }
                        else lm.getCurrentLocation(best, cancel, ctx.mainExecutor) { c.resume(it) }
                    }
                }
        } catch (_: SecurityException) { null } // permission withdrawn mid-read
        return fix?.takeIf { fresh(age(it)) }?.let { (it.latitude to it.longitude) to it.elapsedRealtimeNanos }
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

/**
 * One fix held in memory with its own time (never stored, logged or sent anywhere it does not go today). [get] returns it while it is fresh
 * ([PhoneLocation.MAX_AGE_MS]); else it reads once and holds the answer. Reads take turns, so a near run that comes while the warm-up is
 * still reading waits for that one instead of asking again.
 */
internal class WarmFix(private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos) {
    @Volatile private var fix: Pair<Pair<Double, Double>, Long>? = null
    private val reading = Mutex()
    fun fresh(): Pair<Double, Double>? = fix?.takeIf { PhoneLocation.fresh(clockNanos() - it.second) }?.first
    /** Holds [at], read at [nanos] (elapsedRealtimeNanos). */
    fun keep(at: Pair<Double, Double>, nanos: Long) { fix = at to nanos }
    suspend fun get(read: suspend () -> Pair<Pair<Double, Double>, Long>?): Pair<Double, Double>? = fresh() ?: reading.withLock {
        fresh() ?: read()?.also { keep(it.first, it.second) }?.first
    }
}
