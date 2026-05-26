# Android-клиент: архитектурный обзор

> Дата: 2026-05-25  
> Ветка: master  
> Состояние: pre-sync, до реализации работы с файлами и синхронизацией

---

## 1. Текущая архитектура — краткое описание

### Стек
- Kotlin + Java (смешанный), Jetpack Compose, Hilt, Retrofit 2, OkHttp 4, Room, WorkManager (зависимость есть, кода нет), DataStore, EncryptedSharedPreferences

### Слои и пакеты

```
ru.tbcarus.photo_cloud_client
├── App.kt, MainActivity.kt
├── api/               — Retrofit-интерфейсы (Java) + DTO-модели (Java/Kotlin)
├── auth/              — TokenStorage, AuthRepository, UiState-классы
├── di/                — Hilt-модули (AppModule, NetworkModule, StorageModule)
├── media/             — Room Entity + DAO + Database
├── network/           — OkHttp Interceptor + Authenticator
├── ui/
│   ├── MainScreen.kt
│   ├── components/    — BottomNav, NavigationGraph, ConnectionStatus, диалоги
│   ├── screens/       — экраны (Composable) + ViewModels
│   └── theme/
└── utils/             — AppPreferences, JwtUtils, Routes, вспомогательные функции
```

### Текущий функционал
- Экран сети: ввод IP/порт, ping сервера, сохранение адреса через DataStore
- Экран аутентификации: регистрация, логин, logout, показ токенов, проверка сессии
- Room-база с таблицей `media_files`, готовая к синхронизации
- Два OkHttp-клиента: `plain` (без авторизации) и `auth` (с `AuthInterceptor` + `TokenAuthenticator`)
- Refresh токена через `TokenAuthenticator.authenticate()` с синхронизацией через `synchronized`

---

## 2. Список проблем

### P1 — Критические (сломают следующий этап)

**P1.1 — `MediaFileStatus.UPLOADED` не существует в enum**  
В `MediaFileDao.markUploaded()` используется raw-строка `'UPLOADED'` в SQL:
```sql
UPDATE media_files SET status = 'UPLOADED', serverFileId = :serverFileId ...
```
Enum содержит `SYNCED`, а не `UPLOADED`. TypeConverter бросит `IllegalArgumentException` при первом вызове. Вероятно, нужно было `'SYNCED'` или добавить `UPLOADED` в enum.

**P1.2 — `MediaFile.id` и `mediaStoreId` — дублирующий и неопределённый `@PrimaryKey`**  
Entity имеет `id: Long` как `@PrimaryKey` без `autoGenerate = true` и отдельное поле `mediaStoreId: Long`. Все операции DAO используют `mediaStoreId`, `id` нигде не заполняется и не используется. При вставке нужно будет явно задавать `id` — это либо сломает вставку, либо потребует дублирования `mediaStoreId` в `id`. Неясно намерение.

**P1.3 — Retrofit-инстансы создаются на каждый вызов**  
В пяти разных местах Retrofit строится ad-hoc внутри методов:
- `AuthRepository.plainService()` — при каждом вызове
- `AuthRepository.authService()` — при каждом вызове
- `AuthRepository.authTestService()` — при каждом вызове
- `TokenAuthenticator.refreshService()` — при каждом вызове
- `NetworkViewModel.testConnection()` — inline внутри ViewModel

Это дорогостоящая операция, обходит DI-граф, и означает, что сервисы нельзя протестировать через замену зависимостей.

**P1.4 — `NetworkViewModel` строит Retrofit внутри ViewModel**  
`testConnection()` создаёт `Retrofit` + делает сетевой вызов прямо в ViewModel без Repository-слоя. При расширении (смена протокола, hostname validation, retry) логика будет только разрастаться в ViewModel.

