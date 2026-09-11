# 13. Testing

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Метки — см. [00-executive-summary.md](00-executive-summary.md).

---

## 1. Инвентаризация

**[CONFIRMED]** В репозитории два теста — оба шаблонные, сгенерированы Android Studio:

| Файл | Тип | Содержание |
| --- | --- | --- |
| `app/src/test/java/ru/tbcarus/photo_cloud_client/ExampleUnitTest.kt` | JVM unit | `assertEquals(4, 2 + 2)` |
| `app/src/androidTest/java/ru/tbcarus/photo_cloud_client/ExampleInstrumentedTest.kt` | instrumented | проверка `packageName` |

**Тестовые зависимости** (`app/build.gradle.kts:95-98`): `junit:4.13.2`, `androidx.test.ext:junit:1.1.5`, `espresso-core:3.5.1`, `compose ui-test-junit4:1.6.6`.

**Отсутствуют:** `kotlinx-coroutines-test`, MockK/Mockito, Robolectric, `room-testing` (`MigrationTestHelper`), `work-testing` (`TestListenableWorkerBuilder`), OkHttp `MockWebServer`, Turbine, Hilt testing, `ui-test-manifest`.

**[CONFIRMED]** CI-конфигурации (`.github/`, другой CI) нет. Файлы HTTP-примеров и тестовых данных тоже нет.

## 2. Покрытие по подсистемам

| Subsystem | Tests found | Covered scenarios | Important missing cases |
| --- | --- | --- | --- |
| `ChecksumUtil` | нет | — | пустой поток; известные векторы SHA-256; lowercase/64 символа; большой поток |
| `MediaStoreRepository` | нет | — | нет разрешения; `null`-курсор; fallback-и дат/имени/MIME; фильтр `DCIM/%`; пропуск битых строк |
| `MediaFileRepository` (scan → Room) | нет | — | вставка новых; сброс при изменении `size`/`lastModified` до `updateLocalMetadata`; stale delete > 500; неполная выборка |
| `ChecksumRepository` | нет | — | `PENDING→HASHING→CHECKSUM_READY`; 4 ветки `FAILED`; recovery `HASHING`; auto-retry: лимит 3, cooldown, `retryCount` не сбрасывается в `resetFailedForRetry`; строки с `failureReason = NULL` |
| `ChecksumPrecheckRepository` | нет | — | `CAMERA` отсутствует; батчи по 500; группировка дублей; `unchanged`-выход из цикла; частичный успех; ошибка сервера |
| `FolderRepository` | нет | — | поиск по `folderType`; `NotCreatedYet`; ошибки root/children |
| `FileUploadRepository` | нет | — | `NOT_IMAGE`; pre-open FNF/Security; `2xx`+id; дубль (тот же id); `2xx` без id; `400/409/413`; `401/404/5xx`; `IOException`; recovery `UPLOADING`; head-of-line; upload без `folderId` |
| `ContentUriRequestBody` | нет | — | повторный `writeTo` (refresh); `size = 0` → chunked; несовпадение длины |
| `SyncWorker` | нет | — | маппинг каждого исхода стадий на `Result` и `SyncStatus`; пустой `baseUrl`; ping fail; исключения; `CancellationException` |
| `SyncScheduler` / `PeriodicSyncCoordinator` | нет | — | `KEEP`/`UPDATE`; матрица `reconcile()` (токены × baseUrl × permission); отмена при logout |
| `MediaChangeObserver` | нет | — | debounce схлопывает события; `start`/`stop` идемпотентны |
| Room / `MediaFileDao` | нет | — | каждый SQL-запрос; `markFailed` инкрементирует в SQL; `getRetryableFailed` с `NULL`; converters |
| Room migrations | нет | — | `1→2` (`lastModified = 0` → массовый сброс), `2→3`; destructive fallback; схема `1.json` не экспортирована, поэтому тест `1→2` невозможен без восстановления схемы |
| `TokenAuthenticator` | нет | — | один refresh на параллельные `401`; повтор с уже обновлённым токеном; `responseCount`; очистка на `401`/`403`/`5xx`; `IOException` |
| `AuthInterceptor` | нет | — | с токеном / без токена |
| `AuthRepository` | нет | — | login с пустыми токенами; logout при `IOException` (токены остаются); `404` |
| `EncryptedPrefsTokenStorage` | нет | — | save/clear/flow; поведение при ошибке Keystore |
| `ApiErrorParser` | нет | — | форматы A/B/C, `fieldErrors`, пустое тело, `text/plain` |
| ViewModels | нет | — | `NetworkViewModel.init` восстанавливает `baseUrl` и вызывает `reconcile`; `AuthViewModel` login/logout → `reconcile`; `FilesViewModel.scanPhotos` состояния |
| UI (Compose) | нет | — | permission flow на Files; `LoadingDialog`; отображение `FailureReason`; навигация |
| API-контракт | нет | — | сериализация DTO против эталонных JSON из серверного контракта; multipart-структура |

## 3. Тестопригодность кода

| Аспект | Оценка |
| --- | --- |
| Чистые функции | `ChecksumUtil`, `ApiErrorParser`, `isValidIpAddress`, `JwtUtils` — тестируются напрямую [CONFIRMED] |
| Интерфейсы для подмены | только `TokenStorage`. Репозитории и `ApiServiceFactory` — конкретные классы; сетевые вызовы подменяются на уровне HTTP через `MockWebServer`, поскольку `BaseUrlProvider.baseUrl` мутабелен [INFERRED] |
| Android-зависимости в репозиториях | `Context`/`ContentResolver` в `MediaStoreRepository`, `ChecksumRepository`, `FileUploadRepository`, `PeriodicSyncCoordinator` — нужны Robolectric или instrumented-тесты |
| WorkManager | `SyncWorker` с `@AssistedInject` тестируется через `TestListenableWorkerBuilder` + ручную фабрику [INFERRED] |
| Время | `System.currentTimeMillis()` вызывается напрямую (cooldown, `lastFailureAt`) — нет `Clock` для детерминированных тестов |
| Room | in-memory БД доступна; DAO — чистые SQL-запросы |

## 4. Вывод

**[CONFIRMED]** Автоматических тестов фактически нет. Корректность state machine `MediaFile` (28 переходов, см. 09), миграций, классификации ошибок и auth-логики ничем не зафиксирована. Судя по памяти проекта и истории коммитов, ручная проверка ограничивалась успешной сборкой (`./gradlew build`) и запуском на устройстве/эмуляторе.
