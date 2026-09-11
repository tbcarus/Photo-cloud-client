# 09. State machines

## MediaFile lifecycle

[CONFIRMED] Status enum из [MediaFileStatus.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt:3>): PENDING, HASHING, CHECKSUM_READY, PENDING_UPLOAD, UPLOADING, SYNCED, FAILED, LOCAL_DELETED. Последний [UNUSED] как записываемое состояние: отображение есть, transition assignment нет.

~~~mermaid
stateDiagram-v2
 [*] --> PENDING: scan new ID
 PENDING --> HASHING: checksum starts
 HASHING --> CHECKSUM_READY: SHA256 success
 HASHING --> FAILED: null stream or exception
 HASHING --> PENDING: next checksum recovery
 CHECKSUM_READY --> SYNCED: existing hash
 CHECKSUM_READY --> PENDING_UPLOAD: missing hash or no CAMERA
 PENDING_UPLOAD --> UPLOADING: MIME and preopen passed
 PENDING_UPLOAD --> FAILED: not image or preopen failure
 UPLOADING --> SYNCED: 2xx and valid id
 UPLOADING --> FAILED: HTTP 400/409/413
 UPLOADING --> PENDING_UPLOAD: transient or next upload recovery
 FAILED --> PENDING: eligible CHECKSUM_IO retry
 SYNCED --> PENDING: scan size/mtime changed
 FAILED --> PENDING: scan size/mtime changed
 CHECKSUM_READY --> PENDING: scan size/mtime changed
 PENDING_UPLOAD --> PENDING: scan size/mtime changed
 UPLOADING --> PENDING: scan size/mtime changed
 HASHING --> PENDING: scan size/mtime changed
 SYNCED --> [*]: scan deletes stale row
 note right of SYNCED
 May have no serverFileId after precheck.
 No remote reconciliation.
 end note
~~~

[CONFIRMED] Удаление stale применимо ко **всем** состояниям, не только SYNCED на компактной схеме. LOCAL_DELETED намеренно не изображён как достижимый переход.

## Полная таблица переходов и сохранённых полей

| Current state | Event | Condition | New state | Code location |
| --- | --- | --- | --- | --- |
| [CONFIRMED] Нет row | Discovery | Новый mediaStoreId | PENDING, hash/server ID null, count0 | [MediaFileRepository.kt:88](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:88>) |
| [CONFIRMED] PENDING | Hash batch start | permission есть, stage mutex acquired | HASHING, прочие поля без изменений | [ChecksumRepository.kt:101](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:101>) |
| [CONFIRMED] HASHING | Hash success | stream прочитан | CHECKSUM_READY, новый hash, clear reason/count/time | [MediaFileDao.kt:29](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:29>) |
| [CONFIRMED] HASHING | Stream failure | null/FNF/Security/IO/other | FAILED, reason/count++/time now, hash/ID сохраняются | [ChecksumRepository.kt:105](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:105>) |
| [CONFIRMED] HASHING | Stage recovery | Следующий computePendingChecksums после permission guard | PENDING, status only | [ChecksumRepository.kt:86](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:86>) |
| [CONFIRMED] CHECKSUM_READY | CAMERA absent | root/children успешны, CAMERA не найден | PENDING_UPLOAD, status only | [ChecksumPrecheckRepository.kt:53](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:53>) |
| [CONFIRMED] CHECKSUM_READY | exists=existing | hash входит existing set | SYNCED, serverFileId не заполняется | [ChecksumPrecheckRepository.kt:114](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:114>) |
| [CONFIRMED] CHECKSUM_READY | exists=missing | не existing, входит missing | PENDING_UPLOAD | [ChecksumPrecheckRepository.kt:114](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:114>) |
| [CONFIRMED] CHECKSUM_READY | hash не в partition | malformed/incomplete partition | CHECKSUM_READY; no-progress exits loop | [ChecksumPrecheckRepository.kt:133](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:133>) |
| [CONFIRMED] CHECKSUM_READY | precheck error | folder/API/parse error | Без изменений для неотработанного batch | [ChecksumPrecheckRepository.kt:100](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:100>) |
| [CONFIRMED] PENDING_UPLOAD | MIME guard | не startsWith(image/) | FAILED NOT_IMAGE, count++,time | [FileUploadRepository.kt:79](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:79>) |
| [CONFIRMED] PENDING_UPLOAD | Preopen fail | null/FNF/Security | FAILED FILE_NOT_FOUND/PERMISSION | [FileUploadRepository.kt:86](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:86>) |
| [CONFIRMED] PENDING_UPLOAD | Начало request | guards passed | UPLOADING | [FileUploadRepository.kt:95](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:95>) |
| [CONFIRMED] UPLOADING | Success | response.isSuccessful && body.id>0 | SYNCED, server ID, clear failure/count/time | [FileUploadRepository.kt:111](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:111>) |
| [CONFIRMED] UPLOADING | Permanent HTTP |400/409/413 | FAILED reason HTTP_x, count++,time; hash остаётся | [FileUploadRepository.kt:126](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:126>) |
| [CONFIRMED] UPLOADING | IO / other HTTP / invalid ID | catch IOException or transient branch | PENDING_UPLOAD, status only, run stops | [FileUploadRepository.kt:98](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:98>) |
| [CONFIRMED] UPLOADING | Unhandled stage exception/kill | До очередного SQL update | UPLOADING остаётся persistent | [SyncWorker.kt:36](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:36>) |
| [CONFIRMED] UPLOADING | Recovery | Следующий upload stage | PENDING_UPLOAD, status only | [FileUploadRepository.kt:51](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:51>) |
| [CONFIRMED] FAILED | Auto restore | reason=CHECKSUM_IO,count<3,time<=now-5min,<=500 rows | PENDING, reason/time null, count retained | [ChecksumRepository.kt:45](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:45>) |
| [CONFIRMED] Любой существующий | Local content change | size differs OR lastModified differs | PENDING, checksum/server ID/reason/time null,count0 | [MediaFileDao.kt:155](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:155>) |
| [CONFIRMED] Любой существующий | Metadata refresh | size/mtime same | Status/hash/ID/failure retained; metadata updated | [MediaFileDao.kt:92](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:92>) |
| [CONFIRMED] Любой существующий | Stale scan | ID не входит в успешный snapshot | Row deleted, не LOCAL_DELETED | [MediaFileRepository.kt:64](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:64>) |
| [CONFIRMED] SYNCED | Server delete/move/bytes missing | Нет client reads для этого | SYNCED остаётся | [ChecksumPrecheckRepository.kt:84](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:84>) |
| [CONFIRMED] Любой | Logout/account/server change | Нет DB reset | Без изменений | [AuthRepository.kt:55](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt:55>) |

