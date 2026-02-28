package com.nova.companion.brain.context.collectors

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.nova.companion.brain.context.ContextSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationCollector {
    private const val TAG = "LocationCollector"
    private const val PREFS_NAME = "nova_location_prefs"
    private const val KEY_HOME_LAT = "home_lat"
    private const val KEY_HOME_LNG = "home_lng"
    private const val KEY_WORK_LAT = "work_lat"
    private const val KEY_WORK_LNG = "work_lng"
    private const val LOCATION_RADIUS_METERS = 200.0

    suspend fun collect(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return snapshot
        }

        return try {
            val location = getLastLocation(context) ?: return snapshot
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val isHome = isNearSavedLocation(location, prefs, KEY_HOME_LAT, KEY_HOME_LNG)
            val isWork = isNearSavedLocation(location, prefs, KEY_WORK_LAT, KEY_WORK_LNG)

            val label = when {
                isHome -> "home"
                isWork -> "work"
                else -> null
            }

            snapshot.copy(
                isHome = isHome,
                isWork = isWork,
                locationLabel = label
            )
        } catch (e: Exception) {
            Log.e(TAG, "Location collection error", e)
            snapshot
        }
    }

    private suspend fun getLastLocation(context: Context): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        cont.resume(location)
                    }
                    .addOnFailureListener {
                        cont.resume(null)
                    }
            } catch (e: Exception) {
                cont.resume(null)
            }
        }

    private fun isNearSavedLocation(
        current: Location,
        prefs: SharedPreferences,
        latKey: String,
        lngKey: String
    ): Boolean {
        val savedLat = prefs.getFloat(latKey, Float.MIN_VALUE)
        val savedLng = prefs.getFloat(lngKey, Float.MIN_VALUE)
        if (savedLat == Float.MIN_VALUE || savedLng == Float.MIN_VALUE) return false

        val results = FloatArray(1)
        Location.distanceBetween(current.latitude, current.longitude, savedLat.toDouble(), savedLng.toDouble(), results)
        return results[0] <= LOCATION_RADIUS_METERS
    }

    /**
     * Save the current location as "home".
     * Call this from Settings when user taps "Set Home Location".
     */
    fun saveHomeLocation(context: Context, lat: Double, lng: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_HOME_LAT, lat.toFloat())
            .putFloat(KEY_HOME_LNG, lng.toFloat())
            .apply()
    }

    fun saveWorkLocation(context: Context, lat: Double, lng: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_WORK_LAT, lat.toFloat())
            .putFloat(KEY_WORK_LNG, lng.toFloat())
            .apply()
    }
}
