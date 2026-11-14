package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Resumable Camera использует только сокеты активной Wi-Fi сети, включая повтор после refresh. */
open class WifiUploadNetwork @Inject constructor(@ApplicationContext private val context: Context) {
    open fun current(): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        return network.takeIf {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
