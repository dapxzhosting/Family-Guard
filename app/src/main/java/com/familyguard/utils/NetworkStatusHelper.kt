package com.familyguard.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Util untuk cek koneksi internet saat ini dan memantau perubahannya
 * (dipakai bareng dengan LoadingStateController supaya skeleton loading
 * bisa "stuck" dan diganti pesan "Tidak ada jaringan" saat internet mati).
 */
object NetworkStatusHelper {

    fun isConnected(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Daftarkan listener perubahan koneksi. Kembalikan callback [unregister]
     * yang WAJIB dipanggil di onDestroy/onStop activity supaya tidak leak.
     */
    fun observe(
        context: Context,
        onChanged: (connected: Boolean) -> Unit
    ): () -> Unit {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onChanged(true)
            }

            override fun onLost(network: Network) {
                onChanged(isConnected(context))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                onChanged(
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }
        }

        // Kirim status awal segera
        onChanged(isConnected(context))

        cm.registerNetworkCallback(request, callback)
        return { cm.unregisterNetworkCallback(callback) }
    }
}
