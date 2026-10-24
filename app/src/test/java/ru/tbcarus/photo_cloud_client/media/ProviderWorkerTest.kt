package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.auth.AuthRepository
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.row
import ru.tbcarus.photo_cloud_client.media.FakeMediaStore.Companion.sha256

/**
 * Поставщик в процессе без UI. У [ProviderWorker] нет сетевых зависимостей, адреса Server и токенов —
 * поэтому тесты не поднимают сервер и не сохраняют адрес: локальная обработка проходит и без них.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProviderWorkerTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var media: FakeMediaStore
    private val storage = InMemoryTokenStorage()
    private val syncStatusStore = SyncStatusStore()

    private val dao get() = db.mediaFileDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = FakeMediaStore.install(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun newPhotoReachesChecksumReadyWithoutServerAndTokens() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))

        val result = runProvider()

        assertTrue(result is ListenableWorker.Result.Success)
        val file = dao.single(image.id)
        assertEquals(MediaFileStatus.CHECKSUM_READY, file.status)
        assertEquals(sha256(image.bytes), file.checksum)
        // Без пары (как после logout) Поставщик не будит Загрузчика, но подготовленная запись остаётся в Room.
        assertTrue(uploadWork().isEmpty())
    }

    @Test
    fun preparedPhotoRequestsUploaderWhenSessionExists() = runBlocking {
        grantFullAccess()
        storage.saveTokens(Tokens("access", "refresh"))
        media.add(image(1, "photo-1"))

        runProvider()

        assertEquals(1, uploadWork().size)
    }

    @Test
    fun rescanDoesNotRehashUnchangedPhoto() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))

        runProvider()
        runProvider()

        assertEquals(1, media.openCount(image.id))
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(image.id).status)
    }

    @Test
    fun changedSyncedPhotoGetsNewChecksumAndIsPreparedForUpload() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "old content"))
        dao.insert(row(image, MediaFileStatus.SYNCED, checksum = sha256(image.bytes), serverFileId = 77))
        // Тот же MediaStore ID, другое содержимое, размер и время изменения.
        image.bytes = "new and longer content".toByteArray()
        image.dateModifiedSec += 60

        runProvider()

        val file = dao.single(image.id)
        assertEquals(MediaFileStatus.CHECKSUM_READY, file.status)
        assertEquals(sha256(image.bytes), file.checksum)
        assertNull(file.serverFileId)
        assertEquals(image.size, file.size)
    }

    @Test
    fun photoDeletedFromDeviceIsRemovedOnlyFromRoom() = runBlocking {
        grantFullAccess()
        val kept = media.add(image(1, "kept"))
        val deleted = image(2, "deleted")
        dao.insert(row(deleted, MediaFileStatus.SYNCED, checksum = sha256(deleted.bytes), serverFileId = 55))

        runProvider()

        assertNull(dao.find(deleted.id))
        assertNotNull(dao.find(kept.id))
    }

    @Test
    fun incompleteSnapshotDoesNotDeleteMissingRows() = runBlocking {
        grantFullAccess()
        val missing = image(2, "missing from snapshot")
        dao.insert(row(missing, MediaFileStatus.SYNCED, checksum = sha256(missing.bytes), serverFileId = 55))
        val visible = media.add(image(1, "visible"))
        media.brokenRows = 1

        runProvider()

        assertEquals(MediaFileStatus.SYNCED, dao.single(missing.id).status)
        // Читаемые строки неполного снимка обрабатываются как обычно.
        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(visible.id).status)
    }

    @Test
    fun mediaStoreErrorDoesNotDeleteRows() = runBlocking {
        grantFullAccess()
        val existing = image(2, "existing")
        dao.insert(row(existing, MediaFileStatus.SYNCED, checksum = sha256(existing.bytes), serverFileId = 55))
        media.failQuery = true

        val result = runProvider()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(MediaFileStatus.SYNCED, dao.single(existing.id).status)
    }

    @Test
    fun missingPermissionDoesNotTouchRoom() = runBlocking {
        val existing = image(2, "existing")
        dao.insert(row(existing, MediaFileStatus.SYNCED, checksum = sha256(existing.bytes), serverFileId = 55))

        val result = runProvider()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, media.queryCount)
        assertEquals(MediaFileStatus.SYNCED, dao.single(existing.id).status)
    }

    @Test
    @Config(sdk = [34])
    fun partialPhotoAccessIsNotTreatedAsWholeLibrary() = runBlocking {
        // Android 14+: «выбранные фото» — только READ_MEDIA_VISUAL_USER_SELECTED, без READ_MEDIA_IMAGES.
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        media.add(image(1, "selected"))
        val notSelected = image(2, "not selected")
        dao.insert(row(notSelected, MediaFileStatus.SYNCED, checksum = sha256(notSelected.bytes), serverFileId = 55))

        val result = runProvider()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, media.queryCount)
        assertEquals(MediaFileStatus.SYNCED, dao.single(notSelected.id).status)
    }

    @Test
    @Config(sdk = [34])
    fun fullPhotoAccessOnAndroid14ScansAndCleansRoom() = runBlocking {
        // «Разрешить все» на Android 14+: выдан READ_MEDIA_IMAGES (USER_SELECTED рядом не мешает).
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        val visible = media.add(image(1, "visible"))
        val deleted = image(2, "deleted")
        dao.insert(row(deleted, MediaFileStatus.SYNCED, checksum = sha256(deleted.bytes), serverFileId = 55))

        runProvider()

        assertEquals(MediaFileStatus.CHECKSUM_READY, dao.single(visible.id).status)
        assertNull(dao.find(deleted.id))
    }

    @Test
    @Config(sdk = [34])
    fun android14RequestsFullAndPartialAccessTogether() {
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            mediaPermissionRequest.toList()
        )
        // Полным доступом считается только READ_MEDIA_IMAGES.
        assertEquals(Manifest.permission.READ_MEDIA_IMAGES, mediaReadPermission)
    }

    @Test
    fun interruptedHashingIsRecovered() = runBlocking {
        grantFullAccess()
        val image = media.add(image(1, "photo-1"))
        dao.insert(row(image, MediaFileStatus.HASHING))

        runProvider()

        val file = dao.single(image.id)
        assertEquals(MediaFileStatus.CHECKSUM_READY, file.status)
        assertEquals(sha256(image.bytes), file.checksum)
    }

    @Test
    fun scansDoNotRunConcurrently() = runBlocking {
        grantFullAccess()
        media.add(image(1, "photo-1"))
        media.queryDelayMs = 200
        // Одинаковый экземпляр, как @Singleton в приложении: и Поставщик, и UI вызывают его scanImages.
        val repository = MediaFileRepository(dao, MediaStoreRepository(context))

        listOf(
            async(Dispatchers.Default) { repository.scanImages() },
            async(Dispatchers.Default) { repository.scanImages() }
        ).awaitAll()

        assertEquals(2, media.queryCount)
        assertEquals(1, media.maxConcurrentQueries)
    }

    private fun grantFullAccess() = shadowOf(context).grantPermissions(mediaReadPermission)

    private fun image(id: Long, content: String) =
        FakeMediaStore.Image(id = id, bytes = content.toByteArray(), dateModifiedSec = 1_700_000_000L + id)

    private fun uploadWork() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.UPLOAD_WORK_NAME).get()

    private suspend fun runProvider(): ListenableWorker.Result {
        val baseUrlProvider = BaseUrlProvider(ServerPreferences(context))
        val client = OkHttpClient()
        val scheduler = SyncScheduler(context, syncStatusStore)
        // Настоящий координатор: сигнал Загрузчику зависит только от наличия пары.
        val coordinator = PeriodicSyncCoordinator(
            context,
            AuthRepository(storage, baseUrlProvider, ApiServiceFactory(baseUrlProvider, client, client, client)),
            scheduler,
            MediaChangeObserver(context, scheduler)
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ) = ProviderWorker(
                appContext,
                workerParameters,
                MediaFileRepository(dao, MediaStoreRepository(appContext)),
                ChecksumRepository(dao, appContext),
                syncStatusStore,
                coordinator
            )
        }
        return TestListenableWorkerBuilder<ProviderWorker>(context).setWorkerFactory(factory).build().doWork()
    }
}
