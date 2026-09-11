# 08. End-to-end business processes

[CONFIRMED] Ниже описаны вызываемые ветки, а не предполагаемые по названиям классов функции. У этапов нет отдельных Workers: всё выполняется одним SyncWorker. UI→ViewModel и Worker→repository — разные входы в local scan.

## 1. Application start

Trigger: запуск activity. MainActivity создаёт NetworkViewModel, затем theme/MainScreen/NavigationGraph(network). NetworkViewModel читает ip и port отдельными first(), восстанавливает http URL, обновляет UiState и запускает ping, затем reconcile. App только предоставляет HiltWorkerFactory, настройки не читает. При отсутствии сохранённых данных Network остаётся пустым. Ошибка чтения DataStore в init отдельно не обработана. [MainActivity.kt:14](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/MainActivity.kt:14>), [NetworkViewModel.kt:31](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:31>).

[INFERRED] При headless запуске Worker activity/ViewModel не создаётся: persisted settings не превращаются в baseUrl, ping gate отдаёт retry. При UI start возможно краткое окно между пустым URL и асинхронным восстановлением.

## 2. Server configuration

Trigger: Network Test или init с saved IP/port. UI state→trim→IPv4 regex и непустой port→ServerRepository plain ping. Success→DataStore edit→BaseUrlProvider update→success dialog; failure→error dialog/log. Numeric/range port validation отсутствует. Retrofit construction до try может бросить exception вне Result. Успешный Test не вызывает reconcile; он вызван в init после запуска testConnection coroutine. Старые tokens/Room при смене адреса сохраняются. [NetworkViewModel.kt:49](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:49>), [ServerRepository.kt:15](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt:15>).

## 3. Registration / login

Register: UI email/password→AuthViewModel→AuthRepository plain API→201 message dialog; confirmation осуществляется сервером вне реализованного Android UI.

Login: notReady guard→LOADING→plain POST→проверка body и двух token strings→VM saveTokens→tokensFlow→isLoggedIn/email→coordinator.reconcile→success dialog. Failure→dialog, уже имеющаяся session не очищается login failure. Permissions login не запрашивает; для этого нужен вход Files. [AuthViewModel.kt:59](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:59>).

~~~mermaid
sequenceDiagram
 participant U as AuthScreen
 participant V as AuthViewModel
 participant R as AuthRepository
 participant S as Server
 participant T as TokenStorage
 participant C as Coordinator
 U->>V: login(email,password)
 V->>R: login
 R->>S: POST auth/login (plain)
 S-->>R: accessToken, refreshToken
 R-->>V: Result<Tokens>
 V->>T: saveTokens
 T-->>V: tokensFlow
 V->>C: reconcile
 C->>C: tokens + URL + permission?
~~~

## 4. Scan / discovery / Room insert-update

Trigger: permission callback→FilesViewModel.scanPhotos; либо Worker stage1. FilesScreen при наличии permission только reconcile, не manual scan. MediaFileRepository→MediaStoreRepository check permission/query; snapshot→insertAll IGNORE→для каждого resetChecksumIfFileChanged→updateLocalMetadata→DB IDs minus scanned IDs→delete chunks500.

Success: ScanResult(scanned=N,insertedOrUpdated=N,deletedStale=M). VM показывает числа, Worker сохраняет агрегат. Failure: denied→VM card / Worker success без SyncStatus update; scan exception→VM error / Worker ERROR+retry. Частичные SQL updates не откатываются. [MediaFileRepository.kt:24](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:24>), [FilesViewModel.kt:50](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt:50>).

~~~mermaid
sequenceDiagram
 participant W as Worker or FilesViewModel
 participant R as MediaFileRepository
 participant M as MediaStore
 participant D as MediaFileDao
 W->>R: scanImages
 R->>M: query Images DCIM/%
 M-->>R: snapshot
 R->>D: insertAll IGNORE
 loop Every scanned image
 R->>D: reset if size/mtime changed
 R->>D: update local metadata
 end
 R->>D: getAllMediaStoreIds
 R->>D: delete missing IDs in chunks
 D-->>W: Flow updates (via repository)
 R-->>W: ScanOutcome
