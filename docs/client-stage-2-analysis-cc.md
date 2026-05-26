# Анализ перед Этапом 2 рефакторинга — PhotoCloudClient Android

**Дата:** 2026-05-26  
**Ветка:** master  
**После этапов 0 и 1:** MediaFile PK исправлен, UPLOADED→SYNCED, permissions добавлены,
часть классов перенесена в core/, проект собирается.

---

## 1. Текущее состояние

### 1.1 AppPreferences — usages

**Файл:** `app/.../utils/AppPreferences.kt`  
Kotlin `object` (синглтон). Использует DataStore через extension property `Context.dataStore`.

**Хранит только:**

| DataStore ключ | Тип | Что хранит |
|---|---|---|
| `IP_KEY` | `String?` | IP-адрес сервера |
| `PORT_KEY` | `String?` | Порт сервера |

baseUrl нигде **не хранится** — собирается динамически: `"http://$ip:$port/"`.

**Публичный API, от которого зависят вызывающие:**

```kotlin
fun getIp(context: Context): Flow<String?>
fun getPort(context: Context): Flow<String?>
suspend fun saveConnection(context: Context, ip: String, port: String)
```

**Места использования** — только один файл:

| Файл | Строки | Операция |
|---|---|---|
| `ui/screens/network/NetworkViewModel.kt` | 37, 44-45, 87 | read (init), write (on success) |

Детально:
- Строка 37: `private val preferences = AppPreferences` — прямая ссылка на object.
- Строки 44–45 (init): `preferences.getIp(context).first()` / `preferences.getPort(context).first()` — блокирующий read из DataStore.
- Строка 87 (testConnection, success path): `preferences.saveConnection(context, ip, port)` — запись после успешного пинга.

**Вывод:** AppPreferences используется ровно в одном месте — в `NetworkViewModel`. Замена на DI-класс `ServerPreferences` затрагивает только этот файл.

---

### 1.2 BaseUrlProvider — usages

**Файл:** `app/.../di/BaseUrlProvider.kt`  
`@Singleton class`, constructor injection, простой in-memory holder.

```kotlin
@Singleton
class BaseUrlProvider @Inject constructor() {
    var baseUrl: String = ""
    val isReady: Boolean get() = baseUrl.isNotBlank()
}
```

**Кто пишет (2 точки):**

| Файл | Строка | Операция |
|---|---|---|
| `NetworkViewModel.kt` | 47 | init: `baseUrlProvider.baseUrl = "http://$ip:$port/"` |
| `NetworkViewModel.kt` | 88 | testConnection success: `baseUrlProvider.baseUrl = baseUrl` |

**Кто читает (3 точки):**

| Файл | Строки | Операция |
|---|---|---|
| `auth/AuthRepository.kt` | 34, 41, 48 | `.baseUrl(baseUrlProvider.baseUrl)` в трёх Retrofit.Builder() |
| `core/network/TokenAuthenticator.kt` | 38 | `val baseUrl = baseUrlProvider.baseUrl` перед refreshService() |

**Является ли единственным runtime source of truth?**  
Да. Всё, кроме NetworkViewModel (который сам его и заполняет), только читает.  
Персистентность — отсутствует (теряется при перезапуске); восстанавливается из DataStore при init NetworkViewModel.

**Можно ли оставить BaseUrlProvider как-есть на Этапе 2A?**  
Да. Его роль корректна — in-memory holder. Единственная проблема — он мутабелен публично (`var baseUrl`). Это риск, но не блокер для Этапа 2A.

---

### 1.3 Retrofit — точки создания

Итого **5 вызовов** `Retrofit.Builder()`:

| Файл | Строка | Контекст | Клиент |
|---|---|---|---|
| `NetworkViewModel.kt` | 72 | метод `testConnection()`, внутри ViewModel | `plainClient` (injected) |
| `AuthRepository.kt` | 33 | метод `plainService()` | `plainClient` |
| `AuthRepository.kt` | 40 | метод `authService()` | `authClient` |
| `AuthRepository.kt` | 47 | метод `authTestService()` | `authClient` |
| `TokenAuthenticator.kt` | 27 | метод `refreshService()` | `plainClient` |

**OkHttpClient.Builder()** — только в `NetworkModule.kt` (строки 28 и 43), что правильно.

**addInterceptor()** — только в `NetworkModule.kt` (logging — строки 32, 47; AuthInterceptor — строка 48).

---

### 1.4 Связанные DI-модули

| Модуль | Файл | Что предоставляет |
|---|---|---|
| `AppModule` | `di/AppModule.kt` | `AppDatabase` @Singleton, `MediaFileDao` @Singleton |
| `NetworkModule` | `di/NetworkModule.kt` | `HttpLoggingInterceptor`, `OkHttpClient("plain")`, `OkHttpClient("auth")` |
| `StorageModule` | `di/StorageModule.kt` | `TokenStorage` → `EncryptedPrefsTokenStorage` (@Binds) |
| *(нет)* | — | `RepositoryModule` отсутствует |

