package com.meshtalk.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Fetches a single fresh location fix for attaching to an outgoing message.
 * Uses getCurrentLocation (not requestLocationUpdates) since we only need one point
 * per "send my location" tap, not continuous tracking.
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Returns null if permission is missing or no fix could be obtained (e.g. GPS/network off). */
    @SuppressLint("MissingPermission") // guarded by hasPermission() check before every call site
    suspend fun getCurrentLocation(): GeoPoint? {
        if (!hasPermission()) return null

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        return suspendCancellableCoroutine { continuation ->
            client.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    val point = location?.let { GeoPoint(it.latitude, it.longitude) }
                    if (continuation.isActive) continuation.resume(point)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }
}
