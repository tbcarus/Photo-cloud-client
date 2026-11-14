package ru.tbcarus.photo_cloud_client.api

import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto

data class CreateUploadSessionRequest(
    val clientRequestId: String,
    val folderId: Long,
    val originalFilename: String,
    val size: Long,
    val checksum: String,
    val backupSource: String? = null
)

data class UploadSessionDto(
    val id: String?,
    val confirmedOffset: Long?,
    val state: String?,
    val maxChunkSizeBytes: Long?,
    val result: FileItemDto?,
    val error: String?
)

interface UploadSessionService {
    @POST("api/v1/upload-sessions")
    fun create(@Body request: CreateUploadSessionRequest): Call<UploadSessionDto>

    @GET("api/v1/upload-sessions/{id}")
    fun get(@Path("id") id: String): Call<UploadSessionDto>

    @PATCH("api/v1/upload-sessions/{id}")
    fun patch(@Path("id") id: String, @Header("Upload-Offset") offset: Long, @Body body: RequestBody): Call<UploadSessionDto>

    @POST("api/v1/upload-sessions/{id}/complete")
    fun complete(@Path("id") id: String): Call<UploadSessionDto>

    @DELETE("api/v1/upload-sessions/{id}")
    fun cancel(@Path("id") id: String): Call<UploadSessionDto>
}
