# 19. Server ↔ Android consistency

## Основание сравнения

[CONFIRMED] Android: HEAD2b9ab698c2068d1e20eeb53aa7547557c3e17b54, 2026-09-11. [DOCUMENTED] Server handoff: HEAD84f7a547aabceac0849baef520605dac7111e797, 2026-09-10; это описание server source, не live deployment. Source-of-truth клиента остаётся Android; server facts ниже не объявляются повторно проверенными server source.

Использованы: [01 API](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/01-api-contract-effective.md>), [02 auth](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/02-auth-contract.md>), [03 errors](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/03-error-contract.md>), [04 data](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/04-media-data-contract.md>), [05 sync/upload](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/05-sync-upload-contract.md>), [06 capabilities](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/06-server-capabilities.md>), [07 differences](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/07-api-contract-differences.md>), [08 questions](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/08-client-relevant-open-questions.md>). Exact api-contract.md не найден; исторические планы и README contracts не заменяют effective handoff.

## API

[CONFIRMED] Все10 объявленных клиентских calls совпадают по method/path с effective API. Детальная таблица request/response/caller/status/error находится в [05-api.md](<C:/projects/Photocloudclient/docs/audit/android/05-api.md>). Register201 и остальные200 приняты через isSuccessful; aliases/планы не вызываются.

| Android call | Server contract | Match | Difference |
| --- | --- | --- | --- |
| AuthService.register | POST /auth/register JSON email/password→201 message | MATCH | Input validation только server; confirm отсутствует |
| AuthService.login | POST /auth/login→200 access+refresh | MATCH | Правильные nullable fields + explicit nonblank validation |
| AuthService.refreshToken | Public POST /auth/refresh-token→access only | MATCH | Корректное сохранение old refresh |
| AuthService.logout | Protected POST /auth/logout body refresh→message | MATCH/PARTIAL | Local clear зависит от получения response |
| TestService.testServer | GET /test public→message | MATCH | Mandatory gate Worker |
| TestService.testAuth | GET /test/auth protected→message | MATCH | Init result ignored |
| FolderService.getRoot | GET /folders/root→FolderDto | MATCH | Root write side effect, CAMERA не гарантирована |
| FolderService.getChildren | GET /folders/{id}/children→array | MATCH | first folderType CAMERA |
| FileService.checksumsExist | POST /files/checksums/exists folder+≤500 hashes | MATCH | existing→SYNCED without file ID |
| FileService.uploadFile | POST /files/upload multipart file/optional folderId→FileItemDto | MATCH/PARTIAL | Проверяет id, не authoritative fields |

[CONFIRMED] Prefix всех строк /api/v1. Объявления: [AuthService.java:15](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java:15>), [TestService.java:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/TestService.java:7>), [FolderService.java:10](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/FolderService.java:10>), [FileService.java:14](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/FileService.java:14>).

[DOCUMENTED] Полный remainder effective API: **26 server operations без Android call**. Это server-only scope, не автоматически integration defect.

| Server operation | Server state | Android |
| --- | --- | --- |
| GET /auth/register/confirm | IMPLEMENTED text200 | NOT FOUND |
| POST /auth/register/resend | STUB501 | NOT FOUND |
| POST /auth/logout-all | IMPLEMENTED | NOT FOUND |
| POST /auth/logout-others | IMPLEMENTED | NOT FOUND |
| POST /auth/password/reset/request | IMPLEMENTED query email | NOT FOUND |
| POST /auth/password/reset/confirm | IMPLEMENTED JSON | NOT FOUND |
| POST /auth/password/reset/resend | STUB501 | NOT FOUND |
| GET /auth/password/reset/page | STUB501 | NOT FOUND |
| POST /auth/password/reset/page | STUB501 | NOT FOUND |
| GET /profile | IMPLEMENTED | NOT FOUND; local stub не API |
| PATCH /profile | STUB501 | NOT FOUND |
| GET /profile/settings | STUB501 | NOT FOUND |
| PATCH /profile/settings | STUB501 | NOT FOUND |
| POST /folders | IMPLEMENTED | NOT FOUND |
| PATCH /folders/{id} | IMPLEMENTED | NOT FOUND |
| POST /folders/{id}/move | IMPLEMENTED | NOT FOUND |
| DELETE /folders/{id} | IMPLEMENTED empty USER only | NOT FOUND |
| POST /files | IMPLEMENTED alias upload | NOT USED; /files/upload используется |
| GET /files | IMPLEMENTED page,size,folderId | NOT FOUND |
| GET /files/{id} | IMPLEMENTED metadata | NOT FOUND |
| GET /files/{id}/download | IMPLEMENTED bytes | NOT FOUND |
| PATCH /files/{id} | IMPLEMENTED originalName | NOT FOUND |
| POST /files/{id}/move | IMPLEMENTED | NOT FOUND |
| POST /files/{id}/copy | IMPLEMENTED | NOT FOUND |
| DELETE /files/{id} | IMPLEMENTED hard delete | NOT FOUND |
| GET /files/checksums | IMPLEMENTED global user list | NOT FOUND |

