# Анализ перед Этапом 2C / подготовкой к Этапу 3 — сетевой слой

**Дата:** 2026-05-26  
**После Этапа 2B + hotfix:** `ApiServiceFactory` создан, `AuthRepository` использует фабрику,
`TestService.testAuth()` без ручного Authorization header. Clean build успешный.

---

## 1. Текущее состояние `ApiServiceFactory`

**Файл:** `core/network/ApiServiceFactory.kt`

**Методы и их характеристики:**

| Метод | Client | Service | Читает baseUrl | Явный baseUrl | Кеш |
|---|---|---|---|---|---|
| `plainAuthService()` | `plainClient` | `AuthService` | из `BaseUrlProvider` | нет | нет |
| `authAuthService()` | `authClient` | `AuthService` | из `BaseUrlProvider` | нет | нет |
| `authTestService()` | `authClient` | `TestService` | из `BaseUrlProvider` | нет | нет |

**Ключевые свойства:**
- Каждый вызов создаёт новый Retrofit + новый service — кеш отсутствует намеренно.
- `check(baseUrl.isNotBlank())` — явный fail-fast при незаполненном URL.
- `baseUrl` читается в момент вызова — всегда актуальный.
- Все три метода читают `BaseUrlProvider`; explicit-baseUrl overload отсутствует.

**Код внутри методов дублируется:** каждый метод содержит одинаковый `Retrofit.Builder()` блок — отличаются только `client` и `serviceClass`. Это не дефект, но при расширении стоит вынести в приватный helper.

**Можно ли расширить factory без изменения поведения?**  
Да. Добавление новых перегрузок (с явным baseUrl или для новых service-классов) не затрагивает существующие методы. Factory — `@Singleton`, зависимости неизменны.

---

## 2. `TokenAuthenticator` — детальный анализ

**Файл:** `core/network/TokenAuthenticator.kt`

### Как создаёт Retrofit

```kotlin
private fun refreshService(baseUrl: String): AuthService = Retrofit.Builder()
    .baseUrl(baseUrl)
    .addConverterFactory(GsonConverterFactory.create())
    .client(okHttpClient)   // ← @Named("plain")
    .build()
    .create(AuthService::class.java)
```

Вызывается из `authenticate()` (строка 53): `refreshService(baseUrl).refreshToken(...)`.  
`baseUrl` — параметр, прочитанный из `BaseUrlProvider` чуть выше (строка 38), ещё до вызова.

### Почему plain client — архитектурно обязательно

- **`AuthInterceptor`** добавил бы к refresh-запросу старый/истёкший access token — запрос провалится с 401 ещё на входе.
- **`TokenAuthenticator`** сам является `authenticator()` на `authClient` — подписка на 401 через authClient создала бы бесконечную цепочку:  
  `401 → authenticate() → refresh через authClient → 401 → authenticate() → ...`

Plain client = только `HttpLoggingInterceptor` — никаких auth-слоёв.

### Защита от refresh-loop — три механизма

1. **Строка 35:** `if (responseCount(response) >= 2) return null` — если этот конкретный запрос уже один раз получил 401 и прошёл через authenticate, во второй раз прекращаем попытку.
2. **Строка 37:** `synchronized(lock)` — только один поток исполняет тело authenticate одновременно; одновременные запросы выстраиваются в очередь.
3. **Строки 44–51 (double-read pattern):** читает токены дважды — `current`, затем `latest`. Если `latest.accessToken != current.accessToken` — другой поток уже обновил токены, подставляем новый и не делаем повторный refresh.

### Где читает `BaseUrlProvider`

Строка 38: `val baseUrl = baseUrlProvider.baseUrl` — внутри `synchronized(lock)`, ПОСЛЕ проверки `responseCount`, в момент фактической попытки refresh. Это гарантирует, что Retrofit создаётся с актуальным URL на момент 401-ответа.

### Критический вывод: почему `ApiServiceFactory` нельзя инжектировать в `TokenAuthenticator`

Граф зависимостей Hilt при попытке добавить `ApiServiceFactory` в `TokenAuthenticator`:

```
TokenAuthenticator
    └── ApiServiceFactory
            └── OkHttpClient("auth")
                    └── TokenAuthenticator   ← CIRCULAR DEPENDENCY
```