**@HiltViewModel:** `AuthViewModel`, `NetworkViewModel`.

**@Singleton через @Inject constructor (без @Provides):** `BaseUrlProvider`, `AuthRepository`, `TokenAuthenticator`.

---

## 2. Предлагаемый минимальный план Этапа 2A

Цель: стабилизировать DI/settings/Retrofit-creation **без изменения auth-логики и UI**.

### Шаг 2A-1: Заменить `object AppPreferences` на DI-класс `ServerPreferences`

**Что изменить:**

1. Создать `core/storage/ServerPreferences.kt` — `@Singleton class @Inject constructor(@ApplicationContext context: Context)`.  
   Перенести туда `dataStore`, `getIp()`, `getPort()`, `saveConnection()` с теми же сигнатурами, но без `context: Context` в параметрах (context хранится как поле).
2. Удалить `utils/AppPreferences.kt`.
3. В `NetworkViewModel`: убрать поле `preferences = AppPreferences`, получить `ServerPreferences` через constructor injection.
4. Адаптировать вызовы: `preferences.getIp()` вместо `preferences.getIp(context)` и т.д.

**Что не меняется:** DataStore name (`"settings"`), ключи `IP_KEY`/`PORT_KEY`, логика чтения/записи.

**Риск:** низкий. Единственный пользователь — `NetworkViewModel`. Логика идентична, только убирается `context` из сигнатур.

---

### Шаг 2A-2: Вынести `testConnection` из `NetworkViewModel` в `ServerRepository`

**Что изменить:**

1. Создать `core/server/ServerRepository.kt` — `@Singleton class @Inject constructor(...)`.  
   Перенести туда:
   - Создание `Retrofit` / `TestService` для пинга (сейчас строки 72–77 NetworkViewModel).
   - Вызов `api.testServer()` (строка 83).
   - Возврат результата как `Result<Unit>` или sealed class.
2. В `NetworkViewModel`:  
   - Инжектировать `ServerRepository`.
   - Заменить inline-Retrofit-код вызовом `serverRepository.testConnection(ip, port)`.
   - Оставить: обработку UI state (loading, success/error message), вызов `preferences.saveConnection()`, обновление `baseUrlProvider.baseUrl`.

**Что не меняется:** UI state flow, `NetworkUiState`, `NetworkScreen`, `isValidIpAddress` валидация.

**Риск:** низкий при сохранении той же логики ответов. Нужно убедиться, что все три ветки ответа (success, HTTP error, IOException) покрыты в `ServerRepository`.

---

### Шаг 2A-3: Очистить `NetworkViewModel` от прямых зависимостей на DataStore и Retrofit

После шагов 2A-1 и 2A-2 `NetworkViewModel` должен:
- **Инжектировать:** `BaseUrlProvider`, `ServerPreferences`, `ServerRepository`.
- **НЕ инжектировать:** `OkHttpClient`, `@ApplicationContext Context`.
- **НЕ создавать:** Retrofit напрямую.
- **НЕ обращаться:** к DataStore напрямую.

Конструктор до:
```kotlin
class NetworkViewModel @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @ApplicationContext private val context: Context
)
```

Конструктор после:
```kotlin
class NetworkViewModel @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    private val serverPreferences: ServerPreferences,
    private val serverRepository: ServerRepository
)
```

---

### Итого: что создаётся, что удаляется, что меняется

| Действие | Файл |
|---|---|
| Создать | `core/storage/ServerPreferences.kt` |
| Создать | `core/server/ServerRepository.kt` |
| Удалить | `utils/AppPreferences.kt` |
| Изменить | `ui/screens/network/NetworkViewModel.kt` (constructor, init, testConnection) |

---

## 3. Что НЕ трогать на Этапе 2A

| Область | Файлы | Причина |
|---|---|---|
| Java API interfaces | `api/AuthService.java`, `api/TestService.java` | Изменение сигнатур сломает всех пользователей |
| AuthRepository | `auth/AuthRepository.kt` | Содержит Retrofit, но вся auth-логика должна остаться нетронутой |
| TokenAuthenticator | `core/network/TokenAuthenticator.kt` | Сложная логика refresh; риск token loop при изменении |
| AuthInterceptor | `core/network/AuthInterceptor.kt` | Работает корректно, не создаёт Retrofit |
| Auth/session flow | `auth/AuthUiState.kt`, `AuthViewModel.kt`, `AuthScreen.kt` | Вне области Этапа 2 |
| UI screens | Все файлы в `ui/screens/` кроме `NetworkViewModel.kt` | Вне области Этапа 2 |
| File/media/sync логика | `media/` | Вне области Этапа 2 |
| Centralize Retrofit для auth | `AuthRepository.kt`, строки 33–52 | Оставить до Этапа 3 |
| Retrofit в TokenAuthenticator | `TokenAuthenticator.kt`, строка 27 | Оставить до Этапа 3 (требует осторожности с circular deps) |
| `BaseUrlProvider` API | `di/BaseUrlProvider.kt` | Оставить мутабельным пока; изменение задетит AuthRepository и TokenAuthenticator |

