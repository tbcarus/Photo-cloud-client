package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.net.Network
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.tbcarus.photo_cloud_client.api.UploadSessionService
import ru.tbcarus.photo_cloud_client.backup.BackupAccount
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.transfer.LocalDocuments
import ru.tbcarus.photo_cloud_client.transfer.LocalFile
import ru.tbcarus.photo_cloud_client.transfer.ManualUploadJob
import ru.tbcarus.photo_cloud_client.transfer.ManualUploadSource
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Включается явно: Docker PostgreSQL, отдельный Server на случайном порту и временный storage. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ResumableUploadS1SmokeTest {
    @Test fun androidProtocolPublishesBytesOnRealS1() = runBlocking {
        assumeTrue(System.getenv("PHOTO_CLOUD_A1_S1_SMOKE") == "1")
        val jar = serverJar()
        require(Files.isRegularFile(jar)) { "Соберите bootJar Server: $jar" }
        val work = Files.createTempDirectory("photocloud-a1-s1-")
        var container: String? = null
        var server: Process? = null
        var db: AppDatabase? = null
        try {
            val containerId = command("docker", "run", "-d", "--rm", "-e", "POSTGRES_PASSWORD=smoke",
                "-p", "127.0.0.1::5432", "postgres:16-alpine").trim()
            container = containerId
            val dbPort = command("docker", "port", containerId, "5432/tcp").trim().substringAfterLast(':').toInt()
            val dbDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (System.nanoTime() < dbDeadline && !commandOk("docker", "exec", containerId,
                    "pg_isready", "-U", "postgres")) Thread.sleep(250)
            assertTrue(commandOk("docker", "exec", containerId, "pg_isready", "-U", "postgres"))

            val port = ServerSocket(0).use { it.localPort }
            require(port != 8080)
            val baseUrl = "http://127.0.0.1:$port/"
            val log = work.resolve("server.log")
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString(),
                "--server.port=$port", "--storage.root=${work.resolve("storage")}",
                "--storage.temp-dir=${work.resolve("storage/tmp")}",
                "--storage.max-file-size-bytes=1024", "--upload.maintenance-interval=PT0.2S",
                "--logging.level.root=WARN"
            ).redirectErrorStream(true).redirectOutput(log.toFile())
            process.environment().putAll(mapOf(
                "DB_URL" to "jdbc:postgresql://127.0.0.1:$dbPort/postgres", "DB_USER" to "postgres",
                "DB_PASSWORD" to "smoke", "SMTP_USERNAME" to "test", "SMTP_PASSWORD" to "test",
                "JWT_KEY" to "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVm"
            ))
            val launched = process.start()
            server = launched
            val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (System.nanoTime() < readyDeadline && launched.isAlive) {
                try {
                    if (request(http, baseUrl, "GET", "api/v1/test").code == 200) break
                } catch (_: Exception) { }
                Thread.sleep(250)
            }
            assertTrue("Server не запустился: ${String(Files.readAllBytes(log)).takeLast(2000)}", launched.isAlive)
            assertEquals(200, request(http, baseUrl, "GET", "api/v1/test").code)

            val hash = "\u00242a\u002410\u0024Ch3oNExy5ZFgdfgO7nHEbe8N5FuPN7XasEAY4RqXBCMv2ilZ.BR6."
            val userId = command("docker", "exec", containerId, "psql", "-U", "postgres", "-d", "postgres", "-Atc",
                "INSERT INTO users(email,password,enabled,banned) VALUES ('a1-smoke@test.local','$hash',true,false) RETURNING id").lineSequence()
                .first { it.trim().all(Char::isDigit) }.trim()
            command("docker", "exec", containerId, "psql", "-U", "postgres", "-d", "postgres", "-c",
                "INSERT INTO user_roles(user_id,role) VALUES ($userId,'USER')")
            val login = request(http, baseUrl, "POST", "api/v1/auth/login",
                "{\"email\":\"a1-smoke@test.local\",\"password\":\"pass1\"}")
            assertEquals(200, login.code)
            val token = JsonParser.parseString(String(login.body, Charsets.UTF_8)).asJsonObject.get("accessToken").asString
            val folder = request(http, baseUrl, "POST", "api/v1/folders/camera", token = token)
            assertEquals(200, folder.code)
            val folderId = JsonParser.parseString(String(folder.body, Charsets.UTF_8)).asJsonObject.get("id").asLong

            val bytes = ByteArray(SyncConfig.RESUMABLE_CHUNK_BYTES.toInt() + 3) { (it % 251).toByte() }
            val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val service = Retrofit.Builder().baseUrl(baseUrl)
                .client(OkHttpClient.Builder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
                }.build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(UploadSessionService::class.java)
            val account = BackupAccount(baseUrl, "a1-smoke@test.local")
            val api = mock(ApiServiceFactory::class.java)
            val network = mock(Network::class.java)
            `when`(api.authUploadSessionService(baseUrl, network)).thenReturn(service)
            `when`(api.authUploadSessionService(baseUrl)).thenReturn(service)
            val wifi = mock(WifiUploadNetwork::class.java)
            `when`(wifi.current()).thenReturn(network)
            val accounts = mock(BackupAccountProvider::class.java)
            `when`(accounts.current()).thenReturn(account)
            val source = object : CameraUploadSource(ApplicationProvider.getApplicationContext(), mock(MediaStreamOpener::class.java)) {
                override fun originalMode() = false
                override fun state(record: UploadSessionRecord) = CameraSourceState.Current
                override fun open(record: UploadSessionRecord, offset: Long): InputStream =
                    ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
            }
            val testDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
                .allowMainThreadQueries().build()
            db = testDb
            val upload = ResumableUpload(testDb.uploadSessionDao(), testDb.mediaFileDao(), accounts, api, source, wifi)
            val file = MediaFile(42, uri = "content://media/42", displayName = "a1-smoke.jpg", relativePath = "DCIM/Camera/",
                mimeType = "image/jpeg", size = bytes.size.toLong(), createdAt = 1, lastModified = 2,
                checksum = checksum, status = MediaFileStatus.PENDING_UPLOAD)
            var result: ResumableResult = ResumableResult.Retry
            repeat(15) {
                if (result !is ResumableResult.Complete) {
                    result = upload.camera(file, folderId, WorkBudget.UNLIMITED) { false }
                    if (result !is ResumableResult.Complete) kotlinx.coroutines.delay(1_000)
                }
            }
            assertTrue("S1 result=$result; log=${String(Files.readAllBytes(log)).takeLast(1000)}", result is ResumableResult.Complete)
            val saved = (result as ResumableResult.Complete).file
            assertEquals(checksum, saved.checksum)
            val download = request(http, baseUrl, "GET", "api/v1/files/${saved.id}/download", token = token)
            assertEquals(200, download.code)
            assertArrayEquals(bytes, download.body)

            val root = request(http, baseUrl, "GET", "api/v1/folders/root", token = token)
            assertEquals(200, root.code)
            val rootId = JsonParser.parseString(String(root.body, Charsets.UTF_8)).asJsonObject.get("id").asLong
            val manualBytes = ByteArray(SyncConfig.MULTIPART_MAX_BYTES.toInt() + 1) { (it % 239).toByte() }
            val manualChecksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manualBytes))
            val manualUri = Uri.parse("content://a4-smoke/manual")
            val manualDocs = object : LocalDocuments(ApplicationProvider.getApplicationContext()) {
                override fun hasPersistedRead(uri: Uri) = uri == manualUri
                override fun describe(uri: Uri) = LocalFile(uri, "a4-manual.bin", manualBytes.size.toLong(), "application/octet-stream")
                override fun lastModified(uri: Uri): Long? = 2
                override fun openAt(uri: Uri, offset: Long): InputStream =
                    ByteArrayInputStream(manualBytes, offset.toInt(), manualBytes.size - offset.toInt())
            }
            upload.manualSource = ManualUploadSource(manualDocs)
            val manualJob = ManualUploadJob(UUID.randomUUID().toString(), baseUrl, account.accountId,
                manualUri.toString(), "a4-manual.bin", manualBytes.size.toLong(), "application/octet-stream",
                2, rootId, true, manualChecksum, UUID.randomUUID().toString(), status = ManualUploadJob.RUNNING)
            var manualResult: ResumableResult = ResumableResult.Retry
            repeat(20) {
                if (manualResult !is ResumableResult.Complete) {
                    manualResult = upload.manual(manualJob, WorkBudget.UNLIMITED, { false }, { })
                    if (manualResult !is ResumableResult.Complete) kotlinx.coroutines.delay(1_000)
                }
            }
            assertTrue("A4 result=$manualResult; log=${String(Files.readAllBytes(log)).takeLast(1000)}",
                manualResult is ResumableResult.Complete)
            val manualFile = (manualResult as ResumableResult.Complete).file
            assertEquals(manualChecksum, manualFile.checksum)
            val manualDownload = request(http, baseUrl, "GET", "api/v1/files/${manualFile.id}/download", token = token)
            assertEquals(200, manualDownload.code)
            assertArrayEquals(manualBytes, manualDownload.body)
        } finally {
            db?.close()
            server?.destroyForcibly()
            server?.waitFor(15, TimeUnit.SECONDS)
            container?.let { commandOk("docker", "rm", "-f", it) }
            work.toFile().deleteRecursively()
        }
    }

    private fun serverJar(): Path {
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return listOf(cwd.resolve("../PhotoCloudServer"), cwd.resolve("../../PhotoCloudServer"))
            .map { it.resolve("build/libs/photo-cloud-server-0.0.1-SNAPSHOT.jar").normalize() }
            .firstOrNull { Files.isRegularFile(it) } ?: cwd.resolve("../PhotoCloudServer/build/libs/photo-cloud-server-0.0.1-SNAPSHOT.jar")
    }

    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        check(process.waitFor(45, TimeUnit.SECONDS)) { "Timeout: ${args.joinToString(" ")}" }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "${args.first()} failed: $output" }
        return output
    }

    private fun commandOk(vararg args: String): Boolean = try { command(*args); true } catch (_: Exception) { false }

    private data class HttpResult(val code: Int, val body: ByteArray)

    private fun request(http: OkHttpClient, base: String, method: String, path: String,
                        body: String? = null, token: String? = null): HttpResult {
        val builder = Request.Builder().url(base + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val requestBody = if (method == "GET") null else (body ?: "").toRequestBody("application/json".toMediaType())
        val request = builder.method(method, requestBody).build()
        return http.newCall(request).execute().use { HttpResult(it.code, it.body?.bytes() ?: byteArrayOf()) }
    }
}
