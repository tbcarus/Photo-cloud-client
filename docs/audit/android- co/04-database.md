# 04. Room database

## Схема и сохранность

[CONFIRMED] [AppDatabase.kt:16](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt:16>): database version=3, exportSchema=true, DB name photo_cloud.db; entities=[MediaFile]; один DAO. Конфигурация KSP room.schemaLocation=app/schemas. Сохранены schema2/schema3; schema1 отсутствует, её состояние известно лишь частично по migration1→2. Ни WAL/journal override, ни encryption DB, ни destructive callback не заданы.

[CONFIRMED] Таблица media_files имеет PK mediaStoreId, autoGenerate=false, отдельные indexes/foreign keys/unique checksum/serverFileId constraints отсутствуют. 14 columns:

| Column | SQL affinity | Nullable | Default fresh v3 schema |
| --- | --- | --- | --- |
| mediaStoreId | INTEGER | Нет | Не задан |
| serverFileId | INTEGER | Да | Не задан |
| uri | TEXT | Нет | Не задан |
| displayName | TEXT | Нет | Не задан |
| relativePath | TEXT | Да | Не задан |
| mimeType | TEXT | Нет | Не задан |
| size | INTEGER | Нет | Не задан |
| createdAt | INTEGER | Нет | Не задан |
| lastModified | INTEGER | Нет | Не задан |
| checksum | TEXT | Да | Не задан |
| status | TEXT | Нет | Не задан |
| failureReason | TEXT | Да | Не задан |
| retryCount | INTEGER | Нет | Не задан |
| lastFailureAt | INTEGER | Да | Не задан |

[CONFIRMED] Kotlin default PENDING/retryCount0 не является SQL DEFAULT: schema3 CREATE TABLE не содержит DEFAULT. Migration добавляет defaults для существующих строк. Nullability/fields подтверждены [MediaFile.kt:6](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt:6>) и [3.json](<C:/projects/Photocloudclient/app/schemas/ru.tbcarus.photo_cloud_client.core.db.AppDatabase/3.json>). room_master_table — служебная таблица Room, не отдельная прикладная entity.

~~~mermaid
erDiagram
 media_files {
  long mediaStoreId PK
  long serverFileId "nullable; external logical id, not FK"
  string uri
  string displayName
  string relativePath "nullable"
  string mimeType
  long size
  long createdAt
  long lastModified
  string checksum "nullable"
  string status
  string failureReason "nullable"
  int retryCount
  long lastFailureAt "nullable"
 }
~~~

[CONFIRMED] ER содержит одну entity: реальных SQL relationships нет. Связь mediaStoreId с OS и serverFileId с remote FileItem только логическая, не поддерживается FK.

## Все DAO methods

[CONFIRMED] Источник [MediaFileDao.kt:9](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:9>). Каждый SQL UPDATE атомарен как statement; общего @Transaction метода нет.

