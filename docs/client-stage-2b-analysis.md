# Анализ перед Этапом 2B: вынос ручного Retrofit из AuthRepository

## Цель этапа

Этап 2B должен аккуратно убрать ручное создание Retrofit из `AuthRepository`, не меняя поведение auth-flow, refresh token flow и динамического `baseUrl`.

На этом этапе не стоит менять API-модель, suspend/Call-контракты, UI, token/session state или логику refresh. Главная безопасная цель - централизовать создание Retrofit-сервисов, которые сейчас создаются внутри `AuthRepository`, и сохранить принцип "создавать сервис под актуальный `BaseUrlProvider.baseUrl`".

## 1. Текущее состояние

### Оставшиеся точки `Retrofit.Builder()`

После Этапа 2A в коде осталось 5 точек ручного создания Retrofit:

| Файл | Место | Service | Client | Назначение |
|---|---:|---|---|---|
| `app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt` | `plainService()` | `AuthService` | `@Named("plain")` | `register`, `login` |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt` | `authService()` | `AuthService` | `@Named("auth")` | `logout` |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt` | `authTestService()` | `TestService` | `@Named("auth")` | `testAuth` |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt` | `refreshService(baseUrl)` | `AuthService` | `@Named("plain")` | refresh access token after 401 |
| `app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt` | `testConnection(baseUrl)` | `TestService` | `@Named("plain")` | server ping/test connection |

На Этапе 2B безопаснее убирать только первые 3 точки из `AuthRepository`.

### AuthRepository

`AuthRepository` сейчас имеет публичные методы:

- `isReady(): Boolean` - проверяет `baseUrlProvider.isReady`.
- `getTokens(): Tokens?` - читает токены из `TokenStorage`.
- `saveTokens(tokens: Tokens)` - сохраняет токены в `TokenStorage`.
- `clearTokens()` - очищает `TokenStorage`; сейчас публичный wrapper, но в найденном коде не используется.
- `register(email, password): Result<String>` - вызывает `AuthService.register`.
- `login(email, password): Result<Tokens>` - вызывает `AuthService.login`.
- `logout(): Result<Unit>` - читает refresh token, вызывает `AuthService.logout`, при успехе очищает storage.
- `testAuth(): Result<String>` - вызывает `TestService.testAuth`.

Внутри создаются три Retrofit-сервиса:

- `plainService(): AuthService`
  - `baseUrl(baseUrlProvider.baseUrl)`
  - `client(plainClient)`
  - используется в `register()` и `login()`.
- `authService(): AuthService`
  - `baseUrl(baseUrlProvider.baseUrl)`
  - `client(authClient)`
  - используется в `logout()`.
- `authTestService(): TestService`
  - `baseUrl(baseUrlProvider.baseUrl)`
  - `client(authClient)`
  - используется в `testAuth()`.

Endpoint-ы через plain client:

- `POST api/v1/auth/register`
- `POST api/v1/auth/login`

Endpoint-ы через auth client:

- `POST api/v1/auth/logout`
- `GET api/v1/test/auth`

Токены:

- `AuthRepository.getTokens()` читает `TokenStorage`.
- `AuthRepository.saveTokens()` сохраняет `TokenStorage`.
- `AuthRepository.clearTokens()` очищает `TokenStorage`.
- `AuthRepository.login()` сам токены не сохраняет, а только возвращает `Tokens`; сохранение делает `AuthViewModel.login()`.
- `AuthRepository.logout()` читает refresh token из `storage.getTokens()` и при successful response вызывает `storage.clear()`.
- `AuthRepository.testAuth()` токены явно не читает; access token добавляет `AuthInterceptor` через auth client.

Что можно вынести без изменения поведения:

- Только создание Retrofit-сервисов: `plainService()`, `authService()`, `authTestService()`.
- Выбор plain/auth client должен остаться тем же.
- Публичные методы `AuthRepository` и их `Result`/ошибки лучше оставить без изменений.
- Чтение, сохранение и очистку токенов лучше оставить в `AuthRepository`, `AuthViewModel`, `TokenAuthenticator` как сейчас.

### TokenAuthenticator

`TokenAuthenticator` срабатывает на 401 от auth client.

Текущий refresh flow:

1. Если `responseCount(response) >= 2`, возвращает `null`, чтобы остановить повторные попытки.
2. Входит в `synchronized(lock)`, чтобы refresh не выполнялся параллельно несколькими запросами.
3. Читает `baseUrlProvider.baseUrl`.
4. Если `baseUrl` пустой, пишет warning в лог и возвращает `null`.
5. Читает текущие токены из `TokenStorage`.
6. Повторно читает токены как `latest`; если access token уже изменился, повторяет исходный request с новым access token.
7. Создает `AuthService` через `refreshService(baseUrl)` на plain client.
8. Вызывает `POST api/v1/auth/refresh-token`.
9. Если refresh response неуспешный, очищает storage и возвращает `null`.
10. Если успешный, сохраняет новые `Tokens` и повторяет исходный request с новым `Authorization` header.

Почему используется plain client:

- Refresh endpoint не должен выполняться через auth client, иначе `AuthInterceptor` добавит старый access token, а `TokenAuthenticator` может сам среагировать на 401 refresh-запроса.
- Plain client не имеет `AuthInterceptor` и `TokenAuthenticator`, поэтому refresh-запрос не вызывает рекурсивный refresh-loop.

Где читает `BaseUrlProvider`:

- Внутри `authenticate()` перед refresh-запросом: `val baseUrl = baseUrlProvider.baseUrl`.
- Важно: `refreshService(baseUrl)` получает уже считанное значение, а не читает provider сам.

Риски централизации Retrofit:

- Если фабрика по умолчанию создаст refresh service на auth client, можно получить refresh-loop.
- Если фабрика начнет кешировать `AuthService` без учета текущего `baseUrl`, refresh может уйти на старый сервер.
- Если фабрика будет читать `baseUrlProvider.baseUrl`, нужно сохранить поведение при пустом baseUrl: сейчас authenticator явно возвращает `null`, а не падает на `Retrofit.Builder().baseUrl("")`.

Рекомендация: на Этапе 2B не трогать логику `TokenAuthenticator`. Максимум - заранее спроектировать factory так, чтобы она позже могла создать plain `AuthService` по явно переданному `baseUrl`, но фактический перенос authenticator лучше оставить на отдельный этап.

### AuthInterceptor

`AuthInterceptor` делает только одно:

- читает `TokenStorage.getTokens()`;
- если токенов нет, пропускает request без изменений;
- если токены есть, добавляет header `Authorization: Bearer <accessToken>`.

Риск добавления токена к `login/register/refresh` зависит только от выбранного OkHttpClient:

- Через plain client interceptor не установлен, поэтому `login/register/refresh` идут без access token.
- Через auth client interceptor установлен, поэтому любой endpoint через этот client получит access token.

Сейчас `login` и `register` безопасны, потому что используют plain client. Refresh безопасен, потому что `TokenAuthenticator` использует plain client. `logout` и `testAuth` используют auth client и получают access token автоматически.

На Этапе 2B `AuthInterceptor` лучше не менять. Если позже появятся публичные endpoint-ы на auth client, понадобится либо строгая маршрутизация client-ов, либо исключения по path внутри interceptor, но сейчас это лишний риск.

### Dynamic baseUrl

Сейчас `baseUrl` обновляется в `NetworkViewModel`:

- в `init`: после чтения сохраненных `ip` и `port` из `ServerPreferences` устанавливается `baseUrlProvider.baseUrl = "http://$ip:$port/"`;
- в `testConnection`: после успешного `ServerRepository.testConnection(baseUrl)` сохраняется IP/port и устанавливается `baseUrlProvider.baseUrl = baseUrl`.

`BaseUrlProvider` - mutable singleton с `var baseUrl: String = ""` и `isReady = baseUrl.isNotBlank()`.

Что произойдет при singleton Retrofit:

- Retrofit фиксирует `baseUrl` на момент `build()`.
- Если сделать singleton `Retrofit` или singleton `AuthService`, то после смены сервера сервис продолжит ходить на старый `baseUrl`.
- Это сломает динамическую настройку сервера, особенно после успешного testConnection на новый адрес.

Можно ли кешировать Retrofit-сервисы:

- Без учета `baseUrl` - нельзя.
- Можно кешировать по ключу `(baseUrl, clientType, serviceClass)`, но это усложняет код, требует invalidation и пока не нужно.
- Минимально безопасный вариант - создавать service на каждый вызов factory, как сейчас делает `AuthRepository`.

Нужен ли factory по текущему `baseUrl`:

- Да, если цель Этапа 2B - убрать ручное создание Retrofit из `AuthRepository`, не меняя dynamic baseUrl.
- Factory должен создавать service с актуальным `BaseUrlProvider.baseUrl` на момент вызова.
- Для будущего `TokenAuthenticator` желательно иметь overload/API, который создает service по явно переданному `baseUrl`, но использовать его в authenticator на Этапе 2B не обязательно.

Trade-offs:

| Подход | Плюсы | Минусы | Оценка для 2B |
|---|---|---|---|
| Singleton Retrofit | Простой DI, меньше объектов | Stale baseUrl после смены сервера | Не подходит |
| Singleton service | Удобно инжектировать в repository | Stale baseUrl, невозможно безопасно менять сервер | Не подходит |
| Factory без кеша | Поведение максимально близко к текущему, всегда актуальный baseUrl | Создает Retrofit/service на каждый вызов | Рекомендуется |
| Factory с кешем по baseUrl | Меньше аллокаций при частых вызовах | Больше состояния, invalidation, риск stale service | Не нужно на 2B |

## 2. Возможная целевая структура

Рекомендуемый класс: `RetrofitServiceFactory` или `ApiServiceFactory`.

Более точное имя для текущей задачи - `RetrofitServiceFactory`, потому что класс отвечает именно за создание Retrofit service-ов, а не за доменную API-логику.

Куда положить:

- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/RetrofitServiceFactory.kt`

