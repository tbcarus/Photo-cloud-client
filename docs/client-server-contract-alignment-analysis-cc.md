# Client–Server Contract Alignment Analysis

## 1. Проверенные файлы

### Серверные контракты (источник истины)
- `docs/api-user-contract.md` — Auth, Login, Logout, Password, Profile
- `docs/api-file-contract.md` — Files, Upload, Checksum
- `docs/api-folder-contract.md` — Folders
- `docs/api-checksum-sync-contract.md` — Checksum pre-check

### Клиентские файлы
- `api/ApiPaths.kt`
- `api/AuthService.java`
- `api/TestService.java`
- `api/models/AuthRequest.java`
- `api/models/AuthResponse.java`
- `api/models/RefreshTokenRequest.java`
- `api/models/LogoutRequest.java`
- `api/models/TestResponse.kt`
- `auth/AuthRepository.kt`
- `auth/AuthUiState.kt`
- `core/network/ApiServiceFactory.kt`
- `core/network/AuthInterceptor.kt`
- `core/network/TokenAuthenticator.kt`
- `core/storage/TokenStorage.kt`
- `core/storage/EncryptedPrefsTokenStorage.kt`
- `core/storage/Tokens.kt`
- `core/server/ServerRepository.kt`
- `di/NetworkModule.kt`
- `ui/screens/auth/AuthViewModel.kt`
- `ui/screens/network/NetworkViewModel.kt`
- `utils/getHttpStatusDescription.kt`

---

## 2. Таблица соответствия endpoint-ов

| Server endpoint | Client path / место вызова | Status |
|---|---|---|
| `GET /api/v1/test` | `ApiPaths.TEST` → `TestService.testServer()` → `ServerRepository` | **OK** |
| `GET /api/v1/test/auth` | `ApiPaths.TEST_AUTH` → `TestService.testAuth()` → `AuthRepository.testAuth()` | **OK** |
| `POST /api/v1/auth/register` | `ApiPaths.AUTH_REGISTER` → `AuthService.register()` → `AuthRepository.register()` | **OK** |
| `POST /api/v1/auth/login` | `ApiPaths.AUTH_LOGIN` → `AuthService.login()` → `AuthRepository.login()` | **OK** |
| `POST /api/v1/auth/refresh-token` | `ApiPaths.AUTH_REFRESH_TOKEN` → `AuthService.refreshToken()` → `TokenAuthenticator` | **OK** (путь верный, но есть проблема с DTO — см. раздел 4) |
| `POST /api/v1/auth/logout` | `ApiPaths.AUTH_LOGOUT` → `AuthService.logout()` → `AuthRepository.logout()` | **OK** |
| `GET /api/v1/auth/register/confirm?code=...` | — | **not used** |
| `POST /api/v1/auth/register/resend` | — | **not used** |
| `POST /api/v1/auth/logout-all` | — | **not used** |
| `POST /api/v1/auth/logout-others` | — | **not used** |
| `POST /api/v1/auth/password/reset/request` | — | **not used** |
| `POST /api/v1/auth/password/reset/confirm` | — | **not used** |
| `GET /api/v1/auth/password/reset/page` | — | **not used** |
| `GET /api/v1/profile` | — | **not used** |
| `GET /api/v1/files` | — | **not used** |
| `POST /api/v1/files` | — | **not used** |
| `POST /api/v1/files/upload` | — | **not used** |
| `GET /api/v1/files/{id}` | — | **not used** |
| `GET /api/v1/files/{id}/download` | — | **not used** |
| `POST /api/v1/files/checksums/exists` | — | **not used** |
| `GET /api/v1/files/checksums` | — | **not used** |
| `GET /api/v1/folders/root` | — | **not used** |
| `GET /api/v1/folders/{id}/children` | — | **not used** |

---

## 3. Таблица request DTO

| DTO | Поля клиента | Поля сервера | Status |
|---|---|---|---|
| `AuthRequest` | `email`, `password` | `email`, `password` | **OK** |
| `RefreshTokenRequest` | `refreshToken` | `refreshToken` | **OK** |
| `LogoutRequest` | `refreshToken` | `refreshToken` | **OK** |

Других request DTO в клиенте нет — всё соответствует.

---

## 4. Таблица response DTO

