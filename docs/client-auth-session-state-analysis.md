# Анализ auth/session state — PhotoCloudClient Android

**Дата:** 2026-05-26  
**После Этапов 2A–2C + hotfix:** `ApiServiceFactory`, `ServerRepository`, `AuthRepository` — чистые.
`TokenAuthenticator` — обоснованное исключение.

---

## 1. TokenStorage — текущее состояние

### Интерфейс

```kotlin
interface TokenStorage {
    fun getTokens(): Tokens?
    fun saveTokens(tokens: Tokens)
    fun clear()

    // Default implementations:
    fun getAccess(): String?
    fun getRefresh(): String?
    fun saveAccess(access: String)
}
```

**Полностью синхронный, pull-based API. Никаких Flow / StateFlow / callback нет.**

### Реализация: EncryptedPrefsTokenStorage

- `EncryptedSharedPreferences` с `AES256_GCM` / `AES256_SIV`.
- Prefs-файл: `"secure_tokens"`. Ключи: `"accessToken"`, `"refreshToken"`.
- `getTokens()`: оба ключа должны быть не-null, иначе возвращает `null`.
- `saveTokens()`, `clear()` — через `prefs.edit().apply()` (асинхронная запись на диск, но вызов возвращается немедленно).

### Кто читает / пишет / очищает

| Операция | Кто вызывает | Контекст |
|---|---|---|
| **read** `getTokens()` | `AuthRepository.getTokens()` | из `AuthViewModel.refreshTokensOverview()`, `verifySession()` |
| **read** `getTokens()` | `AuthRepository.logout()` | для получения refreshToken перед logout-запросом |
| **read** `getTokens()` | `TokenAuthenticator.authenticate()` | дважды (double-read pattern) |
| **read** `getTokens()` | `AuthInterceptor.intercept()` | на каждый HTTP-запрос через authClient |
| **write** `saveTokens()` | `TokenAuthenticator.authenticate()` | после успешного refresh |
| **write** `saveTokens()` | `AuthRepository.saveTokens()` | из `AuthViewModel.login()` |
| **clear** `clear()` | `TokenAuthenticator.authenticate()` | при неуспешном refresh ← **без уведомления UI** |
| **clear** `clear()` | `AuthRepository.logout()` | при успешном logout |
| **clear** `clear()` | `AuthRepository.clearTokens()` | **не вызывается нигде** — мёртвый код |

**Критический факт:** `TokenAuthenticator` вызывает `storage.clear()` из OkHttp-потока тихо. Никакой реактивной нотификации нет. `AuthViewModel` узнаёт об этом только при следующем ручном вызове `refreshTokensOverview()`.

---

## 2. AuthViewModel — детальный разбор

### init

```kotlin
init {
    viewModelScope.launch {
        refreshTokensOverview()   // синхронное чтение storage → обновляет UI-state
        verifySession()            // если есть токены → сетевой testAuth → обновляет UI-state
    }
}
```

### Двойной вызов verifySession

`verifySession()` вызывается **дважды** при каждом переходе на `AuthScreen`:

1. В `init` (строка 28) — при создании ViewModel.
2. В `AuthScreen` через `LaunchedEffect(Unit) { viewModel.verifySession() }` (строка 27–29 AuthScreen).

Оба запроса запускаются параллельно. Это означает два `testAuth()` сетевых вызова почти одновременно. Не критический баг (оба read-only), но бессмысленная нагрузка.

### verifySession

```kotlin
fun verifySession() {
    if (!repo.isReady()) { refreshTokensOverview(); return }
    if (repo.getTokens() == null) { _uiState.update { it.copy(isLoggedIn = false) }; return }
    viewModelScope.launch {
        runCatching { repo.testAuth() }   // результат testAuth ОТБРОШЕН
        refreshTokensOverview()
    }
}
```

`runCatching { repo.testAuth() }` — результат (success/failure) никак не используется. `refreshTokensOverview()` вызывается всегда. Это приводит к следующему сценарию:

