# Анализ локальной файловой модели Android-клиента

**Дата:** 2026-05-28  
**Этап:** pre-5B — подготовка к файловой части  
**Статус:** код не изменён, только анализ

---

## Контекст

Auth/network/session завершены. Начинается файловая часть:
- локальный индекс файлов устройства;
- MediaStore scanning;
- checksum;
- sync с сервером — позже.

Серверные контракты (`api-file-contract.md`, `api-folder-contract.md`, `api-checksum-sync-contract.md`) уже существуют и учитываются при анализе архитектурных решений.

---

## 1. Текущее состояние

### 1.1 MediaFile entity

```kotlin
@Entity(tableName = "media_files")
data class MediaFile(
    @PrimaryKey
    val mediaStoreId: Long,
    val serverFileId: Long? = null,
    val uri: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,       // DATE_TAKEN из MediaStore (ms)
    val checksum: String?,
    val status: MediaFileStatus = MediaFileStatus.PENDING
)
```

### 1.2 MediaFileStatus

```kotlin
enum class MediaFileStatus {
    PENDING,
    PENDING_UPLOAD,
    UPLOADING,
    SYNCED,
    FAILED,
    LOCAL_DELETED,
}
```

### 1.3 MediaFileDao

Методы: `insert`, `insertAll`, `getAll`, `getByStatus`, `updateStatus`, `getPending`, `updateChecksum`, `markUploaded`, `exists`, `getLatestCreatedAt`.

### 1.4 AppDatabase

- Room версия: 2.7.0
- version = 1, exportSchema = false
- `fallbackToDestructiveMigration(dropAllTables = true)` — TODO-комментарий уже есть
- TypeConverters для MediaFileStatus

### 1.5 Что отсутствует полностью

- MediaStore scanning — нет ни строки кода
- FileRepository / MediaStoreRepository
- FilesViewModel
- SHA-256 checksum computation
- WorkManager integration (зависимость добавлена, но не используется)

---

## 2. Детальный анализ

### 2.1 Поля MediaFile

#### Хорошие поля

| Поле | Оценка |
|------|--------|
| `mediaStoreId: Long` | Правильный PK для MediaStore-ориентированного индекса |
| `mimeType: String` | Non-null, нужен для определения типа файла |
| `size: Long` | Non-null, нужен для upload и pre-check |
| `createdAt: Long` | DATE_TAKEN в миллисекундах — правильно для сортировки по дате съёмки |
| `checksum: String?` | Nullable — правильно, считается после сканирования |
| `status: MediaFileStatus` | Нужен, но набор статусов требует пересмотра |

#### Спорные поля

**`uri: String`**

Content URI из MediaStore (`content://media/external/images/media/123`). Плюс: стандартный способ доступа к файлам через scoped storage. Риск: URI становится невалидным при переустановке приложения, при переносе файла, при full rescan MediaStore. Текущий тип `String` вместо `Uri` — приемлемо для Room, но нужно чётко помнить об этом.

**`mediaStoreId: Long` как PK**

MediaStore ID стабилен в рамках одной установки приложения на minSdk=29+. Однако после переустановки приложения и полного rescan MediaStore новые ID могут быть присвоены тем же файлам. Это означает: при переустановке вся БД сбрасывается вместе с данными (что и происходит сейчас при `fallbackToDestructiveMigration`). Для текущего этапа — приемлемо. Для долгосрочной стратегии: после появления checksum, первичным ключом логически становится checksum, а mediaStoreId превращается в ссылку на MediaStore.

#### Проблемные поля

**`serverFileId: Long? = null`**

Это серверный идентификатор `FileItem.id` из сервера, встроенный в локальную entity. Это нарушение разделения моделей (см. раздел 5). На текущем этапе без upload это поле не используется вовсе. Удалить нельзя без schema migration, но добавлять новые серверные поля напрямую в MediaFile — нельзя.

#### Отсутствующие поля (нужны до scanner)

