package ru.tbcarus.photo_cloud_client.core.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.tbcarus.photo_cloud_client.api.TestService
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    @Named("plain") private val plainClient: OkHttpClient
) {
    suspend fun testConnection(baseUrl: String): Result<String> {
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(plainClient)
            .build()
            .create(TestService::class.java)

        return withContext(Dispatchers.IO) {
            try {
                val response = api.testServer().execute()
                if (response.isSuccessful) {
                    val message = response.body()?.message ?: "Успешное подключение"
                    Result.success(message)
                } else {
                    val error = response.errorBody()?.string() ?: "Неизвестная ошибка"
                    Result.failure(Exception(error))
                }
            } catch (e: IOException) {
                Result.failure(Exception("Ошибка сети: ${e.localizedMessage}"))
            } catch (e: HttpException) {
                Result.failure(Exception("Ошибка HTTP: ${e.code()}"))
            } catch (e: Exception) {
                Result.failure(Exception("Неизвестная ошибка: ${e.localizedMessage}"))
            }
        }
    }
}
