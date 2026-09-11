# 03. Data Model

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Пути — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`. Метки — см. [00-executive-summary.md](00-executive-summary.md).
> Категории полей: **LOCAL** — данные устройства/MediaStore · **SERVER** — значение, полученное от сервера · **DERIVED** — вычислено клиентом из контента · **SYNC_STATE** — состояние синхронизации.

---

## 1. `MediaFile` — главная модель (Room entity)

**Файл:** `media/MediaFile.kt:6-23`. **Таблица:** `media_files`. **Назначение:** запись локального индекса — одно фото MediaStore и его состояние синхронизации.

| Поле | Kotlin-тип | Nullable | Default | Категория | Источник значения | Кто пишет | Семантика |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `mediaStoreId` | `Long` | нет | — | LOCAL (**ID, PK**) | `MediaStore.Images.Media._ID` | scan (insert) | Идентичность файла между сканами. Единственный ключ |
| `serverFileId` | `Long?` | да | `null` | SERVER | `FileItemDto.id` из ответа upload | `markUploaded` (set), `resetChecksumIfFileChanged` (→ null) | ID серверной записи. **`null` у файлов, признанных существующими на pre-check** |
| `uri` | `String` | нет | — | LOCAL | `ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, _ID)` | scan | `content://media/external/images/media/<id>` |
| `displayName` | `String` | нет | — | LOCAL | `DISPLAY_NAME`, fallback `image_<id>` | scan | Имя в UI и `filename` multipart-части |
| `relativePath` | `String?` | да | — | LOCAL | `RELATIVE_PATH` (напр. `DCIM/Camera/`) | scan | Только для UI |
| `mimeType` | `String` | нет | — | LOCAL | `MIME_TYPE`, fallback `image/*` | scan | Guard `startsWith("image/")` перед upload; `Content-Type` части (сервер его игнорирует) |
| `size` | `Long` | нет | — | LOCAL | `SIZE`, `null` → `0` | scan | Детектор изменений; `Content-Length` тела upload (`0` → chunked) |
| `createdAt` | `Long` (ms) | нет | — | LOCAL | `DATE_TAKEN` → `DATE_MODIFIED*1000` → `DATE_ADDED*1000` → `now()` | scan | Сортировка `ORDER BY createdAt DESC` во всех выборках. На сервер **не передаётся** |
| `lastModified` | `Long` (ms) | нет | — | LOCAL | `DATE_MODIFIED*1000`, fallback `createdAt` | scan | Детектор изменений |
| `checksum` | `String?` | да | — | DERIVED | SHA-256 содержимого, 64 lowercase hex | `updateChecksum` (set), `resetChecksumIfFileChanged` (→ null) | Ключ pre-check. **С checksum сервера не сверяется** |
| `status` | `MediaFileStatus` | нет | `PENDING` | SYNC_STATE | — | все стадии | Позиция в state machine (см. 09) |
| `failureReason` | `FailureReason?` | да | `null` | SYNC_STATE | — | `markFailed` (set); `updateChecksum`, `markUploaded`, `resetFailedForRetry`, `resetChecksumIfFileChanged` (→ null) | Причина последнего `FAILED` |
| `retryCount` | `Int` | нет | `0` | SYNC_STATE (retry metadata) | — | `markFailed` (+1); `updateChecksum`, `markUploaded`, `resetChecksumIfFileChanged` (→ 0); `resetFailedForRetry` не трогает | Число подряд идущих неудач до успешного checksum/upload. Лимит используется только для `CHECKSUM_IO` |
| `lastFailureAt` | `Long?` (ms) | да | `null` | SYNC_STATE (retry metadata) | `System.currentTimeMillis()` | `markFailed` (set); остальные reset-методы (→ null) | Отсчёт cooldown 5 мин для auto-retry |

**Lifecycle:**
1. Запись создаётся первым сканом, увидевшим `_ID`: `INSERT … ON CONFLICT IGNORE`, `status = PENDING`.
2. Метаданные LOCAL обновляются при каждом скане.
3. Checksum/sync-поля движутся по state machine.
4. Запись **физически удаляется**, когда `_ID` пропадает из выборки MediaStore.

`LOCAL_DELETED` не используется. Полный граф — [09-state-machines.md](09-state-machines.md).

**[CONFIRMED] Чего в модели нет** (сравнение с рекомендациями `04-media-data-contract.md` §11):

| Отсутствует | Последствие |
| --- | --- |
| `folderId` серверной папки | нельзя проверить, что запись синхронизирована именно в `CAMERA`, а не в другую папку |
| серверные `originalFilename`, `uploadedAt`, `capturedAt`, `size`, `mimeType`, `fileType` | отображение только по локальным данным |
| идентификатор аккаунта / сервера | индекс общий для всех аккаунтов и серверов (см. 16) |
| флаг «удалено на устройстве» | `LOCAL_DELETED` объявлен, но не используется; строки удаляются |
| upload metadata (время начала/конца, число байт, attempt id) | нет |
| текст ошибки | хранится только enum `FailureReason`; текст сервера теряется |

## 2. `MediaFileStatus`