Почему `core/network`:

- там уже находятся `AuthInterceptor` и `TokenAuthenticator`;
- factory будет общей сетевой инфраструктурой, а не частью auth-domain;
- later `ServerRepository` тоже сможет использовать ее для `TestService`, если это станет целью следующего этапа.

Зависимости:

- `BaseUrlProvider`;
- `@Named("plain") OkHttpClient`;
- `@Named("auth") OkHttpClient`;
- опционально `GsonConverterFactory` как private factory внутри класса или DI dependency.

Factory должен уметь создавать service через plain/auth client:

- plain нужен для `login`, `register`, later refresh и server test;
- auth нужен для `logout`, `testAuth` и будущих защищенных API.

Должен ли читать `BaseUrlProvider` сам:

- Для `AuthRepository` - да, чтобы убрать из repository знание о `Retrofit.Builder()` и сохранить текущую семантику "актуальный baseUrl на каждый вызов".
- Для `TokenAuthenticator` в будущем лучше иметь метод с явно переданным `baseUrl`, потому что authenticator уже обрабатывает пустой baseUrl до создания Retrofit.

Возможный API:

```kotlin
@Singleton
class RetrofitServiceFactory @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    @Named("plain") private val plainClient: OkHttpClient,
    @Named("auth") private val authClient: OkHttpClient
) {
    fun <T> createPlain(serviceClass: Class<T>): T
    fun <T> createAuth(serviceClass: Class<T>): T

    fun <T> createPlain(baseUrl: String, serviceClass: Class<T>): T
    fun <T> createAuth(baseUrl: String, serviceClass: Class<T>): T
}
```