`ApiServiceFactory` инжектирует `@Named("auth") OkHttpClient`, а `OkHttpClient("auth")` строится с `tokenAuthenticator` как authenticator. Hilt обнаружит циклическую зависимость на этапе компиляции и прервёт сборку.

**Вывод: `TokenAuthenticator` нельзя трогать на Этапе 2C без архитектурного разрыва этого цикла.**

Единственный безопасный вариант разрыва — выделить `PlainApiServiceFactory`, которая получает только `plainClient` и не создаёт цикл. Но это требует разделения фабрики, что является отдельным архитектурным решением, выходящим за рамки текущего этапа.

---

## 3. `ServerRepository` — детальный анализ

**Файл:** `core/server/ServerRepository.kt`

### Как создаёт Retrofit

```kotlin
suspend fun testConnection(baseUrl: String): Result<String> {
    val api = Retrofit.Builder()
        .baseUrl(baseUrl)        // ← explicit параметр, НЕ BaseUrlProvider
        .addConverterFactory(GsonConverterFactory.create())
        .client(plainClient)
        .build()
        .create(TestService::class.java)
    ...
}
```

### Почему принимает `baseUrl` параметром — ключевой семантический факт

`ServerRepository.testConnection()` предназначен для проверки **нового, ещё не сохранённого** адреса. Поток в `NetworkViewModel.testConnection()`:

```
1. Пользователь ввёл IP и port
2. ViewModel строит baseUrl = "http://$ip:$port/"
3. serverRepository.testConnection(baseUrl)  ← проверяем НОВЫЙ адрес
4. Только при успехе:
   - serverPreferences.saveConnection(ip, port)
   - baseUrlProvider.baseUrl = baseUrl
```

Если `ServerRepository` читал бы из `BaseUrlProvider` — тестировался бы **старый** сохранённый адрес, а не тот, который ввёл пользователь. Это сломало бы весь flow настройки сервера.

### Можно ли заменить Retrofit на factory?

**Да, безопасно** — при условии добавления в `ApiServiceFactory` метода с явным `baseUrl`:

```kotlin
fun plainTestService(baseUrl: String): TestService
```

- Нет циклической зависимости: `ServerRepository` → `ApiServiceFactory` → `OkHttpClient("plain")` — цепочка не замыкается.
- `OkHttpClient` уйдёт из конструктора `ServerRepository`.
- Семантика сохраняется: явный `baseUrl` передаётся в factory, factory использует `plainClient`.

---

## 4. Два сценария dynamic baseUrl

Ключевое архитектурное различие:

| Сценарий | Кто использует | Источник URL | Семантика |
|---|---|---|---|
| **A: BaseUrlProvider** | `ApiServiceFactory` (3 текущих метода) | `baseUrlProvider.baseUrl` | «Текущий сохранённый сервер» |
| **B: Explicit baseUrl** | `ServerRepository.testConnection()`, `TokenAuthenticator.refreshService()` | Параметр метода | «Этот конкретный URL прямо сейчас» |

**Singleton Retrofit — почему нельзя:**
- `BaseUrlProvider.baseUrl` при старте приложения пустой; `@Singleton` Retrofit строится при первом запросе — `Retrofit.Builder().baseUrl("")` выбросит `IllegalArgumentException`.
- Даже с lazy-инициализацией: baseUrl фиксируется в момент первого создания; смена сервера не обновит singleton.

**Factory без кеша — почему это правильно:**
- Retrofit-объект лёгкий; создание нового на каждый вызов не является узким местом.
- Гарантирует актуальный baseUrl на каждый запрос.
- Нет проблемы stale URL.

**Нужны ли explicit-baseUrl overloads?**
- Для `ServerRepository`: **да** — `plainTestService(baseUrl: String): TestService`.
- Для `TokenAuthenticator`: нет — нельзя инжектировать factory (см. раздел 2).

---

## 5. Сравнение вариантов factory API

### Вариант I: Named methods (текущий + расширение)

```kotlin
// Читают BaseUrlProvider:
fun plainAuthService(): AuthService
fun authAuthService(): AuthService
fun authTestService(): TestService

// Explicit baseUrl:
fun plainTestService(baseUrl: String): TestService
// при необходимости:
fun plainAuthService(baseUrl: String): AuthService
```

