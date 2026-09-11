# 18. Code Map

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Ссылки ведут из `docs/audit/android/` в исходники. Корень пакета: `app/src/main/java/ru/tbcarus/photo_cloud_client/`.

---

## 1. Модуль и сборка

| Файл | Назначение |
| --- | --- |
| [settings.gradle.kts](../../../settings.gradle.kts) | единственный модуль `:app` |
| [build.gradle.kts](../../../build.gradle.kts) | плагины: AGP, Kotlin, Compose, KSP, Hilt |
| [app/build.gradle.kts](../../../app/build.gradle.kts) | SDK, зависимости, Room schema location |
| [gradle/libs.versions.toml](../../../gradle/libs.versions.toml) | каталог версий (фактически только плагины) |
| [app/src/main/AndroidManifest.xml](../../../app/src/main/AndroidManifest.xml) | разрешения, `App`, `MainActivity`, network config, удаление `InitializationProvider` |
| [app/AndroidManifest.xml](../../../app/AndroidManifest.xml) | устаревший, в сборке не участвует [INFERRED] |
| [network_security_config.xml](../../../app/src/main/res/xml/network_security_config.xml) | cleartext для `10.0.2.2`, `localhost` |
| [app/schemas/…/2.json](../../../app/schemas/ru.tbcarus.photo_cloud_client.core.db.AppDatabase/2.json), [3.json](../../../app/schemas/ru.tbcarus.photo_cloud_client.core.db.AppDatabase/3.json) | экспортированные схемы Room |

## 2. Центральные классы

| Роль | Класс | Файл | Ключевые связи |
| --- | --- | --- | --- |
| Application | `App` | [App.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/App.kt) | `@HiltAndroidApp`; `HiltWorkerFactory` для WorkManager |
| Activity | `MainActivity` | [MainActivity.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/MainActivity.kt) | создаёт `NetworkViewModel` (activity scope) → `MainScreen` |
| Navigation | `NavigationGraph`, `Routes`, `BottomNavigationBar` | [NavigationGraph.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/NavigationGraph.kt), [Routes.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/utils/Routes.kt), [BottomNavigationBar.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/BottomNavigationBar.kt) | start = `network`; 5 маршрутов |
| Screens | `NetworkScreen`, `AuthScreen`, `FilesScreen`, `SettingsScreen`, `ProfileScreen` | [ui/screens/](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens) | → ViewModels |
| ViewModels | `NetworkViewModel`, `AuthViewModel`, `FilesViewModel` | [network/](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt), [auth/](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt), [files/](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt) | → репозитории, `PeriodicSyncCoordinator` |
| Repositories | `AuthRepository`, `ServerRepository`, `MediaFileRepository`, `MediaStoreRepository`, `ChecksumRepository`, `ChecksumPrecheckRepository`, `FolderRepository`, `FileUploadRepository` | см. §3 | → DAO, API, `ContentResolver` |
| DAO | `MediaFileDao` | [MediaFileDao.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt) | 21 метод |
| Database | `AppDatabase` | [AppDatabase.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt) | v3, миграции, converters |
| Entity | `MediaFile`, `MediaFileStatus`, `FailureReason` | [MediaFile.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt), [MediaFileStatus.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt), [FailureReason.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/FailureReason.kt) | — |
| API service | `AuthService`, `FileService`, `FolderService`, `TestService`, `ApiServiceFactory` | [api/](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/api), [ApiServiceFactory.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt) | Retrofit per call |
| Worker | `SyncWorker` | [SyncWorker.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt) | оркестратор pipeline |
| Scheduler | `SyncScheduler`, `PeriodicSyncCoordinator`, `MediaChangeObserver` | [SyncScheduler.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt), [PeriodicSyncCoordinator.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt), [MediaChangeObserver.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt) | WorkManager, ContentObserver |
| MediaStore scanner | `MediaStoreRepository` | [MediaStoreRepository.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt) | `DCIM/%` |
| Checksum utility | `ChecksumUtil` | [ChecksumUtil.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumUtil.kt) | SHA-256 |
| Token storage | `TokenStorage`, `EncryptedPrefsTokenStorage` | [core/storage/](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage) | Keystore |
| Auth network | `AuthInterceptor`, `TokenAuthenticator` | [core/network/](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/network) | auth-клиент |
| Server URL | `BaseUrlProvider`, `ServerPreferences` | [BaseUrlProvider.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/di/BaseUrlProvider.kt), [ServerPreferences.kt](../../../app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerPreferences.kt) | in-memory + DataStore |

## 3. Пакеты и файлы

