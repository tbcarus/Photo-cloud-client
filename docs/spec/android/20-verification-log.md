# Android code verification log

Здесь ровно **18 verification questions**. Запись существует только при чтении production source/runtime declaration. Android schemas и tests в этой консолидации не открывались. Git name/hash checks и чтение audit/spec Markdown не являются verification-log entries. Повторно использованное evidence указано явно; один вопрос может охватывать непосредственный call chain.

Причины — по исходному вопросу, а не по конечному вердикту: SERVER_CLIENT_MISMATCH может завершиться подтверждением совместимости. Это число нельзя приравнивать к четырём итоговым INT-AND MISMATCH. Общая reconciliation history находится в21.

## VER-AND-001

**Reason:** CRITICAL_DETAIL

**Audit A:** 00§12/C1; 02§4.2; 08 P1–P2; 12§4: URL только из UI, cold process retry.

**Audit B:** 00 L01; 08§1–2; 12 Restart: та же зависимость, init ping запускается асинхронно.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Кто восстанавливает URL; сохраняется ли URL при неуспешном init ping и вызывает ли отдельный Network Test reconcile?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/di/BaseUrlProvider.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/di/BaseUrlProvider.kt) (1–10); [app/src/main/java/ru/tbcarus/photo_cloud_client/App.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/App.kt) (1–20); [app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt) (1–88).

**Verified result:** BaseUrlProvider starts empty; App только WorkManager config. NetworkViewModel init читает prefs, ставит URL до testConnection и вызывает reconcile; testConnection запускает отдельную coroutine и success сохраняет URL без reconcile. Failure не очищает ранее восстановленный адрес. Worker gate подтверждён VER-AND-003. Scope закрыт без чтения других entry/UI classes.

**Applied to:** 00,01,02,10,13; RISK-AND-001/008/033; OPEN-AND-001.

## VER-AND-002

**Reason:** CRITICAL_DETAIL

**Audit A:** 11§5; 12§2–5: KEEP/UPDATE, observer process lifetime, logout не cancels one-time.

**Audit B:** 12 Scheduling/Duplicate work: отдельные names, нет dirty follow-up; UI только photo_sync.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Каковы names/policies/constraints, trigger и observer lifecycle; что реально отменяет reconcile?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt) (1–96); [app/src/main/java/ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt) (1–61); [app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt) (1–75).

**Verified result:** photo_sync KEEP CONNECTED; photo_sync_periodic UPDATE1h CONNECTED+batteryNotLow; custom backoff/input/progress нет. Cancel только periodic. Reconcile проверяет pair/URL/permission и start/stop observer; Flow subscription нет. Observer Images descendants,3s debounce; URI не фильтрует. UI observe только one-time RUNNING. Library scheduling/OEM runtime не проверялся.

**Applied to:** 02,10,12,13,16; RISK-AND-008/009/026/035/038; OPEN-AND-001.

## VER-AND-003

**Reason:** CRITICAL_DETAIL

**Audit A:** 08 P5–P11; 09§8.3; 12§2.1: stages и Result mapping.

**Audit B:** 08§9–11; 12 Diagnostics; W05: SUCCESS совместим с FAILED/unchanged.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Точный порядок стадий, auth gate, success/permission paths и persistent recovery entry?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt) (1–132).

**Verified result:** Gate URL/public ping до restore/scan/hash; token gate нет. Restore once, затем scan/hash/precheck/upload. PermissionDenied scan/hash→bare success без status update. Stage errors→ERROR/retry; upload Error→RETRY_SCHEDULED/retry; иначе SUCCESS/output независимо от file failed/unchanged. Generic Exception catch включает cancellation. Recovery markers выполняются repositories, не App.

**Applied to:** 00,08–11,12; RISK-AND-001/009/025/027/039.

## VER-AND-004

**Reason:** SERVER_CLIENT_MISMATCH

**Audit A:** 06§2.6–2.11;19§3: non2xx refresh clear, public plain; старый handoff требует иной retry policy.

**Audit B:** 06 Expiration/lock;19 Auth:5xx clear и no session lock; blank access принимается.

**Server Specification:** SRV-AUTH-005; server06 Refresh/Public; server10:5xx не сообщает revoke, public invalid Bearer может отклоняться filter.