| Критерий | Оценка |
|---|---|
| Читаемость | Высокая — название метода полностью описывает назначение |
| Риск неправильного client | Низкий — client зашит в имя метода |
| Безопасность для refresh | Высокая — plain-методы явно отделены от auth |
| Java/Kotlin interop | Отличный — обычные методы без reified |
| Будущие media/file API | Умеренно: нужно добавлять новый метод на каждый service-класс |

### Вариант II: Generic factory

```kotlin
inline fun <reified T> createPlain(baseUrl: String): T
inline fun <reified T> createAuth(baseUrl: String): T
inline fun <reified T> createPlain(): T   // читает BaseUrlProvider
inline fun <reified T> createAuth(): T
```

| Критерий | Оценка |
|---|---|
| Читаемость | Умеренная — `createPlain<AuthService>()` vs `plainAuthService()` |
| Риск неправильного client | Средний — легко перепутать `createPlain` / `createAuth` |
| Безопасность для refresh | Средняя — различие не отражено в типе, только в имени метода |
| Java/Kotlin interop | Ограниченный — `reified` не работает из Java; нужен отдельный не-inlined вариант |
| Будущие media/file API | Высокая — не нужен новый метод для каждого service |

### Вариант III: Hybrid (рекомендуется для 2C)

Приватный generic builder убирает дублирование; публичные named методы сохраняют читаемость:

```kotlin
private fun build(baseUrl: String, client: OkHttpClient): Retrofit =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

// Существующие — без изменений по сигнатуре:
fun plainAuthService(): AuthService =
    build(baseUrlProvider.baseUrl.also { check(it.isNotBlank()) { "..." } }, plainClient)
        .create(AuthService::class.java)

// Новый для ServerRepository:
fun plainTestService(baseUrl: String): TestService =
    build(baseUrl, plainClient).create(TestService::class.java)
```

Это единственный вариант, где `Retrofit.Builder()` находится в одном месте — в приватном `build()`.

---

## 6. Оценка рисков

| Риск | Источник | Вероятность | Тяжесть | Митигация |
|---|---|---|---|---|
| **Circular dependency Hilt** | Инжект `ApiServiceFactory` в `TokenAuthenticator` | Гарантированно | Блокер сборки | Не трогать `TokenAuthenticator` |
| **Refresh-loop** | Случайное использование `authClient` для refresh | Нет при сохранении plain-методов | Критическая | Named methods делают это невозможным |
| **Stale baseUrl** | Кеширование service / singleton Retrofit | Нет при per-call создании | Высокая | Текущий per-call подход — норма |
| **Пустой baseUrl** | `ServerRepository` не проверяет URL | Низкая — валидация в ViewModel | Crash | Добавить `require(baseUrl.isNotBlank())` в новый метод factory |
| **Wrong URL при testConnection** | Factory читает `BaseUrlProvider` вместо явного `baseUrl` | Высокая если ошибиться | Функциональная регрессия | Explicit-baseUrl overload в factory |
| **Ошибки сообщений** | Перемещение catch-блоков из `ServerRepository` в factory | Нет — catch остаётся в Repository | — | — |
| **Преждевременная абстракция** | Generic factory для будущих media API | Средняя | Читаемость/сложность | Отложить generic API до появления реальной нужды |

---

## 7. Рекомендация: Вариант B (частичный)

### Формулировка

**Сейчас:** расширить `ApiServiceFactory` одним методом, перенести `ServerRepository` на factory. `TokenAuthenticator` не трогать.

**Не сейчас:** generic factory, разделение на PlainFactory/AuthFactory, перенос `TokenAuthenticator`.

### Обоснование выбора вариантов

**Вариант A (ничего не трогать) — НЕ выбран:**  
`ServerRepository` — простой, безопасный случай. Один метод в factory и удаление `OkHttpClient` из конструктора `ServerRepository` — чистая и безрисковая операция. Откладывать без причины нет смысла.

**Вариант B (только ServerRepository) — ВЫБРАН:**  
`ApiServiceFactory` получает один новый приватный helper и один новый публичный метод. `ServerRepository` теряет прямую зависимость на `OkHttpClient`. Результат: все создания Retrofit, кроме `TokenAuthenticator`, сосредоточены в `ApiServiceFactory`.

