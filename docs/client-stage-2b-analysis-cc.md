# Анализ перед Этапом 2B рефакторинга — PhotoCloudClient Android

**Дата:** 2026-05-26  
**После Этапа 2A:** `NetworkViewModel` чист от Retrofit/Context/OkHttpClient;
`ServerPreferences` и `ServerRepository` созданы; `AppPreferences` удалён.

---

## 1. Текущее состояние

### 1.1 Оставшиеся точки `Retrofit.Builder()`

После Этапа 2A в проекте остаются 4 точки ручного создания Retrofit:

| # | Файл | Строка | Метод | Client |
|---|---|---|---|---|
| 1 | `auth/AuthRepository.kt` | 33 | `plainService()` | `@Named("plain")` |
| 2 | `auth/AuthRepository.kt` | 40 | `authService()` | `@Named("auth")` |
| 3 | `auth/AuthRepository.kt` | 47 | `authTestService()` | `@Named("auth")` |
| 4 | `core/network/TokenAuthenticator.kt` | 27 | `refreshService(baseUrl)` | `@Named("plain")` |

`ServerRepository.testConnection()` — тоже создаёт Retrofit, но это уже после 2A и это его штатное место (вынесено из ViewModel).

---

### 1.2 AuthRepository — полная карта

**Конструктор (строки 21–26):**
```kotlin
@Singleton
class AuthRepository @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @Named("auth") private val authClient: OkHttpClient
)
```

**Приватные фабрики сервисов (строки 33–52):**

| Метод | Client | Возвращает | Строки |
|---|---|---|---|
| `plainService()` | `plainClient` | `AuthService` | 33–38 |
| `authService()` | `authClient` | `AuthService` | 40–45 |
| `authTestService()` | `authClient` | `TestService` | 47–52 |

Все три — синхронные, `baseUrl` читается в момент вызова из `baseUrlProvider.baseUrl`.

**Публичные методы и используемые сервисы:**

| Метод | Сервис | Endpoint | Client | Токен нужен? |
|---|---|---|---|---|
| `register(email, password)` | `plainService()` | POST `/api/v1/auth/register` | plain | нет |
| `login(email, password)` | `plainService()` | POST `/api/v1/auth/login` | plain | нет |
| `logout()` | `authService()` | POST `/api/v1/auth/logout` | auth | да (в теле LogoutRequest — refreshToken) |
| `testAuth()` | `authTestService()` | GET `/api/v1/test/auth` | auth | да (AuthInterceptor добавит автоматически) |

**Операции с токенами:**
- `getTokens()` → `storage.getTokens()` — читает (строка 29)
- `saveTokens(tokens)` → `storage.saveTokens(tokens)` — пишет (строка 30); вызывается из `AuthViewModel.login()`
- `clearTokens()` → `storage.clear()` — очищает (строка 31); вызывается из `AuthViewModel` при необходимости
- Внутри `logout()` — `storage.clear()` напрямую (строка 76) при успешном logout

**`isReady()`** — единственный читатель `baseUrlProvider.isReady` в `AuthRepository` (строка 27).

---

### 1.3 TokenAuthenticator — механика refresh

**Файл:** `core/network/TokenAuthenticator.kt`

**Зависимости:**
```kotlin
class TokenAuthenticator @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val okHttpClient: OkHttpClient
)
```

**Поток выполнения `authenticate()` (строки 34–68):**

1. Проверяет `responseCount >= 2` → возвращает `null` (защита от бесконечного цикла).
2. Захватывает `synchronized(lock)`.
3. Читает `baseUrlProvider.baseUrl` — если пустой, логирует и возвращает `null`.
4. Получает `current = storage.getTokens()` — если `null`, возвращает `null`.
5. Получает `latest = storage.getTokens()` повторно — если `latest.accessToken != current.accessToken`, другой поток уже обновил токены; возвращает request с `latest.accessToken` без refresh-вызова.
6. Создаёт Retrofit через `refreshService(baseUrl)` с plain client.
7. Вызывает `AuthService.refreshToken(RefreshTokenRequest(current.refreshToken)).execute()`.
8. При неуспехе: `storage.clear()`, возвращает `null`.
9. При успехе: сохраняет новые токены, возвращает request с новым access token.

**Почему plain client?**  
Refresh endpoint **не должен** проходить через `AuthInterceptor` (который добавил бы старый/невалидный access token в заголовок) и **не должен** проходить через `TokenAuthenticator` (иначе возникает бесконечный цикл: 401 → refresh → 401 → refresh...). Plain client содержит только `HttpLoggingInterceptor` — безопасно.

