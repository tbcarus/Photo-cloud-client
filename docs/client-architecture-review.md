# Android client architecture review

Дата анализа: 2026-05-25.

## Краткое описание текущей архитектуры

Проект представляет собой один Android application-модуль `app` на Kotlin/Java с Jetpack Compose, Hilt, Retrofit/OkHttp, Room, DataStore и EncryptedSharedPreferences.

Текущая структура пакетов:

- `ru.tbcarus.photo_cloud_client` - `App`, `MainActivity`.
- `api` - Retrofit-интерфейсы и константы endpoint-ов.
- `api.models` - DTO для auth/test API, частично на Java, частично на Kotlin.
- `auth` - `AuthRepository`, состояние auth/network UI, токены и token storage.
- `network` - OkHttp `AuthInterceptor` и `TokenAuthenticator`.
- `di` - Hilt-модули для OkHttp, БД, token storage и `BaseUrlProvider`.
- `media` - Room entity/DAO/database для будущего учета медиафайлов.
- `ui` - корневой Compose-экран.
- `ui.components` - навигация, bottom bar, диалоги, loading/status components.
- `ui.screens` - экраны Network/Auth/Profile/Files/Settings, пока часть экранов являются заглушками.
- `ui.screens.auth`, `ui.screens.network` - ViewModel и экран авторизации/настройки сервера.
- `utils` - routes, preferences, JWT helpers, IP validation, HTTP status helper.

По факту архитектура сейчас ближе к прототипу с частичным разделением слоев:

- UI написан на Compose и подписывается на `StateFlow` из ViewModel.
- ViewModel содержит заметную часть orchestration-логики, а `NetworkViewModel` также напрямую создает Retrofit API.
- Repository есть только для авторизации.
- API слой описывает auth/test endpoint-ы, но Retrofit-сервисы создаются вручную в нескольких местах.
- Storage разделен на token storage, DataStore-настройки соединения и Room-заготовку для медиафайлов.
- Синхронизации, файлового repository/use case слоя, worker-ов и файлового API пока нет, хотя зависимости Room/WorkManager и модель статусов уже намечают будущую логику.

## Разделение UI / ViewModel / Repository / API / storage

### UI

UI-слой расположен в `ui`, `ui.components`, `ui.screens`.

Положительно:

- Compose-экраны в основном получают состояние из ViewModel.
- Вводы и кнопки прокидываются в ViewModel через callbacks.
- Навигация вынесена в `NavigationGraph`.

Проблемы:

- `AuthScreen` одновременно отвечает за login/register и отображение профиля/токенов. Отдельный `ProfileScreen` существует, но пока является заглушкой.
- `NetworkScreen` завязан на настройку сервера и тест соединения, то есть является не просто экраном, а входной точкой для глобальной конфигурации клиента.
- UI показывает access/refresh token в интерфейсе, пусть и сокращенно. Это удобно для отладки, но опасно для дальнейшего развития.
- Строки интерфейса захардкожены в коде, не вынесены в resources.

### ViewModel

Основные ViewModel:

- `AuthViewModel` - авторизация, регистрация, logout, проверка токена, derived-состояние токенов.
- `NetworkViewModel` - IP/port, сохранение настроек, тест соединения, установка глобального base URL.

Положительно:

- Состояние хранится через `MutableStateFlow`.
- Сетевые вызовы запускаются из `viewModelScope`.
- Блокирующие `.execute()` вызовы обернуты в `Dispatchers.IO`.

Проблемы:

- `NetworkViewModel` напрямую создает Retrofit (`Retrofit.Builder`) и работает с DataStore через singleton `AppPreferences`. Это смешивает UI orchestration, storage и API.
- `AuthViewModel` содержит знание о JWT payload и вычисляет валидность токенов через `JwtUtils`; это ближе к auth/session domain слою.
- `AuthViewModel.verifySession()` вызывает `runCatching { repo.testAuth() }`, но не обрабатывает результат, поэтому фактическая валидность серверной сессии не отражается явно в состоянии.
- В `AuthScreen` есть `LaunchedEffect(Unit) { viewModel.verifySession() }`, при этом `AuthViewModel` уже вызывает `verifySession()` в `init`. Проверка дублируется.

### Repository

Сейчас полноценный repository есть только один: `AuthRepository`.