**Файл:** `media/MediaFileStatus.kt:3-14`. Хранится в Room как `TEXT` (`name`).

| Значение | Комментарий в коде | Фактически | Используется |
| --- | --- | --- | --- |
| `PENDING` | файл найден, checksum ещё не посчитан | ждёт hashing | да |
| `HASHING` | checksum считается | transient; сбрасывается в `PENDING` в начале каждого прогона checksum | да |
| `CHECKSUM_READY` | готов к pre-check | ждёт pre-check | да |
| `PENDING_UPLOAD` | прошёл pre-check, ждёт загрузки | очередь upload | да |
| `UPLOADING` | загружается | transient; сбрасывается в `PENDING_UPLOAD` в начале каждого прогона upload | да |
| `SYNCED` | есть на сервере | терминальное; после pre-check может быть без `serverFileId` | да |
| `FAILED` | ошибка, «будет повтор» | **[INCONSISTENCY]** повтор есть только для `CHECKSUM_IO` (≤ 3 раз); остальные причины терминальны | да |
| `LOCAL_DELETED` | для будущей логики удаления на сервере | **[UNUSED]** — не присваивается нигде; есть только ветка форматирования `FilesScreen.kt:294` | нет |

## 3. `FailureReason`

**Файл:** `media/FailureReason.kt:7-20`. Хранится как `TEXT` (`name`).

| Значение | Где выставляется | `isRetryable` | Фактический retry |
| --- | --- | --- | --- |
| `CHECKSUM_IO` | `IOException` при hashing (`ChecksumRepository.kt:127-131`) | `true` | авто, ≤ 3 раз, cooldown 5 мин |
| `FILE_NOT_FOUND` | `openInputStream == null` или `FileNotFoundException` при hashing (`ChecksumRepository.kt:107-112, 123-126`); pre-open перед upload (`FileUploadRepository.kt:155-162`) | `false` | нет |
| `PERMISSION` | `SecurityException` при hashing (`ChecksumRepository.kt:118-122`) или pre-open (`FileUploadRepository.kt:160-161`) | `false` | нет |
| `NOT_IMAGE` | `mimeType` не начинается с `image/` (`FileUploadRepository.kt:80-84`) | `false` | нет |
| `HTTP_400` / `HTTP_409` / `HTTP_413` | ответ upload (`FileUploadRepository.kt:177-182`) | `false` | нет |
| `UNKNOWN` | прочие `Exception` при hashing (`ChecksumRepository.kt:132-136`) | `false` | нет |

**[UNUSED]** Свойство `FailureReason.isRetryable` нигде не читается: auto-retry жёстко фильтрует `reason = CHECKSUM_IO` в SQL (`ChecksumRepository.kt:47-52`).

## 4. `MediaStoreImage` — снимок строки MediaStore

**Файл:** `media/MediaStoreImage.kt`. Не хранится в Room. Поля совпадают с LOCAL-полями `MediaFile`: `mediaStoreId`, `uri`, `displayName`, `relativePath?`, `mimeType`, `size`, `createdAt`, `lastModified`. Все временные метки нормализованы в миллисекунды (`MediaStoreRepository.kt:101-110`). Преобразуется в `MediaFile` через `MediaFileRepository.toMediaFile()` (`MediaFileRepository.kt:88-100`).

## 5. Модели результатов стадий

| Модель | Файл | Поля | Потребитель |
| --- | --- | --- | --- |
| `MediaStoreScanOutcome` = `Success(images)` \| `PermissionDenied` \| `Error(message)` | `media/ScanResult.kt:22-26` | — | `MediaFileRepository` |
| `ScanOutcome` = `Success(ScanResult)` \| `PermissionDenied` \| `Error` | `media/ScanResult.kt:15-19` | — | `SyncWorker`, `FilesViewModel` |
| `ScanResult` | `media/ScanResult.kt:8-12` | `scanned`, `insertedOrUpdated` (всегда = `scanned`), `deletedStale` | UI (`lastScanResult`), `SyncResult` |
| `ChecksumOutcome` = `Success` \| `PermissionDenied` \| `Error` | `media/ChecksumResult.kt:14-18` | — | `SyncWorker` |
| `ChecksumResult` | `media/ChecksumResult.kt:7-11` | `processed`, `succeeded`, `failed` | output worker'а |
| `ChecksumPrecheckOutcome` = `Success` \| `Error` | `media/ChecksumPrecheckResult.kt:16-19` | — | `SyncWorker` |
| `ChecksumPrecheckResult` | `media/ChecksumPrecheckResult.kt:8-13` | `checked`, `existing`, `pendingUpload`, `unchanged` | output worker'а |
| `CameraFolderOutcome` = `Success(folderId)` \| `NotCreatedYet` \| `Error` | `media/FolderRepository.kt:14-18` | — | pre-check, upload |
| `UploadOutcome` = `Success(result)` \| `Error(message, result?)` | `media/UploadResult.kt:15-21` | — | `SyncWorker` |
| `UploadResult` | `media/UploadResult.kt:7-12` | `attempted`, `succeeded`, `failed`, `leftPending` | `SyncWorker`. **[CONFIRMED]** В `Success` `leftPending` всегда 0: инкремент есть только на ветках, возвращающих `Error` |
| `SyncResult` | `media/SyncResult.kt` | `scan?`, `checksum?`, `precheck?`, `upload?` | `SyncWorker.buildOutput` |
| `SyncStatus` | `media/SyncStatus.kt:12-24` | `SUCCESS`, `SERVER_UNAVAILABLE`, `ERROR`, `RETRY_SCHEDULED` | `SyncStatusStore` → UI |
| `SyncStatusRecord` | `media/SyncStatus.kt:7-10` | `status`, `timestamp` | UI; только в памяти |