- access token истёк, refresh тоже истёк;
- `testAuth()` получает 401 → `TokenAuthenticator` пробует refresh → refresh fails → `storage.clear()`;
- `testAuth()` возвращает ошибку → `runCatching` её поглощает;
- `refreshTokensOverview()` вызывается → `storage.getTokens() == null` → `isLoggedIn = false` ✓

Т.е. **в этом конкретном сценарии работает корректно**, потому что `refreshTokensOverview()` замечает очистку *после* завершения `testAuth()`. Проблема возникает вне `verifySession()` — в фоновых запросах.

### isLoggedIn

```kotlin
isLoggedIn = tokens != null   // в refreshTokensOverview()
```

Производится snapshot-чтением `storage.getTokens()`. Корректен в момент вызова, но не реагирует на изменения storage между вызовами `refreshTokensOverview()`.

### login

1. `repo.login()` → `Result<Tokens>`.
2. На success: `repo.saveTokens(tokens)` → `storage.saveTokens()`.
3. `refreshTokensOverview()` → `isLoggedIn = true`.

Корректно. Токены сохраняются, UI обновляется.

### logout

```kotlin
fun logout() {
    if (notReady()) return           // если сервер не настроен — ERROR, ничего не делает
    ...
    repo.logout()
        .onSuccess { refreshTokensOverview(); updateStatus(SUCCESS) }
        .onFailure { updateStatus(ERROR, ...) }  // токены НЕ очищаются
}
```

**Проблема:** если сервер недоступен — `logout()` завершается с ошибкой, токены остаются в storage, пользователь остаётся «залогиненным» и не может выйти. Нет кнопки «Force Logout».

### testAuth в ViewModel — двойной refreshTokensOverview

```kotlin
fun testAuth() {
    viewModelScope.launch {
        repo.testAuth()
            .onSuccess { refreshTokensOverview(); ... }  // ← раз
            .onFailure { ... }
        refreshTokensOverview()                           // ← два (всегда)
    }
}
```

На success: `refreshTokensOverview()` вызывается дважды. Безвредно, но избыточно.

---

## 3. AuthUiState — анализ полей

```kotlin
data class AuthUiState(
    val email: String = "",              // ← UI форма
    val password: String = "",           // ← UI форма
    val status: ConnectionStatus = ...,  // ← операционный статус
    val message: String? = null,         // ← операционное сообщение

    val savedAccessToken: String? = null,   // ← raw JWT ← SECURITY / DEBUG
    val savedRefreshToken: String? = null,  // ← raw JWT ← SECURITY / DEBUG
    val isAccessValid: Boolean = false,     // ← DEBUG (derived от savedAccessToken)
    val isRefreshValid: Boolean = false,    // ← DEBUG (derived от savedRefreshToken)

    val isLoggedIn: Boolean = false,     // ← SESSION STATE
    val userEmail: String? = null        // ← UI display (derived от access token)
)
```

### Классификация полей

| Поле | Тип | Нужно в UI? | Риск |
|---|---|---|---|
| `email` | Форма | Да | — |
| `password` | Форма | Да | — |
| `status` | Операционный | Да | — |
| `message` | Операционный | Да | — |
| `savedAccessToken` | **Security/Debug** | Нет | Утечка токена через state dump, лог |
| `savedRefreshToken` | **Security/Debug** | Нет | То же |
| `isAccessValid` | **Debug** (derived) | Нет | Избыточно, устаревает |
| `isRefreshValid` | **Debug** (derived) | Нет | То же |
| `isLoggedIn` | Session | Да | Может устаревать |
| `userEmail` | UI display | Да | — |

`savedAccessToken` и `savedRefreshToken` — сырые JWT-строки в `MutableStateFlow`. Хранятся в памяти, не персистируются напрямую через state, но:
- Любой подписчик на `uiState` получает полные токены.
- Compose snapshot system сохраняет состояние между рекомпозициями.
- Риск случайного логирования (crashlytics, debug dumps).
- Нет никакого UI-кейса, для которого нужен полный JWT-строки.

