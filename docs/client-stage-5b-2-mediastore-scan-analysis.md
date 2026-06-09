# Этап 5B-2 — MediaStore scanner (только фото): анализ перед реализацией

Документ — только анализ. Код не меняется, классы не создаются. Цель — зафиксировать архитектуру scanner-а для images, точки подключения и решения, которые нужно принять до написания кода.

Контекст входа (после 5B-1):
- `MediaFile` поля: `mediaStoreId` (PK), `serverFileId?`, `uri`, `displayName`, `relativePath?`, `mimeType`, `size`, `createdAt`, `lastModified`, `checksum?`, `status`.
- `MediaFileStatus`: `PENDING`, `HASHING`, `CHECKSUM_READY`, `PENDING_UPLOAD`, `UPLOADING`, `SYNCED`, `FAILED`, `LOCAL_DELETED`.
- DAO уже содержит: `insert`, `insertAll(IGNORE)`, `getAll()` (`ORDER BY createdAt DESC`), `getByStatus`, `getPending()`, `updateStatus`, `updateChecksum(+status)`, `updateLocalMetadata(...)`, `markUploaded`, `exists`, `getAllMediaStoreIds()`, `deleteByMediaStoreIds(ids)`, `getLatestCreatedAt()`.
- DB version 2, migration 1→2 на месте, schema export включён.
- DI: `AppModule` отдаёт `AppDatabase` и `MediaFileDao`.

---

## 0. Что уже готово в проекте (важно для scope)

| Элемент | Состояние | Вывод для 5B-2 |
| --- | --- | --- |
| `READ_MEDIA_IMAGES` | объявлен в `app/src/main/AndroidManifest.xml` | менять не нужно |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | объявлен | покрывает API 29–32, менять не нужно |
| `INTERNET` | объявлен | — |
| Runtime permission flow | **отсутствует** | требуется решение (см. §1) |
| MediaStore scan код | **отсутствует** | создаём |
| `MediaStoreRepository` | **отсутствует** | создаём |
| `MediaFileRepository` | **отсутствует** | создаём |
| `MediaFileDao` методы для scan | присутствуют (`getAllMediaStoreIds`, `deleteByMediaStoreIds`, `updateLocalMetadata`, `insertAll`) | достаточно, кроме одного transaction-метода (см. §6) |
| `FilesScreen` | заглушка `CenteredText("Files")` | UI не трогаем, только фиксируем точку подключения |

Дополнительно обнаружено: существует **второй манифест** `app/AndroidManifest.xml` (вне source set, legacy, package-атрибут, без permissions). Активный — `app/src/main/AndroidManifest.xml`. На 5B-2 это не блокер, но файл-сирота стоит удалить отдельной задачей, чтобы не путать. **В рамках 5B-2 не трогаем.**

---

## 1. Permissions

### Текущее покрытие
`minSdk = 29`, `targetSdk = 35`, `compileSdk = 35`.

| API уровень | Нужное разрешение | Объявлено? |
| --- | --- | --- |
| 29–32 | `READ_EXTERNAL_STORAGE` | да (`maxSdkVersion=32`) |
| 33+ | `READ_MEDIA_IMAGES` | да |
| 34+ (partial) | `READ_MEDIA_VISUAL_USER_SELECTED` | нет — **сознательно откладываем** |

Манифест-объявлений для базового сценария достаточно. **Менять манифест на 5B-2 не нужно.**

### Runtime permission
Эти разрешения — dangerous, на API 23+ требуют runtime-grant. Runtime-flow в проекте **нет**.

Что произойдёт без grant при `ContentResolver.query(...)`:
- как правило вернётся пустой/`null` cursor (фактически 0 строк), в части прошивок возможен `SecurityException`.
- то есть scanner без разрешения «молча» вернёт пустой результат либо упадёт — оба варианта надо обработать.