| Поле | Тип | Зачем |
|------|-----|-------|
| `displayName: String` | non-null | имя файла для UI и для upload `originalFilename` |
| `relativePath: String?` | nullable | папка в MediaStore (DCIM/Camera/, Pictures/…) |
| `lastModified: Long` | non-null | DATE_MODIFIED из MediaStore, для обнаружения изменений |

#### Поля, которые НЕ добавлять сейчас

| Поле | Причина отказа |
|------|----------------|
| `uploadedAt: Long?` | относится к upload pipeline — не нужен до sync |
| `syncedAt: Long?` | относится к sync sessions — не нужен сейчас |
| `folderId: Long?` | серверная `Folder.id` — принадлежит серверной модели |
| `serverChecksum: String?` | дублирует локальный checksum, путает модели |
| `fileType: String` | вычисляется из mimeType, хранить избыточно |
| `thumbnailPath: String?` | UI-кеш, не файловый индекс |

---

### 2.2 MediaFileStatus

#### Анализ текущих статусов

| Статус | Оценка |
|--------|--------|
| `PENDING` | Нужен — файл найден MediaStore, checksum не считан |
| `FAILED` | Нужен — ошибка (при вычислении checksum или в будущем при upload) |
| `PENDING_UPLOAD` | Преждевременен — upload pipeline не реализован |
| `UPLOADING` | Преждевременен — upload pipeline не реализован |
| `SYNCED` | Преждевременен — sync не реализован |
| `LOCAL_DELETED` | Преждевременен — sync-aware deletion не реализована |

#### Что нужно для этапа 5B (scanner + checksum)

Минимально достаточный набор:

```
PENDING          → файл найден, checksum не считан
HASHING          → checksum считается прямо сейчас
CHECKSUM_READY   → checksum есть, можно делать pre-check с сервером
FAILED           → ошибка (IO, permission denied при чтении файла)
```

**Рекомендация:** не расширять сейчас весь enum до полного state machine. Добавить только `HASHING` и `CHECKSUM_READY` перед scanner. `PENDING_UPLOAD`, `UPLOADING`, `SYNCED`, `LOCAL_DELETED` можно добавить когда начнётся upload/sync этап — это нормально для schema version + migration.

---

### 2.3 MediaFileDao

#### Хорошие запросы (пригодятся)

| Метод | Зачем |
|-------|-------|
| `insertAll(files)` с IGNORE | batch insert при начальном сканировании |
| `exists(mediaStoreId)` | delta-scan: пропускать уже индексированные файлы |
| `getLatestCreatedAt()` | инкрементальный scan по DATE_TAKEN |
| `updateChecksum()` | обновление после SHA-256 |
| `getByStatus()` | выборка PENDING для checksum worker |
| `getAll()` as Flow | наблюдение UI за изменениями |

#### Проблемные запросы

**Hardcoded строки вместо параметров:**

```kotlin
// ПЛОХО — хрупкий hardcode, не ломается при переименовании enum
@Query("SELECT * FROM media_files WHERE status = 'PENDING' ORDER BY createdAt DESC")
fun getPending(): Flow<List<MediaFile>>

@Query("UPDATE media_files SET status = 'SYNCED', serverFileId = :serverFileId WHERE mediaStoreId = :mediaStoreId")
suspend fun markUploaded(mediaStoreId: Long, serverFileId: Long)
```

Если переименовать статус в enum — Room не обнаружит несоответствие на этапе компиляции, только в runtime. Нужно использовать параметры.

#### Отсутствующие запросы (нужны до scanner)

```kotlin
// Для обнаружения удалённых файлов (stale records)
@Query("SELECT mediaStoreId FROM media_files")
suspend fun getAllMediaStoreIds(): List<Long>

// Для batch stale cleanup после scan
@Query("DELETE FROM media_files WHERE mediaStoreId IN (:ids)")
suspend fun deleteByMediaStoreIds(ids: List<Long>)

// Для обновления displayName/relativePath/lastModified без пересоздания записи
@Query("UPDATE media_files SET displayName = :displayName, relativePath = :relativePath, lastModified = :lastModified WHERE mediaStoreId = :mediaStoreId")
suspend fun updateLocalMeta(mediaStoreId: Long, displayName: String, relativePath: String?, lastModified: Long)
```

