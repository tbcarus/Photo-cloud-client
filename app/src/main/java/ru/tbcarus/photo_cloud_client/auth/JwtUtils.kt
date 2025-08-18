package ru.tbcarus.photo_cloud_client.auth

import android.util.Base64
import org.json.JSONObject

object JwtUtils {
    fun isExpired(jwt: String): Boolean {
        return try {
            val payload = jwt.split(".").getOrNull(1) ?: return true
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP))
            val exp = JSONObject(json).optLong("exp", 0L)
            if (exp == 0L) true else System.currentTimeMillis() / 1000 >= exp
        } catch (_: Exception) { true }
    }
}