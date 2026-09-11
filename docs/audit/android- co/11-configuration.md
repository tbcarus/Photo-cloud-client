# 11. Configuration

## Build и зависимости

[CONFIRMED] [app/build.gradle.kts](<C:/projects/Photocloudclient/app/build.gradle.kts>) — фактически подключённые dependencies; значения version catalog не следует подменять ими. Один module :app, namespace/applicationId ru.tbcarus.photo_cloud_client, versionCode1/versionName1.0, compileSdk35,targetSdk35,minSdk29, Java/Kotlin target17, Compose enabled, release minify=false, custom flavors/buildConfigField не найдены.

| Setting | Source | Default / build | Use |
| --- | --- | --- | --- |
| [CONFIRMED] AGP / Kotlin | root plugins + libs.versions.toml |8.9.1 /2.0.21 | Android/Kotlin build |
| [CONFIRMED] Gradle wrapper | gradle-wrapper.properties |8.11.1 | Build runtime |
| [CONFIRMED] KSP / Hilt plugin | root build.gradle.kts |2.0.21-1.0.28 /2.52 | Room/Hilt generation |
| [CONFIRMED] Compose plugin | app plugins |2.0.21 | Kotlin Compose compiler plugin |
| [CONFIRMED] Compose artifacts | app dependencies | UI1.6.7, Material3 1.2.1, preview/tooling1.6.6, compiler artifact1.5.12, icons1.7.8 | Разнородные прямые версии |
| [CONFIRMED] Activity / navigation | app dependencies |1.9.0 /2.7.7 | Compose activity/nav |
| [CONFIRMED] Lifecycle | app dependencies | runtime2.8.1, viewmodel-compose2.7.0 | ViewModels/lifecycle |
| [CONFIRMED] Retrofit/Gson converter | app dependencies |2.11.0 | HTTP DTO |
| [CONFIRMED] OkHttp/logging / Gson | app dependencies |4.12.0 /2.10.1 | Transport/serialization |
| [CONFIRMED] Coroutines | app dependencies |1.8.0 | Android coroutines |
| [CONFIRMED] Hilt / AndroidX Hilt | app dependencies |2.52 /1.2.0 | DI + navigation/work |
| [CONFIRMED] Room | app dependencies |2.7.0 | persistence + KSP |
| [CONFIRMED] WorkManager | app dependencies |2.9.0 | background |
| [CONFIRMED] DataStore / security-crypto | app dependencies |1.1.1 /1.1.0-alpha06 | settings/tokens |
| [CONFIRMED] Core KTX | app dependencies |1.13.1 | Android helpers |
| [CONFIRMED] Test libs | app dependencies | JUnit4.13.2, ext junit1.1.5, Espresso3.5.1, Compose test1.6.6 | Только 2 template tests |
| [CONFIRMED] JVM heap | gradle.properties | -Xmx2048m / UTF-8 | Gradle daemon |
| [CONFIRMED] Android flags | gradle.properties | useAndroidX=true, nonTransitiveRClass=true | Build |
| [UNUSED] Catalog libraries/BOM | libs.versions.toml | Более новые core/activity/lifecycle и Compose BOM2024.09.00 | app dependencies их aliases не используют; plugin aliases используются |

[PARTIAL] Смешанные версии и alpha crypto — факт конфигурации, не доказанная incompatibility/vulnerability. Dependency resolution/build не выполнялись. Исходные .idea настройки и local.properties не являются shared production runtime configuration; machine-local SDK путь не включён в отчёт.

## Runtime configuration

