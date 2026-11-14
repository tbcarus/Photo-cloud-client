package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.timeout
import org.mockito.ArgumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.backup.FolderBackupScheduler
import ru.tbcarus.photo_cloud_client.core.server.BackupNetworkMode
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AutomaticBackupNetworkTest {
    private lateinit var context: Application
    private lateinit var preferences: ServerPreferences
    private lateinit var transport: RecordingTransport
    private lateinit var policy: AutomaticBackupNetwork

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        preferences = ServerPreferences(context)
        preferences.saveBackupNetworkMode(BackupNetworkMode.WIFI_ONLY)
        transport = RecordingTransport(context)
        policy = AutomaticBackupNetwork(context, preferences, transport,
            mock(SyncScheduler::class.java), mock(FolderBackupScheduler::class.java))
    }

    @Test fun modeChangeTakesEffectWithoutRestart() = runBlocking {
        assertNull(policy.current())
        assertEquals(false, transport.lastAllowMobile)

        preferences.saveBackupNetworkMode(BackupNetworkMode.WIFI_AND_MOBILE)
        assertEquals(BackupNetworkMode.WIFI_AND_MOBILE, ServerPreferences(context).backupNetworkMode().first())
        assertEquals(transport.mobileNetwork, policy.current())
        assertEquals(true, transport.lastAllowMobile)

        preferences.saveBackupNetworkMode(BackupNetworkMode.WIFI_ONLY)
        assertNull(policy.current())
        assertEquals(false, transport.lastAllowMobile)
    }

    @Test fun allowedNetworkReturningSchedulesCameraAndSaf() {
        val mockedContext = mock(Context::class.java)
        val manager = mock(ConnectivityManager::class.java)
        `when`(mockedContext.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
        val mockedPreferences = mock(ServerPreferences::class.java)
        `when`(mockedPreferences.backupNetworkMode()).thenReturn(flowOf(BackupNetworkMode.WIFI_ONLY))
        val returned = mock(Network::class.java)
        val firstCheck = CountDownLatch(1)
        val switchingTransport = object : WifiUploadNetwork(mockedContext) {
            @Volatile var available = false
            override fun current(allowMobile: Boolean): Network? {
                firstCheck.countDown()
                return if (available) returned else null
            }
        }
        val camera = mock(SyncScheduler::class.java)
        val saf = mock(FolderBackupScheduler::class.java)
        val observer = AutomaticBackupNetwork(mockedContext, mockedPreferences, switchingTransport, camera, saf)

        observer.start()
        assertEquals(true, firstCheck.await(2, TimeUnit.SECONDS))
        val callback = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(manager, timeout(2_000)).registerDefaultNetworkCallback(callback.capture())
        switchingTransport.available = true
        callback.value.onCapabilitiesChanged(returned, mock(NetworkCapabilities::class.java))

        verify(camera, timeout(2_000)).enqueueUpload()
        verify(saf, timeout(2_000)).enqueue()
    }

    private class RecordingTransport(context: Context) : WifiUploadNetwork(context) {
        val mobileNetwork: Network = mock(Network::class.java)
        var lastAllowMobile: Boolean? = null
        override fun current(allowMobile: Boolean): Network? {
            lastAllowMobile = allowMobile
            return if (allowMobile) mobileNetwork else null
        }
    }
}