### Решение для 5B-2 (рекомендация)
- **Не строить permission UI на этом этапе.**
- Scanner должен сам делать **проверку** `ContextCompat.checkSelfPermission(...)` перед запросом и при отсутствии grant возвращать «пустой»/`PermissionDenied` результат, а не падать. Это снимает зависимость от UI и делает scanner вызываемым из теста/отладки.
- Хелпер выбора нужного permission по версии:
  - `Build.VERSION.SDK_INT >= 33` → `READ_MEDIA_IMAGES`
  - иначе → `READ_EXTERNAL_STORAGE`.
- Сам runtime-запрос (`ActivityResultContracts.RequestPermission`) и UI — **отдельный будущий этап** (5B-3 или permission-этап). Зафиксировать как открытый вопрос (§10).

### Partial access (API 34+)
`READ_MEDIA_VISUAL_USER_SELECTED` даёт доступ только к выбранным фото. На 5B-2 не обрабатываем; зафиксировать как известное ограничение — при partial-grant scanner увидит только подмножество, это допустимо для текущего шага.

---

## 2. MediaStore columns → MediaFile mapping

Источник: `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`.

### Projection
```
MediaStore.Images.Media._ID
MediaStore.Images.Media.DISPLAY_NAME
MediaStore.Images.Media.RELATIVE_PATH      // API 29+, у нас minSdk=29 — безопасно
MediaStore.Images.Media.MIME_TYPE
MediaStore.Images.Media.SIZE
MediaStore.Images.Media.DATE_TAKEN
MediaStore.Images.Media.DATE_MODIFIED
```

### Маппинг
| MediaStore column | MediaFile поле | Тип / нюанс |
| --- | --- | --- |
| `_ID` | `mediaStoreId` | `Long`, PK. URI строится из него |
| `DISPLAY_NAME` | `displayName` | `String`, NOT NULL. Теоретически может быть пустым — fallback на `"" `/`unknown` |
| `RELATIVE_PATH` | `relativePath` | `String?`, напр. `DCIM/Camera/`. Доступно с API 29 |
| `MIME_TYPE` | `mimeType` | `String`, NOT NULL. Может быть null в редких записях — fallback `image/*` или skip |
| `SIZE` | `size` | `Long`. Может быть 0 для битых записей |
| `DATE_TAKEN` | `createdAt` | `Long` **в миллисекундах** (UTC). **Может быть null/0** |
| `DATE_MODIFIED` | `lastModified` | `Long` **в секундах** — см. §6 timestamps |
| `_ID` → URI | `uri` | `ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id).toString()` |

Поля, которые scanner **не** заполняет: `serverFileId` (остаётся null), `checksum` (null), `status` (`PENDING` для новых, не трогается для существующих).

---

## 3. Обработка timestamps (критичная развилка)

Единицы измерения разные — это главный источник будущих багов:

- `DATE_TAKEN` — **миллисекунды**.
- `DATE_MODIFIED` (и `DATE_ADDED`) — **секунды**.

В 5B-1 поле `lastModified` закомментировано как «секунды», а `createdAt` — как «ms». Это рассинхрон единиц внутри одной entity.

### Рекомендация
Привести оба timestamp к **одной единице — миллисекундам** на этапе маппинга:
- `createdAt`:
  - если `DATE_TAKEN` не null и > 0 → берём как есть (ms);
  - иначе fallback → `DATE_MODIFIED * 1000` (а если и его нет → `DATE_ADDED * 1000`, иначе `0`/now — решить, см. §10).
- `lastModified`: `DATE_MODIFIED * 1000` (нормализуем в ms).

Это решение **не меняет схему** (типы `Long` те же), только конвенцию заполнения. Но нужно **сознательно зафиксировать**, что `lastModified` теперь хранится в ms, и при необходимости поправить комментарий в `MediaFile.kt` (это часть будущей реализации, не текущего анализа).

Альтернатива (не рекомендуется): хранить `lastModified` в секундах «как есть» — тогда сравнения с `createdAt` и любые дельты будут опасны. Единая единица проще и безопаснее.

---

## 4. Архитектура 5B-2 (рекомендация)

Двухслойная схема, чёткое разделение «источник данных устройства» ↔ «локальный индекс»:

```
MediaStoreRepository   -> читает MediaStore, отдаёт List<MediaStoreImage> (raw scan-records)
        │
        ▼
MediaFileRepository    -> маппит в MediaFile, синхронит Room (insert/update/delete stale),
                          отдаёт Flow<List<MediaFile>> и ScanResult
        │
        ▼
MediaFileDao (Room)    -> уже есть
```

### Почему scan-record DTO, а не сразу `MediaFile`
Рекомендуется промежуточный `MediaStoreImage`, а не возврат `MediaFile` напрямую из `MediaStoreRepository`:
- `MediaStoreRepository` не должен знать про `status`, `serverFileId`, `checksum` — это поля локального индекса/sync, не устройства.
- для **существующих** записей мы вызываем `updateLocalMetadata(...)` (только metadata-колонки), а не пересоздаём `MediaFile` — иначе затрём `checksum`/`status`/`serverFileId`. Scan-record естественно отражает «только то, что реально пришло из MediaStore».
- маппинг и решение insert-vs-update — ответственность `MediaFileRepository`.

Предлагаемый DTO:
```kotlin
data class MediaStoreImage(
    val mediaStoreId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,     // нормализовано в ms
    val lastModified: Long   // нормализовано в ms
)
```

### MediaStoreRepository (контур)
```kotlin
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun scanImages(): List<MediaStoreImage>  // на Dispatchers.IO
}
```
- зависимость: только `@ApplicationContext Context`; `ContentResolver` берётся как `context.contentResolver` (отдельно инжектить не нужно).
- внутри: проверка permission → если нет grant, вернуть `emptyList()`; `query(...)` под `use {}`; маппинг строк; работа на `Dispatchers.IO`.
- пакет: `ru.tbcarus.photo_cloud_client.media` (рядом с entity/dao) либо `media/scan`. Рекомендация — `media`.

### MediaFileRepository (контур)
```kotlin
class MediaFileRepository @Inject constructor(
    private val dao: MediaFileDao,
    private val mediaStore: MediaStoreRepository
) {
    fun observeAll(): Flow<List<MediaFile>> = dao.getAll()
    suspend fun scanImages(): ScanResult
}
```
- пакет: `media`.

---

## 5. Какие классы создать / какие файлы изменить

### Создать
| Класс | Пакет | Назначение |
| --- | --- | --- |
| `MediaStoreImage` | `media` | raw scan-record DTO |
| `MediaStoreRepository` | `media` | чтение MediaStore.Images |
| `MediaFileRepository` | `media` | маппинг + sync Room + Flow |
| `ScanResult` | `media` | результат scan (§8) |
| (опц.) хелпер permission-check | `media` или `utils` | выбор/проверка нужного разрешения |

### Изменить (минимум)
| Файл | Изменение | Обязательно? |
| --- | --- | --- |
| `MediaFileDao.kt` | добавить один `@Transaction`-метод для атомарного scan-sync (§6) | желательно |
| `MediaFile.kt` | поправить комментарий `lastModified` → «ms» (если принимаем §3) | косметика |
| `AppModule.kt` | **не требуется** — оба repo создаются Hilt через `@Inject constructor` | нет |

### НЕ трогать
`AndroidManifest.xml`, `AppDatabase.kt`, `FilesScreen.kt`, navigation, network/auth, server DTO.

---

## 6. Стратегия sync: insert / update / stale + транзакция

### Anti-pattern, которого избегаем
- **Не** использовать `@Upsert` по всему `MediaFile` — Upsert перезапишет строку целиком и затрёт `checksum`, `status`, `serverFileId`. Это разрушит будущий checksum/upload-этап.
- **Не** вызывать `exists()` per-item в цикле — на больших библиотеках это тысячи отдельных запросов.

