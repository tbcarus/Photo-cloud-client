package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единственный способ открыть поток фотографии MediaStore: checksum, проверка доступа перед upload и отправка
 * выбирают источник байтов одинаково.
 *
 * С ACCESS_MEDIA_LOCATION читается оригинал через [MediaStore.setRequireOriginal]: без него Android 10+
 * отдаёт копию с затёртыми геоданными. Без разрешения или при отказе в оригинале — обычный поток MediaStore.
 */
@Singleton
class MediaStreamOpener @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "MediaStreamOpener"
    }

    /**
     * Поток содержимого [uri]; null — provider не вернул поток (как у openInputStream).
     * Отсутствие файла, ошибки чтения и отказ в доступе к фото пробрасываются как есть.
     */
    fun open(uri: Uri): InputStream? {
        if (context.hasMediaLocationPermission()) {
            try {
                val descriptor = context.contentResolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r")
                    ?: return null
                return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            } catch (e: UnsupportedOperationException) {
                // Разрешение отозвано после проверки — Android отказывает в оригинале, читаем доступную версию.
                Log.w(TAG, "Оригинал недоступен, читается обычный поток: ${e.message}")
            } catch (e: SecurityException) {
                // Отказ именно в оригинале; отказ в самом фото повторится при обычном открытии и не будет скрыт.
                Log.w(TAG, "Нет доступа к оригиналу, читается обычный поток: ${e.message}")
            }
        }
        return context.contentResolver.openInputStream(uri)
    }

    fun open(uri: Uri, collection: MediaCollection): InputStream? =
        if (collection == MediaCollection.VIDEO) context.contentResolver.openInputStream(uri) else open(uri)

    /** Для блока исходная версия и позиция фиксируются заново при каждом HTTP retry. */
    fun openAt(uri: Uri, requireOriginal: Boolean, offset: Long): InputStream {
        val target = if (requireOriginal) MediaStore.setRequireOriginal(uri) else uri
        val descriptor = context.contentResolver.openFileDescriptor(target, "r")
            ?: throw IOException("MediaStore не вернул дескриптор: $uri")
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).also { stream ->
                stream.channel.position(offset)
            }
        } catch (e: Exception) {
            descriptor.close()
            throw e
        }
    }

    fun readsOriginal(): Boolean = context.hasMediaLocationPermission()
}
