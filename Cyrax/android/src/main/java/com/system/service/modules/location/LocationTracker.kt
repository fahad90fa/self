// [context: Kotlin, Android API 26+, fused location + GPS fallback]
package com.system.service.modules.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.*
import org.json.JSONObject
import kotlinx.coroutines.*

class LocationTracker(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun startTracking(intervalMs: Long = 300_000L, onLocation: (JSONObject) -> Unit) {
        try {
            fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateDistanceMeters(50f)
                .build()
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { onLocation(toJson(it)) }
                }
            }
            fusedClient?.requestLocationUpdates(req, locationCallback!!, Looper.getMainLooper())
        } catch (_: Exception) {
            fallbackGPS(intervalMs, onLocation)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackGPS(intervalMs: Long, onLocation: (JSONObject) -> Unit) {
        scope.launch {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            val provider = when {
                LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                else -> return@launch
            }
            lm.requestLocationUpdates(provider, intervalMs, 50f, object : LocationListener {
                override fun onLocationChanged(loc: Location) { onLocation(toJson(loc)) }
            }, Looper.getMainLooper())
        }
    }

    private fun toJson(loc: Location) = JSONObject().apply {
        put("lat", loc.latitude)
        put("lon", loc.longitude)
        put("accuracy", loc.accuracy)
        put("altitude", loc.altitude)
        put("speed", loc.speed)
        put("provider", loc.provider)
        put("ts", loc.time)
    }

    fun stop() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        scope.cancel()
    }
}
