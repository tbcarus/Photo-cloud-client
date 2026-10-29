package ru.tbcarus.photo_cloud_client.media

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Сохраняемые итоги прогонов авто-sync для UI (A7.1, DataStore) — раздельно для Поставщика и Загрузчика:
 * последняя проблема и время её возникновения, время последнего успешного scan, время последней подтверждённой
 * отправки файла. Пишутся один раз в конце прогона Worker; «выполняется» не сохраняется — его даёт WorkManager.
 * WorkManager не умеет отдавать причину retry через WorkInfo, поэтому [UploadWorker] и [ProviderWorker] пишут итог сюда напрямую.
 */
class SyncStatusStore(
    private val dataStore: DataStore<Preferences>,
    private val wallClock: () -> Long = System::currentTimeMillis
) {

    private companion object {
        const val TAG = "SyncStatusStore"
        val PROVIDER_PROBLEM = stringPreferencesKey("provider_problem")
        val PROVIDER_PROBLEM_AT = longPreferencesKey("provider_problem_at")
        val PROVIDER_LAST_SCAN_AT = longPreferencesKey("provider_last_scan_at")
        val UPLOADER_PROBLEM = stringPreferencesKey("uploader_problem")
        val UPLOADER_PROBLEM_AT = longPreferencesKey("uploader_problem_at")
        val UPLOADER_LAST_UPLOAD_AT = longPreferencesKey("uploader_last_upload_at")
    }

    /** Сохранённые итоги обеих частей; нечитаемый файл даёт пустые итоги, а не ошибку UI. */
    fun observeRunResults(): Flow<SyncRunResults> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            SyncRunResults(
                providerProblem = prefs.problem(PROVIDER_PROBLEM, PROVIDER_PROBLEM_AT),
                lastScanAt = prefs[PROVIDER_LAST_SCAN_AT],
                uploaderProblem = prefs.problem(UPLOADER_PROBLEM, UPLOADER_PROBLEM_AT),
                lastUploadAt = prefs[UPLOADER_LAST_UPLOAD_AT]
            )
        }
        .distinctUntilChanged()

    /**
     * Итог прогона Поставщика: [scanned] — scan в этом прогоне выполнен успешно; [problem] — ошибка прогона,
     * null снимает прежнюю проблему Поставщика. Сведения Загрузчика не меняются.
     */
    suspend fun recordProviderRun(scanned: Boolean, problem: SyncStatus?) = save { prefs, now ->
        if (scanned) prefs[PROVIDER_LAST_SCAN_AT] = now
        prefs.setProblem(PROVIDER_PROBLEM, PROVIDER_PROBLEM_AT, problem, now)
    }

    /**
     * Итог прогона Загрузчика: [problem] — проблема прогона, null снимает прежнюю проблему Загрузчика;
     * [filesUploaded] — в прогоне Server подтвердил хотя бы один отправленный файл (existing не отправка).
     * Сведения Поставщика не меняются.
     */
    suspend fun recordUploaderRun(problem: SyncStatus?, filesUploaded: Boolean) = save { prefs, now ->
        if (filesUploaded) prefs[UPLOADER_LAST_UPLOAD_AT] = now
        prefs.setProblem(UPLOADER_PROBLEM, UPLOADER_PROBLEM_AT, problem, now)
    }

    // Итог для UI не должен влиять на синхронизацию: ошибка записи только логируется.
    // DataStore не пишет файл, если данные не изменились, — повтор той же проблемы ничего не записывает.
    private suspend fun save(update: (MutablePreferences, Long) -> Unit) {
        try {
            dataStore.edit { prefs -> update(prefs, wallClock()) }
        } catch (e: IOException) {
            Log.w(TAG, "Не удалось сохранить итог прогона", e)
        }
    }

    // Время возникновения сохраняется, пока проблема та же: «сервер недоступен с 14:05».
    private fun MutablePreferences.setProblem(
        key: Preferences.Key<String>,
        atKey: Preferences.Key<Long>,
        problem: SyncStatus?,
        now: Long
    ) {
        if (problem == null) {
            remove(key)
            remove(atKey)
        } else if (this[key] != problem.name || this[atKey] == null) {
            this[key] = problem.name
            this[atKey] = now
        }
    }

    // Неизвестное значение (например, из другой версии) считается отсутствием проблемы.
    private fun Preferences.problem(key: Preferences.Key<String>, atKey: Preferences.Key<Long>): SyncProblem? {
        val status = this[key]?.let { name -> SyncStatus.entries.firstOrNull { it.name == name } } ?: return null
        val since = this[atKey] ?: return null
        return SyncProblem(status, since)
    }
}

/** Последняя проблема части синхронизации и время её возникновения (ms, часы устройства). */
data class SyncProblem(val status: SyncStatus, val since: Long)

/** Сохранённые итоги прогонов Поставщика и Загрузчика (A7.1); время — ms по часам устройства. */
data class SyncRunResults(
    val providerProblem: SyncProblem? = null,
    /** Конец последнего прогона Поставщика с успешным scan. */
    val lastScanAt: Long? = null,
    val uploaderProblem: SyncProblem? = null,
    /** Конец последнего прогона Загрузчика, в котором Server подтвердил отправленный файл. */
    val lastUploadAt: Long? = null
)
