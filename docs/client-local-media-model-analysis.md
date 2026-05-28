# Анализ локальной файловой модели Android-клиента

Дата: 2026-05-28  
Этап: подготовка к локальной файловой части, перед 5B  
Статус: код не изменялся, выполнен только анализ

## 1. Текущее состояние

Файловая часть клиента сейчас представлена минимальной Room-моделью:

- `app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/di/AppModule.kt`

Сканера MediaStore, локального файлового репозитория, checksum-утилиты и `FilesViewModel` пока нет. `FilesScreen` сейчас является stub-экраном с текстом `Files`.

### MediaFile

Текущая entity:

```kotlin
@Entity(tableName = "media_files")
data class MediaFile(
    @PrimaryKey
    val mediaStoreId: Long,
    val serverFileId: Long? = null,
    val uri: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val checksum: String?,
    val status: MediaFileStatus = MediaFileStatus.PENDING
)
```

Текущая модель уже похожа на локальный индекс устройства, но в ней смешаны три слоя:

- локальные данные из MediaStore: `mediaStoreId`, `uri`, `mimeType`, `size`, `createdAt`;
- локально вычисляемые данные: `checksum`;
- будущая серверная связь и sync-state: `serverFileId`, `status`.

### MediaFileStatus

Текущие статусы:

```kotlin
PENDING,
PENDING_UPLOAD,
UPLOADING,
SYNCED,
FAILED,
LOCAL_DELETED
```

Набор уже смотрит в сторону upload/sync, хотя upload pipeline, checksum pre-check и server sync на клиенте еще не реализованы.

### MediaFileDao

Сейчас DAO умеет:

- вставлять один файл или список файлов через `OnConflictStrategy.IGNORE`;
- читать все файлы и файлы по статусу;
- обновлять статус;
- читать hardcoded `PENDING`;
- обновлять checksum;
- помечать файл как uploaded через `SYNCED` и `serverFileId`;
- проверять существование `mediaStoreId`;
- получать максимальный `createdAt`.

Для начального scanner-а часть методов полезна, но часть уже относится к upload.

### AppDatabase

Текущее состояние:

- Room database version `1`;
- единственная таблица `media_files`;
- `exportSchema = false`;
- есть `TypeConverter` для `MediaFileStatus` через `status.name`;
- включен `fallbackToDestructiveMigration(dropAllTables = true)` с TODO-комментарием;
- база создается singleton-ом через `AppDatabase.getInstance`;
- DAO предоставляется через Hilt в `AppModule`.

Это нормально для ранней разработки, но перед расширением схемы под scanner лучше перейти к явным миграциям.

## 2. Оценка полей MediaFile

### Что уже хорошее

`mediaStoreId: Long`  
Хороший первичный ключ для локального индекса MediaStore в рамках одной установки приложения. Он удобен для scanner-а: можно быстро проверять, есть ли запись в локальной БД.

`uri: String`  
Правильное направление для scoped storage: файл открывается через `ContentResolver`, а не через raw file path. Хранить как `String` в Room нормально. В коде выше слоя БД можно преобразовывать в `Uri`.

`mimeType: String`  
Нужно для фильтрации image/video, UI, будущего upload и определения типа файла. Поле лучше оставить локальным значением из MediaStore.

`size: Long`  
Нужно для UI, pre-check, лимитов и будущего upload. Non-null тип подходит.

`createdAt: Long`  
Полезно для сортировки и первичного отображения. Сейчас комментарий указывает на `DATE_TAKEN` в миллисекундах. Важно в scanner-е явно нормализовать единицы времени: `DATE_TAKEN` обычно уже ms, а `DATE_MODIFIED` в MediaStore часто seconds.

`checksum: String?`  
Nullable правильно: после scan checksum еще может отсутствовать. Это локально вычисленный SHA-256, а не серверное поле.

### Спорные поля

`serverFileId: Long?`  
Это серверный идентификатор `FileItem.id`, встроенный в локальную entity. Сейчас поле не мешает компиляции, но архитектурно это первый признак смешивания локального индекса и серверной модели. Для будущего sync лучше вынести связь `mediaStoreId/checksum -> serverFileId` в отдельную sync-модель или sync-таблицу, когда она реально понадобится.

`status: MediaFileStatus`  
Само поле полезно, но текущие значения смешивают локальную обработку, upload и sync. Для ближайшего scanner-а статус должен описывать локальное состояние: найден, checksum считается, checksum готов, ошибка чтения. Состояния `UPLOADING` и `SYNCED` пока преждевременны.

