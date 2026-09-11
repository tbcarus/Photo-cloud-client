# 10. Errors & Retries

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Пути — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`. Метки — см. [00-executive-summary.md](00-executive-summary.md).
> Эталон сравнения — `client-handoff/03-error-contract.md`.

---

## 1. Стили обработки ошибок

| Слой | Механизм | Пример |
| --- | --- | --- |
| Auth / server | `runCatching { … throw Exception(message) }` → `kotlin.Result<T>`; ViewModel показывает `localizedMessage` | `auth/AuthRepository.kt:30-72` |
| Server ping | ручной `try/catch` по типам → `Result.failure(Exception(текст))` | `core/server/ServerRepository.kt:18-35` |
| Media pipeline | sealed `…Outcome` (`Success` / `PermissionDenied` / `Error(message)`); per-file ошибки фиксируются в Room | `media/*Result.kt` |
| Worker | `try { runSync() } catch (Exception)` → `ERROR` + `Result.retry()` | `media/SyncWorker.kt:36-45` |
| HTTP body | `ApiErrorParser.parse()` → строка | `core/network/ApiErrorParser.kt` |
| OkHttp auth | `TokenAuthenticator` | `core/network/TokenAuthenticator.kt` |

**[CONFIRMED]** Собственной иерархии исключений или типизированной модели ошибки (код, retryable, user message) нет. Всё сводится к строке либо к `FailureReason`.

## 2. Типы исключений, встречающиеся в коде

| Исключение | Где возникает | Где обрабатывается |
| --- | --- | --- |
| `IOException` (сеть, таймаут, обрыв) | `Call.execute()` | upload → transient; pre-check/folder → `catch (Exception)` → `Error`; ping → «Ошибка сети»; auth → `Result.failure` |
| `FileNotFoundException` | `openInputStream` | checksum, pre-open upload → `FILE_NOT_FOUND` |
| `SecurityException` | `openInputStream` | checksum, pre-open upload → `PERMISSION`; из `writeTo` — **не перехвачено** в upload |
| `IOException` при чтении содержимого | hashing | `CHECKSUM_IO` |
| `IOException("Не удалось открыть поток…")` | `ContentUriRequestBody.writeTo` | upload → transient |
| `ProtocolException` [INFERRED] | OkHttp при несовпадении `Content-Length` | upload → transient |
| `IllegalStateException("Server URL is not configured")` | `ApiServiceFactory` при пустом `baseUrl` | folder/pre-check → `Error`; в upload-цикле — не перехвачено |
| `JsonSyntaxException` | `ApiErrorParser` | возвращается сырое тело |
| `retrofit2.HttpException` | ловится в `ServerRepository.kt:30` | **[CONFIRMED]** мёртвая ветка: `Call.execute()` это исключение не бросает |
| `CancellationException` | остановка worker'а | ловится общим `catch (Exception)` в `doWork` → ложный `ERROR` |
| Ошибки Room | любой DAO-вызов | уровень стадии → `Error` / исключение → `retry()` |
| Ошибки Keystore/EncryptedSharedPreferences | конструктор `EncryptedPrefsTokenStorage` | не обрабатываются → падение [INFERRED] |

## 3. `FailureReason` — классификация

| Reason | Класс | `isRetryable` | Фактический retry |
| --- | --- | --- | --- |
| `CHECKSUM_IO` | transient (локальный I/O) | true | авто, `retryCount < 3`, cooldown 5 мин |
| `FILE_NOT_FOUND` | permanent (локальный) | false | нет |
| `PERMISSION` | permanent (локальный) | false | нет |
| `NOT_IMAGE` | permanent (политика клиента) | false | нет |
| `HTTP_400` | permanent (сервер) | false | нет |
| `HTTP_409` | permanent (сервер) | false | нет |
| `HTTP_413` | permanent (сервер) | false | нет |
| `UNKNOWN` | permanent | false | нет |

**[CONFIRMED]** Transient-ошибки upload (`IOException`, `401`/`403`/`404`/`5xx`, `2xx` без id) в `FailureReason` не попадают: файл возвращается в `PENDING_UPLOAD` без `retryCount++`. Счётчика попыток upload нет.

## 4. Главная таблица ошибок

| Failure | Source | Stored state | Retryable | Retry trigger | User visible |
| --- | --- | --- | --- | --- | --- |
| `baseUrl` пуст (процесс без UI) | `SyncWorker.kt:51` | Room не меняется; `SERVER_UNAVAILABLE` (память) | да, **бесконечно** | backoff WorkManager | карточка «Сервер недоступен…», если открыт UI в том же процессе |
| Ping `/test` не прошёл | `SyncWorker.kt:51-55` | то же | да | backoff | то же |
| Нет разрешения на медиа (фон) | `MediaStoreRepository.kt:39-41`, `ChecksumRepository.kt:71-73` | нет | — | следующий триггер | нет (worker `success()`, статус не пишется) |
| Нет разрешения (UI-scan) | `FilesViewModel.scanPhotos` | `permissionDenied=true` | — | повторный вход на Files | `PermissionDeniedCard` |
| MediaStore `null`-курсор / исключение | `MediaStoreRepository.kt:67, 135-137` | нет | да | backoff | «Ошибка синхронизации…» / `errorMessage` при UI-scan |
| Некорректная строка курсора | `MediaStoreRepository.kt:128-130` | строка пропущена | неявно (следующий scan) | — | нет; **[RISK]** пропущенный `_ID` будет удалён из Room как stale |
| Ошибка Room в scan | `MediaFileRepository.kt:31-33` | частично применено | да | backoff | «Ошибка синхронизации» |
| Hashing: `FileNotFoundException` / `null`-поток | `ChecksumRepository.kt:107-112, 123-126` | `FAILED(FILE_NOT_FOUND)` | нет | только изменение файла | «File not found» в списке |
| Hashing: `SecurityException` | `ChecksumRepository.kt:118-122` | `FAILED(PERMISSION)` | нет | только изменение файла | «Permission denied» |
| Hashing: `IOException` | `ChecksumRepository.kt:127-131` | `FAILED(CHECKSUM_IO)`, `retryCount+1` | да, ≤ 3 | шаг 0 следующего `SyncWorker` после 5 мин | «Checksum I/O error» |
| Hashing: прочее | `ChecksumRepository.kt:132-136` | `FAILED(UNKNOWN)` | нет | изменение файла | «Unknown error» |
| Checksum-стадия: исключение уровня прогона | `ChecksumRepository.kt:81-83` | строки могут остаться `HASHING` | да | backoff + recovery | «Ошибка синхронизации» |
| `CAMERA` не резолвится (сеть / `401` / `5xx` / пустой ответ) | `FolderRepository.kt:37-61` | pre-check: `CHECKSUM_READY` остаются; upload: продолжает **без `folderId`** | да | backoff | «Ошибка синхронизации» |
| Pre-check: HTTP не-`2xx` (`400`/`401`/`404`/`5xx`) | `ChecksumPrecheckRepository.kt:100-103` | предыдущие батчи применены | да (**любой код**, включая `400`) | backoff | «Ошибка синхронизации» |
| Pre-check: сеть / исключение | `ChecksumPrecheckRepository.kt:140-142` | то же | да | backoff | то же |
| Pre-check: checksum нет в ответе | `ChecksumPrecheckRepository.kt:118, 135-138` | `CHECKSUM_READY` | да | следующий прогон | нет |
| Upload: MIME не image | `FileUploadRepository.kt:80-84` | `FAILED(NOT_IMAGE)` | нет | изменение файла | «Not an image» |
| Upload: pre-open FNF / Security | `FileUploadRepository.kt:88-93` | `FAILED(FILE_NOT_FOUND / PERMISSION)` | нет | изменение файла | в списке |
| Upload: pre-open — иное исключение | `FileUploadRepository.kt:155-162` | `PENDING_UPLOAD` | да, бесконечно | backoff | «Ошибка синхронизации» |
| Upload: `IOException` | `FileUploadRepository.kt:100-109` | `PENDING_UPLOAD`, прогон оборван | да, бесконечно | backoff | «Синхронизация ожидает повтора» |
| Upload: `2xx` без `id` | `FileUploadRepository.kt:117-125` | `PENDING_UPLOAD` | да | backoff | то же |
| Upload: `400` | `FileUploadRepository.kt:178` | `FAILED(HTTP_400)` | нет | изменение файла | «HTTP 400» |
| Upload: `409` | `FileUploadRepository.kt:179` | `FAILED(HTTP_409)` | нет | изменение файла | «HTTP 409» |
| Upload: `413` | `FileUploadRepository.kt:180` | `FAILED(HTTP_413)` | нет | изменение `size` | «HTTP 413» |
| Upload: `401` (refresh не помог / токенов нет) | `FileUploadRepository.kt:134-143` | `PENDING_UPLOAD` | да, бесконечно | backoff | «Синхронизация ожидает повтора»; призыва войти нет |
| Upload: `403` / `404` / `5xx` / прочие | то же | `PENDING_UPLOAD` | да, бесконечно | backoff | то же |
| Upload: `SecurityException` из `writeTo` и прочие не-IO | `FileUploadRepository.kt:98-109` | `UPLOADING` до recovery | да | backoff + recovery | «Ошибка синхронизации» |
| Refresh: не-`2xx` | `TokenAuthenticator.kt:62-65` | токены удалены | нет | повторный login | форма входа на вкладке Login (если открыта); явного уведомления нет |
| Refresh: `IOException` | `TokenAuthenticator.kt:58-60` | нет | зависит от вызывающего | — | как ошибка исходного вызова |
| Login `400`/`401` | `AuthRepository.kt:49-51` | нет | вручную | — | диалог с текстом сервера |
| Register `400`/`409` | `AuthRepository.kt:33-34` | нет | вручную | — | диалог |
| Logout: HTTP-ошибка | `AuthRepository.kt:59-62` | токены удалены | — | — | диалог «…» (даже для `404`) |
| Logout: `IOException` | `AuthRepository.kt:58` | **токены остаются** | вручную | — | диалог |
| Ping из UI не прошёл | `NetworkViewModel.kt:75-84` | `baseUrl` не меняется | вручную | — | диалог + красный фон |
| Остановка worker'а (`CancellationException`) | `SyncWorker.kt:38-44` | `HASHING`/`UPLOADING` до recovery | да | WorkManager | ложное «Ошибка синхронизации» |

## 5. Автоматическое восстановление failed items

**[CONFIRMED]** Поведение двухполярное:

1. **Permanent-ветка.** `FAILED` с причиной, отличной от `CHECKSUM_IO`, — тупик. Выйти можно только через изменение файла (T2) или удаление строки (T3). Ручного повтора нет. `PERMISSION`/`FILE_NOT_FOUND` могут быть временными (временная недоступность тома, повторная выдача разрешения), но классифицированы как permanent. `HTTP_400` на upload возникает, например, для пустого файла; если файл потом допишется, `size` изменится и сработает T2.
2. **Transient-ветка.** Transient-ошибки upload и все ошибки уровня стадий приводят к `Result.retry()` **без лимита**. **[INFERRED]** Backoff WorkManager по умолчанию — экспоненциальный, 30 с × 2ⁿ, потолок 5 ч; `setBackoffCriteria` в коде не вызывается. Детерминированная transient-ошибка на конкретном файле (стабильный `5xx` на этом файле, несовпадение `Content-Length`) останавливает очередь навсегда: файл возвращается в `PENDING_UPLOAD` и первым выбирается снова (`ORDER BY createdAt DESC`).
3. **Auto-retry `CHECKSUM_IO`** — единственный ограниченный механизм: ≤ 3 подряд идущих неудач, cooldown 5 мин, до 500 строк за прогон. Выполняется только после успешного ping сервера, хотя hashing — локальная операция.
4. **Recovery `HASHING`/`UPLOADING`** — безусловный, в начале соответствующей стадии.
5. **Строки `FAILED` до Stage 5K** (`failureReason = NULL`) не восстанавливаются никогда (см. 04 §4.2).

## 6. Сравнение с серверным `03-error-contract.md`

| Серверная ситуация | Контракт: retryable / действие | Android | Match |
| --- | --- | --- | --- |
| Любой защищённый `401 UNAUTHORIZED` | refresh → повтор 1 раз → иначе re-login | refresh → 1 повтор → `null`; re-login UI нет | ⚠️ PARTIAL |
| refresh `401 INVALID_REFRESH_TOKEN` / `403 REFRESH_TOKEN_REVOKED` | re-login | очистка токенов | ✅ (без UI) |
| refresh `5xx` / сеть | повторить позже, состояние не менять | `5xx` → **очистка**; сеть → без изменений | ❌ |
| logout `401` | повторить после refresh | через authenticator | ✅ |
| logout `404 REFRESH_TOKEN_NOT_FOUND` | считать успехом | ошибка в UI (токены очищены) | ⚠️ |
| logout `403 OWNERSHIP` | очистить хранилище | очищает | ✅ |
| upload `400 BAD_REQUEST` (пустой файл / нет части) | ❌ | `FAILED(HTTP_400)` | ✅ |
| upload `404 NOT_FOUND` (папка) | ❌ → пересинхронизировать дерево | transient → следующий прогон заново резолвит `CAMERA` | ✅ (по сути) |
| upload `409 CONFLICT` (имя) | только с другим именем | `FAILED(HTTP_409)`, без переименования | ✅ (в `CAMERA` не возникает) |
| upload `413 FILE_TOO_LARGE` | никогда | `FAILED(HTTP_413)` | ✅; предварительного фильтра > 100 MiB нет — байты передаются до отказа |
| upload таймаут / обрыв | ✅ безопасно | transient | ✅ |
| `checksums/exists` `400 VALIDATION_ERROR` | ❌ | retry прогона — **бесконечно** | ❌ (на практике недостижимо: клиент шлёт валидный lowercase hex) |
| `checksums/exists` `400 BAD_REQUEST` (> 500) | разбить на батчи | клиент заранее шлёт ≤ 500 | ✅ |
| `checksums/exists` `404` (папка) | ❌ | retry прогона, `CAMERA` резолвится заново | ✅ |
| `500` без `code` (формат C) | backoff, ограниченное число попыток | backoff, **без ограничения** | ⚠️ |
| `500 DATABASE_CONSTRAINT_VIOLATION` | никогда не повторять | клиент не вызывает `move` — ситуация недостижима | N/A |
| `501` (формат B) | отключить в UI | клиент не вызывает `501`-эндпоинты | N/A |
| `503` / нет ответа | backoff | backoff | ✅ |
| Разбор формата A / B / C | пробовать A, иначе по статусу | `ApiErrorParser`: A и B → `message`, C → `"HTTP 500"`; `code` наружу не отдаётся | ✅ (разбор) / ⚠️ (машинная классификация по `code` не используется) |
| `message` сервера не показывать как есть | рекомендуется | показывается как есть | ⚠️ |
| Дубль upload → `200` + существующий `id` | не ошибка | `SYNCED` | ✅ |
