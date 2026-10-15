package ru.tbcarus.photo_cloud_client.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleTest {

    private val plainClient = NetworkModule.providePlainOkHttpClient(NetworkModule.provideLoggingInterceptor())

    @Test
    fun registerClientHasLongerReadAndCallTimeoutsAndNoRetry() {
        val registerClient = NetworkModule.provideRegisterOkHttpClient(plainClient)

        assertEquals(5_000, registerClient.connectTimeoutMillis)
        assertEquals(5_000, registerClient.writeTimeoutMillis)
        assertEquals(30_000, registerClient.readTimeoutMillis)
        assertEquals(40_000, registerClient.callTimeoutMillis)
        assertFalse(registerClient.retryOnConnectionFailure)
    }

    @Test
    fun plainClientKeepsItsSettings() {
        NetworkModule.provideRegisterOkHttpClient(plainClient)

        assertEquals(5_000, plainClient.connectTimeoutMillis)
        assertEquals(5_000, plainClient.writeTimeoutMillis)
        assertEquals(5_000, plainClient.readTimeoutMillis)
        assertEquals(0, plainClient.callTimeoutMillis)
        assertTrue(plainClient.retryOnConnectionFailure)
    }
}
