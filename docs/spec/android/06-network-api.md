# Verified Android API usage

Все paths ниже относительны к base URL `http://IPv4:port/` и начинаются `/api/v1`. Клиент использует **10** method/path операций. Server wire authority — [PhotoCloud Server Specification — API](../../../../PhotoCloudServer/docs/spec/server/05-api.md).

## HTTP infrastructure

| Client | Connect/read/write | Auth | Использование |
| --- | --- | --- | --- |
| plain | 5/5/5 секунд | Без AuthInterceptor и authenticator | Public test, register/login/refresh |
| auth | 10/60/60 секунд | BODY logger → AuthInterceptor; TokenAuthenticator на 401 | Test auth, logout, folders, files |

Call timeout, отдельный upload client и custom retry/backoff OkHttp не заданы. BODY logging включён для обоих clients независимо от build. Factory строит Retrofit/GsonConverterFactory при каждом вызове service; protected/public auth services получают текущий BaseUrlProvider, public test — переданный URL. Пустой URL отвергается factory.

Interfaces возвращают Call<T>; execute используется синхронно. Операции чтения/записи выполняются внутри IO contexts repositories, кроме создания service для public test до его try/IO block. Coroutine cancellation не связана с Call.cancel.

## Используемые операции

| Method/path | Client | Request | Response, фактическое потребление | Caller / flow |
| --- | --- | --- | --- | --- |
| GET /api/v1/test | plain | Нет | TestResponse.message; 2xx → message или fallback | ServerRepository ← NetworkViewModel Test/start, SyncWorker gate |
| GET /api/v1/test/auth | auth | Bearer при наличии tokens | TestResponse.message; 2xx → message или OK | AuthRepository ← Test Auth и verifySession |
| POST /api/v1/auth/register | plain | AuthRequest JSON email/password | Map<String,String>; message или Registered | AuthRepository ← AuthViewModel.register |
| POST /api/v1/auth/login | plain | AuthRequest JSON email/password | LoginResponseDto accessToken?/refreshToken?; оба nonblank обязательны для успеха | AuthRepository → AuthViewModel сохраняет Tokens |
| POST /api/v1/auth/refresh-token | plain | RefreshTokenRequest JSON refreshToken | RefreshResponseDto accessToken?; null отвергается, blank отдельно не проверяется | TokenAuthenticator.authenticate после protected 401 |
| POST /api/v1/auth/logout | auth | LogoutRequest JSON refreshToken | Map<String,String>; body не используется; HTTP response предшествует clear | AuthRepository ← AuthViewModel.logout |
| GET /api/v1/folders/root | auth | Нет | FolderDto; нужен body.id | FolderRepository, pre-check и upload |
| GET /api/v1/folders/{id}/children | auth | Path Long root ID | List<FolderDto>; первый exact folderType CAMERA; null body → emptyList | FolderRepository после root |
| POST /api/v1/files/checksums/exists | auth | JSON folderId:Long, checksums:List<String> | existing/missing lists; lowercase lookup → status-only writes | ChecksumPrecheckRepository |
| POST /api/v1/files/upload | auth | Multipart file + nullable folderId text/plain | FileItemDto; только id>0 определяет успех | FileUploadRepository для каждого PENDING_UPLOAD |

Все вызывающие принимают isSuccessful (2xx), а не только один exact success status. Сервер определяет 201 для register, 200 для остальных используемых операций.

## Wire shapes

AuthRequest, RefreshTokenRequest и LogoutRequest — Java DTO со строковыми полями. Gson использует соответствующие field names. Client-side validation email/password length отсутствует; login проверяет только наличие обоих ответных токенов.

FolderDto: id Long; parentId Long?; name/folderType String; createdAt/updatedAt String?. Только root ID и CAMERA id/type участвуют в flow. Проверки положительности folder ID, аналогичной upload id>0, нет.

ChecksumExistsRequest: folderId Long и checksums List<String>. Response: existing/missing List<String> с defaults emptyList. Отсутствие runtime JSON schema validation не гарантирует безопасность explicit null; parsing/iteration exception приводит к stage error.

FileItemDto: id Long?; folderId Long?; originalFilename, mimeType, checksum, fileType, capturedAt, uploadedAt, deletedAt — String?; size Long?; metadata Map<String,Any?>?. После upload используется только id; остальные поля не сверяются и не сохраняются. Dates остаются строками; metadata не интерпретируется.

Multipart file: name=file, filename=displayName или file_ID, content type из local MIME, stream из URI. folderId — decimal Long, text/plain, при null часть отсутствует. checksum, URI, local ID, status, capturedAt и retry metadata не отправляются.

## Response/error handling

Public ping на non-2xx возвращает сырое error body. Остальные обычные HTTP errors форматирует ApiErrorParser: fieldErrors → message → code → HTTP N. Ошибка чтения body/blank body → HTTP N; JsonSyntaxException → raw body; JSON null/объект без известных полей → HTTP N.

UUID error id и machine code не сохраняются как structured state. Upload классифицирует HTTP 400/409/413 как FAILED; прочие коды, IOException и 2xx без положительного id — возврат PENDING_UPLOAD и stop. Folder/pre-check errors прерывают соответствующий flow. Полная таблица — [11](11-errors-retry-recovery.md).

## Available on server but unused by Android

[PhotoCloud Server Specification](../../../../PhotoCloudServer/docs/spec/server/05-api.md) содержит 36 mappings; остальные 26 не вызываются Android. Реализованные, но неиспользуемые capabilities: email confirmation, logout-all/others, password reset JSON, profile read, folder CRUD, file list/get/download/rename/move/copy/delete, full checksum list; legacy POST /files — неиспользуемый alias.

Серверные resend, reset page GET/POST, profile edit/settings — STUB, также не вызываются. Video/general file acceptance сервера не означает Android video support. Полная классификация — [17](17-server-client-consistency.md); само неиспользование не является mismatch.
