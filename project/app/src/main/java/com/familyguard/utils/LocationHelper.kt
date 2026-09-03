package com.familyguard.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.familyguard.sync.FamilyLink
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

object LocationHelper {
    private const val TAG = "LocationHelper"

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun updateCurrentLocation(context: Context) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Skip update: izin lokasi belum diberikan")
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    Log.d(TAG, "Last location found: ${location.latitude}, ${location.longitude}")
                    FamilyLink.updateLocation(context, location.latitude, location.longitude)
                } else {
                    Log.d(TAG, "Last location is null, requesting fresh update")
                    requestFreshLocation(context, fusedLocationClient)
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to get last location", it)
                requestFreshLocation(context, fusedLocationClient)
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(context: Context, client: com.google.android.gms.location.FusedLocationProviderClient) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Skip fresh location: izin lokasi belum diberikan")
            return
        }
        val request = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10000
        )
            .setMinUpdateIntervalMillis(5000)
            .setMaxUpdates(1)
            .setDurationMillis(30000)
            .build()

        try {
            client.requestLocationUpdates(request, object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    val location = result.lastLocation
                    if (location != null) {
                        Log.d(TAG, "Fresh location: ${location.latitude}, ${location.longitude}")
                        FamilyLink.updateLocation(context, location.latitude, location.longitude)
                    } else {
                        Log.w(TAG, "Fresh location result is null, trying balanced accuracy")
                        requestBalancedLocation(context, client)
                    }
                }
            }, context.mainLooper)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException saat request fresh location", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestBalancedLocation(context: Context, client: com.google.android.gms.location.FusedLocationProviderClient) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Skip balanced location: izin lokasi belum diberikan")
            return
        }
        val request = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10000
        )
            .setMaxUpdates(1)
            .build()

        try {
            client.requestLocationUpdates(request, object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    result.lastLocation?.let {
                        Log.d(TAG, "Balanced location: ${it.latitude}, ${it.longitude}")
                        FamilyLink.updateLocation(context, it.latitude, it.longitude)
                    }
                }
            }, context.mainLooper)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException saat request balanced location", e)
        }
    }
}