Положительно:

- Auth flow частично отделен от UI.
- Repository инкапсулирует login/register/logout/testAuth.
- TokenStorage скрыт за интерфейсом.

Проблемы:

- `AuthRepository` создает Retrofit-сервисы на каждый вызов через приватные методы `plainService()`, `authService()`, `authTestService()`.
- Repository возвращает `Result<T>` с `Exception(String)` вместо типизированной ошибки.
- Repository одновременно отвечает за network calls, token access, token save/clear и проверку готовности base URL.
- Нет repository для server settings/network config, файлов, media index, sync queue.

### API

API слой:

- `AuthService.java`
- `TestService.java`
- `ApiPaths.kt`
- DTO в `api.models`.

Положительно:

- Endpoint paths централизованы в `ApiPaths`.
- Auth/test API отделены интерфейсами Retrofit.

Проблемы:

- Retrofit interfaces написаны на Java, рядом с Kotlin DTO (`TestResponse.kt`), что увеличивает неоднородность.
- Используются `Call<T>` и синхронный `.execute()`, хотя проект уже использует корутины. Для развития лучше перейти на `suspend` API.
- `TestService.testAuth(@Header("Authorization") String token)` принимает header вручную, но auth-клиент уже добавляет `Authorization` через interceptor. Сейчас вызывается `testAuth("")`, что создает лишний и потенциально конфликтующий контракт.
- Нет единой модели server error response.

### Storage

Сейчас состояние приложения хранится в нескольких местах:

- Токены: `EncryptedPrefsTokenStorage` через `EncryptedSharedPreferences`.
- IP/port: `AppPreferences` через Preferences DataStore.
- Runtime base URL: `BaseUrlProvider.baseUrl` как mutable singleton в памяти.
- UI state: `MutableStateFlow` внутри ViewModel.
- Медиафайлы: Room `AppDatabase`, `MediaFile`, `MediaFileDao`, пока без подключенной бизнес-логики.

Положительно:

- Access/refresh token вынесены в отдельный `TokenStorage`.
- Токены хранятся не в обычных SharedPreferences, а в `EncryptedSharedPreferences`.
- Для настроек соединения используется DataStore.
- Для будущих медиафайлов уже есть Room-схема.

Проблемы:

- Источник истины для адреса сервера раздвоен: persisted IP/port в DataStore и runtime `baseUrl` в `BaseUrlProvider`.
- `BaseUrlProvider.baseUrl` является публичной mutable property без синхронизации и без реактивного потока состояния.
- `AppPreferences` является `object` и напрямую требует `Context`, поэтому плохо тестируется и не интегрирован в DI.
- В Room DAO есть явная ошибка: `markUploaded()` выставляет статус `'UPLOADED'`, но в `MediaFileStatus` такого enum-значения нет. Есть `SYNCED`.
- Room entity уже содержит sync-поля (`checksum`, `serverFileId`, `status`), но нет слоя, который задает инварианты переходов между статусами.

## Retrofit / OkHttp / Auth

OkHttp создается в `NetworkModule`:

- `@Named("plain")` клиент: logging interceptor, timeouts.
- `@Named("auth")` клиент: logging interceptor, `AuthInterceptor`, `TokenAuthenticator`.

Auth flow:

- `AuthInterceptor` берет токены из `TokenStorage` и добавляет `Authorization: Bearer <accessToken>`.
- `TokenAuthenticator` на 401 пытается refresh token через plain client, сохраняет новые токены и повторяет запрос.
- При неуспешном refresh token storage очищается.

Сильные стороны:

- Есть разделение plain/auth clients.
- Refresh token вынесен в OkHttp `Authenticator`, а не размазан по экранам.
- Обновление токена защищено `synchronized(lock)` от параллельных refresh-запросов.

Проблемы и ограничения:

- Все HTTP body логируются через `HttpLoggingInterceptor.Level.BODY`. Это может печатать email/password, access/refresh token и ответы auth API.
- Retrofit создается вручную минимум в трех местах: `AuthRepository`, `TokenAuthenticator`, `NetworkViewModel`.
- Нет единого `RetrofitProvider`/`ApiFactory`, поэтому изменение base URL, converters, call adapters и error parsing придется размазывать.
- Timeouts 5 секунд заданы одинаково для всех запросов; для загрузки файлов это будет слишком мало.
- Base URL устанавливается после теста соединения, но auth/session flow может стартовать раньше и видеть пустой URL.
- `TokenAuthenticator` делает синхронный refresh внутри OkHttp thread, что нормально для Authenticator, но важно не допустить циклов и refresh endpoint-ов на auth client.
- Network security разрешает cleartext для `10.0.2.2` и `localhost`, а manifest дополнительно ставит `android:usesCleartextTraffic="true"` глобально. Для прод-сборок это риск.

## Обработка ошибок сервера

Сейчас обработка ошибок простая и неоднородная:

- `AuthRepository.register/login` возвращают `errorBody()?.string()` или `"Unknown error"`.
- `AuthRepository.testAuth` превращает HTTP status в описание через `getHttpStatusDescription`.
- `NetworkViewModel.testConnection` отдельно ловит `IOException`, `HttpException`, `Exception`, но при `.execute()` `HttpException` обычно не будет выброшен для HTTP error response.
- `logout()` при любой ошибке сервера выбрасывает `"Logout failed"`, теряя код и тело ответа.
- Ошибки передаются в UI строками через `message`.

Основная проблема: нет единой типизированной модели ошибок, нет различения network error / HTTP error / auth expired / validation error / server unavailable / parse error. Для синхронизации и файлов это станет болезненным, потому что retry policy зависит от типа ошибки.

## Навигация

Навигация устроена через `NavHost` в `NavigationGraph`, routes заданы строками в `utils.Routes`.

Текущее меню:

- `Network`
- `Login`
- `Settings`
- `Profile`
- `Files`

Проблемы:

- `NavHost` использует `startDestination = "network"` напрямую, хотя есть `Routes.Network`.
- Навигация не учитывает auth/session state: пользователь всегда видит все вкладки.
- Экран login фактически содержит profile при `isLoggedIn`, а отдельный route `Profile` пока заглушка.
- Нет typed navigation / sealed route модели, но для текущего размера проекта это не критично.

## Где есть дублирование логики

- Создание Retrofit:
  - `AuthRepository.plainService()`
  - `AuthRepository.authService()`
  - `AuthRepository.authTestService()`
  - `TokenAuthenticator.refreshService()`
  - `NetworkViewModel.testConnection()`
- Проверка готовности base URL:
  - `AuthRepository.isReady()`
  - `AuthViewModel.notReady()`
  - `NetworkViewModel.init/testConnection()`
- Проверка сессии:
  - `AuthViewModel.init`
  - `AuthScreen.LaunchedEffect`
- Отображение loading/message/status:
  - auth и network используют похожую схему, но разные поля (`status`/`connectionStatus`, `isLoading`/`status == LOADING`).
- Storage settings:
  - DataStore спрятан в `AppPreferences object`, а runtime base URL отдельно в `BaseUrlProvider`.

## Где код уже мешает дальнейшему развитию

1. Dynamic base URL реализован как mutable singleton.

   Для будущей синхронизации, фоновых задач и file upload нужен надежный источник конфигурации сервера. Сейчас любой слой может читать/писать `BaseUrlProvider.baseUrl`, а Retrofit создается вручную под текущее значение.

2. Нет общего network boundary.

   Когда появятся API для файлов, sync, metadata, upload/download, придется копировать создание сервисов, обработку ошибок, auth behavior и retry logic.

3. Ошибки представлены строками.

   Это мешает построить retry/backoff, offline state, re-login flow, conflict handling и понятные сообщения UI.

4. Room-модель опережает бизнес-логику.

   `MediaFileStatus` уже задает будущий state machine, но нет repository/use case, который контролирует переходы. Уже есть несовпадение `'UPLOADED'` vs `SYNCED`.

5. Auth/UI смешаны.

   `AuthScreen` показывает и форму входа, и профиль, а token/debug-состояние торчит в UI. При развитии профиля, settings и session guard это начнет конфликтовать.

6. Синхронные Retrofit calls.

   Сейчас они завернуты в IO dispatcher, но для масштабирования лучше использовать `suspend` endpoint-ы и один подход к cancellation/error handling.

7. Глобальный BODY logging.

   При развитии upload/auth это станет риском безопасности и производительности.

## Список проблем