| DTO / endpoint | Поля клиента | Поля сервера | Status |
|---|---|---|---|
| `TestResponse` (test + test/auth) | `message: String` | `{"message":"..."}` | **OK** |
| `AuthResponse` для `POST /auth/login` | `accessToken`, `refreshToken` | `{"accessToken","refreshToken"}` | **OK** |
| `AuthResponse` для `POST /auth/refresh-token` | `accessToken`, `refreshToken` | `{"accessToken"}` — **refreshToken отсутствует** | **mismatch** |
| Register response (`Map<String,String>`) | читается `map.get("message")` | `{"message":"Email was sent"}`, **status 201** | **OK** — `isSuccessful` покрывает 201 |
| Logout response (`Map<String,String>`) | только проверяется `isSuccessful` | `{"message":"Logged out successfully"}` | **OK** |

### Детали по `AuthResponse` на refresh-token

Сервер возвращает только `{"accessToken": "..."}` — поле `refreshToken` отсутствует.  
Клиент (`TokenAuthenticator:60`):
```kotlin
val newRefresh = body.refreshToken ?: current.refreshToken
```
Gson десериализует отсутствующее поле как `null`, фолбек на `current.refreshToken` срабатывает — **функционально не ломается**.  
Но `AuthResponse` используется для двух семантически разных ответов с разным набором полей.  
Если в будущем сервер начнёт возвращать вращаемый refresh token, клиент подхватит его корректно — но это неявное поведение.

---

## 5. Таблица обработки ошибок

Серверный формат ошибки:
```json
{
  "id": "uuid",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "fieldErrors": { "email": "Email must be valid" }
}
```

| Сценарий | Ответ сервера | Обработка клиента | Status |
|---|---|---|---|
| Register — validation error (400) | `{"code":"VALIDATION_ERROR","fieldErrors":{...}}` | `errorBody()?.string()` — сырой JSON передаётся в UI | **mismatch** — пользователь видит JSON-строку |
| Login — неверные credentials (401) | `{"code":"INVALID_CREDENTIALS","message":"..."}` | `errorBody()?.string()` — сырой JSON | **mismatch** — пользователь видит JSON-строку |
| Refresh token — expired/invalid (401) | любой 4xx | `storage.clear()` → silent logout | **OK** — токены очищаются, `tokensFlow` реагирует |
| Test auth — expired token (401) | `{"code":"...","message":"..."}` | `getHttpStatusDescription(code)` — возвращает `"401 Unauthorized"`, игнорирует `errorBody` | **mismatch** — сообщение сервера теряется |
| Logout — failure | любой 4xx | `throw Exception("Logout failed")` — hardcoded строка, токены НЕ очищаются | **mismatch** — при ошибке логаут не завершается локально |
| Любая ошибка — `fieldErrors` | `{"fieldErrors":{"field":"msg"}}` | не парсится нигде | **mismatch** — отдельные ошибки полей не читаются |
| Register/login — `message` из ошибки | `{"message":"Human-readable text"}` | `errorBody()?.string()` — вместо `message` показывается полный JSON | **mismatch** |

---

## 6. Auth flow — анализ

| Шаг | Соответствие контракту | Проблемы |
|---|---|---|
| Register | Путь, тело, статус 201 — ✅ | Ошибки показываются как raw JSON |
| Login | Путь, тело, response — ✅ | `body.refreshToken ?: ""` — фолбек к пустой строке вместо ошибки; ошибки как raw JSON |
| Сохранение токенов | Оба токена в `EncryptedSharedPreferences` — ✅ | — |
| Auth test | `GET /test/auth`, `Bearer` header — ✅ | На ошибке теряется сообщение сервера |
| `AuthInterceptor` | Добавляет `Authorization: Bearer <accessToken>` — ✅ | — |
| `TokenAuthenticator` — refresh | Путь и тело верны — ✅ | **Баг: staleness check всегда false** (см. ниже) |
| Logout | Путь, тело, protected endpoint — ✅ | На ошибке токены не чистятся локально |
| `tokensFlow` реакция UI | `collect` в `AuthViewModel.init` — ✅ | — |

### Баг в `TokenAuthenticator` (staleness check)