**P1.5 — `BaseUrlProvider` — нереактивный мутируемый синглтон**  
`var baseUrl: String = ""` — изменяемое поле без синхронизации и без уведомления зависимых компонентов. Если адрес меняется (например, переключение сервера), уже созданные Retrofit-инстансы продолжат обращаться по старому URL. При динамически строящихся сервисах (как сейчас) это маскируется, но при кеше сервисов — ломает.

---

### P2 — Существенные (ухудшат развитие)

**P2.1 — Дублирование логики токенов в `AuthRepository`**  
`AuthRepository.getTokens()`, `saveTokens()`, `clearTokens()` — просто делегаты к `TokenStorage`. `TokenStorage` уже инжектируется напрямую в `AuthInterceptor` и `TokenAuthenticator`. Два источника правды для токенов вводят риск рассинхронизации и неясность: вызывать `repo.getTokens()` или `storage.getTokens()`?

**P2.2 — Токены в `AuthUiState`**  
`savedAccessToken: String?` и `savedRefreshToken: String?` — сырые токены хранятся в UI-состоянии. Это создаёт отладочный дисплей токенов, но означает, что токены доступны любому, кто держит ссылку на `StateFlow<AuthUiState>`. При расширении (другие ViewModels, Composables) токены могут утечь в логи или UI.

**P2.3 — `isLoggedIn` не реактивно связан с TokenStorage**  
При выкидывании токенов через `TokenAuthenticator` (неуспешный refresh → `storage.clear()`) `AuthUiState.isLoggedIn` остаётся `true` до следующего ручного вызова `refreshTokensOverview()`. Нет реактивного моста: изменение в хранилище не обновляет UI.

**P2.4 — `NetworkUiState` живёт в пакете `auth`**  
`auth/NetworkUiState.kt` описывает состояние сетевого экрана, но находится в пакете авторизации. Это пакетная ошибка, затрудняющая навигацию по коду.

**P2.5 — `AppPreferences` — статический объект, принимающий `Context`**  
`object AppPreferences` требует `Context` при каждом вызове. Он не инжектируется, его нельзя подменить в тестах, и он тянет `Context` через ViewModel в DAL. Стандартный подход — обернуть DataStore в репозиторий/провайдер и инжектировать его.

**P2.6 — `AppDatabase` имеет двойной синглтон**  
Companion object `getInstance()` реализует ручной синглтон, но Hilt также управляет им как `@Singleton` через `AppModule`. Ручной синглтон лишний — Hilt уже гарантирует одну инстанцию.

**P2.7 — Смешение Java и Kotlin в `api/`**  
`AuthService.java`, `TestService.java`, `AuthRequest.java`, `AuthResponse.java` и `LogoutRequest.java`, `RefreshTokenRequest.java` — Java-классы в Kotlin-проекте. Они не поддерживают Kotlin-idioms (data classes, null safety, coroutines). Новые API-сервисы нужно будет либо писать на Java (несогласованность), либо в Kotlin (несогласованность в том же пакете).

**P2.8 — Два паттерна обработки ошибок**  
`AuthRepository` использует `runCatching {}` → `Result<T>`. `NetworkViewModel` использует ручной `try/catch` с тремя `catch`-блоками. Единого подхода нет.

**P2.9 — `testAuth()` в `TestService` передаёт `@Header` вручную**  
```java
Call<TestResponse> testAuth(@Header("Authorization") String token);
```
При использовании с `authClient` `AuthInterceptor` уже добавляет заголовок. `AuthRepository` вызывает `testAuth("")`, `""` перезаписывается интерцептором. Аргумент токена — лишний и вводит в заблуждение.

---

### P3 — Мелкие / технический долг

**P3.1 — `verifySession()` вызывается дважды при старте `AuthViewModel`**  
В `init {}` — `viewModelScope.launch { refreshTokensOverview(); verifySession() }`, а `AuthScreen` вызывает `LaunchedEffect(Unit) { viewModel.verifySession() }`. Два сетевых вызова при открытии экрана.

