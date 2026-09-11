# 16. Known gaps and risks

Severity: **CRITICAL** — возможная экспозиция credentials/передача данных не тому security scope; **HIGH** — потеря согласованности/невыполнение backup/recovery; **MEDIUM** — частичные сбои/неполный UX/масштабирование; **LOW** — локальная неоднозначность/поддерживаемость. Это оценка последствий, не утверждение о воспроизведённом инциденте. [RISK] использован для условных failure scenarios; отсутствие кода подтверждено статически. Redesign/fixes в рамках аудита не выполнялись.

## Functional gaps

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [PARTIAL] F01 Нет server list/download/remote changes | Только2 file calls [FileService.java:14](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/FileService.java:14>) | Клиент не видит remote deletion/move, gallery browsing отсутствует | HIGH |
| [PARTIAL] F02 Только Images DCIM | [MediaStoreRepository.kt:58](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:58>), [FileUploadRepository.kt:80](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:80>) | Video и изображения вне DCIM не backed up | MEDIUM |
| [PARTIAL] F03 Settings/Profile destinations stubs | [SettingsScreen.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/SettingsScreen.kt:7>), [ProfileScreen.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/ProfileScreen.kt:7>) | Настроить sync/получить server profile нельзя | MEDIUM |
| [PARTIAL] F04 Нет manual retry/permission recovery controls | [FilesScreen.kt:94](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:94>) | Permanent/исчерпанные FAILED не восстановить из UI | HIGH |

## Architecture

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [RISK] A01 Нет общей atomic pipeline boundary | Stage Mutex, scan no lock [ChecksumRepository.kt:26](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:26>), [MediaFileRepository.kt:36](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:36>) | Межстадийные writes перетирают результаты | HIGH |
| [PARTIAL] A02 Entity/UiState coupling | [FilesUiState.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesUiState.kt:8>), [AuthUiState.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthUiState.kt:3>) | Изменение persistence/UI затрагивает несколько ролей; tests сложнее изолировать | LOW |
| [RISK] A03 Неполная связь state stores | [PeriodicSyncCoordinator.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt:38>), [AuthViewModel.kt:26](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:26>) | UI logout и background schedule расходятся | HIGH |

## Android lifecycle

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [RISK] L01 Headless process без URL | [App.kt:10](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/App.kt:10>), [BaseUrlProvider.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/BaseUrlProvider.kt:8>), [NetworkViewModel.kt:31](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:31>), [SyncWorker.kt:50](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:50>) | Worker после process death/reboot retry до foreground restore | HIGH |
| [RISK] L02 Cancellation swallowed / blocking IO | [SyncWorker.kt:39](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:39>), [ChecksumRepository.kt:81](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:81>), [FileUploadRepository.kt:99](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:99>) | Cancel не гарантирует немедленное прекращение IO/side effects | HIGH |
| [PARTIAL] L03 LaunchedEffect(Unit) и отсутствие onResume recheck | [FilesScreen.kt:73](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:73>) | Изменённые разрешения/конфигурация могут не отразиться до reentry | MEDIUM |

## Data consistency

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [RISK] D01 Общая DB/token pair для server/account | [MediaFile.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt:7>), [AuthRepository.kt:55](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt:55>), [NetworkViewModel.kt:68](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:68>) | SYNCED старого account скрывает missing backup нового; pending могут уйти под новой session; credentials на новый host | CRITICAL |
| [RISK] D02 Hash/bytes TOCTOU | [FileUploadRepository.kt:111](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:111>), [ContentUriRequestBody.kt:28](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt:28>) | Stored hash/precheck не соответствуют реально загруженному содержимому | HIGH |
| [PARTIAL] D03 Existing hash→SYNCED без ID | [ChecksumPrecheckRepository.kt:122](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:122>) | Remote object identity недоступна; list-sync отсутствует | MEDIUM |
| [RISK] D04 Destructive DB fallback | [AppDatabase.kt:65](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt:65>) | Unsupported migration path может удалить весь local index/queue | HIGH |
| [RISK] D05 SQL updates без expected version/affected rows | [MediaFileDao.kt:26](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:26>), [MediaFileDao.kt:124](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:124>) | Deleted/modified row во время request не обнаруживается | HIGH |
| [PARTIAL] D06 Исторические FAILED без reason | [AppDatabase.kt:47](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt:47>), [MediaFileDao.kt:63](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:63>) | Auto recovery таких rows не выберет | HIGH |

