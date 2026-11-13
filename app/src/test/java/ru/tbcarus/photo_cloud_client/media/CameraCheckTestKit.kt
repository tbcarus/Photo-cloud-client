package ru.tbcarus.photo_cloud_client.media

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import ru.tbcarus.photo_cloud_client.backup.BackupAccountProvider
import ru.tbcarus.photo_cloud_client.core.db.AppDatabase
import ru.tbcarus.photo_cloud_client.core.network.ApiServiceFactory
import ru.tbcarus.photo_cloud_client.core.server.ServerPreferences
import ru.tbcarus.photo_cloud_client.core.storage.InMemoryTokenStorage
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider

/**
 * [CameraCheckRepository] над той же БД для тестов, где ручная сверка не запускается:
 * без строки проверки Поставщик и Загрузчик работают как прежде.
 */
fun idleCameraCheck(context: Context, db: AppDatabase, tokens: TokenStorage = InMemoryTokenStorage()): CameraCheckRepository {
    val baseUrlProvider = BaseUrlProvider(ServerPreferences(context))
    val client = OkHttpClient()
    return CameraCheckRepository(
        context, db, ApiServiceFactory(baseUrlProvider, client, client, client), MediaStreamOpener(context),
        BackupAccountProvider(baseUrlProvider, tokens), SyncScheduler(context), tokens
    )
}

/** Access token с заданным subject: аккаунт проверки определяется по нему. */
fun accessTokenFor(subject: String): String {
    fun part(json: String) = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    return "${part("""{"alg":"none"}""")}.${part("""{"sub":"$subject","exp":4102444800}""")}.sig"
}
