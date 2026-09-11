# 15. Implemented Features — Master Matrix

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Пути — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`.
> Статусы: **IMPLEMENTED** — работает end-to-end · **PARTIAL** — работает не полностью / с существенными ограничениями · **STUB** — точка входа есть, реализации нет · **UNUSED** — код есть, но не вызывается · **NOT FOUND** — в проекте отсутствует.

---

## 1. Подключение к серверу

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Ввод адреса сервера | IMPLEMENTED | `NetworkScreen`, `NetworkViewModel` | только IPv4 + порт; схема `http` захардкожена |
| Проверка связи | IMPLEMENTED | `ServerRepository.testConnection`, `TestService.testServer` | `GET /api/v1/test`, plain-клиент 5 с |
| Сохранение адреса | IMPLEMENTED | `ServerPreferences` (DataStore) | сохраняется только после успешной проверки |
| Восстановление адреса при запуске | PARTIAL | `NetworkViewModel.init` → `BaseUrlProvider` | только при открытии UI; фоновый процесс адреса не знает |
| HTTPS / hostname / reverse-proxy URL | NOT FOUND | — | — |

## 2. Аутентификация

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Регистрация | IMPLEMENTED | `AuthViewModel.register`, `AuthRepository.register` | без экрана «подтвердите email» и валидации 4–20 |
| Подтверждение email | NOT FOUND | — | ссылка из письма открывается вне приложения |
| Login | IMPLEMENTED | `AuthViewModel.login`, `AuthRepository.login` | поле пароля не маскируется |
| Хранение токенов | IMPLEMENTED | `EncryptedPrefsTokenStorage` | AES256, Keystore |
| Bearer-заголовок | IMPLEMENTED | `AuthInterceptor` | — |
| Token refresh (на `401`) | IMPLEMENTED | `TokenAuthenticator` | однопоточный, 1 повтор; очищает токены на любом не-`2xx`, включая `5xx` |
| Проактивный refresh | UNUSED | `JwtUtils.isExpired` | метод есть, не вызывается |
| Проверка сессии при открытии экрана Login | IMPLEMENTED | `AuthViewModel.verifySession` | результат не показывается; побочный эффект — refresh |
| Test Auth | IMPLEMENTED | `AuthRepository.testAuth` | отладочная кнопка |
| Logout | PARTIAL | `AuthRepository.logout`, `AuthViewModel.logout` | при `IOException` токены остаются; Room и one-time work не очищаются; требует настроенного `baseUrl` |
| Сценарий «сессия истекла → войдите» | NOT FOUND | — | только косвенно: вкладка Login покажет форму |
| Logout all / others | NOT FOUND | — | серверные эндпоинты есть |
| Восстановление пароля | NOT FOUND | — | — |
| Отображение пользователя | PARTIAL | `JwtUtils.getSubject` | email из JWT `sub`; `GET /profile` не используется |
| Удобные методы `TokenStorage` (`getAccess`, `getRefresh`, `saveAccess`) | UNUSED | `core/storage/TokenStorage.kt:12-17` | — |

## 3. Разрешения и доступ к медиа

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Объявление разрешений | IMPLEMENTED | `AndroidManifest.xml` | `READ_MEDIA_IMAGES`; `READ_EXTERNAL_STORAGE` до API 32 |
| Runtime-запрос разрешения | PARTIAL | `FilesScreen` (`LaunchedEffect`), `FilesViewModel` | только при входе на Files и только если есть токены и `baseUrl` |
| Переход в настройки при окончательном отказе | NOT FOUND | TODO `FilesScreen.kt:57` | — |
| Partial photo access (Android 14) | NOT FOUND | — | `READ_MEDIA_VISUAL_USER_SELECTED` не объявлен |
| `ACCESS_MEDIA_LOCATION` / оригинальные байты | NOT FOUND | — | см. 07 §5.1 |
| Видео (`READ_MEDIA_VIDEO`) | NOT FOUND | — | — |

## 4. Локальный индекс

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Scan MediaStore (фото из `DCIM/`) | IMPLEMENTED | `MediaStoreRepository.loadImages` | полный скан, без пагинации |
| Room persistence | IMPLEMENTED | `AppDatabase` v3, `MediaFile`, `MediaFileDao` | индексов и транзакций нет |
| Миграции | PARTIAL | `MIGRATION_1_2`, `MIGRATION_2_3` | + destructive fallback; `1.json` нет; тестов нет |
| Обновление метаданных | IMPLEMENTED | `MediaFileDao.updateLocalMetadata` | каждый scan |
| File change detection | IMPLEMENTED | `MediaFileDao.resetChecksumIfFileChanged` | эвристика `size`/`DATE_MODIFIED`; сбрасывает и `SYNCED` |
| Local deletion detection | PARTIAL | stale delete в `MediaFileRepository.applyToRoom` | строка удаляется; `LOCAL_DELETED` не используется; сервер не уведомляется |
| Статус `LOCAL_DELETED` | UNUSED | `MediaFileStatus.LOCAL_DELETED` | — |
| DAO `insert`, `getByStatus`, `getPending`, `exists`, `getLatestCreatedAt` | UNUSED | `MediaFileDao` | — |
| Привязка индекса к аккаунту/серверу | NOT FOUND | — | — |

## 5. Pipeline синхронизации

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Checksum SHA-256 | IMPLEMENTED | `ChecksumRepository`, `ChecksumUtil` | потоково, lowercase hex, батчи по 100 |
| Resolve системной папки `CAMERA` | IMPLEMENTED | `FolderRepository` | по `folderType`; без кэша; 2 раза за прогон |
| Server existence check (pre-check) | IMPLEMENTED | `ChecksumPrecheckRepository` | батчи ≤ 500, группировка дублей, только `CAMERA` |
| `serverFileId` для файлов, найденных pre-check | NOT FOUND | TODO `ChecksumPrecheckRepository.kt:20` | остаётся `null` |
| Upload | IMPLEMENTED | `FileUploadRepository`, `ContentUriRequestBody` | только `image/*`; `folderId=CAMERA`; поток из `content://` (но см. 14 §3.1) |
| Фиксация успеха (`serverFileId`) | IMPLEMENTED | `MediaFileDao.markUploaded` | дубль считается успехом |
| Upload в выбранную пользователем папку | NOT FOUND | — | — |
| Видео sync | NOT FOUND | — | — |
| Обратная синхронизация (server → device) | NOT FOUND | — | `GET /files` не используется |
| Сверка локального и серверного checksum | NOT FOUND | — | `FileItemDto.checksum` не читается |

## 6. Фоновое выполнение

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Background sync worker | IMPLEMENTED | `SyncWorker` | весь pipeline в одном `doWork` |
| Событийный sync (новые фото) | PARTIAL | `MediaChangeObserver` → `photo_sync` | живёт только в процессе, где был открыт UI; `KEEP` отбрасывает события во время backoff |
| Periodic sync | PARTIAL | `SyncScheduler.enqueuePeriodicSync` | 1 ч; в процессе без UI уходит в `SERVER_UNAVAILABLE` |
| Включение/выключение автосинка | IMPLEMENTED | `PeriodicSyncCoordinator.reconcile` | не вызывается при очистке токенов authenticator'ом |
| Работа после перезагрузки без открытия приложения | NOT FOUND | — | см. 12 §4 |
| Foreground service / уведомление о прогрессе | NOT FOUND | — | — |
| Wi-Fi only / зарядка | NOT FOUND | — | только `CONNECTED` (+ battery not low для periodic) |
| Ручной «Sync now» | NOT FOUND | — | кнопки удалены в Stage 5J.2 |

## 7. Ошибки и retry

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Failure reason | IMPLEMENTED | `FailureReason`, `MediaFileDao.markFailed` | 8 причин; текст ошибки не хранится |
| `FailureReason.isRetryable` | UNUSED | `FailureReason.kt:18-19` | — |
| Automatic retry — `CHECKSUM_IO` | IMPLEMENTED | `ChecksumRepository.restoreRetryableFailed` | ≤ 3, cooldown 5 мин |
| Automatic retry — transient upload/стадии | PARTIAL | `Result.retry()` | без лимита; head-of-line blocking |
| Automatic retry — прочие `FAILED` | NOT FOUND | — | терминальны |
| Manual retry | NOT FOUND | — | — |
| Recovery зависших `HASHING`/`UPLOADING` | IMPLEMENTED | `MediaFileDao.resetStatus` | в начале стадий |

## 8. UI

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Нижняя навигация (5 вкладок) | IMPLEMENTED | `MainScreen`, `BottomNavigationBar`, `NavigationGraph` | стек вкладок накапливается |
| UI status display (итог sync) | PARTIAL | `SyncStatusCard`, `SyncStatusStore` | только в памяти; periodic не отражается |
| Индикатор «идёт sync» | PARTIAL | `SyncScheduler.observeIsSyncing`, `LoadingDialog` | модальный неотменяемый диалог блокирует весь UI |
| Счётчики по статусам | IMPLEMENTED | `StatusCountersRow` | без `LOCAL_DELETED` |
| Media list | IMPLEMENTED | `FilesScreen` `LazyColumn` | только текст (имя, путь, размер, статус, причина); без миниатюр |
| Просмотр фото / галерея / миниатюры | NOT FOUND | — | — |
| Settings | STUB | `SettingsScreen` | текст «Settings» |
| Profile | STUB | `ProfileScreen` | текст «Profile»; профиль фактически показан на вкладке Login |
| `FilesViewModel.clearMessages` | UNUSED | — | — |

## 9. Серверные возможности на стороне клиента

| Feature | Status | Notes |
| --- | --- | --- |
| Список серверных файлов | NOT FOUND | — |
| Download | NOT FOUND | — |
| Deletion (сервер) | NOT FOUND | — |
| Deletion (локально из приложения) | NOT FOUND | — |
| Rename / move / copy | NOT FOUND | — |
| Управление папками | NOT FOUND | используется только чтение root/children |
| Metadata (EXIF) | NOT FOUND | `FileItemDto.metadata` не используется |

## 10. Безопасность и эксплуатация

| Feature | Status | Notes |
| --- | --- | --- |
| Шифрование токенов | IMPLEMENTED | — |
| TLS / pinning | NOT FOUND | — |
| Логирование | PARTIAL | `Log.*` в ключевых местах; HTTP `BODY` без отключения в release |
| Crash reporting / аналитика | NOT FOUND | — |
| Правила backup | STUB | шаблонные XML без правил |
| R8 / minify | NOT FOUND | `isMinifyEnabled = false` |
| Автотесты | STUB | только шаблонные |
| CI | NOT FOUND | — |