## Дополнительные поля и инварианты

[CONFIRMED] retryCount увеличивает каждый markFailed, включая permanent, но auto selector использует только CHECKSUM_IO. lastFailureAt содержит wall-clock ms, а не monotonic time. Изменение часов устройства меняет eligibility. Checksum success/upload success/file changed reset count; resetFailedForRetry сохраняет count. Retry status change не стирает checksum/serverFileId. [MediaFileDao.kt:39](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:39>).

[PARTIAL] Предполагаемые нормальные комбинации: PENDING hash=null; CHECKSUM_READY/PENDING_UPLOAD hash nonnull; SYNCED hash nonnull и server ID optional; FAILED reason normally nonnull. SQL не гарантирует эти инварианты. Публичные updateStatus/resetStatus могут менять status независимо от полей, updateChecksum допускает status аргумент, без expected old state.

| Комбинация / ситуация | Доказательство и оценка |
| --- | --- |
| [CONFIRMED] SYNCED + serverFileId null | Нормальный existing precheck, не corruption |
| [CONFIRMED] FAILED + reason null/count0/time null | Возможны исторические rows после migration2→3; авто retry не подберёт |
| [RISK] SYNCED + stale checksum | Upload response hash игнорируется, local bytes могли смениться |
| [RISK] CHECKSUM_READY + null checksum | Scan reset между hash snapshot и status-only precheck update; query IS NOT NULL не гарантирует последующий atomic update |
| [RISK] SYNCED + чужой server ID | DB не scoped; смена server/account сохраняет row |
| [RISK] PENDING + старый hash/ID | status-only recovery/reset APIs сохраняют поля; отсутствуют schema constraints |
| [RISK] Исчезнувшая row, успешный upload | Scan DELETE во время request; markUploaded обновит0 rows без проверки |
| [PARTIAL] LOCAL_DELETED row | Enum/formatter существуют; штатный producer не найден |

[RISK] Пример межстадийной гонки: precheck читает CHECKSUM_READY/hashA; concurrent scan обнаруживает changed bytes и ставит PENDING/null; ответ existing(hashA) выполняет status-only SYNCED по ID. Теперь local row может иметь null hash и уже новый metadata при SYNCED. Ни общий mutex, ни SQL version guard эту цепочку не исключают.

## Восстановление и work states

[CONFIRMED] HASHING recovery выполняется после permission check, UPLOADING recovery после завершения предыдущих стадий Worker. Это recovery по status, не по owner/lease. Один stage Mutex защищает лишь concurrent экземпляры соответствующего singleton repository; persisted worker identity не записывается.

[PARTIAL] Process death не теряет SQL-успехи, но HTTP commit и Room markUploaded не атомарны. Повтор upload использует серверную duplicate семантику, однако target и bytes не snapshot. CancellationException не выделен из catch Exception; библиотека может отменить worker, но код не обеспечивает немедленный abort blocking IO.

[CONFIRMED] SyncStatusStore принимает SUCCESS/SERVER_UNAVAILABLE/ERROR/RETRY_SCHEDULED, record timestamp теперь; это не WM enum. observeIsSyncing=true только если one-time WorkInfo RUNNING; ENQUEUED/BLOCKED/periodic не отображаются как running. Worker Result.failure нигде не возвращается. [SyncScheduler.kt:87](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:87>), [SyncWorker.kt:112](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:112>).