## DTO: serialization и nullability

[CONFIRMED] Gson без @SerializedName/custom adapters/naming policy: wire keys совпадают с именами полей, Java private поля сериализуются библиотекой. Kotlin nullability не является runtime JSON schema validation. Nullable/default поля перечислены ниже; unknown fields не используются. На server boundary MediaFileStatus/FailureReason не сериализуются. [DOCUMENTED] Server enums строковые, IDs int64 JSON numbers, dates ISO LocalDateTime без offset. В таблицах Server type/nullability взяты из04 handoff; Android из перечисленных исходников.

### Requests, auth и simple responses

| DTO / semantic field | Server field/type/nullability | Android field/type/nullability/default | Match |
| --- | --- | --- | --- |
| RegisterRequest/LoginRequest.email | email:string, required nonblank valid email | AuthRequest.email:Java String private, no null annotation/default, constructor argument | MATCH wire; validation не локальная |
| RegisterRequest/LoginRequest.password | password:string, required4..20 nonblank | AuthRequest.password:Java String, constructor argument | MATCH wire; bounds не проверяются UI |
| RefreshRequest.refreshToken | refreshToken:string required nonblank | RefreshTokenRequest.refreshToken:Java String, constructor | MATCH |
| LogoutRequest.refreshToken | refreshToken:string required nonblank, own principal | LogoutRequest.refreshToken:Java String, constructor | MATCH wire; account consistency не enforced локально |
| LoginResponse.accessToken | accessToken:string nonnull при200 | LoginResponseDto.accessToken:String?=null | MATCH, explicit nonblank validation |
| LoginResponse.refreshToken | refreshToken:string nonnull при200 | LoginResponseDto.refreshToken:String?=null | MATCH, explicit nonblank validation |
| RefreshResponse.accessToken | accessToken:string nonnull при200 | RefreshResponseDto.accessToken:String?=null | MATCH; null rejects, blank не rejects |
| RefreshResponse.refreshToken | Не возвращается | Нет поля; old Tokens.refreshToken сохраняется | MATCH no rotation |
| Public/protected ping.message | message:string | TestResponse.message:String, required constructor, no default | MATCH |
| Register/logout map.message | message:string | Map<String,String>, register читает message/fallback, logout body не использует | MATCH |
| ChecksumExistsRequest.folderId | folderId:int64 required | folderId:Long nonnull, no default | MATCH |
| ChecksumExistsRequest.checksums | checksums:string[] required nonempty, each64hex,max500 raw | checksums:List<String> nonnull, no default | MATCH generated SHA256, batches≤500 |

Источники: [AuthRequest.java:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/AuthRequest.java:3>), [RefreshTokenRequest.java:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/RefreshTokenRequest.java:3>), [LogoutRequest.java:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/LogoutRequest.java:3>), [LoginResponseDto.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/LoginResponseDto.kt:3>), [RefreshResponseDto.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/RefreshResponseDto.kt:3>), [TestResponse.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/TestResponse.kt:3>), [ChecksumExistsRequest.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/ChecksumExistsRequest.kt:3>).