`TokenAuthenticator.kt:44-48`:
```kotlin
val current = storage.getTokens() ?: return null     // вызов 1
val latest  = storage.getTokens() ?: return null     // вызов 2
if (latest.accessToken != current.accessToken) { ... }  // всегда false
```
Оба вызова внутри `synchronized(lock)`, между ними нет ожидания.  
`latest == current` всегда, поэтому проверка «другой поток уже обновил токен» никогда не срабатывает.  
Правильная реализация: взять токен из заголовка упавшего запроса (`response.request.header("Authorization")`), сравнить с текущим хранимым. Если отличается — вернуть запрос с обновлённым хранимым токеном без нового refresh.

---

## 7. Обязательные правки клиента

| # | Проблема | Файл(ы) | Приоритет |
|---|---|---|---|
| 1 | Нет `ErrorResponseDto` — ошибки сервера показываются как сырой JSON | `AuthRepository.kt`, новый файл `api/models/ErrorResponse.kt` | Высокий |
| 2 | `testAuth()` использует `getHttpStatusDescription()` вместо `errorBody()` — теряется сообщение сервера | `AuthRepository.kt:61` | Высокий |
| 3 | `fieldErrors` не парсится — пользователь не видит, какое поле содержит ошибку | `AuthRepository.kt`, `ErrorResponse.kt` | Высокий |
| 4 | Баг в `TokenAuthenticator`: staleness check всегда false, риск лишнего refresh | `TokenAuthenticator.kt:44-48` | Средний |
| 5 | `logout()` при ошибке не чистит токены локально — пользователь остаётся залогиненым с невалидным сессией | `AuthRepository.kt:48-55` | Средний |
| 6 | `AuthResponse` используется для двух endpoint-ов с разным набором полей — `refreshToken` всегда null для `/refresh-token` | `AuthResponse.java`, `AuthService.java`, `TokenAuthenticator.kt` | Низкий (работает через null-фолбек) |

---

## 8. Будущие задачи (вне ближайшего этапа)

| Задача | Серверный endpoint |
|---|---|
| Подтверждение email после регистрации | `GET /api/v1/auth/register/confirm?code=...` |
| Повторная отправка кода подтверждения | `POST /api/v1/auth/register/resend` |
| Logout всех сессий | `POST /api/v1/auth/logout-all` |
| Logout всех остальных сессий | `POST /api/v1/auth/logout-others` |
| Запрос сброса пароля | `POST /api/v1/auth/password/reset/request` |
| Подтверждение сброса пароля | `POST /api/v1/auth/password/reset/confirm` |
| Профиль пользователя | `GET /api/v1/profile` |
| Список файлов | `GET /api/v1/files` |
| Загрузка файлов | `POST /api/v1/files/upload` |
| Checksum pre-check перед upload | `POST /api/v1/files/checksums/exists` |
| Папки пользователя | `GET /api/v1/folders/root`, `GET /api/v1/folders/{id}/children` |
| Операции с файлами (rename, move, copy, delete) | `PATCH/POST/DELETE /api/v1/files/{id}` |
| Операции с папками (create, rename, move, delete) | `POST/PATCH/POST/DELETE /api/v1/folders/{id}` |

---

## 9. Рекомендация по следующему этапу

### Минимальный следующий этап (4A): Error Response Parsing

**Цель:** привести обработку ошибок к серверному контракту — пользователь видит человекочитаемое сообщение, а не JSON.

**Что менять:**

| Файл | Что делать |
|---|---|
| `api/models/ErrorResponse.kt` (новый) | Создать data class с полями `id`, `code`, `message`, `fieldErrors: Map<String, String>?` |
| `AuthRepository.kt` | Заменить `errorBody()?.string()` на парсинг через `ErrorResponse`; убрать `getHttpStatusDescription()` из `testAuth()`; в `logout()` — чистить токены локально при любом исходе |
| `TokenAuthenticator.kt` | Исправить staleness check: брать токен из `response.request.header("Authorization")` |
| `AuthService.java` | (опционально) Заменить `Call<AuthResponse>` на `Call<RefreshResponse>` для `refreshToken()`, чтобы убрать ложное поле `refreshToken` |

**Что не трогать:**
- `ApiPaths.kt` — все пути верны
- `AuthInterceptor.kt` — корректен
- `ApiServiceFactory.kt` — корректен
- `NetworkModule.kt` — корректен
- `TokenStorage` / `EncryptedPrefsTokenStorage` — корректны
- `AuthUiState.kt` — корректен
- `TestService.java`, `TestResponse.kt` — корректны
- `ServerRepository.kt` — корректен (ошибка сервера там ожидается, а не валидационный ответ)