На Этапе 2B можно использовать только `createPlain(serviceClass)` и `createAuth(serviceClass)` в `AuthRepository`. Методы с явным `baseUrl` можно либо сразу добавить для будущего безопасного переноса `TokenAuthenticator`, либо отложить, чтобы не расширять API заранее.

## 3. Рекомендуемый минимальный план Этапа 2B

### Что менять

1. Создать `RetrofitServiceFactory` в `core/network`.
   - Constructor injection через Hilt.
   - Получает `BaseUrlProvider`, `@Named("plain") OkHttpClient`, `@Named("auth") OkHttpClient`.
   - Создает Retrofit service на каждый вызов.
   - Не кеширует Retrofit и service.

2. Изменить `AuthRepository`.
   - Убрать зависимости на `@Named("plain") OkHttpClient` и `@Named("auth") OkHttpClient`.
   - Добавить зависимость на `RetrofitServiceFactory`.
   - Заменить private builder-методы на вызовы factory:
     - `plainService()` -> `serviceFactory.createPlain(AuthService::class.java)`;
     - `authService()` -> `serviceFactory.createAuth(AuthService::class.java)`;
     - `authTestService()` -> `serviceFactory.createAuth(TestService::class.java)`.
   - Оставить все публичные методы и обработку `Result` без изменений.

