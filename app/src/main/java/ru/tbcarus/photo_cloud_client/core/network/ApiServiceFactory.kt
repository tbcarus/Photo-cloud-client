package ru.tbcarus.photo_cloud_client.core.network

import android.net.Network
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.ConnectionPool
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.FileService
import ru.tbcarus.photo_cloud_client.api.FolderService
import ru.tbcarus.photo_cloud_client.api.NotificationService
import ru.tbcarus.photo_cloud_client.api.ShareService
import ru.tbcarus.photo_cloud_client.api.TestService
import ru.tbcarus.photo_cloud_client.api.UploadSessionService
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import java.net.InetAddress

@Singleton
class ApiServiceFactory @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @Named("auth") private val authClient: OkHttpClient,
    @Named("register") private val registerClient: OkHttpClient
) {
    private var uploadServiceKey: Pair<String, Network>? = null
    private var uploadService: UploadSessionService? = null
    private var uploadClient: OkHttpClient? = null
    private fun build(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    fun plainAuthService(): AuthService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, plainClient).create(AuthService::class.java)
    }

    // Только для POST регистрации: увеличенное ожидание ответа и без автоповтора.
    fun registerAuthService(): AuthService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, registerClient).create(AuthService::class.java)
    }

    fun authAuthService(): AuthService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, authClient).create(AuthService::class.java)
    }

    fun authTestService(): TestService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, authClient).create(TestService::class.java)
    }

    fun plainTestService(baseUrl: String): TestService {
        require(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, plainClient).create(TestService::class.java)
    }

    fun authFileService(): FileService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, authClient).create(FileService::class.java)
    }

    @Synchronized
    fun authUploadSessionService(baseUrl: String, network: Network): UploadSessionService {
        val key = baseUrl to network
        if (uploadServiceKey == key) uploadService?.let { return it }
        uploadClient?.connectionPool?.evictAll()
        val client = authClient.newBuilder()
            .connectionPool(ConnectionPool())
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().tag(Network::class.java, network).build())
            }
            .build()
        return build(baseUrl, client).create(UploadSessionService::class.java).also {
            uploadServiceKey = key
            uploadService = it
            uploadClient = client
        }
    }

    fun authFolderService(): FolderService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, authClient).create(FolderService::class.java)
    }

    fun authShareService(): ShareService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, authClient).create(ShareService::class.java)
    }

    fun authNotificationService(): NotificationService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, authClient).create(NotificationService::class.java)
    }
}
