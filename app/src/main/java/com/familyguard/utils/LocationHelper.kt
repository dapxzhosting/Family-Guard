package com.familyguard.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.familyguard.sync.FamilyLink
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

object LocationHelper {

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun updateCurrentLocation(context: Context) {
        if (!hasLocationPermission(context)) {

            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {

                    FamilyLink.updateLocation(context, location.latitude, location.longitude)
                } else {

                    requestFreshLocation(context, fusedLocationClient)
                }
            }
            .addOnFailureListener {

                requestFreshLocation(context, fusedLocationClient)
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(context: Context, client: com.google.android.gms.location.FusedLocationProviderClient) {
        if (!hasLocationPermission(context)) {

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

                        FamilyLink.updateLocation(context, location.latitude, location.longitude)
                    } else {

                        requestBalancedLocation(context, client)
                    }
                }
            }, context.mainLooper)
        } catch (e: SecurityException) {

        }
    }

    @SuppressLint("MissingPermission")
    private fun requestBalancedLocation(context: Context, client: com.google.android.gms.location.FusedLocationProviderClient) {
        if (!hasLocationPermission(context)) {

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

                        FamilyLink.updateLocation(context, it.latitude, it.longitude)
                    }
                }
            }, context.mainLooper)
        } catch (e: SecurityException) {

        }
    }
}

