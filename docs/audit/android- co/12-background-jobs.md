# 12. Background jobs / WorkManager

## Inventory

[CONFIRMED] Найден **один Worker class — SyncWorker**, CoroutineWorker с @HiltWorker и AssistedInject. WorkRequest типов два, класс один. В app manifest удалён весь androidx.startup.InitializationProvider; App реализует Configuration.Provider и возвращает HiltWorkerFactory. Собственной eager initialize(), Service, alarm, boot receiver, foreground info/notification нет. [App.kt:9](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/App.kt:9>), [SyncWorker.kt:19](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:19>).

| Worker/request | Trigger | Input | Output | Constraints | Uniqueness | Retry/backoff | Chaining/persisted state |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [CONFIRMED] SyncWorker one-time | MediaChangeObserver после debounce3s | Нет custom inputData | Success counters; retry без output | CONNECTED | photo_sync,KEEP | Result.retry; custom backoff/limit не заданы | Один doWork pipeline; WM request + Room states |
| [CONFIRMED] SyncWorker periodic | Coordinator.reconcile | Нет custom inputData | Тот же buildOutput при success | CONNECTED,batteryNotLow | photo_sync_periodic,UPDATE; interval1h | Тот же Result.retry; no runAttemptCount branch | Independent periodic request; Room state общая |

[CONFIRMED] Никаких beginWith/then chain, tags, setProgress, expedited, initialDelay, custom executor или constraints Wi-Fi/charging не найдено. NetworkType.CONNECTED не ограничивает sync Wi-Fi. Batch размеры100/500/20 ограничивают snapshot, **не число файлов за worker**: while loops опустошают соответствующие очереди.

## Pipeline внутри doWork

~~~mermaid
flowchart TD
 A["doWork try/catch"] --> B{"baseUrl nonblank and public ping OK?"}
 B -- no --> R["SERVER_UNAVAILABLE / Result.retry"]
 B -- yes --> C["restore eligible FAILED CHECKSUM_IO"]
 C --> D["scan Images → Room"]
 D --> E["checksum: recover HASHING then process PENDING"]
 E --> F["precheck CAMERA / classify ready"]
 F --> G["upload: recover UPLOADING then process pending"]
 G --> H{"Upload error or leftPending > 0?"}
 H -- yes --> I["RETRY_SCHEDULED / Result.retry"]
 H -- no --> J["SUCCESS / Result.success counters"]
 D -. permission denied .-> K["Result.success without status/output"]
 E -. permission denied .-> K
 D -. error .-> L["ERROR / retry"]
 E -. error .-> L
 F -. error .-> L
 A -. uncaught Exception .-> L
~~~

[CONFIRMED] Output keys: scan_scanned, checksum_processed, precheck_checked, upload_attempted, upload_succeeded, upload_failed, upload_left_pending; missing aggregate→0. Output не содержит per-file IDs/errors/permission info. SyncStatusStore record отдельно существует только в памяти. [SyncWorker.kt:122](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:122>).

## Scheduling lifecycle

[CONFIRMED] Coordinator.reconcile читает authRepository.getTokens()!=null, isReady(), permission. True→enqueuePeriodicSync + observer.start; false→cancelPeriodicSync + observer.stop. Вызовы: NetworkViewModel.init, AuthViewModel successful login и после logout, FilesViewModel.ensureAutoSyncStarted/onMediaPermissionGranted. Нет subscription на tokensFlow/URL/permission. Успешное сохранение нового URL в Test само не вызывает reconcile. [PeriodicSyncCoordinator.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt:38>), [NetworkViewModel.kt:68](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:68>).

[CONFIRMED] Observer singleton содержит registered boolean, CoroutineScope(SupervisorJob+Default), debounceJob и ContentObserver(main Handler). Регистрация descendants=true на Images.EXTERNAL_CONTENT_URI. Любая gallery image change ставит общий sync (URI content не фильтруется до DCIM); stop unregister и cancel debounce. Это process-lifetime observer, не отдельный persistent system job. [MediaChangeObserver.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt:38>).

## Restart, interruption и recovery

| Ситуация | Фактическая защита / ограничение |
| --- | --- |
| [PARTIAL] Process killed mid-hash | Room HASHING persists; следующий checksum stage→PENDING; до этого должны пройти gate/scan |
| [PARTIAL] Process killed mid-upload | Room UPLOADING persists; следующий upload stage→PENDING_UPLOAD; HTTP commit мог состояться |
| [INFERRED] Process start from background / reboot | App не восстанавливает URL; Worker blank gate retry до foreground ViewModel |
| [PARTIAL] Network loss | .execute IOException→pending/retry; constraints также управляются WM; whole request resend |
| [PARTIAL] Permission revoked | scan/hash guard→success, per-file access errors→FAILED PERMISSION; retry on grant не реализован |
| [PARTIAL] Logout | Reconcile cancels periodic и observer при token clear; one-time не отменяется |
| [RISK] Token clear by authenticator | UI flow обновится; background scheduling не пересматривается автоматически |
| [RISK] Interrupted coroutine | Нет explicit cancellation rethrow/checks; blocking .execute и streams не привязаны к Call.cancel |
| [PARTIAL] Force stop / OEM restrictions / Doze | Свой recovery не реализован; runtime behavior и сроки в этом аудите не измерялись |

[INFERRED] Persisted WorkManager scheduling не устраняет app-specific cold URL dependency. Наличие библиотеки не означает, что backup успешно выполняется без activity после reboot.

## Duplicate work и isolation

[CONFIRMED] KEEP не допускает второй unfinished work с **тем же** photo_sync name; UPDATE обслуживает другое имя. [RISK] Они могут исполняться одновременно и заходить в разные стадии одного общего индекса. Mutex на checksum/precheck/upload ограничивает лишь каждый stage; scan и retry restore не защищены. Нет global pipeline mutex, DB run owner или generation/version compare. [SyncScheduler.kt:45](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:45>), [ChecksumRepository.kt:26](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:26>), [MediaFileRepository.kt:24](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:24>).

[RISK] Event во время running one-time может быть coalesced KEEP; отдельного dirty flag/follow-up job нет. Если scan уже прошёл, новое фото может ждать periodic или следующего события. Observer не переживает process death. Нет explicit startup one-time enqueue при login/grant; periodic может запуститься по своему scheduling, точное время не задаётся.

## Diagnostics и UI

[CONFIRMED] FilesViewModel observeIsSyncing смотрит **только photo_sync** и только RUNNING. Periodic processing может менять Room без loader. ENQUEUED/BLOCKED не показаны как ожидание constraints, последнее retry message может оставаться без изменения. Timestamp SyncStatusRecord в card не показывается. [SyncScheduler.kt:87](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:87>), [FilesScreen.kt:183](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:183>).

[PARTIAL] Result.success означает окончание текущего прохода, не отсутствие FAILED/unchanged CHECKSUM_READY. Worker не возвращает Result.failure даже при permanent per-file failures; эти failures видны в counters/rows, если UI открыт.