**Question:** Посылается ли access на refresh, чем отличаются4xx/5xx/IO/empty success и как ограничен concurrent replay?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt) (1–89); [app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/AuthInterceptor.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/AuthInterceptor.kt) (1–18); [app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt) (1–53).

**Verified result:** Refresh отдельным plain Retrofit без Bearer/authenticator. ResponseCount>=2 stop. Под lock URL/current pair; reuse current access только при nonnull failed access, отличающемся от current. Non2xx clear, IO escapes,2xx null body/access retain; blank access принимается. Сохраняется old refresh. Lock не session-wide; fixed server5xx→clear branch составляет INT-AND-004, public header mismatch отсутствует.

**Applied to:** 06,07,10,14,17; RISK-AND-003/006/010.

## VER-AND-005

**Reason:** CRITICAL_DETAIL

**Audit A:** 06§2.7/2.8;08 P18: HTTP clear, IO retains, room/one-time остаются.

**Audit B:** 06 Logout и session UI: retained pair может оставить periodic; verify Result ignored.

**Server Specification:** SRV-AUTH-006; server06: revoke refresh, access до exp; server10 не задаёт offline client policy.

**Question:** Когда logout действительно очищает pair, как меняет UI/background и вызывает ли login/verify общий session barrier?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt) (1–73); [app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt) (1–119).

**Verified result:** Login validates nonblank pair, VM сохраняет и reconcile. Logout требует ready и refresh, execute до clear; HTTP non2xx clear затем error; exception до clear может сохранить pair. VM reconcile после Result. Room/URL/password/one-time не очищаются. tokensFlow меняет isLoggedIn/sub; verifySession ignores Result. Не введена server обязанность offline clear.

**Applied to:** 03,07,10–12,17; RISK-AND-007–010; OPEN-AND-015.

## VER-AND-006

**Reason:** SERVER_CLIENT_MISMATCH

**Audit A:** 03§1/6;06§2.4;16 D1: common Room и pair.

**Audit B:** 03 MediaFile;16 D01: server/account switch может направлять old credentials/new scope.

**Server Specification:** SRV-AUTH-004; SRV-SYNC-002; server06 owner-scoped access.

**Question:** Какие identity/scope поля persisted и как encrypted storage связывает pair с server/account?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt) (1–23); [app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/EncryptedPrefsTokenStorage.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/EncryptedPrefsTokenStorage.kt) (1–45).

**Verified result:** 14 MediaFile fields, sole mediaStoreId PK, без account/server/folder/device/volume namespace. Одна prefs pair secure_tokens, getTokens проверяет только обе строки; AES schemes и apply/Flow видны. Storage не имеет server binding или constructor error handling. Switch/cleanup side подтверждена VER-AND-001/005. INT-AND-001 подтверждён.

**Applied to:** 03,04,07,13,14,17; RISK-AND-002/024.

## VER-AND-007

**Reason:** CONFLICT

**Audit A:** 09§4: PENDING с nonnull checksum недостижим; reason!=null при status!=FAILED исключён. 04§5 обобщает UPDATE без status check.

**Audit B:** 09 Invariants: PENDING с old hash/ID допустим, SQL не гарантирует сочетания; пример CHECKSUM_READY/null сформулирован слишком широко.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Являются ли эти hash/status/reason сочетания инвариантами и какие поля сохраняют status-only transitions?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt) (1–205); [app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt) (1–14); [app/src/main/java/ru/tbcarus/photo_cloud_client/media/FailureReason.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/FailureReason.kt) (1–20).

**Verified result:** ResetFailedForRetry сохраняет count/hash/ID и фильтрует только ID; status-only writes не очищают reason. Нет schema/expected-version guard. С concurrent restore возможен PENDING с hash (точный chain VER-AND-008). Precheck+scan пример даёт SYNCED/PENDING_UPLOAD/null, не CHECKSUM_READY/null. resetStatus действительно имеет WHERE source status, change reset — size/mtime guard. C-AND-001 разрешён; инварианты не объявляются абсолютными.

**Applied to:** 03,04,09,11; RISK-AND-011/012; reconciliation21.

## VER-AND-008

**Reason:** CONFLICT

**Audit A:** 09§5: concurrent restoreRetryableFailed без mutex, но «результат идемпотентен»;03/08 используют краткое «до3».

