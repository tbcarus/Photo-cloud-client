package ru.tbcarus.photo_cloud_client.core.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.tbcarus.photo_cloud_client.api.AuthService
import ru.tbcarus.photo_cloud_client.api.TestService
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ApiServiceFactory @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @Named("auth") private val authClient: OkHttpClient
) {
    fun plainAuthService(): AuthService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(plainClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthService::class.java)
    }

    fun authAuthService(): AuthService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthService::class.java)
    }

    fun authTestService(): TestService {
        val baseUrl = baseUrlProvider.baseUrl
        check(baseUrl.isNotBlank()) { "Server URL is not configured" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TestService::class.java)
    }
}
