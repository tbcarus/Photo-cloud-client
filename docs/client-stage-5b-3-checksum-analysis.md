# Этап 5B-3 — Checksum preparation для локальных фото: анализ перед реализацией

Документ — только анализ. Код не меняется, классы не создаются. Цель — зафиксировать архитектуру checksum-слоя (SHA-256 локальных фото через `ContentResolver`), переходы статусов и решения, которые нужно принять до написания кода.

Контекст входа:
- 5B-1: `MediaFile` с локальными полями; статусы `HASHING`, `CHECKSUM_READY`; DAO подготовлен; schema v2.
- 5B-2: `MediaStoreImage`, `MediaStoreRepository`, `MediaFileRepository`; scan только фото; Room синхронизируется; новые записи создаются со `status = PENDING`, `checksum = null`.

Scope 5B-3:
- считать SHA-256 локальных фото через `ContentResolver.openInputStream`;
- только для записей со `status = PENDING` (т.е. `checksum == null`);
- потоковое чтение, без загрузки файла целиком в память;
- без сервера / upload / WorkManager / retry-backoff / UI progress / video.

---

## 1. Текущее состояние DAO / статусов

### Статусы (`MediaFileStatus`)
`PENDING`, `HASHING`, `CHECKSUM_READY`, `PENDING_UPLOAD`, `UPLOADING`, `SYNCED`, `FAILED`, `LOCAL_DELETED`.
Для 5B-3 задействуются только: `PENDING` → `HASHING` → `CHECKSUM_READY` / `FAILED`.

### DAO — что уже есть и применимо
| Метод | Применимость к checksum |
| --- | --- |
| `getByStatus(status): Flow<List<MediaFile>>` | **не подходит как есть** — это `Flow`, для batch-джобы нужен one-shot snapshot (`suspend ... : List`) |
| `getPending(): Flow<...>` | то же — `Flow`, не snapshot |
| `updateStatus(id, status)` | **подходит**: выставить `HASHING` и `FAILED` |
| `updateChecksum(id, checksum, status = CHECKSUM_READY)` | **подходит**: успех — одним апдейтом ставит checksum + `CHECKSUM_READY` |
| `updateLocalMetadata(...)` | НЕ трогает checksum/status — корректно для scan, но не сбрасывает stale checksum |
| `getAllMediaStoreIds`, `deleteByMediaStoreIds`, `getLatestCreatedAt`, `exists`, `markUploaded` | к checksum не относятся |

### Чего DAO не хватает для 5B-3
1. **Snapshot-выборка pending под checksum** (suspend, не Flow, с `LIMIT`) — для пакетной обработки без загрузки всей библиотеки в память.
2. **Recovery-метод** `HASHING → PENDING` (массовый) — для записей, зависших в `HASHING` после краша.
3. **(опционально, см. §9)** условный сброс checksum при изменении файла.

`updateChecksum` и `updateStatus` закрывают запись результата и `FAILED` — новых методов для них не нужно.

---

## 2. Рекомендуемая архитектура checksum-слоя

Двухкомпонентная, по аналогии с разделением scan-слоя в 5B-2:

```
ChecksumUtil (pure)        -> SHA-256 над InputStream, без Android-зависимостей
        │
        ▼
ChecksumRepository         -> оркестрация: snapshot PENDING -> HASHING -> compute
   @ApplicationContext        -> updateChecksum(CHECKSUM_READY) / updateStatus(FAILED)
   + MediaFileDao           -> владеет ContentResolver (через context), Mutex, batch-loop
        │
        ▼
MediaFileDao (Room)
```

### Почему отдельный `ChecksumRepository`, а не метод в `MediaFileRepository`
- `MediaFileRepository` сейчас зависит только от `MediaFileDao` + `MediaStoreRepository` и **не имеет `Context`**. Для `openInputStream(uri)` нужен `ContentResolver`.
- Checksum — отдельная ответственность (CPU/IO-bound джоба), её удобно изолировать: свой `Mutex`, свой batch-loop, свой результат.
- `ChecksumUtil` — чистая функция над потоком: легко юнит-тестируется без Android.

### Где держать `ContentResolver`
В `ChecksumRepository` через `@ApplicationContext private val context: Context`, далее `context.contentResolver.openInputStream(Uri.parse(mediaFile.uri))`. Не тащить resolver в `ChecksumUtil` — util остаётся «байты → hex».

