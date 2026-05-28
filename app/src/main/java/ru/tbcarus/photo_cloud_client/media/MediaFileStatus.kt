package ru.tbcarus.photo_cloud_client.media

enum class MediaFileStatus {
    PENDING,        // файл найден, checksum ещё не посчитан
    HASHING,        // checksum считается прямо сейчас
    CHECKSUM_READY, // checksum есть, готов к pre-check с сервером
    PENDING_UPLOAD, // прошёл pre-check, ждёт загрузки на сервер
    UPLOADING,      // сейчас загружается
    SYNCED,         // есть на сервере, всё хорошо
    FAILED,         // ошибка (IO при hashing или upload), будет повтор
    LOCAL_DELETED,  // удалён локально (для будущей логики удаления на сервере)
}
