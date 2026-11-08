package ru.tbcarus.photo_cloud_client.core.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import retrofit2.Response
import ru.tbcarus.photo_cloud_client.api.models.ErrorResponseDto

/**
 * Код ErrorResponse (null — тела нет или это не ErrorResponse) и текст для пользователя.
 * [fieldErrors] — исходные ошибки полей: по ним, например, отмечаются отклонённые адреса выдачи доступа.
 */
data class ApiError(val code: String?, val message: String, val fieldErrors: Map<String, String>? = null)

object ApiErrorParser {

    private val gson = Gson()

    fun parse(response: Response<*>): String = read(response).message

    /** Код ErrorResponse из тела ошибки; null, если тела нет или это не ErrorResponse. Тело читается один раз. */
    fun errorCode(response: Response<*>): String? = read(response).code

    /** Код и текст из одного чтения тела: повторно errorBody() не читается. */
    fun read(response: Response<*>): ApiError {
        val rawBody = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }

        if (rawBody.isNullOrBlank()) {
            return ApiError(null, "HTTP ${response.code()}")
        }

        val dto = try {
            gson.fromJson(rawBody, ErrorResponseDto::class.java)
        } catch (e: JsonSyntaxException) {
            return ApiError(null, rawBody)
        }

        val code = dto?.code?.takeIf { it.isNotBlank() }
        if (dto != null) {
            val fieldErrors = dto.fieldErrors
            if (!fieldErrors.isNullOrEmpty()) {
                return ApiError(code, fieldErrors.entries.joinToString("; ") { "${it.key}: ${it.value}" }, fieldErrors)
            }
            dto.message?.takeIf { it.isNotBlank() }?.let { return ApiError(code, it) }
            code?.let { return ApiError(it, it) }
        }

        return ApiError(code, "HTTP ${response.code()}")
    }
}
