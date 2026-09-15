# Android capability matrix

ID закреплены за capability, а не номером endpoint. IMPLEMENTED описывает существующий механизм в обозначенном объёме; limitations указаны в Notes и профильных разделах. Отсутствие серверной возможности на Android само по себе не defect.

| ID | Feature | Status | Main mechanism | Notes |
| --- | --- | --- | --- | --- |
| AND-AUTH-001 | Регистрация request | IMPLEMENTED | AuthRepository.register | Ответ message; подтверждение вне Android |
| AND-AUTH-002 | Login | IMPLEMENTED | AuthRepository + AuthViewModel | Оба токена nonblank |
| AND-AUTH-003 | Encrypted token pair | IMPLEMENTED | EncryptedPrefsTokenStorage | Одна пара на установку |
| AND-AUTH-004 | Bearer | IMPLEMENTED | AuthInterceptor | Текущий access; без tokens request не блокируется |
| AND-AUTH-005 | Refresh и single replay | PARTIAL | TokenAuthenticator | Lock; non-2xx clear; нет session-wide lock |
| AND-AUTH-006 | Logout | PARTIAL | AuthRepository / coordinator | Clear после response; IO может сохранить пару |
| AND-AUTH-007 | Session verification | PARTIAL | verifySession / Test Auth | Init Result игнорируется |
| AND-AUTH-008 | Proactive expiry helper | UNUSED | JwtUtils.isExpired | Автоматического proactive refresh нет |
| AND-AUTH-009 | Email confirm/resend и password recovery UI | NOT PRESENT | — | Нет Android endpoints/screens |
| AND-AUTH-010 | Logout-all/others | NOT PRESENT | — | Только current refresh logout |
| AND-AUTH-011 | Session-expired navigation/notification | NOT PRESENT | — | Auth form через tokensFlow |
| AND-MEDIA-001 | Images discovery DCIM | IMPLEMENTED | MediaStoreRepository | Полный snapshot external Images |
| AND-MEDIA-002 | Local metadata и change detector | PARTIAL | MediaFileRepository / DAO | Size/mtime; не byte-generation guard |
| AND-MEDIA-003 | Local stale deletion | IMPLEMENTED | Snapshot IDs difference | DELETE только Room; visibility не physical deletion proof |
| AND-MEDIA-004 | Permissions | PARTIAL | Files launcher + guards | Нет selected-photo/revoke recovery flow |
| AND-MEDIA-005 | Video и media вне DCIM | NOT PRESENT | — | Нет соответствующего query/scope |
| AND-MEDIA-006 | Explicit original/GPS access | NOT PRESENT | — | Нет location permission/setRequireOriginal |
| AND-MEDIA-007 | Local original delete/edit | NOT PRESENT | — | MediaStore write/delete отсутствуют |
| AND-DATA-001 | Room persistence | IMPLEMENTED | DB3 / MediaFileDao | Одна entity,14 fields |
| AND-DATA-002 | Room migrations | PARTIAL | 1→2→3, destructive fallback | Runtime migration tests нет |
| AND-DATA-003 | Account/server/folder/device namespace | NOT PRESENT | — | MediaStore ID — единственный PK |
| AND-DATA-004 | LOCAL_DELETED status | UNUSED | Enum + UI formatter | Активного writer нет |
| AND-DATA-005 | Incremental scan helper | UNUSED | getLatestCreatedAt | Active scan полный |
| AND-SYNC-001 | SHA-256 | IMPLEMENTED | ChecksumRepository / ChecksumUtil | Buffer8192, batch100 |
| AND-SYNC-002 | CAMERA lookup/bootstrap | IMPLEMENTED | FolderRepository + pre-check/upload | Absence → omitted folder upload |
| AND-SYNC-003 | Folder checksum pre-check | IMPLEMENTED | ChecksumPrecheckRepository | <=500, lowercase/dedup, logical existing |
| AND-SYNC-004 | Single-file upload | IMPLEMENTED | FileUploadRepository | Sequential multipart; id>0 |
| AND-SYNC-005 | Duplicate200 handling | IMPLEMENTED | markUploaded | Новый/existing response одинаково успешны |
| AND-SYNC-006 | Server ID after upload | IMPLEMENTED | MediaFile.serverFileId | Записывается из positive response ID |
| AND-SYNC-007 | Server ID after pre-check | NOT PRESENT | — | Status-only, обычный ID null |
| AND-SYNC-008 | Remote hash/size/target reconciliation | NOT PRESENT | — | Response fields не сверяются |
| AND-SYNC-009 | Failure reason persistence | IMPLEMENTED | markFailed | 8 reasons; legacy nullable |
| AND-SYNC-010 | CHECKSUM_IO auto retry | PARTIAL | Eligible restore | Count<3, cooldown5min; concurrent restore не изолирован |
| AND-SYNC-011 | Transient/whole-stage retry | PARTIAL | WorkManager Result.retry | Нет лимита; head-of-line possible |
| AND-SYNC-012 | HASHING/UPLOADING recovery | IMPLEMENTED | Stage resetStatus | Только при достижении стадии |
| AND-SYNC-013 | Manual sync/retry/cancel | NOT PRESENT | — | Нет UI actions |
| AND-SYNC-014 | Server→device / remote mutation reconciliation | NOT PRESENT | — | SYNCED не перепроверяется |
| AND-SYNC-015 | Chunk/resume/byte progress | NOT PRESENT | — | Повтор целого URI |
| AND-BG-001 | Periodic sync | PARTIAL | photo_sync_periodic UPDATE | 1h, CONNECTED+batteryNotLow; cold URL |
| AND-BG-002 | Observer-triggered sync | PARTIAL | MediaChangeObserver / photo_sync KEEP | 3s debounce; process lifetime |
| AND-BG-003 | Scheduling reconcile | PARTIAL | PeriodicSyncCoordinator | Явные VM calls; нет subscription |
| AND-BG-004 | Headless URL restore | NOT PRESENT | — | URL восстанавливает UI ViewModel |
| AND-BG-005 | Foreground/expedited notification | NOT PRESENT | — | Обычный CoroutineWorker |
| AND-UI-001 | Bottom navigation | IMPLEMENTED | 5 destinations | Network стартовая |
| AND-UI-002 | Network configuration/test | IMPLEMENTED | NetworkScreen / ViewModel | IPv4/port HTTP |
| AND-UI-003 | Local Files monitor | PARTIAL | Room Flow + WorkInfo/status store | Текст; periodic running не показан |
| AND-UI-004 | Auth profile content | IMPLEMENTED | JWT sub + Logout/Test Auth | Не GET /profile |
| AND-UI-005 | Settings | STUB | CenteredText | Достижимый Settings screen является заглушкой; controls отсутствуют, ни один параметр sync через UI не настраивается |
| AND-UI-006 | Profile destination | STUB | CenteredText | Отдельно от Auth content |
| AND-UI-007 | Photo viewer/thumbnails | NOT PRESENT | — | Files — текстовый монитор |
| AND-UI-008 | Persistent sync history | NOT PRESENT | — | Последний outcome только в памяти |
| AND-API-001 | Ten HTTP operations | IMPLEMENTED | Retrofit 4 interfaces | Подробности06/17 |
| AND-API-002 | Server browsing/download/file mutations/folder CRUD | NOT PRESENT | — | 26 server mappings не вызываются |
| AND-API-003 | HTTPS/hostname/IPv6 configuration | NOT PRESENT | — | HTTP IPv4/port builder |
| AND-OPS-001 | HTTP/error diagnostics | PARTIAL | BODY logger / Log / status cards | Нет redaction/structured correlation |
| AND-OPS-002 | Product automated tests | NOT PRESENT | — | Два templates не product coverage |
| AND-OPS-003 | Test scaffolding | STUB | JUnit/AndroidJUnit4 templates | Arithmetic/package name |
| AND-OPS-004 | Crash reporting/analytics/CI | NOT PRESENT | — | Crash reporting/analytics — по обоим аудитам; отсутствие CI зафиксировано только Audit A |