**P3.2 — `isValidIpAddress` regex некорректен**  
```kotlin
val regex = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(\\.|$)){4}")
```
Regex пытается матчить 4 повторения `(octet)(.|$)`, но последний `$` внутри группы не работает как anchor всей строки при использовании `{4}`. Строки вида `999.999.999.999` могут пройти валидацию. Следует использовать `Patterns.IP_ADDRESS` из Android SDK.

**P3.3 — Prop drilling `NetworkViewModel` через 3 слоя**  
`MainActivity` → `MainScreen` → `NavigationGraph` → `NetworkScreen` — хотя `hiltViewModel()` доступен в любой Composable-функции.

**P3.4 — `startDestination` в `NavigationGraph` захардкожен строкой**  
```kotlin
NavHost(navController, startDestination = "network")
```
Не используется `Routes.Network`.

**P3.5 — `AuthScreen` объединяет Login и Profile**  
Один экран условно рендерит два совершенно разных состояния (`LoginContent` / `ProfileContent`). При расширении профиля (аватар, статистика) и форм входа (2FA, forgot password) экран разрастётся.

**P3.6 — WorkManager в зависимостях, но не используется**  
`work-runtime-ktx:2.9.0` добавлен, кода нет. Увеличивает APK.

**P3.7 — `UPLOADED` статус отсутствует, но `MediaFileStatus` не полон**  
Статус `PENDING_UPLOAD` есть, но нет чёткого конечного состояния «успешно загружен» (enum имеет `SYNCED`, DAO пишет `'UPLOADED'`). Сама машина состояний не валидирована.

---

## 3. Список рисков

| # | Риск | Вероятность | Влияние |
|---|------|-------------|---------|
| R1 | `markUploaded()` бросает `IllegalArgumentException` при первом вызове синхронизации | Высокая | Критическое: блокирует upload |
| R2 | `MediaFile.id` без `autoGenerate` вызовет `PRIMARY KEY constraint failed` при вставке | Высокая | Критическое: блокирует запись в БД |
| R3 | Retrofit создаётся на каждый запрос → OutOfMemory при частых вызовах / параллельных загрузках | Средняя | Высокое: деградация при синхронизации |
| R4 | `BaseUrlProvider.baseUrl` читается без синхронизации из разных потоков | Средняя | Среднее: race condition при смене адреса |
| R5 | TokenAuthenticator очищает токены без уведомления UI → пользователь не знает, что разлогинен | Высокая | Среднее: UX-деградация |
| R6 | Отсутствие `READ_MEDIA_IMAGES` / `FOREGROUND_SERVICE` в манифесте → crash при запросе медиа | Высокая (нужно для синхронизации) | Критическое |
| R7 | DataStore (`AppPreferences`) без инжекции → невозможно тестировать NetworkViewModel | Средняя | Среднее: технический долг растёт |
| R8 | Java-модели без null-safety проверок → NPE в Kotlin-коде | Средняя | Среднее |

---

## 4. Рекомендуемая целевая структура

