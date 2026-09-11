# 11. Configuration

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Пути — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`. Метки — см. [00-executive-summary.md](00-executive-summary.md).

---

## 1. Адрес сервера

| Параметр | Источник | Default | Использование |
| --- | --- | --- | --- |
| IP | DataStore `settings`, ключ `ip` (`core/server/ServerPreferences.kt:13, 19`), ввод на Network | нет | `baseUrl` |
| Порт | DataStore `settings`, ключ `port` (`:20`) | нет | `baseUrl` |
| `baseUrl` | `BaseUrlProvider.baseUrl` в памяти (`di/BaseUrlProvider.kt:8`) | `""` | все Retrofit-сервисы, `TokenAuthenticator`, `SyncWorker` |
| Формат | `"http://$ip:$port/"` (`NetworkViewModel.kt:36, 60`) | — | **только `http`**, только IPv4 (`utils/isValidIpAddress.kt`), без hostname и path-префикса |
| Валидация порта | `port.isEmpty()` | — | числовой диапазон не проверяется |
| Сохранение | только после успешного `GET /api/v1/test` (`NetworkViewModel.kt:69`) | — | — |
| Восстановление | `NetworkViewModel.init` (`:31-43`) | — | **[RISK]** только при открытии UI |

**[CONFIRMED]** Адреса по умолчанию, `BuildConfig`-поля и адресов по build type нет.

## 2. Сборка и варианты

| Параметр | Значение | Источник |
| --- | --- | --- |
| `applicationId` / `namespace` | `ru.tbcarus.photo_cloud_client` | `app/build.gradle.kts:10, 14` |
| `versionCode` / `versionName` | `1` / `"1.0"` | `:17-18` |
| `minSdk` / `targetSdk` / `compileSdk` | 29 / 35 / 35 | `:12, 15-16` |
| Java / JVM target | 17 | `:33-40` |
| Build types | `debug` (по умолчанию), `release` (`isMinifyEnabled = false`, proguard-файлы подключены, но правил нет) | `:23-31`, `proguard-rules.pro` |
| Product flavors | нет | — |
| `buildFeatures` | `compose = true`; `buildConfig` не включён (AGP 8: `BuildConfig` не генерируется) | `:42-44` |
| Room schema location | `$projectDir/schemas` (KSP arg) | `:101-103` |
| Signing config | не описан | — |
| Каталог версий | `gradle/libs.versions.toml` — используется только для плагинов; версии библиотек в нём расходятся с фактическими | см. 02 §2 |

## 3. Сетевые таймауты

| Клиент | connect | read | write | call | Источник |
| --- | --- | --- | --- | --- | --- |
| `plain` | 5 s | 5 s | 5 s | не задан | `di/NetworkModule.kt:27-33` |
| `auth` | 10 s | 60 s | 60 s | не задан | `di/NetworkModule.kt:40-52` |

**[INFERRED]** Refresh (plain, 5 с) выполняется внутри вызова auth-клиента и блокирует его поток.

## 4. Прочие сетевые настройки

| Настройка | Значение | Источник |
| --- | --- | --- |
| `INTERNET` | объявлено | `AndroidManifest.xml:5` |
| `usesCleartextTraffic` | `true` | `AndroidManifest.xml:22` |
| Network security config | cleartext разрешён для `10.0.2.2`, `localhost` (+ поддомены); `base-config` нет | `res/xml/network_security_config.xml` |
| TLS / pinning | нет | — |
| Логирование | `HttpLoggingInterceptor.Level.BODY`, оба клиента, все сборки | `di/NetworkModule.kt:21-22` |
| Converter | Gson по умолчанию | `ApiServiceFactory.kt:25` |
| Пути API | `ApiPaths` (`api/v1/...`) | `api/ApiPaths.kt` |

## 5. WorkManager

| Параметр | One-time | Periodic | Источник |
| --- | --- | --- | --- |
| Unique name | `photo_sync` | `photo_sync_periodic` | `media/SyncScheduler.kt:30-31` |
| Policy | `ExistingWorkPolicy.KEEP` | `ExistingPeriodicWorkPolicy.UPDATE` | `:45-49`, `:70-74` |
| Интервал | — | 1 час (`PERIODIC_INTERVAL_HOURS`), flex не задан | `:32, 63-65` |
| Constraints | `NetworkType.CONNECTED` | `CONNECTED` + `requiresBatteryNotLow` | `:36-38`, `:58-61` |
| Initial delay | нет | нет | — |
| Backoff | не задан → **[INFERRED]** default экспоненциальный, 30 с, максимум 5 ч | то же | — |
| Expedited / foreground | нет | нет | — |
| Tags / input data | нет | нет | — |
| Worker factory | `HiltWorkerFactory` через `Configuration.Provider` | — | `App.kt:12-19` |
| Автоинициализация | `InitializationProvider` удалён целиком | — | `AndroidManifest.xml:37-41` |

## 6. Размеры батчей и лимиты

| Константа | Значение | Где | Назначение |
| --- | --- | --- | --- |
| `CHECKSUM_BATCH_SIZE` | 100 | `ChecksumRepository.kt:29` | выборка `PENDING` для hashing |
| `BUFFER_SIZE` | 8 KiB | `ChecksumUtil.kt:10` | буфер SHA-256 |
| `PRECHECK_BATCH_SIZE` | 500 | `ChecksumPrecheckRepository.kt:34` | = серверный лимит `checksums/exists` |
| `SQLITE_UPDATE_CHUNK_SIZE` | 500 | `ChecksumPrecheckRepository.kt:35` | лимит bind-параметров |
| stale-delete chunk | 500 (литерал) | `MediaFileRepository.kt:71` | лимит bind-параметров |
| `UPLOAD_BATCH_SIZE` | 20 | `FileUploadRepository.kt:41` | выборка `PENDING_UPLOAD` |
| `RETRY_RESTORE_BATCH_SIZE` | 500 | `ChecksumRepository.kt:35` | максимум восстановлений за прогон |
| Лимит размера файла | **нет** | — | серверный лимит 100 MiB клиентом не учитывается |

## 7. Retry и тайминги

| Константа | Значение | Где |
| --- | --- | --- |
| `MAX_AUTO_RETRY_COUNT` | 3 | `ChecksumRepository.kt:33` |
| `RETRY_COOLDOWN_MS` | 5 мин | `ChecksumRepository.kt:34` |
| `DEBOUNCE_MS` (observer) | 3 с | `MediaChangeObserver.kt:35` |
| Повтор запроса после refresh | 1 (`responseCount >= 2`) | `TokenAuthenticator.kt:35` |

## 8. Настройки скана

| Параметр | Значение | Где |
| --- | --- | --- |
| Коллекция | `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` | `MediaStoreRepository.kt:62` |
| Фильтр | `RELATIVE_PATH LIKE 'DCIM/%'` | `MediaStoreRepository.kt:58-59` |
| Типы | только изображения; guard upload `image/*` | `FileUploadRepository.kt:80` |
| Инкрементальность | нет — полный скан каждый прогон | — |
| Настраиваемость пользователем | нет | — |

## 9. Параметры upload

| Параметр | Значение |
| --- | --- |
| Endpoint | `POST api/v1/files/upload` (`ApiPaths.FILES_UPLOAD`) |
| Часть файла | `name="file"`, `filename=displayName` (fallback `file_<mediaStoreId>`), `Content-Type` = MediaStore MIME |
| Длина тела | MediaStore `SIZE`; `0` → `-1` (chunked) (`ContentUriRequestBody.kt:26`) |
| `folderId` | id `CAMERA` (`text/plain`), при неизвестной папке не отправляется |
| Целевая папка | только `CAMERA`, не настраивается |

## 10. Отладочные флаги

**[CONFIRMED]** Debug-флагов нет. Отладочные элементы присутствуют в production-UI:
- кнопки «Test» (Network) и «Test Auth» (Login);
- диалог с сообщением сервера при каждом старте;
- «Scan result: …» и сырые счётчики статусов на Files.

Логирование HTTP `BODY` не зависит от build type.

## 11. DI-конфигурация

| Модуль | Binding | Scope |
| --- | --- | --- |
| `AppModule` | `AppDatabase` ← `AppDatabase.getInstance(context)`; `MediaFileDao` ← `db.mediaFileDao()` | Singleton |
| `NetworkModule` | `HttpLoggingInterceptor(BODY)`; `@Named("plain") OkHttpClient`; `@Named("auth") OkHttpClient` | Singleton |
| `StorageModule` | `@Binds TokenStorage ← EncryptedPrefsTokenStorage` | Singleton |
| Constructor injection | `BaseUrlProvider`, `ApiServiceFactory`, `ServerPreferences`, `ServerRepository`, `AuthRepository`, все media-репозитории и координаторы | Singleton |
| Unscoped | `AuthInterceptor`, `TokenAuthenticator` | — |
| Worker | `SyncWorker` (`@HiltWorker`, `@AssistedInject`) | per-run |

## 12. Имена хранилищ

| Объект | Имя |
| --- | --- |
| Room DB | `photo_cloud.db` |
| Таблица | `media_files` |
| EncryptedSharedPreferences | `secure_tokens` (ключи `accessToken`, `refreshToken`) |
| MasterKey | default alias `MasterKey` (AES256_GCM) [INFERRED значение по умолчанию библиотеки] |
| DataStore | `settings` (ключи `ip`, `port`) |
| WorkManager unique works | `photo_sync`, `photo_sync_periodic` |

## 13. Разрешения и манифест

| Элемент | Значение |
| --- | --- |
| `READ_EXTERNAL_STORAGE` | `maxSdkVersion=32` |
| `READ_MEDIA_IMAGES` | да |
| `allowBackup` | `true`; `backup_rules.xml` и `data_extraction_rules.xml` — шаблоны без правил |
| Тема | `Theme.Photocloudclient` (`android:Theme.Material.Light.NoActionBar`); в Compose — Material3 с dynamic color на API 31+ |
| `app/AndroidManifest.xml` (вне `src/main`) | устаревший файл, **[INFERRED]** в сборке не участвует |

## 14. Пользовательские настройки

**[CONFIRMED]** Нет. `SettingsScreen` — заглушка. Серверные `/profile/settings` возвращают `501`. Wi-Fi only, интервала, выбора папок, видео, целевой папки нет.
