package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class WifiUploadNetworkTest {
    @Test fun meteredWifiIsAllowedAndCellularNeedsOptIn() {
        val context = mock(Context::class.java)
        val manager = mock(ConnectivityManager::class.java)
        val network = mock(Network::class.java)
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
        `when`(manager.activeNetwork).thenReturn(network)
        `when`(manager.getNetworkCapabilities(network)).thenReturn(capabilities)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        val policy = WifiUploadNetwork(context)

        assertEquals(network, policy.current(false))
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(false)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true)
        assertNull(policy.current(false))
        assertEquals(network, policy.current(true))
    }

    @Test fun vpnUsesActiveNetworkOnlyWhenItReportsAllowedUnderlyingTransport() {
        val context = mock(Context::class.java)
        val manager = mock(ConnectivityManager::class.java)
        val vpn = mock(Network::class.java)
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
        `when`(manager.activeNetwork).thenReturn(vpn)
        `when`(manager.getNetworkCapabilities(vpn)).thenReturn(capabilities)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        val policy = WifiUploadNetwork(context)

        assertNull(policy.current(false))
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        assertEquals(vpn, policy.current(false))
    }
}
