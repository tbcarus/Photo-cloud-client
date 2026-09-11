# 19. Server ↔ Client Consistency

> Интеграционный отчёт: **Android implementation ↕ Server handoff specification**.
> Android: `Photocloudclient` HEAD `2b9ab69` (2026-09-10). Server: `photo-cloud-server/docs/audit/server - сс/client-handoff/01…08`.
> Источник истины о клиенте — Android-код; расхождения описаны, клиент под контракт не переписывался.
> Пути Android — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`. Severity: **LOW / MEDIUM / HIGH / CRITICAL**.

---

## 1. API

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| Base URL | `http://<IPv4>:<port>/` + `api/v1/...` | `http(s)://<host>:8080/api/v1`; HTTPS — через внешний reverse proxy | Нельзя подключиться по доменному имени или HTTPS | MEDIUM |
| Набор операций | 10 из 36 | — | Все используемые пути, методы и тела совпадают (05 §7) | — |
| Health-check | `GET /api/v1/test` перед каждым прогоном sync | статический ответ, зависимости не проверяет | Прогон стартует при недоступной БД/хранилище сервера и падает позже | LOW |
| Upload endpoint | `POST /files/upload` | `/files` и `/files/upload` идентичны; для нового кода рекомендован `/upload` | совпадает | — |
| Коды успеха | любой `2xx` | `200` для upload и папок, `201` для register | совпадает | — |
| Резолв `CAMERA` | root → children на каждый прогон, дважды | root создаёт ROOT лениво; дерево обходится по уровням | +4 запроса на прогон | LOW |

## 2. DTO

### 2.1. Таблица полей

| DTO | Server (поле: тип, nullability) | Android (поле: тип) | Serialization | Semantic | Match |
| --- | --- | --- | --- | --- | --- |
| Login request | `email: string`, `password: string (4–20)` | `AuthRequest.email/password: String` (Java private) | Gson reflection | — | ✅ |
| Login response | `accessToken: string`, `refreshToken: string` | `LoginResponseDto.accessToken/refreshToken: String?` | Gson, no-arg ctor | клиент проверяет непустоту | ✅ |
| Refresh request | `refreshToken: string` | `RefreshTokenRequest.refreshToken` | Gson | — | ✅ |
| Refresh response | `accessToken: string` (refresh не возвращается) | `RefreshResponseDto.accessToken: String?` | Gson | refresh сохраняется прежний | ✅ |
| Logout request | `refreshToken: string` | `LogoutRequest.refreshToken` | Gson | — | ✅ |
| Message response | `{"message": string}` | `Map<String,String>` | Gson | — | ✅ |
| Test response | `{"message": string}` | `TestResponse.message: String` | Gson | — | ✅ |
| `FolderDto.id` | int64, non-null | `Long` | Gson (Unsafe) | — | ✅ |
| `FolderDto.parentId` | int64 \| null | `Long?` | — | не используется | ✅ |
| `FolderDto.name` | string | `String` | — | не используется | ✅ |
| `FolderDto.folderType` | enum `ROOT\|CAMERA\|FILES\|USER` (строка) | `String`, сравнение с `"CAMERA"` | строка | неизвестные значения безопасны | ✅ |
| `FolderDto.createdAt/updatedAt` | `LocalDateTime` без TZ | `String?` | не парсится | — | ✅ |
| `ChecksumExistsRequest.folderId` | int64, **обязателен** | `Long` (non-null) | Gson | всегда `CAMERA` | ✅ |
| `ChecksumExistsRequest.checksums` | string[], 1..500, `^[0-9a-fA-F]{64}$` | `List<String>`, ≤ 500, уникальные, lowercase hex | Gson | — | ✅ |
| `ChecksumExistsResponse.existing/missing` | string[] lowercase | `List<String>` default `[]` | no-arg ctor | повторно приводится к lowercase | ✅ |
| `FileItemDto.id` | int64, non-null | `Long?` (проверка `> 0`) | Gson | → `serverFileId` | ✅ |
| `FileItemDto.folderId` | int64 | `Long?` | — | **не проверяется и не хранится** | ⚠️ |
| `FileItemDto.originalFilename` | string (может отличаться от присланного при дубле) | `String?` | — | не используется | ✅ |
| `FileItemDto.mimeType`, `size`, `fileType` | string, int64, enum | `String?`, `Long?`, `String?` | — | не используются | ✅ |
| `FileItemDto.checksum` | 64 hex lowercase | `String?` | — | **не сравнивается с локальным** | ⚠️ |
| `FileItemDto.capturedAt/uploadedAt` | `LocalDateTime` | `String?` | не парсится | не используется | ✅ |
| `FileItemDto.deletedAt` | всегда `null` | `String?` | — | не используется | ✅ |
| `FileItemDto.metadata` | object \| null; `fNumber`, decimals | `Map<String, Any?>?` | числа → `Double` | не используется | ✅ |
| `ErrorResponse` (формат A) | `id`, `code`, `message`, `fieldErrors` | `ErrorResponseDto` все nullable | Gson | используются `fieldErrors`/`message`/`code` как текст | ✅ |
| Формат B (501) | `{"message"}` | тот же DTO, `message` | Gson | — | ✅ |
| Формат C (Spring 500) | `timestamp, status, error, path` | все поля `null` → `"HTTP 500"` | Gson | — | ✅ |
| Multipart `file` | часть `file`; `Content-Type` игнорируется | `name="file"`, `filename=DISPLAY_NAME`, MediaStore MIME | OkHttp multipart | — | ✅ |
| Multipart `folderId` | параметр формы `Long`, опционален | `@Part("folderId") RequestBody` `text/plain`, опускается при `null` | Retrofit добавляет `Content-Disposition` и `Content-Transfer-Encoding: binary` | [INFERRED] сервер привяжет как параметр (17 V10) | ✅ |