## MediaStore

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [RISK] M01 Visibility interpreted as deletion | Row skip [MediaStoreRepository.kt:128](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:128>) + stale delete [MediaFileRepository.kt:64](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:64>) | Индекс теряет невидимые/непрочитанные items и failure history | HIGH |
| [RISK] M02 Только size/mtime change detection | [MediaFileDao.kt:163](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:163>) | Same-size/mtime content change пропускается | HIGH |
| [RISK] M03 Полный snapshot/N updates | [MediaFileRepository.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:38>), [MediaStoreRepository.kt:80](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:80>) | RAM/DB IO растут с библиотекой, нет performance evidence | MEDIUM |
| [PARTIAL] M04 Ограниченные permissions model | [FilesScreen.kt:46](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:46>) | Selected access/revoke UX и корректность snapshots не подтверждены | HIGH |

## WorkManager

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [RISK] W01 Два independent unique names | [SyncScheduler.kt:29](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:29>) | One-time и periodic могут overlap stages | HIGH |
| [RISK] W02 Потеря event follow-up | KEEP [SyncScheduler.kt:45](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:45>), observer [MediaChangeObserver.kt:67](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt:67>) | Изменение после scan может ждать periodic | MEDIUM |
| [RISK] W03 Logout не cancels one-time | [SyncScheduler.kt:77](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:77>) | Ранее поставленный worker продолжает lifecycle | HIGH |
| [PARTIAL] W04 Нет foreground/progress/cancellation handling для длинного прогона | [SyncWorker.kt:36](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:36>) | Большие очереди уязвимы к interruption; выполнение всего списка не гарантируется | MEDIUM |
| [INCONSISTENCY] W05 SUCCESS при failed/unchanged | [SyncWorker.kt:99](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:99>) | Итог card вводит в заблуждение относительно полноты backup | HIGH |

## Networking

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [RISK] N01 HTTP-only URL | [NetworkViewModel.kt:60](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:60>), [network_security_config.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/network_security_config.xml>) | При разрешённом cleartext credentials/media передаются без TLS; произвольный LAN host может также блокироваться OS policy | HIGH |
| [RISK] N02 Port лишь nonempty, factory вне try | [NetworkViewModel.kt:53](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:53>), [ServerRepository.kt:16](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt:16>) | Invalid URL exception может покинуть coroutine без корректного UI completion | MEDIUM |
| [RISK] N03 Fallback target после lookup error | [FileUploadRepository.kt:60](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:60>) | Default server routing может отличаться от precheck CAMERA при misdetected MIME/смене контекста | HIGH |
| [PARTIAL] N04 Нет local max-size check | [FileUploadRepository.kt:164](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:164>) | Oversized фото читается/передаётся до413, permanent failure | MEDIUM |
| [RISK] N05 BODY logger при streaming upload | [NetworkModule.kt:21](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:21>) | Возможная буферизация/дополнительное чтение bytes библиотекой; RAM не измерена | MEDIUM |

## Auth

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [RISK] S01 Secret bodies in all-build BODY logging | [NetworkModule.kt:21](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:21>), [AuthRequest.java:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/AuthRequest.java:3>) | Пароль/access/refresh доступны logs; encryption at rest это не закрывает | CRITICAL |
| [INCONSISTENCY] S02 Logout IO оставляет tokens | [AuthRepository.kt:55](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt:55>), [AuthViewModel.kt:115](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:115>) | Пользователь видит logout error, session/background могут остаться активны вопреки комментарию | HIGH |
| [RISK] S03 Refresh любое non2xx clear | [TokenAuthenticator.kt:62](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt:62>) | Transient server failure принудительно теряет session | HIGH |
| [RISK] S04 Refresh lock не session lock | [TokenAuthenticator.kt:37](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt:37>), [EncryptedPrefsTokenStorage.kt:34](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/EncryptedPrefsTokenStorage.kt:34>) | Refresh может восстановить old session после logout/нового login | HIGH |
| [PARTIAL] S05 Password visible/retained | [AuthScreen.kt:74](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt:74>), [AuthUiState.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthUiState.kt:7>) | Раскрытие при просмотре экрана; строка остаётся в VM | MEDIUM |
| [RISK] S06 Backup без exclusions/restore recovery | [backup_rules.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/backup_rules.xml>), [data_extraction_rules.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/data_extraction_rules.xml>) | Поведение переноса encrypted prefs/Room не контролируется приложением | MEDIUM |
| [PARTIAL] S07 VerifyResult ignored / nonnull tokens=loggedIn | [AuthViewModel.kt:94](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:94>) | UI может выглядеть authenticated после неуспешной проверки | MEDIUM |

