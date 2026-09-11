# 01. System overview

## Ответственность и границы

[CONFIRMED] Клиент управляет подключением к заданному IPv4/port, локальными credentials, чтением фотографий DCIM, локальным индексом и доставкой bytes. Сервер выбирает/создаёт логический объект, определяет его ID и metadata; Android получает только результат. OS отвечает за доступ к MediaStore, исполнение WorkManager и жизненный цикл процесса. Клиент не владеет оригинальными фото и не копирует их в собственный файловый каталог. [NetworkViewModel.kt:49](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:49>), [MediaStoreRepository.kt:31](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:31>), [ContentUriRequestBody.kt:28](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt:28>).

~~~mermaid
flowchart TD
 UI["MainActivity / MainScreen / Compose screens"] --> VM["NetworkViewModel / AuthViewModel / FilesViewModel"]
 VM --> R["AuthRepository / ServerRepository / MediaFileRepository"]
 VM --> C["PeriodicSyncCoordinator / SyncScheduler"]
 C --> WM["WorkManager: SyncWorker"]
 WM --> P["Scan → ChecksumRepository → ChecksumPrecheckRepository → FileUploadRepository"]
 P --> ROOM["MediaFileDao / Room"]
 P --> MS["ContentResolver / MediaStore.Images"]
 P --> API["ApiServiceFactory / Retrofit / OkHttp"]
 R --> ROOM
 R --> MS
 R --> API
 API --> SERVER["PhotoCloud server"]
 VM --> PREF["ServerPreferences / BaseUrlProvider"]
 R --> TOKEN["TokenStorage"]
 OBS["MediaChangeObserver"] --> C
 MS -. notifications .-> OBS
~~~

[CONFIRMED] Отдельных use cases в этой схеме нет. Worker вызывает repositories напрямую; FilesViewModel вызывает local scan напрямую при выдаче/отказе permission. Coordinator включает periodic+observer по токенам, URL и permission, но не выполняет pipeline. [FilesViewModel.kt:110](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/files/FilesViewModel.kt:110>), [PeriodicSyncCoordinator.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt:38>).

## Пользовательские сценарии

| Сценарий | Путь по UI | Результат / ограничение |
| --- | --- | --- |
| [CONFIRMED] Настроить сервер | Network→IP/port→Test | Public ping; сохранение только при успехе; URL всегда http |
| [CONFIRMED] Зарегистрироваться | Login→Register | Письмо по server API; локального confirm/resend/reset flow нет |
| [CONFIRMED] Войти | Login→Login | Сохранение двух токенов, показ JWT sub как email, reconcile background |
| [CONFIRMED] Проверить session | Login→Test Auth; init AuthViewModel | Protected ping с refresh на401; init результат не показывается |
| [CONFIRMED] Дать доступ к фото | Files при настроенном auth/URL | Runtime permission→reconcile и scan; при уже выданном доступе только reconcile |
| [CONFIRMED] Наблюдать backup | Files | Список Room, counters, общий последний outcome; нет preview/progress bytes |
| [CONFIRMED] Выйти | Login в режиме профиля→Logout | Remote revoke request; детали local clear см.06 |
| [PARTIAL] Настроить sync / профиль | Settings / Profile | Только centered text |
| [UNUSED] Ручной sync/retry | UI control не найден | Публичные методы repositories не делают функцию пользовательской |

## Состояние системы

[CONFIRMED] Persistent state:

| Хранилище | Состав | Scope / сброс |
| --- | --- | --- |
| Room photo_cloud.db | media_files: 14 полей, очередь и remote ID | Общая для установки; scan удаляет stale; logout не очищает |
| EncryptedSharedPreferences secure_tokens | accessToken, refreshToken | Одна пара для приложения; clear/logout/failed HTTP refresh |
| DataStore settings | ip, port | Сохранённое соединение, logout не очищает |
| WorkManager internal persistence | One-time/periodic requests и execution state | Управляется библиотекой, не частью AppDatabase; custom input отсутствует |

[CONFIRMED] Transient state: BaseUrlProvider.baseUrl; три UiState и корутины ViewModel; tokensFlow как in-memory зеркало secure prefs; SyncStatusStore; состояние observer/debounce; stage mutex; агрегаты Scan/Checksum/Precheck/Upload/SyncResult. UI state не использует SavedStateHandle. При process death теряются формы, сообщения, last scan/last sync и observer.

[INFERRED] Восстановление Room не равно восстановлению всей системы: foreground создаёт NetworkViewModel и читает settings, headless Worker этого не делает. WorkManager persistence сохраняет намерение выполнить задачу, но не наполняет BaseUrlProvider. [App.kt:10](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/App.kt:10>), [NetworkViewModel.kt:31](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:31>), [SyncWorker.kt:50](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:50>).

## Взаимодействие с OS

[CONFIRMED] Используются Application/ComponentActivity, Compose activity-result permission launcher, ContentResolver, ContentObserver с main Handler, MediaStore.Images.EXTERNAL_CONTENT_URI, Android security crypto, WorkManager Configuration.Provider + HiltWorkerFactory. Пользовательские Service/BroadcastReceiver/foreground notification, explicit boot receiver, SAF picker и MediaStore write/delete calls не найдены.

[PARTIAL] До API33 запрашивается READ_EXTERNAL_STORAGE, с33 READ_MEDIA_IMAGES. Код не моделирует отдельный selected-photos grant и не подписывается на permission change/onResume. Поведение частичного доступа конкретной OS требует runtime проверки; нельзя считать любой неполный snapshot доказательством физического удаления. [FilesScreen.kt:46](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:46>), [MediaFileRepository.kt:64](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaFileRepository.kt:64>).