### 2.2. Серверные DTO, отсутствующие на клиенте

`UserDto` (`GET /profile`), `PageResponse<FileItemDto>` (`GET /files`), `FileChecksumDto` (`GET /files/checksums`), типизированный `FileMetadataDto`, запросы создания/переименования/перемещения папок и файлов, `copy`, запросы сброса пароля.

### 2.3. Особые проверки

| Проверка | Результат |
| --- | --- |
| ID | Серверные id — `Long` (int64) на клиенте: `FolderDto.id`, `FileItemDto.id`, `MediaFile.serverFileId` ✅. Локальный `mediaStoreId` на сервер не передаётся (CLIENT_LOCAL_ONLY) ✅ |
| Checksum | Клиент считает SHA-256, lowercase, 64 hex (`ChecksumUtil.kt:24`) — идентично серверу ✅. Checksum при upload не передаётся (сервер его не принимает) ✅ |
| Timestamps | Серверные даты не парсятся — проблема таймзон пока не проявляется. Клиентские `createdAt`/`lastModified` (epoch ms) на сервер не уходят ✅ |
| Enum serialization | `folderType`, `fileType` — строки без enum-маппинга: неизвестные значения не ломают парсинг ✅. Локальные enum-ы в Room — `name` (не пересекают API) |
| Error bodies | Все три формата парсятся без исключений; `code` вызывающему коду не отдаётся ⚠️ |

## 3. Authentication

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| Refresh на `5xx`/`400` | очищает токены | только `401`/`403` терминальны; сеть/`5xx` — повторить позже | Временный сбой сервера разлогинивает пользователя, фоновый sync останавливается | MEDIUM |
| Реакция на терминальный refresh | тихая очистка; нет UI re-login и `reconcile()` | полный re-login | Пользователь не знает, что автозагрузка остановилась | MEDIUM |
| Logout при сетевой ошибке | токены остаются | «если шаг 1 или 2 не удался — всё равно очистить локальное хранилище» | Нельзя выйти офлайн | LOW |
| Logout `404` | ошибка в UI (токены очищены) | считать успехом | Ложное сообщение об ошибке | LOW |
| Проактивный refresh | нет | рекомендован перед длинными upload | Лишняя повторная передача тела после `401`; корректность сохраняется | LOW |
| Однопоточный refresh + 1 повтор | реализовано | рекомендовано | — | MATCH |
| Refresh не ротируется | сохраняет прежний refresh | так и работает | — | MATCH |
| Хранение токенов | EncryptedSharedPreferences | защищённое хранилище | — | MATCH |
| Сообщение `401 INVALID_CREDENTIALS` | сырой `message` сервера | общее сообщение + подсказка «подтвердите email» | UX | LOW |
| Регистрация | нет предупреждения о невозможности resend, нет экрана подтверждения | рекомендовано | UX-тупик у пользователя без письма | LOW |
| Пароль 4–20 | не валидируется, `400` показывается как `fieldErrors` | валидирует сервер | — | LOW |