**Вариант C (и TokenAuthenticator) — НЕ выбран:**  
Не реализуем из-за circular dependency. Для его реализации потребовалось бы:
- разделить `ApiServiceFactory` на `PlainApiServiceFactory` и `AuthApiServiceFactory`;
- перестроить DI-граф;
- или использовать `Lazy<T>` / `Provider<T>` от Dagger для разрыва цикла.

Это отдельный архитектурный шаг, выходящий за рамки текущего этапа.

---

## 8. Конкретные изменения для Этапа 2C (Вариант B)

### Изменить: `core/network/ApiServiceFactory.kt`

Добавить приватный builder-helper (убирает дублирование внутри factory):
```kotlin
private fun build(baseUrl: String, client: OkHttpClient): Retrofit =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
```

Добавить один новый публичный метод:
```kotlin
fun plainTestService(baseUrl: String): TestService {
    require(baseUrl.isNotBlank()) { "Server URL is not configured" }
    return build(baseUrl, plainClient).create(TestService::class.java)
}
```

Опционально: рефакторинг существующих методов под `build()` helper (устраняет дублирование, не меняет поведение).

### Изменить: `core/server/ServerRepository.kt`

Конструктор: убрать `@Named("plain") OkHttpClient`, добавить `ApiServiceFactory`.  
В методе `testConnection(baseUrl)`: заменить `Retrofit.Builder()...` на `apiServiceFactory.plainTestService(baseUrl)`.

### Не трогать

| Файл | Причина |
|---|---|
| `TokenAuthenticator.kt` | Circular dependency в Hilt-графе — невозможно безопасно |
| `NetworkModule.kt` | Не требует изменений |
| `AuthRepository.kt` | Уже использует factory |
| Все UI файлы | Вне области |
| Java API interfaces | Без изменений |
| Auth/session/media flow | Вне области |

---

## 9. Уточнения перед реализацией

### Q1: Рефакторить ли существующие методы factory под приватный `build()`?
Существующие три метода содержат одинаковый `Retrofit.Builder()` блок. Вынос в `build()` — внутренний рефакторинг без изменения публичного API.  
**Рекомендация:** да, сделать одновременно с добавлением `plainTestService(baseUrl)` — минимальный overhead, устраняет будущее дублирование.  
**Нужно подтверждение.**

### Q2: Нужен ли `require()` или `check()` в `plainTestService(baseUrl: String)`?
В `ServerRepository.testConnection()` URL уже валидируется в `NetworkViewModel` (проверка IP + port). Дополнительная проверка в factory — defensive coding.  
**Рекомендация:** `require(baseUrl.isNotBlank())` — семантически `require` для внешних параметров правильнее `check` (который для внутренних инвариантов). В существующих методах factory стоит `check` — оставить как есть для консистентности.  
**Нужно подтверждение.**

### Q3: `TokenAuthenticator` — оставить навсегда или Этап 3?
Для устранения circular dependency нужно одно из:
- A: Вынести `PlainApiServiceFactory` как отдельный `@Singleton`-класс с зависимостью только на `plainClient`
- B: Использовать `Lazy<ApiServiceFactory>` в `TokenAuthenticator` (разрывает цикл через lazy-инициализацию)
- C: Оставить `refreshService()` как есть навсегда — это единственный Retrofit.Builder() вне factory, но он обоснован архитектурно

Вариант C самый простой и безопасный.  
**Нужно подтверждение: допустимо ли оставить `TokenAuthenticator.refreshService()` как постоянный исключение из правила?**

---

## Приложение: карта Retrofit.Builder() до и после Этапа 2C

**До:**

| Файл | Точек | Статус |
|---|---|---|
| `core/network/ApiServiceFactory.kt` | 3 | Штатное место |
| `core/network/TokenAuthenticator.kt` | 1 | Не трогаем (circular dep) |
| `core/server/ServerRepository.kt` | 1 | Убрать на 2C |

**После Варианта B:**

| Файл | Точек | Статус |
|---|---|---|
| `core/network/ApiServiceFactory.kt` | 1 (в `build()`) | Единственная точка создания |
| `core/network/TokenAuthenticator.kt` | 1 | Обоснованное исключение |
| `core/server/ServerRepository.kt` | 0 | Чисто |