`isAccessValid` / `isRefreshValid` — computed из `JwtUtils.isExpired(access.toString())`. Обратите внимание: если `access == null`, то `null.toString() == "null"` → `isExpired("null")` → вернёт `true` (blank check провалится). Это работает корректно, но неочевидно.

---

## 4. AuthRepository — структурный анализ

### Текущие методы

| Метод | Делегирует | Кто вызывает |
|---|---|---|
| `isReady()` | `baseUrlProvider.isReady` | `AuthViewModel.notReady()`, `verifySession()` |
| `getTokens()` | `storage.getTokens()` | `AuthViewModel.refreshTokensOverview()`, `verifySession()` |
| `saveTokens()` | `storage.saveTokens()` | `AuthViewModel.login()` |
| `clearTokens()` | `storage.clear()` | **Нигде** — мёртвый код |
| `register()` | network | `AuthViewModel.register()` |
| `login()` | network | `AuthViewModel.login()` |
| `logout()` | network + `storage.clear()` | `AuthViewModel.logout()` |
| `testAuth()` | network | `AuthViewModel.testAuth()`, `verifySession()` |

**Наблюдение:** `AuthRepository.clearTokens()` существует, но не вызывается. Очистка токенов происходит: (a) внутри `logout()` при успехе, (b) в `TokenAuthenticator` напрямую через свой `storage`.

**Дублирование владения:** `AuthRepository` владеет и сетевыми вызовами, и lifecycle токенов. Для текущего масштаба это приемлемо. Разделение на `AuthRepository` (только network) + `SessionManager` (токены + session state) — правильная цель, но не для следующего шага.

---

## 5. TokenAuthenticator — silent logout проблема

### Когда очищает токены

Строки 54–56:
```kotlin
val refreshResp = refreshService(baseUrl).refreshToken(...).execute()
if (!refreshResp.isSuccessful) {
    storage.clear()    // ← тихая очистка
    return null
}
```

### Цепочка событий при истечении refresh token

```
1. Любой аутентифицированный запрос (logout, testAuth, медиа-операция)
2. Сервер возвращает 401
3. OkHttp вызывает TokenAuthenticator.authenticate() синхронно на IO-потоке
4. refreshToken() → refresh тоже истёк → 401/403
5. storage.clear() — ТИХО
6. return null → OkHttp прерывает исходный запрос
7. Исходный вызов (logout, testAuth) получает ошибку (IOException или пустой ответ)
8. AuthViewModel получает onFailure → показывает сообщение об ошибке
9. isLoggedIn по-прежнему == true в UI state
```

### Что видит пользователь

- Появляется диалог с ошибкой (например, «Logout error» или «Ошибка подключения»).
- Экран остаётся в режиме «Профиль» (`isLoggedIn = true`).
- Кнопки Logout / Test Auth присутствуют.
- При следующей попытке — та же картина: 401, нет токенов, storage уже пустой.
- **Выйти невозможно**: `logout()` требует serverReady + tokens, но `notReady()` не триггерится; `logout()` запустится, `storage.getTokens()?.refreshToken` вернёт `null` → `throw Exception("No tokens")` → `onFailure` → «Logout error».
- **Разлогиниться можно только через `verifySession()`** (автоматически), если он будет вызван снова.

---

## 6. UI — токены на экране

### ProfileContent отображает

```kotlin
TokenRow(label = "Access token", token = state.savedAccessToken, valid = state.isAccessValid)
TokenRow(label = "Refresh token", token = state.savedRefreshToken, valid = state.isRefreshValid)
```

`shortenToken()` показывает первые 16 + последние 12 символов JWT. Этого достаточно для идентификации/отладки, но:
- Пользователю это ничего не говорит.
- JWT-подпись частично видна — не критично, но лишнее.
- В UI production-приложения токены не должны быть видны вообще.

### Вывод по UI

- Секция «Токены» — явный debug/development артефакт. Должна быть скрыта или перенесена в debug-only раздел.
- `ProfileContent` в остальном корректен: email, кнопки Logout / Test Auth.
- `LoginContent` корректен: поля email/password, Register/Login/Test Auth.
- Переключение `isLoggedIn` → показ Login/Profile работает правильно при условии актуальности `isLoggedIn`.