## 4. Error handling

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| `500` без `code` | retry прогона, без лимита | backoff, **ограниченное** число попыток | Бесконечные прогоны; на upload — head-of-line blocking | MEDIUM |
| `500 DATABASE_CONSTRAINT_VIOLATION` | не различается (клиент не вызывает `move`) | не повторять никогда | Сейчас недостижимо | — |
| `400` pre-check | retry прогона | не повторять | Сейчас недостижимо (клиент шлёт валидные данные) | LOW |
| `404` upload / pre-check (папка) | retry; `CAMERA` резолвится заново | пересинхронизировать дерево | фактически совпадает | MATCH |
| `413` | `FAILED(HTTP_413)`, без повтора | не повторять | Совпадает, но без предфильтра байты передаются | LOW |
| `409` upload | `FAILED(HTTP_409)` | повторить только с другим именем | В `CAMERA` не возникает; возможен при upload без `folderId` в `Files` | LOW |
| Машинная классификация | по HTTP-коду; `code` не используется | `code` — основное поле | Достаточно для текущих операций; не масштабируется | LOW |
| Сообщения пользователю | сырой `message` сервера (смесь языков) | не показывать как есть | UX | LOW |

## 5. Upload

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| `folderId` | `CAMERA`, если найдена; **не передаёт**, если `CAMERA` ещё нет или её резолв в upload-стадии упал | всегда передавать тот же `folderId`, что и в pre-check | При ошибке резолва сервер выбирает папку по MIME (Tika). Файл, чей тип определён не как IMAGE/VIDEO, попадёт в `Files`, но будет отмечен `SYNCED` | MEDIUM |
| Первый upload без `CAMERA` | все `CHECKSUM_READY → PENDING_UPLOAD` без pre-check; upload без `folderId` | `CAMERA` создаётся лениво при первом IMAGE/VIDEO | Корректно; pre-check пропускается обоснованно | MATCH |
| Одношаговый протокол | `2xx` + `id` = готово | commit-шага нет | — | MATCH |
| Resumable | нет; повтор с нуля | не поддерживается | — | MATCH |
| Размер | нет предфильтра 100 MiB; `Content-Length` из MediaStore | лимит 104 857 600 байт, потоковая проверка | Трата трафика; риск `ProtocolException` при неверном `SIZE` | LOW / MEDIUM (G5) |
| Тип файла | только `image/*` (guard) | принимается что угодно | Видео не загружается | MEDIUM (продукт) |
| Идемпотентность при повторе | повтор с тем же `folderId` (если `CAMERA` известна) | условие идемпотентности — тот же `folderId` | При смене «с `folderId`» на «без» между попытками возможны дубли в разных папках | LOW |
| Сетевой обрыв / таймаут | transient → повтор | безопасно повторять | — | MATCH |
| Параллельность | строго по одному файлу | ограничений нет | — | — |
| Байты | [INFERRED] поток MediaStore, вероятно, с вырезанным GPS | сервер извлекает EXIF, в том числе GPS | `metadata.latitude/longitude = null`; копия не побайтовая | MEDIUM |
| Логирование тела | [INFERRED] `BODY` буферизует и печатает всё тело | — | Память/Logcat (G1) | HIGH (клиентская проблема) |

## 6. Duplicate detection

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| Правило дубля | pre-check по `(folderId=CAMERA, checksum)` | `UNIQUE(user, folder, checksum)` | — | MATCH |
| Дубль при upload | `200` + существующий `id` → `SYNCED` | дубль — не ошибка | — | MATCH |
| Локальные дубли (одно фото в двух `_ID`) | группировка по checksum; оба `SYNCED`, при upload — один серверный `id` | — | — | MATCH |
| Имя при дубле | игнорируется | возвращается прежнее имя | Клиент имя не хранит | — |
| Тот же checksum в другой папке | `missing` → upload в `CAMERA` | создаст независимую копию | By design сервера | — |
| Различение «новый» / «дубль» | не различает | признак дубля — уже известный `id` | Для backup не требуется | — |

