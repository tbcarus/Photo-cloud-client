# 07. Local Media Storage

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Пути — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`. Метки — см. [00-executive-summary.md](00-executive-summary.md).
> Зеркальный раздел серверного `07-file-storage.md`: как клиент находит, читает и отслеживает локальные файлы.

---

## 1. Доступ к MediaStore

**[CONFIRMED]** Единственная точка чтения каталога — `MediaStoreRepository.loadImages()` (`media/MediaStoreRepository.kt:31-138`).

| Параметр запроса | Значение |
| --- | --- |
| Collection URI | `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` |
| Projection | `_ID`, `DISPLAY_NAME`, `RELATIVE_PATH`, `MIME_TYPE`, `SIZE`, `DATE_TAKEN`, `DATE_MODIFIED`, `DATE_ADDED` (`:43-52`) |
| Selection | `RELATIVE_PATH LIKE ?`, args `["DCIM/%"]` (`:58-59`) |
| Sort order | `null` (порядок провайдера) |
| Пагинация | нет — весь результат загружается в `List<MediaStoreImage>` в памяти |
| Поток | `Dispatchers.IO` |
| Ошибки | `null`-курсор → `Error`; исключение вне цикла → `Error`; исключение в строке → строка молча пропускается (`:128-130`) |

**[CONFIRMED]** Фильтр `DCIM/%` добавлен в `09c37ec` (Stage 5I). Скриншоты (`Pictures/Screenshots`), загрузки (`Download`), мессенджеры (`Pictures/…`) исключены. TODO допускает сужение до подпапок камеры (`:57`).

**[INFERRED]** На API 30+ выборка по умолчанию не включает элементы с `IS_PENDING = 1` (файл ещё пишется) и `IS_TRASHED = 1` (в корзине). Код эти флаги явно не задаёт.

## 2. Content URI и абсолютные пути

**[CONFIRMED]**
- URI строится как `ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, _ID)` (`:112-114`) и хранится строкой в `MediaFile.uri`.
- Колонка `DATA` (абсолютный путь) не запрашивается и не используется. `java.io.File` нигде не применяется.
- Все чтения — `contentResolver.openInputStream(Uri)`:

| Чтение | Где | Сколько раз на файл |
| --- | --- | --- |
| Hashing | `ChecksumRepository.kt:106` | 1 (+ повтор при auto-retry) |
| Pre-open проверка перед upload | `FileUploadRepository.kt:155-162` | 1 (поток открывается и сразу закрывается) |
| Тело upload | `ContentUriRequestBody.writeTo` (`ContentUriRequestBody.kt:31`) | 1 на каждую отправку тела; **[INFERRED]** +1 при refresh-повторе; +1 полное чтение для `HttpLoggingInterceptor(BODY)` (см. 14) |

## 3. Разрешения

| Разрешение | Манифест | Runtime-запрос | Проверки в коде |
| --- | --- | --- | --- |
| `READ_EXTERNAL_STORAGE` | `maxSdkVersion="32"` (`AndroidManifest.xml:6-8`) | на API 29–32 | 4 копии логики: `MediaStoreRepository.kt:34-41`, `ChecksumRepository.kt:65-73`, `PeriodicSyncCoordinator.kt:52-60`, `FilesScreen.kt:47-53` |
| `READ_MEDIA_IMAGES` | да (`AndroidManifest.xml:9`) | на API 33+ | там же |
| `READ_MEDIA_VIDEO` | **нет** | — | — |
| `READ_MEDIA_VISUAL_USER_SELECTED` (API 34 partial access) | **нет** | — | — |
| `ACCESS_MEDIA_LOCATION` | **нет** | — | — |

**Runtime-запрос [CONFIRMED]:** только из `FilesScreen` при входе на вкладку и только если `isAutoSyncConfigured()` (есть токены и `baseUrl`) (`FilesScreen.kt:73-83`).
- При отказе вызывается `scanPhotos()` → `PermissionDenied` → карточка «Нет доступа к фотографиям. Разрешение будет запрошено на следующем этапе.» Текст устарел: запрос уже реализован.
- Перехода в настройки при окончательном отказе нет (TODO `FilesScreen.kt:57`).
- **[INFERRED]** После «Don't ask again» `launch()` сразу возвращает отказ, и пользователь не может выдать разрешение из приложения.

**[INFERRED] Partial access (Android 14+).** Приложение не объявляет `READ_MEDIA_VISUAL_USER_SELECTED`. Поведение системы для такого приложения, когда пользователь выбирает «Разрешить ограниченный доступ», код не обрабатывает. Если в итоге выборка MediaStore вернёт только выбранные фото, stale-детекция удалит из Room строки всех остальных (§6.4). Требует проверки на API 34+ (см. 17).

## 4. Image / video

**[CONFIRMED]**
- Только изображения: коллекция `Images`, observer на `Images.Media.EXTERNAL_CONTENT_URI`.
- Перед upload стоит guard `mimeType.startsWith("image/")`, иначе `FAILED(NOT_IMAGE)` (`FileUploadRepository.kt:80-84`).
- Отсутствующий MIME заменяется на `"image/*"` (`MediaStoreRepository.kt:92-94`) и проходит guard.
- Видео не сканируется, разрешения на видео нет.

## 5. Метаданные

| MediaStore | Нормализация | Room | Пересчитывается при каждом скане |
| --- | --- | --- | --- |
| `_ID` | `<= 0` → строка пропускается | `mediaStoreId` (PK) | — |
| — | `content://…/images/media/<_ID>` | `uri` | да |
| `DISPLAY_NAME` | пусто → `image_<id>` | `displayName` | да |
| `RELATIVE_PATH` | пусто → `null` | `relativePath` | да |
| `MIME_TYPE` | пусто → `image/*` | `mimeType` | да |
| `SIZE` | `NULL` → `0` | `size` | да (и детектор изменений) |
| `DATE_TAKEN` (ms) | `> 0` иначе fallback | `createdAt` | да |
| `DATE_MODIFIED` (s) | `× 1000`; `0` → `createdAt` | `lastModified` | да (и детектор изменений) |
| `DATE_ADDED` (s) | `× 1000`, только fallback для `createdAt` | — | — |
| — | `System.currentTimeMillis()` — последний fallback `createdAt` | `createdAt` | **[RISK]** при отсутствии всех дат `createdAt` меняется на каждом скане; lastModified при этом = `createdAt` → файл будет считаться изменённым на каждом скане |

