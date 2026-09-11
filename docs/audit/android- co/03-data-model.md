# 03. Data model

## MediaFile

[CONFIRMED] Единственная entity, одновременно local queue item и UI item. Поля из [MediaFile.kt:6](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt:6>). «Нет default» означает обязательный аргумент Kotlin constructor; SQL defaults рассматриваются отдельно в04. Все поля val, изменения выполняются SQL.

| Field | Kotlin type | Nullable | Kotlin default | Category | Смысл / lifecycle |
| --- | --- | --- | --- | --- | --- |
| mediaStoreId | Long | Нет | Нет | LOCAL | PK, _ID MediaStore, не генерируется Room |
| serverFileId | Long? | Да | null | SERVER | FileItem.id при upload; existing precheck оставляет null; file change сбрасывает |
| uri | String | Нет | Нет | LOCAL | content URI external images с ID; обновляется scan |
| displayName | String | Нет | Нет | LOCAL | DISPLAY_NAME, fallback image_ID; multipart filename |
| relativePath | String? | Да | Нет | LOCAL | RELATIVE_PATH; фильтр DCIM; используется в UI |
| mimeType | String | Нет | Нет | LOCAL | MediaStore MIME/fallback image/*; upload image/ guard |
| size | Long | Нет | Нет | LOCAL | Bytes из MediaStore, null column→0; detect change + contentLength |
| createdAt | Long | Нет | Нет | DERIVED | Epoch ms: taken→modified→added→now; sorting |
| lastModified | Long | Нет | Нет | DERIVED | DATE_MODIFIED seconds×1000, fallback createdAt |
| checksum | String? | Да | Нет | DERIVED | SHA-256 по stream, lowercase hex64; null при discovery/change |
| status | MediaFileStatus | Нет | PENDING | SYNC_STATE | 8 enum states; подробности09 |
| failureReason | FailureReason? | Да | null | SYNC_STATE | Код last FAILED; clear при retry/change/checksum/upload success |
| retryCount | Int | Нет | 0 | SYNC_STATE | Число markFailed, не количество HTTP/WM retry |
| lastFailureAt | Long? | Да | null | SYNC_STATE | Epoch ms последнего FAILED; cooldown |

[CONFIRMED] user/account ID, server URL, folderId, checksum generation, scannedAt, uploadedAt, retryAt, last HTTP error body, upload session/idempotency key, byte progress, localDeleted flag отсутствуют. serverFileId не FK и не unique. Local URI/path не серверные поля. [MediaFileDao.kt:49](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:49>), [FileUploadRepository.kt:111](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:111>).

[CONFIRMED] Lifecycle: scan создаёт PENDING; SHA-256 формирует CHECKSUM_READY; pre-check→SYNCED или PENDING_UPLOAD; upload→SYNCED/server ID; scan content change очищает identity/hash/failure; stale detection физически удаляет entity. Размер/mtime являются эвристикой изменения, не криптографической идентичностью.

## Scan snapshot и outcomes

[CONFIRMED] [MediaStoreImage.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreImage.kt:8>): MediaStoreImage содержит mediaStoreId Long, uri String, displayName String, relativePath String?, mimeType String, size Long, createdAt Long, lastModified Long; default нет; transient snapshot, все категории LOCAL/DERIVED как выше. Содержимого bytes в модели нет.

| Model | Все поля / варианты | Default / lifetime |
| --- | --- | --- |
| [CONFIRMED] ScanResult | scanned:Int, insertedOrUpdated:Int, deletedStale:Int | Все required; один scan; insertedOrUpdated=images.size, не точные insert/update counts |
| [CONFIRMED] ScanOutcome | Success(result:ScanResult), PermissionDenied object, Error(message:String) | Repository→VM/Worker |
| [CONFIRMED] MediaStoreScanOutcome | Success(images:List<MediaStoreImage>), PermissionDenied, Error(message:String) | Scanner→MediaFileRepository |
| [CONFIRMED] ChecksumResult | processed:Int, succeeded:Int, failed:Int | Required; один hash run |
| [CONFIRMED] ChecksumOutcome | Success(result), PermissionDenied, Error(message:String) | Hash stage→Worker |
| [CONFIRMED] ChecksumPrecheckResult | checked:Int, existing:Int, pendingUpload:Int, unchanged:Int | Required; локальные item counts, не число distinct hashes |
| [CONFIRMED] ChecksumPrecheckOutcome | Success(result), Error(message:String) | Precheck→Worker |
| [CONFIRMED] CameraFolderOutcome | Success(folderId:Long), NotCreatedYet object, Error(message:String) | transient root/children lookup, не cache |
| [CONFIRMED] UploadResult | attempted:Int, succeeded:Int, failed:Int, leftPending:Int | Required; leftPending считает встретившую transient ошибку запись, не всю DB queue |
| [CONFIRMED] UploadOutcome | Success(result), Error(message:String,result:UploadResult?=null) | Upload→Worker |
| [CONFIRMED] SyncResult | scan:ScanResult?=null, checksum:ChecksumResult?=null, precheck:ChecksumPrecheckResult?=null, upload:UploadResult?=null | Накопление в Worker; в Room не хранится |
| [CONFIRMED] SyncStatusRecord | status:SyncStatus, timestamp:Long | Required; now epoch ms; in-memory latest outcome |

Источники: [ScanResult.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ScanResult.kt:8>), [ChecksumResult.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumResult.kt:7>), [ChecksumPrecheckResult.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckResult.kt:8>), [FolderRepository.kt:14](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FolderRepository.kt:14>), [UploadResult.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/UploadResult.kt:7>), [SyncResult.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncResult.kt:7>), [SyncStatus.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncStatus.kt:7>).

## Enums

[CONFIRMED] MediaFileStatus: PENDING, HASHING, CHECKSUM_READY, PENDING_UPLOAD, UPLOADING, SYNCED, FAILED, LOCAL_DELETED. FailureReason: CHECKSUM_IO, FILE_NOT_FOUND, PERMISSION, NOT_IMAGE, HTTP_400, HTTP_409, HTTP_413, UNKNOWN; isRetryable=true только CHECKSUM_IO. Enum serialization Room — name/valueOf; на сервер они не отправляются. LOCAL_DELETED объявлен/отображается, записывающий переход не найден.

[CONFIRMED] SyncStatus: SUCCESS, SERVER_UNAVAILABLE, ERROR, RETRY_SCHEDULED; это результат попытки pipeline, не статус отдельного файла. ConnectionStatus: NONE, SUCCESS, ERROR, LOADING, каждый с Compose Color и title; presentation enum, не HTTP model. [MediaFileStatus.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt:3>), [FailureReason.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FailureReason.kt:7>), [SyncStatus.kt:12](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncStatus.kt:12>), [ConnectionStatus.kt:5](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/ConnectionStatus.kt:5>).

## UI и session models

[CONFIRMED] Все UI models transient, nullable поля имеют null default, коллекции emptyList:

| Model | Поля и defaults |
| --- | --- |
| AuthUiState | email:String="", password:String="", status:ConnectionStatus=NONE, message:String?=null, isLoggedIn:Boolean=false, userEmail:String?=null, isVerifying:Boolean=false |
| NetworkUiState | ip:String="", port:String="", isLoading:Boolean=false, message:String?=null, connectionStatus:ConnectionStatus=NONE |
| FilesUiState | files:List<MediaFile>=emptyList(), isScanning:Boolean=false, lastScanResult:ScanResult?=null, permissionDenied:Boolean=false, errorMessage:String?=null, isSyncing:Boolean=false, lastSyncStatus:SyncStatusRecord?=null |
| Tokens | accessToken:String, refreshToken:String; required/non-null; persistent через TokenStorage |
| BottomNavItem | label:String, icon:ImageVector, route:String; required/non-null; навигационная конфигурация |

Источники: [AuthUiState.kt:5](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthUiState.kt:5>), [NetworkUiState.kt:5](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkUiState.kt:5>), [FilesUiState.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesUiState.kt:7>), [Tokens.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/Tokens.kt:3>), [BottomNavItem.kt:5](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/BottomNavItem.kt:5>).

[CONFIRMED] API DTO inventory: AuthRequest, RefreshTokenRequest, LogoutRequest (Java private strings без null annotations); LoginResponseDto, RefreshResponseDto, TestResponse, ChecksumExistsRequest/Response, FileItemDto, FolderDto, ErrorResponseDto; register/logout Map<String,String>. Полное перечисление их wire fields/type/nullability/serialization/defaults и серверного соответствия находится в [19-server-client-consistency.md](<C:/projects/Photocloudclient/docs/audit/android/19-server-client-consistency.md>). Эти DTO не являются Room entities; upload использует только FileItemDto.id.

