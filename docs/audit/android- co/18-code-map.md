# 18. Code map and completeness audit

[CONFIRMED] Обследован один module :app, **82 production Kotlin/Java source files**, 18 package locations (включая root package), 2 test source files. Все production source files перечислены ниже; generated build sources не считаются implementation source. Android HEAD 2b9ab698c2068d1e20eeb53aa7547557c3e17b54.

## Packages/modules

| Package/module | Назначение | Ключевые связи |
| --- | --- | --- |
| :app | Android application, единственный module | Build/configuration/resources + весь код |
| root ru.tbcarus.photo_cloud_client | App, MainActivity | Hilt/WM config и Compose bootstrap |
| api | Interfaces и paths | Retrofit factory→server |
| api.models |11 named DTO/request classes | Gson↔repositories; Room не используют |
| auth | AuthRepository/AuthUiState | TokenStorage/network и UI ConnectionStatus |
| core.db | Room DB/converters/migrations | AppModule→MediaFileDao |
| core.network | Factory/parser/interceptor/authenticator | URL + clients + TokenStorage |
| core.server | Preferences и public ping | DataStore + Retrofit |
| core.storage | Token interface/pair/encrypted impl | Android security crypto и Flow |
| di |3 modules + URL provider | Singleton component |
| media | Entity/DAO, scan/hash/precheck/upload, scheduler/worker/models | Room/MediaStore/API/WM |
| ui | MainScreen shell | NavigationGraph и components |
| ui.components | Nav/dialog/loading/status models | Compose/Navigation |
| ui.screens | Files/Network/Profile/Settings + stub helper | VM state; permission launcher |
| ui.screens.auth | AuthScreen/AuthViewModel | AuthRepository/coordinator |
| ui.screens.files | FilesViewModel/FilesUiState | Room Flow/WM/coordinator |
| ui.screens.network | NetworkViewModel/NetworkUiState | Preferences/server/URL/coordinator |
| ui.theme | Palette/Theme/Typography | Compose Material3 |
| utils | Routes/JWT/IPv4 helpers | UI/configuration |

## Полный source inventory

[CONFIRMED] Каждая строка имеет ссылку на исходник; UNUSED/STUB в описании означает ограничение активного использования, а не исключение из обследования.