- Нет четкой feature/data/domain структуры; пакеты разделены скорее технически, но границы ответственности уже размываются.
- `NetworkViewModel` нарушает слои: создает Retrofit, работает с DataStore и управляет глобальным base URL.
- `AuthRepository` вручную создает Retrofit-сервисы и смешивает auth API, token storage и session checks.
- Нет централизованной фабрики API/Retrofit при динамическом base URL.
- Нет типизированного результата network layer.
- Токены безопасно сохраняются, но отображаются в UI и могут попадать в logs.
- `HttpLoggingInterceptor.Level.BODY` включен для всех билдов/клиентов.
- Global cleartext traffic включен в manifest.
- `BaseUrlProvider` не является persisted/reactive source of truth.
- `AppPreferences` как static object не тестируем и не инжектится.
- `AuthViewModel.verifySession()` не обновляет UI на основе результата серверной проверки.
- `AuthScreen` и `AuthViewModel` дублируют запуск проверки сессии.
- `ProfileScreen`, `FilesScreen`, `SettingsScreen` пока заглушки, но routes уже есть.
- `markUploaded()` использует статус, которого нет в enum.
- Нет migration strategy у Room (`exportSchema = false`, нет migrations).
- WorkManager подключен, но фоновой sync-архитектуры пока нет.
- Нет тестов на auth refresh, base URL config, DAO status transitions.

## Список рисков

- Риск утечки секретов: BODY logging может записывать credentials и токены.
- Риск нестабильной авторизации: при пустом/устаревшем `baseUrl` auth repository и authenticator будут падать или работать с неверным endpoint.
- Риск сложного расширения API: без единого network layer новые file/sync API быстро размножат дублирование.
- Риск некорректной синхронизации: статусы медиафайлов уже не согласованы с DAO.
- Риск потери управляемости ошибок: строковые ошибки не позволяют отличать временную сетевую проблему от auth failure или server validation.
- Риск проблем с большими файлами: текущие 5-секундные timeouts и общий auth client не подходят для upload/download.
- Риск конфликтов UI-навигации: login/profile/settings/files не завязаны на session state.
- Риск усложнения тестирования: Context-bound singleton preferences, ручной Retrofit и mutable globals плохо мокируются.

## Что лучше не трогать пока

- Не переписывать весь UI и тему: Compose-слой достаточно простой, его можно стабилизировать после выноса state/repository.
- Не менять Room-схему радикально до проектирования sync state machine. Сначала стоит описать статусы и переходы.
- Не добавлять WorkManager-задачи до появления четкого `SyncRepository`/`MediaRepository` и типизированных ошибок.
- Не вводить многомодульность прямо сейчас. Проект пока небольшой; достаточно привести пакеты и слои в порядок внутри `app`.
- Не усложнять навигацию typed routes раньше, чем появятся реальные экраны файлов/профиля/настроек.
- Не менять механизм хранения токенов, кроме ограничения логирования/отображения. `EncryptedSharedPreferences` сейчас приемлем для текущего масштаба.

## Рекомендуемая целевая структура

Без добавления новых Gradle-модулей можно перейти к feature/data-oriented структуре внутри `app`:

```text
ru.tbcarus.photo_cloud_client
  app/
    App.kt
    MainActivity.kt

  core/
    common/
      AppResult.kt
      AppError.kt
    network/
      ApiConfig.kt
      ApiClientFactory.kt
      NetworkErrorMapper.kt
      AuthInterceptor.kt
      TokenAuthenticator.kt
    storage/
      SettingsStorage.kt
      TokenStorage.kt
    navigation/
      Routes.kt

  feature/
    auth/
      data/
        AuthApi.kt
        AuthRepository.kt
        dto/
      domain/
        SessionManager.kt
      presentation/
        AuthScreen.kt
        AuthViewModel.kt
        AuthUiState.kt
    network_settings/
      data/
        ServerSettingsRepository.kt
      presentation/
        NetworkScreen.kt
        NetworkViewModel.kt
        NetworkUiState.kt
    media/
      data/
        MediaDatabase.kt
        MediaFileDao.kt
        MediaRepository.kt
      domain/
        MediaFile.kt
        MediaFileStatus.kt
        MediaSyncStateMachine.kt
      presentation/
        FilesScreen.kt
        FilesViewModel.kt
    sync/
      data/
        SyncRepository.kt
      worker/
        SyncWorker.kt

  ui/
    components/
    theme/
```