### Рекомендуемый алгоритм (без per-item ветвления)
1. `scanned = mediaStore.scanImages()` → список `MediaStoreImage`.
2. Новые строки: `dao.insertAll(scanned.map { it.toNewMediaFile(status = PENDING) })` с `OnConflictStrategy.IGNORE` — существующие PK игнорируются, новые вставляются с `checksum=null`, `status=PENDING`.
3. Metadata существующих: для каждого scanned вызвать `dao.updateLocalMetadata(...)` — обновляет только metadata-колонки, сохраняя `checksum`/`status`/`serverFileId`. (Для новых это no-op/повторное проставление тех же значений — безопасно.)
4. Stale detection (§7) и `deleteByMediaStoreIds(...)`.

Шаг 3 — это N апдейтов. Для 5B-2 приемлемо. Оптимизация (обновлять только при изменившемся `lastModified`/`size`) — **будущий этап**, зафиксировать в §10.

### Транзакция / consistency
Три шага (insert / update / delete) должны быть атомарны, иначе при сбое в середине индекс окажется частично обновлён.

Два варианта:
- **(A, рекомендуется)** добавить в DAO один `@Transaction`-метод (default/abstract), который принимает `scanned: List<MediaFile>` и `staleIds: List<Long>` и внутри вызывает `insertAll` + апдейты + delete. Room обернёт всё в одну транзакцию.
- **(B)** использовать `androidx.room.withTransaction { }` (room-ktx уже подключён) в `MediaFileRepository`, но тогда репозиторию нужен доступ к `AppDatabase` (доп. зависимость в конструктор).

Рекомендация — **вариант A**: один новый DAO-метод, репозиторий остаётся с зависимостью только от DAO.

### Лимит SQLite-переменных
`deleteByMediaStoreIds(ids IN (...))` и пакетные операции упираются в лимит ~999/1000 host-параметров SQLite. Для больших библиотек **чанковать** списки (по ~500–900) на стороне репозитория. Зафиксировать как требование реализации.

---

## 7. Stale detection

Минимальная стратегия:
1. `roomIds = dao.getAllMediaStoreIds().toHashSet()`.
2. `scannedIds = scanned.map { it.mediaStoreId }.toHashSet()`.
3. `staleIds = roomIds - scannedIds`.
4. `dao.deleteByMediaStoreIds(staleIds)` (чанками, §6).

### Решения
- **Hard delete сейчас.** `LOCAL_DELETED` на 5B-2 **не используем**: он задуман для «файл был засинкан на сервер, потом удалён локально → надо отразить на сервере». Пока нет upload/sync, помечать нечем и незачем. Удаляем строки физически из Room. `LOCAL_DELETED` остаётся в enum для будущего, но scanner его не ставит.
  - Тонкость на будущее: когда появится upload, удалять `SYNCED`-записи стирая `serverFileId` — потеря связи с сервером. Тогда логику нужно будет развести (stale + был synced → `LOCAL_DELETED`, иначе hard delete). На 5B-2 это **вне scope**, зафиксировать в §10.
- **Память.** ID — `Long`; даже десятки тысяч фото это сотни КБ в `HashSet` — приемлемо. Полные `MediaFile` в память целиком не грузим (используем `getAllMediaStoreIds`, а не `getAll`).

---

## 8. Результат scan

Рекомендуемый минимальный тип:
```kotlin
data class ScanResult(
    val scanned: Int,          // сколько строк пришло из MediaStore
    val insertedOrUpdated: Int,// сколько затронуто в Room (insert + metadata update)
    val deletedStale: Int      // сколько удалено
)
```

Разделять `inserted` и `updated` отдельно — приятно, но требует знать, что реально вставилось (`insertAll(IGNORE)` не возвращает это напрямую — возвращает rowId/-1 на конфликт). Для 5B-2 достаточно агрегата `insertedOrUpdated`. Точный split — будущая опция.

### Возврат ошибок
- `MediaStoreRepository.scanImages()` при отсутствии permission → `emptyList()` (или отдельный флаг — см. ниже), `cursor == null` → `emptyList()`, неполная строка → пропуск этой строки (не падать на одной битой записи).
- `MediaFileRepository.scanImages()` → возвращает `ScanResult`. Для отличения «нет доступа» от «0 фото» рекомендуется одно из:
  - `Result<ScanResult>` с явным `PermissionDeniedException`, **или**
  - sealed-результат `ScanOutcome { Success(ScanResult) | PermissionDenied }`.
