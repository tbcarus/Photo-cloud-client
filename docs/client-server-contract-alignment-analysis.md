# Client-server contract alignment analysis

## 1. Проверенные серверные contract-файлы

- `C:/projects/photo-cloud-server/docs/api-user-contract.md`
- `C:/projects/photo-cloud-server/docs/api-file-contract.md`
- `C:/projects/photo-cloud-server/docs/api-folder-contract.md`
- `C:/projects/photo-cloud-server/docs/api-checksum-sync-contract.md`

## 2. Проверенные клиентские файлы

- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/ApiPaths.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/TestService.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/AuthRequest.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/AuthResponse.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/RefreshTokenRequest.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/LogoutRequest.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/TestResponse.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/AuthInterceptor.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/TokenStorage.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/EncryptedPrefsTokenStorage.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/Tokens.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/ProfileScreen.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt`
- Search по `app/src/main/java`: profile/password/files/folders/checksum endpoints как Retrofit API не найдены; есть только локальная `media` БД с полем `checksum`.

## 3. Endpoint alignment

| Server endpoint | Client endpoint | Client files | Status |
| --- | --- | --- | --- |
| `GET /api/v1/test` | `GET api/v1/test` | `ApiPaths.TEST`, `TestService.testServer`, `ServerRepository.testConnection` | OK |
| `GET /api/v1/test/auth` | `GET api/v1/test/auth` | `ApiPaths.TEST_AUTH`, `TestService.testAuth`, `AuthRepository.testAuth` | OK |
| `POST /api/v1/auth/register` | `POST api/v1/auth/register` | `ApiPaths.AUTH_REGISTER`, `AuthService.register`, `AuthRepository.register` | OK |
| `POST /api/v1/auth/login` | `POST api/v1/auth/login` | `ApiPaths.AUTH_LOGIN`, `AuthService.login`, `AuthRepository.login` | OK |
| `POST /api/v1/auth/refresh-token` | `POST api/v1/auth/refresh-token` | `ApiPaths.AUTH_REFRESH_TOKEN`, `AuthService.refreshToken`, `TokenAuthenticator` | OK, but response DTO is shared with login and partly mismatched |
| `POST /api/v1/auth/logout` | `POST api/v1/auth/logout` | `ApiPaths.AUTH_LOGOUT`, `AuthService.logout`, `AuthRepository.logout` | OK |
| `GET /api/v1/profile` | нет | none | not used |
| `PATCH /api/v1/profile` | нет | none | not used, server reserved `501` |
| `GET/PATCH /api/v1/profile/settings` | нет | none | not used, server reserved `501` |
| Password reset endpoints under `/api/v1/auth/password/reset/*` | нет | none | not used |
| Register confirm/resend endpoints | нет | none | not used |
| `POST /api/v1/auth/logout-all` | нет | none | not used |
| `POST /api/v1/auth/logout-others` | нет | none | not used |
| Folder endpoints under `/api/v1/folders` | нет | none | not used |
| File endpoints under `/api/v1/files` | нет | none | not used |
| `GET /api/v1/files/checksums` | нет | none | not used |
| `POST /api/v1/files/checksums/exists` | нет | none | not used |

Client obsolete endpoints: не найдены. Все endpoint-ы, реально объявленные в `ApiPaths`, есть в серверном контракте.

## 4. Request DTO alignment

| DTO | Client fields | Server fields | Status |
| --- | --- | --- | --- |
| `AuthRequest` for register | `email: String`, `password: String` | `email: string`, `password: string`; оба required; `email` not blank valid email; `password` not blank length `4..20` | OK by JSON shape; client-side validation контракта отсутствует, но серверная validation покрывает |
| `AuthRequest` for login | `email: String`, `password: String` | `email: string`, `password: string` | OK |
| `RefreshTokenRequest` | `refreshToken: String` | `refreshToken: string`; required/not blank | OK |
| `LogoutRequest` | `refreshToken: String` | `refreshToken: string`; required | OK |

Лишних или устаревших request-полей в используемых DTO не найдено. Request DTO для profile/password/files/folders/checksum отсутствуют, потому что соответствующие серверные endpoint-ы клиент пока не вызывает.

## 5. Response DTO alignment

| DTO / scenario | Client expected fields | Server response fields | Status |
| --- | --- | --- | --- |
| `TestResponse` for public test | `message: String` | `message: "All good! Permit all connection"` | OK |
| `TestResponse` for auth test | `message: String` | `message: "All good! Authenticated connection. Hello user@example.com"` | OK |
| Register response | `Map<String, String>`, reads `message` | `201 Created`, `{ "message": "Email was sent" }` | OK |
| Login response via `AuthResponse` | `accessToken: String?`, `refreshToken: String?`; repository saves `refreshToken ?: ""` | `{ "accessToken": "...", "refreshToken": "..." }` | OK by contract; client should fail if `refreshToken` is absent instead of saving empty token |
| Refresh response via `AuthResponse` | `accessToken: String?`, optional `refreshToken`; authenticator keeps old refresh when absent | `{ "accessToken": "<new-jwt-access-token>" }` only | Shape works with Gson, but DTO is semantically mismatched: `AuthResponse` suggests refresh may return `refreshToken` |
| Logout response | `Map<String, String>`, body not read by repository | `{ "message": "Logged out successfully" }` | OK, but useful server message ignored |

Gson behavior: additional server fields are ignored by default. If the server omits a field that Kotlin/Java code needs, Java DTO fields become `null`; current code handles missing refresh `accessToken` by failing in `TokenAuthenticator`, but login stores an empty refresh token when `refreshToken` is missing.

Response DTO for profile/password/files/folders/checksum отсутствуют в клиенте и не проверялись как используемые.

## 6. Error responses

Server common error shape:

```json
{
  "id": "uuid",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "fieldErrors": {
    "email": "Email must be valid"
  }
}
```

| Scenario | Server response | Client handling | Status |
| --- | --- | --- | --- |
| Register validation error | `400`, common error JSON with `code`, `message`, `fieldErrors` | `AuthRepository.register` returns raw `errorBody()?.string()` as exception message | mismatch: no common error DTO; field errors not parsed, but raw JSON is not lost |
| Register email already exists | `409`, common error JSON | raw `errorBody` string | partial: message preserved raw, code/status not structured |
| Login wrong credentials | `401 INVALID_CREDENTIALS` | raw `errorBody` string | partial: server detail preserved raw, no structured mapping |
| Refresh token empty | `400` | `TokenAuthenticator` clears tokens on any unsuccessful refresh and returns `null` | partial: correct logout effect, server message lost |
| Refresh token expired/invalid/malformed/wrong type | `401` | `TokenAuthenticator` clears tokens and returns `null` | OK for session invalidation; error details lost |
| Refresh token revoked | `403` | `TokenAuthenticator` clears tokens and returns `null` | OK for session invalidation; error details lost |
| Auth test with expired access and valid refresh | original `401`, then `POST /auth/refresh-token`; retry with new access token | `TokenAuthenticator` refreshes access token, saves tokens, retries request | OK |
| Auth test with expired access and invalid refresh | refresh returns `401/403`; common error JSON | `TokenAuthenticator` clears tokens; `tokensFlow` updates UI; original request fails with generic status handling | partial: session state correct, message lost |
| Auth test non-success after retry | common error JSON possible | `AuthRepository.testAuth` uses `getHttpStatusDescription(resp.code())`, not `errorBody` | mismatch: server message/code/fieldErrors lost |
| Logout success | `200`, `{ "message": "Logged out successfully" }` | clears tokens; UI hardcodes `"Logged out successfully"` | OK |
| Logout error | `401/403/404` common error JSON | `AuthRepository.logout` throws `"Logout failed"` and does not parse body | mismatch: server message/code lost; tokens are not cleared on server-side logout failure except via authenticator refresh failure |

## 7. Auth flow alignment

- Register: client sends `email/password` to public plain client endpoint. Path and body match. Server returns `201` with `message`; client accepts any 2xx as success and reads `message`. OK.
- Login: client sends `email/password` to public plain client endpoint. Path/body/response match. Tokens are saved by `AuthViewModel` through `repo.saveTokens(tokens)`. OK by contract.
- Token storage: `EncryptedPrefsTokenStorage` stores `accessToken` and `refreshToken`; `tokensFlow` emits on save/clear. This matches current auth needs.
- Auth test: client calls protected `GET /api/v1/test/auth` through auth client. `AuthInterceptor` adds `Authorization: Bearer <accessToken>`. OK.
- Access token: `AuthInterceptor` uses `.header("Authorization", ...)`, so stale/duplicate Authorization is replaced at OkHttp request-builder level. OK.
- Refresh token: `TokenAuthenticator` handles `401`, calls public `POST /api/v1/auth/refresh-token` through plain client, sends `{ "refreshToken": ... }`, expects new access token. Contract says refresh returns only `accessToken`; client preserves old refresh token. OK behavior, but DTO should be separated from login response.
- Logout: client calls protected `POST /api/v1/auth/logout` with refresh token in body, then clears local tokens on success. Path/body match. Error handling is too generic.
- UI reaction: `AuthViewModel` collects `tokensFlow`; `TokenAuthenticator.storage.clear()` now propagates logout state. OK.

What can break:

- If login response lacks `refreshToken`, client saves `refreshToken = ""`; later refresh/logout will fail. Contract says refreshToken is present, but client should still guard this.
- Refresh response intentionally lacks `refreshToken`; using `AuthResponse` works only because the repository/authenticator treats `refreshToken` as nullable. This is fragile and unclear.
- Server error bodies carry `code`, `message`, `fieldErrors`, but client mostly exposes raw JSON or generic text. Validation and auth failures are hard to present correctly.
- `AuthRepository.testAuth` and `logout` discard useful server messages completely.

## 8. Обязательные правки клиента

1. Introduce a client error DTO for the common server error shape: `id`, `code`, `message`, `fieldErrors`.
2. Centralize error parsing for Retrofit responses instead of using raw `errorBody()?.string()` in some paths and generic messages in others.
3. Split response DTOs by contract:
   - login response: `accessToken`, `refreshToken`;
   - refresh response: `accessToken` only;
   - message response: `message`.
4. In login handling, treat missing/blank `accessToken` or `refreshToken` as contract failure; do not save empty refresh token.
5. In `logout`, preserve/parse server error details for `401/403/404` instead of returning only `"Logout failed"`.
6. In `testAuth`, read server error body when present; status description alone is not enough for contract errors.

No mandatory path changes are required for currently used endpoints.

## 9. Future tasks outside the nearest stage

These endpoint groups are present in server contracts but absent from current client Retrofit API. Do not implement them in this analysis stage:

- Registration confirmation: `GET /api/v1/auth/register/confirm`, `POST /api/v1/auth/register/resend`.
- Password reset flow: `/api/v1/auth/password/reset/*`.
- Session management: `POST /api/v1/auth/logout-all`, `POST /api/v1/auth/logout-others`.
- Profile read/update/settings: `/api/v1/profile`, `/api/v1/profile/settings`.
- Folder API: `/api/v1/folders/*`.
- File API: `/api/v1/files/*`.
- Checksum pre-check: `POST /api/v1/files/checksums/exists`.
- Legacy checksum list: `GET /api/v1/files/checksums`.

## 10. Recommendation

Minimal next implementation stage: align auth/test DTO and error handling only. Do not add new server features.

Change:

- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/AuthResponse.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/RefreshTokenRequest.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/LogoutRequest.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/TestResponse.kt`
- new error/message/login/refresh response DTO files under `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt`
- optionally `app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt` for shared error parsing on public test.

Do not touch in the nearest stage:

- `ApiPaths.kt`, unless a new endpoint is intentionally added later.
- `TestService.java` paths.
- `AuthInterceptor.kt`, because header behavior matches the server contract.
- `TokenStorage.kt`, `EncryptedPrefsTokenStorage.kt`, `Tokens.kt`, except if tests reveal token validation needs local helpers.
- UI screens, navigation, profile/files stubs.
- File/folder/media/sync implementation.
