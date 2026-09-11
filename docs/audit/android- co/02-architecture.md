# 02. Architecture

## Стиль, модули и граф зависимостей

[CONFIRMED] settings.gradle.kts включает только :app. Внутри application module смешаны Kotlin и Java, packages служат логическими границами, compile-time module isolation отсутствует. [INFERRED] Стиль ближе к MVVM с repositories и infrastructure services, чем к строгой Clean Architecture: нет domain interfaces/use cases, Room entity напрямую становится UI model.

~~~mermaid
flowchart LR
 A["App / Hilt singleton graph"] --> DB["AppModule → AppDatabase → MediaFileDao"]
 A --> NET["NetworkModule → plain/auth OkHttp"]
 A --> ST["StorageModule → EncryptedPrefsTokenStorage"]
 ACT["MainActivity"] --> NAV["MainScreen → NavigationGraph"]
 NAV --> VMS["3 ViewModels"]
 VMS --> REPOS["Auth / Server / MediaFile repositories"]
 VMS --> BG["PeriodicSyncCoordinator → SyncScheduler"]
 BG --> W["Hilt SyncWorker"]
 W --> MR["MediaFileRepository → MediaStoreRepository"]
 W --> CR["ChecksumRepository"]
 W --> PR["ChecksumPrecheckRepository"]
 W --> UR["FileUploadRepository"]
 MR --> DB
 CR --> DB
 PR --> DB
 UR --> DB
 PR --> FR["FolderRepository"]
 UR --> FR
 FR --> AF["ApiServiceFactory"]
 UR --> AF
 PR --> AF
 REPOS --> AF
 AF --> NET
 NET --> AU["AuthInterceptor / TokenAuthenticator"]
 AU --> ST
~~~

## Слои

[CONFIRMED] Ниже state — реально удерживаемое состояние, а не предполагаемый слой.

| Layer/package | Ответственность и ключевые классы | Зависимости | State |
| --- | --- | --- | --- |
| Entry | App, MainActivity | Hilt, Android, Compose, WorkManager | WorkerFactory; activity-owned NetworkViewModel |
| UI shell | MainScreen, NavigationGraph, BottomNavigationBar | Navigation Compose, Screens | NavController/back stack, unused SnackbarHostState |
| Presentation | NetworkViewModel, AuthViewModel, FilesViewModel | Конкретные repositories, scheduler/coordinator | MutableStateFlow, viewModelScope |
| UI screens | NetworkScreen, AuthScreen, FilesScreen; Profile/Settings stubs | UiStates, Android permission API | Launcher/LaunchedEffect; derived counters |
| auth | AuthRepository, AuthUiState | TokenStorage, ApiServiceFactory, BaseUrlProvider | Tokens через storage; UiState объявлен здесь, зависит от UI ConnectionStatus |
| core.server | ServerPreferences, ServerRepository | DataStore, Retrofit factory | ip/port; API result |
| media scan | MediaStoreRepository, MediaFileRepository | Context/ContentResolver, DAO | Полный snapshot в памяти; mutex отсутствует |
| media checksum | ChecksumRepository, ChecksumUtil | Context, DAO, MessageDigest | stage Mutex; счётчики прогона |
| media network | FolderRepository, ChecksumPrecheckRepository, FileUploadRepository | API, DAO, Android stream | отдельные precheck/upload Mutex; folder cache отсутствует |
| storage/network | TokenStorage + encrypted impl, factory, interceptor/authenticator, parser | Android crypto, OkHttp/Gson | token Flow, authenticator lock; factory без cache |
| database | AppDatabase + Converters, MediaFileDao | Room | singleton DB и persisted index |
| background | SyncWorker, SyncScheduler, PeriodicSyncCoordinator, MediaChangeObserver, SyncStatusStore | Repositories, OS/WM | WM jobs + in-memory observer/result |
| di/utilities | 3 Hilt modules, BaseUrlProvider, JwtUtils, Routes, IPv4 helper | Hilt, Android Base64/JSON, Compose types в UI enums | URL mutable singleton; utilities stateless |
| domain/use cases | NOT FOUND | — | — |