```
ru.tbcarus.photo_cloud_client
├── App.kt
├── MainActivity.kt
│
├── core/
│   ├── network/
│   │   ├── AuthInterceptor.kt
│   │   ├── TokenAuthenticator.kt
│   │   └── ApiErrorMapper.kt       # единый маппер ошибок
│   ├── storage/
│   │   ├── TokenStorage.kt         # интерфейс
│   │   └── EncryptedPrefsTokenStorage.kt
│   └── db/
│       ├── AppDatabase.kt
│       └── converters/
│
├── data/
│   ├── auth/
│   │   ├── AuthApi.kt              # Kotlin Retrofit interface (suspend fun)
│   │   ├── model/                  # Kotlin data classes
│   │   └── AuthRepository.kt
│   ├── media/
│   │   ├── MediaApi.kt             # будущий API для файлов
│   │   ├── MediaFileDao.kt
│   │   ├── MediaFile.kt
│   │   ├── MediaFileStatus.kt
│   │   └── MediaRepository.kt
│   └── server/
│       ├── ServerApi.kt
│       └── ServerRepository.kt     # ping, смена адреса
│
├── di/
│   ├── NetworkModule.kt            # Retrofit singleton + 2 OkHttpClient
│   ├── DatabaseModule.kt
│   ├── StorageModule.kt
│   └── RepositoryModule.kt
│
├── domain/                         # (опционально, при росте бизнес-логики)
│   └── sync/
│       └── SyncWorker.kt
│
└── ui/
    ├── navigation/
    │   ├── Routes.kt
    │   └── AppNavGraph.kt
    ├── screens/
    │   ├── network/
    │   │   ├── NetworkScreen.kt
    │   │   ├── NetworkViewModel.kt
    │   │   └── NetworkUiState.kt   # перенести из auth/
    │   ├── auth/
    │   │   ├── LoginScreen.kt      # разделить AuthScreen
    │   │   ├── ProfileScreen.kt
    │   │   └── AuthViewModel.kt
    │   ├── files/
    │   │   ├── FilesScreen.kt
    │   │   └── FilesViewModel.kt
    │   └── settings/
    ├── components/
    └── theme/
```

### Ключевые архитектурные решения для целевого состояния

1. **Retrofit как `@Singleton` в DI** — один `AuthApi` и один `MediaApi`, создаются при старте приложения, `baseUrl` задаётся через `BaseUrl`-qualifier или перестраивается при смене адреса через `OkHttpClient` с кастомным `HostnameVerifier` / URL-перехватчиком
2. **`BaseUrlProvider` → `Flow<String>`** — реактивный, `NetworkRepository` сохраняет URL, подписчики (Retrofit, ViewModel) реагируют на смену
3. **`AppPreferences` → инжектируемый класс** — `@Singleton class ServerPreferences @Inject constructor(dataStore: DataStore<Preferences>)`
4. **Единый `Result<T>` + маппер ошибок** — `ApiErrorMapper` преобразует `HttpException`, `IOException`, body в доменные ошибки
5. **Реактивный `isLoggedIn`** — `TokenStorage` возвращает `Flow<Tokens?>`, `AuthViewModel` подписывается
6. **`MediaFile.mediaStoreId` как `@PrimaryKey`** — убрать дублирование с `id`

---

## 5. Поэтапный план рефакторинга

> Правило: каждый этап — отдельный PR, не смешивать рефакторинг с новой функциональностью, тесты не ломать.

### Этап 0 — Исправление критических багов (делать первым, до любого рефакторинга)

| Задача | Файл | Суть |
|--------|------|------|
| 0.1 | `MediaFileDao.kt` | Заменить `'UPLOADED'` на `'SYNCED'` в `markUploaded()` |
| 0.2 | `MediaFile.kt` | Убрать `id: Long`, сделать `@PrimaryKey val mediaStoreId: Long` |
| 0.3 | `MediaFileStatus.kt` | Решить: добавить `UPLOADED` или зафиксировать `SYNCED` как финальный статус — и привести DAO в соответствие |
| 0.4 | `AndroidManifest.xml` | Добавить `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE` (под соответствующие API levels) |

---

### Этап 1 — Перемещение файлов без изменения логики (~1 ч)

| Задача | Суть |
|--------|------|
| 1.1 | Переместить `NetworkUiState.kt` из `auth/` в `ui/screens/network/` |
| 1.2 | Создать пакет `core/network/`, перенести `AuthInterceptor`, `TokenAuthenticator` |
| 1.3 | Создать пакет `core/storage/`, перенести `TokenStorage`, `EncryptedPrefsTokenStorage`, `Tokens` |
| 1.4 | Создать пакет `core/db/`, перенести `AppDatabase` и конвертеры |
| 1.5 | Зафиксировать `Routes.Network` как `startDestination` в `NavigationGraph` |

