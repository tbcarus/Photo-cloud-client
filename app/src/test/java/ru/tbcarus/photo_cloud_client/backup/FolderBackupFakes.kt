package ru.tbcarus.photo_cloud_client.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.Document
import okhttp3.MultipartReader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import ru.tbcarus.photo_cloud_client.transfer.DocumentRow
import ru.tbcarus.photo_cloud_client.transfer.LocalDocuments
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

/**
 * Дерево SAF в памяти. Корень — документ "primary:<имя>" как у ExternalStorageProvider; ID вложенных — путь.
 * Можно сделать папку нечитаемой, файл недоступным, а сведения — отличными от содержимого.
 */
class FakeTree(context: Context, rootName: String) : LocalDocuments(context) {

    class Node(
        val id: String,
        var name: String,
        val directory: Boolean,
        var bytes: ByteArray = ByteArray(0),
        var lastModified: Long? = 1,
        var reportedSize: Long? = null,
        var sizeUnknown: Boolean = false,
        var unreadable: Boolean = false,
        var virtual: Boolean = false
    ) {
        val children = LinkedHashMap<String, Node>()
    }

    val rootId = "primary:$rootName"
    val treeUri: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/" + Uri.encode(rootId))
    private val nodes = HashMap<String, Node>().apply { put(rootId, Node(rootId, rootName, true)) }
    val unreadableFolders = mutableSetOf<String>()

    /** После чтения содержимого файла меняет сведения: файл изменился во время передачи. */
    var onOpen: (Node) -> Unit = {}
    val openedOffsets = mutableListOf<Long>()
    var nonSeekable = false

    fun dir(path: String): Node = node(path, directory = true)

    fun file(path: String, content: String, lastModified: Long = 1): Node =
        node(path, directory = false).also { it.bytes = content.toByteArray(); it.lastModified = lastModified }

    fun remove(path: String) {
        val parent = parentOf(path)
        val removed = parent.children.remove(path.substringAfterLast('/')) ?: return
        nodes.remove(removed.id)
    }

    fun at(path: String): Node = nodes.getValue("$rootId/$path")

    private fun parentOf(path: String): Node =
        if ('/' in path) at(path.substringBeforeLast('/')) else nodes.getValue(rootId)

    private fun node(path: String, directory: Boolean): Node {
        val parent = parentOf(path)
        val name = path.substringAfterLast('/')
        return parent.children.getOrPut(name) { Node("$rootId/$path", name, directory).also { nodes[it.id] = it } }
    }

    override fun children(treeUri: Uri, parentId: String): List<DocumentRow>? {
        if (parentId in unreadableFolders) return null
        return nodes[parentId]?.children?.values?.map { it.row() }
    }

    override fun stat(treeUri: Uri, documentId: String): DocumentRow? = nodes[documentId]?.row()

    override fun documentUri(treeUri: Uri, documentId: String): Uri = Uri.parse("fake://doc/" + Uri.encode(documentId))

    override fun open(uri: Uri): InputStream {
        val node = nodes[Uri.decode(uri.toString().removePrefix("fake://doc/"))] ?: throw FileNotFoundException(uri.toString())
        if (node.unreadable) throw FileNotFoundException("unreadable")
        val stream = ByteArrayInputStream(node.bytes)
        onOpen(node)
        return stream
    }

    override fun openAt(uri: Uri, offset: Long): InputStream {
        openedOffsets += offset
        val stream = open(uri)
        if (nonSeekable) {
            var remaining = offset
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) throw FileNotFoundException("offset beyond end")
                remaining -= skipped
            }
        } else stream.skip(offset)
        return stream
    }

    private fun Node.row() = DocumentRow(
        id = id,
        name = name,
        mimeType = if (directory) Document.MIME_TYPE_DIR else "application/octet-stream",
        size = if (directory || sizeUnknown) null else reportedSize ?: bytes.size.toLong(),
        flags = if (virtual) Document.FLAG_VIRTUAL_DOCUMENT else 0,
        lastModified = if (directory) null else lastModified
    )
}

/**
 * Server папок и файлов с правилами защищённого дерева: запись в активное дерево — только с его UUID,
 * иначе 409 FOLDER_MANAGED; занятое имя — 409 CONFLICT; то же имя и checksum — прежний файл.
 */
class FakeLibraryServer : Dispatcher() {

