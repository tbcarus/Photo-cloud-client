# 15. Implemented features

[CONFIRMED] Формат Feature / Status / Main components / Notes совместим с server feature matrix. IMPLEMENTED означает наличие активного кода в данном объёме функции, не runtime certification. PARTIAL — существенная реализованная часть с ограничениями; STUB — достижимая заглушка; UNUSED — producer/caller отсутствует; NOT FOUND — соответствующий код не найден. Риски отдельно16.

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Server connection configuration/test | IMPLEMENTED | NetworkScreen/VM, ServerPreferences, ServerRepository | IPv4+port HTTP, public ping, save on success |
| Register request | IMPLEMENTED | AuthScreen/VM/Repository |201 message; подтверждение outside app |
| Registration confirmation/resend UI | NOT FOUND | — | Deep link/query endpoint не объявлен |
| Login | IMPLEMENTED | AuthScreen/VM/Repository, TokenStorage |2 tokens, nonblank validation |
| Logout | PARTIAL | AuthRepository/AuthViewModel/coordinator | IOException до clear оставляет tokens; one-time/Room сохраняются |
| Token refresh | PARTIAL | AuthInterceptor/TokenAuthenticator | No rotation, lock+loop guard; any non2xx clears, races with session changes |
| Encrypted token storage | IMPLEMENTED | EncryptedPrefsTokenStorage | AES schemes, одна pair; restore/corruption flow нет |
| Session verification | PARTIAL | AuthViewModel.verifySession | Request есть; Result не анализируется |
| Permissions | PARTIAL | FilesScreen + repo/coordinator guards | Runtime request; revoke/permanent denial/selected access incomplete |
| Scan | IMPLEMENTED | MediaStoreRepository/MediaFileRepository | Images DCIM/% only, full snapshot |
| Room persistence | IMPLEMENTED | AppDatabase/MediaFileDao/MediaFile | DB3, two migrations; no account scope |
| Checksum | IMPLEMENTED | ChecksumRepository/ChecksumUtil | SHA256 stream, batches100 |
| File change detection | PARTIAL | resetChecksumIfFileChanged | Только size/mtime; in-flight mutation не защищена |
| Server existence check | IMPLEMENTED | FolderRepository/ChecksumPrecheckRepository | Folder-scoped batches≤500, first CAMERA absence handled |
| Upload | IMPLEMENTED | FileUploadRepository/ContentUriRequestBody | Single file, explicit upload alias, id validation, duplicate200 |
| Upload integrity reconciliation | NOT FOUND | — | Ответный checksum/size не сверяются |
| Automatic retry | PARTIAL | ChecksumRepository/SyncWorker | CHECKSUM_IO limited + WM transient; no general permanent recovery |
| Manual retry | NOT FOUND | — | Нет button/VM action для FAILED |
| Failure reason | IMPLEMENTED | FailureReason/DAO/FilesScreen |8 reasons; legacy null и detailed server code loss |
| Background sync | PARTIAL | Worker/Scheduler/Coordinator/Observer | Periodic+event; cold URL and lifecycle races |
| UI status display | PARTIAL | FilesScreen/FilesViewModel/SyncStatusStore |7 counters,8 statuses; only one-time running, last summary in memory |
| Media list | IMPLEMENTED | Room Flow→FilesScreen | Local textual list, no thumbnails/viewer |
| Video | NOT FOUND | — | Только Images permission/query и image/ upload guard |
| Local stale removal | IMPLEMENTED | MediaFileRepository/DAO | Физический DELETE индекса, оригиналы не удаляет |
| Local delete operation | NOT FOUND | — | Нет MediaStore delete |
| Remote deletion / reconciliation | NOT FOUND | — | Нет DELETE /files или reread synced |
| LOCAL_DELETED state | UNUSED | MediaFileStatus/formatStatus | Enum+UI label, no transition writer |
| Server media list/download | NOT FOUND | — | Нет endpoints/DTO page model |
| Settings | STUB | SettingsScreen | CenteredText(Settings), no controls |
| Profile destination | STUB | ProfileScreen | CenteredText(Profile) |
| Login profile content | IMPLEMENTED | AuthScreen.ProfileContent/JwtUtils | JWT sub display, logout/test |
| Server profile API | NOT FOUND | — | GET /profile отсутствует |
| Folder lookup | IMPLEMENTED | FolderRepository | Root/direct children/CAMERA, no cache |
| Folder CRUD/selection | NOT FOUND | — | Все upload направлены CAMERA/default |
| Password reset | NOT FOUND | — | Android reset flow отсутствует |
| Logout-all/others | NOT FOUND | — | Only current refresh logout |
| Progress/resumable upload | NOT FOUND | — | Нет byte progress/chunks/resume token |
| Account/server isolation | NOT FOUND | — | Shared DB/pair, no namespace |
| JWT local expiry helper | UNUSED | JwtUtils.isExpired | Current callers не найдено |
| Incremental scan helper | UNUSED | MediaFileDao.getLatestCreatedAt | Full scan only |
| Product tests | NOT FOUND | — |2 template tests only |

Доказательная карта компонентов: [18-code-map.md](<C:/projects/Photocloudclient/docs/audit/android/18-code-map.md>), flows08/state09/errors10; сравнение с серверными возможностями — [19-server-client-consistency.md](<C:/projects/Photocloudclient/docs/audit/android/19-server-client-consistency.md>).

