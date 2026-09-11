# 07. Local media storage

## Источник, selection и permissions

[CONFIRMED] MediaStoreRepository.loadImages использует ContentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), selection RELATIVE_PATH LIKE ?, args DCIM/%, sortOrder=null. Это **только Images**, не video даже несмотря на комментарий «снимки/видео». Pictures/Download/Screenshots вне DCIM не индексируются; подпапки DCIM включаются независимо от имени. [MediaStoreRepository.kt:43](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:43>).

[CONFIRMED] Before query: API>=33 READ_MEDIA_IMAGES, иначе READ_EXTERNAL_STORAGE. Manifest declares обе (legacy maxSdk32) и INTERNET. READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, SAF/persistable URI grant не используются. Runtime request находится в FilesScreen и зависит от tokens+baseUrl на момент LaunchedEffect(Unit). Отказ вызывает scan, который формирует PermissionDenied card.

[PARTIAL] Нет onResume recheck или listener permission loss; worker не может запрашивать grant. Проверка до query не закрывает race revoke: сам query расположен вне локального try scanner, но вызывающий MediaFileRepository ловит Exception и превращает в ScanOutcome.Error. Permission loss непосредственно в checksum stream→per-file PERMISSION, что auto-retry не восстанавливает. [MediaStoreRepository.kt:61](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:61>), [MediaFileRepository.kt:24](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:24>), [ChecksumRepository.kt:118](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:118>).

## Metadata projection и нормализация

[CONFIRMED] Projection: _ID, DISPLAY_NAME, RELATIVE_PATH, MIME_TYPE, SIZE, DATE_TAKEN, DATE_MODIFIED, DATE_ADDED.

| Column | Mapping / invalid handling |
| --- | --- |
| _ID | Long>0, иначе строка пропускается |
| DISPLAY_NAME | Nonblank String, иначе image_ID |
| RELATIVE_PATH | Blank→null; absolute DATA path не читается |
| MIME_TYPE | Blank/null→image/* |
| SIZE | Null→0, иначе Long без проверки >0 |
| DATE_TAKEN | >0 в ms; предпочтительный createdAt |
| DATE_MODIFIED | >0 seconds×1000; createdAt fallback и lastModified |
| DATE_ADDED | >0 seconds×1000; следующий createdAt fallback |
| Полностью нет дат | createdAt=System.currentTimeMillis; lastModified=createdAt |

[CONFIRMED] Ошибка разбора отдельной строки молча пропускается. Null cursor — Error, а не empty success. Cursor.use закрывает cursor; row list целиком собирается в памяти. Нет incremental generation/date query, paging, explicit IS_PENDING/IS_TRASHED selection и проверки конкретного storage volume. [MediaStoreRepository.kt:80](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:80>).

[RISK] Если все даты отсутствуют, now меняется между scan, что может сбрасывать checksum через lastModified. Если содержимое меняется без size/mtime, reset не произойдёт. DATE_MODIFIED нормализуется из seconds, поэтому изменения в одном временном интервале при прежнем размере могут быть не замечены. Это вывод из алгоритма, без device reproduction.

## MediaStore object ↔ Room MediaFile

[CONFIRMED] Identity — только MediaStore _ID→MediaFile.mediaStoreId PK. URI конструируется ContentUris.withAppendedId(EXTERNAL_CONTENT_URI,id), хранится строкой и обновляется scan. displayName/path не являются identity. volume/account/server/device ID в PK отсутствуют. [MediaFileRepository.kt:88](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:88>), [MediaFile.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt:8>).

| Изменение | Фактическое поведение следующего успешного scan |
| --- | --- |
| [CONFIRMED] Новый ID | insert IGNORE создаёт PENDING/checksum null/server ID null |
| [CONFIRMED] Прежний ID, те же size/mtime | Сохраняет sync state/hash/server ID; обновляет local metadata |
| [CONFIRMED] Прежний ID, новые size или mtime | Сброс hash/server ID/status/failure/retry →PENDING, затем metadata update |
| [CONFIRMED] Rename внутри DCIM с прежним ID | Обновляет имя; remote rename не делает; hash reset только при size/mtime change |
| [CONFIRMED] Move внутри DCIM с прежним ID | Обновляет relativePath; target server остаётся CAMERA |
| [CONFIRMED] Move за пределы DCIM / local delete | Row отсутствует в snapshot→DELETE Room; server copy не удаляется |
| [INFERRED] Move с новым ID / MediaStore rebuild | Старый удаляется, новый PENDING; при прежних bytes server precheck может вернуть existing |
| [RISK] Неполный visibility snapshot | Пропущенный/недоступный item может быть принят за stale; tombstone/differentiation нет |
| [CONFIRMED] Null cursor / scan exception | Delete stale не выполняется, если applyToRoom не начат |

[RISK] Успешный scan с пропущенными malformed rows не отличается от полного списка: stale detection удалит такие existing rows. Это не удаление оригинального файла, но потеря локальной sync history. [MediaFileRepository.kt:64](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:64>).

## Доступ к bytes

[CONFIRMED] Checksum openInputStream(Uri.parse(uri))→stream.use→ChecksumUtil.sha256 с buffer8192. Upload сначала открывает/закрывает stream для availability check, затем ContentUriRequestBody снова открывает его в writeTo. contentType=local MIME; contentLength=size если >0, иначе -1. Multipart filename=displayName или file_ID. Private staging/temp copy не создаётся. [ChecksumRepository.kt:105](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:105>), [ContentUriRequestBody.kt:16](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt:16>), [FileUploadRepository.kt:155](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:155>).

[PARTIAL] Availability check не проверяет неизменность bytes. Он catches только FileNotFoundException/SecurityException, другие исключения поднимаются до Worker. Во время writeTo FileNotFoundException является IOException и попадает в transient upload ветку; SecurityException из повторного открытия уходит в общий Worker catch, UPLOADING остаётся до recovery.

[CONFIRMED] Приложение не изменяет, не перемещает и не удаляет MediaStore objects. Нет собственного image cache/thumbnail loader, EXIF extractor, video metadata parser, local gallery viewer. Metadata UI — только текст Room; серверные capturedAt/GPS/dimensions не используются.