| File | Назначение / связи |
| --- | --- |
| [ru/tbcarus/photo_cloud_client/App.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/App.kt>) | Application, Hilt graph и WorkManager Configuration.Provider; settings bootstrap отсутствует |
| [ru/tbcarus/photo_cloud_client/MainActivity.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/MainActivity.kt>) | Единственная launcher Activity; создаёт NetworkViewModel и MainScreen |
| [ru/tbcarus/photo_cloud_client/api/ApiPaths.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/ApiPaths.kt>) | 10 route constants под api/v1 |
| [ru/tbcarus/photo_cloud_client/api/AuthService.java](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java>) | 4 POST: register/login/refresh/logout |
| [ru/tbcarus/photo_cloud_client/api/FileService.java](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/FileService.java>) | 2 POST: checksum exists / multipart upload |
| [ru/tbcarus/photo_cloud_client/api/FolderService.java](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/FolderService.java>) | 2 GET: root / children |
| [ru/tbcarus/photo_cloud_client/api/TestService.java](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/TestService.java>) | 2 GET: public/protected ping |
| [ru/tbcarus/photo_cloud_client/api/models/AuthRequest.java](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/AuthRequest.java>) | JSON email/password для register/login |
| [ru/tbcarus/photo_cloud_client/api/models/ChecksumExistsRequest.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/ChecksumExistsRequest.kt>) | JSON folderId + checksums |
| [ru/tbcarus/photo_cloud_client/api/models/ChecksumExistsResponse.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/ChecksumExistsResponse.kt>) | JSON existing/missing arrays |
| [ru/tbcarus/photo_cloud_client/api/models/ErrorResponseDto.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/ErrorResponseDto.kt>) | Error envelope id/code/message/fieldErrors |
| [ru/tbcarus/photo_cloud_client/api/models/FileItemDto.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/FileItemDto.kt>) | Upload response; используется id, остальные поля резерв |
| [ru/tbcarus/photo_cloud_client/api/models/FolderDto.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/FolderDto.kt>) | Root/children response; используется id/folderType |
| [ru/tbcarus/photo_cloud_client/api/models/LoginResponseDto.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/LoginResponseDto.kt>) | Nullable accessToken + refreshToken response |
| [ru/tbcarus/photo_cloud_client/api/models/LogoutRequest.java](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/LogoutRequest.java>) | JSON refreshToken для protected logout |
| [ru/tbcarus/photo_cloud_client/api/models/RefreshResponseDto.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/RefreshResponseDto.kt>) | Nullable accessToken response, no rotation |
| [ru/tbcarus/photo_cloud_client/api/models/RefreshTokenRequest.java](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/RefreshTokenRequest.java>) | JSON refreshToken |
| [ru/tbcarus/photo_cloud_client/api/models/TestResponse.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/TestResponse.kt>) | Ping message |
| [ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt>) | Register/login/logout/testAuth; façade TokenStorage |
| [ru/tbcarus/photo_cloud_client/auth/AuthUiState.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthUiState.kt>) | Transient login form/session view state |
| [ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt>) | Room v3, Converters, migrations1→2/2→3, destructive fallback |
| [ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt>) | Response error body→human string; id discarded |
| [ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt>) | Dynamic Retrofit services на plain/auth clients |
| [ru/tbcarus/photo_cloud_client/core/network/AuthInterceptor.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/AuthInterceptor.kt>) | Bearer из current TokenStorage |
| [ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt>) | Synchronized refresh на401 + retry guard |
| [ru/tbcarus/photo_cloud_client/core/server/ServerPreferences.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerPreferences.kt>) | DataStore settings ip/port |
| [ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt>) | Public testConnection |
| [ru/tbcarus/photo_cloud_client/core/storage/EncryptedPrefsTokenStorage.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/EncryptedPrefsTokenStorage.kt>) | EncryptedSharedPreferences secure_tokens + StateFlow |
| [ru/tbcarus/photo_cloud_client/core/storage/TokenStorage.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/TokenStorage.kt>) | Интерфейс пары токенов; getAccess/getRefresh/saveAccess helper callers не найдены |
| [ru/tbcarus/photo_cloud_client/core/storage/Tokens.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/Tokens.kt>) | Access/refresh pair |
| [ru/tbcarus/photo_cloud_client/di/AppModule.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/AppModule.kt>) | Hilt singleton DB/DAO providers |
| [ru/tbcarus/photo_cloud_client/di/BaseUrlProvider.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/BaseUrlProvider.kt>) | Mutable singleton URL, default empty |
| [ru/tbcarus/photo_cloud_client/di/NetworkModule.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt>) | Plain/auth OkHttp, BODY logger, timeouts |
| [ru/tbcarus/photo_cloud_client/di/StorageModule.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/StorageModule.kt>) | Hilt binding TokenStorage |
| [ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt>) | CAMERA exists classification, batches500, stage Mutex |
| [ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckResult.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckResult.kt>) | Precheck counters + Success/Error outcome |
| [ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt>) | SHA256 pipeline/recovery и CHECKSUM_IO restore |
| [ru/tbcarus/photo_cloud_client/media/ChecksumResult.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumResult.kt>) | Hash counters + Success/PermissionDenied/Error |
| [ru/tbcarus/photo_cloud_client/media/ChecksumUtil.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumUtil.kt>) | Pure stream SHA256 hex utility |
| [ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt>) | Повторно открываемый streaming multipart body |
| [ru/tbcarus/photo_cloud_client/media/FailureReason.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FailureReason.kt>) | 8 failed codes; isRetryable property callers не найдены, selector жёсткий |
| [ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt>) | Upload queue/recovery/permanent vs transient classification |
| [ru/tbcarus/photo_cloud_client/media/FolderRepository.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FolderRepository.kt>) | Root→children→CAMERA + CameraFolderOutcome |
| [ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt>) | ContentObserver и3s debounce→one-time work |
| [ru/tbcarus/photo_cloud_client/media/MediaFile.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFile.kt>) | Единственная Room entity,14 columns |
| [ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt>) | 21 DAO methods/wrappers; полный каталог04 |
| [ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt>) | Scan snapshot→insert/update/delete Room, observeAll |
| [ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileStatus.kt>) | 8 queue statuses; LOCAL_DELETED producer отсутствует |
| [ru/tbcarus/photo_cloud_client/media/MediaStoreImage.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreImage.kt>) | Transient OS metadata snapshot |
| [ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt>) | Images/DCIM query, permission guard и normalization |
| [ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt>) | Conditional periodic+observer lifecycle |
| [ru/tbcarus/photo_cloud_client/media/ScanResult.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ScanResult.kt>) | Scan counters, ScanOutcome и MediaStoreScanOutcome |
| [ru/tbcarus/photo_cloud_client/media/SyncResult.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncResult.kt>) | Transient aggregate стадий |
| [ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt>) | 2 unique work names, observe one-time running/status |
| [ru/tbcarus/photo_cloud_client/media/SyncStatus.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncStatus.kt>) | 4 aggregate outcomes + timestamp record |
| [ru/tbcarus/photo_cloud_client/media/SyncStatusStore.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncStatusStore.kt>) | In-memory latest sync outcome Flow |
| [ru/tbcarus/photo_cloud_client/media/SyncWorker.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt>) | Единственный CoroutineWorker, последовательная orchestration |
| [ru/tbcarus/photo_cloud_client/media/UploadResult.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/UploadResult.kt>) | Upload counters + Success/Error outcome |
| [ru/tbcarus/photo_cloud_client/ui/MainScreen.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/MainScreen.kt>) | Scaffold/topbar/5 tabs/NavController, unused snackbar host |
| [ru/tbcarus/photo_cloud_client/ui/components/BottomNavItem.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/BottomNavItem.kt>) | Label/icon/route |
| [ru/tbcarus/photo_cloud_client/ui/components/BottomNavigationBar.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/BottomNavigationBar.kt>) | Tab navigation singleTop+restoreState без popUpTo |
| [ru/tbcarus/photo_cloud_client/ui/components/ConnectionStatus.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/ConnectionStatus.kt>) | Presentation enum с Compose colors/titles |
| [ru/tbcarus/photo_cloud_client/ui/components/Dialogs.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/Dialogs.kt>) | Общий message AlertDialog |
| [ru/tbcarus/photo_cloud_client/ui/components/LoadingDialog.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/LoadingDialog.kt>) | Недоступный для dismiss modal progress indicator |
| [ru/tbcarus/photo_cloud_client/ui/components/NavigationGraph.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/NavigationGraph.kt>) | 5 destinations, network start |
| [ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt>) | Permission bootstrap и текстовый Room monitor, counters/formatters |
| [ru/tbcarus/photo_cloud_client/ui/screens/NetworkScreen.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/NetworkScreen.kt>) | IP/port/Test form/dialog |
| [ru/tbcarus/photo_cloud_client/ui/screens/ProfileScreen.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/ProfileScreen.kt>) | Достижимый STUB: CenteredText |
| [ru/tbcarus/photo_cloud_client/ui/screens/SettingsScreen.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/SettingsScreen.kt>) | Достижимый STUB: CenteredText |
| [ru/tbcarus/photo_cloud_client/ui/screens/StubComponents.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/StubComponents.kt>) | CenteredText helper |
| [ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt>) | Login/register либо ProfileContent(email/logout/test) |
| [ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt>) | Auth actions/tokensFlow/session verification/coordinator |
| [ru/tbcarus/photo_cloud_client/ui/screens/files/FilesUiState.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesUiState.kt>) | Room files + scan/sync presentation flags/results |
| [ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt>) | Observe Room/WM/status; permission scan/reconcile |
| [ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkUiState.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkUiState.kt>) | IP/port/ping status presentation model |
| [ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt>) | Единственный settings→baseUrl restore; connection test/save |
| [ru/tbcarus/photo_cloud_client/ui/theme/Color.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/theme/Color.kt>) | Compose theme palette |
| [ru/tbcarus/photo_cloud_client/ui/theme/Theme.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/theme/Theme.kt>) | PhotoCloudClientTheme/system dark/dynamic colors |
| [ru/tbcarus/photo_cloud_client/ui/theme/Type.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/theme/Type.kt>) | Compose typography |
| [ru/tbcarus/photo_cloud_client/utils/JwtUtils.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/utils/JwtUtils.kt>) | JWT payload sub display; isExpired unused |
| [ru/tbcarus/photo_cloud_client/utils/Routes.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/utils/Routes.kt>) | 5 navigation route strings |
| [ru/tbcarus/photo_cloud_client/utils/isValidIpAddress.kt](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/utils/isValidIpAddress.kt>) | IPv4 regex helper |

