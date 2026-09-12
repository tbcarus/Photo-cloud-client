# Room database

Android использует Room 2.7.0, `AppDatabase`, имя `photo_cloud.db`, version **3**, exportSchema=true. Одна entity MediaFile и один MediaFileDao. Экспорты schemas 2 и 3 присутствуют в исходном срезе; schema 1 отсутствует. KSP schemaLocation указывает `app/schemas`.

## Таблица

`media_files` имеет 14 колонок из [03](03-local-data-model.md). INTEGER: mediaStoreId, serverFileId, size, createdAt, lastModified, retryCount, lastFailureAt. Остальные — TEXT. Nullable: serverFileId, relativePath, checksum, failureReason, lastFailureAt.

PK — mediaStoreId, autoGenerate=false. Дополнительные indexes, foreign keys, UNIQUE по checksum/serverFileId и CHECK для сочетаний статусов отсутствуют. Несколько локальных строк могут иметь одинаковые checksum и serverFileId.

Свежая schema v3 не задаёт SQL defaults. Enum converters используют name/valueOf; неизвестная строка вызывает ошибку преобразования, nullable reason сохраняет null. Room не шифруется приложением. Journal/WAL override не задан.

## Используемые группы запросов

| Запрос / изменение | Точная существенная семантика |
| --- | --- |
| insertAll IGNORE | Вставка новых PK, сохранение существующих rows |
| getAll Flow | Вся таблица, ORDER BY createdAt DESC |
| getByStatusOnce | Snapshot по status, ORDER BY createdAt DESC LIMIT; checksum=100, upload=20 |
| getByStatusWithChecksumOnce | Status + checksum IS NOT NULL, DESC createdAt LIMIT 500; SQL не исключает пустую строку |
| updateLocalMetadata | Обновляет local fields по ID после проверки change |
| resetChecksumIfFileChanged | WHERE ID AND (size отличается OR lastModified отличается); PENDING и очистка hash/ID/failure/count/time |
| updateStatus / bulk IDs | Только status по ID/IDs; без expected old status |
| updateChecksum | Hash, CHECKSUM_READY, clear failure/count/time; server ID не меняет |
| markUploaded | SYNCED, server ID, clear failure/count/time; локальный hash не меняет |
| markFailed | FAILED, reason, retryCount+1, failureAt; hash/ID не меняет |
| getRetryableFailed | FAILED + CHECKSUM_IO + count<3 + lastFailureAt<=now−300000; DESC createdAt LIMIT 500 |
| resetFailedForRetry | PENDING, reason/time=null; count/hash/server ID сохраняются |
| resetStatus | Массовый sourceStatus→targetStatus; WHERE status, остальные поля сохраняются |
| getAllMediaStoreIds / deleteByMediaStoreIds | Разность с успешным snapshot; DELETE чанками 500 |

При update по ID нет optimistic version/content generation или affected-row проверки у caller. resetStatus фильтрует source status, а change reset — size/mtime.

UNUSED в активном flow: insert(single), getByStatus Flow/getPending, exists, getLatestCreatedAt. Эти helpers не означают incremental scan или ручной retry.

## Миграции

| Путь | Действие |
| --- | --- |
| 1→2 | ADD displayName TEXT NOT NULL DEFAULT ''; relativePath TEXT; lastModified INTEGER NOT NULL DEFAULT 0 |
| 2→3 | ADD failureReason TEXT; retryCount INTEGER NOT NULL DEFAULT 0; lastFailureAt INTEGER |
| 1→3 | Зарегистрированная последовательность 1→2→3, отдельной миграции нет |
| Fallback | Настроен fallbackToDestructiveMigration(dropAllTables=true); локальный индекс может быть уничтожен при применении destructive fallback |

После 1→2 новые lastModified=0 сбрасывают hash/server ID/status при следующем scan **если** актуальное значение отличается. После 2→3 старые FAILED имеют null reason/time; retry selector их не выбирает. Факт наличия миграций не подтверждает их прохождение на установленных исторических БД.

Здесь не устанавливаются схемы всех старых dev-установок, результат downgrade или runtime validation fresh/upgraded defaults. Их предел — OPEN-AND-008. Повторного migration audit нет.

## Persistence lifecycle

Room сохраняет завершённые SQL writes между process restarts. Scan применяет insert, отдельные reset/update и stale delete без общей транзакции; промежуточное состояние доступно Flow, ошибка не откатывает уже выполненные statements. Отдельный statement атомарен, весь scan/HTTP pipeline — нет.

Logout/URL change не очищают DB. Recovery HASHING и UPLOADING происходит на входе соответствующих стадий, не при открытии БД. Stale delete и destructive fallback удаляют локальное состояние; серверных операций они не выполняют.
