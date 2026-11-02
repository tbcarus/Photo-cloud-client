package ru.tbcarus.photo_cloud_client.transfer

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Локальный документ для отправки. problem != null — документ не отправляется. */
data class LocalFile(
    val uri: Uri,
    val name: String?,
    val size: Long?,
    val mimeType: String?,
    val problem: String? = null
)

/** Папка выбранного дерева. problem != null — содержимое не прочитано, папка не загружается. */
data class LocalDir(
    val name: String?,
    val files: List<LocalFile> = emptyList(),
    val dirs: List<LocalDir> = emptyList(),
    val problem: String? = null
) {
    val fileCount: Int get() = files.size + dirs.sumOf { it.fileCount }
}

/** Строка дочернего документа SAF. */
data class DocumentRow(
    val id: String,
    val name: String?,
    val mimeType: String?,
    val size: Long?,
    val flags: Int
)

/** Документы, выбранные пользователем через Storage Access Framework. Прямые файловые пути не используются. */
@Singleton
open class LocalDocuments @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "LocalDocuments"
    }

    private val resolver get() = context.contentResolver

    open fun describe(uri: Uri): LocalFile = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                LocalFile(
                    uri = uri,
                    name = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                    size = cursor.longOrNull(OpenableColumns.SIZE),
                    mimeType = resolver.getType(uri)
                )
            }
            ?: LocalFile(uri, null, null, null, problem = UNREADABLE)
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось прочитать сведения о документе: ${e.message}")
        LocalFile(uri, null, null, null, problem = UNREADABLE)
    }

    open fun walk(treeUri: Uri): LocalDir {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return LocalDir(name = null, problem = UNREADABLE_FOLDER)
        }
        val rootName = try {
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
                arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.stringOrNull(Document.COLUMN_DISPLAY_NAME) else null }
        } catch (e: Exception) {
            null
        }
        return walkDocumentTree(
            rootId = rootId,
            rootName = rootName,
            children = { parentId -> children(treeUri, parentId) },
            documentUri = { id -> DocumentsContract.buildDocumentUriUsingTree(treeUri, id) }
        )
    }

    open fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("Документ не открывается: $uri")

    // "wt": без усечения часть провайдеров оставляет хвост прежнего содержимого.
    open fun openOutput(uri: Uri): OutputStream =
        resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("Документ не открывается для записи: $uri")

    open fun delete(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось удалить незаписанный документ: ${e.message}")
        }
    }

    private fun children(treeUri: Uri, parentId: String): List<DocumentRow>? {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS
        )
        return try {
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId), projection, null, null, null)
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.stringOrNull(Document.COLUMN_DOCUMENT_ID) ?: continue
                            add(
                                DocumentRow(
                                    id = id,
                                    name = cursor.stringOrNull(Document.COLUMN_DISPLAY_NAME),
                                    mimeType = cursor.stringOrNull(Document.COLUMN_MIME_TYPE),
                                    size = cursor.longOrNull(Document.COLUMN_SIZE),
                                    flags = cursor.longOrNull(Document.COLUMN_FLAGS)?.toInt() ?: 0
                                )
                            )
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать папку: ${e.message}")
            null
        }
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }
}

internal const val UNREADABLE = "Нет доступа к файлу"
internal const val UNREADABLE_FOLDER = "Нет доступа к папке"
internal const val VIRTUAL_DOCUMENT = "Документ без локального содержимого"
internal const val REPEATED_FOLDER = "Папка уже встречалась при обходе"
internal const val TOO_DEEP = "Слишком глубокая вложенность"

// Защита от провайдера, выдающего бесконечную цепочку новых папок; повтор ID отсекается отдельно.
internal const val MAX_TREE_DEPTH = 64

/**
 * Обход дерева SAF. children = null — папку прочитать нельзя.
 * Каждый ID папки обходится один раз: цикл или повторная ссылка провайдера не дают бесконечного обхода.
 */
internal fun walkDocumentTree(
    rootId: String,
    rootName: String?,
    children: (String) -> List<DocumentRow>?,
    documentUri: (String) -> Uri
): LocalDir {
    val visited = HashSet<String>()

    fun walk(id: String, name: String?, depth: Int): LocalDir {
        if (!visited.add(id)) return LocalDir(name, problem = REPEATED_FOLDER)
        if (depth > MAX_TREE_DEPTH) return LocalDir(name, problem = TOO_DEEP)
        val rows = children(id) ?: return LocalDir(name, problem = UNREADABLE_FOLDER)
        val files = ArrayList<LocalFile>()
        val dirs = ArrayList<LocalDir>()
        for (row in rows) {
            if (row.mimeType == Document.MIME_TYPE_DIR) {
                dirs += walk(row.id, row.name, depth + 1)
            } else {
                val virtual = row.flags and Document.FLAG_VIRTUAL_DOCUMENT != 0
                files += LocalFile(
                    uri = documentUri(row.id),
                    name = row.name,
                    size = row.size,
                    mimeType = row.mimeType,
                    problem = if (virtual) VIRTUAL_DOCUMENT else null
                )
            }
        }
        return LocalDir(name, files, dirs)
    }

    return walk(rootId, rootName, 0)
}
