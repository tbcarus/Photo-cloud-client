# Background execution

Один `@HiltWorker CoroutineWorker` SyncWorker используется двумя независимыми WorkRequest types. Custom input, tags, chaining, setProgress, initial delay, expedited и foreground mode отсутствуют.

| Request | Trigger | Unique name/policy | Constraints | Интервал |
| --- | --- | --- | --- | --- |
| One-time | MediaChangeObserver после debounce | photo_sync / KEEP | CONNECTED | Событие; отдельного UI enqueue нет |
| Periodic | PeriodicSyncCoordinator.reconcile | photo_sync_periodic / UPDATE | CONNECTED, batteryNotLow | 1 час |

Интервал — настройка, а не обещание запуска ровно каждый час. CONNECTED допускает мобильную/metered сеть. Wi-Fi/charging/idle/storage constraints не заданы.

## Reconcile и observer

Coordinator проверяет наличие Tokens, непустой URL и media permission. Все true → enqueue/update periodic + observer.start; иначе cancel periodic + observer.stop. Он вызывается при NetworkViewModel init, успешном login, завершении logout Result и Files bootstrap/grant. Прямой successful Network Test не вызывает reconcile.

Coordinator не подписан на token/URL/permission изменения. Authenticator token clear сам фон не отключает. isAutoSyncConfigured для permission UI проверяет только tokens+URL, без permission.

Observer регистрируется на Images.EXTERNAL_CONTENT_URI с descendants=true, main Handler. Любое onChange перезапускает coroutine debounce 3000 ms; URI события не фильтруется по DCIM. Stop unregister и отменяет ожидающий debounce. Регистрация, flag registered и debounce job — только память процесса.

## BaseUrl after process death

App предоставляет HiltWorkerFactory, не читает settings. BaseUrlProvider по умолчанию пуст; Worker читает его, не DataStore. Headless запуск после гибели процесса/reboot до UI restore заканчивается SERVER_UNAVAILABLE+retry без scan/hash/upload.

NetworkViewModel init читает IP/port, назначает URL **до** ping и вызывает reconcile. Поэтому для восстановления не нужен успешный повторный ping UI; worker позже всё равно имеет собственный ping gate. Асинхронное создание UI допускает краткое окно пустого URL.

Завершённые Room writes, preferences и WorkManager scheduling persisted; observer и SyncStatusStore не persisted. Android не содержит собственного boot receiver. Сохранение WorkManager requests не восстанавливает app-specific URL. Реальные сроки reboot/force-stop/OEM execution — OPEN-AND-001.

## KEEP и backoff

При существующей незавершённой photo_sync KEEP не ставит отдельный follow-up request. Это относится и к ожиданию retry/backoff. Dirty flag нет: событие после завершённого scan текущего worker может не попасть в его snapshot и ждать последующего события или periodic.

Custom backoff/attempt limit и runAttemptCount branching не заданы. Backoff делегирован defaults WorkManager: exponential, начально 30 секунд, предел delay 5 часов; это не client constant и не SLA. Фактические задержки зависят от WorkManager/OS/constraints. Retry повторяет весь pipeline; entity retryCount не считает эти попытки.

## Параллельность и interruption

KEEP относится только к photo_sync, UPDATE — к другому имени. One-time и periodic могут выполняться одновременно. Singleton mutex сериализуют каждый checksum/pre-check/upload run по отдельности; общий scan и restore не защищены. UI permission scan тоже может пересечься с worker.

Stage recovery выполняется под соответствующим mutex: HASHING→PENDING после permission guard; UPLOADING→PENDING_UPLOAD только после прохождения предыдущих стадий. Recovery не выполняется сразу при App start. Уже завершённые SQL writes сохраняются.

Blocking HTTP/stream loops не связаны с Call.cancel и не имеют explicit cancellation checks; catch Exception не выделяет CancellationException. Поэтому запрос отмены work не является гарантированным барьером завершения side effects. Точное runtime прекращение остаётся OPEN-AND-007.

## Logout и UI

Logout с local clear через reconcile отменяет periodic и observer, но не photo_sync. Worker entry проверяет URL/ping, а не сессию. Уже поставленная one-time работа может сканировать/хешировать и затем получать 401/retry. Authenticator clear без reconcile оставляет и periodic/observer до очередного явного вызова.

Files наблюдает только RUNNING photo_sync. Periodic и ожидание constraints/backoff не входят в isSyncing. Last SyncStatus общий для обоих workers и живёт только в памяти; последний записавший outcome заменяет предыдущий.

Success output содержит семь counters: scan_scanned, checksum_processed, precheck_checked, upload_attempted, upload_succeeded, upload_failed, upload_left_pending. Наличие поля `upload_left_pending` в success output установлено; его фактическая ненулевая достижимость в success path отдельно не проверялась при consolidation. Bare success при PermissionDenied и retry не несут этой сводки.