#### Преждевременные запросы (не удалять, просто не использовать)

`markUploaded()` — логика upload. Правильно оставить, будет нужен позже. Но `serverFileId` в параметре привязывает DAO к серверной модели — это уже архитектурная проблема (см. раздел 5).

---

### 2.4 Room / AppDatabase

#### Текущее состояние

- `exportSchema = false` — плохо для production, но приемлемо пока нет release
- `fallbackToDestructiveMigration` — приемлемо для разработки, нужно заменить перед первым production-ready release
- TypeConverters для MediaFileStatus через `name` — правильно, но при переименовании enum значения в БД станут невалидными

#### Что нужно сделать до scanner

1. Изменить `exportSchema = false` → `exportSchema = true` и настроить `schemaLocation`. Это позволит Room генерировать JSON-схему и проверять миграции.
2. Завести `/app/schemas/` папку для Room schema exports.
3. Написать первую реальную migration (1→2) когда будут добавляться поля `displayName`, `relativePath`, `lastModified`.

---

## 3. Разделение моделей (критически важно)

### Правильная граница

```
MediaFile (Room entity)
  └── локальный индекс файлов устройства
  └── данные из MediaStore
  └── локальный статус (PENDING, HASHING, CHECKSUM_READY, FAILED)
  └── checksum (вычисляется локально)

FileItemDto (Retrofit DTO, будущий)
  └── серверный ответ от GET /api/v1/files
  └── id, folderId, originalFilename, mimeType, size, checksum, fileType
  └── capturedAt, uploadedAt, metadata
  └── НЕ попадает в Room напрямую

SyncRecord (будущий, опциональный)
  └── связь mediaStoreId ↔ serverFileId
  └── syncedAt, syncStatus
  └── отдельная таблица или отдельный entity
```

### Текущая проблема

`serverFileId: Long?` в `MediaFile` — это начало смешивания моделей. Сейчас это единственное нарушение, но оно создаёт прецедент. Если по этому паттерну начать добавлять `serverFolderId`, `serverChecksum`, `uploadedAt` — получится "универсальная модель", которую невозможно рефакторить без разрыва sync логики.

### Почему нельзя делать одну универсальную модель

1. **Жизненный цикл разный.** Локальный файл существует с момента сканирования. Серверная запись — только после upload. До upload `serverFileId = null` — это null по причине "ещё не загружен", а не "не существует".

2. **Источник истины разный.** `MediaFile.size` — из MediaStore, реальный размер файла на устройстве. `FileItemDto.size` — из сервера, может отличаться (если сервер делал конвертацию). Если это одно поле — непонятно чему верить.

3. **Checksum используется в двух контекстах.** Локально: вычисляется для pre-check запроса `POST /api/v1/files/checksums/exists`. Серверно: сервер хранит и возвращает в `FileItemDto.checksum`. После upload клиент может захотеть сравнить локальный и серверный checksum для верификации. Если это одно поле — невозможно отличить "вычислен локально" от "подтверждён сервером".

4. **Schema migration nightmare.** Серверные поля изменяются вместе с API. Локальные поля изменяются вместе с Android-стороной. Смешанная entity требует миграции каждый раз при изменении любого контракта.

### Правило для этапа 5B

**В `MediaFile` добавлять только то, что приходит из MediaStore или вычисляется локально (checksum).** `serverFileId` — технический долг, изолировать его в будущем `SyncRecord`.

---

## 4. MediaStore scanning

### Чего нет

- Нет ни одного класса для сканирования MediaStore
- Нет ContentResolver usage
- Нет permission handling (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO)
- Нет projection definition (какие колонки запрашивать)

### Что нужно для начального scanner

**Projection (колонки из MediaStore):**