---

### Этап 2 — Исправление инфраструктуры DI и Retrofit (~2-3 ч)

| Задача | Суть |
|--------|------|
| 2.1 | Убрать `getInstance()` из `AppDatabase`, оставить только Hilt |
| 2.2 | Вынести `AppPreferences` из `object` в `@Singleton class ServerPreferences @Inject constructor(...)` с DataStore через Hilt |
| 2.3 | Добавить провижн `AuthApi` (Retrofit singleton) в `NetworkModule`; убрать `plainService()` / `authService()` / `authTestService()` из `AuthRepository` |
| 2.4 | Убрать создание Retrofit из `NetworkViewModel.testConnection()` — делегировать в `ServerRepository` |
| 2.5 | Убрать prop-drilling `NetworkViewModel`: удалить параметр из `MainScreen` и `NavigationGraph`, использовать `hiltViewModel()` в `NetworkScreen` |

---

### Этап 3 — Конвертация Java → Kotlin в `api/` (~1-2 ч)

| Задача | Суть |
|--------|------|
| 3.1 | Переписать `AuthService.java` → `AuthApi.kt` с `suspend fun` вместо `Call<>` |
| 3.2 | Переписать `TestService.java` → `ServerApi.kt`, убрать лишний `@Header` у `testAuth` |
| 3.3 | Конвертировать `AuthRequest`, `AuthResponse`, `LogoutRequest`, `RefreshTokenRequest` → Kotlin data classes |
| 3.4 | Обновить `AuthRepository` и `TokenAuthenticator` под новые интерфейсы |

---

### Этап 4 — Унификация обработки ошибок (~1 ч)

| Задача | Суть |
|--------|------|
| 4.1 | Создать `ApiErrorMapper` — маппинг `HttpException`, `IOException`, пустого тела |
| 4.2 | Унифицировать `NetworkViewModel` под `runCatching {}` + `Result<T>` (убрать 3 catch-блока) |
| 4.3 | Убрать дублирующие делегаты `getTokens()`/`saveTokens()`/`clearTokens()` из `AuthRepository` (оставить прямой доступ только через `TokenStorage`) |

---

### Этап 5 — Реактивный auth-state (~1-2 ч)

| Задача | Суть |
|--------|------|
| 5.1 | Добавить `Flow<Tokens?>` в `TokenStorage` (через `MutableStateFlow` или DataStore Flow) |
| 5.2 | Подписать `AuthViewModel` на `Flow<Tokens?>` вместо ручного `refreshTokensOverview()` |
| 5.3 | Убрать `savedAccessToken`/`savedRefreshToken` из `AuthUiState` (UI получает только `isLoggedIn`, `userEmail`) |
| 5.4 | Исправить двойной вызов `verifySession()` при старте |

---

### Этап 6 — Разделение экранов и навигация (~1 ч)

| Задача | Суть |
|--------|------|
| 6.1 | Разделить `AuthScreen` на `LoginScreen` и `ProfileScreen` с отдельными маршрутами |
| 6.2 | Перейти на `NavOptions` с `popUpTo` для навигации после логина |

---

## 6. Что сейчас лучше не трогать

| Компонент | Причина |
|-----------|---------|
| `AuthInterceptor` + `TokenAuthenticator` | Логика refresh работает корректно (double-check, lock, retry); менять только в рамках Этапа 3 |
| `EncryptedPrefsTokenStorage` | Реализация безопасна (AES256-GCM), трогать только при переходе на DataStore |
| `MediaFileDao` | После фикса Этапа 0 — стабильный контракт; расширять, не переписывать |
| `JwtUtils` | Вспомогательная утилита, работает корректно |
| `NetworkModule` (два клиента) | Правильное разделение `plain`/`auth`; расширять, не реструктурировать |
| `AppDatabase` Room schema | Не добавлять новые сущности до решения вопроса с `MediaFile.id` (Этап 0) |