## Error recovery

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [RISK] E01 All other HTTP→unbounded retry | [FileUploadRepository.kt:176](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:176>) | Persistent401/403/404/500 не устраняются, повтор/блокирование очереди | HIGH |
| [PARTIAL] E02 Grant не восстанавливает PERMISSION failed | [ChecksumRepository.kt:47](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:47>), [MediaFileDao.kt:163](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:163>) | Неизменные files остаются FAILED после возврата доступа | HIGH |
| [PARTIAL] E03 Cooldown не wake-up timer | [SyncWorker.kt:59](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:59>), [ChecksumRepository.kt:45](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:45>) | CHECKSUM_IO может ждать час/событие; «5min retry» не SLA | MEDIUM |
| [RISK] E04 Newest transient blocks queue | [FileUploadRepository.kt:73](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:73>), [MediaFileDao.kt:139](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:139>) | Старые pending не обрабатываются до успешного нового item | HIGH |
| [PARTIAL] E05 Error ID/code/details discarded | [ApiErrorParser.kt:29](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt:29>) | Сложно связать failure с server log и выбрать правильное восстановление | MEDIUM |

## UX

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [INCONSISTENCY] U01 Periodic running не observable UI | [SyncScheduler.kt:87](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:87>) | Sync идёт без соответствующего loader | MEDIUM |
| [INCONSISTENCY] U02 Permission card «следующий этап» | [FilesScreen.kt:174](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:174>) | Неверная инструкция при actual runtime launcher | LOW |
| [PARTIAL] U03 Нет preview/progress/retry details | [FilesScreen.kt:239](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:239>) | Пользователь видит только текстовую очередь | MEDIUM |
| [PARTIAL] U04 Navigation без popUpTo/saveState | [BottomNavigationBar.kt:29](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/BottomNavigationBar.kt:29>) | Повторные tab entries/back stack могут накапливаться | LOW |
| [PARTIAL] U05 Empty local list обещает auto sync без config context | [FilesScreen.kt:146](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:146>) | Нет guidance configure/login/permission | MEDIUM |

## Maintainability

| ID / проблема | Доказательство | Consequence | Severity |
| --- | --- | --- | --- |
| [PARTIAL] T01 Только template tests | [ExampleUnitTest.kt:13](<C:/projects/Photocloudclient/app/src/test/java/ru/tbcarus/photo_cloud_client/ExampleUnitTest.kt:13>), [ExampleInstrumentedTest.kt:18](<C:/projects/Photocloudclient/app/src/androidTest/java/ru/tbcarus/photo_cloud_client/ExampleInstrumentedTest.kt:18>) | Ни один риск pipeline/auth/DB не защищён продуктовым regression coverage | HIGH |
| [INCONSISTENCY] T02 Устаревшие TODO | [MediaFileRepository.kt:45](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:45>), [ChecksumRepository.kt:70](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:70>), [FilesScreen.kt:131](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:131>) | Комментарии не отражают уже реализованные стадии, аудит по ним ошибочен | LOW |
| [UNUSED] T03 Дополнительный app/AndroidManifest.xml | [файл](<C:/projects/Photocloudclient/app/AndroidManifest.xml>), sourceSets override отсутствует | Похожий неактивный manifest может запутать сопровождение | LOW |
| [PARTIAL] T04 Разрозненные dependency versions | [app Gradle](<C:/projects/Photocloudclient/app/build.gradle.kts>), [libs.versions.toml](<C:/projects/Photocloudclient/gradle/libs.versions.toml>) | Catalog не описывает фактически подключённые app libraries | LOW |

[RISK] Все последствия concurrency/cold-start/cancellation описаны как воспроизводимые по предпосылкам code-path hypotheses; emulator trace и server-side execution для них не получены. Проблемы не исправлялись.