**Audit B:** 10 Auto recovery: stale concurrent snapshot может reset уже начатую/обработанную row;3 failures=initial+2, cooldown не timer.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Идемпотентен ли restore при overlap worker; как именно работают count/cooldown и checksum exceptions?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt) (1–148). Повторно использовано: MediaFileDao из VER-AND-007 (63–90,139–147); Worker из003.

**Verified result:** Restore вне checksum mutex: snapshot<=500 count<3/time<=now−5min, затем resets по ID. W1 и W2 могут взять row; W1 reset/hash success, W2 delayed reset→PENDING/hash сохранён. Это возможное interleaving, не runtime reproduction. Count3 — три accumulated failures, не три дополнительных retry; success count0; cooldown не schedules wake-up. FNF/Security/IO/other hashing→соответствующие FAILED, per-file продолжение. C-AND-002 разрешён.

**Applied to:** 08–11,13; RISK-AND-012/014; reconciliation21.

## VER-AND-009

**Reason:** CONFLICT

**Audit A:** 07§1 описывает loadImages: «исключение вне цикла → Error», без разграничения query/cursor catch.

**Audit B:** 07 Permissions: query вне scanner try, Exception ловит MediaFileRepository; malformed row successful partial snapshot.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Где ловится query failure, каковы scope/normalization и когда применяются stale deletes?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt) (1–139); [app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt) (1–101).

**Verified result:** Query до scanner try; его exception ловится scanImages caller и становится ScanOutcome.Error. Null cursor Error; cursor/column failures локальный Error; row errors пропускаются. Images/DCIM projection8, dates fallback, content URI подтверждены. Apply только для Success; insert→conditional reset→metadata→delete absent IDs500; mutex/общей transaction нет. C-AND-003 — разница exception boundary; конечный caller error outcome у обоих аудитов в целом совпадал.

**Applied to:** 03–05,08,09,11; RISK-AND-015/031/032.

## VER-AND-010

**Reason:** CRITICAL_DETAIL

**Audit A:** 05§4.7–4.9;08 P7;19§5–6: CAMERA absence/precheck; местами ID описан как всегда null.

**Audit B:** 08§7;19 Bootstrap/Existing: no ID, partition/no-progress, null children→absence.

**Server Specification:** SRV-FOLDER-001/002/003, SRV-SYNC-002; server05 exists и server08 bootstrap.

**Question:** Как разрешается CAMERA, что происходит при отсутствии/ошибке, и какие writes делает existing/неполная partition?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/FolderRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/FolderRepository.kt) (1–63); [app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt) (1–160). Повторно использовано: DAO status-only query из007.

**Verified result:** Root→children exact CAMERA; cache нет. Error stops precheck, absence переводит все ready с nonnull checksum в pending без exists. Batch500 rows→lowercase grouping/dedup; existing priority над missing; неизвестный hash unchanged; no-progress exits Success. Existing пишет только status, не получает/не очищает ID. Counts — local rows, unchangedTotal финального no-progress batch, не full queue. Граница между logical existing и доступностью bytes на сервере соблюдена в тексте.

**Applied to:** 03,06,08,09,17; RISK-AND-017/019/027.

## VER-AND-011

**Reason:** SERVER_CLIENT_MISMATCH

**Audit A:** 05§4.10;08 P8–P10;19 Upload: fallback null folder, positive id, transient stop.

**Audit B:** 08§8;16 N03/D02;19 target/hash: отсутствие snapshot и response comparison.

**Server Specification:** SRV-FILE-001/002/003; SRV-MEDIA-001; server05 upload/error; server07 default target по detected bytes.

**Question:** Может ли upload после CAMERA precheck уйти без target, как принимаются duplicate200/ID и как обрабатываются errors?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt) (1–183). Повторно использовано: DAO из007, FolderRepository из010.

**Verified result:** Recovery UPLOADING первым; повторный resolve once перед run: absence или Error→null folderIdPart. Batch20 DESC createdAt, MIME/preopen guards, status UPLOADING, execute.2xx id>0→markUploaded, другие response fields не читаются; duplicate200 success.400/409/413 FAILED continue; IO/invalid id/other HTTP→pending stop; generic exception escapes. INT-AND-002/003 подтверждены как условные семантические случаи.