### Buffer / потоковость
- Потоковое чтение обязательным, файл целиком в память не грузим.
- Буфер **8 KB** (`DEFAULT_BUFFER_SIZE = 8192`) как дефолт; допустимо поднять до 32–64 KB для throughput на больших фото — параметр для тюнинга, не критичен.
- Реализация: цикл `read(buffer)` → `digest.update(buffer, 0, n)`, либо `DigestInputStream`. Поток закрывать через `use {}`.
- Выход: SHA-256, **lowercase hex, 64 символа** (совпадает с серверным контрактом `^[0-9a-fA-F]{64}$`, сервер нормализует в lowercase — отдаём сразу lowercase).

### Как `ChecksumUtil` возвращает результат
- `ChecksumUtil.sha256(input: InputStream): String` — кидает `IOException` наверх (ошибка потока — исключительная ситуация на уровне util).
- Решение «успех/ошибка» по конкретному файлу принимает `ChecksumRepository`: оборачивает вызов в try/catch и переводит в `FAILED`. То есть **exception как нормальный поток не используется на уровне репозитория**; на уровне util он естественен.

---

## 3. Какие классы создать

| Класс | Пакет | Назначение |
| --- | --- | --- |
| `ChecksumUtil` | `media` | `object`/util: SHA-256 над `InputStream` → lowercase hex. Без DI |
| `ChecksumRepository` | `media` | `@Singleton @Inject`: оркестрация pending → checksum, статусы, batch-loop, Mutex |
| `ChecksumResult` (+ `ChecksumOutcome`) | `media` | результат прогона (см. §10) |

---

## 4. Какие существующие файлы изменить

| Файл | Изменение | Обязательно? |
| --- | --- | --- |
| `MediaFileDao.kt` | + snapshot-выборка pending (LIMIT); + recovery `HASHING→PENDING`; + (опц.) условный сброс checksum | да (первые два), §9 (третий) |
| `MediaFileRepository.kt` | вызвать условный сброс checksum в `applyToRoom` до `updateLocalMetadata` — **только если закрываем stale-TODO в 5B-3** | по решению §9 |
| `AppModule.kt` | **не требуется** — `ChecksumRepository` создаётся Hilt через `@Inject constructor`; `ChecksumUtil` — `object` | нет |
| `MediaFile.kt` / schema | **не требуется**, если не добавляем `errorMessage` (см. §6) | нет |

---

## 5. Нужны ли новые DAO-методы

Да, минимум два, плюс один опциональный:

1. **Snapshot pending под checksum** (suspend, не Flow, с лимитом):
   - смысл: `SELECT * FROM media_files WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit`.
   - почему не `getByStatus`: тот возвращает `Flow` (реактивный поток для UI), а для последовательной batch-джобы нужен разовый список. Можно сделать как параметризованный `getByStatusOnce(status, limit)` либо специализированный `getPendingForChecksum(limit)`.

2. **Recovery `HASHING → PENDING`** (массовый):
   - смысл: `UPDATE media_files SET status = 'PENDING' WHERE status = 'HASHING'`.
   - закрывает «зависшие в HASHING» после краша (см. §3 transitions, §7).

3. **(опц.) Условный сброс checksum при изменении файла** (см. §9):
   - смысл: `UPDATE media_files SET checksum = NULL, status = 'PENDING' WHERE mediaStoreId = :id AND (size != :size OR lastModified != :lastModified)`.

Методы записи успеха (`updateChecksum`) и `FAILED` (`updateStatus`) уже существуют — новых не требуется.

---

## 6. Нужна ли schema migration v3

**Нет.** 5B-3 не требует новых колонок:
- результат checksum пишется в существующее `checksum`;
- состояние/ошибка отражаются существующим `status` (`CHECKSUM_READY` / `FAILED`).

### Про `errorMessage` — обоснование «не сейчас»
Добавление поля `errorMessage` потребовало бы migration v3 + bump версии + новый столбец ради диагностики единичных сбоев. Выгода маргинальна на текущем этапе:
- факт ошибки уже фиксируется статусом `FAILED`;
- текст ошибки на этапе без UI/retry полезен только для отладки → достаточно логировать в Logcat внутри `ChecksumRepository`.

**Рекомендация:** не добавлять `errorMessage`, не делать v3. Вернуться к нему позже вместе с retry/UI-диагностикой (тогда миграция оправдана). Это решение зафиксировать (см. §12).

---

## 7. Как считать SHA-256 (итог)

- Источник байт: `context.contentResolver.openInputStream(Uri.parse(mediaFile.uri))`.
- `ChecksumUtil.sha256(input)`: `MessageDigest.getInstance("SHA-256")`, цикл чтения 8 KB буфером, `update`, в конце `digest()` → байты → lowercase hex (64 символа).
- Поток в `use {}`; работа на `Dispatchers.IO`.
- `null` от `openInputStream` или `FileNotFoundException` → трактуется репозиторием как `FAILED` для этой записи (см. §6 error handling).