3. Оставить `BaseUrlProvider` как mutable runtime holder.
   - Не переводить на Flow/StateFlow.
   - Не делать persisted source of truth.

4. Оставить `ServerRepository` как есть.
   - Его `Retrofit.Builder()` можно перенести в factory позже.
   - Он принимает `baseUrl` параметром и используется для тестирования еще не сохраненного адреса, поэтому перенос требует отдельного решения API factory по explicit baseUrl.

5. Оставить `TokenAuthenticator` как есть.
   - Его перенос несет высокий риск refresh-loop и изменения поведения при пустом baseUrl.
   - Его стоит вынести отдельным этапом после проверки factory API.

### Что оставить как есть

- `AuthRepository.register/login/logout/testAuth` behavior.
- `AuthViewModel` и места сохранения токенов.
- `TokenAuthenticator.authenticate()` и `refreshService(baseUrl)`.
- `AuthInterceptor`.
- `NetworkModule` client graph.
- `ServerRepository.testConnection(baseUrl)`.
- `NetworkViewModel` и обновление `BaseUrlProvider`.

### Какие классы создать

- `core/network/RetrofitServiceFactory.kt`

### Какие классы изменить

- Только `auth/AuthRepository.kt`

### Какие классы не менять

- `core/network/TokenAuthenticator.kt`
- `core/network/AuthInterceptor.kt`
- `core/server/ServerRepository.kt`
- `ui/screens/auth/AuthViewModel.kt`
- `ui/screens/network/NetworkViewModel.kt`
- `di/NetworkModule.kt`, если factory получает уже существующие clients через constructor injection

## 4. Что НЕ трогать на Этапе 2B

- Java -> Kotlin миграцию `AuthService`, request/response models или других API.
- Переход Retrofit `Call<T>.execute()` на suspend API.
- Логику `TokenAuthenticator`, если цель этапа - минимальный риск.
- Логику `AuthInterceptor`.
- UI и Compose screens.
- `AuthViewModel` session/auth state.
- `TokenStorage` и encrypted preferences.
- Media/file/sync слои.
- `ServerRepository` и server test flow.
- `BaseUrlProvider` как архитектурную модель; его можно использовать, но не переделывать.

## 5. Риски

### Refresh-loop

Главный риск - случайно отправить refresh-запрос через auth client. Тогда refresh request получит старый access token и authenticator, что может привести к рекурсивной авторизации или неправильной очистке storage.

Митигация: `TokenAuthenticator` на 2B не трогать; если переносить позже, refresh service должен создаваться только через plain client.

### Login/register/refresh через auth client

Если `AuthRepository.register()` или `login()` начнут использовать auth client, `AuthInterceptor` добавит access token при наличии старой сессии. Это изменит поведение публичных endpoint-ов.