### Чего не хватает для scanner-а

`displayName: String`  
Нужно для UI, логов, будущего `originalFilename` при upload. Сейчас без него экран файлов будет вынужден показывать URI или извлекать имя ad hoc.

`relativePath: String?`  
Нужно для понимания локальной папки MediaStore (`DCIM/Camera/`, `Pictures/...`) и будущего UX. Это не server folder и не должно маппиться напрямую на `FolderDto`.

`lastModified: Long`  
Нужно для incremental scan, detection moved/renamed/changed и обновления metadata. Лучше хранить явно нормализованное значение, например seconds из `DATE_MODIFIED` или ms, но выбрать один формат и зафиксировать.

Опционально позже, но не обязательно до первого scanner-а:

- `indexedAt: Long` - когда запись была добавлена/обновлена в локальном индексе;
- `lastSeenAt: Long` или `scanGeneration: Long` - для аккуратной stale detection без огромных списков id;
- `errorMessage: String?` - если понадобится показывать причину checksum/permission ошибки.

### Что лучше не добавлять сейчас

`uploadedAt`, `syncedAt`  
Это поля upload/sync pipeline. До реального server sync они будут либо пустыми, либо начнут задавать ложную модель.

`folderId` / `serverFolderId`  
Серверная папка не равна локальному `relativePath`. Серверный контракт явно говорит, что `Folder` - логическое дерево пользователя, а физическое storage layout не является зеркалом папок.

`deletedAt` для server soft delete  
Сервер сейчас использует hard delete для файлов; `deletedAt` в контракте есть, но не является активной client sync-семантикой. Локальное удаление с устройства лучше сначала обрабатывать как stale record локального индекса.

`serverChecksum`  
Локальный checksum и серверный checksum имеют разные источники истины. Для pre-check достаточно локального `checksum`; серверный ответ `existing/missing` не нужно сохранять как второе checksum-поле в `MediaFile`.

`fileType`  
Для ближайшего этапа выводится из `mimeType`. Хранить отдельное поле преждевременно.

## 3. MediaFileStatus

Текущие статусы:

| Статус | Оценка |
| --- | --- |
| `PENDING` | Нужен. Хорошо описывает файл, найденный scanner-ом, но еще без checksum. |
| `PENDING_UPLOAD` | Преждевременен до server pre-check/upload. |
| `UPLOADING` | Преждевременен до upload pipeline. |
| `SYNCED` | Преждевременен до полноценного sync/link-existing/upload результата. |
| `FAILED` | Нужен, но желательно уточнить, ошибка чего именно: scan/checksum/upload. |
| `LOCAL_DELETED` | Преждевременен для sync-aware delete. Для scanner-а проще удалить stale запись или пометить локально отдельным признаком позже. |

Минимально достаточный набор для этапа scanner + checksum preparation:

```kotlin
PENDING
HASHING
CHECKSUM_READY
FAILED
```

При этом не обязательно немедленно удалять существующие upload-статусы, если это усложняет миграцию. Важно не строить на них логику до появления upload/sync.

Отдельный статус для checksum полезен, но не нужно превращать enum в полноценную state machine. Достаточно различать: checksum еще нет, checksum считается, checksum есть, checksum не удалось посчитать.

## 4. DAO

### Что уже пригодится

`insertAll(files)` с `IGNORE`  
Подходит для initial scan: можно batch-вставлять новые MediaStore записи.

`exists(mediaStoreId)`  
Полезно для простого delta scan, хотя на большой библиотеке per-item `exists()` может стать дорогим.

`getAll()`  
Подходит для UI, потому что возвращает `Flow<List<MediaFile>>`.

`getByStatus(status)`  
Подходит для выборки файлов под checksum.

`updateChecksum(mediaStoreId, checksum)`  
Нужно, но лучше вместе с checksum обновлять статус на `CHECKSUM_READY`.

`getLatestCreatedAt()`  
Может помочь с incremental scan, но одного `createdAt` недостаточно для renamed/moved/modified/deleted файлов.

### Что спорно

`getPending()` hardcoded на `'PENDING'`  
Хрупко: значение enum зашито строкой в SQL. Лучше использовать параметр `MediaFileStatus`.

