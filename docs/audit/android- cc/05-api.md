# 05. API (Android networking layer)

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Пути — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`. Метки — см. [00-executive-summary.md](00-executive-summary.md).
> Эталон сравнения — `client-handoff/01-api-contract-effective.md` (далее «контракт»).

---

## 1. Стек

**[CONFIRMED]**
- Retrofit 2.11.0 + `GsonConverterFactory` (Gson по умолчанию, без кастомных адаптеров).
- OkHttp 4.12.0 + `HttpLoggingInterceptor`.
- Интерфейсы на Java возвращают `retrofit2.Call<T>`. Вызов всегда синхронный `.execute()` внутри `withContext(Dispatchers.IO)`.
- Base URL: `http://<ip>:<port>/` из `BaseUrlProvider`. Пути задаются относительно корня (`api/v1/...`, `api/ApiPaths.kt:3-21`).

## 2. HTTP-клиенты

`di/NetworkModule.kt`:

| Клиент | connect / read / write | Interceptors (порядок) | Authenticator | Используется для |
| --- | --- | --- | --- | --- |
| `@Named("plain")` | 5 s / 5 s / 5 s | `HttpLoggingInterceptor(BODY)` | — | `/test`, `/auth/register`, `/auth/login`, `/auth/refresh-token` |
| `@Named("auth")` | 10 s / 60 s / 60 s | `HttpLoggingInterceptor(BODY)` → `AuthInterceptor` | `TokenAuthenticator` | `/test/auth`, `/auth/logout`, `/folders/*`, `/files/*` |

- **[CONFIRMED]** `callTimeout` не задан.
- **[CONFIRMED]** Отдельного upload-клиента нет (TODO `NetworkModule.kt:35`).
- **[INFERRED]** `HttpLoggingInterceptor` добавлен **перед** `AuthInterceptor`, поэтому логирует запрос без заголовка `Authorization`. Тела запросов и ответов логируются целиком (см. [14-observability.md](14-observability.md)).

## 3. `ApiServiceFactory`

**[CONFIRMED]** `core/network/ApiServiceFactory.kt`. На **каждый** вызов `xxxService()` создаётся новый `Retrofit` и новый `GsonConverterFactory` (`:21-26`). Методы `plainAuthService`, `authAuthService`, `authTestService`, `authFileService`, `authFolderService` бросают `IllegalStateException("Server URL is not configured")`, если `baseUrl` пуст. `plainTestService(baseUrl)` принимает URL параметром.

**[CONFIRMED]** В `FileUploadRepository` `authFileService()` вызывается на каждый файл (`FileUploadRepository.kt:99`): Retrofit пересобирается на каждый upload. **[INFERRED]** Функционально безопасно, есть лишние аллокации.

## 4. Каталог вызовов

### 4.1. `TestService.testServer()` — проверка сервера

| | |
| --- | --- |
| Retrofit | `@GET(ApiPaths.TEST) Call<TestResponse> testServer()` — `api/TestService.java:8-9` |
| HTTP | `GET /api/v1/test` |
| Request / Response | — / `TestResponse { message: String }` |
| Клиент | plain |
| Вызывающие | `ServerRepository.testConnection(baseUrl)` ← `NetworkViewModel.testConnection()` (UI, кнопка «Test» и автоматически при старте) и `SyncWorker.runSync()` (health-check перед pipeline) |
| Коды | `2xx` → `Result.success(message)`; иначе `Result.failure(Exception(errorBody.string() ?: "Неизвестная ошибка"))` |
| Исключения | `IOException` → «Ошибка сети: …»; прочие → «Неизвестная ошибка: …» (`core/server/ServerRepository.kt:15-36`) |

### 4.2. `TestService.testAuth()` — проверка токена