### FolderDto и checksum response

| DTO.field | Server | Android | Match |
| --- | --- | --- | --- |
| FolderDto.id | id:int64, nonnull | id:Long, no default | MATCH; not same space as FileItem.id |
| FolderDto.parentId | parentId:int64 nullable ROOT | parentId:Long?, no default | MATCH |
| FolderDto.name | name:string nonnull | name:String, no default | MATCH; selection не по имени |
| FolderDto.folderType | folderType:enum ROOT/CAMERA/FILES/USER | folderType:String, no default | MATCH strings; unknown string не enum crash, CAMERA exact compare |
| FolderDto.createdAt | local datetime string, normally nonnull | createdAt:String?=null | MATCH wire; ignored |
| FolderDto.updatedAt | local datetime string, normally nonnull | updatedAt:String?=null | MATCH wire; ignored |
| ChecksumExistsResponse.existing | string[],nonnull,[] allowed | existing:List<String>=emptyList() | MATCH; explicit JSON null не защищён Kotlin declaration |
| ChecksumExistsResponse.missing | string[],nonnull,[] allowed | missing:List<String>=emptyList() | MATCH; file IDs не ожидаются |

Источники: [FolderDto.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/FolderDto.kt:3>), [ChecksumExistsResponse.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/ChecksumExistsResponse.kt:3>), [FolderRepository.kt:50](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FolderRepository.kt:50>), [ChecksumPrecheckRepository.kt:107](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:107>). [PARTIAL] Non-null response declaration не защищает от explicit malformed null; exceptions ловятся стадией и становятся retry. Missing/default behavior не считается заменой validation.

### FileItemDto: все поля

| DTO.field / semantics | Server field/type/nullability | Android field/type/nullability/default | Match |
| --- | --- | --- | --- |
| Logical file identity | id:int64 nonnull | id:Long?, без constructor default | MATCH; единственное используемое поле, >0 guard |
| Target folder | folderId:int64 nonnull | folderId:Long?=null | MATCH wire; actual target не сверяется |
| Normalized logical filename | originalFilename:string nonnull | originalFilename:String?=null | MATCH wire; local displayName не обновляется |
| Detected MIME | mimeType:string nonnull | mimeType:String?=null | MATCH wire; local MIME остаётся MediaStore |
| Actual byte length | size:int64 nonnull | size:Long?=null | MATCH wire; contentLength/stored size не сверяются |
| Server SHA256 | checksum:string nonnull hex64 | checksum:String?=null | MATCH wire; integrity comparison отсутствует |
| FileType | fileType:enum IMAGE/VIDEO/AUDIO/DOCUMENT/ARCHIVE/OTHER | fileType:String?=null | MATCH tolerant string; нет typed handling |
| EXIF/server fallback date | capturedAt:LocalDateTime string nonnull | capturedAt:String?=null | MATCH opaque string, timezone не интерпретируется |
| Server upload date | uploadedAt:LocalDateTime string nonnull | uploadedAt:String?=null | MATCH opaque string, не persist |
| Reserved delete timestamp | deletedAt:LocalDateTime string nullable | deletedAt:String?=null | MATCH; не используется как tombstone |
| Extracted metadata | metadata:FileMetadataDto nullable | metadata:Map<String,Any?>?=null | PARTIAL loose representation, не typed DTO |

Источник: [FileItemDto.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/FileItemDto.kt:8>), consumer [FileUploadRepository.kt:111](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:111>). [CONFIRMED] Upload не проверяет checksum/size/MIME/folder/name/date даже если они присутствуют; пустой/malformed body или id<=0 возвращают pending. [RISK] Нельзя считать совпадение wire fields доказательством metadata consistency Room/server.

### FileMetadataDto внутри metadata map

[CONFIRMED] Отдельного Android FileMetadataDto нет. Все keys поглощаются Map<String,Any?> и не читаются. [PARTIAL] Числовые значения нетипизированы: нет гарантии Int/decimal precision по Android model; не вводится ложное соответствие server BigDecimal→Kotlin Double field.

