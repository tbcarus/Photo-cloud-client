# 00. Executive Summary

> Аудит As-Is Android-клиента PhotoCloud (`ru.tbcarus.photo_cloud_client`), репозиторий `Photocloudclient`.
> Дата анализа: 2026-09-10. Ветка `master`, HEAD `2b9ab69 5K`. Production-код в ходе аудита не изменялся.
> Пути к коду — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`, если не указано иное. Серверные документы — `photo-cloud-server/docs/audit/server - сс/client-handoff/`.
> Метки: **[CONFIRMED]** подтверждено кодом · **[DOCUMENTED]** только комментарии/документация · **[INFERRED]** вывод из структуры кода или из поведения платформы/библиотеки, запуском не проверялось · **[PARTIAL]** реализовано частично · **[UNUSED]** код есть, но не вызывается · **[INCONSISTENCY]** расхождение · **[RISK]** потенциальная проблема.

---

## 1. Назначение Android-приложения

**[CONFIRMED]** Android-клиент личного облака PhotoCloud. По коду его фактическое назначение — **автоматически выгружать фотографии из `DCIM/` устройства на сервер `photo-cloud-server`** (одностороннее резервное копирование device → server). Приложение:

- хранит адрес сервера (IPv4 + порт, только `http://`);
- регистрирует и логинит пользователя (JWT access + refresh);
- индексирует фото из MediaStore в локальной Room-БД;
- считает SHA-256 каждого файла;
- спрашивает у сервера, какие checksum уже есть в системной папке `CAMERA`;
- загружает недостающие файлы в `CAMERA`;
- работает в фоне через WorkManager и `ContentObserver`;
- показывает список локальных фото со статусами и итог последней синхронизации.

**[CONFIRMED]** Чего приложение **не** делает: не показывает серверные файлы, не скачивает их, ничего не удаляет (ни на устройстве, ни на сервере), не управляет папками, не синхронизирует видео, не делает обратной синхронизации server → device.

## 2. Технологический стек

| Слой | Технология | Источник |
| --- | --- | --- |
| Язык | Kotlin 2.0.21; Java — 4 Retrofit-интерфейса и 3 DTO | `build.gradle.kts`, `api/*.java` |
| SDK | `minSdk 29`, `targetSdk 35`, `compileSdk 35`, Java 17 | `app/build.gradle.kts:11-40` |
| Сборка | AGP 8.9.1, **один Gradle-модуль `:app`**, KSP 2.0.21-1.0.28 | `settings.gradle.kts:23`, `build.gradle.kts:6` |
| UI | Jetpack Compose (ui 1.6.7, material3 1.2.1), Navigation Compose 2.7.7 | `app/build.gradle.kts:48-57` |
| DI | Hilt 2.52, hilt-navigation-compose 1.2.0, hilt-work 1.2.0 | `app/build.gradle.kts:74-88` |
| Локальная БД | Room 2.7.0 | `app/build.gradle.kts:79-81` |
| Сеть | Retrofit 2.11.0 + Gson converter, OkHttp 4.12.0 + logging-interceptor, Gson 2.10.1 | `app/build.gradle.kts:64-68` |
| Фон | WorkManager 2.9.0 | `app/build.gradle.kts:84` |
| Хранилища настроек | DataStore Preferences 1.1.1; `security-crypto 1.1.0-alpha06` (EncryptedSharedPreferences) | `app/build.gradle.kts:91-92` |
| Тесты | JUnit 4, androidx.test, Espresso, Compose ui-test — **только шаблонные тесты** | `app/src/test`, `app/src/androidTest` |

## 3. Архитектура

**[CONFIRMED]** Одномодульное MVVM-приложение: Compose-экраны → `@HiltViewModel` со `StateFlow` → `@Singleton`-репозитории → Room / MediaStore / Retrofit / WorkManager. Отдельного domain/use-case слоя нет. Вся логика синхронизации сосредоточена в пакете `media/`: 6 репозиториев, 3 координатора/планировщика, `SyncWorker`. Сеть — синхронные Retrofit `Call.execute()` внутри `withContext(Dispatchers.IO)`. Подробно — [02-architecture.md](02-architecture.md).

## 4. Основные экраны

**[CONFIRMED]** Нижняя навигация из 5 вкладок (`ui/MainScreen.kt:34-40`), стартовая — Network (`ui/components/NavigationGraph.kt:19`):

| Вкладка | Экран | Состояние |
| --- | --- | --- |
| Network | `NetworkScreen` — ввод IP/порта, кнопка «Test» | IMPLEMENTED |
| Login | `AuthScreen` — регистрация/вход/«Test Auth»; после входа — email из JWT и «Logout» | IMPLEMENTED |
| Settings | `SettingsScreen` — текст «Settings» | STUB |
| Profile | `ProfileScreen` — текст «Profile» | STUB |
| Files | `FilesScreen` — монитор синхронизации: карточки статуса, счётчики по статусам, список локальных фото | IMPLEMENTED (только чтение) |

## 5. Room

