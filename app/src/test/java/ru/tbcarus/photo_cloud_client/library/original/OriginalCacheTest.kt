package ru.tbcarus.photo_cloud_client.library.original

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.network.AuthInterceptor
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.Tokens
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.transfer.DownloadedFile
import ru.tbcarus.photo_cloud_client.transfer.RemoteResult
import ru.tbcarus.photo_cloud_client.transfer.TransferRemote
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest

class OriginalCacheTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val dir by lazy { temporary.newFolder("originals") }
    private val tempDir by lazy { temporary.newFolder("originals-tmp") }
    private val content = HashMap<Long, ByteArray>()
    private val calls = mutableListOf<Long>()
    private var free = Long.MAX_VALUE

    // Скачивание пишет байты файла [fileId] так же, как TransferRemote: целиком в target, со своим размером и SHA-256.
    private var download: suspend (Long, File) -> RemoteResult<DownloadedFile> = { fileId, target ->
        calls += fileId
        val bytes = content.getValue(fileId)
        target.writeBytes(bytes)
        RemoteResult.Success(DownloadedFile(bytes.size.toLong(), sha256(bytes)))
    }

    private fun cache(maxBytes: Long = 1_000_000) =
        OriginalCache(dir, tempDir, maxBytes, { id, target -> download(id, target) }, { free })

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun put(fileId: Long, size: Int, seed: Int = fileId.toInt()): String {
        val value = bytes(size, seed)
        content[fileId] = value
        return sha256(value)
    }

    @Test
    fun downloadIsVerifiedAndPublishedUnderChecksum() = runBlocking {
        val checksum = put(1, 500)
        val cache = cache()

        val result = cache.open(1, checksum, 500) as OriginalResult.Ready

        assertEquals(File(dir, checksum), result.file)
        assertArrayEquals(content[1], result.file.readBytes())
        // Во временном каталоге ничего не остаётся, имени файла Server в кеше нет.
        assertTrue(tempDir.listFiles().isNullOrEmpty())
        assertEquals(listOf(checksum), dir.list()!!.toList())
    }

    @Test
    fun cachedCopyIsReusedForAnyFileIdAndServer() = runBlocking {
        val checksum = put(1, 500)
        content[77] = content.getValue(1)
        val cache = cache()

        cache.open(1, checksum, 500)
        // Другой ID (например, тот же файл после смены адреса Server) с тем же содержимым — без скачивания.
        val again = cache.open(77, checksum, 500)
        val upper = cache.open(1, checksum.uppercase(), 500)

        assertTrue(again is OriginalResult.Ready)
        assertTrue(upper is OriginalResult.Ready)
        assertEquals(listOf(1L), calls)
    }

    @Test
    fun wrongSizeOrChecksumIsRejectedAndNothingIsCached() = runBlocking {
        val checksum = put(1, 500)
        val cache = cache()

        assertEquals(OriginalResult.Corrupted, cache.open(1, checksum, 499))
        assertEquals(OriginalResult.Corrupted, cache.open(1, "a".repeat(64), 500))

        assertTrue(dir.list().isNullOrEmpty())
        assertTrue(tempDir.list().isNullOrEmpty())
    }

    @Test
    fun interruptedDownloadLeavesNoPartialFile() = runBlocking {
        val checksum = put(1, 500)
        download = { _, target ->
            target.writeBytes(ByteArray(100))
            RemoteResult.NetworkError(IOException("reset"))
        }
        val cache = cache()

        assertEquals(OriginalResult.NoConnection, cache.open(1, checksum, 500))
        assertTrue(dir.list().isNullOrEmpty())
        assertTrue(tempDir.list().isNullOrEmpty())
    }

    @Test
    fun httpErrorsAreMapped() = runBlocking {
        val checksum = put(1, 500)
        val cache = cache()
        for ((code, expected) in listOf(404 to OriginalResult.NotFound, 401 to OriginalResult.Unauthorized,
            403 to OriginalResult.Unauthorized, 500 to OriginalResult.Failed(500))) {
            download = { _, _ -> RemoteResult.HttpError(code, "error") }
            assertEquals(expected, cache.open(1, checksum, 500))
        }
    }

    @Test
    fun damagedCachedCopyIsDownloadedAgain() = runBlocking {
        val checksum = put(1, 500)
        val cache = cache()
        cache.open(1, checksum, 500)
        File(dir, checksum).writeBytes(ByteArray(10))

        val result = cache.open(1, checksum, 500) as OriginalResult.Ready

        assertArrayEquals(content[1], result.file.readBytes())
        assertEquals(listOf(1L, 1L), calls)
    }

    @Test
    fun leastRecentlyUsedIsEvictedToFitLimit() = runBlocking {
        val cache = cache(maxBytes = 300)
        val a = put(1, 100)
        val b = put(2, 100)
        val c = put(3, 100)
        val d = put(4, 100)
        (cache.open(1, a, 100) as OriginalResult.Ready).lease.release()
        (cache.open(2, b, 100) as OriginalResult.Ready).lease.release()
        (cache.open(3, c, 100) as OriginalResult.Ready).lease.release()
        File(dir, a).setLastModified(1_000)
        File(dir, b).setLastModified(2_000)
        File(dir, c).setLastModified(3_000)
        // Повторное открытие A делает его самым свежим.
        (cache.open(1, a, 100) as OriginalResult.Ready).lease.release()

        (cache.open(4, d, 100) as OriginalResult.Ready).lease.release()

        assertTrue(cache.contains(a))
        assertFalse(cache.contains(b))
        assertTrue(cache.contains(c))
        assertTrue(cache.contains(d))
        assertTrue(cache.sizeBytes() <= 300)
    }

    @Test
    fun openFileIsNotEvicted() = runBlocking {
        val cache = cache(maxBytes = 200)
        val a = put(1, 100)
        val b = put(2, 100)
        val c = put(3, 100)
        val opened = cache.open(1, a, 100) as OriginalResult.Ready
        (cache.open(2, b, 100) as OriginalResult.Ready).lease.release()
        File(dir, a).setLastModified(1_000)
        File(dir, b).setLastModified(2_000)

        (cache.open(3, c, 100) as OriginalResult.Ready).lease.release()

        // A старше, но открыт: вытеснен B.
        assertTrue(cache.contains(a))
        assertFalse(cache.contains(b))
        opened.lease.release()
        opened.lease.release()
        val e = put(5, 100)
        File(dir, a).setLastModified(1_000)
        (cache.open(5, e, 100) as OriginalResult.Ready).lease.release()
        assertFalse(cache.contains(a))
    }

    @Test
    fun sameChecksumIsDownloadedOnceForConcurrentOpens() = runBlocking {
        val checksum = put(1, 500)
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val original = download
        download = { id, target ->
            started.complete(Unit)
            proceed.await()
            original(id, target)
        }
        val cache = cache()

        val first = async { cache.open(1, checksum, 500) }
        started.await()
        val second = async { cache.open(1, checksum, 500) }
        proceed.complete(Unit)

        assertTrue(first.await() is OriginalResult.Ready)
        assertTrue(second.await() is OriginalResult.Ready)
        assertEquals(listOf(1L), calls)
    }

    @Test
    fun cancelledDownloadIsDiscardedAndCanStartAgain() = runBlocking {
        val checksum = put(1, 500)
        val started = CompletableDeferred<Unit>()
        val original = download
        download = { _, target ->
            target.writeBytes(ByteArray(50))
            started.complete(Unit)
            awaitCancellation()
        }
        val cache = cache()

        val job = async { cache.open(1, checksum, 500) }
        started.await()
        job.cancel()
        job.join()

        assertTrue(dir.list().isNullOrEmpty())
        assertTrue(tempDir.list().isNullOrEmpty())
        download = original
        val result = withTimeout(5_000) { cache.open(1, checksum, 500) }
        assertTrue(result is OriginalResult.Ready)
    }

    @Test
    fun lowStorageIsReportedWithoutDownload() = runBlocking {
        val checksum = put(1, 500)
        free = 1_000

        assertEquals(OriginalResult.NoSpace, cache().open(1, checksum, 500))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun partialFilesOfPreviousProcessAreRemoved() {
        File(tempDir, "abc.part").writeBytes(ByteArray(10))

        cache()

        assertTrue(tempDir.list().isNullOrEmpty())
    }

    @Test
    fun originalIsRequestedThroughAuthorizedDownload() = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            val bytes = bytes(70_000, 3)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val baseUrlProvider = BaseUrlProvider(mock(ServerPreferences::class.java)).apply { baseUrl = server.url("/").toString() }
            val auth = OkHttpClient.Builder().addInterceptor(AuthInterceptor(InMemoryTokenStorage(Tokens("access", "refresh")))).build()
            val remote = TransferRemote(ApiServiceFactory(baseUrlProvider, OkHttpClient(), auth, OkHttpClient()))
            val cache = OriginalCache(dir, tempDir, 1_000_000, { id, target -> remote.download(id, target) {} }, { free })

            val result = cache.open(42, sha256(bytes), bytes.size.toLong()) as OriginalResult.Ready

            assertArrayEquals(bytes, result.file.readBytes())
            val request = server.takeRequest()
            assertEquals("/api/v1/files/42/download", request.path)
            assertEquals("Bearer access", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