## 6. Auth- и конфигурационные модели

| Модель | Файл | Поля | Хранение |
| --- | --- | --- | --- |
| `Tokens` | `core/storage/Tokens.kt` | `accessToken: String`, `refreshToken: String` | EncryptedSharedPreferences, ключи `accessToken`/`refreshToken` |
| IP / порт | `core/server/ServerPreferences.kt:19-20` | `ip: String?`, `port: String?` | DataStore `settings` |
| `baseUrl` | `di/BaseUrlProvider.kt:8` | `String`, default `""` | только в памяти |

## 7. UI-модели

| Модель | Файл | Поля |
| --- | --- | --- |
| `AuthUiState` | `auth/AuthUiState.kt` | `email`, `password` (открытым текстом в памяти), `status: ConnectionStatus`, `message?`, `isLoggedIn`, `userEmail?` (из JWT `sub`), `isVerifying` |
| `NetworkUiState` | `ui/screens/network/NetworkUiState.kt` | `ip`, `port`, `isLoading`, `message?`, `connectionStatus` |
| `FilesUiState` | `ui/screens/files/FilesUiState.kt` | `files: List<MediaFile>` (вся таблица), `isScanning`, `lastScanResult?`, `permissionDenied`, `errorMessage?`, `isSyncing`, `lastSyncStatus?` |
| `ConnectionStatus` | `ui/components/ConnectionStatus.kt` | `NONE`, `SUCCESS`, `ERROR`, `LOADING` + Compose `Color` и заголовок диалога |
| `BottomNavItem` | `ui/components/BottomNavItem.kt` | `label`, `icon`, `route` |

**[CONFIRMED]** Токены в UI-состоянии не хранятся (`AuthUiState` содержит только производный `userEmail`).

## 8. Сетевые DTO (кратко)

Полное сопоставление с сервером — [05-api.md](05-api.md) и [19-server-client-consistency.md](19-server-client-consistency.md).

| DTO | Файл | Поля (тип) |
| --- | --- | --- |
| `AuthRequest` (Java) | `api/models/AuthRequest.java` | `email`, `password` (private, сериализуются Gson через reflection) |
| `LoginResponseDto` | `api/models/LoginResponseDto.kt` | `accessToken: String?`, `refreshToken: String?` |
| `RefreshTokenRequest` (Java) | `api/models/RefreshTokenRequest.java` | `refreshToken` |
| `RefreshResponseDto` | `api/models/RefreshResponseDto.kt` | `accessToken: String?` |
| `LogoutRequest` (Java) | `api/models/LogoutRequest.java` | `refreshToken` |
| `TestResponse` | `api/models/TestResponse.kt` | `message: String` |
| `FolderDto` | `api/models/FolderDto.kt` | `id: Long`, `parentId: Long?`, `name: String`, `folderType: String`, `createdAt: String?`, `updatedAt: String?` |
| `ChecksumExistsRequest` | `api/models/ChecksumExistsRequest.kt` | `folderId: Long`, `checksums: List<String>` |
| `ChecksumExistsResponse` | `api/models/ChecksumExistsResponse.kt` | `existing: List<String> = []`, `missing: List<String> = []` |
| `FileItemDto` | `api/models/FileItemDto.kt` | `id: Long?` + 10 nullable-полей; `metadata: Map<String, Any?>?`; реально используется только `id` |
| `ErrorResponseDto` | `api/models/ErrorResponseDto.kt` | `id?`, `code?`, `message?`, `fieldErrors: Map<String,String>?` |
| регистрация / logout | `Map<String, String>` | `{"message": ...}` |

## 9. Сводная классификация полей

| Категория | Поля `MediaFile` | Кто владеет истиной |
| --- | --- | --- |
| LOCAL | `mediaStoreId`, `uri`, `displayName`, `relativePath`, `mimeType`, `size`, `createdAt`, `lastModified` | MediaStore; перезаписываются каждым сканом |
| SERVER | `serverFileId` | сервер; заполняется только из ответа upload |
| DERIVED | `checksum` | клиент; пересчитывается только после сброса по изменению `size`/`lastModified` |
| SYNC_STATE | `status`, `failureReason`, `retryCount`, `lastFailureAt` | клиент (Room) |

**Единицы и форматы [CONFIRMED]:** время — epoch milliseconds (`Long`); checksum — 64 символа lowercase hex (`ChecksumUtil.kt:24`); enum-ы — строковые имена в Room.