## Resources и build artifacts как входы

[CONFIRMED] Active manifest: [app/src/main/AndroidManifest.xml](<C:/projects/Photocloudclient/app/src/main/AndroidManifest.xml>). Дополнительный [app/AndroidManifest.xml](<C:/projects/Photocloudclient/app/AndroidManifest.xml>) не подключён custom sourceSet и классифицирован [UNUSED] для обычного build. Runtime network/backup XML: [network_security_config.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/network_security_config.xml>), [backup_rules.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/backup_rules.xml>), [data_extraction_rules.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/data_extraction_rules.xml>). values: strings.xml (app_name), themes.xml (NoActionBar activity theme), colors.xml (XML palette); drawable/mipmap — launcher assets, не отдельные screens.

[CONFIRMED] Room exports [2.json](<C:/projects/Photocloudclient/app/schemas/ru.tbcarus.photo_cloud_client.core.db.AppDatabase/2.json>), [3.json](<C:/projects/Photocloudclient/app/schemas/ru.tbcarus.photo_cloud_client.core.db.AppDatabase/3.json>); schema1 нет. [Root Gradle](<C:/projects/Photocloudclient/build.gradle.kts>), [app Gradle](<C:/projects/Photocloudclient/app/build.gradle.kts>), [settings](<C:/projects/Photocloudclient/settings.gradle.kts>), [libs.versions.toml](<C:/projects/Photocloudclient/gradle/libs.versions.toml>), [gradle-wrapper.properties](<C:/projects/Photocloudclient/gradle/wrapper/gradle-wrapper.properties>), [gradle.properties](<C:/projects/Photocloudclient/gradle.properties>), [proguard-rules](<C:/projects/Photocloudclient/app/proguard-rules.pro>) разобраны в11. local.properties — machine SDK setting, не shared API config. .idea/.git/.gradle/build не production source-of-truth.