**[CONFIRMED]** Одна БД `photo_cloud.db`, версия **3**, одна таблица `media_files` (PK `mediaStoreId`), 14 колонок, миграции `1→2` и `2→3`, плюс `fallbackToDestructiveMigration(dropAllTables = true)`. Индексов нет, `@Transaction` нет. Жизненный цикл файла кодируется enum `MediaFileStatus` (8 значений, одно не используется) и `FailureReason` (8 значений). Подробно — [04-database.md](04-database.md), [09-state-machines.md](09-state-machines.md).

## 6. MediaStore

**[CONFIRMED]** Запрос только к `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` с фильтром `RELATIVE_PATH LIKE 'DCIM/%'`. Работа идёт только через `content://` URI, абсолютные пути не используются. Разрешения: `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (API ≤ 32). Изменение файла определяется по `size`/`DATE_MODIFIED`, удаление — по исчезновению `_ID` из выборки. Подробно — [07-local-media-storage.md](07-local-media-storage.md).

## 7. Networking

**[CONFIRMED]** Два `OkHttpClient`: `plain` (таймауты 5 с, без авторизации) и `auth` (10/60/60 с, `AuthInterceptor` + `TokenAuthenticator`). `ApiServiceFactory` собирает новый `Retrofit` на каждый вызов сервиса. `HttpLoggingInterceptor` уровня `BODY` включён в обоих клиентах во **всех** сборках. Клиент вызывает **10 из 36** серверных операций. Подробно — [05-api.md](05-api.md).

## 8. Auth

**[CONFIRMED]** Токены лежат в `EncryptedSharedPreferences` (`secure_tokens`, ключ в Android Keystore). Bearer-заголовок добавляет `AuthInterceptor`. На любой `401` `TokenAuthenticator` один раз делает refresh; refresh однопоточный (`synchronized` + сравнение токенов). Любой неуспешный ответ refresh очищает токены. Logout = `POST /auth/logout` + очистка токенов; при сетевой ошибке токены **не** очищаются. Проактивного refresh и сценария «сессия истекла → войдите снова» нет. Подробно — [06-auth-security.md](06-auth-security.md).

## 9. Background sync

**[CONFIRMED]** `SyncWorker` (`@HiltWorker`, `CoroutineWorker`) строго последовательно выполняет: проверка сервера `GET /api/v1/test` → возврат retryable-ошибок → scan → checksum → pre-check `POST /files/checksums/exists` → upload `POST /files/upload`.

Запускается двумя путями:
- `MediaChangeObserver` (`ContentObserver` на Images, debounce 3 с) → one-time unique work `photo_sync` (`KEEP`, сеть CONNECTED);
- периодическая работа `photo_sync_periodic` (1 ч, `UPDATE`, сеть CONNECTED + батарея не низкая).

Оба пути включает и выключает `PeriodicSyncCoordinator.reconcile()` по правилу «токены + baseUrl + разрешение на медиа». Подробно — [12-background-jobs.md](12-background-jobs.md).

## 10. Текущий уровень готовности

**[INFERRED]** Проект на стадии рабочего вертикального среза (MVP) для фотографий. Сквозной сценарий «настроить сервер → войти → дать доступ к фото → фото из DCIM загружаются в CAMERA» реализован и согласован с серверным контрактом по эндпоинтам и DTO.

Надёжность ниже уровня production. Фоновая синхронизация зависит от того, открывался ли UI в текущем процессе. Восстановление после ошибок неполное. Локальный индекс не привязан к аккаунту и серверу. Логи содержат секреты. Автотестов нет. Экраны Settings и Profile — заглушки.

| Область | Оценка |
| --- | --- |
| Auth (login/refresh/logout) | IMPLEMENTED с отклонениями от контракта |
| Scan / checksum / pre-check / upload | IMPLEMENTED (только фото из DCIM) |
| Фоновая синхронизация | PARTIAL — работает только в процессе, где был открыт UI |
| Обработка ошибок и retry | PARTIAL |
| UI | PARTIAL (2 заглушки, модальный блокирующий loader) |
| Тесты | отсутствуют |

## 11. Основные завершённые возможности

**[CONFIRMED]**
1. Настройка и проверка адреса сервера с сохранением в DataStore.
2. Регистрация, вход, выход; зашифрованное хранение токенов.
3. Автоматический refresh access token на `401` с защитой от параллельных refresh и от зацикливания.
4. Инкрементальный скан MediaStore (`DCIM/`): вставка новых, обновление метаданных, удаление исчезнувших.
5. Потоковый SHA-256 (lowercase hex, совпадает с серверным форматом).
6. Batch pre-check по 500 checksum с группировкой локальных дубликатов.
7. Потоковая multipart-загрузка из `content://` URI с явным `folderId` папки `CAMERA`.
8. Классификация ошибок: permanent → `FAILED` + `FailureReason`, transient → возврат в очередь + `Result.retry()`.
9. Ограниченный auto-retry для `CHECKSUM_IO`: 3 попытки, cooldown 5 мин (Stage 5K).
10. Восстановление зависших `HASHING`/`UPLOADING` после гибели процесса.
11. Триггеры фоновой синхронизации: `ContentObserver` + периодическая работа.
12. Экран-монитор со счётчиками по статусам и причинами ошибок.