`markUploaded()` hardcoded на `'SYNCED'` и `serverFileId`  
Это upload/sync метод. Для локального scanner-а он не нужен. Оставлять можно как технический долг, но не использовать на 5B.

`updateStatus()` отдельно от checksum/meta updates  
Полезно, но для checksum лучше иметь атомарный метод "записать checksum и статус", чтобы не оставлять запись в промежуточном состоянии.

### Чего не хватает для scanner-а

Для минимального 5B понадобятся DAO-возможности:

- получить все локальные `mediaStoreId` для stale detection;
- удалить или пометить stale records;
- обновить локальные metadata (`displayName`, `relativePath`, `mimeType`, `size`, `createdAt`, `lastModified`, `uri`) для уже известных `mediaStoreId`;
- выбрать ограниченную пачку файлов без checksum, чтобы не грузить память на большой библиотеке;
- записать checksum вместе со статусом;
- записать ошибку или хотя бы статус `FAILED`.

Что преждевременно:

- запросы pending upload;
- retry upload failed;
- server folder queries;
- sync-session queries;
- queries по `serverFileId` как основной путь.

## 5. Room / AppDatabase

Текущая структура готова для раннего прототипа, но расширение `MediaFile` под scanner уже потребует schema version `2`.

Что стоит сделать до scanner:

1. Включить `exportSchema = true`.
2. Настроить `room.schemaLocation` в Gradle.
3. Добавить явную migration `1 -> 2` для `displayName`, `relativePath`, `lastModified` и возможных новых локальных полей.
4. Перестать полагаться на `fallbackToDestructiveMigration` как основной путь.

`fallbackToDestructiveMigration(dropAllTables = true)` можно оставить только как временную страховку для dev-сборок, но важное решение лучше принять сейчас: начиная с файлового индекса, локальная БД становится пользовательскими данными, а не disposable cache.

TypeConverter через `status.name` работает, но переименование enum-значений сломает старые записи. Если статусы начнут мигрировать, лучше использовать явный mapping.

## 6. Разделение моделей

Разделение локальной и серверной модели обязательно.

Правильные роли:

`MediaFile`  
Локальный индекс файлов устройства. Источник истины - MediaStore и локально вычисленный checksum.

`FileItemDto`  
Серверная логическая запись файла. Источник истины - server API. В контракте содержит `id`, `folderId`, `originalFilename`, `mimeType`, `size`, `checksum`, `fileType`, `capturedAt`, `uploadedAt`, `deletedAt`, `metadata`.

`StoredObject`  
Серверная физическая сущность, важная для dedup по checksum. На клиенте это не должна быть Room entity на текущем этапе.

Будущая sync model  
Отдельная модель связи локального файла и серверного результата: `mediaStoreId`, `checksum`, `serverFileId`, `syncStatus`, `syncedAt`, возможно `deviceId`. Ее лучше вводить только когда появится реальный sync/link/upload.

Почему нельзя делать одну универсальную модель:

- `mediaStoreId` не существует на сервере;
- `serverFileId` не существует в MediaStore;
- `relativePath` не равен `FolderDto`;
- серверный `originalFilename` может отличаться от локального `displayName` после rename;
- локальный `size` и серверный `size` имеют разные источники истины;
- локальный файл может быть удален, а серверный `FileItem` остаться;
- серверный `FileItem` может существовать без локального файла после reinstall или DB reset;
- checksum pre-check отвечает про `StoredObject`, а не про наличие логической записи `FileItem`.

Ключевое решение сейчас: `MediaFile` не должен становиться серверным DTO с дополнительными локальными полями. `serverFileId` уже есть как технический долг, но новые серверные поля в entity добавлять не стоит.

## 7. MediaStore scanning

Что уже есть:

- разрешение `READ_EXTERNAL_STORAGE` для API <= 32;
- разрешение `READ_MEDIA_IMAGES`;
- Room-таблица, способная хранить часть metadata;
- `insertAll`, `exists`, `getLatestCreatedAt`.

Чего нет:

- `READ_MEDIA_VIDEO`, если планируются видео;
- runtime permission flow для медиа;
- `ContentResolver`/`MediaStore` query код;
- scanner/repository;
- stale detection;
- обновление metadata существующих записей;
- handling deleted/moved/renamed files.

Рекомендуемая организация scanner-а:

Initial scan:

- запросить `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`;
- если нужны видео, отдельно запросить `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`;
- читать projection: `_ID`, `DISPLAY_NAME`, `RELATIVE_PATH`, `MIME_TYPE`, `SIZE`, `DATE_TAKEN`, `DATE_MODIFIED`;
- формировать `content://` URI через id;
- batch-вставлять новые записи;
- обновлять metadata уже существующих записей, если изменились `displayName`, `relativePath`, `size`, `lastModified`, `mimeType`, `uri`.

Incremental scan:

- не полагаться только на `MAX(createdAt)`, потому что rename/move/delete не меняют дату съемки;
- использовать `DATE_MODIFIED` для поиска измененных записей;
- для простого первого варианта допустим full scan с batch processing, если не делать checksum внутри scan.

Повторное сканирование:

- scan должен быть идемпотентным;
- повторный запуск не должен создавать дубли;
- scan обновляет metadata, но не должен сбрасывать уже посчитанный checksum без признаков изменения файла.

Detection deleted files:

- самый простой вариант: получить set id из MediaStore и set id из Room, разницу удалить из локального индекса;
- если позже появится server sync/delete, hard delete локальной записи нужно заменить на отдельную sync-aware модель.

WorkManager на этом этапе не нужен. Scanner можно запускать из ViewModel/repository вручную или при входе на Files screen.

## 8. Checksum strategy

Checksum должен жить в `MediaFile.checksum` как локально вычисленный SHA-256 в lowercase hex.

Когда считать:

- не во время MediaStore scan;
- после scan отдельной операцией;
- пачками и не на main thread;
- через `ContentResolver.openInputStream(uri)`;
- только для файлов без checksum или для файлов, metadata которых изменилась так, что checksum стал потенциально stale.

Нужен ли отдельный status:

- да, минимально полезны `HASHING` и `CHECKSUM_READY`;
- отдельный сложный worker-state пока не нужен;
- `FAILED` должен покрыть невозможность открыть/прочитать файл.

Нужен ли отдельный worker:

- WorkManager пока не нужен;
- отдельный use case / repository method для checksum нужен;
- WorkManager стоит вводить позже, когда потребуется гарантированная фоновая обработка при закрытом приложении.

Что можно отложить:

- batch call `/api/v1/files/checksums/exists`;
- upload decisions;
- link-existing;
- retry policy;
- server reconciliation;
- device model.

## 9. Риски

| Риск | Оценка | Что делать |
| --- | --- | --- |
| Дубли MediaStore ID | В одной таблице с PK дубль не вставится, но image/video id могут жить в разных collections. | Если сканируются images и videos, хранить `mediaKind` или строить URI с учетом collection; не считать один `Long` глобальным id для всех типов без проверки. |
| File moved/renamed | `mediaStoreId` может остаться тем же, но `displayName`/`relativePath` изменятся. | Хранить и обновлять `displayName`, `relativePath`, `lastModified`. |
| Deleted local file | В Room останется stale record. | Добавить stale detection при rescan. |
| Large library | Full list в памяти и per-item DAO queries будут дорогими. | Batch processing, ограниченные выборки, минимум работы внутри scan. |
| Checksum performance | SHA-256 читает весь файл и может занять минуты на большой библиотеке. | Считать отдельно от scan, пачками, на IO dispatcher, с прогрессом позже. |
| Stale checksum | Файл изменился, а checksum старый. | Сбрасывать checksum при изменении `size` или `lastModified`. |
| Scoped storage | Raw path может быть недоступен. | Не хранить path как основной доступ; использовать `content Uri`. |
| App reinstall / DB reset | Локальный индекс исчезает, server state остается. | Считать это будущей задачей sync; на 5B просто пересканировать MediaStore. |
| Server model leakage | `MediaFile` разрастется полями `folderId`, `uploadedAt`, `deletedAt`. | Зафиксировать границу моделей сейчас. |
| Permissions | Есть `READ_MEDIA_IMAGES`, но нет `READ_MEDIA_VIDEO`. | Перед scan видео добавить permission и runtime flow. |

## 10. Что оставить как есть

- `mediaStoreId` как текущий Room primary key для локального индекса.
- `uri` как строковое хранение content URI.
- `mimeType`, `size`, `createdAt`, `checksum`.
- `insertAll(..., IGNORE)` как базовую стратегию добавления новых файлов.
- `getAll()` как Flow для будущего UI.
- `getByStatus(status)` как универсальную выборку.
- Hilt provider для `MediaFileDao`.
- Отсутствие WorkManager на ближайшем шаге.

