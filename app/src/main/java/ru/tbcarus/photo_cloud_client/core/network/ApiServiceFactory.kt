package ru.tbcarus.photo_cloud_client.core.network

import android.net.Network
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
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
import java.util.Collections
import java.util.WeakHashMap

@Singleton
class ApiServiceFactory @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @Named("auth") private val authClient: OkHttpClient,
    @Named("register") private val registerClient: OkHttpClient
) {
    private var boundClientKey: Pair<String, Network>? = null
    private var boundClient: OkHttpClient? = null
    private val automaticClients = Collections.newSetFromMap(WeakHashMap<OkHttpClient, Boolean>())

    @Synchronized
    fun cancelAutomaticCalls() {
        automaticClients.forEach { it.dispatcher.cancelAll() }
    }
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

    @Synchronized
    fun plainTestService(baseUrl: String, network: Network): TestService {
        require(baseUrl.isNotBlank()) { "Server URL is not configured" }
        val client = bound(plainClient, network)
        automaticClients.add(client)
        return build(baseUrl, client).create(TestService::class.java)
    }

    fun authFileService(): FileService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, authClient).create(FileService::class.java)
    }

    fun authFileService(network: Network): FileService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, networkClient(baseUrl, network)).create(FileService::class.java)
    }

    fun authUploadSessionService(baseUrl: String, network: Network): UploadSessionService {
        return build(baseUrl, networkClient(baseUrl, network)).create(UploadSessionService::class.java)
    }

    @Synchronized
    private fun networkClient(baseUrl: String, network: Network): OkHttpClient {
        val key = baseUrl to network
        if (boundClientKey == key) boundClient?.let { return it }
        boundClient?.connectionPool?.evictAll()
        val client = bound(authClient, network)
        boundClientKey = key
        boundClient = client
        automaticClients.add(client)
        return client
    }

    private fun bound(base: OkHttpClient, network: Network): OkHttpClient = base.newBuilder()
            .dispatcher(Dispatcher())
            .connectionPool(ConnectionPool())
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().tag(Network::class.java, network).build())
            }
            .build()

    fun authFolderService(): FolderService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, authClient).create(FolderService::class.java)
    }

    fun authFolderService(network: Network): FolderService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return build(baseUrl, networkClient(baseUrl, network)).create(FolderService::class.java)
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