~~~

## 5. Automatic sync trigger и gate

Trigger: registered ContentObserver любое Images change→debounce3s→unique one-time KEEP; либо periodic1h UPDATE. One-time требует CONNECTED; periodic также batteryNotLow. Worker проверяет baseUrl и GET /test до любой локальной работы. Blank/ping failure→SERVER_UNAVAILABLE+retry. Затем один restore snapshot CHECKSUM_IO (<=500 ready failures), затем scan→checksum→precheck→upload. [MediaChangeObserver.kt:67](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt:67>), [SyncScheduler.kt:35](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:35>), [SyncWorker.kt:47](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:47>).

## 6. Checksum

Worker→ChecksumRepository permission guard→IO Mutex→reset all HASHING→PENDING. Loop batches100 newest createdAt: PENDING→HASHING→open URI→SHA256 lowercase→DAO updateChecksum→CHECKSUM_READY и clear failures. Null stream/not found/security/IO/unknown→FAILED с reason, count++, timestamp. Per-file failure не прерывает остальные файлы; итог Success может содержать failed>0. Общая ошибка→ChecksumOutcome.Error→Worker retry. [ChecksumRepository.kt:64](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:64>).

## 7. Server existence check

Worker→ChecksumPrecheckRepository mutex→FolderRepository GET root, GET root children→first folderType CAMERA. Missing CAMERA→все ready rows с nonnull checksum переводятся pending без exists call. Folder error→Worker ERROR/retry.

Existing CAMERA: batch500 ready rows→lowercase checksum→group hash→IDs, dedup request→POST checksums/exists. existing→SYNCED без ID; missing→PENDING_UPLOAD; не указанный hash остаётся ready. При полном отсутствии продвижения loop заканчивается Success с unchanged. Non2xx/empty body/exception→Error, прежние batches сохранены. [ChecksumPrecheckRepository.kt:77](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:77>).

## 8. Upload / success / failure

Worker→FileUploadRepository mutex→reset UPLOADING→PENDING_UPLOAD→resolve CAMERA заново. Любой NotCreatedYet/Error lookup даёт folderId=null. Batches20 newest: attempted++; MIME не image/→FAILED NOT_IMAGE; preopen error→FAILED. Иначе UPLOADING→multipart file+optional folderId→execute.

Success2xx + id>0→markUploaded: SYNCED, serverFileId=id, reason/time clear, count0; остальные response fields игнорируются. Duplicate response также success. Permanent400/409/413→FAILED и продолжение batch. IOException/other HTTP/invalid success ID→PENDING_UPLOAD и остановка всего upload run, Result.Error.

Exception вне upload IOException catch (например DB или SecurityException во время writeTo) попадает в Worker generic catch→ERROR/retry; текущий UPLOADING будет recovery на следующем дошедшем до upload прогоне. [FileUploadRepository.kt:51](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:51>).

~~~mermaid
sequenceDiagram
 participant W as SyncWorker
 participant P as PrecheckRepository
 participant D as Room
 participant S as Server
 participant U as UploadRepository
 participant M as ContentResolver
 W->>P: runCameraPrecheck
 P->>S: root, children
 alt CAMERA exists
 P->>S: checksums/exists(folderId,hashes)
 S-->>P: existing / missing
 P->>D: existing SYNCED / missing PENDING_UPLOAD
 else CAMERA absent
 P->>D: CHECKSUM_READY to PENDING_UPLOAD
 end
 W->>U: uploadPendingCameraFiles
 U->>D: recover UPLOADING / select pending
 U->>S: root, children again
 U->>M: preopen availability check
 U->>D: UPLOADING
 U->>S: multipart upload(file, optional folderId)
 alt 2xx and id greater than zero
 S-->>U: FileItemDto (new or duplicate)
 U->>D: SYNCED + id
 else 400 / 409 / 413
 U->>D: FAILED + reason/count/time
 else transient or invalid id
 U->>D: PENDING_UPLOAD
 U-->>W: Error / stop upload run
 end