## Final coverage checklist

| Объект | Полный перечень / количество | Где раскрыт |
| --- | --- | --- |
| [CONFIRMED] Activity / Application | MainActivity, App |01/02/08/12 |
| [CONFIRMED] Screen destinations | NetworkScreen, AuthScreen, SettingsScreen, ProfileScreen, FilesScreen —5 |01/02/08/15 |
| [CONFIRMED] UI shell / inner auth content | MainScreen; LoginContent/ProfileContent |02/06/18 |
| [CONFIRMED] ViewModels | NetworkViewModel, AuthViewModel, FilesViewModel —3 |02/06/08 |
| [CONFIRMED] Repositories | Auth, Server, MediaStore, MediaFile, Checksum, ChecksumPrecheck, Folder, FileUpload —8 |02/05/07/08 |
| [CONFIRMED] Entity / DAO | MediaFile / MediaFileDao —1/1 |03/04/09 |
| [CONFIRMED] DAO methods/wrappers |21, включая неиспользуемые |04 |
| [CONFIRMED] Migrations |1→2,2→3; destructive fallback |04 |
| [CONFIRMED] Worker classes / requests | SyncWorker —1; one-time/periodic —2 |12 |
| [CONFIRMED] Retrofit service interfaces / calls | Auth4,Test2,Folder2,File2 —4 interfaces/10 calls |05/19 |
| [CONFIRMED] DTO/request classes |11 в api.models + generic Maps |03/19 |
| [CONFIRMED] Media statuses / failure reasons |8 /8 |09/10 |
| [CONFIRMED] Scan/change/delete/permission | DCIM Images, ID mapping, resets/stale |07/08 |
| [CONFIRMED] Auth/retry/process recovery | Token storage/refresh/logout; all code branches |06/09/10/12 |
| [CONFIRMED] Tests |2 templates |13 |
| [DOCUMENTED] Server handoff | Все8 документов,36 operations |05/19 |
| [CONFIRMED] Deliverables |20 Markdown files,00–19 |00–19 |

## Предел доказательности

[CONFIRMED] Ссылки в отчётах относятся к неизменённому Android source snapshot. Наличие всех файлов, разрешимость local links, inventories и отсутствие изменений production проверяются перед завершением. Runtime build/device/server tests не запускались; причина и последствия в13. Документы являются As-Is audit, не изменением контрактов или утверждённым планом реализации.

