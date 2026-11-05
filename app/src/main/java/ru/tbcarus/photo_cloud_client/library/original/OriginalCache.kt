package ru.tbcarus.photo_cloud_client.library.original

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.tbcarus.photo_cloud_client.transfer.DownloadedFile
import ru.tbcarus.photo_cloud_client.transfer.RemoteResult
import ru.tbcarus.photo_cloud_client.transfer.TransferRemote
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Итог получения оригинала для просмотра. */
sealed interface OriginalResult {
    /** Проверенная копия в кеше; файл защищён от вытеснения до [OriginalLease.release]. */
    data class Ready(val file: File, val lease: OriginalLease) : OriginalResult

    sealed interface Failure : OriginalResult
    data object NoConnection : Failure
    data object NotFound : Failure
    data object Unauthorized : Failure
    data object NoSpace : Failure
    /** Скачанные байты не совпали по размеру или SHA-256 с файлом Server. */
    data object Corrupted : Failure
    data class Failed(val code: Int) : Failure
}

/** Защита файла кеша от вытеснения, пока он открыт в просмотре. Повторный release ничего не делает. */
class OriginalLease internal constructor(private val onRelease: () -> Unit) {
    @Volatile
    private var released = false

    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

/**
 * Приватный кеш оригиналов изображений для просмотра. Ключ — SHA-256 содержимого: копия не зависит от ID файла,
 * адреса Server и пользователя, и одинаковое содержимое не скачивается повторно. Объём ограничен [maxBytes],
 * вытесняются давно не открывавшиеся файлы (время последнего доступа — mtime файла).
 * Оригинал пишется во временный файл, проверяется по размеру и SHA-256 и только потом атомарно переносится в кеш.
 * Android может очистить cacheDir в любой момент: отсутствие файла означает новое скачивание.
 */
@Singleton
class OriginalCache internal constructor(
    private val dir: File,
    private val tempDir: File,
    private val maxBytes: Long,
    private val download: suspend (fileId: Long, target: File) -> RemoteResult<DownloadedFile>,
    private val usableSpace: () -> Long
) {

    @Inject
    constructor(@ApplicationContext context: Context, remote: TransferRemote) : this(
        File(context.cacheDir, CACHE_DIR),
        File(context.cacheDir, TEMP_DIR),
        MAX_BYTES,
        { fileId, target -> remote.download(fileId, target) {} },
        { context.cacheDir.usableSpace }
    )

    companion object {
        /** Предел объёма кеша оригиналов. */
        const val MAX_BYTES = 1L shl 30
        private const val CACHE_DIR = "originals"
        private const val TEMP_DIR = "originals-tmp"
        // Запас свободного места устройства сверх размера скачиваемого файла.
        private const val SPACE_RESERVE_BYTES = 32L * 1024 * 1024
        private val CHECKSUM = Regex("^[0-9a-f]{64}$")
    }

    // Одно скачивание на checksum: второй запрос ждёт и берёт уже опубликованный файл.
    private val downloads = ConcurrentHashMap<String, Mutex>()
    // Публикация и вытеснение меняют состав каталога: один вход на всё.
    private val index = Mutex()
    private val pins = HashMap<String, Int>()

    init {
        // Временные файлы прошлого процесса не принадлежат ни одному скачиванию.
        tempDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun open(fileId: Long, checksum: String, size: Long?): OriginalResult {
        val key = checksum.lowercase()
        require(CHECKSUM.matches(key)) { "Invalid checksum" }
        return downloads.getOrPut(key) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                // Проверка и защита от вытеснения — под тем же замком, что вытеснение.
                index.withLock { cached(key, size)?.let { ready(key, it) } } ?: fetch(fileId, key, size)
            }
        }
    }

    // Копия подходит, если размер совпадает с ожидаемым; неверный размер — повреждение, файл удаляется.
    private fun cached(key: String, size: Long?): File? {
        val file = File(dir, key)
        if (!file.isFile) return null
        if (size != null && file.length() != size) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    private suspend fun fetch(fileId: Long, key: String, size: Long?): OriginalResult {
        if (size != null) {
            index.withLock { evict(incoming = size, keep = key) }
            if (usableSpace() < size + SPACE_RESERVE_BYTES) return OriginalResult.NoSpace
        }
        tempDir.mkdirs()
        val temp = File(tempDir, "$key-${UUID.randomUUID()}.part")
        try {
            val downloaded = when (val result = download(fileId, temp)) {
                is RemoteResult.Success -> result.value
                is RemoteResult.HttpError -> return httpFailure(result.code)
                // Ошибка записи на заполненный диск тоже приходит как IOException.
                is RemoteResult.NetworkError ->
                    return if (size != null && usableSpace() < size) OriginalResult.NoSpace else OriginalResult.NoConnection
            }
            val intact = (size == null || downloaded.size == size) && downloaded.sha256.equals(key, ignoreCase = true)
            if (!intact) return OriginalResult.Corrupted
            return index.withLock {
                evict(incoming = downloaded.size, keep = key)
                dir.mkdirs()
                val target = File(dir, key)
                publish(temp, target)
                target.setLastModified(System.currentTimeMillis())
                ready(key, target)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return if (size != null && usableSpace() < size) OriginalResult.NoSpace else OriginalResult.Failed(0)
        } finally {
            temp.delete()
        }
    }

    private fun publish(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun httpFailure(code: Int): OriginalResult.Failure = when (code) {
        401, 403 -> OriginalResult.Unauthorized
        404 -> OriginalResult.NotFound
        else -> OriginalResult.Failed(code)
    }

    private fun ready(key: String, file: File): OriginalResult.Ready {
        synchronized(pins) { pins[key] = (pins[key] ?: 0) + 1 }
        return OriginalResult.Ready(file, OriginalLease {
            synchronized(pins) {
                val count = (pins[key] ?: 1) - 1
                if (count <= 0) pins.remove(key) else pins[key] = count
            }
        })
    }

    /** Удаляет давно не открывавшиеся файлы, пока новый [incoming] не помещается в предел. Открытые не трогает. */
    private fun evict(incoming: Long, keep: String) {
        val files = dir.listFiles { file -> file.isFile && CHECKSUM.matches(file.name) }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total + incoming <= maxBytes) return
        val pinned = synchronized(pins) { pins.keys.toSet() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total + incoming <= maxBytes) break
            if (file.name == keep || file.name in pinned) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    /** Занятый кешем объём; для тестов и диагностики. */
    internal fun sizeBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    internal fun contains(checksum: String): Boolean = File(dir, checksum.lowercase()).isFile
}