| Setting | Source | Default | Environment/build variant | Use |
| --- | --- | --- | --- | --- |
| [CONFIRMED] Server URL | BaseUrlProvider + NetworkViewModel | Empty, затем http://IP:port/ | Одинаково | Все services; headless bootstrap отсутствует |
| [CONFIRMED] IP/port | ServerPreferences DataStore settings | null persisted / "" UI | Одна пара на install | Сохраняется после успешного Test |
| [CONFIRMED] IPv4 validation | isValidIpAddress | regex | Одинаково | Hostname/IPv6 UI не принимает; port лишь nonempty |
| [CONFIRMED] Plain timeouts | NetworkModule | connect/read/write5s | Все builds | Public ping/auth/refresh |
| [CONFIRMED] Auth timeouts | NetworkModule | connect10/read60/write60s | Все builds | Upload/folders/protected ping/logout |
| [CONFIRMED] Logging | NetworkModule | BODY | Без debug guard | Оба clients |
| [CONFIRMED] HTTP permission | Manifest/XML | usesCleartextTraffic=true, explicit domains10.0.2.2/localhost | Нет build overlay | Effective arbitrary host policy не проверена |
| [CONFIRMED] API prefix | ApiPaths | api/v1 | Одинаково |10 endpoints |
| [CONFIRMED] DB | AppDatabase | photo_cloud.db v3 | Одинаково | Migrations + destructive fallback |
| [CONFIRMED] Scan scope | MediaStoreRepository | Images / DCIM/% | API29+ | Полный snapshot |
| [CONFIRMED] Permission | UI/repositories/coordinator | legacy до32, images с33 | SDK branch | Read-only image access |
| [CONFIRMED] Hash buffer / batch | ChecksumUtil/Repository |8192 bytes /100 rows | Одинаково | SHA256 |
| [CONFIRMED] Failed retry | ChecksumRepository | maxCount3,cooldown300000ms,restore500 | Одинаково | Только CHECKSUM_IO |
| [CONFIRMED] Precheck / SQL chunks | Precheck repository |500 /500 | Одинаково | API≤500 hashes и SQL IN |
| [CONFIRMED] Stale delete chunk | MediaFileRepository |500 | Одинаково | Room DELETE |
| [CONFIRMED] Upload batch | FileUploadRepository |20 | Одинаково | Последовательная обработка; не20 parallel requests |
| [CONFIRMED] Target | FolderRepository | folderType CAMERA | Одинаково | Root direct child lookup, no cache |
| [CONFIRMED] Multipart | FileUploadRepository | file; folderId optional text/plain | Одинаково | Filename из displayName |
| [CONFIRMED] Worker names | SyncScheduler | photo_sync /photo_sync_periodic | Одна install | Separate unique namespaces |
| [CONFIRMED] One-time constraints | SyncScheduler | CONNECTED,KEEP | Одинаково | Observer event |
| [CONFIRMED] Periodic constraints | SyncScheduler |1hour,CONNECTED,batteryNotLow,UPDATE | Одинаково | Auto fallback |
| [CONFIRMED] Observer debounce | MediaChangeObserver |3000ms | Process memory | Схлопывает Images notifications |
| [CONFIRMED] UI theme | Theme.kt | system dark + dynamicColor=true сAPI31 | Runtime SDK/theme | Остальные hardcoded цвета могут перекрывать theme |
| [CONFIRMED] Backup | Manifest/templates | allowBackup=true, exclusions отсутствуют | Все builds | Platform backup configuration |

Источники: [NetworkModule.kt:19](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:19>), [NetworkViewModel.kt:31](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:31>), [ServerPreferences.kt:13](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerPreferences.kt:13>), [ChecksumRepository.kt:28](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:28>), [SyncScheduler.kt:29](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:29>), [MediaChangeObserver.kt:33](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt:33>).

## Что не конфигурируется

[CONFIRMED] Нет sync enable/disable switch, Wi-Fi-only option (CONNECTED допускает metered), charging/idle/storage constraints, max file size guard100MiB, timeout per file, batch tuning UI, retry/backoff settings, upload parallelism, folder selection, host TLS selector, production/debug server variants, quotas и feature flags.

[DOCUMENTED] Server handoff задаёт max500 checksums и100MiB upload; первое клиент соблюдает batch code, второе узнаёт только через413. SettingsScreen не управляет ни одним из параметров: centered text. [SettingsScreen.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/SettingsScreen.kt:7>).