**Applied to:** 06,08–11,17; RISK-AND-013/016/017/038.

## VER-AND-012

**Reason:** CRITICAL_DETAIL

**Audit A:** 07§5.1/6.5;14§3.1: возможный GPS stripping, SIZE vs Content-Length, BODY full-read inference.

**Audit B:** 07 Bytes;16 D02/N05: независимые reads, size>0 else−1, no immutable snapshot; GPS не развит отдельно.

**Server Specification:** SRV-MEDIA-001/002; server07 hash/size по полученным bytes и best-effort metadata.

**Question:** Какие именно bytes/length клиент читает для hash/upload, запрашивает ли оригинал и что можно утверждать без runtime?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt) (1–37); [app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumUtil.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumUtil.kt) (1–26). Повторно использовано: Hash URI open из008; upload pre-open из011; manifest permissions из014.

**Verified result:** SHA256 stream buffer8192 lowercase hex. Upload pre-open и writeTo открывают URI отдельно; writeTo каждый раз reopening, contentLength=size>0 else−1. Ни этих call paths, ни manifest не задают setRequireOriginal/ACCESS_MEDIA_LOCATION. Byte/GPS agreement не доказано; redaction/actual length/memory остаются runtime OPEN. Никакого самостоятельного client EXIF stripping не приписано.

**Applied to:** 05,08,11,14,17; RISK-AND-016/020–022; OPEN-AND-003/004/005.

## VER-AND-013

**Reason:** CRITICAL_DETAIL

**Audit A:** 00§12/J1;06 Login UI;07 permission;16 J1: модальный loader, cleartext password, no manual retry.

**Audit B:** 18 LoadingDialog descriptor;06 visible password;12 Diagnostics;16 L03/U01: permission effect и periodic invisible.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Когда loader блокирует экран, маскируется ли пароль, что именно запускается после grant/already granted?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt) (36–88); [app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt) (20–100); [app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/LoadingDialog.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/LoadingDialog.kt) (1–36); [app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt) (94–124).

**Verified result:** Files loader isScanning||isSyncing; Dialog empty onDismissRequest. Auth LOADING/isVerifying тоже loader; password plain OutlinedTextField. Files LaunchedEffect(Unit) только при configured; granted→reconcile без scan; grant callback→reconcile+scan, denial→scan. Manual-control отсутствие взято из согласованных inventory A/B, остальной Files UI повторно не читался.

**Applied to:** 07,12,14,16; RISK-AND-005/028/034.

## VER-AND-014

**Reason:** CRITICAL_DETAIL

**Audit A:** 06 Security;11 manifest;14 logging: BODY all builds, network domains, backup templates.

**Audit B:** 06/14: тот же secret-body risk, аккуратнее о реальном Authorization/binary output.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Каковы фактические logging, transport, media permissions и backup declarations без вывода неизвестного library/device runtime?

**Android code inspected:** [app/src/main/AndroidManifest.xml](../../../app/src/main/AndroidManifest.xml) (1–44); [app/src/main/res/xml/network_security_config.xml](../../../app/src/main/res/xml/network_security_config.xml) (1–7); [app/src/main/res/xml/backup_rules.xml](../../../app/src/main/res/xml/backup_rules.xml) (1–13); [app/src/main/res/xml/data_extraction_rules.xml](../../../app/src/main/res/xml/data_extraction_rules.xml) (1–19). Повторно использовано: NetworkModule1–53 уже открыт для004; encrypted storage1–45 для006.

**Verified result:** BODY обоих clients без guard/redaction; logging-before-auth. Manifest HTTP flag+network config, explicit domains emulator/localhost, no base-config; allowBackup=true, templates без действующих exclusions; INTERNET/images/legacy permission, location/video/selected отсутствуют. Весь Startup provider removed. Не проверялся merged manifest, library body buffering, policy на LAN/OEM backup.

**Applied to:** 02,10,13,14; RISK-AND-003/004/020/022–024.

## VER-AND-015

**Reason:** AMBIGUOUS

**Audit A:** 04§4: current migrations плюс historical dev variants и категоричное inferred downgrade behaviour.

**Audit B:** 04: current schema/migrations, defaults различаются; runtime migration validation не выполнялась.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Что текущая DB конфигурация реально задаёт и какие последствия для current migrated fields устанавливаются без нового migration audit?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt) (1–73). Повторно использовано: MediaFile007/006 и retry selector007/008; schemas и historical commits не открывались.