**[CONFIRMED]** EXIF, размеры, ориентация, геопозиция клиентом не читаются. Серверу из метаданных передаются только имя файла (как `filename` части) и MIME (игнорируется сервером).

### 5.1. Геопозиция в EXIF

**[INFERRED — поведение платформы Android 10+]** Если у приложения нет `ACCESS_MEDIA_LOCATION` и оно не запрашивает `MediaStore.setRequireOriginal(uri)`, MediaStore отдаёт через `openInputStream` фото с вырезанными GPS-тегами EXIF. Код не делает ни того, ни другого. Следствия:
- SHA-256 и загружаемые байты согласованы (обе операции читают одинаково изменённый поток);
- на сервер, вероятно, уходит **не побайтовая копия оригинала**;
- серверные `metadata.latitude/longitude` будут `null`.

Требует проверки (см. 17).

## 6. Жизненные события локального файла

### 6.1. Новый файл

Новый `_ID` → `insertAll(IGNORE)` → `PENDING`. Если процесс жив и observer зарегистрирован, изменение в `Images` → debounce 3 с → one-time sync.

### 6.2. Изменённый файл

**[CONFIRMED]** Критерий — `size` или `DATE_MODIFIED` отличаются от сохранённых (`MediaFileDao.kt:155-171`). Любой статус → `PENDING`; `checksum`, `serverFileId`, failure-метаданные обнуляются.

**[INFERRED] Следствия:**
- редактирование с сохранением поверх (поворот, кроп) → новый checksum → загрузка **нового** файла; старая версия остаётся на сервере, связь с ней (`serverFileId`) теряется;
- изменение только `DATE_MODIFIED` без изменения байтов (например, метаданные) → повторный hashing → pre-check `existing` → `SYNCED`, но `serverFileId` потерян;
- изменение байтов без изменения `SIZE` и `DATE_MODIFIED` не обнаруживается.

### 6.3. Перемещённый / переименованный файл

**[INFERRED — поведение MediaStore]** Переименование или перемещение через `ContentResolver.update()` сохраняет `_ID`: клиент обновит `displayName`/`relativePath` и, если изменится `DATE_MODIFIED`, пересчитает checksum. Перемещение «удалить + создать» (некоторые файловые менеджеры, копирование на ПК) даёт новый `_ID`: старая строка удаляется, новая проходит pipeline, pre-check находит тот же checksum → `SYNCED` без загрузки. Перемещение **за пределы `DCIM/`** равносильно удалению.

### 6.4. Удалённый файл

**[CONFIRMED]** Stale-детекция: все `mediaStoreId` из Room, которых нет в текущей выборке, удаляются чанками по 500 (`MediaFileRepository.kt:65-74`). Независимо от статуса строка просто исчезает. `LOCAL_DELETED` не выставляется. Сервер не уведомляется, серверная копия остаётся. **[INFERRED]** Перемещение в системную корзину (`IS_TRASHED`) на API 30+ выглядит так же; восстановление из корзины возвращает прежний `_ID`, и файл проходит pipeline заново (pre-check → `SYNCED`).

