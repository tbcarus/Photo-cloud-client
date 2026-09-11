# 14. Observability

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Пути — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`. Метки — см. [00-executive-summary.md](00-executive-summary.md).

---

## 1. Logcat — инвентаризация явных вызовов

| Tag | Level | Сообщение | Где |
| --- | --- | --- | --- |
| `TokenAuthenticator` | W | «Base URL is not configured; skipping token refresh» | `core/network/TokenAuthenticator.kt:40` |
| `ChecksumRepository` | W | `openInputStream` вернул `null`, `mediaStoreId=…` | `media/ChecksumRepository.kt:108` |
| `ChecksumRepository` | W | ошибка доступа / файл не найден / IO / неизвестная ошибка + `mediaStoreId` + `e.message` | `media/ChecksumRepository.kt:120, 124, 129, 133` |
| `MediaChangeObserver` | I | зарегистрирован / остановлен | `media/MediaChangeObserver.kt:56, 64` |
| `SyncWorker` | E | «Sync завершился исключением» + stacktrace | `media/SyncWorker.kt:41` |
| `SyncWorker` | W | «Сервер недоступен — откладываем sync» | `media/SyncWorker.kt:52` |
| `SyncWorker` | I | «Auto-retry: восстановлено retryable FAILED = N» | `media/SyncWorker.kt:63` |
| `BASE_URL` | D | полный `baseUrl` | `ui/screens/network/NetworkViewModel.kt:61` |
| `NetworkViewModel` | E | «Ошибка подключения» + throwable | `ui/screens/network/NetworkViewModel.kt:76` |
| `okhttp.OkHttpClient` [INFERRED тег по умолчанию] | I | каждый HTTP-запрос и ответ с заголовками и телами | `di/NetworkModule.kt:21-22` |

**[CONFIRMED] Не логируется:**
- начало, конец и длительность прогона `SyncWorker`, результаты стадий (`SyncResult` уходит только в `outputData` при успехе);
- каждая стадия pre-check и upload; причины `FAILED` в upload (только в Room);
- ошибки `FolderRepository`, `ChecksumPrecheckRepository`, `FileUploadRepository`, `AuthRepository` — возвращаются строкой, но не пишутся в лог;
- решения `reconcile()`.

## 2. Structured logging / crash reporting / метрики

**[CONFIRMED]** Нет Timber, структурированных логов, корреляционных id, Crashlytics/Sentry, аналитики, метрик производительности. Серверный `ErrorResponse.id` не сохраняется и не показывается.

## 3. HTTP-логирование и утечка чувствительных данных

**[CONFIRMED]** `HttpLoggingInterceptor().apply { level = BODY }` — один экземпляр в обоих клиентах, без условия по build type и без `redactHeader(...)`.

**[INFERRED — поведение OkHttp 4.12]** Для каждого запроса `BODY` логирует строку запроса, заголовки, тело (если оно похоже на UTF-8), ответ и тело ответа. Логирующий interceptor стоит в auth-клиенте первым среди application-interceptors, поэтому `Authorization`, который добавляется позже `AuthInterceptor`'ом, в лог **не попадает**. Повторы, инициированные `TokenAuthenticator`, происходят ниже application-уровня и отдельно не логируются.

| Данные | Попадают в Logcat | Как |
| --- | --- | --- |
| Пароль | **да** [INFERRED] | тело `POST /auth/login` и `POST /auth/register` (plain-клиент) |
| Access token | **да** [INFERRED] | тело ответа login; тело ответа refresh (plain-клиент) |
| Refresh token | **да** [INFERRED] | тело ответа login; тело запроса refresh; тело запроса logout |
| `Authorization` header | нет [INFERRED] | см. выше |
| Email | да | тела login/register; JWT `sub` внутри токенов |
| `baseUrl` (IP сервера) | да | `Log.d("BASE_URL")` + URL каждого запроса |
| Содержимое фото | **да** [INFERRED] | см. §3.1 |
| Имена файлов, `mediaStoreId` | да | заголовки multipart-части; `ChecksumRepository` warnings |
| checksum-ы | да | тело `checksums/exists` |

### 3.1. Upload и `BODY`-логирование

**[INFERRED — по исходному коду OkHttp 4.x `HttpLoggingInterceptor`]** Если тело запроса не `isDuplex()` и не `isOneShot()`, логгер вызывает `requestBody.writeTo(Buffer())` — копирует **всё** тело в память. Затем проверяет первые 16 code points на «похожесть на UTF-8». Multipart-тело начинается с текстовой границы и заголовков `Content-Disposition`, поэтому проверка проходит, и логгер печатает **всё тело как строку**. `MultipartBody` и `ContentUriRequestBody` не переопределяют `isOneShot()`. Следствия для каждой загрузки:

- файл читается из `ContentResolver` лишний раз;
- в куче создаётся буфер размером с файл плюс строка примерно двойного размера (UTF-16). Для 10 МБ фото это десятки МБ временного мусора, для файла около 100 МБ — вероятный `OutOfMemoryError`;
- в Logcat пишутся сотни или тысячи строк бинарного мусора (Android-логгер OkHttp режет сообщения по ~4000 символов);
- комментарий «не загружая файл целиком в память» (`media/ContentUriRequestBody.kt:13-14`) на практике не выполняется.

Требует проверки профилированием (см. 17).

## 4. Исключения

| Место | Что видно разработчику |
| --- | --- |
| `SyncWorker.doWork` catch | `Log.e` со stacktrace |
| `NetworkViewModel` ping failure | `Log.e` с throwable |
| Per-file ошибки checksum | `Log.w` без stacktrace |
| Остальные repository-ошибки | не логируются — только текст в `Outcome.Error`, который `SyncWorker` отбрасывает, записывая лишь enum `SyncStatus` |
| Ошибки upload (текст сервера) | **теряются**: `UploadOutcome.Error.message` нигде не используется |
| Крэши (Keystore, Room, converters) | стандартный Android crash log |

## 5. WorkManager diagnostics

| Средство | Состояние |
| --- | --- |
| `WorkInfo.outputData` | только при `success(output)` — 7 счётчиков. При `retry()` данных нет |
| `WorkInfo.runAttemptCount` | не читается |
| Tags | не заданы |
| UI | только флаг «RUNNING» для `photo_sync` |
| **[INFERRED]** Внешние средства | `adb shell dumpsys jobscheduler`; `WorkManager` diagnostics broadcast (`androidx.work.diagnostics.REQUEST_DIAGNOSTICS`) — стандартные возможности библиотеки, проектом не настраивались |

## 6. Видимость ошибок в UI

| Экран | Что показывается |
| --- | --- |
| Network | диалог с текстом успеха/ошибки; фон зелёный/красный |
| Login | диалог с `message`/`fieldErrors` сервера или локальным текстом; `LoadingDialog` |
| Files | `SyncStatusCard` (4 варианта, без деталей и без времени — `timestamp` не отображается); `PermissionDeniedCard`; `errorMessage` (только UI-scan); «Scan result»; счётчики `Total / Pending / Hashing / Ready / Failed` и `Pending upload / Uploading / Synced`; в строке файла — `relativePath` · размер · статус · признак checksum · краткая причина `FailureReason` |

**[CONFIRMED]** Не показываются:
- текст ошибки сервера для файла;
- число попыток;
- время последней ошибки или синхронизации;
- причина retry прогона;
- состояние periodic-работы;
- факт очистки токенов authenticator'ом;
- счётчик `LOCAL_DELETED` (не входит в `StatusCountersRow`).

## 7. Debug UI и developer diagnostics

**[CONFIRMED]** Отдельного debug-экрана, экспорта логов, просмотра БД, кнопки «сбросить индекс» нет. Как отладочные средства в production-UI работают:
- кнопки «Test» и «Test Auth»;
- диалог сервера при каждом старте;
- счётчики статусов и «Scan result» на Files.

## 8. Сводка по чувствительным данным

| Категория | Риск | Severity (см. 16) |
| --- | --- | --- |
| Пароль и токены в Logcat во всех сборках | **[RISK]** [INFERRED] | HIGH (G1) |
| Содержимое фото в Logcat и полная буферизация в памяти | **[RISK]** [INFERRED] | HIGH (G1) |
| IP сервера в Logcat | низкий | LOW |
| `mediaStoreId`/имена файлов | низкий | LOW |