```kotlin
arrayOf(
    MediaStore.MediaColumns._ID,                // mediaStoreId
    MediaStore.MediaColumns.DISPLAY_NAME,       // displayName
    MediaStore.MediaColumns.RELATIVE_PATH,      // relativePath
    MediaStore.MediaColumns.MIME_TYPE,          // mimeType
    MediaStore.MediaColumns.SIZE,               // size
    MediaStore.MediaColumns.DATE_TAKEN,         // createdAt
    MediaStore.MediaColumns.DATE_MODIFIED,      // lastModified (секунды!)
)
```

**Стратегия scanning:**

- **Initial scan** — полный запрос `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` + `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`. Для каждого ID: проверить `exists()` в БД, если нет — вставить.
- **Incremental scan** — запрос с `DATE_MODIFIED > lastKnownModified`. Позволяет не перегружать БД при повторных запусках.
- **Stale detection** — после scan получить `getAllMediaStoreIds()` из БД, сравнить с тем что пришло из MediaStore, удалить стale записи.

**Важно для minSdk=29 (scoped storage всегда включён):**
- Доступны только `Images.Media.EXTERNAL_CONTENT_URI` и `Video.Media.EXTERNAL_CONTENT_URI`
- Нет доступа к файлам других приложений без `MANAGE_EXTERNAL_STORAGE`
- `DATE_TAKEN` может быть null (если файл не из камеры) — нужен fallback на `DATE_ADDED`

### Что НЕ делать на этапе scanner

- Не читать байты файла при сканировании (только метаданные)
- Не считать checksum во время scan-прохода
- Не запускать WorkManager — scanner как coroutine в Repository достаточно для этапа 5B

---

## 5. Checksum strategy

### Где живёт checksum

В `MediaFile.checksum: String?` — правильно.

### Когда вычислять

Отдельный шаг после сканирования, не во время scan. Scanner создаёт записи со статусом `PENDING`. Checksum worker берёт PENDING файлы и:
1. Открывает файл через URI с ContentResolver
2. Считает SHA-256 потоково (не грузить весь файл в память)
3. Сохраняет через `updateChecksum()` и меняет статус на `CHECKSUM_READY`

### Нужен ли отдельный статус

Да. Нужен `HASHING` — маркер что файл сейчас обрабатывается. Это предотвращает двойной запуск checksum вычисления на один файл если worker перезапускается.

### Нужен ли WorkManager на этапе 5B

Нет. На начальном этапе checksum вычисление как coroutine в ViewModel или UseCase достаточно. WorkManager добавляется когда понадобится фоновая обработка при закрытом приложении.

### SHA-256 performance

На 4K JPG (~6MB) SHA-256 занимает ~50-100ms на современном устройстве. Для 1000 фотографий — несколько минут. Это нормально для фоновой обработки, но нельзя делать на main thread.

---

## 6. Риски

| Риск | Серьёзность | Когда проявится | Митигация |
|------|-------------|-----------------|-----------|
| `mediaStoreId` меняется после переустановки | Средняя | При переустановке | `fallbackToDestructiveMigration` покрывает сейчас; долгосрочно — checksum как primary key sync |
| URI становится невалидным (файл перемещён/переименован) | Высокая | При повторном scan | `lastModified` + incremental rescan; при ошибке открытия — пометить FAILED и пересканировать |
| Большая библиотека (100k+ фото) | Высокая | Initial scan | Batch insert, не per-item; coroutine с пагинацией |
| Checksum performance на большом объёме | Средняя | Batch hashing | Потоковый SHA-256, sequential в отдельном coroutine; WorkManager — позже |
| Stale records (файл удалён с устройства) | Средняя | После первого scan при последующем rescan | `getAllMediaStoreIds()` + stale detection |
| Scoped storage — нет доступа к файлам других приложений | Известное ограничение | Сразу | Документировать, сканировать только собственные медиа |
| Переименование enum статусов ломает TypeConverter | Низкая сейчас | При рефакторинге | Конвертировать через явный when-блок, не `name` |
| `exportSchema = false` не позволяет проверять миграции | Средняя | При первой migration | Включить до добавления новых полей |

---

## 7. Что оставить как есть

