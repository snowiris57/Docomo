package io.github.snowiris57.reconnect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * いまの場所を、なるべく電池を使わずに 1 回だけ調べる（「通信が悪かった場所」の記録用）。
 * 1) ほかのアプリなどが最近調べた場所があればそれを使う
 * 2) 省電力の測位（基地局・Wi-Fi）
 * 3) [allowGps] のときだけ GPS（通信が止まっていても測れる。悪いときだけ使う）
 * Google Play 開発者サービスには頼らず、Android に元からある LocationManager を使う。
 */
object Locator {
    /** これより粗い結果は、場所の記録には使わない（m） */
    private const val MAX_ACCURACY_M = 300f

    fun granted(ctx: Context): Boolean =
        has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** アプリを閉じている間も場所を調べられるか（「常に許可」） */
    fun backgroundGranted(ctx: Context): Boolean = has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun has(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun locate(ctx: Context, allowGps: Boolean): Location? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return try {
            if (!lm.isLocationEnabled) return null
            recent(lm, 30_000)?.let { return it }
            val light = when {
                Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            light?.let { p -> request(lm, p, 6_000)?.takeIf { it.accuracy <= MAX_ACCURACY_M }?.let { return it } }
            if (allowGps && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                request(lm, LocationManager.GPS_PROVIDER, 15_000)?.takeIf { it.accuracy <= MAX_ACCURACY_M }?.let { return it }
            }
            // 測れなかったら、少し前（3 分以内）の場所で代用する
            recent(lm, 180_000)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 最近（[maxAgeMs] 以内）に分かっている場所のうち、いちばん正確なもの */
    @SuppressLint("MissingPermission")
    private fun recent(lm: LocationManager, maxAgeMs: Long): Location? {
        val now = SystemClock.elapsedRealtimeNanos()
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { (now - it.elapsedRealtimeNanos) / 1_000_000 <= maxAgeMs && it.hasAccuracy() && it.accuracy <= MAX_ACCURACY_M }
            .minByOrNull { it.accuracy }
    }

    @SuppressLint("MissingPermission")
    private suspend fun request(lm: LocationManager, provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs + 1_000) {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    val done = { loc: Location? -> if (cont.isActive) cont.resume(loc) }
                    if (Build.VERSION.SDK_INT >= 31) {
                        val req = LocationRequest.Builder(0)
                            .setQuality(
                                if (provider == LocationManager.GPS_PROVIDER) LocationRequest.QUALITY_HIGH_ACCURACY
                                else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                            )
                            .setDurationMillis(timeoutMs)
                            .build()
                        lm.getCurrentLocation(provider, req, cancel, Runnable::run) { done(it) }
                    } else {
                        lm.getCurrentLocation(provider, cancel, Runnable::run) { done(it) }
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            lm.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }
}
