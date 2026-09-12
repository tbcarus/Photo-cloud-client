# Локальная модель данных

## MediaFile

MediaFile представляет одну индексированную строку MediaStore и её локальное состояние обработки. Все поля persisted в `media_files`; API DTO отдельно в Room не хранятся.

| Поле | Тип / null | Источник и lifecycle |
| --- | --- | --- |
| mediaStoreId | Long, required, PK | MediaStore _ID > 0; Room не генерирует ID |
| serverFileId | Long?, default null | Только положительный FileItemDto.id успешного upload; очищается при size/mtime change |
| uri | String, required | content URI Images с добавленным _ID; обновляется scan |
| displayName | String, required | DISPLAY_NAME или image_ID; UI и multipart filename |
| relativePath | String? | RELATIVE_PATH; blank → null; отражает локальный путь, не server folder |
| mimeType | String, required | MediaStore MIME или image/*; upload guard и Content-Type части |
| size | Long, required | MediaStore SIZE, null → 0; detector изменений и заявленная длина upload |
| createdAt | Long, required | Epoch ms: DATE_TAKEN > 0 → modified seconds×1000 → added seconds×1000 → now |
| lastModified | Long, required | Положительный DATE_MODIFIED×1000, иначе createdAt |
| checksum | String? | SHA-256 прочитанного потока, 64 lowercase hex; discovery/change → null |
| status | MediaFileStatus, default PENDING | Очередь и результат обработки |
| failureReason | FailureReason?, default null | Причина последнего markFailed; не raw HTTP error |
| retryCount | Int, default 0 | Каждый markFailed увеличивает на 1; не число worker/HTTP attempts |
| lastFailureAt | Long?, default null | Wall-clock epoch ms последнего markFailed; eligibility cooldown |

Kotlin defaults не являются SQL DEFAULT свежей таблицы. Hash/checksum — identity содержимого для pre-check, а не PK локального объекта.

## Local identity и scope

Сопоставление между scan использует только `mediaStoreId`. URI выводится из этого ID; имя и путь не определяют identity. Два локальных ID с одинаковыми байтами остаются двумя строками. Отдельного volume/device ID нет.

Room имеет scope установки приложения, без namespace по account, server или folder. Токены также представлены одной парой без server binding. Logout и успешная смена URL не очищают и не переразмечают строки. Старое SYNCED остаётся SYNCED при смене principal/host; pending обрабатывается с текущими credentials.

## Server identity

Upload 2xx с `id>0` вызывает markUploaded: status=SYNCED, serverFileId=id, failureReason/lastFailureAt=null, retryCount=0. Ответный checksum не записывается вместо локального.

Pre-check existing выполняет **только status UPDATE**. Он не получает ID из API, не заполняет и не очищает уже имеющийся serverFileId. В обычном пути discovery → hash → existing ID остаётся null.

При изменении size/mtime серверный ID вместе с hash сбрасывается. Нет list-sync для последующего заполнения ID. В Room не хранятся folderId, server/account identity, uploadedAt, remote filename, server MIME/size/capturedAt/metadata, upload session или byte progress.

## Lifecycle и failure metadata

Новый ID → PENDING; hashing → CHECKSUM_READY; pre-check → SYNCED либо PENDING_UPLOAD; upload → SYNCED либо локальная/HTTP failure. Отсутствующий в успешном scan ID физически удаляется.

markFailed сохраняет checksum/server ID, увеличивает retryCount и пишет время. resetFailedForRetry очищает reason/time, возвращает PENDING, сохраняет count/hash/ID. Checksum success очищает reason/count/time и пишет hash; upload success очищает те же failure поля; content change очищает hash, ID и все failure поля.

Эти комбинации не закреплены SQL CHECK constraints. Конкурентные status-only updates допускают комбинации за пределами обычного последовательного пути; точные примеры — [09](09-state-model.md).

## Связанные модели

| Модель | Содержание | Lifetime |
| --- | --- | --- |
| MediaStoreImage | Восемь нормализованных local metadata fields до применения в Room | Один scan snapshot |
| ScanResult | scanned, insertedOrUpdated, deletedStale; insertedOrUpdated равно размеру snapshot | Один scan |
| ChecksumResult | processed, succeeded, failed | Одна checksum стадия |
| ChecksumPrecheckResult | checked, existing, pendingUpload, unchanged; counts локальных строк | Одна pre-check стадия |
| UploadResult | attempted, succeeded, failed, leftPending; leftPending — встретившая transient запись, не размер всей очереди | Одна upload стадия |
| SyncResult | Nullable результаты четырёх стадий | Один worker |
| SyncStatusRecord | SyncStatus + timestamp | Последний результат в памяти процесса |
| Tokens | accessToken, refreshToken | Encrypted persistence и in-memory Flow |
| UiState | Форма auth/network, список Room, флаги и сообщения Files | ViewModel; без SavedStateHandle |

MediaFileStatus и FailureReason перечислены полностью в [09](09-state-model.md). SyncStatus и WorkInfo не являются полями MediaFile.