### `(root)`
| Файл | Роль |
| --- | --- |
| `App.kt` | Application + WorkManager configuration |
| `MainActivity.kt` | единственная Activity |

### `api`
| Файл | Роль | Используется |
| --- | --- | --- |
| `ApiPaths.kt` | константы путей `api/v1/...` | Retrofit-интерфейсы |
| `AuthService.java` | register, login, refreshToken, logout | `AuthRepository`, `TokenAuthenticator` |
| `FileService.java` | checksumsExist, uploadFile (multipart) | `ChecksumPrecheckRepository`, `FileUploadRepository` |
| `FolderService.java` | getRoot, getChildren | `FolderRepository` |
| `TestService.java` | testServer, testAuth | `ServerRepository`, `AuthRepository` |

### `api.models`
| Файл | Роль |
| --- | --- |
| `AuthRequest.java`, `LogoutRequest.java`, `RefreshTokenRequest.java` | тела запросов auth (Java) |
| `LoginResponseDto.kt`, `RefreshResponseDto.kt` | ответы auth |
| `ChecksumExistsRequest.kt`, `ChecksumExistsResponse.kt` | pre-check |
| `FileItemDto.kt` | ответ upload (используется `id`) |
| `FolderDto.kt` | папки |
| `ErrorResponseDto.kt` | ошибка сервера (формат A) |
| `TestResponse.kt` | `/test`, `/test/auth` |

### `auth`
| Файл | Роль |
| --- | --- |
| `AuthRepository.kt` | register / login / logout / testAuth; фасад `TokenStorage`; `isReady()` = baseUrl задан |
| `AuthUiState.kt` | UI-state экрана Login (лежит в `auth`, зависит от `ui.components.ConnectionStatus`) |

### `core.db`
| Файл | Роль |
| --- | --- |
| `AppDatabase.kt` | Room DB v3, `Converters`, `MIGRATION_1_2`, `MIGRATION_2_3`, `getInstance()` |

### `core.network`
| Файл | Роль |
| --- | --- |
| `ApiServiceFactory.kt` | сборка Retrofit-сервисов по текущему `baseUrl` |
| `AuthInterceptor.kt` | Bearer-заголовок |
| `TokenAuthenticator.kt` | refresh на `401` |
| `ApiErrorParser.kt` | error body → строка |

### `core.server`
| Файл | Роль |
| --- | --- |
| `ServerPreferences.kt` | DataStore `settings` (ip, port) |
| `ServerRepository.kt` | `GET /api/v1/test` |

### `core.storage`
| Файл | Роль |
| --- | --- |
| `TokenStorage.kt` | интерфейс + неиспользуемые default-методы |
| `EncryptedPrefsTokenStorage.kt` | реализация на EncryptedSharedPreferences |
| `Tokens.kt` | пара токенов |

### `di`
| Файл | Роль |
| --- | --- |
| `AppModule.kt` | `AppDatabase`, `MediaFileDao` |
| `NetworkModule.kt` | `HttpLoggingInterceptor(BODY)`, plain/auth `OkHttpClient` |
| `StorageModule.kt` | `TokenStorage` binding |
| `BaseUrlProvider.kt` | in-memory `baseUrl` |

### `media`
| Файл | Роль | Слой |
| --- | --- | --- |
| `MediaFile.kt` | Room entity | data |
| `MediaFileStatus.kt` | enum статусов | domain/data |
| `FailureReason.kt` | enum причин | domain/data |
| `MediaFileDao.kt` | DAO | data |
| `MediaStoreImage.kt` | снимок MediaStore | data |
| `MediaStoreRepository.kt` | чтение MediaStore | data |
| `MediaFileRepository.kt` | scan → Room, `observeAll` | data |
| `ScanResult.kt` | `ScanResult`, `ScanOutcome`, `MediaStoreScanOutcome` | model |
| `ChecksumUtil.kt` | SHA-256 | util |
| `ChecksumRepository.kt` | hashing, recovery, auto-retry | data/domain |
| `ChecksumResult.kt` | `ChecksumResult`, `ChecksumOutcome` | model |
| `FolderRepository.kt` | резолв `CAMERA`; `CameraFolderOutcome` | data |
| `ChecksumPrecheckRepository.kt` | pre-check | data/domain |
| `ChecksumPrecheckResult.kt` | результаты pre-check | model |
| `ContentUriRequestBody.kt` | потоковое тело upload | network |
| `FileUploadRepository.kt` | upload | data/domain |
| `UploadResult.kt` | результаты upload | model |
| `SyncResult.kt` | агрегат по стадиям | model |
| `SyncStatus.kt` | `SyncStatus`, `SyncStatusRecord` | model |
| `SyncStatusStore.kt` | in-memory статус | state |
| `SyncWorker.kt` | WorkManager worker | background |
| `SyncScheduler.kt` | enqueue/cancel/observe | background |
| `PeriodicSyncCoordinator.kt` | правило автосинка | domain/background |
| `MediaChangeObserver.kt` | ContentObserver | background |

