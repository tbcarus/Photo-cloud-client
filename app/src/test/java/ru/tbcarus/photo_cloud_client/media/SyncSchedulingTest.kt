package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider

/**
 * Планирование двух частей: periodic Поставщика и Загрузчика независимы, не дублируются
 * при повторном reconcile, восстанавливаются после отмены.
 * Работы здесь не выполняются: у всех periodic и у разового Загрузчика есть невыполненные constraints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncSchedulingTest {

    private lateinit var context: Application
    private lateinit var scheduler: SyncScheduler
    private lateinit var coordinator: PeriodicSyncCoordinator
    private val storage = InMemoryTokenStorage()
    private val workManager get() = WorkManager.getInstance(context)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply {
            baseUrl = "http://127.0.0.1:8080/"
        }
        val client = OkHttpClient()
        scheduler = SyncScheduler(context)
        coordinator = PeriodicSyncCoordinator(
            context,
            AuthRepository(storage, baseUrlProvider, ApiServiceFactory(baseUrlProvider, client, client, client)),
            scheduler,
            MediaChangeObserver(context, scheduler)
        )
    }

    @Test
    fun configuredAppSchedulesBothPeriodicTasksOnceAcrossReconciles() {
        grantFullAccess()
        storage.saveTokens(Tokens("access", "refresh"))

        coordinator.reconcile()
        val providerId = active(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).id
        val uploadId = active(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).id
        coordinator.reconcile()

        // Повторный reconcile не создаёт новых заданий: UPDATE сохраняет прежние.
        assertEquals(providerId, active(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).id)
        assertEquals(uploadId, active(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).id)
        assertEquals(1, infos(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).size)
        assertEquals(1, infos(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).size)
    }

    @Test
    fun logoutKeepsProviderAndCancelsUploader() {
        grantFullAccess()
        storage.saveTokens(Tokens("access", "refresh"))
        coordinator.reconcile()
        val providerId = active(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).id

        storage.clear()
        coordinator.reconcile()

        assertEquals(providerId, active(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).id)
        assertTrue(infos(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun periodicTasksAreIndependentAndRestoredAfterCancel() {
        grantFullAccess()
        storage.saveTokens(Tokens("access", "refresh"))
        coordinator.reconcile()
        val oldProviderId = active(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).id
        val uploadId = active(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).id

        scheduler.cancelProviderPeriodic()
        assertEquals(uploadId, active(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).id)

        coordinator.reconcile()
        val restored = active(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME)
        assertNotEquals(oldProviderId, restored.id)
        assertEquals(uploadId, active(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).id)
    }

    @Test
    fun missingPermissionCancelsBothPeriodicTasks() {
        grantFullAccess()
        storage.saveTokens(Tokens("access", "refresh"))
        coordinator.reconcile()

        shadowOf(context).denyPermissions(mediaReadPermission)
        coordinator.reconcile()

        assertTrue(infos(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(infos(SyncScheduler.UPLOAD_PERIODIC_WORK_NAME).all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun uploadRequestNeedsSessionAndIsNotDuplicated() {
        coordinator.requestUpload()
        assertTrue(infos(SyncScheduler.UPLOAD_WORK_NAME).isEmpty())

        storage.saveTokens(Tokens("access", "refresh"))
        coordinator.requestUpload()
        coordinator.requestUpload()

        assertEquals(1, infos(SyncScheduler.UPLOAD_WORK_NAME).size)
        assertEquals(WorkInfo.State.ENQUEUED, infos(SyncScheduler.UPLOAD_WORK_NAME).single().state)
    }

    private fun grantFullAccess() = shadowOf(context).grantPermissions(mediaReadPermission)

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun active(name: String): WorkInfo = infos(name).single { it.state == WorkInfo.State.ENQUEUED }
}
