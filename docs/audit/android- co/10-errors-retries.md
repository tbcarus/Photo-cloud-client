# 10. Errors and retries

## Уровни ошибок

[CONFIRMED] Есть три независимых представления: per-file FAILED+FailureReason+retry metadata в Room; stage Outcome.Error(message); Worker Result.retry + in-memory SyncStatus. Они не сводятся в общий error model. Throwable types обрабатываются по месту; custom exception classes не найдены. Auth/server используют Kotlin Result, остальные sealed outcomes. [FailureReason.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FailureReason.kt:7>), [SyncWorker.kt:36](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:36>).

## Failure matrix

| Failure | Source | Stored state | Retryable | Retry trigger | User visible |
| --- | --- | --- | --- | --- | --- |
| [CONFIRMED] Permission absent до scan | MediaStoreRepository guard | Room unchanged | После grant | UI callback / future worker | Card только VM scan; Worker bare success |
| [CONFIRMED] Null cursor | Scanner query | Room unchanged | Whole run | Worker retry | Generic ERROR, VM text если UI |
| [CONFIRMED] Malformed row | Scanner per-row catch | Row исключена из snapshot, может стать stale | Не classified | Следующий scan | Молча |
| [CONFIRMED] Scan/Room exception | MediaFileRepository catch | Partial writes possible | Whole run | Worker retry | VM error либо generic sync ERROR |
| [CONFIRMED] Permission absent до hash | ChecksumRepository guard | HASHING recovery не выполнен | После grant | Future run | Worker bare success |
| [CONFIRMED] Hash null stream/FNF | ChecksumRepository | FAILED FILE_NOT_FOUND,count++,time | Нет auto | File change/re-discovery only | File not found |
| [CONFIRMED] Hash SecurityException | ChecksumRepository | FAILED PERMISSION,count++,time | Нет auto | File change/re-discovery only | Permission denied |
| [CONFIRMED] Hash IOException | ChecksumRepository | FAILED CHECKSUM_IO,count++,time | Ограниченно | Future Worker eligible restore | Checksum I/O error |
| [CONFIRMED] Hash other Exception | ChecksumRepository | FAILED UNKNOWN,count++,time | Нет auto | File change/re-discovery only | Unknown error |
| [CONFIRMED] Non-image | Upload MIME guard | FAILED NOT_IMAGE | Нет auto | File change/re-discovery | Not an image |
| [CONFIRMED] Upload preopen null/FNF/Security | FileUploadRepository | FAILED FILE_NOT_FOUND/PERMISSION | Нет auto | File change/re-discovery | Per-file reason |
| [CONFIRMED] Upload IOException after UPLOADING | execute/body writing | PENDING_UPLOAD; no reason/time/count | Да | Worker retry | RETRY_SCHEDULED |
| [CONFIRMED] Upload400 | API | FAILED HTTP_400,count++,time | Нет auto | Нет manual action | HTTP400 |
| [CONFIRMED] Upload409 | API | FAILED HTTP_409,count++,time | Нет auto | Нет manual action | HTTP409 |
| [CONFIRMED] Upload413 | API | FAILED HTTP_413,count++,time | Нет auto | Content change | HTTP413 |
| [CONFIRMED] Upload401 after refresh /403/404/429/5xx/other | API | PENDING_UPLOAD; stop run | Неограниченно со стороны client code | Worker retry | RETRY_SCHEDULED, без причины |
| [CONFIRMED] Upload2xx empty/invalid id | Response validation | PENDING_UPLOAD | Да | Worker retry | Generic status |
| [CONFIRMED] Upload SecurityException в повторном write / generic runtime | Outside local IO catch | Возможен UPLOADING | Whole run | Worker retry + recovery | ERROR |
| [CONFIRMED] Folder lookup error precheck | FolderRepository | Ready retained | Whole run | Worker retry | ERROR |
| [CONFIRMED] Folder lookup error upload | FolderRepository | Нет непосредственного FAILED | Fallback null folder | Тот же upload | Ошибка lookup скрыта |
| [CONFIRMED] Precheck HTTP/parse/network error | ChecksumPrecheckRepository | Current batch ready, earlier batches committed | Whole run | Worker retry | ERROR |
| [CONFIRMED] Precheck incomplete partition | Response | Unchanged ready | Не ставит retry сам | Следующий scheduled run | Может показывать SUCCESS |
| [CONFIRMED] Public ping/baseUrl fail | Worker/server repository | File states unchanged | Да | Worker retry | SERVER_UNAVAILABLE |
| [CONFIRMED] Login/register HTTP error | AuthRepository | Tokens unchanged | Не автоматически | User action | Dialog parsed message |
| [CONFIRMED] Refresh non2xx | Authenticator | Tokens cleared | Новая login session | User login | Auth flow logged-out, files may keep retrying |
| [CONFIRMED] Refresh network exception | Authenticator | Tokens retained | Caller-specific | Next request/worker | Caller-specific |
| [CONFIRMED] Logout transport fail | AuthRepository | Tokens могут сохраниться | User request | Logout again | Logout error dialog |

