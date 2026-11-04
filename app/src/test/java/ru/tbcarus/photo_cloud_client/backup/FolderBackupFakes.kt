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
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

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
        val stream = ByteArrayInputStream(node.bytes.copyOf())
        onOpen(node)
        return stream
    }

    private fun Node.row() = DocumentRow(
        id = id,
        name = name,
        mimeType = if (directory) Document.MIME_TYPE_DIR else "application/octet-stream",
        size = if (directory) null else reportedSize ?: bytes.size.toLong(),
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
    class File(val id: Long, val folderId: Long, val name: String, val bytes: ByteArray) {
        val checksum: String get() = sha256(bytes)
    }

    private var nextId = 1L
    val folders = LinkedHashMap<Long, Folder>()
    val files = LinkedHashMap<Long, File>()
    val requests = CopyOnWriteArrayList<String>()
    val uploads = CopyOnWriteArrayList<String>()

    /** Ответ следующего upload теряется после сохранения файла. */
    var dropNextUpload = false
    var failUploadsWith: Int? = null
    var onUpload: (String) -> Unit = {}

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
            else -> error(404, "NOT_FOUND")
        }
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
        if (dropNextUpload) {
            dropNextUpload = false
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        return json(fileJson(file).toString())
    }

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
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