| | |
| --- | --- |
| Retrofit | `@GET(ApiPaths.TEST_AUTH) Call<TestResponse> testAuth()` |
| HTTP | `GET /api/v1/test/auth` + Bearer |
| Клиент | auth |
| Вызывающие | `AuthRepository.testAuth()` ← `AuthViewModel.testAuth()` (кнопка «Test Auth») и `AuthViewModel.verifySession()` (при создании VM; результат игнорируется) |
| Коды | `2xx` → message; иначе `Exception(ApiErrorParser.parse)`; `401` сначала обрабатывает `TokenAuthenticator` |

### 4.3. `AuthService.register()`

| | |
| --- | --- |
| Retrofit | `@POST(AUTH_REGISTER) Call<Map<String,String>> register(@Body AuthRequest)` — `api/AuthService.java:16-17` |
| HTTP | `POST /api/v1/auth/register` |
| Request | `AuthRequest { email, password }` (Java, private-поля) |
| Response | `Map<String,String>` → `message` или `"Registered"` |
| Клиент | plain |
| Вызывающие | `AuthRepository.register` ← `AuthViewModel.register` |
| Коды | любой `2xx` (сервер отдаёт `201`) → успех; `400`/`409` → текст из `ApiErrorParser` в диалоге |

### 4.4. `AuthService.login()`

| | |
| --- | --- |
| HTTP | `POST /api/v1/auth/login` |
| Request / Response | `AuthRequest` / `LoginResponseDto { accessToken?, refreshToken? }` |
| Клиент | plain |
| Вызывающие | `AuthRepository.login` ← `AuthViewModel.login` |
| Обработка | `2xx`: пустое тело → «Empty login response»; пустой токен → «Invalid login response: missing token». Не-`2xx` → `ApiErrorParser` (`auth/AuthRepository.kt:38-53`) |

### 4.5. `AuthService.refreshToken()`

| | |
| --- | --- |
| HTTP | `POST /api/v1/auth/refresh-token` |
| Request / Response | `RefreshTokenRequest { refreshToken }` / `RefreshResponseDto { accessToken? }` |
| Клиент | plain; собственный `Retrofit` внутри `TokenAuthenticator` (`core/network/TokenAuthenticator.kt:27-32`) |
| Вызывающий | только `TokenAuthenticator.authenticate()` при `401` на auth-клиенте |
| Обработка | `2xx` + `accessToken` → сохранить `Tokens(newAccess, oldRefresh)` и повторить запрос; `2xx` без тела/токена → `return null` (**токены не очищаются**); не-`2xx` (**любой**, включая `5xx`) → `storage.clear()`; `IOException` → пробрасывается в исходный вызов |

### 4.6. `AuthService.logout()`

| | |
| --- | --- |
| HTTP | `POST /api/v1/auth/logout` + Bearer |
| Request / Response | `LogoutRequest { refreshToken }` / `Map<String,String>` |
| Клиент | auth (при `401` сначала refresh) |
| Вызывающие | `AuthRepository.logout` ← `AuthViewModel.logout` |
| Обработка | нет токенов → `Exception("No tokens")` до запроса; после **любого HTTP-ответа** — `storage.clear()`; не-`2xx` → ошибка в UI; `IOException` → исключение **до** `clear()`, токены остаются (`auth/AuthRepository.kt:55-64`) |

### 4.7. `FolderService.getRoot()`

| | |
| --- | --- |
| HTTP | `GET /api/v1/folders/root` + Bearer |
| Response | `FolderDto` |
| Клиент | auth |
| Вызывающие | `FolderRepository.resolveCameraFolderId()` ← `ChecksumPrecheckRepository.runCameraPrecheck()` и `FileUploadRepository.runUpload()` — **дважды за прогон sync** |
| Обработка | не-`2xx` → `CameraFolderOutcome.Error(ApiErrorParser)`; тело без `id` → Error; исключение → Error |

### 4.8. `FolderService.getChildren(id)`

| | |
| --- | --- |
| HTTP | `GET /api/v1/folders/{id}/children` + Bearer |
| Response | `List<FolderDto>` |
| Обработка | ищется первая папка с `folderType == "CAMERA"` (`media/FolderRepository.kt:51`); не найдена → `NotCreatedYet`; ошибка → `Error` |

### 4.9. `FileService.checksumsExist()`

