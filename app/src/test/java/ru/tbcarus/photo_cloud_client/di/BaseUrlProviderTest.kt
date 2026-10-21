package ru.tbcarus.photo_cloud_client.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences

class BaseUrlProviderTest {

    private val ip = MutableStateFlow<String?>(null)
    private val port = MutableStateFlow<String?>(null)
    private val serverPreferences = mock(ServerPreferences::class.java).also {
        `when`(it.getIp()).thenReturn(ip)
        `when`(it.getPort()).thenReturn(port)
    }
    private val provider = BaseUrlProvider(serverPreferences)

    @Test
    fun reloadRestoresSavedAddressIntoEmptyMemory() = runBlocking {
        ip.value = "192.168.1.10"
        port.value = "8080"

        assertEquals("http://192.168.1.10:8080/", provider.reload())
        assertEquals("http://192.168.1.10:8080/", provider.baseUrl)
    }

    @Test
    fun reloadReplacesStaleAddressWithNewlySaved() = runBlocking {
        provider.baseUrl = "http://192.168.1.10:8080/"
        ip.value = "192.168.1.20"
        port.value = "9090"

        assertEquals("http://192.168.1.20:9090/", provider.reload())
    }

    @Test
    fun missingSettingsClearStaleAddress() = runBlocking {
        provider.baseUrl = "http://192.168.1.10:8080/"

        assertEquals("", provider.reload())
        assertFalse(provider.isReady)
    }

    @Test
    fun invalidSettingsClearStaleAddress() = runBlocking {
        provider.baseUrl = "http://192.168.1.10:8080/"
        ip.value = "999.1.1.1"
        port.value = "8080"

        assertEquals("", provider.reload())
        assertFalse(provider.isReady)
    }
}
