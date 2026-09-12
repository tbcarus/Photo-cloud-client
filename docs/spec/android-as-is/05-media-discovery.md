# Media discovery

Media scan читает только `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`. Selection: `RELATIVE_PATH LIKE ?`, argument `DCIM/%`, sortOrder=null. Подпапки DCIM включены; изображения вне этой области и видео не входят в scan. Отдельного выбора альбомов, volumes, SAF или incremental generation/date filter нет. Explicit `IS_PENDING`/`IS_TRASHED` selection отсутствует.

## Query и metadata

Projection состоит из _ID, DISPLAY_NAME, RELATIVE_PATH, MIME_TYPE, SIZE, DATE_TAKEN, DATE_MODIFIED, DATE_ADDED. Весь успешный результат собирается в память; пагинации нет.

| Поле | Обработка |
| --- | --- |
| _ID | <=0 пропускается; положительный Long становится PK |
| DISPLAY_NAME | Blank/null → image_ID |
| RELATIVE_PATH | Blank/null → null |
| MIME_TYPE | Blank/null → image/* |
| SIZE | Null → 0; положительность scanner не валидирует |
| DATE_TAKEN | Положительное значение в ms — первый кандидат createdAt |
| DATE_MODIFIED | Положительное значение seconds×1000; lastModified и второй кандидат createdAt |
| DATE_ADDED | Положительное seconds×1000; третий кандидат createdAt |
| Все даты отсутствуют/неположительны | createdAt=now, lastModified=createdAt; возможен новый change reset на каждом scan |

URI строится через ContentUris.withAppendedId(collection, ID) и сохраняется строкой. DATA/absolute path не запрашивается. Потоки открываются ContentResolver.openInputStream. Клиент не создаёт private snapshot исходных байтов.

## Permissions и ошибки query

API 29–32 использует READ_EXTERNAL_STORAGE, API 33+ — READ_MEDIA_IMAGES. Runtime request находится в FilesScreen, при входе и наличии токенов/URL. Repository, checksum и coordinator также проверяют разрешение. Worker сам permission не запрашивает.

Нет отдельной модели READ_MEDIA_VISUAL_USER_SELECTED, READ_MEDIA_VIDEO или ACCESS_MEDIA_LOCATION. Нет onResume permission reconcile и перехода в system settings после окончательного отказа.

Отсутствие permission до query возвращает PermissionDenied. Сам query находится **до** локального try scanner; исключение из query перехватывает MediaFileRepository.scanImages и возвращает ScanOutcome.Error. Null cursor — Error, не пустой успешный scan. Ошибки получения колонок/итерации cursor преобразуются в Error; исключение отдельной строки молча пропускает строку, сохраняя Success для остальных.

## Применение snapshot

1. insertAll IGNORE новых MediaFile с PENDING/hash=null/ID=null.
2. Для каждой строки reset по отличию size или lastModified, затем обновление metadata.
3. Получение всех Room ID и физический DELETE отсутствующих в snapshot, чанками 500.

Переименование/перемещение с прежним ID обновляет metadata. Hash/status сбрасываются только по size/mtime, а не по имени/path. Выход из DCIM и исчезновение из видимости удаляют локальную строку независимо от её статуса. LOCAL_DELETED не записывается; remote DELETE не вызывается.

Успешный неполный snapshot, включая пропущенные malformed rows, может удалить ранее видимые строки. Error до applyToRoom этого не делает. Ошибка во время применения может оставить частичные writes. Stale detection не удостоверяет физическое удаление оригинала.

## Байты и время

Hashing и upload независимо открывают один content URI. Upload дополнительно делает pre-open и использует SIZE как contentLength при size>0, иначе −1. Размер потока и неизменность байтов не сверяются с metadata.

Android не извлекает EXIF/GPS и не вызывает setRequireOriginal. Возможное location redaction является свойством MediaStore runtime; фактические GPS/байты, selected-photo visibility и согласованность SIZE с потоком остаются OPEN-AND-002/003/005. Нельзя утверждать, что GPS всегда удалён или оба независимых чтения обязательно дают идентичные байты.

createdAt/lastModified — локальные epoch ms. Они не отправляются серверу как capturedAt; серверные даты из upload response клиент не использует.