- Рекомендация: **sealed `ScanOutcome`** — явнее, чем перегружать `ScanResult` или кидать исключение для штатной ситуации «нет grant». Решение зафиксировать в §10.

---

## 9. DI

- `MediaStoreRepository` и `MediaFileRepository` — конкретные классы с `@Inject constructor`; Hilt создаёт их **без** providers. Добавлять методы в `AppModule` **не нужно**.
- `MediaFileDao` уже предоставляется (`provideMediaFileDao`).
- `@ApplicationContext Context` доступен из Hilt «из коробки».
- Scope: репозитории логично сделать `@Singleton` (особенно `MediaStoreRepository` без состояния — необязательно, но дёшево). Решить при реализации.

Вывод: **изменения в DI на 5B-2 не требуются**, кроме (опционально) `@Singleton`-аннотаций на самих классах.

---

## 10. Открытые вопросы / решения до реализации

1. **Единица `lastModified`** — принимаем нормализацию в ms (§3)? Если да — поправить комментарий в `MediaFile.kt`. (Рекомендация: да.)
2. **Fallback `createdAt`**, когда `DATE_TAKEN` null/0: `DATE_MODIFIED*1000` → `DATE_ADDED*1000` → ? Финальный fallback (`0` vs `System.currentTimeMillis()`)? (Рекомендация: цепочка taken→modified→added, иначе `0`.)
3. **Тип результата**: `ScanResult` + sealed `ScanOutcome` для PermissionDenied, или `Result<ScanResult>`? (Рекомендация: `ScanOutcome`.)
4. **Permission UI**: подтверждаем, что runtime-запрос (UI) — отдельный будущий этап, а на 5B-2 scanner лишь graceful-проверяет grant и возвращает `PermissionDenied`/empty? (Рекомендация: да.)
5. **Точка вызова scan на 5B-2**: вызывать ли scan откуда-либо (debug-кнопка/`FilesViewModel`) или оставить scanner «библиотечным» без UI-триггера? Скоуп говорит UI не трогать — рекомендуется пока **не** подключать к UI, только класс + (опц.) unit/instrumented проверка.
6. **DAO transaction-метод** (вариант A §6) — подтверждаем добавление одного `@Transaction`-метода как единственное изменение DAO?
7. **Чанкование** для `deleteByMediaStoreIds` и пакетных апдейтов — фиксируем как требование реализации (лимит ~999 переменных SQLite).
8. **Будущее взаимодействие stale + `SYNCED`** (когда появится upload): hard delete vs `LOCAL_DELETED`. Вне scope 5B-2, но зафиксировано чтобы не сломать sync позже.
9. **Legacy `app/AndroidManifest.xml`** — удалить отдельной задачей (не в 5B-2).
10. **Partial access (API 34+, `READ_MEDIA_VISUAL_USER_SELECTED`)** — осознанно не поддерживаем сейчас; при partial-grant scan вернёт подмножество. Подтвердить, что это приемлемо для текущего шага.

---

## Что НЕ делать на 5B-2 (зафиксировано)

- Checksum (SHA-256), `HASHING`/`CHECKSUM_READY` логика — не трогаем (поля/статусы уже есть, но не используем).
- Video scanning (`MediaStore.Video`) — нет.
- Upload / sync / `link-existing` / checksum-exists API — нет.
- WorkManager / фоновое расписание — нет.
- Server file/folder API, Retrofit-сервисы файлов, server DTO — нет.
- `FilesScreen`, `FilesViewModel`, navigation, permission UI — не реализуем (только фиксируем будущие точки подключения).
- `AppDatabase`, миграции, версия БД — не меняем (схема для images уже готова с 5B-1).
- Auth/network/session, `ApiServiceFactory`, `TokenAuthenticator`, `ServerRepository` — не трогаем.