    class Folder(val id: Long, val parentId: Long?, val name: String, val type: String, var backupSource: String?)
    class File(val id: Long, val folderId: Long, var name: String, var bytes: ByteArray) {
        val checksum: String get() = sha256(bytes)
    }

    private var nextId = 1L
    val folders = LinkedHashMap<Long, Folder>()
    val files = LinkedHashMap<Long, File>()
    val requests = CopyOnWriteArrayList<String>()
    val uploads = CopyOnWriteArrayList<String>()
    val sessionPatches = CopyOnWriteArrayList<Long>()
    val sessionCreates = CopyOnWriteArrayList<String>()
    private class Session(val id: String, val folderId: Long, val name: String, val size: Long, val checksum: String,
                          val source: String) {
        val bytes = ByteArrayOutputStream()
        var result: File? = null
    }
    private val sessions = HashMap<String, Session>()
    /** Имена из PATCH файлов в порядке запросов. */
    val renames = CopyOnWriteArrayList<String>()

    /** Ответы стольких следующих upload теряются после сохранения файла. */
    var dropUploads = 0
    var dropNextUpload: Boolean
        get() = dropUploads > 0
        set(value) { dropUploads = if (value) 1 else 0 }
    var failUploadsWith: Int? = null
    var onUpload: (String) -> Unit = {}
    /** После сохранения файла, до ответа: гибель клиента здесь теряет ответ успешного upload. */
    var afterUpload: (File) -> Unit = {}

    /** Ответ следующего PATCH теряется после переименования. */
    var dropNextRename = false
    /** Ошибка PATCH до переименования. */
    var failRenamesWith: Int? = null
    var afterRename: (File) -> Unit = {}
    /** Столько следующих GET файла по ID отвечают 500. */
    var failFileGets = 0
    /** Столько следующих GET файла по ID обрываются до ответа. */
    var disconnectFileGets = 0
    var onFileGet: (Long) -> Unit = {}
    /** Пути DELETE /folders/{id}/backup в порядке запросов. */
    val disables = CopyOnWriteArrayList<String>()
    /** Ошибка DELETE до отключения. */
    var failDisablesWith: Int? = null
    /** Следующий DELETE обрывается без обработки: Server ничего не меняет. */
    var disconnectNextDisable = false
    /** Ответ следующего DELETE теряется после отключения. */
    var dropNextDisable = false
    var onDisable: () -> Unit = {}

    val root: Folder = add(null, "root", "ROOT", null)
    val autoUploads: Folder = add(root.id, "AutoUploads", "AUTO_UPLOADS", null)

    fun source(name: String, uuid: String): Folder = add(autoUploads.id, name, "USER", uuid)

    fun folderAt(sourceId: Long, path: String): Folder? {
        var current = folders.getValue(sourceId)
        for (part in path.split('/')) {
            current = folders.values.firstOrNull { it.parentId == current.id && it.name == part } ?: return null
        }
        return current
    }

    fun fileAt(folderId: Long, name: String): File? = files.values.firstOrNull { it.folderId == folderId && it.name == name }

    fun putFile(folderId: Long, name: String, content: String): File = File(nextId++, folderId, name, content.toByteArray()).also { files[it.id] = it }