| | |
| --- | --- |
| HTTP | `POST /api/v1/files/checksums/exists` + Bearer |
| Request | `ChecksumExistsRequest { folderId: Long, checksums: List<String> }` — до 500 уникальных lowercase-значений |
| Response | `ChecksumExistsResponse { existing, missing }` |
| Клиент | auth |
| Вызывающий | `ChecksumPrecheckRepository.runPrecheckForFolder()` (`media/ChecksumPrecheckRepository.kt:97-98`) |
| Обработка | не-`2xx` → `Error` (предыдущие батчи уже записаны); `null`-тело → `Error`; любое исключение → `Error`; ответ повторно приводится к lowercase |

### 4.10. `FileService.uploadFile()`

| | |
| --- | --- |
| Retrofit | `@Multipart @POST(FILES_UPLOAD) Call<FileItemDto> uploadFile(@Part MultipartBody.Part file, @Part("folderId") RequestBody folderId)` — `api/FileService.java:22-27` |
| HTTP | `POST /api/v1/files/upload`, `multipart/form-data` |
| Часть `file` | `name="file"`, `filename=displayName` (или `file_<id>`), `Content-Type` = MediaStore MIME, тело — `ContentUriRequestBody` (поток из `content://`, `Content-Length` = MediaStore `SIZE`, при `0` — chunked) |
| Часть `folderId` | `text/plain` с id `CAMERA`; при `null` часть не отправляется (Retrofit пропускает `null` `@Part`) |
| Response | `FileItemDto` — используется только `id` |
| Клиент | auth (read/write 60 s) |
| Вызывающий | `FileUploadRepository.runUpload()` (`media/FileUploadRepository.kt:97-99`) |
| Обработка | `2xx` + `id > 0` → `SYNCED`; `2xx` без `id` → transient; `400`/`409`/`413` → `FAILED(HTTP_*)`; прочие коды → transient; `IOException` → transient. Transient возвращает файл в `PENDING_UPLOAD` **и обрывает весь прогон** |

## 5. Обработка ошибок: `ApiErrorParser`

**[CONFIRMED]** `core/network/ApiErrorParser.kt:12-39` возвращает **строку** по приоритету:
1. `fieldErrors` → `"field: msg; field2: msg2"`;
2. `message`;
3. `code`;
4. `"HTTP <code>"`.

Если тело не парсится как JSON-объект — возвращается сырое тело. Пустое тело → `"HTTP <code>"`.

| Серверный формат (`03-error-contract.md` §1) | Результат парсера |
| --- | --- |
| A — `ErrorResponse {id, code, message, fieldErrors}` | `fieldErrors` или `message` |
| B — `{"message": ...}` (501) | `message` |
| C — Spring `{timestamp, status, error, path}` | все поля DTO `null` → `"HTTP 500"` |
| `text/plain` | `JsonSyntaxException` → сырое тело [INFERRED] |

**[CONFIRMED]** Машиночитаемый `code` вызывающему коду **не возвращается**. Классификация ошибок upload идёт только по HTTP-статусу. Сообщения сервера (смесь русского и английского) показываются пользователю как есть через `showDialog`.

## 6. Сериализация

| Аспект | Факт |
| --- | --- |
| Java-DTO запросов | Gson сериализует private-поля через reflection; имена совпадают с JSON (`email`, `password`, `refreshToken`) |
| Kotlin data class без no-arg конструктора (`FolderDto`, `FileItemDto`, `TestResponse`) | **[INFERRED]** Gson создаёт объект через `Unsafe`, Kotlin-nullability не проверяется: отсутствующее поле даст `null` в non-null `String`/`Long` |
| Kotlin data class со всеми default (`ChecksumExistsResponse`, `ErrorResponseDto`, `LoginResponseDto`, `RefreshResponseDto`) | есть no-arg конструктор → default-значения применяются |
| Даты | не парсятся (`String?`) |
| Enum (`folderType`, `fileType`) | строки; неизвестные значения безопасны |
| `metadata` | `Map<String, Any?>` — числа станут `Double`; не используется |

