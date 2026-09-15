# Configuration

## Build

| Параметр | Значение |
| --- | --- |
| Module / namespace / applicationId | :app / ru.tbcarus.photo_cloud_client |
| Version | versionCode 1, versionName 1.0 |
| SDK / JVM | min 29, target 35, compile 35; Java/Kotlin target 17 |
| Gradle / AGP | 8.11.1 / 8.9.1 |
| Kotlin / KSP | 2.0.21 / 2.0.21-1.0.28 |
| UI | Compose enabled; UI 1.6.7, Material3 1.2.1, Navigation 2.7.7 |
| DI | Hilt 2.52; AndroidX Hilt 1.2.0 |
| Persistence | Room 2.7.0; DataStore 1.1.1; security-crypto 1.1.0-alpha06 |
| Networking | Retrofit/converter 2.11.0; OkHttp/logging 4.12.0; Gson 2.10.1 |
| Background / coroutines | WorkManager 2.9.0; coroutines 1.8.0 |
| Release | minify=false; custom signing/flavors/server build constants не заданы |
| Schema export | KSP app/schemas; Room version3 |

Это объявленные зависимости исходного среза, а не результат dependency resolution данной работы. App libraries заданы прямыми версиями, включая разнородные Compose/lifecycle artifacts; library aliases/BOM каталога не определяют фактически подключённый набор. Version catalog plugin aliases используются.

## Runtime persistence и URL

| Объект | Имя / default / применение |
| --- | --- |
| DataStore | settings, string keys ip/port; default отсутствует |
| BaseUrlProvider | Mutable String, default empty; UI строит http://IP:port/ |
| Encrypted preferences | secure_tokens; accessToken/refreshToken; одна пара |
| Room | photo_cloud.db / media_files / v3 |
| SyncStatusStore | In-memory, начально null |

IP/port сохраняются после successful public test. Init NetworkViewModel восстанавливает URL из preferences до проверки связи. Worker/Authenticator читают BaseUrlProvider; headless restore отсутствует. Hostname/IPv6/HTTPS и context-path пользователь не задаёт. Port numeric/range validation отсутствует.

Секретные значения credentials/tokens или machine-local SDK settings в пакет не включены.

## Timing и batch constants

| Параметр | Значение / scope |
| --- | --- |
| plain connect/read/write | 5/5/5 s |
| auth connect/read/write | 10/60/60 s |
| Auth replay | responseCount>=2 прекращает replay |
| Checksum batch/buffer | 100 rows / 8192 bytes |
| Pre-check batch / SQL status chunks | 500 rows / 500 IDs |
| Stale DELETE chunks | 500 IDs |
| Upload batch | 20 rows, последовательные requests |
| CHECKSUM_IO max count | 3 accumulated failures |
| Cooldown | 300000 ms wall-clock eligibility |
| Restore batch | До 500 rows один раз за worker |
| Observer debounce | 3000 ms |
| One-time | photo_sync, KEEP, CONNECTED |
| Periodic | photo_sync_periodic, UPDATE, 1 hour, CONNECTED+batteryNotLow |
| Backoff | Custom criteria/limit отсутствуют; library scheduling |
| Scan | Images external URI; RELATIVE_PATH LIKE DCIM/% |
| Upload target | Exact folderType CAMERA lookup; omitted folderId fallback |
| HTTP logger | BODY обоих clients, все builds |

Максимум файла локально не проверяется; размерный отказ узнаётся из response. Значение 100 MiB — серверный service limit, а не Android constant.

## Runtime configuration

Source manifest задаёт INTERNET, READ_MEDIA_IMAGES и READ_EXTERNAL_STORAGE maxSdk32, allowBackup=true, networkSecurityConfig и usesCleartextTraffic=true. XML явно разрешает cleartext для 10.0.2.2/localhost; base-config нет. Backup XML не задают действующих include/exclude.

Permission-dependent поведение: legacy до API32, images с API33; без permission coordinator отменяет periodic/observer при reconcile, scan/hash возвращают PermissionDenied. Отдельной selected-media/location/video модели нет.

SettingsScreen не управляет ни одним параметром. Sync toggle, Wi-Fi-only, charging, interval, retry, folder/media selection, max-size и log-level controls отсутствуют.
