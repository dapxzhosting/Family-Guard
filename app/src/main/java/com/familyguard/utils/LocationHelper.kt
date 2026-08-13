package com.familyguard.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.familyguard.sync.FamilyLink
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

object LocationHelper {
    @SuppressLint("MissingPermission")
    fun updateCurrentLocation(context: Context) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        
        // Coba ambil lokasi terakhir (cepat)
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                FamilyLink.updateLocation(context, it.latitude, it.longitude)
            }
        }

        // Minta update lokasi segar
        val request = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000
        ).setMaxUpdates(1).build()

        fusedLocationClient.requestLocationUpdates(request, object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.let {
                    FamilyLink.updateLocation(context, it.latitude, it.longitude)
                }
            }
        }, context.mainLooper)
    }
}