**Чтение `BaseUrlProvider`:**  
Строка 38 — `val baseUrl = baseUrlProvider.baseUrl` — читается внутри `authenticate()` при каждом вызове. Это гарантирует актуальный URL на момент refresh, даже если он изменился с момента создания `TokenAuthenticator`.

---

### 1.4 AuthInterceptor — логика добавления токена

**Файл:** `core/network/AuthInterceptor.kt`

```kotlin
class AuthInterceptor @Inject constructor(private val storage: TokenStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val tokens = storage.getTokens()
        if (tokens == null) return chain.proceed(original)
        val authed = original.newBuilder()
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .build()
        return chain.proceed(authed)
    }
}
```

**Ключевые свойства:**
- Добавляет токен ко **всем** запросам через `authClient`, если токены есть.
- Если токенов нет — пропускает запрос без заголовка (строки 12–13).
- **Не знает** о конкретных endpoint-ах — это соответствует разделению на два клиента: plainClient (register, login, refresh) и authClient (logout, testAuth).

**Риск double-header в `testAuth()`:**  
В `AuthRepository.testAuth()` вызывается `authTestService().testAuth("").execute()`. В `TestService.java` метод объявлен как:
```java
Call<TestResponse> testAuth(@Header("Authorization") String token);
```
Это добавляет пустой `Authorization: Bearer ` header через аннотацию. Одновременно `AuthInterceptor` добавляет корректный `Authorization: Bearer <token>` header. OkHttp при дублировании header-ов отправляет оба. Это работает только потому, что сервер, скорее всего, берёт первый или один из них. **Это существующий дефект**, не относящийся к Этапу 2B — трогать не нужно.

---

## 2. Динамический baseUrl — проблема и варианты

### Почему нельзя сделать Retrofit @Singleton напрямую

Все `@Singleton` в Hilt создаются при первом использовании, но граф строится до того, как пользователь настроил сервер. В момент создания `@Singleton Retrofit`:
- `baseUrlProvider.baseUrl == ""` (пустая строка)
- `Retrofit.Builder().baseUrl("")` выбрасывает `IllegalArgumentException`

Даже если обойти это через заглушку `"http://localhost/"`:
- `baseUrl` будет заморожен на момент создания
- Последующее изменение `BaseUrlProvider.baseUrl` не повлияет на уже созданный Retrofit

### Варианты

**Вариант A: Factory (per-call service creation)** — рекомендуется для Этапа 2B

- `@Singleton`-класс, который читает `baseUrlProvider.baseUrl` и создаёт сервис при каждом вызове метода.
- Семантика идентична текущему коду в `AuthRepository` — только вынесена в один класс.
- `AuthRepository` заменяет 3 Retrofit.Builder()-вызова на 3 вызова фабрики.
- **Pros:** минимальные изменения, нулевой риск, точное поведение.
- **Cons:** Retrofit пересоздаётся на каждый вызов (но это текущее поведение — не регрессия).

**Вариант B: Retrofit.newBuilder() / клонирование**

- Построить базовый Retrofit без URL, при каждом вызове клонировать с нужным baseUrl.
- Retrofit не поддерживает удобное клонирование baseUrl после построения — нестандартно.
- **Не рекомендуется** для Этапа 2B.

**Вариант C: Interceptor-based URL rewriting**

- Retrofit строится с заглушкой, interceptor переписывает URL динамически.
- Сложно, добавляет магию, плохо читается.
- **Не рекомендуется** для любого этапа без крайней необходимости.

**Вариант D: Lazy holder / Retrofit factory с кешированием**

- Кешировать `AuthService` после первого создания, инвалидировать при изменении baseUrl.
- Требует подписки на изменение baseUrl — неоправданная сложность.
- **Не рекомендуется** для Этапа 2B.

---

## 3. Рекомендуемый минимальный план Этапа 2B

### Что создать: `core/network/ApiServiceFactory.kt`

`@Singleton`-класс, инкапсулирующий создание Retrofit-сервисов.

**Зависимости:**
```kotlin
@Singleton
class ApiServiceFactory @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @Named("auth") private val authClient: OkHttpClient
)
```

**Методы (читают `baseUrlProvider.baseUrl` в момент вызова):**