Источники: [AppModule.kt:13](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/AppModule.kt:13>), [NetworkModule.kt:15](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:15>), [StorageModule.kt:11](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/StorageModule.kt:11>), [FilesViewModel.kt:18](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt:18>), [AuthRepository.kt:17](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt:17>), [SyncWorker.kt:20](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:20>).

## DI и создание объектов

[CONFIRMED] @HiltAndroidApp создаёт graph, @AndroidEntryPoint activity, три @HiltViewModel, @HiltWorker с Assisted context/params. AppModule предоставляет singleton AppDatabase/DAO; AppDatabase дополнительно содержит свой volatile/synchronized singleton. StorageModule bind singleton TokenStorage. NetworkModule создаёт singleton plain/auth clients и общий BODY logger. Constructor-injected repositories, provider, coordinator, scheduler, observer, status store — singleton; AuthInterceptor/TokenAuthenticator удерживаются singleton auth client. [App.kt:9](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/App.kt:9>), [AppDatabase.kt:55](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt:55>), [NetworkModule.kt:19](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:19>).

## Границы и состояние

[PARTIAL] UI не выполняет SQL/HTTP непосредственно. Однако FilesScreen решает, когда запросить permission и запустить bootstrap (LaunchedEffect(Unit)); форматирует статусы/failureReasons и 7 раз подсчитывает список. NetworkViewModel конструирует URL, сохраняет настройки и инициирует scheduler. AuthViewModel отвечает за saveTokens/reconcile после login: AuthRepository.login сам не сохраняет session. Worker является фактическим orchestration/use-case layer.

[INCONSISTENCY] AuthUiState в auth package зависит от UI ConnectionStatus, который несёт Compose Color. MediaFile одновременно Room entity, результат repository и модель списка. Android Context присутствует в scan/checksum/upload repositories, Android Uri в request body. Это фактические зависимости, не нарушение неизвестного project rule. [AuthUiState.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthUiState.kt:3>), [ConnectionStatus.kt:5](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/ConnectionStatus.kt:5>), [FilesUiState.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesUiState.kt:8>).

[RISK] Stage Mutex не обеспечивает atomic pipeline. One-time и periodic используют разные unique names, scan не заблокирован, restoreRetryableFailed также вне checksum mutex. Обновления DAO адресуют только mediaStoreId, без expected status/version/content generation. Scan может сбросить status/checksum между snapshot и записью результата другого этапа. [SyncScheduler.kt:29](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:29>), [ChecksumRepository.kt:45](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:45>), [MediaFileDao.kt:26](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileDao.kt:26>).

[PARTIAL] Дубли состояния: persisted ip/port ↔ mutable baseUrl ↔ NetworkUiState; secure prefs ↔ tokensFlow ↔ AuthUiState; Room file states ↔ WM WorkInfo running ↔ SyncStatusStore. Синхронизация этих представлений неполная: token clear не вызывает coordinator автоматически, periodic running не входит в observeIsSyncing, last sync исчезает при restart. [SyncScheduler.kt:87](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncScheduler.kt:87>), [AuthViewModel.kt:26](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:26>).

## Navigation As-Is

[CONFIRMED] Начальная route network. Все пять tabs доступны независимо от login/URL. Navigation использует launchSingleTop=true/restoreState=true, но не popUpTo/saveState; switch tabs может накапливать entries, восстановление detached tab state явно не организовано. Отдельный NetworkViewModel передаётся из activity; Auth/Files создаются hiltViewModel внутри destinations. Deep links, navigation arguments, nested auth graph и обязательный login gate отсутствуют. [BottomNavigationBar.kt:29](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/BottomNavigationBar.kt:29>), [NavigationGraph.kt:18](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/NavigationGraph.kt:18>).

[UNUSED] SnackbarHost присутствует, вызовов showSnackbar не найдено. Отдельные ProfileScreen/SettingsScreen достижимы, поэтому это STUB, а не UNUSED.