| DTO.field | Server wire type/nullability | Android key/type/default | Match |
| --- | --- | --- | --- |
| width | int32 nullable | metadata["width"]:Any?, absent allowed | PARTIAL untyped/unused |
| height | int32 nullable | metadata["height"]:Any? | PARTIAL |
| durationSec | int32 nullable, extractor не заполняет | metadata["durationSec"]:Any? | PARTIAL, video support из поля не следует |
| cameraMake | string nullable | metadata["cameraMake"]:Any? | PARTIAL |
| cameraModel | string nullable | metadata["cameraModel"]:Any? | PARTIAL |
| lensModel | string nullable | metadata["lensModel"]:Any? | PARTIAL |
| exposureTime | string nullable | metadata["exposureTime"]:Any? | PARTIAL |
| fNumber | decimal number nullable, explicit server wire case | metadata["fNumber"]:Any? | PARTIAL |
| iso | int32 nullable | metadata["iso"]:Any? | PARTIAL |
| focalLength | decimal number nullable | metadata["focalLength"]:Any? | PARTIAL |
| latitude | decimal number nullable | metadata["latitude"]:Any? | PARTIAL |
| longitude | decimal number nullable | metadata["longitude"]:Any? | PARTIAL |

### ErrorResponse и multipart

| DTO/field | Server | Android | Match |
| --- | --- | --- | --- |
| ErrorResponse.id | UUID string nonnull controlled error | ErrorResponseDto.id:String?=null | MATCH wire, discarded |
| ErrorResponse.code | machine enum string nonnull | code:String?=null | MATCH wire, не machine retry discriminator |
| ErrorResponse.message | human string normally nonnull | message:String?=null | MATCH |
| ErrorResponse.fieldErrors | map string→string nullable | fieldErrors:Map<String,String>?=null | MATCH; joined message |
| file multipart | Required nonempty bytes | MultipartBody.Part name=file, ContentUriRequestBody | MATCH shape; no pre-send size/zero validation |
| file.filename | string possibly absent, server sanitize | displayName or file_ID | MATCH input, output may differ |
| file Content-Type | Non-authoritative header | MediaStore mimeType via toMediaTypeOrNull | MATCH header; server Tika authoritative |
| folderId multipart | Optional int64 param/part | RequestBody? text/plain decimal Long, omitted if null | MATCH |
| checksum/date/status/localUri parts | Не accepted upload fields | Не отправляются | MATCH |

Источники: [ErrorResponseDto.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/ErrorResponseDto.kt:3>), [ApiErrorParser.kt:12](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt:12>), [FileUploadRepository.kt:164](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:164>), [ContentUriRequestBody.kt:23](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt:23>).

[CONFIRMED] DTO без Android counterpart: UserDto, PageResponse<FileItemDto>, FileChecksumDto, PasswordResetConfirmRequest, CreateFolderRequest, RenameFolderRequest, MoveFolderRequest, RenameFileRequest, MoveFileRequest, CopyFileRequest. Они не пересекают текущий клиент API. Это покрывает остальные server DTO; standalone FileMetadataDto представлен generic map.

## Authentication

[CONFIRMED] Login/refresh plain requests, protected Bearer exact prefix, current refresh сохраняется после access renewal; wire match. Guard responseCount>=2 и synchronized+token comparison ограничивают401 loops в одном auth client. [TokenAuthenticator.kt:34](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt:34>).

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [INCONSISTENCY] Refresh failure | Любой non2xx clear tokens |401 invalid/403 revoked отличаются от transient failure | Forced logout при temporary outage | HIGH |
| [PARTIAL] Logout | HTTP response clear, IOException before clear retains | Remote revoke подтверждается server response; offline clear policy server не задаёт | Session может остаться локально; нет универсального logout success | HIGH |
| [RISK] Session switch | Shared tokens/DB, refresh без общего session lock | Все protected operations scoped current principal | Старые rows/credentials привязываются к новому scope | CRITICAL |