| Method | Query/effect | Caller / использование |
| --- | --- | --- |
| insert | @Insert IGNORE одного MediaFile | [UNUSED] активный caller не найден |
| insertAll | @Insert IGNORE list | MediaFileRepository.applyToRoom |
| getAll | SELECT * ORDER BY createdAt DESC, Flow | observeAll→FilesViewModel |
| getByStatus | SELECT * WHERE status=:status ORDER BY createdAt DESC, Flow | [UNUSED] кроме getPending wrapper |
| getPending | getByStatus(PENDING), Flow | [UNUSED] pipeline использует snapshot query |
| updateStatus | UPDATE status WHERE mediaStoreId | HASHING, UPLOADING, transient→PENDING_UPLOAD |
| updateChecksum | SET checksum,status,failureReason=NULL,retryCount=0,lastFailureAt=NULL | checksum success; serverFileId не меняет |
| markFailed | status=FAILED, reason, retryCount+1, lastFailureAt | checksum/upload failures; hash/server ID не очищает |
| getRetryableFailed | FAILED AND reason AND retryCount<max AND lastFailureAt<=retryBefore; DESC createdAt LIMIT | CHECKSUM_IO restore, max3/500 |
| resetFailedForRetry | status=PENDING, failureReason=NULL,lastFailureAt=NULL | restore; retryCount/checksum/serverFileId сохраняются |
| updateLocalMetadata | displayName/path/MIME/size/createdAt/lastModified/uri | scan для всех scanned rows |
| markUploaded | SYNCED, serverFileId, clear reason/count/time | upload response id>0; checksum сохраняется |
| exists | COUNT WHERE mediaStoreId | [UNUSED] |
| getAllMediaStoreIds | SELECT mediaStoreId | stale detection |
| deleteByMediaStoreIds | DELETE WHERE ID IN list | stale chunks500 |
| getByStatusOnce | status DESC createdAt LIMIT | checksum100/upload20 |
| resetStatus | all sourceStatus→targetStatus | HASHING→PENDING; UPLOADING→PENDING_UPLOAD |
| resetChecksumIfFileChanged | if size differs OR lastModified differs: checksum/server ID NULL, status PENDING, clear failure/count/time | scan до metadata update |
| getLatestCreatedAt | MAX(createdAt), nullable Long | [UNUSED] incremental scan отсутствует |
| getByStatusWithChecksumOnce | status AND checksum IS NOT NULL DESC createdAt LIMIT | precheck500; empty-string не исключён SQL |
| updateStatusByMediaStoreIds | SET status WHERE IDs IN | precheck existing/missing chunks500; failure metadata не очищает |

[PARTIAL] @Insert list генерирует свою Room insert operation, но весь applyToRoom (insert → per-row reset/update → delete stale) не обёрнут в одну транзакцию. Промежуточные результаты видны Flow; исключение оставляет уже завершённые statements. Отсутствуют guards по исходному status/version и число affected rows не возвращается большинством update methods. [MediaFileRepository.kt:36](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:36>).

## Миграции

| Path | SQL / data effect |
| --- | --- |
| [CONFIRMED] 1→2 | ADD displayName TEXT NOT NULL DEFAULT ''; ADD relativePath TEXT; ADD lastModified INTEGER NOT NULL DEFAULT0 |
| [CONFIRMED] 2→3 | ADD failureReason TEXT; ADD retryCount INTEGER NOT NULL DEFAULT0; ADD lastFailureAt INTEGER |
| [CONFIRMED] 1→3 | Обе registered migration последовательно; явной 1→3 нет |
| [CONFIRMED] Uncovered versions | fallbackToDestructiveMigration(dropAllTables=true); возможность потери локального индекса |
| [UNUSED] AutoMigration | Не объявлены |

[CONFIRMED] Исторические FAILED из v2 получают failureReason=null, retryCount0,lastFailureAt=null. Они не проходят CHECKSUM_IO retry selector и останутся FAILED до локального изменения или удаления/re-discovery. Old lastModified0 из migration1→2 приводит к reset при следующем scan, если актуальное значение отличается. [AppDatabase.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt:38>), [MediaFileDao.kt:63](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:63>).

[RISK] Enum converters valueOf выбросят исключение на неизвестной строке. DB не содержит CHECK constraints для enum/сочетаний полей, не запрещает SYNCED с null checksum или FAILED без reason. Schema2/3 exports прочитаны, runtime migration validation не выполнялась; различия SQL defaults fresh vs upgraded DB сами по себе не объявляются доказанным crash.

## Scan, reset и performance

[CONFIRMED] Изменение имени/path само по себе не сбрасывает checksum; reset только size/mtime. Удаление из scan scope удаляет Room row, не переводит LOCAL_DELETED. Bulk reset recovery меняет только status; marker ownership отсутствует.

[RISK] Фильтры status/retry и ORDER BY createdAt не поддержаны explicit indexes; getAll доставляет всю библиотеку и UI считает counters по полному списку. Scan загружает все images+DB IDs и делает два UPDATE на каждую строку, включая неизменные. Масштабирование на большие библиотеки не проверено. [MediaFileRepository.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:38>), [MediaFileDao.kt:18](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:18>), [FilesScreen.kt:216](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:216>).

