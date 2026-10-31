package com.ajit.dynamicisland.module.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper

/**
 * "Current location", only while the Weather screen is open (approximate location, no background
 * access): one fix, rounded to about 1 km, saved as the place for the 30-minute updates.
 */
object Here {
    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun locate(ctx: Context, done: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null || !allowed(ctx)) { done(null); return }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (provider == null) { done(last); return }
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val cancel = CancellationSignal()
        fun finish(l: Location?) { if (!finished) { finished = true; cancel.cancel(); done(l ?: last) } }
        main.postDelayed({ finish(null) }, 12_000) // every wait has an exit
        try { lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { finish(it) } } catch (e: Exception) { finish(null) }
    }

    /** A place name for the position, from Android's geocoder where the phone has one. */
    fun name(ctx: Context, l: Location, done: (String?) -> Unit) {
        if (!Geocoder.isPresent()) { done(null); return }
        val g = Geocoder(ctx)
        fun pick(a: android.location.Address?) = a?.let { it.subLocality ?: it.locality ?: it.subAdminArea ?: it.adminArea }
        try {
            if (Build.VERSION.SDK_INT >= 33) g.getFromLocation(l.latitude, l.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(list: MutableList<android.location.Address>) { Handler(Looper.getMainLooper()).post { done(pick(list.firstOrNull())) } }
                override fun onError(msg: String?) { Handler(Looper.getMainLooper()).post { done(null) } }
            }) else Thread {
                @Suppress("DEPRECATION") val n = runCatching { pick(g.getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()) }.getOrNull()
                Handler(Looper.getMainLooper()).post { done(n) }
            }.start()
        } catch (e: Exception) { done(null) }
    }
}