## Error handling

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [INCONSISTENCY] Retry HTTP | Только400/409/413 permanent, остальные retry |403/404/deterministic500 не обязательно transient | Бесконечные повторы без исправления условия | HIGH |
| [PARTIAL] Error envelope | Извлекает string, id/code теряются | Controlled id/code/fieldErrors + nonuniform framework bodies | Нет correlation/structured recovery | MEDIUM |
| [CONFIRMED] Duplicate upload |2xx id>0 success | Duplicate200 old DTO, не409 | MATCH;409 не ошибочно считается successful duplicate | LOW |

## Upload

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [PARTIAL] Size limit | Нет предварительного100MiB guard |100MiB service,110MB container limit | Waste traffic/terminal413 | MEDIUM |
| [RISK] Target fallback | Lookup Error→folderId null | Null→CAMERA только Tika IMAGE/VIDEO, иначе FILES | MIME disagreement может менять target после CAMERA precheck | HIGH |
| [PARTIAL] Confirmation | Читает только id | Server response authoritative bytes/type/name/target metadata | SYNCED не подтверждает соответствие local hash/target | HIGH |
| [CONFIRMED] No CAMERA bootstrap | All ready→pending, upload null | System CAMERA lazy default upload | MATCH first-upload path | LOW |

## Duplicate detection

[CONFIRMED] Для existing CAMERA клиент группирует hashes→local IDs, sends≤500 distinct lower strings, получает partition. Это соответствует [DOCUMENTED] user+folder+SHA256. Несколько local identical photos могут стать SYNCED с null IDs после precheck либо получить один server ID через duplicate upload; local duplicate rows не удаляются. File name/date не участвуют в precheck. [ChecksumPrecheckRepository.kt:90](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:90>).

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [PARTIAL] Existing identity | SYNCED без server ID | Exists возвращает только hashes, list/upload может вернуть ID | Нет дальнейшей адресации remote object | MEDIUM |
| [PARTIAL] Physical bytes availability | Existing считается достаточным | Exists проверяет logical record, не bytes | SYNCED возможен при потерянном server file | HIGH |
| [PARTIAL] Duplicate name | Сохраняет local name, response name ignored | Duplicate возвращает старое normalized name | UI имя и remote name расходятся | LOW |

## Sync semantics

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [PARTIAL] Remote changes | SYNCED больше не проверяет | Server позволяет delete/move, delta/tombstones нет | Локальный статус устаревает бессрочно | HIGH |
| [CONFIRMED] Local deletion | DELETE Room, no API | Server local deletion не знает | Backup copy остаётся; продуктовая политика не установлена | MEDIUM |
| [RISK] Cold execution | Worker не читает saved URL | Server доступен только по настроенному адресу | Auto-sync может не начать API после reboot | HIGH |
| [CONFIRMED] Protocol scope | Precheck+full single upload | Full sync sessions/resume не реализованы server | MATCH ограниченный протокол; название sync не расширяет его | LOW |

## IDs

[CONFIRMED] MediaStoreId — local Long PK; serverFileId — logical FileItem.id nullable Long; folderId — transient Long; account ID/StoredObject ID отсутствуют. Нет смешения fileId с folderId в request code. [DOCUMENTED] Ошибка такого смешения в historical server smoke doc из07 не относится к Android.

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [RISK] ID namespace | DB serverFileId без server/account/folder scope | FileItem принадлежит конкретному principal/folder/server | Старые IDs/status после switch становятся ложной identity | CRITICAL |
| [PARTIAL] ID from precheck | Не получает/не восстанавливает | Exists не выдаёт IDs | Это ограничение общего flow, не DTO mismatch | MEDIUM |

## Checksums

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [CONFIRMED] Algorithm/format | SHA256 stream hex64 lowercase | SHA256 actual bytes hex64 lowercase | MATCH | LOW |
| [CONFIRMED] Batch |<=500 rows, then dedup |<=500 raw checksums | MATCH | LOW |
| [RISK] Checksum snapshot | Hash до upload, response hash ignored | Server computes actual upload bytes independently | Локальный hash может не соответствовать remote object | HIGH |
| [PARTIAL] Change detector | size OR mtime | Bytes identity SHA256 | Same-size/mtime edits могут не запустить hash | HIGH |