Митигация: в factory явно разделить `createPlain` и `createAuth`; в `AuthRepository` сохранить текущий mapping endpoint -> client.

### Stale baseUrl

Singleton Retrofit/service зафиксирует старый `baseUrl` и сломает смену сервера.

Митигация: factory без кеша, создание service на каждый вызов, чтение `BaseUrlProvider.baseUrl` непосредственно перед build.

### Пустой baseUrl до настройки сервера

Сейчас `AuthViewModel` вызывает `repo.isReady()` перед `register/login/logout/testAuth`. Но `AuthRepository` private service methods сами не валидируют пустой baseUrl. Если вызвать repository напрямую при пустом baseUrl, Retrofit упадет при `.baseUrl("")`.

Митигация на 2B: не менять поведение. Можно в отчете/плане зафиксировать, что factory либо сохраняет текущее поведение, либо добавляет явную ошибку только после отдельного решения. Для минимального изменения лучше не вводить новую семантику ошибок.

### Потеря обработки ошибок

`AuthRepository` сейчас возвращает тексты из `errorBody()?.string()` для `register/login`, `"Logout failed"` для logout и `getHttpStatusDescription(resp.code())` для `testAuth`.

Митигация: переносить только создание service, не менять bodies/catches/result mapping.

### Изменение поведения logout/testAuth

`logout` сейчас:

- читает refresh token из storage;
- вызывает endpoint через auth client;
- при успехе чистит storage;
- при ошибке не чистит storage.

`testAuth` сейчас:

- вызывает `TestService.testAuth("")` через auth client;
- передает пустой header-параметр, но `AuthInterceptor` добавляет настоящий `Authorization`.

Митигация: оставить вызовы и client mapping без изменений.

## 6. Уточнения перед реализацией

1. Нужен ли factory?

Да. Для Этапа 2B это самый безопасный способ убрать `Retrofit.Builder()` из `AuthRepository`, не вводя singleton Retrofit и не ломая dynamic baseUrl.

2. Должен ли factory читать `BaseUrlProvider`?

Для `AuthRepository` - да. Это сохраняет текущее поведение: каждый repository-call строит service на актуальном runtime `baseUrl`.

Для будущего `TokenAuthenticator` и `ServerRepository` полезен вариант с explicit `baseUrl`, потому что:

- `TokenAuthenticator` сам проверяет пустой `baseUrl` до refresh;
- `ServerRepository.testConnection(baseUrl)` тестирует адрес, который еще не сохранен в `BaseUrlProvider`.

3. Стоит ли на этом этапе трогать `TokenAuthenticator`?

Рекомендация: нет. Он связан с 401 retry, синхронизацией refresh, очисткой токенов и защитой от loops. Его лучше оставить до отдельного этапа, где будет отдельная проверка refresh-flow.

4. Как обрабатывать пустой `baseUrl`?

Для минимального Этапа 2B лучше не менять поведение. Сейчас верхний слой проверяет `repo.isReady()`, а factory может вести себя так же, как текущий `Retrofit.Builder().baseUrl(baseUrlProvider.baseUrl)`.

Если хочется улучшить диагностику, можно отдельно обсудить явную проверку в factory:

- `check(baseUrl.isNotBlank()) { "Base URL is not configured" }`

Но это уже меняет тип/текст ошибки для прямых вызовов repository и не является обязательным для 2B.

## Итоговая рекомендация

Минимальный Этап 2B:

- создать `RetrofitServiceFactory` без кеша;
- перевести только `AuthRepository` на factory;
- сохранить mapping:
  - `register/login` -> plain `AuthService`;
  - `logout` -> auth `AuthService`;
  - `testAuth` -> auth `TestService`;
- не трогать `TokenAuthenticator`, `AuthInterceptor`, `ServerRepository`, UI и token/session state.

Это убирает ручное создание Retrofit из `AuthRepository` и почти не меняет runtime-поведение: service по-прежнему создается на каждый вызов с текущим `baseUrl`.