    fun add(parentId: Long?, name: String, type: String, uuid: String?): Folder =
        Folder(nextId++, parentId, name, type, uuid).also { folders[it.id] = it }

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        requests += "${request.method} $path"
        return when {
            request.method == "GET" && path == "/api/v1/folders/root" -> json(folderJson(root).toString())
            request.method == "GET" && path.endsWith("/children") -> {
                val id = path.removePrefix("/api/v1/folders/").removeSuffix("/children").toLong()
                if (id !in folders) return error(404, "NOT_FOUND")
                json(JSONArray(folders.values.filter { it.parentId == id }.map { folderJson(it) }).toString())
            }
            request.method == "GET" && path == "/api/v1/files" -> {
                val folderId = request.requestUrl!!.queryParameter("folderId")!!.toLong()
                val items = files.values.filter { it.folderId == folderId }.map { fileJson(it) }
                json(JSONObject().put("items", JSONArray(items)).put("hasNext", false).toString())
            }
            request.method == "POST" && path == "/api/v1/folders" -> createFolder(JSONObject(request.body.readUtf8()))
            request.method == "POST" && path == "/api/v1/folders/backup" -> error(500, "UNEXPECTED")
            request.method == "POST" && path == "/api/v1/files/upload" -> upload(request)
            request.method == "POST" && path == "/api/v1/upload-sessions" -> createSession(request)
            path.startsWith("/api/v1/upload-sessions/") -> sessionRequest(request, path)
            request.method == "GET" && FILE_ID.matches(path) -> {
                val id = path.substringAfterLast('/').toLong()
                onFileGet(id)
                if (disconnectFileGets > 0) {
                    disconnectFileGets--
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                }
                if (failFileGets > 0) {
                    failFileGets--
                    return error(500, "INTERNAL_ERROR")
                }
                files[id]?.let { json(fileJson(it).toString()) } ?: error(404, "FILE_ITEM_NOT_FOUND")
            }
            request.method == "DELETE" && SOURCE_BACKUP.matches(path) ->
                disable(path.removePrefix("/api/v1/folders/").removeSuffix("/backup").toLong(), path)
            request.method == "PATCH" && FILE_ID.matches(path) ->
                rename(path.substringAfterLast('/').toLong(), JSONObject(request.body.readUtf8()))
            else -> error(404, "NOT_FOUND")
        }
    }

    /** Правила Server: корень источника — USER-папка в AutoUploads; повтор для отключённого тоже 204. */
    private fun disable(id: Long, path: String): MockResponse {
        disables += path
        onDisable()
        if (disconnectNextDisable) {
            disconnectNextDisable = false
            // DISCONNECT_AT_START из dispatch не действует: запрос уже прочитан.
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        failDisablesWith?.let { return error(it, "INTERNAL_ERROR") }
        val folder = folders[id] ?: return error(404, "NOT_FOUND")
        if (folder.type != "USER" || folder.parentId != autoUploads.id) return error(400, "BAD_REQUEST")
        folder.backupSource = null
        if (dropNextDisable) {
            dropNextDisable = false
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        return MockResponse().setResponseCode(204)
    }

    /** Правила Server: ID сохраняется, имя свободно без учёта регистра, кроме собственного; запись в активное дерево — с его UUID. */
    private fun rename(id: Long, body: JSONObject): MockResponse {
        val name = body.getString("originalName")
        renames += name
        failRenamesWith?.let { return error(it, "INTERNAL_ERROR") }
        val file = files[id] ?: return error(404, "FILE_ITEM_NOT_FOUND")
        val backupSource = if (body.isNull("backupSource")) "" else body.getString("backupSource")
        if (activeSourceOf(file.folderId) != backupSource) {
            return error(409, "FOLDER_MANAGED")
        }
        if (name.isBlank() || name.contains('/')) return error(400, "BAD_REQUEST")
        val taken = folders.values.any { it.parentId == file.folderId && it.name.equals(name, ignoreCase = true) } ||
            files.values.any { it.id != id && it.folderId == file.folderId && it.name.equals(name, ignoreCase = true) }
        if (taken) return error(409, "CONFLICT")
        file.name = name
        afterRename(file)
        if (dropNextRename) {
            dropNextRename = false
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        return json(fileJson(file).toString())
    }

    private fun createFolder(body: JSONObject): MockResponse {
        val parentId = body.getLong("parentId")
        val name = body.getString("name")
        if (parentId !in folders) return error(404, "NOT_FOUND")
        if (activeSourceOf(parentId) != body.optString("backupSource", "")) return error(409, "FOLDER_MANAGED")
        if (nameTaken(parentId, name)) return error(409, "CONFLICT")
        return json(folderJson(add(parentId, name, "USER", null)).toString())
    }

    private fun upload(request: RecordedRequest): MockResponse {
        val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
        var name = ""
        var bytes = ByteArray(0)
        var folderId = 0L
        var backupSource = ""
        MultipartReader(Buffer().write(request.body.readByteArray()), boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val disposition = part.headers["Content-Disposition"].orEmpty()
                val field = disposition.substringAfter("name=\"").substringBefore('"')
                val value = part.body.readByteArray()
                when (field) {
                    "file" -> {
                        name = disposition.substringAfter("filename=\"").substringBefore('"')
                        bytes = value
                    }
                    "folderId" -> folderId = String(value).toLong()
                    "backupSource" -> backupSource = String(value)
                }
            }
        }
        uploads += name
        onUpload(name)
        failUploadsWith?.let { return error(it, "INTERNAL_ERROR") }
        if (folderId !in folders) return error(404, "NOT_FOUND")
        if (activeSourceOf(folderId) != backupSource) return error(409, "FOLDER_MANAGED")
        if (bytes.isEmpty()) return error(400, "BAD_REQUEST")
        val checksum = sha256(bytes)
        val file = fileAt(folderId, name)?.takeIf { it.checksum == checksum }
            ?: if (nameTaken(folderId, name)) return error(409, "CONFLICT") else File(nextId++, folderId, name, bytes).also { files[it.id] = it }
        afterUpload(file)
        if (dropUploads > 0) {
            dropUploads--
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        return json(fileJson(file).toString())
    }

    private fun createSession(request: RecordedRequest): MockResponse {
        val body = JSONObject(request.body.readUtf8())
        val requestId = body.getString("clientRequestId")
        sessionCreates += requestId
        val folderId = body.getLong("folderId")
        val source = body.optString("backupSource", "")
        if (folderId !in folders) return error(404, "NOT_FOUND")
        if (activeSourceOf(folderId) != source) return error(409, "FOLDER_MANAGED")
        val session = sessions.getOrPut(requestId) {
            Session(UUID.randomUUID().toString(), folderId, body.getString("originalFilename"), body.getLong("size"),
                body.getString("checksum"), source)
        }
        return json(sessionJson(session).toString())
    }

    private fun sessionRequest(request: RecordedRequest, path: String): MockResponse {
        val id = path.removePrefix("/api/v1/upload-sessions/").substringBefore('/')
        val session = sessions.values.firstOrNull { it.id == id } ?: return error(404, "NOT_FOUND")
        return when {
            request.method == "GET" -> json(sessionJson(session).toString())
            request.method == "PATCH" -> {
                val offset = request.getHeader("Upload-Offset")?.toLongOrNull() ?: return error(400, "BAD_REQUEST")
                if (offset != session.bytes.size().toLong()) return error(409, "OFFSET_MISMATCH")
                sessionPatches += offset
                session.bytes.write(request.body.readByteArray())
                json(sessionJson(session).toString())
            }
            request.method == "POST" && path.endsWith("/complete") -> {
                if (session.bytes.size().toLong() != session.size || sha256(session.bytes.toByteArray()) != session.checksum)
                    return error(409, "CHECKSUM_MISMATCH")
                if (session.result == null) {
                    val existing = fileAt(session.folderId, session.name)
                    session.result = existing?.takeIf { it.checksum == session.checksum }
                        ?: if (existing != null) return error(409, "CONFLICT")
                        else File(nextId++, session.folderId, session.name, session.bytes.toByteArray()).also { files[it.id] = it }
                }
                json(sessionJson(session).toString())
            }
            request.method == "DELETE" -> {
                sessions.entries.removeIf { it.value.id == id }
                MockResponse().setResponseCode(204)
            }
            else -> error(404, "NOT_FOUND")
        }
    }

    private fun sessionJson(session: Session) = JSONObject()
        .put("id", session.id).put("confirmedOffset", session.bytes.size()).put("maxChunkSizeBytes", 8 * 1024 * 1024)
        .put("state", if (session.result == null) "UPLOADING" else "COMPLETED")
        .put("result", session.result?.let(::fileJson) ?: JSONObject.NULL)

    /** UUID активного источника, к дереву которого относится папка; "" — папка вне активного дерева. */
    private fun activeSourceOf(folderId: Long): String {
        var current = folders[folderId]
        while (current != null) {
            current.backupSource?.let { return it }
            current = current.parentId?.let { folders[it] }
        }
        return ""
    }

    private fun nameTaken(parentId: Long, name: String) =
        folders.values.any { it.parentId == parentId && it.name.equals(name, ignoreCase = true) } ||
            files.values.any { it.folderId == parentId && it.name.equals(name, ignoreCase = true) }

    private fun folderJson(folder: Folder): JSONObject = JSONObject()
        .put("id", folder.id).put("parentId", folder.parentId ?: JSONObject.NULL).put("name", folder.name)
        .put("folderType", folder.type).put("backupSource", folder.backupSource ?: JSONObject.NULL)

    private fun fileJson(file: File): JSONObject = JSONObject()
        .put("id", file.id).put("folderId", file.folderId).put("originalFilename", file.name)
        .put("size", file.bytes.size).put("checksum", file.checksum)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun error(status: Int, code: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("code", code).put("message", code).toString())

    companion object {
        private val FILE_ID = Regex("/api/v1/files/\\d+")
        private val SOURCE_BACKUP = Regex("/api/v1/folders/\\d+/backup")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