## Retry

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [PARTIAL] Lost response | PENDING_UPLOAD then full retransmission | Same bytes/user/folder repeat200 existing | Обычно recovery возможен, но client не фиксирует bytes/target snapshot | HIGH |
| [CONFIRMED] Permanent upload |400/409/413 FAILED | Bad request/conflict/size не решаются blind replay | MATCH coarse classification | LOW |
| [PARTIAL] Failed item recovery | CHECKSUM_IO only,max3 failed attempts | Server не определяет local failure lifecycle | Не server mismatch, ограничение client recovery | MEDIUM |
| [INCONSISTENCY] Unauthorized work | Retry после clear/no tokens |401 требует пригодной session | WM retries не дают новых credentials | HIGH |

## Media metadata

| Area | Client does | Server expects / documented semantics | Impact | Severity |
| --- | --- | --- | --- | --- |
| [CONFIRMED] Time encoding | Local createdAt/mtime epoch ms; server strings opaque | capturedAt/uploadedAt local datetime без offset | Wire mismatch нет, unified chronology не реализована | MEDIUM |
| [PARTIAL] Metadata authority | Local MIME/size/name остаются в Room | Server Tika/actual bytes/sanitized name authoritative для remote | Нельзя считать local row зеркалом FileItemDto | MEDIUM |
| [CONFIRMED] Client metadata upload | Не отправляет fields кроме file/folder | Server checksum/date/status overrides не принимает | MATCH | LOW |
| [PARTIAL] Video | Images-only query/guard | Server general upload/download supports VIDEO | SERVER ONLY media type | MEDIUM |

## Feature matrix server ↔ client

[DOCUMENTED] Server column —06 capabilities; Android column — исходники/15. Integration status MATCH относится только к указанному scope.

| Feature | Server | Android | Integration status |
| --- | --- | --- | --- |
| Login | IMPLEMENTED | IMPLEMENTED | MATCH |
| Register request | IMPLEMENTED | IMPLEMENTED | MATCH |
| Email confirm | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Resend | STUB | NOT FOUND | SERVER STUB |
| Refresh | IMPLEMENTED | PARTIAL | MATCH wire / PARTIAL recovery |
| Logout current | IMPLEMENTED | PARTIAL | MATCH request / PARTIAL local lifecycle |
| Logout all/others | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Password reset JSON | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Browser reset page | STUB | NOT FOUND | INCOMPLETE SERVER FLOW |
| Profile read | IMPLEMENTED | STUB destination | SERVER ONLY API |
| Profile edit/settings | STUB | STUB screens | NO WORKING INTEGRATION |
| Permissions/MediaStore/Room | NOT APPLICABLE | PARTIAL / IMPLEMENTED | CLIENT ONLY |
| SHA256 | IMPLEMENTED | IMPLEMENTED | MATCH algorithm |
| Exists precheck | IMPLEMENTED | IMPLEMENTED | MATCH scope/batch |
| Upload explicit/legacy aliases | IMPLEMENTED | IMPLEMENTED explicit | MATCH |
| Duplicate upload | IMPLEMENTED | IMPLEMENTED | MATCH200 |
| Local modifications | No overwrite/version API | PARTIAL new upload | PARTIAL |
| Automatic retry | Idempotent upload, no client scheduler | PARTIAL | CLIENT POLICY / LIMITED SEMANTICS |
| Manual retry | No dedicated endpoint needed | NOT FOUND | CLIENT GAP |
| Full sync/delta/tombstones | NOT FOUND | NOT FOUND | NOT IMPLEMENTED |
| Background execution | NOT APPLICABLE | PARTIAL | CLIENT ONLY |
| File list/metadata/download | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| File rename/move/copy/delete | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Local deletion propagation | No local events | NOT FOUND | NOT IMPLEMENTED |
| Root/children | IMPLEMENTED | IMPLEMENTED internal | MATCH |
| Folder CRUD | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Images | IMPLEMENTED | IMPLEMENTED DCIM-only | PARTIAL scope |
| Video | IMPLEMENTED upload/download | NOT FOUND | SERVER ONLY |
| Video duration processing | NOT FOUND (field exists) | NOT FOUND | NOT IMPLEMENTED |
| Audio/docs/archive/other | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Thumbnails/albums/share | NOT FOUND | NOT FOUND | NOT IMPLEMENTED |
| Chunk/resume upload | NOT FOUND | NOT FOUND | NOT IMPLEMENTED |
| Quota remaining/versioning/trash | NOT FOUND | NOT FOUND | NOT IMPLEMENTED |
| Client status/failure display | No wire states | IMPLEMENTED/PARTIAL | CLIENT ONLY |
| Device registration/session list | NOT FOUND | NOT FOUND | NOT IMPLEMENTED |

