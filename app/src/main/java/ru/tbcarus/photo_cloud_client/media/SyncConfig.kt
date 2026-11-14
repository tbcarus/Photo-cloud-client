package ru.tbcarus.photo_cloud_client.media

/**
 * Технические параметры пакетной синхронизации (A8) — единственное место их задания.
 * Размеры партий — единица выборки из Room; длительность прогона ограничивает [WORKER_TIME_BUDGET_MS].
 */
enum class UploadSizeClass { UNKNOWN, MULTIPART, RESUMABLE, TOO_LARGE }

object SyncConfig {

    const val MULTIPART_MAX_BYTES = 50L * 1024 * 1024
    const val RESUMABLE_MAX_BYTES = 20L * 1024 * 1024 * 1024
    const val RESUMABLE_CHUNK_BYTES = 8L * 1024 * 1024

    fun uploadSizeClass(size: Long): UploadSizeClass = when {
        size <= 0 -> UploadSizeClass.UNKNOWN
        size <= MULTIPART_MAX_BYTES -> UploadSizeClass.MULTIPART
        size <= RESUMABLE_MAX_BYTES -> UploadSizeClass.RESUMABLE
        else -> UploadSizeClass.TOO_LARGE
    }

    /** Строк PENDING, выбираемых Поставщиком для хеширования за раз; после каждой партии будится Загрузчик. */
    const val PROVIDER_BATCH_SIZE = 100

    /** Checksum в одном запросе pre-check. Не больше серверного sync.checksum-exists.max-batch-size, иначе 400. */
    const val PRECHECK_BATCH_SIZE = 500

    /** Строк PENDING_UPLOAD, выбираемых Загрузчиком за раз; файлы всё равно отправляются по одному. */
    const val UPLOAD_BATCH_SIZE = 20

    /** Строк снимка MediaStore, записываемых в Room одной транзакцией. */
    const val SCAN_WRITE_BATCH_SIZE = 500

    /**
     * Бюджет одного прогона Worker: после него новый файл не начинается, работа продолжается следующим запуском.
     * Запас до системного окна выполнения (~10 минут) — на уже начатую операцию с одним файлом.
     */
    const val WORKER_TIME_BUDGET_MS = 420_000L

    /** Папок дерева, читаемых за один ход по источнику FolderBackupWorker; источники чередуются ходами. */
    const val FOLDER_SCAN_PER_TURN = 20

    /** Папок, создаваемых на Server за один ход по источнику. */
    const val FOLDER_CREATE_PER_TURN = 20

    /** Файлов, отправляемых за один ход по источнику: большой источник не задерживает следующие. */
    const val FOLDER_FILES_PER_TURN = 10
}