## 7. Sync semantics

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| Владелец sync-состояния | клиент (Room) | сервер состояние не хранит | — | MATCH |
| Признак «синхронизирован» | `SYNCED` после pre-check `existing` или upload `2xx` | «существует запись с тем же checksum в **целевой** `folderId`» | Совпадает в штатном пути. Нарушается при upload без `folderId` (запись в `Files`) и не перепроверяется | MEDIUM |
| Ненадёжные признаки | не используются (имя, размер, дата) | не использовать | — | MATCH |
| Локальный флаг без `id` | `SYNCED` без `serverFileId` после pre-check | «локальный флаг без проверки `id` ненадёжен при переустановке» | Клиент перепроверяет через pre-check после потери БД, повторных загрузок нет | LOW |
| Изоляция по пользователю | **нет** — индекс общий | всё скоупировано пользователем | После смены аккаунта файлы с `SYNCED` не загружаются новому пользователю | HIGH |
| Изоляция по серверу | нет | — | То же при смене сервера | HIGH |
| Обратная синхронизация | нет | `GET /files` постранично, delta нет | Удаление на сервере не обнаруживается; `SYNCED` не пересматривается | MEDIUM |
| Удаление на устройстве | строка удаляется, сервер не уведомляется | политика не определена (серверный вопрос #1) | Де-факто backup-модель | — (open question) |
| Изменение на устройстве | новый upload, старая запись остаётся | overwrite нет | Рост дублей версий на сервере | — (open question) |
| Pre-check обязателен? | выполняется всегда, для всех файлов считается SHA-256 | pre-check — оптимизация | Хэширование всей библиотеки до первого upload | LOW |

## 8. Фактический sync protocol vs `05-sync-upload-contract.md` §11

| Шаг контракта | Контракт (рекомендация) | Android (факт) | Статус |
| --- | --- | --- | --- |
| 0. Login | сохранить access + refresh | так же | ✅ |
| 1. Корень и дерево | `GET /folders/root` → `children` → `Camera`; запомнить `targetFolderId` | так же, но **без запоминания** — дважды за прогон | ⚠️ (эффективность) |
| 2. Локальный индекс | SHA-256 каждого файла в локальную БД | так же (Room, батчи по 100) | ✅ |
| 3. Pre-check пачками ≤ 500 | `existing` → «уже на сервере»; `missing` → очередь | так же; `existing` → `SYNCED` без `id` | ✅ |
| 4. Загрузка | всегда с `folderId`; `200` → сохранить `id`, `folderId`, даты; `401` → refresh; `409` → переименовать; `413` → больше не пытаться; `404` → шаг 1; `500`/сеть → backoff | `folderId`, если известен; сохраняется только `id`; `401` → refresh (и transient); `409` → `FAILED`; `413` → `FAILED`; `404` → retry (шаг 1 заново); `500`/сеть → retry без лимита, прогон обрывается | ⚠️ |
| 5. Обратная синхронизация | `GET /files` постранично, сравнить checksum | не реализована | ❌ |
| Замечание «всегда передавайте `folderId`» | — | не передаётся при ошибке резолва | ⚠️ |
| Замечание «pre-check не гарантирует состояние на момент upload» | upload сам обработает дубль | клиент полагается на идемпотентность | ✅ |
| Критерий «файл синхронизирован» | тот же checksum в целевой `folderId` | `SYNCED`, без хранения `folderId` | ⚠️ |

## 9. IDs

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| `serverFileId` | хранится только после upload | `id` — единственный способ адресовать файл | Будущие download/delete потребуют обратной синхронизации | LOW |
| `folderId` | не хранится | рекомендуется хранить | Нельзя проверить целевую папку записи | LOW |
| `mediaStoreId` | PK локального индекса | CLIENT_LOCAL_ONLY | — | MATCH |
| user id | не хранится | в запросах не требуется | Нет изоляции индекса (§7) | HIGH (через §7) |

## 10. Checksums

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| Алгоритм / формат | SHA-256, lowercase hex | SHA-256, lowercase (вход — любой регистр) | — | MATCH |
| Когда считается | для всех файлов до pre-check | нужен для pre-check | — | MATCH |
| Контент для хэша | поток MediaStore (тот же, что для upload) | сервер считает по принятым байтам | [INFERRED] согласовано, даже если GPS вырезан | MATCH |
| Сверка с ответом | нет | — | Не обнаруживается расхождение при изменении файла между hash и upload | LOW |
| Инвалидация | по `size`/`DATE_MODIFIED` | любое изменение байтов даёт новый checksum | Изменение без смены `size`/`DATE_MODIFIED` не обнаруживается | LOW |

## 11. Retry

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| Upload retry | безопасен (идемпотентен в папке) — используется | безопасно | — | MATCH |
| Лимит попыток | нет | «ограниченное число попыток» для `500` | Бесконечные прогоны | MEDIUM |
| Backoff | default WorkManager (экспоненциальный) [INFERRED] | exponential backoff | — | MATCH |
| Rate limiting | self-limit через backoff; periodic 1 ч | клиент должен ограничивать себя сам | Observer может ставить sync после каждого фото (debounce 3 с) | LOW |
| Повтор после refresh | 1 раз | ровно 1 раз | — | MATCH |
| Head-of-line | transient-ошибка файла обрывает очередь | — | Застревание очереди | HIGH (клиент) |

## 12. Media metadata

| Area | Client does | Server expects | Impact | Severity |
| --- | --- | --- | --- | --- |
| Метаданные при upload | не передаются (кроме имени и MIME части) | сервер извлекает сам, от клиента не принимает | — | MATCH |
| MIME | MediaStore MIME в части | игнорируется, Tika | — | MATCH |
| EXIF GPS | [INFERRED] вероятно вырезан MediaStore | извлекается из байтов | Нет координат на сервере | MEDIUM |
| `capturedAt` | клиентский `DATE_TAKEN` на сервер не уходит | берётся из EXIF, иначе = `uploadedAt` | Для фото без EXIF дата на сервере = дата загрузки | LOW |
| Видео (`durationSec`) | видео не загружается | всегда `null` | — | — |
| Ориентация | не используется | не извлекается | — | — |

## 13. Feature matrix Server ↔ Android

| Feature | Server | Android | Integration status |
| --- | --- | --- | --- |
| Connectivity test (`GET /test`) | IMPLEMENTED | IMPLEMENTED | MATCH |
| Auth test (`GET /test/auth`) | IMPLEMENTED | IMPLEMENTED | MATCH |
| Registration | IMPLEMENTED | IMPLEMENTED | MATCH (UX partial) |
| Email confirmation | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Resend activation | STUB (501) | NOT FOUND | BOTH MISSING |
| Login | IMPLEMENTED | IMPLEMENTED | MATCH |
| Refresh | IMPLEMENTED | IMPLEMENTED | PARTIAL (очистка на `5xx`/`400`) |
| Logout | IMPLEMENTED | PARTIAL | PARTIAL |
| Logout all / others | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Password reset | IMPLEMENTED (web-страница — 501) | NOT FOUND | SERVER ONLY |
| Profile read | IMPLEMENTED | NOT FOUND (email из JWT) | SERVER ONLY |
| Profile / settings edit | STUB (501) | STUB (экраны-заглушки) | BOTH MISSING |
| Folders: root / children | IMPLEMENTED | IMPLEMENTED (поиск `CAMERA`) | MATCH |
| Folders: create / rename / move / delete | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Checksum pre-check | IMPLEMENTED | IMPLEMENTED | MATCH |
| Full checksum list | PARTIAL | NOT FOUND | SERVER ONLY |
| Upload | IMPLEMENTED | IMPLEMENTED (только фото) | MATCH / PARTIAL |
| Idempotent upload / duplicate | IMPLEMENTED | используется | MATCH |
| List files | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| File card | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Download | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Rename / move / copy | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Delete | IMPLEMENTED | NOT FOUND | SERVER ONLY |
| Video | PARTIAL (принимается, без метаданных) | NOT FOUND | SERVER ONLY |
| EXIF metadata | IMPLEMENTED (только изображения) | не используется | SERVER ONLY |
| Thumbnails | NOT FOUND | NOT FOUND | BOTH MISSING |
| Reverse / delta sync | NOT FOUND | NOT FOUND | BOTH MISSING |
| Trash | NOT FOUND | NOT FOUND | BOTH MISSING |
| Local deletion propagation | — | NOT FOUND | N/A (open question) |
| Sync state | не хранится (by design) | IMPLEMENTED (Room) | MATCH |
| Device model / multi-device | NOT FOUND | NOT FOUND | BOTH MISSING |
| HTTPS | NOT FOUND (reverse proxy) | NOT FOUND (только `http`) | BOTH MISSING — клиент не может использовать proxy |
| Rate limiting | NOT FOUND | self-limit через WorkManager | CLIENT ONLY |

## 14. Итог

**Совпадает [CONFIRMED]:**
- все 10 используемых операций — пути, методы, тела, коды успеха;
- формат checksum и правило дедупликации;
- батч pre-check ≤ 500;
- поиск `CAMERA` по `folderType`;
- модель токенов: без ротации, один повтор, однопоточный refresh;
- идемпотентный повтор upload;
- разбор трёх форматов ошибок.

**Ключевые расхождения:**

| # | Расхождение | Severity |
| --- | --- | --- |
| 1 | Sync-состояние не изолировано по пользователю и серверу (§7) | HIGH |
| 2 | Upload без `folderId` при ошибке резолва → расхождение pre-check и фактической папки (§5) | MEDIUM |
| 3 | Refresh `5xx` → потеря сессии (§3) | MEDIUM |
| 4 | Retry `5xx` без лимита (§4, §11) | MEDIUM |
| 5 | Нет обратной синхронизации: удаление на сервере не обнаруживается (§7) | MEDIUM |
| 6 | Вероятная потеря GPS в загружаемых байтах (§5, §12) [INFERRED] | MEDIUM |
| 7 | Только `http://IPv4` против HTTPS через proxy (§1) | MEDIUM |
| 8 | Logout офлайн не очищает токены; `404` logout не считается успехом (§3) | LOW |