- Зависимости Room 2.7.0, Hilt 2.52, WorkManager (добавлен, не используется — ок)
- `AppDatabase` companion object с singleton — хорошая структура
- TypeConverters — работают, нужно только усилить через `when` вместо `name`
- `mediaStoreId` как PK — приемлемо для текущего этапа
- `insertAll` с IGNORE conflict — правильная стратегия для scanner
- `getLatestCreatedAt()` — пригодится для incremental scan
- `exists()` — пригодится для delta scan
- Hilt `AppModule` с `provideMediaFileDao` — правильно

---

## 8. Что нужно исправить до scanner

### 8.1 Добавить поля в MediaFile (schema migration 1→2)

```kotlin
val displayName: String,       // DISPLAY_NAME из MediaStore, нужен для UI и upload
val relativePath: String?,     // RELATIVE_PATH из MediaStore (DCIM/Camera/, Pictures/…)
val lastModified: Long,        // DATE_MODIFIED из MediaStore (секунды → хранить в секундах)
```

Это требует Room migration. Как только `exportSchema = true` включён — можно написать:
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_files ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE media_files ADD COLUMN relativePath TEXT")
        db.execSQL("ALTER TABLE media_files ADD COLUMN lastModified INTEGER NOT NULL DEFAULT 0")
    }
}
```

### 8.2 Добавить статусы HASHING и CHECKSUM_READY

```kotlin
enum class MediaFileStatus {
    PENDING,
    HASHING,           // добавить
    CHECKSUM_READY,    // добавить
    PENDING_UPLOAD,
    UPLOADING,
    SYNCED,
    FAILED,
    LOCAL_DELETED,
}
```

### 8.3 Исправить hardcoded строки в DAO

```kotlin
// БЫЛО
@Query("SELECT * FROM media_files WHERE status = 'PENDING' ORDER BY createdAt DESC")
fun getPending(): Flow<List<MediaFile>>

// СТАЛО — безопасно при переименовании статусов
@Query("SELECT * FROM media_files WHERE status = :status ORDER BY createdAt DESC")
fun getPending(status: MediaFileStatus = MediaFileStatus.PENDING): Flow<List<MediaFile>>
```

Аналогично `markUploaded` — убрать hardcoded `'SYNCED'`.

### 8.4 Добавить отсутствующие DAO методы

```kotlin
@Query("SELECT mediaStoreId FROM media_files")
suspend fun getAllMediaStoreIds(): List<Long>

@Query("DELETE FROM media_files WHERE mediaStoreId IN (:ids)")
suspend fun deleteByMediaStoreIds(ids: List<Long>)
```

### 8.5 Включить exportSchema

```kotlin
@Database(entities = [MediaFile::class], version = 2, exportSchema = true)
```

И в build.gradle.kts:
```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

---

## 9. Что пока не трогать

- `serverFileId` в `MediaFile` — технический долг, но удаление требует миграции. Изолировать в `SyncRecord` — задача этапа upload/sync, не сейчас
- `markUploaded()` в DAO — пригодится позже для upload pipeline
- `PENDING_UPLOAD`, `UPLOADING`, `SYNCED`, `LOCAL_DELETED` в статусах — не использовать, но не удалять
- WorkManager — зависимость есть, не трогать до upload этапа
- `fallbackToDestructiveMigration` — заменить на реальные migrations вместе с добавлением полей

---

## 10. Рекомендуемый этап 5B — минимальный безопасный шаг

**Цель этапа 5B:** локальный индекс файлов устройства. Без upload, без sync, без WorkManager.

### Компоненты

```
media/
  MediaFile.kt          ← добавить displayName, relativePath, lastModified
  MediaFileStatus.kt    ← добавить HASHING, CHECKSUM_READY
  MediaFileDao.kt       ← исправить hardcode, добавить stale methods
  MediaStoreRepository.kt  ← НОВЫЙ: scanning и MediaStore queries
  MediaFileRepository.kt   ← НОВЫЙ: local CRUD, checksum trigger
  ChecksumUtil.kt          ← НОВЫЙ: потоковый SHA-256

core/db/
  AppDatabase.kt        ← version 2, exportSchema = true, MIGRATION_1_2
  schemas/              ← Room schema exports

ui/screens/
  FilesScreen.kt        ← отображение локального индекса (уже есть stub)
  files/
    FilesViewModel.kt   ← НОВЫЙ: observes MediaFileRepository
    FilesUiState.kt     ← НОВЫЙ
```

