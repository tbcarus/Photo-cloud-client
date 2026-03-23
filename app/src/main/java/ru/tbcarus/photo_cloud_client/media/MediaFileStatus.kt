package ru.tbcarus.photo_cloud_client.media

enum class MediaFileStatus {
    PENDING,   // файл найден, хэш ещё не посчитан
    PENDING_UPLOAD, // хэш есть, ждёт загрузки на сервер
    UPLOADING,      // сейчас загружается
    SYNCED,         // есть на сервере, всё хорошо
    FAILED,         // ошибка загрузки, будет повтор
    LOCAL_DELETED,  // удалён локально (для будущей логики удаления на сервере)
}