Ключевые целевые принципы:

- UI знает только о ViewModel и UI-state.
- ViewModel вызывает repository/use case, но не создает Retrofit и не работает напрямую с DataStore/Room.
- Repository работает с API/storage и возвращает типизированный результат.
- Network layer централизует Retrofit creation, auth, error mapping и dynamic base URL.
- Settings storage является единственным persisted source of truth для server config.
- Session/token state управляется отдельным `SessionManager` или `AuthRepository`, а не экранами.
- Media/sync статусы меняются только через repository/use case, а не прямыми DAO updates из разных мест.

## Поэтапный план рефакторинга без добавления новой функциональности

### Этап 1. Зафиксировать текущие границы и убрать явные ошибки

- Добавить архитектурные smoke-тесты или минимальные unit-тесты для `JwtUtils`, `isValidIpAddress`, `MediaFileDao` status transitions.
- Исправить несоответствие `MediaFileDao.markUploaded()` и `MediaFileStatus` после отдельного review: выбрать `SYNCED` или добавить корректный статус, но не добавлять новую sync-логику.
- Заменить literal `"network"` в `NavigationGraph` на `Routes.Network`.
- Убрать двойной запуск `verifySession()` или оставить одну точку запуска.

### Этап 2. Централизовать server settings

- Заменить `AppPreferences object` на DI-интерфейс `ServerSettingsStorage`.
- Ввести `ServerSettingsRepository`, который отвечает за IP/port/baseUrl.
- Сделать `BaseUrlProvider` read-only для внешних слоев или заменить его на reactive config holder.
- Оставить UI behavior прежним: Network screen по-прежнему вводит IP/port и тестирует соединение.

### Этап 3. Централизовать Retrofit/API creation

- Ввести `ApiClientFactory` или `RetrofitProvider`, который создает сервисы по текущему base URL.
- Убрать `Retrofit.Builder` из `AuthRepository`, `NetworkViewModel`, `TokenAuthenticator`.
- Перевести API interfaces с `Call<T>` на `suspend` функции, кроме refresh внутри `Authenticator`, если решено оставить OkHttp `Authenticator`.
- Сохранить разделение plain/auth clients.

### Этап 4. Ввести единый результат network layer

- Добавить `AppResult<T>` / `NetworkResult<T>` и `AppError`.
- Реализовать mapper для HTTP/network/serialization/auth errors.
- Перевести `AuthRepository` и network test на типизированный результат.
- В UI оставить отображение строк, но получать их через mapper на presentation-уровне.

### Этап 5. Разделить auth/session/profile

- Оставить `AuthScreen` только для login/register.
- Перенести отображение профиля в `ProfileScreen`.
- Вынести token/JWT-derived state в session layer.
- Скрыть токены из обычного UI или оставить только debug-only отображение.
- Сформировать единый session state: unknown / anonymous / authenticated / expired / serverNotConfigured.

### Этап 6. Подготовить media слой к синхронизации

- Ввести `MediaRepository`, который единственный использует `MediaFileDao`.
- Описать допустимые переходы `MediaFileStatus`.
- Подготовить DAO методы под эти переходы без добавления upload/download.
- Добавить миграционную стратегию Room и включить `exportSchema`.

### Этап 7. Подготовить sync слой без новой функциональности

- Создать пустые границы `SyncRepository`/`SyncPlanner` без worker-логики.
- Определить, какие ошибки retryable, какие требуют re-auth, какие требуют вмешательства пользователя.
- Развести network clients/timeouts для обычных JSON-запросов и будущих file transfer запросов.
- Ограничить BODY logging debug-сборками и исключить токены/credentials из логов.

## Рекомендуемый ближайший порядок работ

1. Сначала исправить архитектурные острые края: Retrofit duplication, server settings source of truth, typed errors.
2. Затем разделить auth/profile/session, чтобы навигация и состояние пользователя стали предсказуемыми.
3. Потом стабилизировать Room media state machine.
4. Только после этого добавлять sync workers, file API и upload/download.

Такой порядок позволит развивать серверную логику, файлы, локальную БД и синхронизацию без переписывания приложения целиком и без закрепления текущих прототипных решений как постоянной архитектуры.