**[RISK]** Неполная выборка приводит к удалению строк. Причины: изменение фильтра, partial access, временно размонтированный том. Stale-детекция не отличает «файл удалён» от «файл не попал в выборку».

### 6.5. Недоступный файл

| Ситуация | Стадия | Результат |
| --- | --- | --- |
| `openInputStream` → `null` | hashing | `FAILED(FILE_NOT_FOUND)` |
| `FileNotFoundException` | hashing / pre-open upload | `FAILED(FILE_NOT_FOUND)` |
| `SecurityException` | hashing / pre-open upload | `FAILED(PERMISSION)` |
| `IOException` при чтении | hashing | `FAILED(CHECKSUM_IO)` → auto-retry ≤ 3 |
| Прочие исключения | hashing | `FAILED(UNKNOWN)` |
| Исключение, отличное от `FileNotFoundException`/`SecurityException`, в pre-open upload | upload | **[CONFIRMED]** не перехватывается → прогон падает → `SyncWorker` → `Result.retry()`; файл остаётся `PENDING_UPLOAD` |
| Файл исчез между pre-open и отправкой | upload | `IOException` из `writeTo` → transient → `PENDING_UPLOAD`; на следующем прогоне — scan удалит строку или pre-open даст `FILE_NOT_FOUND` |
| Реальный размер ≠ MediaStore `SIZE` | upload | **[INFERRED]** OkHttp бросит `ProtocolException` (`IOException`) → transient → head-of-line blocking (см. 16) |

**[CONFIRMED]** Статусы `FILE_NOT_FOUND` и `PERMISSION` терминальны. Если доступ позже восстановится, файл останется `FAILED`, пока у него не изменятся `size`/`DATE_MODIFIED`.

## 7. `MediaStore object ↔ Room MediaFile`

```
MediaStore.Images row (_ID, DISPLAY_NAME, RELATIVE_PATH, MIME_TYPE, SIZE, DATE_*)
        │  MediaStoreRepository.loadImages()      (filter DCIM/%, normalization)
        ▼
MediaStoreImage (in-memory snapshot)
        │  MediaFileRepository.applyToRoom()
        │   1) insertAll(IGNORE)            — новая строка PENDING
        │   2) resetChecksumIfFileChanged   — size/lastModified отличаются → PENDING
        │   3) updateLocalMetadata          — LOCAL-поля перезаписываются
        │   4) delete stale                 — _ID не найден → строка удалена
        ▼
media_files row (LOCAL + DERIVED + SERVER + SYNC_STATE)
```

| Характеристика | Значение |
| --- | --- |
| Кардинальность | 1 строка MediaStore (в `DCIM/`) ↔ 0..1 строка Room |
| Ключ сопоставления | **только `_ID` = `mediaStoreId`** |
| Используется ли checksum для идентичности между `_ID` | нет — два `_ID` с одинаковым содержимым дают две строки |
| Используется ли имя / путь для идентичности | нет |
| Что обновляется из MediaStore | все LOCAL-поля, каждый скан |
| Что MediaStore никогда не трогает | `checksum`, `serverFileId`, `status`, retry-поля — кроме сброса при изменении |

### 7.1. Поля, позволяющие узнать тот же файл между сканами

| Поле | Роль | Стабильность |
| --- | --- | --- |
| `mediaStoreId` (`_ID`) | идентичность | **[INFERRED]** стабилен, пока запись MediaStore жива; меняется при пересоздании записи и перестроении БД MediaStore |
| `size` + `lastModified` | детектор изменения содержимого | эвристика |
| `checksum` | идентичность содержимого (для сервера) | точен, но между `_ID` не используется |
| `uri` | производная от `_ID` | как `_ID` |

**[INFERRED]** При потере соответствия `_ID` (переустановка, очистка данных MediaStore, миграция устройства) весь индекс строится заново. Благодаря pre-check повторной загрузки не будет, но `serverFileId` у всех записей станет `null`.

## 8. ContentObserver

**[CONFIRMED]** `MediaChangeObserver` (`media/MediaChangeObserver.kt`):
- `registerContentObserver(Images.Media.EXTERNAL_CONTENT_URI, notifyForDescendants = true)`;
- `Handler(mainLooper)`;
- любое `onChange` (вставка, изменение, удаление, в том числе вне `DCIM/`) перезапускает debounce 3 с → `enqueueOneTimeSync()`;
- содержимое `uri` в `onChange` не анализируется.

Жизненный цикл, ограничения по процессу — в [12-background-jobs.md](12-background-jobs.md).