**Verified result:** DB3, one entity, exportSchema, enums name/valueOf, migrations1→2→3, destructive fallback(dropAllTables=true).1→2 adds mtime0; next scan resets если differs.2→3 old FAILED получает null reason/time и не eligible. Runtime downgrade/open validation и распространённость старых DB не выводятся из read этого файла; OPEN-AND-008. Fresh-schema defaults/indexes взяты из consensus A/B04, не новый schema audit.

**Applied to:** 04,09,11,13; RISK-AND-029/030; OPEN-AND-008.

## VER-AND-016

**Reason:** B_ONLY

**Audit A:** 11§1 port nonempty;05 exception paths описаны общо, риск factory-before-try не раскрыт.

**Audit B:** 08§2;16 N02: malformed port→factory exception до Result, UI может не завершить loading.

**Server Specification:** Не требуется: вопрос о client behaviour.

**Question:** Может ли ошибка URL construction миновать штатную Network Result обработку?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt) (1–37). Повторно использовано: NetworkViewModel49–87 из001; ApiServiceFactory21–26,46–48 из018.

**Verified result:** plainTestService вызывается до IO/try; VM заранее ставит isLoading и не имеет внешнего catch/finally вокруг repo call. Port лишь nonempty. Factory URL exception не превращается этим repository в Result.failure и может оставить UI loading; конкретный invalid-input runtime не запускался.

**Applied to:** 06,12,13; RISK-AND-033.

## VER-AND-017

**Reason:** SERVER_CLIENT_MISMATCH

**Audit A:** 05§5/19 DTO: три error formats, в том числе fixed Spring JSON500, представлены как handoff contract.

**Audit B:** 05/10/19: parser/raw fallback и отсутствие uniform framework JSON; тоже есть historical shape examples.

**Server Specification:** server10 Границы единого формата; server05 Framework surface OPEN-SRV-030.

**Question:** Зависит ли Android от фиксированного framework error JSON и какие данные parser реально сохраняет/возвращает?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt) (1–40).

**Verified result:** Read body catch→null; blank→HTTP N; Gson JsonSyntaxException→raw; dto null/unknown keys→HTTP N; fieldErrors→nonblank message→nonblank code. UUID не возвращается. Универсальная зависимость от fixed Spring shape не установлена; этот candidate mismatch снят. Другие exceptions не превращаются магически в любой гарантированный parser success; server framework body остаётся OPEN в Server Specification.

**Applied to:** 06,11,14,17; RISK-AND-035; OPEN-AND-010.

## VER-AND-018

**Reason:** CRITICAL_DETAIL

**Audit A:** 05 inventory/19 multipart;17 V10 оставляет text/plain folder binding runtime вопросом.

**Audit B:** 05/19 method/path/wire inventory: text/plain folderId shape согласован,10 calls.

**Server Specification:** server05 все десять используемых mappings и optional folderId multipart/request param; SRV-API-001.

**Question:** Совпадают ли используемые method/path, выбор clients и multipart parts с Server API, без чтения остальных API/DTO catalogs заново?

**Android code inspected:** [app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt) (1–62); [app/src/main/java/ru/tbcarus/photo_cloud_client/api/ApiPaths.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/api/ApiPaths.kt) (1–21); [app/src/main/java/ru/tbcarus/photo_cloud_client/api/FileService.java](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/api/FileService.java) (1–28); [app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java) (1–27); [app/src/main/java/ru/tbcarus/photo_cloud_client/api/TestService.java](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/api/TestService.java) (1–13); [app/src/main/java/ru/tbcarus/photo_cloud_client/api/FolderService.java](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/api/FolderService.java) (1–16).

**Verified result:** Десять method/path mappings соответствуют Server Specification; factory разделяет plain/auth; refresh отдельный plain из004. Upload file + optional text/plain folderId, no extra checksum/status/date parts; Java nullable part передаётся из011. DTO fields взяты из A/B consensus19; malformed/deployed binding response не выдаётся за live verification. Candidate normal multipart mismatch снят.

**Applied to:** 06,17,16; все API rows; OPEN-AND-010 только deployment/edge runtime.