### `ui`, `ui.components`, `ui.screens.*`, `ui.theme`
| Файл | Роль |
| --- | --- |
| `ui/MainScreen.kt` | Scaffold: TopAppBar, BottomBar, NavHost |
| `ui/components/BottomNavItem.kt`, `BottomNavigationBar.kt`, `NavigationGraph.kt` | навигация |
| `ui/components/ConnectionStatus.kt` | enum статуса с цветом |
| `ui/components/Dialogs.kt` | `showDialog` (AlertDialog) |
| `ui/components/LoadingDialog.kt` | модальный спиннер |
| `ui/screens/NetworkScreen.kt` + `network/NetworkViewModel.kt`, `NetworkUiState.kt` | настройка сервера |
| `ui/screens/auth/AuthScreen.kt`, `AuthViewModel.kt` | вход/регистрация/выход |
| `ui/screens/FilesScreen.kt` + `files/FilesViewModel.kt`, `FilesUiState.kt` | монитор синхронизации |
| `ui/screens/SettingsScreen.kt`, `ProfileScreen.kt`, `StubComponents.kt` | заглушки |
| `ui/theme/Theme.kt`, `Color.kt`, `Type.kt` | Material3 |

### `utils`
| Файл | Роль |
| --- | --- |
| `JwtUtils.kt` | `getSubject` (используется), `isExpired` (нет) |
| `Routes.kt` | маршруты |
| `isValidIpAddress.kt` | IPv4 regex |

### Тесты
| Файл | Роль |
| --- | --- |
| `app/src/test/.../ExampleUnitTest.kt` | шаблон |
| `app/src/androidTest/.../ExampleInstrumentedTest.kt` | шаблон |

## 4. Граф вызовов sync pipeline

```
MediaChangeObserver.onChange ─debounce 3s─▶ SyncScheduler.enqueueOneTimeSync ─┐
PeriodicSyncCoordinator.reconcile ─▶ SyncScheduler.enqueuePeriodicSync ────────┤
                                                                               ▼
SyncWorker.doWork → runSync
 ├─ ServerRepository.testConnection → ApiServiceFactory.plainTestService → GET /test
 ├─ ChecksumRepository.restoreRetryableFailed → MediaFileDao.getRetryableFailed / resetFailedForRetry
 ├─ MediaFileRepository.scanImages
 │    ├─ MediaStoreRepository.loadImages → ContentResolver.query(Images, DCIM/%)
 │    └─ MediaFileDao.insertAll / resetChecksumIfFileChanged / updateLocalMetadata / getAllMediaStoreIds / deleteByMediaStoreIds
 ├─ ChecksumRepository.computePendingChecksums
 │    └─ MediaFileDao.resetStatus / getByStatusOnce / updateStatus / updateChecksum / markFailed
 │       + ContentResolver.openInputStream → ChecksumUtil.sha256
 ├─ ChecksumPrecheckRepository.runCameraPrecheck
 │    ├─ FolderRepository.resolveCameraFolderId → GET /folders/root, GET /folders/{id}/children
 │    └─ MediaFileDao.getByStatusWithChecksumOnce → POST /files/checksums/exists → updateStatusByMediaStoreIds
 └─ FileUploadRepository.uploadPendingCameraFiles
      ├─ MediaFileDao.resetStatus
      ├─ FolderRepository.resolveCameraFolderId
      └─ loop: getByStatusOnce → openInputStream (pre-open) → updateStatus(UPLOADING)
               → POST /files/upload (ContentUriRequestBody) → markUploaded | markFailed | updateStatus
```

## 5. Граф вызовов auth

```
AuthScreen ─▶ AuthViewModel.login ─▶ AuthRepository.login ─▶ plain AuthService.login
                                  └▶ AuthRepository.saveTokens ─▶ EncryptedPrefsTokenStorage
                                  └▶ PeriodicSyncCoordinator.reconcile
auth OkHttpClient ─▶ AuthInterceptor (Bearer) ─▶ server
                  └▶ TokenAuthenticator (401) ─▶ plain AuthService.refreshToken ─▶ TokenStorage.save | clear
AuthViewModel.logout ─▶ AuthRepository.logout ─▶ auth AuthService.logout ─▶ TokenStorage.clear
                    └▶ PeriodicSyncCoordinator.reconcile
```