### Что делает 5B

1. `MediaStoreRepository` — сканирует `Images.Media` + `Video.Media`, возвращает список `MediaFile` для вставки
2. `MediaFileRepository` — оркестрирует scan: сравнивает MediaStore с БД, вставляет новые, удаляет stale
3. `ChecksumUtil` — вычисляет SHA-256 потоково через `ContentResolver.openInputStream(uri)`
4. `FilesViewModel` — запускает scan, затем checksum вычисление для PENDING файлов; отдаёт Flow в UI
5. `FilesScreen` — список файлов из локального индекса с именем, датой, статусом

### Что НЕ делает 5B

- Не делает upload
- Не вызывает `/api/v1/files/checksums/exists`
- Не использует WorkManager
- Не создаёт Retrofit сервис для файлов
- Не добавляет навигацию по папкам

### Граница следующего этапа (5C)

После 5B: pre-check `POST /api/v1/files/checksums/exists` — отправить список checksum на сервер, получить existing/missing, обновить статусы. Это уже сетевой вызов, но без реального upload.

---

## 11. Ключевые архитектурные решения, которые важно принять сейчас

### Решение A: `MediaFile` — только локальная сущность

`serverFileId` — последнее серверное поле в `MediaFile`. Все последующие серверные данные идут в отдельный DTO или будущий `SyncRecord`. Это решение нужно принять сейчас, иначе entity будет расти в неправильную сторону.

### Решение B: checksum — часть локального индекса

Checksum вычисляется локально из байтов файла и хранится в `MediaFile.checksum`. Это не "серверный" checksum — это локально вычисленный SHA-256. Сервер возвращает свой checksum в `FileItemDto`, и они должны совпасть (верификация). Два разных поля — два разных источника истины.

### Решение C: URI как рабочий ключ доступа, mediaStoreId как DB key

`mediaStoreId` — первичный ключ для индексирования. `uri` — рабочий ключ для открытия файла через ContentResolver. После появления checksum, sync-идентификатором становится checksum, а не mediaStoreId.

### Решение D: scan и checksum — разные операции

Scan быстрый (только метаданные из MediaStore). Checksum медленный (читает байты файла). Разделение позволяет сначала показать пользователю список файлов, затем фоново досчитать checksum.

### Решение E: Room migration вместо destructive

С момента добавления полей (этап 5B) — реальные миграции. `fallbackToDestructiveMigration` остаётся как резерв для разработки, но основной путь — migration объекты.

---

## Файлы, которые будут затронуты на этапе 5B

| Файл | Изменение |
|------|-----------|
| `media/MediaFile.kt` | добавить `displayName`, `relativePath`, `lastModified` |
| `media/MediaFileStatus.kt` | добавить `HASHING`, `CHECKSUM_READY` |
| `media/MediaFileDao.kt` | исправить hardcode, добавить методы |
| `core/db/AppDatabase.kt` | version 2, exportSchema, MIGRATION_1_2 |
| `build.gradle.kts` | добавить `room.schemaLocation` ksp arg |
| `media/MediaStoreRepository.kt` | новый файл |
| `media/MediaFileRepository.kt` | новый файл |
| `media/ChecksumUtil.kt` | новый файл |
| `ui/screens/files/FilesViewModel.kt` | новый файл |
| `ui/screens/files/FilesUiState.kt` | новый файл |
| `ui/screens/FilesScreen.kt` | обновить stub на реальный список |
| `di/AppModule.kt` | добавить провайдеры для новых репозиториев |
| `app/schemas/` | новая папка для Room schema exports |

---

*Анализ подготовлен перед этапом 5B. Код не изменялся.*
