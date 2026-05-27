package ru.tbcarus.photo_cloud_client.core.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import retrofit2.Response
import ru.tbcarus.photo_cloud_client.api.models.ErrorResponseDto

object ApiErrorParser {

    private val gson = Gson()

    fun parse(response: Response<*>): String {
        val rawBody = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }

        if (rawBody.isNullOrBlank()) {
            return "HTTP ${response.code()}"
        }

        val dto = try {
            gson.fromJson(rawBody, ErrorResponseDto::class.java)
        } catch (e: JsonSyntaxException) {
            return rawBody
        }

        if (dto != null) {
            val fieldErrors = dto.fieldErrors
            if (!fieldErrors.isNullOrEmpty()) {
                return fieldErrors.entries.joinToString("; ") { "${it.key}: ${it.value}" }
            }
            dto.message?.takeIf { it.isNotBlank() }?.let { return it }
            dto.code?.takeIf { it.isNotBlank() }?.let { return it }
        }

        return "HTTP ${response.code()}"
    }
}