---

## 8. Как обновлять статусы

### Переходы (минимальная схема)
```
PENDING  -> HASHING        (ChecksumRepository, перед расчётом — маркер «взято в работу»)
HASHING  -> CHECKSUM_READY (успех: updateChecksum(id, hash, CHECKSUM_READY))
HASHING  -> FAILED         (любая ошибка чтения/потока: updateStatus(id, FAILED))
```

### Кто что выставляет
- **PENDING**: scanner (новые записи) и stale-reset (§9). Checksum-слой не создаёт `PENDING`, только потребляет.
- **HASHING**: `ChecksumRepository` непосредственно перед открытием потока конкретного файла (`updateStatus(id, HASHING)`).
- **CHECKSUM_READY**: `ChecksumRepository` при успехе (`updateChecksum`).
- **FAILED**: `ChecksumRepository` при ошибке.

### Краш / закрытие в `HASHING`
Записи останутся в `HASHING` навсегда, если ничего не делать. **Рекомендация:** в начале каждого прогона `computePendingChecksums()` выполнять recovery `HASHING → PENDING` (DAO-метод из §5.2), затем выбирать `PENDING`.
- Почему не на старте приложения: не требует Application-hook, self-contained, и любой повторный запуск джобы сам себя чинит.
- Это также упрощает идемпотентность: прерванный прогон при следующем запуске досчитает.

---

## 9. Stale checksum при изменении файла (TODO из 5B-2)

Контекст TODO: при повторном scan, если у существующей записи изменились `size` или `lastModified`, ранее посчитанный `checksum` может стать неверным, но `updateLocalMetadata` его не сбрасывает.

### Закрывать ли в 5B-3
**Рекомендация: закрыть минимальную версию в 5B-3**, потому что корректность checksum напрямую зависит от инвалидации при изменении файла; оставлять «битый» checksum опаснее, чем досчитать заново.

### Где и как
Чисто на уровне DAO одним условным апдейтом — **без чтения старой строки**:
```
UPDATE media_files
SET checksum = NULL, status = 'PENDING'
WHERE mediaStoreId = :id AND (size != :size OR lastModified != :lastModified)
```
Вызывать в `MediaFileRepository.applyToRoom` **до** `updateLocalMetadata` (иначе `size/lastModified` уже перезаписаны новыми значениями и сравнение всегда ложно).

### Почему так не ломает уже посчитанные checksum
- Сброс срабатывает только когда `size` ИЛИ `lastModified` реально отличаются от хранимых → неизменённые файлы (включая `CHECKSUM_READY`) не трогаются.
- Сброс в `PENDING` для записи, уже бывшей `PENDING`, безвреден (идемпотентно).
- Возврат `status` к `PENDING` означает, что следующий checksum-прогон пересчитает — корректно.

### Тонкость / открытый вопрос
Этот сброс затронет и записи в продвинутых состояниях (`PENDING_UPLOAD`/`SYNCED` — появятся позже). На 5B-3 таких состояний ещё нет, поэтому безопасно. Когда появится upload, логику отката из `SYNCED` нужно будет уточнить (откат к `PENDING` при изменившемся файле — корректно семантически, но затронет sync). Зафиксировать в §12.

---

## 10. Минимальный результат

Per-file ошибки фиксируются статусом `FAILED` в БД, поэтому прогон возвращает только агрегат:
```kotlin
data class ChecksumResult(
    val processed: Int,   // сколько записей взято в работу
    val succeeded: Int,   // переведено в CHECKSUM_READY
    val failed: Int       // переведено в FAILED
)
```
Для run-level ситуаций (нет доступа к медиа) — по аналогии с 5B-2 — обернуть в sealed:
```kotlin
sealed interface ChecksumOutcome {
    data class Success(val result: ChecksumResult) : ChecksumOutcome
    data object PermissionDenied : ChecksumOutcome
    data class Error(val message: String) : ChecksumOutcome
}
```
- `PermissionDenied` — если разрешение на медиа отсутствует (up-front проверка, как в `MediaStoreRepository`). Без него `openInputStream` бросит `SecurityException` на каждом файле — лучше проверить заранее.
- `Error` — неожиданный сбой самого прогона (не отдельного файла).
- Прогресс-колбэк/`Flow<progress>` — **не делаем** (нет UI). Defer.

---

## 11. Scope обработки, concurrency, error handling

