# 05. API integration

## Transport и factory

[CONFIRMED] [ApiServiceFactory.kt:21](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt:21>) создаёт новый Retrofit и service proxy при каждом вызове factory method. GsonConverterFactory.create() без naming policy/type adapters. Все Retrofit methods возвращают Java Call<T>, repositories исполняют .execute() в Dispatchers.IO. Suspend Retrofit API, request cancellation bridge, pagination/cache/offline HTTP queue отсутствуют.

[CONFIRMED] BaseUrlProvider хранит http://IP:port/; ApiPaths задаёт относительные api/v1/... без начального slash. plain client используется register/login/refresh/public test; auth client — protected test/logout/folders/files. AuthInterceptor добавляет Bearer; TokenAuthenticator обрабатывает401. Timeout plain5/5/5s и auth10/60/60s (connect/read/write). Custom call timeout, certificate pinning, retry policy и upload-specific client не заданы. [ApiPaths.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/ApiPaths.kt:3>), [NetworkModule.kt:27](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:27>).

## Полный реестр calls

[CONFIRMED] Клиент проверяет response.isSuccessful (любой2xx), а не exact200/201. Столбец status описывает expected по [server effective contract](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/01-api-contract-effective.md>) [DOCUMENTED].

| Retrofit method / HTTP endpoint | Request | Response | Caller / repository | Expected status | Client error path |
| --- | --- | --- | --- | --- | --- |
| AuthService.register POST /api/v1/auth/register | AuthRequest(email,password), JSON | Map<String,String> message | AuthViewModel.register→AuthRepository.register |201 | ApiErrorParser→Exception→Result.failure; dialog |
| AuthService.login POST /api/v1/auth/login | AuthRequest JSON | LoginResponseDto | AuthViewModel.login→AuthRepository.login |200 | Non2xx parsed; empty body/blank token rejected; save только VM onSuccess |
| AuthService.refreshToken POST /api/v1/auth/refresh-token | RefreshTokenRequest JSON | RefreshResponseDto | TokenAuthenticator.authenticate, plain |200 | Non2xx storage.clear; empty access/body→null no clear; IOException escapes |
| AuthService.logout POST /api/v1/auth/logout | LogoutRequest JSON + access | Map<String,String> | AuthViewModel.logout→AuthRepository.logout |200 | После HTTP response clear даже non2xx; transport exception до clear |
| TestService.testServer GET /api/v1/test | None; public | TestResponse(message) | NetworkViewModel.testConnection / SyncWorker→ServerRepository |200 | Non2xx raw error body; transport formatted exception; factory construction вне try |
| TestService.testAuth GET /api/v1/test/auth | None + access | TestResponse(message) | AuthViewModel.testAuth/verifySession→AuthRepository |200 | ApiErrorParser Result.failure; verifySession игнорирует Result |
| FolderService.getRoot GET /api/v1/folders/root | None + access | FolderDto | FolderRepository.resolveCameraFolderId; precheck+upload |200 | Non2xx parsed→CameraFolderOutcome.Error; body null error |
| FolderService.getChildren GET /api/v1/folders/{id}/children | Path id:long | List<FolderDto> | FolderRepository after root |200 | Non2xx error; null body воспринимается emptyList |
| FileService.checksumsExist POST /api/v1/files/checksums/exists | ChecksumExistsRequest(folderId,checksums) | ChecksumExistsResponse | ChecksumPrecheckRepository |200 | Current batch не изменяется при non2xx/empty body; предыдущие сохраняются |
| FileService.uploadFile POST /api/v1/files/upload | multipart file + optional folderId:text/plain | FileItemDto | FileUploadRepository |200 new or existing | id>0→success;400/409/413→FAILED; остальные HTTP/IOException→pending+stop |

Объявления: [AuthService.java:15](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java:15>), [TestService.java:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/TestService.java:7>), [FolderService.java:10](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/FolderService.java:10>), [FileService.java:14](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/FileService.java:14>). Модели: [03-data-model.md](<C:/projects/Photocloudclient/docs/audit/android/03-data-model.md>) и field-by-field [19-server-client-consistency.md](<C:/projects/Photocloudclient/docs/audit/android/19-server-client-consistency.md>).

## Call ↔ effective server comparison

| Android call | Server contract | Match | Difference |
| --- | --- | --- | --- |
| [DOCUMENTED] register | POST JSON email/password,201 message | MATCH | Client без validation email/password bounds; ошибки показывает server |
| [DOCUMENTED] login | POST JSON,200 access+refresh | MATCH | Нет expiresIn expectation; validation token present реализована |
| [DOCUMENTED] refreshToken | Public body refresh,200 access only | MATCH | Сохраняет прежний refresh; transient HTTP failures трактуются как logout |
| [DOCUMENTED] logout | Protected refresh body,200 message | MATCH/PARTIAL | HTTP request верен; локальный logout не гарантирован при IO |
| [DOCUMENTED] testServer | Public message200 | MATCH | Ping используется как обязательный gate всего Worker |
| [DOCUMENTED] testAuth | Protected message200 | MATCH | Init результата не анализирует |
| [DOCUMENTED] getRoot | GET создаёт ROOT лениво | MATCH | Root lookup не означает создание CAMERA |
| [DOCUMENTED] getChildren | Direct array, folderType=CAMERA | MATCH | Null array трактуется как CAMERA absent |
| [DOCUMENTED] checksumsExist | folder required, max500 raw, hex64, partition | MATCH | Клиент chunks500 rows + dedup; existing не даёт ID, клиент его не получает |
| [DOCUMENTED] uploadFile | single multipart, folder optional, bytes-derived hash,200 | MATCH/PARTIAL | Client читает только id; response metadata/hash/target игнорируются |

## Семантические особенности

[CONFIRMED] Precheck sends lowercase distinct hashes из batch не более500 rows; upload не передаёт checksum/createdAt/local URI/status/serverFileId. folderId определяется заново перед precheck и upload, не persisted. Upload при ошибке folder lookup использует null target; precheck при такой ошибке прерывается. При отсутствии CAMERA precheck без API exists переводит все ready в pending. [ChecksumPrecheckRepository.kt:41](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:41>), [FileUploadRepository.kt:60](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:60>).

[PARTIAL] ErrorResponseDto читает id/code/message/fieldErrors, но parser возвращает единственную строку: fieldErrors→message→code→HTTP status; malformed JSON→raw body. Не сохраняются error UUID, structured code и status в outcome. Upload самостоятельно классифицирует response.code, независимо от parser text. [ApiErrorParser.kt:12](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt:12>).

[CONFIRMED] Остальные26 серверных операций не объявлены в клиентском Retrofit: полный перечень в19. В частности GET /files, /files/{id}, download, checksum list и mutations отсутствуют. Alias POST /files не используется. Нельзя выводить поддержку server browsing из имени FilesScreen: список берётся исключительно из Room.