Источники: [MediaStoreRepository.kt:61](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:61>), [ChecksumRepository.kt:105](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:105>), [FileUploadRepository.kt:76](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:76>), [TokenAuthenticator.kt:58](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt:58>), [AuthRepository.kt:55](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt:55>), [SyncWorker.kt:47](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:47>).

## Auto recovery FAILED подробно

[CONFIRMED] Единственная retryable reason — CHECKSUM_IO. Selector FAILED+reason+retryCount<3+lastFailureAt<=now-300000, sort createdAt DESC limit500; snapshot один за Worker. Restore изменяет status=PENDING, reason/time=null, count retained. Нет записи nextRetryAt и отдельной scheduled task на истечение cooldown.

[CONFIRMED] Три — лимит накопленных неуспехов, а не три дополнительных повтора. После первой failure count1 возможны ещё две; при count3 запись остаётся FAILED. Успешный checksum ставит count0. Permanent failures тоже увеличивают count, но не eligible. Исторические FAILED с reason/time null также не eligible. Restore не использует isRetryable property — reason жёстко задан CHECKSUM_IO. [ChecksumRepository.kt:28](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:28>), [MediaFileDao.kt:63](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:63>).

[PARTIAL] Если в прогоне только per-file CHECKSUM_IO failures, Worker может завершиться SUCCESS; следующая попытка будет по periodic/observer, а не обязательно через5min. Нет восстановления PERMISSION после grant и FILE_NOT_FOUND после повторного появления того же ID с неизменными metadata. При смене metadata reset очищает failure независимо от reason.

[RISK] restoreRetryableFailed вне mutex/transaction: concurrent workers могут выбрать один snapshot и повторно reset строку, которую другой уже начал обрабатывать. Лимит «один раз за проход» обеспечен только локальным кодом одного worker, не всеми одновременно.

## WorkManager retry

[CONFIRMED] Result.retry для ping/error/precheck/upload transient/generic exception; custom backoff criteria и maximum attempts не заданы, runAttemptCount не используется. WorkManager library defaults, constraints и OS определяют график; точное wall-clock время не обещано. Нет Retry-After handling, jitter, per-file upload attempts или выборки по nextRetryAt. [SyncScheduler.kt:40](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:40>), [SyncWorker.kt:36](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:36>).

[RISK] Постоянный403/404/500 upload и отсутствие токенов могут порождать повтор без изменения условий. Очередь newest-first, первый transient прекращает прогон; один проблемный новый item задерживает остальные pending.

## Сопоставление server error contract

[DOCUMENTED] [Server errors](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/03-error-contract.md>) не гарантируют uniform JSON для IO/framework; duplicate upload200, а не409;400/413 означают invalid/oversized request; deterministic constraint500 не всегда transient. Клиент корректно считает duplicate2xx success, не делает blind retry409 и умеет показать raw non-JSON.

[INCONSISTENCY] Upload классифицирует все HTTP кроме400/409/413 как transient, теряя разницу UNAUTHORIZED/FORBIDDEN/NOT_FOUND/DATABASE_CONSTRAINT_VIOLATION. ApiErrorParser отбрасывает error id и structured code; raw body для malformed JSON не включает HTTP code. Для JSON неподходящей структуры parser может вернуть HTTP N, скрыв тело.

[CONFIRMED] UUID id читается DTO, но никогда не сохраняется/показывается; fieldErrors сворачиваются в строку field: message через "; ". Parsed upload permanent error message вообще не сохраняется — остаётся HTTP_x. Login/register показывают ту же строку общим dialog, без inline field errors. [ApiErrorParser.kt:12](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt:12>), [FileUploadRepository.kt:127](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:127>).

## Cancellation и ошибки самого error handling

[RISK] catch Exception/runCatching захватывают cancellation; нет dedicated rethrow CancellationException. Errors Room внутри markFailed могут прервать stage и попасть в общий retry, не гарантируя нужный per-file reason. Retry/Result messages описывают намерение повторить, а не доказательство будущего успешного восстановления. Tests для этих путей отсутствуют.