| Метод фабрики | Client | Возвращает | Заменяет |
|---|---|---|---|
| `plainAuthService()` | plainClient | `AuthService` | `AuthRepository.plainService()` |
| `authAuthService()` | authClient | `AuthService` | `AuthRepository.authService()` |
| `authTestService()` | authClient | `TestService` | `AuthRepository.authTestService()` |

**Важно:** каждый метод собирает Retrofit с текущим `baseUrlProvider.baseUrl` — поведение идентично текущему.

**Должен ли factory читать BaseUrlProvider сам?** Да — это главная цель: убрать зависимость `AuthRepository` на `BaseUrlProvider` для целей создания Retrofit. Для `isReady()` `AuthRepository` по-прежнему может держать `BaseUrlProvider` или делегировать через фабрику. Вариант ниже сохраняет `BaseUrlProvider` в `AuthRepository` для `isReady()` — минимальные изменения.

---

### Что изменить: `auth/AuthRepository.kt`

**Конструктор — убрать `OkHttpClient`×2, добавить `ApiServiceFactory`:**

```kotlin
// Было:
class AuthRepository @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @Named("auth") private val authClient: OkHttpClient
)

// Станет:
class AuthRepository @Inject constructor(
    private val storage: TokenStorage,
    private val baseUrlProvider: BaseUrlProvider,   // остаётся для isReady()
    private val serviceFactory: ApiServiceFactory
)
```

**Методы — заменить вызовы приватных фабрик:**

```kotlin
// Было:
val resp = plainService().register(...).execute()
// Станет:
val resp = serviceFactory.plainAuthService().register(...).execute()
```

Логика `register`, `login`, `logout`, `testAuth` — не меняется.  
Удалить приватные методы `plainService()`, `authService()`, `authTestService()`.  
Удалить импорты `Retrofit`, `GsonConverterFactory`, `@Named`, `OkHttpClient`.

---

### Что оставить без изменений

| Компонент | Причина |
|---|---|
| `TokenAuthenticator` | Имеет специфичный plain client + `synchronized` + защиту от loop; создаёт Retrofit внутри `authenticate()` потокобезопасно. Риск рефакторинга высок — оставить до Этапа 3. |
| `NetworkModule` | Не требует изменений; `ApiServiceFactory` инжектируется автоматически через `@Inject constructor`. |
| `StorageModule` | Не затронут. |
| `ServerRepository` | Уже чистый после 2A. |

---

## 4. Риски

### R1: Отправка refresh/login/register через auth client
**Вероятность:** Низкая при соблюдении разделения в фабрике.  
**Сценарий:** Если в `ApiServiceFactory.plainAuthService()` случайно передать `authClient` вместо `plainClient` — login/register получат `AuthInterceptor` с устаревшим/невалидным токеном; в случае refresh — возникнет loop.  
**Митигация:** В фабрике чётко именовать методы и клиенты; code review при реализации.

### R2: Пустой baseUrl
**Вероятность:** Возможна, если auth-операции вызываются до настройки сервера.  
**Текущая защита:** `isReady()` в `AuthViewModel.notReady()` — проверяет перед каждой операцией.  
**Дополнительная защита:** В методах фабрики можно добавить проверку `require(baseUrlProvider.baseUrl.isNotBlank())` с понятным сообщением — не обязательно для 2B, но полезно.  
**Риск при 2B:** Не меняется — поведение идентично текущему.

### R3: Stale baseUrl при кешировании сервисов
**Вероятность:** Не актуальна при per-call создании сервисов (Вариант A).  
**Риск при 2B:** Отсутствует при правильной реализации фабрики.

### R4: Refresh-loop через TokenAuthenticator
**Вероятность:** Нулевая, если `TokenAuthenticator` не трогать.  
`TokenAuthenticator` уже использует plain client напрямую — не зависит от `ApiServiceFactory`.  
**Риск при 2B:** Отсутствует.

### R5: Потеря обработки ошибок
**Вероятность:** Низкая — `runCatching` в методах `AuthRepository` остаётся.  
**Риск:** Если при перестановке кода случайно убрать `runCatching` — исключение прорвётся в ViewModel.  
**Митигация:** Проверить после рефакторинга, что все методы AuthRepository по-прежнему обёрнуты в `withContext(Dispatchers.IO) { runCatching { ... } }`.

### R6: Изменение поведения `testAuth()`
Текущая реализация: `authTestService().testAuth("").execute()` — двойной Authorization header (см. выше).  
**Риск при 2B:** Нулевой — поведение не меняется, просто `authTestService()` заменяется на `serviceFactory.authTestService()`.