### Scope (объём за один вызов)
- Обрабатывать **батчами с LIMIT** в цикле, пока `PENDING` не закончатся (snapshot из §5.1, напр. по 50–200), а не грузить весь список разом — ограничивает память на больших библиотеках.
- Без `limit`-параметра наружу обязательного нет; внутренний размер батча — константа. Прогресс не требуется.

### Concurrency
- **Mutex в `ChecksumRepository`** (`withLock` вокруг всего прогона) — простой корректный способ исключить два параллельных прогона и гонки статусов. `HASHING` сам по себе не защищает от второго коллектора, уже снявшего snapshot до апдейта.
- Гонка со scan: при ручном одиночном триггере риск низкий. Возможные эффекты и почему допустимо:
  - scan удалил файл в момент hashing → `updateChecksum`/`updateStatus` сделают no-op (нет строки по `id`) — безопасно;
  - scan вызвал `updateLocalMetadata`/stale-reset параллельно — не разрушает данные.
- **Рекомендация на 5B-3:** Mutex только внутри `ChecksumRepository`; не делить его со scan. Координацию scan↔checksum отложить до WorkManager-этапа (зафиксировать в §12). На уровне вызывающего кода — не запускать scan и checksum одновременно.

### Error handling (по случаям)
| Ситуация | Поведение |
| --- | --- |
| `openInputStream` вернул `null` / `FileNotFoundException` | `FAILED` для записи, прогон продолжается |
| Файл удалён между scan и checksum | `FileNotFoundException` → `FAILED` (физически запись уберёт следующий scan как stale) |
| `IOException` / оборван поток | `FAILED`, продолжаем |
| `SecurityException` (permission пропало в процессе) | прерывание прогона → `ChecksumOutcome.PermissionDenied` (или per-file `FAILED` + остановка) |
| checksum уже есть / статус не `PENDING` | не попадёт в выборку (snapshot только `PENDING`); после recovery `HASHING→PENDING` дубли исключены |
| permission отсутствует на старте | up-front проверка → `PermissionDenied`, прогон не начинается |

---

## 12. Вопросы / уточнения перед реализацией

1. **Stale checksum (§9):** закрываем минимальную инвалидацию в 5B-3 через условный DAO-апдейт + правку `applyToRoom`? (Рекомендация: да.)
2. **Recovery `HASHING→PENDING` (§8):** делаем в начале каждого прогона, а не на старте приложения? (Рекомендация: да, start-of-run.)
3. **`errorMessage` / migration v3 (§6):** подтверждаем, что НЕ добавляем сейчас, ошибки только статусом `FAILED` + Logcat? (Рекомендация: да, не добавляем.)
4. **Результат (§10):** `ChecksumResult` + sealed `ChecksumOutcome` (Success/PermissionDenied/Error)? (Рекомендация: да.)
5. **Батч-стратегия (§11):** цикл по `PENDING` с внутренним LIMIT до исчерпания за один вызов `computePendingChecksums()`? (Рекомендация: да.)
6. **Точка вызова:** на 5B-3 checksum-слой остаётся «библиотечным» (вызов из теста/отладки), без UI-триггера и без `FilesViewModel`? Или нужна минимальная точка вызова? (Рекомендация: библиотечный, без UI.)
7. **Permission (§10/§11):** up-front проверка с `PermissionDenied`, плюс per-file `FAILED` как страховка? (Рекомендация: да.)
8. **Mutex (§11):** только в `ChecksumRepository`, координацию со scan отложить до WorkManager? (Рекомендация: да.)
9. **Buffer size (§2):** 8 KB по умолчанию приемлемо? (Рекомендация: да, тюнинг позже.)
10. **Расположение `ContentResolver`:** в `ChecksumRepository` через `@ApplicationContext`, `ChecksumUtil` чистый? (Рекомендация: да.)

---

## Что НЕ делать на 5B-3 (зафиксировано)

- Server checksum pre-check (`POST /files/checksums/exists`) — нет.
- Upload / `link-existing` / любые server file/folder API и DTO — нет.
- WorkManager / фоновое расписание / retry-backoff — нет.
- UI progress, `FilesScreen`, `FilesViewModel`, navigation — нет.
- Video checksum (`MediaStore.Video`), `READ_MEDIA_VIDEO` — нет.
- Schema migration v3 / `errorMessage` колонка — нет (см. §6).
- Изменение auth/network/session, `ApiServiceFactory`, `TokenAuthenticator`, `ServerRepository` — нет.
- Статусы `PENDING_UPLOAD` / `UPLOADING` / `SYNCED` / `LOCAL_DELETED` — checksum-слой их не выставляет.
