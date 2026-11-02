package ru.tbcarus.photo_cloud_client.transfer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import okhttp3.RequestBody
import okio.Buffer
import org.mockito.Mockito.mock
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Server в памяти с правилами имён обычной библиотеки: имя без учёта регистра, тот же файл возвращается повторно.
 * Тест может задать ответы-отказы по ключу вызова и удержать вызов до освобождения.
 */
internal class FakeTransferRemote : TransferRemote(mock(ApiServiceFactory::class.java)) {

    val calls = mutableListOf<String>()
    val folders = LinkedHashMap<Long, FolderDto>()
    private val files = LinkedHashMap<Long, MutableList<FileItemDto>>()
    val content = HashMap<Long, ByteArray>()
    private val failures = HashMap<String, ArrayDeque<RemoteResult<Nothing>>>()
    private val gates = HashMap<String, CompletableDeferred<Unit>>()
    private var nextId = 100L

    /** Байты, которые download отдаёт вместо сохранённых: искажение при передаче. */
    var servedBytes: ByteArray? = null

    val root = folder(1, null, "root", "ROOT")

    fun folder(id: Long, parentId: Long?, name: String, type: String = "USER"): FolderDto =
        FolderDto(id, parentId, name, type).also { folders[id] = it }

    fun file(folderId: Long, name: String, bytes: ByteArray): FileItemDto {
        val dto = FileItemDto(
            id = nextId++, folderId = folderId, originalFilename = name,
            size = bytes.size.toLong(), checksum = sha256(bytes)
        )
        files.getOrPut(folderId) { mutableListOf() } += dto
        content[dto.id!!] = bytes
        return dto
    }

    fun filesIn(folderId: Long): List<FileItemDto> = files[folderId].orEmpty()

    fun folderNamed(parentId: Long, name: String): FolderDto? =
        folders.values.firstOrNull { it.parentId == parentId && it.name == name }

    fun fail(key: String, vararg results: RemoteResult<Nothing>) {
        failures.getOrPut(key) { ArrayDeque() }.addAll(results)
    }

    fun hold(key: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[key] = it }

    val uploads: List<String> get() = calls.filter { it.startsWith("upload:") }

    override suspend fun children(folderId: Long): RemoteResult<List<FolderDto>> = answer("children:$folderId") {
        if (folders[folderId] == null) notFound() else RemoteResult.Success(folders.values.filter { it.parentId == folderId })
    }

    override suspend fun files(folderId: Long, page: Int): RemoteResult<FilePageDto> = answer("files:$folderId:$page") {
        if (folders[folderId] == null) notFound() else RemoteResult.Success(FilePageDto(items = filesIn(folderId)))
    }

    override suspend fun createFolder(parentId: Long, name: String): RemoteResult<FolderDto> = answer("create:$parentId:$name") {
        when {
            folders[parentId] == null -> notFound()
            taken(parentId, name) -> RemoteResult.HttpError(409, "Folder with this name already exists in parent folder")
            else -> RemoteResult.Success(folder(nextId++, parentId, name))
        }
    }

    override suspend fun upload(folderId: Long, name: String, body: RequestBody): RemoteResult<FileItemDto> =
        answer("upload:$folderId:$name") {
            val buffer = Buffer()
            try {
                body.writeTo(buffer)
            } catch (e: IOException) {
                return@answer RemoteResult.NetworkError(e)
            }
            val bytes = buffer.readByteArray()
            val same = filesIn(folderId).firstOrNull { it.originalFilename.equals(name, ignoreCase = true) }
            when {
                folders[folderId] == null -> notFound()
                same != null && same.checksum == sha256(bytes) -> RemoteResult.Success(same)
                taken(folderId, name) -> RemoteResult.HttpError(409, "File with this name already exists in folder")
                bytes.isEmpty() -> RemoteResult.HttpError(400, "Файл пустой")
                else -> RemoteResult.Success(file(folderId, name, bytes))
            }
        }

    override suspend fun download(fileId: Long, target: File, onProgress: (Long) -> Unit): RemoteResult<DownloadedFile> =
        answer("download:$fileId") {
            val bytes = servedBytes ?: content[fileId] ?: return@answer notFound()
            target.writeBytes(bytes)
            onProgress(bytes.size.toLong())
            RemoteResult.Success(DownloadedFile(bytes.size.toLong(), sha256(bytes)))
        }

    private fun taken(folderId: Long, name: String): Boolean =
        folders.values.any { it.parentId == folderId && it.name.equals(name, ignoreCase = true) } ||
            filesIn(folderId).any { it.originalFilename.equals(name, ignoreCase = true) }

    private fun notFound() = RemoteResult.HttpError(404, "Not found")

    /** Вызовы, которые Server выполняет, но ответ которых теряется в сети. */
    val lostResponses = HashSet<String>()

    // "upload:*:имя" задаёт отказ для файла в любой папке.
    private suspend fun <T> answer(key: String, block: suspend () -> RemoteResult<T>): RemoteResult<T> {
        calls += key
        gates[key]?.await()
        val anyFolder = key.replace(Regex("^upload:\\d+:"), "upload:*:")
        (failures[key] ?: failures[anyFolder])?.removeFirstOrNull()?.let { return it }
        val result = block()
        if (lostResponses.remove(key)) return RemoteResult.NetworkError(IOException("Connection reset after response"))
        return result
    }
}

/** Документы SAF в памяти. */
internal open class FakeDocuments(context: Context) : LocalDocuments(context) {

    private var counter = 0
    private val content = HashMap<Uri, ByteArray>()
    private val streams = HashMap<Uri, () -> InputStream>()
    private val described = HashMap<Uri, LocalFile>()
    val unreadable = HashSet<Uri>()
    val outputs = HashMap<Uri, ByteArrayOutputStream>()
    val deleted = mutableListOf<Uri>()
    val opened = mutableListOf<Uri>()
    var tree: LocalDir = LocalDir(null)
    var failOutput = false

    fun file(name: String?, bytes: ByteArray, size: Long? = bytes.size.toLong()): LocalFile {
        val uri = Uri.parse("content://test/doc/${counter++}")
        content[uri] = bytes
        return LocalFile(uri, name, size, "text/plain").also { described[uri] = it }
    }

    /** Документ, поток которого создаётся при каждом открытии. */
    fun stream(name: String, size: Long?, open: () -> InputStream): LocalFile {
        val uri = Uri.parse("content://test/doc/${counter++}")
        streams[uri] = open
        return LocalFile(uri, name, size, "application/octet-stream").also { described[uri] = it }
    }

    fun output(): Uri = Uri.parse("content://test/out/${counter++}")

    override fun describe(uri: Uri): LocalFile = described.getValue(uri)

    override fun walk(treeUri: Uri): LocalDir = tree

    override fun open(uri: Uri): InputStream {
        opened += uri
        if (uri in unreadable) throw SecurityException("Permission denied")
        streams[uri]?.let { return it() }
        return ByteArrayInputStream(content.getValue(uri))
    }

    override fun openOutput(uri: Uri): OutputStream {
        if (failOutput) throw IOException("No space left")
        return ByteArrayOutputStream().also { outputs[uri] = it }
    }

    override fun delete(uri: Uri) {
        deleted += uri
    }
}
