package ru.tbcarus.photo_cloud_client.media

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowEnvironment
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Тестовый MediaStore для Robolectric: Images-запросы отдаются из [images],
 * содержимое файлов — через ShadowContentResolver с подсчётом открытий потока.
 */
class FakeMediaStore private constructor(
    private val context: Context,
    private val provider: Provider
) {
    /** Изменяемый снимок одной фотографии: правка полей имитирует изменение файла на устройстве. */
    class Image(
        val id: Long,
        @Volatile var bytes: ByteArray,
        @Volatile var dateModifiedSec: Long,
        val relativePath: String = "DCIM/Camera/",
        val mimeType: String = "image/jpeg"
    ) {
        val uri: Uri get() = uriOf(id)
        val size: Long get() = bytes.size.toLong()
        val lastModifiedMs: Long get() = dateModifiedSec * 1000L
    }

    /** Запрос MediaStore завершается исключением (недоступный MediaProvider). */
    var failQuery: Boolean
        get() = provider.failQuery
        set(value) { provider.failQuery = value }

    /** Число строк с некорректным _ID: scan пропускает их, а снимок становится неполным. */
    var brokenRows: Int
        get() = provider.brokenRows
        set(value) { provider.brokenRows = value }

    /** Задержка каждого запроса — для проверки, что scan не выполняются параллельно. */
    var queryDelayMs: Long
        get() = provider.queryDelayMs
        set(value) { provider.queryDelayMs = value }

    /** Вызывается перед каждым запросом с его порядковым номером (с 1): меняет библиотеку между снимками. */
    var beforeQuery: (Int) -> Unit
        get() = provider.beforeQuery
        set(value) { provider.beforeQuery = value }

    /** Состояние основного хранилища (Environment.getExternalStorageState). */
    fun setStorageState(state: String) = ShadowEnvironment.setExternalStorageState(state)

    val queryCount: Int get() = provider.queryCount.get()
    val maxConcurrentQueries: Int get() = provider.maxConcurrent.get()

    private val opens = ConcurrentHashMap<Long, AtomicInteger>()

    /** Вызывается при каждом открытии потока файла с его MediaStore ID (до чтения байтов). */
    @Volatile var onOpen: (Long) -> Unit = {}

    fun add(image: Image): Image {
        provider.images += image
        shadowOf(context.contentResolver).registerInputStreamSupplier(image.uri) {
            opens.computeIfAbsent(image.id) { AtomicInteger() }.incrementAndGet()
            onOpen(image.id)
            ByteArrayInputStream(image.bytes)
        }
        return image
    }

    fun remove(id: Long) {
        provider.images.removeIf { it.id == id }
    }

    /** Сколько раз открывался поток файла (hash, проверка доступа перед upload, отправка). */
    fun openCount(id: Long): Int = opens[id]?.get() ?: 0

    class Provider : ContentProvider() {
        val images = CopyOnWriteArrayList<Image>()
        @Volatile var failQuery = false
        @Volatile var brokenRows = 0
        @Volatile var queryDelayMs = 0L
        @Volatile var beforeQuery: (Int) -> Unit = {}
        val queryCount = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            beforeQuery(queryCount.incrementAndGet())
            val now = active.incrementAndGet()
            maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                if (queryDelayMs > 0) Thread.sleep(queryDelayMs)
                check(!failQuery) { "MediaStore недоступен" }
                val columns = requireNotNull(projection)
                val cursor = MatrixCursor(columns)
                // Фильтр запроса MediaStoreRepository: RELATIVE_PATH LIKE 'каталог/%' [OR ...].
                // LIKE в SQLite не различает регистр ASCII; без аргументов отдаётся вся коллекция.
                val prefixes = selectionArgs.orEmpty().map { it.removeSuffix("%") }
                images.filter { image ->
                    prefixes.isEmpty() || prefixes.any { image.relativePath.startsWith(it, ignoreCase = true) }
                }.forEach { image ->
                    cursor.addRow(columns.map { column -> value(image, column) })
                }
                repeat(brokenRows) {
                    cursor.addRow(columns.map { column -> if (column == MediaStore.Images.Media._ID) "broken" else null })
                }
                return cursor
            } finally {
                active.decrementAndGet()
            }
        }

        private fun value(image: Image, column: String): Any? = when (column) {
            MediaStore.Images.Media._ID -> image.id
            MediaStore.Images.Media.DISPLAY_NAME -> "IMG_${image.id}.jpg"
            MediaStore.Images.Media.RELATIVE_PATH -> image.relativePath
            MediaStore.Images.Media.MIME_TYPE -> image.mimeType
            MediaStore.Images.Media.SIZE -> image.size
            MediaStore.Images.Media.DATE_TAKEN -> image.lastModifiedMs
            MediaStore.Images.Media.DATE_MODIFIED -> image.dateModifiedSec
            MediaStore.Images.Media.DATE_ADDED -> image.dateModifiedSec
            else -> null
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException()
    }

    companion object {
        /** Подключает тестовый MediaStore; основное хранилище считается подключённым (у Robolectric по умолчанию — нет). */
        fun install(context: Context): FakeMediaStore {
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            return FakeMediaStore(context, Robolectric.setupContentProvider(Provider::class.java, MediaStore.AUTHORITY))
        }

        fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        fun sha256(bytes: ByteArray): String = ChecksumUtil.sha256(ByteArrayInputStream(bytes))

        /** Строка Room для снимка [image] в заданном состоянии. */
        fun row(
            image: Image,
            status: MediaFileStatus,
            checksum: String? = null,
            serverFileId: Long? = null
        ) = MediaFile(
            mediaStoreId = image.id,
            serverFileId = serverFileId,
            uri = image.uri.toString(),
            displayName = "IMG_${image.id}.jpg",
            relativePath = image.relativePath,
            mimeType = image.mimeType,
            size = image.size,
            createdAt = image.lastModifiedMs,
            lastModified = image.lastModifiedMs,
            checksum = checksum,
            status = status
        )
    }
}