~~~

[PARTIAL] Stream write выполняется после UPLOADING; request body читает URI при отправке. Preopen/read для logger/network может происходить несколько раз; immutable byte snapshot отсутствует.

## 9. Worker completion

Upload Error или leftPending>0→RETRY_SCHEDULED+Result.retry. Иначе SUCCESS+Result.success(output counters). FAILED count и precheck unchanged не делают итог Worker failure/retry. Поэтому «Синхронизация завершена» не гарантирует, что все фото SYNCED. PermissionDenied на scan/checksum возвращает bare success, последний SyncStatusRecord может остаться от прошлого запуска. [SyncWorker.kt:99](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:99>).

## 10. Automatic retry / manual retry

[CONFIRMED] Auto failed recovery до scan: FAILED CHECKSUM_IO, retryCount<3, lastFailureAt<=now-5min, limit500; reset PENDING, clear reason/time, count сохранить. Новая ошибка hash снова увеличит count. При count3 авто восстановления больше нет (первая попытка+до2 повторов). Cooldown не ставит timer на5min: нужен следующий worker run, а per-file failure сам по себе Result.retry не вызывает.

[CONFIRMED] Transient upload/whole-stage failure используют WorkManager retry без своего limit/runAttemptCount branching. Эти попытки не увеличивают retryCount entity.

[UNUSED] Manual retry UI, reset all failed button, per-item retry и публичная VM action для этого не найдены. Возможность вызвать DAO/repository из нового кода не является текущим пользовательским сценарием. [ChecksumRepository.kt:45](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:45>), [FilesScreen.kt:94](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:94>).

## 11. App restart / process death / WorkManager interruption

[CONFIRMED] Room/tokens/settings сохраняются; UI states/last SyncStatus/observer теряются. HASHING восстанавливается на входе checksum stage, UPLOADING на входе upload stage, не непосредственно Application start. До upload recovery должны успешно пройти ping/scan/checksum/precheck. Blocking .execute и checksum loop не содержат explicit cancellation checks; catch Exception перехватывает также cancellation на части границ. [INFERRED] Нельзя считать WM cancel гарантией немедленной остановки сетевых/файловых side effects.

## 12. Device restart

[INFERRED] WorkManager использует своё persistent scheduling; собственный boot flow в приложении отсутствует. Даже если библиотека запустит pending/periodic после reboot, BaseUrlProvider нового процесса пуст. Пока activity не восстановит его, Worker возвращает retry. Реальное время исполнения зависит от OS/constraints и не подтверждено устройством.

## 13. Permission loss

[CONFIRMED] До scan/checksum→PermissionDenied; во время доступа→Error либо FAILED PERMISSION. При поздней выдаче разрешения scan не сбрасывает неизменный FAILED PERMISSION. Observer/periodic отменяются только при вызове reconcile; loss event сам такой вызов не делает. Нет settings deep link или manual recovery в FilesScreen.

## 14. Local modification / deletion

[CONFIRMED] Size/mtime change→PENDING/null hash/null serverId/reset failure, повтор pipeline. Уже загруженный server object не изменяется и не удаляется. [RISK] Изменение после hash до upload не сверяется, возвращённый server checksum игнорируется.

[CONFIRMED] Отсутствие ID в успешном DCIM snapshot→Room DELETE независимо от текущего status. Удаление во время preopen может дать FAILED FILE_NOT_FOUND; следующая scan удалит row. Remote DELETE не вызывается, LOCAL_DELETED не присваивается. [MediaFileDao.kt:155](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:155>), [MediaFileRepository.kt:64](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:64>).

## 15. Logout / account or server switch

[CONFIRMED] Logout flow описан в06: HTTP response→clear; transport failure может оставить tokens. Reconcile отменяет periodic, observer, но one-time и Room не очищаются. [INFERRED] Новый account/server наследует SYNCED старого и старые pending; uploads используют credentials текущего singleton storage. Это не реализованная миграция аккаунта — scope отсутствует.