## Восстановленный протокол в10 шагах

1. [CONFIRMED] Trigger observer/periodic и network gate; scan Images DCIM.
2. [CONFIRMED] Snapshot→Room insert/update, size/mtime reset, stale delete.
3. [CONFIRMED] PENDING→HASHING→SHA256→CHECKSUM_READY; eligible old CHECKSUM_IO restored до scan.
4. [CONFIRMED] Root/children resolves CAMERA; existing folder→checksums/exists.
5. [CONFIRMED] existing→SYNCED; missing/no CAMERA→PENDING_UPLOAD.
6. [CONFIRMED] Sequential multipart upload, file URI reopened, folder resolved again.
7. [CONFIRMED]2xx id>0→SYNCED/serverFileId; response hash/metadata ignored.
8. [CONFIRMED] Local/permanent failure→FAILED+reason/count/time; transient→pending+stop.
9. [CONFIRMED] Whole-run WM retry + limited future CHECKSUM_IO restore; manual retry нет.
10. [PARTIAL] Room state recovery HASHING/UPLOADING на следующем stage; cold process URL не восстановлен Worker.

[DOCUMENTED] Это согласуется с server05 в части precheck + single upload, ограниченной duplicate идемпотентности и отсутствия wire sync states. [INCONSISTENCY] Свойства «полная синхронизация», «файл точно доступен на сервере», «любая500 transient» из этого протокола не следуют.

## Закрытие вопросов server handoff08

| Вопрос из handoff | Установленный Android As-Is |
| --- | --- |
| Bootstrap CAMERA/cache | Missing→skip exists/upload null; cache нет |
| Remote identity / duplicate old name | Long? serverFileId only; precheck ID missing; name ignored |
|409 copy/name /500 move | Copy/move отсутствуют; upload409 permanent; upload500 retry |
| Existing без bytes | Repair/signal отсутствует |
| Lost upload response | Retransmit whole URI; no uncertain-outcome state |
| Local file modified/deleted after scan | Response hash не сверяет; preopen failure или next scan delete |
| Local delete / server delete / multi-device policy | No propagation/reconciliation/device identity |
| Logout/account switch/expired access | Shared index; logout auth refresh possible; clear exceptions см.06 |
| Immediate ban/reset/logout | Никакой клиентской гарантии, server semantics не расширены |
| Expired Bearer on refresh / refresh storage | Plain client; old refresh retained — оба MATCH |
| Reset deep link / capturedAt / required metadata | Reset UI нет; dates strings и metadata ignored |
| Non-JSON / limits / aliases | Parser raw fallback;500 hashes;413 terminal; explicit upload only |
| Copy default / Unicode name / system folder type restrictions | Copy/download UI нет; upload response name ignored; image local guard, server may detect otherwise |
| Client metadata/status fields | Не передаются |

[INFERRED] Оставшиеся вопросы о требуемом поведении/политике находятся в17. Server07 использован для отделения исторических планов/ошибочных smoke примеров от effective routes: Android не приписаны отсутствие уже существующего folder API, refresh rotation,500 move bug в неиспользуемом endpoint или несуществующие NEW/CHECK/UPLOAD/LIMIT wire states.

