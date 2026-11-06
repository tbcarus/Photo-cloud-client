package ru.tbcarus.photo_cloud_client.library.external

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.library.original.OriginalCache
import ru.tbcarus.photo_cloud_client.library.original.OriginalLease
import ru.tbcarus.photo_cloud_client.library.original.OriginalResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val CHECKSUM = Regex("^[0-9a-fA-F]{64}$")
private const val GENERIC_MIME = "application/octet-stream"

/** Внешним приложением открывается файл с ID и SHA-256: оригинал берётся из кеша оригиналов по checksum. */
fun canOpenExternally(file: FileItemDto): Boolean =
    (file.id ?: 0) > 0 && file.checksum?.let(CHECKSUM::matches) == true

/** Имя, которое увидит внешнее приложение: исходное имя файла, а не SHA-256 копии в кеше. */
fun externalDisplayName(file: FileItemDto): String =
    file.originalFilename?.takeIf { it.isNotBlank() } ?: "file-${file.id}"

/**
 * Конкретный MIME Server; при общем или пустом — по расширению имени; иначе `application/octet-stream`.
 * Общими считаются и внутренние типы Tika (`application/x-tika-ooxml` для DOCX и XLSX): приложения Android их не знают.
 */
fun externalMimeType(name: String, serverMime: String?): String {
    val server = serverMime?.trim()?.lowercase()
    if (!server.isNullOrEmpty() && server != GENERIC_MIME && !server.startsWith("application/x-tika-")) return server
    return mimeByName(name) ?: GENERIC_MIME
}

internal fun mimeByName(name: String): String? {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}

enum class ExternalError { NO_CONNECTION, NOT_FOUND, UNAUTHORIZED, NO_SPACE, TOO_LARGE, CORRUPTED, FAILED, NO_APP }

sealed interface ExternalFile {
    /** Проверенная копия в кеше; [lease] защищает её от вытеснения, пока файл открыт внешним приложением. */
    class Ready(val file: File, val name: String, val mimeType: String, val lease: OriginalLease) : ExternalFile

    data class Error(val reason: ExternalError) : ExternalFile
}

/** Подготовка файла для внешнего приложения через общий кеш оригиналов: отдельного кеша и копий под исходным именем нет. */
@Singleton
class ExternalFiles @Inject constructor(private val cache: OriginalCache) {

    suspend fun prepare(file: FileItemDto): ExternalFile {
        val id = file.id
        val checksum = file.checksum
        if (id == null || checksum == null || !canOpenExternally(file)) return ExternalFile.Error(ExternalError.NOT_FOUND)
        // Кеш ограничен 1 ГиБ: больший файл в нём не удержать, его сохраняют через «Скачать».
        if ((file.size ?: 0) > OriginalCache.MAX_BYTES) return ExternalFile.Error(ExternalError.TOO_LARGE)
        val name = externalDisplayName(file)
        return when (val result = cache.open(id, checksum, file.size)) {
            is OriginalResult.Ready -> ExternalFile.Ready(result.file, name, externalMimeType(name, file.mimeType), result.lease)
            OriginalResult.NoConnection -> ExternalFile.Error(ExternalError.NO_CONNECTION)
            OriginalResult.NotFound -> ExternalFile.Error(ExternalError.NOT_FOUND)
            OriginalResult.Unauthorized -> ExternalFile.Error(ExternalError.UNAUTHORIZED)
            OriginalResult.NoSpace -> ExternalFile.Error(ExternalError.NO_SPACE)
            OriginalResult.Corrupted -> ExternalFile.Error(ExternalError.CORRUPTED)
            is OriginalResult.Failed -> ExternalFile.Error(ExternalError.FAILED)
        }
    }
}

/** Защищённый `content://` адрес копии в кеше оригиналов с исходным именем файла. */
fun originalContentUri(context: Context, ready: ExternalFile.Ready): Uri =
    FileProvider.getUriForFile(context, context.packageName + OriginalsFileProvider.AUTHORITY_SUFFIX, ready.file, ready.name)

/**
 * Просмотр во внешнем приложении: тип задан явно, право — только временное чтение этого адреса
 * (без записи и постоянного доступа). ClipData с тем же адресом нужен, чтобы право дошло и через выбор приложения.
 */
fun externalViewIntent(uri: Uri, name: String, mimeType: String): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData(name, arrayOf(mimeType), ClipData.Item(uri)) }

/**
 * FileProvider каталога `cacheDir/originals`. Файлы там названы SHA-256 без расширения, и штатный getType
 * вернул бы `application/octet-stream`: тип берётся по расширению отображаемого имени из адреса.
 */
class OriginalsFileProvider : FileProvider() {

    override fun getType(uri: Uri): String? =
        uri.getQueryParameter(DISPLAY_NAME)?.let(::mimeByName) ?: super.getType(uri)

    companion object {
        const val AUTHORITY_SUFFIX = ".originals"
        // Параметр, в который FileProvider.getUriForFile кладёт отображаемое имя.
        private const val DISPLAY_NAME = "displayName"
    }
}