## 11. Что нужно исправить до scanner

Минимальный список перед реализацией scanner-а:

1. Добавить локальные поля `displayName`, `relativePath`, `lastModified`.
2. Добавить migration `1 -> 2` и включить schema export.
3. Добавить DAO methods для stale detection и metadata update.
4. Убрать hardcoded enum strings из DAO-запросов или хотя бы не добавлять новые.
5. Развести scanner и checksum: scan не должен считать SHA-256.
6. Добавить `READ_MEDIA_VIDEO`, если в scope входят видео.
7. Принять правило: новые серверные поля не добавляются в `MediaFile`.

## 12. Что пока не трогать

- Upload pipeline.
- Retrofit API для `/api/v1/files`.
- Retrofit API для `/api/v1/files/checksums/exists`.
- WorkManager.
- Server folder navigation.
- Полноценную sync-session модель.
- Server DTO persistence в Room.
- Auth/network/session код.
- Новую UI-навигацию.
- "Идеальную Clean Architecture".

`serverFileId` лучше не использовать в 5B. Удалять его прямо сейчас тоже не обязательно: это отдельная миграция и архитектурное решение для sync-этапа.

## 13. Минимальный рекомендуемый этап 5B

Цель 5B: локальный индекс MediaStore без upload, без server sync, без WorkManager.

Состав этапа:

1. Room cleanup под scanner:
   - `MediaFile`: добавить `displayName`, `relativePath`, `lastModified`;
   - `MediaFileStatus`: оставить минимальную локальную семантику, добавить checksum-ready состояние;
   - `MediaFileDao`: добавить методы для batch scan, stale cleanup, metadata update;
   - `AppDatabase`: version 2, migration, schema export.

2. `MediaStoreRepository`:
   - читает Images и, при необходимости, Video;
   - возвращает локальные scan records;
   - не знает про сервер.

3. `MediaFileRepository`:
   - оркестрирует scan;
   - вставляет новые записи;
   - обновляет измененные metadata;
   - удаляет или помечает stale records;
   - предоставляет Flow локального индекса.

4. `Checksum` preparation:
   - отдельная потоковая SHA-256 функция;
   - отдельный метод обработки pending файлов;
   - без server pre-check.

5. Минимальный Files UI:
   - показать локальный список;
   - ручной запуск scan;
   - базовые статусы checksum.

## 14. Какие файлы будут затронуты на 5B

Вероятные изменения:

| Файл | Зачем |
| --- | --- |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt` | Добавить поля локального MediaStore metadata. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt` | Уточнить локальные checksum-статусы. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt` | Добавить scanner/checksum DAO methods, убрать hardcoded statuses. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt` | Version 2, migration, schema export. |
| `app/build.gradle.kts` | `room.schemaLocation` для Room schema export. |
| `app/src/main/AndroidManifest.xml` | Permission для video, если видео входит в scope. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt` | Новый локальный scanner. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt` | Новый локальный repository для индекса. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumUtil.kt` | Новый SHA-256 helper/use case. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt` | Минимальное отображение локального индекса вместо stub. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt` | Новый ViewModel для scan/list/checksum. |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/di/AppModule.kt` | Providers для новых repositories/use cases, если не использовать constructor injection. |

Не должны затрагиваться:

- auth/session/network modules;
- server Retrofit services;
- folder API;
- upload API;
- navigation graph, кроме уже существующего Files entry, если он есть.

## 15. Архитектурные решения, которые важно принять сейчас

1. `MediaFile` - локальный индекс устройства, а не серверный `FileItem`.
2. `relativePath` - локальный MediaStore путь, а не server `Folder`.
3. `checksum` - локально вычисленный SHA-256, который позже используется для server pre-check.
4. Scan и checksum - разные операции с разной стоимостью.
5. Upload/sync статусы не должны управлять локальным scanner-ом.
6. `serverFileId` не должен становиться началом универсальной модели; будущую связь с сервером лучше вынести в sync layer.
7. Начиная с расширения схемы под scanner, Room migrations должны стать нормальным путем развития БД.

Итоговая рекомендация: следующий шаг 5B должен быть узким и локальным - scanner + Room cleanup + checksum preparation + минимальный local repository/ViewModel. Server API, upload, sync и WorkManager лучше оставить на следующий этап, когда локальный индекс уже стабильно отражает состояние устройства.
