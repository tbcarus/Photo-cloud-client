package ru.tbcarus.photo_cloud_client.transfer

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.media.ResumableUpload
import ru.tbcarus.photo_cloud_client.media.SyncConfig
import ru.tbcarus.photo_cloud_client.media.WorkBudget
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManualUploadQueueTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val account = BackupAccount("http://server/", "alice")

    @Test fun filesAreSentInRoomOrderAndOnlyOnce() = runBlocking {
        val name = "manual-queue-${UUID.randomUUID()}.db"
        val db = AppDatabase.build(context, name)
        try {
            val data = mapOf("content://test/1" to "one", "content://test/2" to "two")
            val docs = object : LocalDocuments(context) {
                override fun hasPersistedRead(uri: Uri) = true
                override fun describe(uri: Uri) = LocalFile(uri, if (uri.toString().endsWith("1")) "one.jpg" else "two.jpg", 3, "image/jpeg")
                override fun lastModified(uri: Uri): Long? = 1
                override fun open(uri: Uri): InputStream = ByteArrayInputStream(data.getValue(uri.toString()).toByteArray())
            }
            val sent = ArrayList<String>()
            val remote = object : TransferRemote(Mockito.mock(ApiServiceFactory::class.java)) {
                override suspend fun children(folderId: Long): RemoteResult<List<FolderDto>> = RemoteResult.Success(emptyList())
                override suspend fun files(folderId: Long, page: Int): RemoteResult<FilePageDto> = RemoteResult.Success(FilePageDto())
                override suspend fun upload(folderId: Long, name: String, body: RequestBody): RemoteResult<FileItemDto> {
                    sent += name
                    val bytes = if (name == "one.jpg") "one" else "two"
                    val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    return RemoteResult.Success(FileItemDto(id = sent.size.toLong(), checksum = checksum))
                }
            }
            val accounts = object : BackupAccountProvider(Mockito.mock(BaseUrlProvider::class.java), Mockito.mock(TokenStorage::class.java)) {
                override suspend fun current(): BackupAccount = account
                override fun observe(): Flow<BackupAccount?> = flowOf(account)
            }
            val resumable = Mockito.mock(ResumableUpload::class.java)
            Mockito.`when`(resumable.cleanupManual(account, WorkBudget.UNLIMITED)).thenReturn(true)
            val queue = ManualUploadQueue(db.manualUploadDao(), accounts, docs, remote, resumable,
                Mockito.mock(ManualUploadScheduler::class.java))
            val jobs = data.keys.mapIndexed { index, uri ->
                ManualUploadJob(UUID.randomUUID().toString(), account.serverUrl, account.accountId, uri,
                    if (index == 0) "one.jpg" else "two.jpg", 3, "image/jpeg", 1, 7, false,
                    createdAt = index.toLong())
            }
            db.manualUploadDao().insert(jobs)
            assertFalse(queue.run(WorkBudget.UNLIMITED))
            assertEquals(listOf("one.jpg", "two.jpg"), sent)
            assertTrue(jobs.all { db.manualUploadDao().get(it.id)?.status == ManualUploadJob.COMPLETED })
            assertFalse(queue.run(WorkBudget.UNLIMITED))
            assertEquals(2, sent.size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun unknownAndTooLargeFilesFailWithoutOpeningSource() = runBlocking {
        val name = "manual-sizes-${UUID.randomUUID()}.db"
        val db = AppDatabase.build(context, name)
        try {
            val accounts = object : BackupAccountProvider(Mockito.mock(BaseUrlProvider::class.java), Mockito.mock(TokenStorage::class.java)) {
                override suspend fun current(): BackupAccount = account
                override fun observe(): Flow<BackupAccount?> = flowOf(account)
            }
            val resumable = Mockito.mock(ResumableUpload::class.java)
            Mockito.`when`(resumable.cleanupManual(account, WorkBudget.UNLIMITED)).thenReturn(true)
            val queue = ManualUploadQueue(db.manualUploadDao(), accounts, Mockito.mock(LocalDocuments::class.java),
                Mockito.mock(TransferRemote::class.java), resumable, Mockito.mock(ManualUploadScheduler::class.java))
            val jobs = listOf(null, SyncConfig.RESUMABLE_MAX_BYTES + 1).mapIndexed { index, size ->
                ManualUploadJob(UUID.randomUUID().toString(), account.serverUrl, account.accountId,
                    "content://test/$index", "file$index", size, null, null, 7, false, createdAt = index.toLong())
            }
            db.manualUploadDao().insert(jobs)
            assertFalse(queue.run(WorkBudget.UNLIMITED))
            assertTrue(jobs.all { db.manualUploadDao().get(it.id)?.status == ManualUploadJob.FAILED })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun lostMultipartResponseIsReconciledByFolderNameWithoutSecondPost() = runBlocking {
        val name = "manual-uncertain-${UUID.randomUUID()}.db"
        val db = AppDatabase.build(context, name)
        try {
            val uri = Uri.parse("content://test/lost")
            val bytes = "small".toByteArray()
            val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val docs = object : LocalDocuments(context) {
                override fun hasPersistedRead(uri: Uri) = true
                override fun describe(uri: Uri) = LocalFile(uri, "small.jpg", bytes.size.toLong(), "image/jpeg")
                override fun open(uri: Uri): InputStream = ByteArrayInputStream(bytes)
            }
            var posts = 0
            var published = false
            val remote = object : TransferRemote(Mockito.mock(ApiServiceFactory::class.java)) {
                override suspend fun children(folderId: Long): RemoteResult<List<FolderDto>> = RemoteResult.Success(emptyList())
                override suspend fun files(folderId: Long, page: Int): RemoteResult<FilePageDto> =
                    RemoteResult.Success(FilePageDto(items = if (published) listOf(FileItemDto(9, originalFilename = "small.jpg",
                        size = bytes.size.toLong(), checksum = checksum)) else emptyList()))
                override suspend fun upload(folderId: Long, name: String, body: RequestBody): RemoteResult<FileItemDto> {
                    posts++
                    published = true
                    return RemoteResult.NetworkError(IOException("Ответ потерян"))
                }
            }
            val accounts = object : BackupAccountProvider(Mockito.mock(BaseUrlProvider::class.java), Mockito.mock(TokenStorage::class.java)) {
                override suspend fun current(): BackupAccount = account
                override fun observe(): Flow<BackupAccount?> = flowOf(account)
            }
            val resumable = Mockito.mock(ResumableUpload::class.java)
            Mockito.`when`(resumable.cleanupManual(account, WorkBudget.UNLIMITED)).thenReturn(true)
            val queue = ManualUploadQueue(db.manualUploadDao(), accounts, docs, remote, resumable,
                Mockito.mock(ManualUploadScheduler::class.java))
            val job = ManualUploadJob(UUID.randomUUID().toString(), account.serverUrl, account.accountId,
                uri.toString(), "small.jpg", bytes.size.toLong(), "image/jpeg", null, 7, false)
            db.manualUploadDao().insert(listOf(job))
            assertTrue(queue.run(WorkBudget.UNLIMITED))
            assertEquals(ManualUploadJob.UNCERTAIN, db.manualUploadDao().get(job.id)?.status)
            assertFalse(queue.run(WorkBudget.UNLIMITED))
            assertEquals(ManualUploadJob.COMPLETED, db.manualUploadDao().get(job.id)?.status)
            assertEquals(1, posts)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun persistedUriIsReleasedOnlyAfterLastTerminalJobIsDismissed() = runBlocking {
        val name = "manual-release-${UUID.randomUUID()}.db"
        val db = AppDatabase.build(context, name)
        try {
            val uri = Uri.parse("content://test/shared")
            var released = 0
            val docs = object : LocalDocuments(context) {
                override fun releaseRead(uri: Uri) { released++ }
            }
            val accounts = object : BackupAccountProvider(Mockito.mock(BaseUrlProvider::class.java), Mockito.mock(TokenStorage::class.java)) {
                override suspend fun current(): BackupAccount = account
                override fun observe(): Flow<BackupAccount?> = flowOf(account)
            }
            val queue = ManualUploadQueue(db.manualUploadDao(), accounts, docs,
                Mockito.mock(TransferRemote::class.java), Mockito.mock(ResumableUpload::class.java),
                Mockito.mock(ManualUploadScheduler::class.java))
            val jobs = (1..2).map {
                ManualUploadJob(UUID.randomUUID().toString(), account.serverUrl, account.accountId,
                    uri.toString(), "file$it", 5, null, null, 7, false, status = ManualUploadJob.COMPLETED)
            }
            db.manualUploadDao().insert(jobs)
            queue.dismiss(jobs[0].id)
            assertEquals(0, released)
            queue.dismiss(jobs[1].id)
            assertEquals(1, released)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