## 12. Ключевые незавершённые места и главные риски

Полный список — [16-known-gaps-and-risks.md](16-known-gaps-and-risks.md).

| # | Проблема | Severity |
| --- | --- | --- |
| 1 | **Фоновый sync не работает в процессе, запущенном без UI.** `BaseUrlProvider.baseUrl` заполняется только из `NetworkViewModel`. `SyncWorker` после перезагрузки или гибели процесса видит пустой URL → `SERVER_UNAVAILABLE` → бесконечный `retry()` (`di/BaseUrlProvider.kt:8`, `media/SyncWorker.kt:50-55`) | CRITICAL |
| 2 | **Head-of-line blocking в upload.** Transient-ошибка на одном файле обрывает весь прогон. Файл возвращается в очередь и при следующем прогоне снова идёт первым (`ORDER BY createdAt DESC`). Если ошибка воспроизводится именно на этом файле (5xx, несовпадение длины), очередь стоит навсегда (`media/FileUploadRepository.kt:100-143`) | HIGH |
| 3 | **Локальный индекс не привязан к аккаунту и серверу.** Logout не очищает Room. После входа другим пользователем или смены сервера файлы в `SYNCED` уже никогда не загружаются | HIGH |
| 4 | **`HttpLoggingInterceptor.Level.BODY` во всех сборках.** В Logcat попадают пароль, access и refresh token. По поведению OkHttp [INFERRED] тело каждого upload целиком буферизуется в памяти и печатается в лог (`di/NetworkModule.kt:21-22`) | HIGH |
| 5 | **Модальный неотменяемый `LoadingDialog`** на вкладке Files, пока выполняется one-time sync: весь UI заблокирован (`ui/screens/FilesScreen.kt:85-87`, `ui/components/LoadingDialog.kt:17`) | HIGH |
| 6 | Любой неуспешный refresh (включая `5xx`) очищает токены. После этого нет перехода на вход, а фоновый pipeline продолжает крутиться с `401` | MEDIUM |
| 7 | `KEEP` + неограниченный экспоненциальный backoff (до 5 ч): пока one-time work ждёт повтора, события `ContentObserver` игнорируются | MEDIUM |
| 8 | `MediaChangeObserver` живёт только в памяти процесса и после его гибели до открытия UI не восстанавливается | MEDIUM |
| 9 | Network security config разрешает cleartext только для `10.0.2.2`/`localhost`, а клиент поддерживает только `http://<IP>` [INFERRED — требует проверки на устройстве] | MEDIUM |
| 10 | Фото читаются без `ACCESS_MEDIA_LOCATION` / `setRequireOriginal`: на сервер, вероятно, уходят байты с вырезанным GPS [INFERRED] | MEDIUM |
| 11 | Ручного retry `FAILED` нет; причины `PERMISSION`/`FILE_NOT_FOUND`/`HTTP_4xx`/`UNKNOWN` терминальны | MEDIUM |
| 12 | Автотестов нет | MEDIUM |

## 13. Карта документов

| Файл | Содержание |
| --- | --- |
| [01-system-overview.md](01-system-overview.md) | Клиент как система, границы, сценарии, persistent/transient state |
| [02-architecture.md](02-architecture.md) | Слои, пакеты, DI, архитектурные замечания |
| [03-data-model.md](03-data-model.md) | Все модели, классификация полей LOCAL/SERVER/DERIVED/SYNC_STATE |
| [04-database.md](04-database.md) | Room: схема, DAO, миграции, ER |
| [05-api.md](05-api.md) | Retrofit-вызовы и сравнение с серверным контрактом |
| [06-auth-security.md](06-auth-security.md) | Auth lifecycle, хранение токенов, refresh, безопасность |
| [07-local-media-storage.md](07-local-media-storage.md) | MediaStore ↔ Room, идентификация файлов |
| [08-business-processes.md](08-business-processes.md) | End-to-end процессы, sequence diagrams, фактический sync protocol |
| [09-state-machines.md](09-state-machines.md) | Полный lifecycle `MediaFile` |
| [10-errors-retries.md](10-errors-retries.md) | Ошибки, `FailureReason`, retry |
| [11-configuration.md](11-configuration.md) | Все настройки и константы |
| [12-background-jobs.md](12-background-jobs.md) | WorkManager, observer, координатор |
| [13-testing.md](13-testing.md) | Тестовое покрытие |
| [14-observability.md](14-observability.md) | Логи, диагностика, утечки в логах |
| [15-implemented-features.md](15-implemented-features.md) | Feature matrix |
| [16-known-gaps-and-risks.md](16-known-gaps-and-risks.md) | Пробелы и риски с severity |
| [17-open-questions.md](17-open-questions.md) | Вопросы, на которые код не отвечает |
| [18-code-map.md](18-code-map.md) | Карта кода |
| [19-server-client-consistency.md](19-server-client-consistency.md) | Интеграционный отчёт Android ↔ Server |