---

## 5. Что НЕ трогать на Этапе 2B

| Область | Файлы |
|---|---|
| Java API interfaces | `api/AuthService.java`, `api/TestService.java` |
| Suspend API | Не переводить `Call<T>` в suspend |
| TokenAuthenticator | `core/network/TokenAuthenticator.kt` — Этап 3 |
| AuthInterceptor | `core/network/AuthInterceptor.kt` — работает корректно |
| NetworkModule | `di/NetworkModule.kt` — OkHttpClient-конфигурация не меняется |
| UI composables | Все файлы в `ui/` |
| Auth/session flow | `AuthViewModel.kt`, `AuthUiState.kt` |
| ServerRepository | `core/server/ServerRepository.kt` — уже чистый |
| Media/file/sync | `media/` |

---

## 6. Уточнения перед реализацией

### Q1: Должен ли `ApiServiceFactory` сам вернуть `isReady()`?
Сейчас `AuthRepository.isReady()` читает `baseUrlProvider.isReady` напрямую.  
**Вариант:** оставить `BaseUrlProvider` в `AuthRepository` только для `isReady()`.  
**Вариант:** убрать `BaseUrlProvider` из `AuthRepository` и добавить метод `isReady()` в фабрику.  
**Рекомендация:** оставить `BaseUrlProvider` в `AuthRepository` на Этапе 2B — минимальное изменение. Убрать в Этапе 3.  
**Нужно подтверждение.**

### Q2: Нужна ли проверка пустого baseUrl внутри методов фабрики?
Текущие методы фабрики `AuthRepository.plainService()` и другие не проверяют пустоту URL — они молча передают пустую строку в `Retrofit.Builder().baseUrl("")`, что вызывает `IllegalArgumentException`.  
**Вариант A:** добавить `check(baseUrlProvider.baseUrl.isNotBlank()) { "Server URL is not configured" }` в каждом методе фабрики.  
**Вариант B:** не добавлять — полагаться на `isReady()` guard в ViewModel.  
**Рекомендация:** Вариант A — явный fail fast лучше неясного `IllegalArgumentException`.  
**Нужно подтверждение.**

### Q3: Куда положить `ApiServiceFactory`?
**Вариант A:** `core/network/ApiServiceFactory.kt` — рядом с `AuthInterceptor` и `TokenAuthenticator`.  
**Вариант B:** `di/ApiServiceFactory.kt` — рядом с `BaseUrlProvider` и DI-модулями.  
**Рекомендация:** `core/network/` — это часть сетевой инфраструктуры, не DI-конфигурации.  
**Нужно подтверждение.**

### Q4: Трогать ли `TokenAuthenticator` на Этапе 2B?
**Рекомендация: нет.** Причины:
- `refreshService()` в `TokenAuthenticator` создаётся внутри `synchronized(lock)` — это намеренно
- Замена на фабрику потребует, чтобы фабрика тоже была потокобезопасна или выносилась за lock
- Текущий код явно читает `baseUrl` из `BaseUrlProvider` непосредственно перед refresh — это гарантия актуальности
- Риск refresh-loop при неправильной замене — слишком высок для этого этапа

Оставить `TokenAuthenticator.refreshService()` как есть; убрать в Этапе 3 вместе с централизацией Retrofit-инстансов.

---

## Приложение: карта после Этапа 2B

```
AuthRepository (@Singleton) — после 2B
├── @Inject TokenStorage
├── @Inject BaseUrlProvider    (только для isReady())
└── @Inject ApiServiceFactory

ApiServiceFactory (@Singleton) — новый класс
├── @Inject BaseUrlProvider
├── @Inject OkHttpClient("plain")
├── @Inject OkHttpClient("auth")
├── fun plainAuthService(): AuthService   (per-call Retrofit)
├── fun authAuthService(): AuthService    (per-call Retrofit)
└── fun authTestService(): TestService    (per-call Retrofit)

TokenAuthenticator — НЕ трогаем
├── @Inject TokenStorage
├── @Inject BaseUrlProvider
├── @Inject OkHttpClient("plain")
└── refreshService(baseUrl): AuthService  (per-call, inline Retrofit)
```

**Оставшиеся Retrofit.Builder() после 2B:**
- `core/network/TokenAuthenticator.kt` — 1 точка (Этап 3)
- `core/server/ServerRepository.kt` — 1 точка (штатное место, Этап 3 опционально)