## 7. Сравнение с серверным контрактом

| Android call | Server contract (`01-api-contract-effective.md`) | Match | Difference |
| --- | --- | --- | --- |
| `GET api/v1/test` (`testServer`) | §1: `200 {"message"}`, без auth, **статический — не проверяет БД/хранилище** | ✅ | Клиент использует его как health-check в `SyncWorker`: «сервер жив» ≠ «pipeline выполним» |
| `GET api/v1/test/auth` (`testAuth`) | §1: Bearer, `200 {"message"}`, `401` | ✅ | — |
| `POST api/v1/auth/register` | §2: `{email, password}` → `201 {"message"}`; `400`, `409` | ✅ | Нет клиентской валидации 4–20; нет экрана «подтвердите email» |
| `POST api/v1/auth/login` | §3: `{email, password}` → `200 {accessToken, refreshToken}`; `400`, `401` | ✅ | Сообщение `401` показывается сырым; поле пароля не маскируется (UI) |
| `POST api/v1/auth/refresh-token` | §3: без auth; `{refreshToken}` → `200 {accessToken}`; `401`/`403` терминальны | ⚠️ | Клиент очищает токены при **любом** не-`2xx`, включая `400` и `5xx` |
| `POST api/v1/auth/logout` | §3: Bearer + `{refreshToken}`; `404` можно считать успехом | ⚠️ | Любой не-`2xx` показывается ошибкой (токены очищены); при сетевой ошибке токены **не** очищаются |
| `GET api/v1/folders/root` | §6: `FolderDto`, создаёт ROOT | ✅ | Вызывается дважды за прогон, без кэша |
| `GET api/v1/folders/{id}/children` | §6: прямые потомки | ✅ | Поиск `CAMERA` по `folderType` — соответствует |
| `POST api/v1/files/checksums/exists` | §7: `folderId` обязателен; 1..500 элементов, 64 hex; `existing`/`missing` lowercase | ✅ | Батч ≤ 500, lowercase, дедупликация до отправки. Коды ошибок не различаются (всё → retry прогона) |
| `POST api/v1/files/upload` | §7: multipart `file` + опц. `folderId`; `200 FileItemDto`; `400/401/404/409/413`; идемпотентен в папке | ✅ / ⚠️ | Путь, части, коды — совпадают. `folderId` не передаётся, если `CAMERA` нет или её резолв упал: при ошибке резолва pre-check (по `CAMERA`) и upload (сервер выбирает папку по MIME) могут разойтись. `404` трактуется как transient (контракт: пересинхронизировать дерево — фактически это и происходит на следующем прогоне) |

## 8. Серверные операции, не используемые клиентом

**[CONFIRMED]** Из 36 серверных операций (`01-api-contract-effective.md` §0) клиент вызывает 10. Не используются:

| Группа | Операции |
| --- | --- |
| Регистрация | `GET /auth/register/confirm` (ссылка открывается вне приложения), `POST /auth/register/resend` (501) |
| Сессии | `POST /auth/logout-all`, `POST /auth/logout-others` |
| Пароль | `POST /auth/password/reset/request`, `/reset/confirm`, `/reset/resend` (501), `/reset/page` (501) |
| Профиль | `GET /profile`, `PATCH /profile` (501), `GET/PATCH /profile/settings` (501) |
| Папки | `POST /folders`, `PATCH /folders/{id}`, `POST /folders/{id}/move`, `DELETE /folders/{id}` |
| Файлы | `POST /files` (legacy upload), `GET /files`, `GET /files/{id}`, `GET /files/{id}/download`, `PATCH /files/{id}`, `POST /files/{id}/move`, `POST /files/{id}/copy`, `DELETE /files/{id}`, `GET /files/checksums` |

**[CONFIRMED]** Константа `ApiPaths.FILES` (`api/v1/files`) используется только как префикс для `FILES_UPLOAD` и `FILES_CHECKSUMS_EXISTS`. Legacy `POST /files` не вызывается.