---

## 7. Нужна ли отдельная Session State модель?

### Текущие неявные состояния

| Состояние | Как выражено сейчас | Проблема |
|---|---|---|
| Server not configured | `isReady() = false` | Нет отдельного UI-состояния |
| Anonymous (no tokens) | `isLoggedIn = false` | — |
| Authenticated | `isLoggedIn = true` | Может быть stale |
| Expired / InvalidSession | Не существует явно | Выглядит как «загрузка» или «ошибка» |
| Offline | Не различается | Слита с auth-error |
| Loading / verifySession in progress | Частично — `status = LOADING` | Нет индикатора пока verifySession работает |

### Вывод

Для следующего шага полная sealed-class `SessionState` (Unknown / Loading / Anonymous / Authenticated / Expired / Offline) — преждевременно. Это правильная цель для Этапа 3.

**Сейчас достаточно:** реактивный `Flow<Tokens?>` в `TokenStorage` + удаление raw токенов из `AuthUiState`. Это решает самую острую проблему без введения новых архитектурных слоёв.

---

## 8. Риски

| Риск | Тяжесть | Вероятность | Статус |
|---|---|---|---|
| **Stale `isLoggedIn` после `storage.clear()` из TokenAuthenticator** | Высокая | Высокая | Не решён — ключевая проблема |
| **Raw JWT-токены в `AuthUiState`** | Средняя | — | Всегда присутствует |
| **Рассинхронизация UI-state и TokenStorage** | Высокая | Высокая | Следствие pull-based storage |
| **Двойной `verifySession()`** | Низкая | 100% | Потеря производительности, не баг |
| **Logout при недоступном сервере** | Средняя | Реальная | Пользователь не может выйти |
| **Истёкший access, живой refresh** | Низкая | — | `TokenAuthenticator` обрабатывает автоматически |
| **Refresh failed** | Высокая | Реальная | Silent logout — не нотифицирует UI |
| **Двойной `refreshTokensOverview()` в `testAuth()`** | Низкая | 100% | Бессмысленный, не баг |
| **`clearTokens()` мёртвый код** | Низкая | — | Запутывает, не баг |

---

## 9. Рекомендация: Вариант B

### Почему не A

Вариант A (убрать токены из UI-state, починить `verifySession` вручную) решает видимые симптомы, но не устраняет корневую причину: `TokenAuthenticator.storage.clear()` остаётся необнаруживаемым для `AuthViewModel`. После удаления токенов из state `isLoggedIn` по-прежнему будет устаревать. Проблема с silent logout сохраняется.

### Почему не C

Вариант C (SessionManager) — правильная долгосрочная архитектура, но:
- Требует создания нового класса с нетривиальным API.
- Требует миграции `AuthViewModel`, `AuthRepository`, DI-графа.
- Один SessionManager не решает проблему TokenAuthenticator — нужен ещё один способ уведомления.
- Слишком большой шаг без промежуточной стабилизации.

### Вариант B — почему

- Один источник изменения: добавить `Flow<Tokens?>` или `StateFlow<Tokens?>` в `TokenStorage`.
- `EncryptedPrefsTokenStorage` обновляет flow при каждом `saveTokens()` и `clear()`.
- `TokenAuthenticator.clear()` автоматически тригерит обновление — без изменений в самом `TokenAuthenticator`.
- `AuthViewModel` подписывается на flow → `isLoggedIn` становится реактивным → UI обновляется при silent logout.
- Минимальные изменения, нет новых классов.

---

## 10. Минимальный план Этапа 3A (Вариант B)

### Создать / изменить

**1. `core/storage/TokenStorage.kt`** — добавить свойство:
```kotlin
val tokensFlow: Flow<Tokens?>
```