---

## 4. Вопросы и уточнения перед реализацией

### Q1: `ServerPreferences` — в какой пакет?
Логика хранения настроек сервера — это не совсем то же самое, что хранение токенов.  
**Варианты:**
- `core/storage/ServerPreferences.kt` — рядом с `TokenStorage`
- `core/server/ServerPreferences.kt` — отдельный пакет для серверных настроек

Если будет отдельный пакет `core/server/`, туда же логично положить `ServerRepository`.  
**Нужно подтверждение.**

### Q2: `ServerRepository.testConnection()` — какой вернуть тип?
Сейчас `testConnection()` в ViewModel смешивает `Result`-like обработку с UI state.  
**Варианты для Repository:**
- `suspend fun testConnection(ip: String, port: String): Result<String>` — простой
- `suspend fun testConnection(ip: String, port: String): TestConnectionResult` — sealed class с деталями ошибки

Sealed class чище, но добавляет файл. Result проще на этом этапе.  
**Нужно подтверждение.**

### Q3: Кто обновляет `BaseUrlProvider.baseUrl` после успешного testConnection?
**Варианты:**
- ViewModel (текущее поведение: строки 88) — проще, ViewModel контролирует состояние
- ServerRepository (как часть testConnection) — Repository знает url, логично

Если переносить в Repository — Repository начинает писать в `BaseUrlProvider`, т.е. косвенно влияет на AuthRepository и TokenAuthenticator. Это может быть скользко.  
**Рекомендация:** оставить обновление `baseUrlProvider.baseUrl` в ViewModel на Этапе 2A, вынести в Этапе 3 вместе с централизацией Retrofit.

### Q4: `saveConnection()` — кто вызывает после успешного пинга?
Аналогично Q3 — сейчас это строка 87 NetworkViewModel.  
**Рекомендация:** оставить в ViewModel (он уже будет инжектировать `ServerPreferences` напрямую). ServerRepository только возвращает результат.

### Q5: `@Named("plain") OkHttpClient` в `ServerRepository` — как передавать?
ServerRepository нужен OkHttpClient для создания Retrofit при тесте соединения.  
**Варианты:**
- Инжектировать `@Named("plain") OkHttpClient` в `ServerRepository` — корректно, DI делает это сам
- Принимать как параметр в метод — неправильно для DI

**Рекомендация:** инжектировать через constructor. Это дублирует ситуацию с NetworkViewModel, но это правильный паттерн.

### Q6: Нужен ли `RepositoryModule`?
Сейчас `AuthRepository` и (после 2A) `ServerRepository` работают через `@Singleton @Inject constructor()` — без явного `@Provides`. Это работает.  
Явный `RepositoryModule` нужен, если:
- Требуется `@Binds` (interface → impl)
- Нужна нетривиальная конструкция

Для текущего состояния — **не нужен**. Можно добавить в Этапе 3 при централизации Retrofit-сервисов.

---

## Приложение: карта зависимостей (текущее состояние)

```
NetworkViewModel
├── @Inject BaseUrlProvider        (читает/пишет baseUrl)
├── @Inject OkHttpClient("plain")  (передаёт в inline Retrofit.Builder)
├── @ApplicationContext Context    (передаёт в AppPreferences)
├── AppPreferences (object)        (read IP/port при init, write при success)
└── создаёт Retrofit.Builder()     (строка 72)

AuthRepository (@Singleton)
├── @Inject BaseUrlProvider        (читает baseUrl)
├── @Inject OkHttpClient("plain")
├── @Inject OkHttpClient("auth")
├── @Inject TokenStorage
└── создаёт Retrofit.Builder() x3  (строки 33, 40, 47)

TokenAuthenticator (@Inject)
├── @Inject BaseUrlProvider        (читает baseUrl)
├── @Inject OkHttpClient("plain")
├── @Inject TokenStorage
└── создаёт Retrofit.Builder() x1  (строка 27)

NetworkModule
├── provides OkHttpClient("plain") ← logging interceptor
└── provides OkHttpClient("auth")  ← logging + AuthInterceptor + TokenAuthenticator
```

```
После Этапа 2A:

NetworkViewModel
├── @Inject BaseUrlProvider        (пишет baseUrl на success)
├── @Inject ServerPreferences      (read при init, write при success)
└── @Inject ServerRepository       (делегирует testConnection)

ServerRepository (@Singleton)
├── @Inject OkHttpClient("plain")
└── создаёт Retrofit.Builder()     (строка из NetworkViewModel, перенесена)

ServerPreferences (@Singleton)
└── @ApplicationContext Context    (DataStore)
```