**2. `core/storage/EncryptedPrefsTokenStorage.kt`** — реализовать через `MutableStateFlow`:
```kotlin
private val _tokensFlow = MutableStateFlow<Tokens?>(getTokens())  // начальное значение из prefs
override val tokensFlow: StateFlow<Tokens?> = _tokensFlow

override fun saveTokens(tokens: Tokens) {
    prefs.edit()...apply()
    _tokensFlow.value = tokens
}
override fun clear() {
    prefs.edit().clear().apply()
    _tokensFlow.value = null
}
```
Это не требует `OnSharedPreferenceChangeListener`, потому что единственные места изменения — сам `EncryptedPrefsTokenStorage`.

**3. `auth/AuthRepository.kt`** — делегировать flow:
```kotlin
val tokensFlow: Flow<Tokens?> get() = storage.tokensFlow
```

**4. `ui/screens/auth/AuthViewModel.kt`** — подписаться на flow:
```kotlin
init {
    viewModelScope.launch {
        repo.tokensFlow.collect { tokens ->
            _uiState.update { it.copy(
                isLoggedIn = tokens != null,
                userEmail = tokens?.accessToken?.let { JwtUtils.getSubject(it) }
            )}
        }
    }
    // убрать двойной verifySession — оставить только один
    viewModelScope.launch { verifySession() }
}
```

**5. `auth/AuthUiState.kt`** — удалить поля:
- `savedAccessToken`
- `savedRefreshToken`
- `isAccessValid`
- `isRefreshValid`

**6. `ui/screens/auth/AuthScreen.kt`** — удалить `TokenRow` и секцию «Токены» из `ProfileContent`.

**7. `ui/screens/auth/AuthScreen.kt`** — убрать `LaunchedEffect(Unit) { viewModel.verifySession() }` (так как `init` уже вызывает).

### Что НЕ трогать

| Файл | Причина |
|---|---|
| `TokenAuthenticator` | Работает корректно, теперь автоматически уведомляет через `_tokensFlow` |
| `AuthInterceptor` | Не затронут |
| `NetworkModule` | Не затронут |
| `ApiServiceFactory` | Не затронут |
| `AuthRepository` (кроме добавления `tokensFlow`) | Все сетевые методы без изменений |
| `ServerRepository`, `ServerPreferences` | Не затронуты |
| Все остальные экраны UI | Не затронуты |
| Java API interfaces | Не затронуты |

---

## 11. Вопросы/уточнения перед реализацией

### Q1: Что показывать, пока `verifySession()` работает?

Сейчас нет индикатора загрузки во время `verifySession()`. После введения реактивного `tokensFlow` состояние `isLoggedIn` будет корректным сразу, но сетевая проверка всё ещё происходит в фоне.  
**Вариант:** добавить `isVerifying: Boolean` в `AuthUiState`, показывать `LoadingDialog` во время verifySession.  
**Рекомендация:** добавить — это одна строка в state и один update в ViewModel.  
**Нужно подтверждение.**

### Q2: Что делать с logout при недоступном сервере?

Сейчас: нельзя выйти, если сервер не отвечает. После Варианта B — токены будут очищены в UI при любой очистке storage, но сделать это намеренно пользователь всё ещё не сможет.  
**Вариант:** добавить `forceLogout()` в AuthViewModel — просто вызывает `repo.clearTokens()` без сетевого вызова.  
**Рекомендация:** добавить, но это отдельная задача после 3A.  
**Нужно подтверждение.**

### Q3: `AuthRepository.clearTokens()` — оставить или удалить?

Сейчас мёртвый код. Нужен для `forceLogout()` (см. Q2) или можно удалить и вызывать `storage.clear()` напрямую.  
**Рекомендация:** оставить — пригодится для Q2.

### Q4: Оставить ли отображение validity токенов (без raw строк)?

Убрав `savedAccessToken/savedRefreshToken`, автоматически уходят и `isAccessValid/isRefreshValid`. Но показывать «access token: valid/expired» без показа самого токена — уместно для debug-экрана.  
**Рекомендация:** убрать совсем на Этапе 3A. Если нужен debug-экран — отдельная задача.  
**Нужно подтверждение.**
