# Диагностика компиляции и анализ дефекта testAuth/Authorization

**Дата:** 2026-05-26  
**После Этапа 2B:** `ApiServiceFactory` создан, `AuthRepository` использует фабрику.

---

## 1. Проблемы компиляции — диагностика

### Заявленные проблемы

| # | Сообщение | Реальный статус |
|---|---|---|
| 1 | `import ru.tbcarus.photo_cloud_client.api.TestService` не находится в `ApiServiceFactory` | **Не воспроизводится** |
| 2 | `ApiPaths.TEST` / `ApiPaths.TEST_AUTH` не разрешаются в `TestService` | **Не воспроизводится** |

### Почему ошибки не воспроизводятся

**Проблема 1 (импорт TestService):**  
`TestService.java` физически находится по пути `app/.../api/TestService.java` и объявляет `package ru.tbcarus.photo_cloud_client.api`. Это точно соответствует импорту в `ApiServiceFactory.kt`. Файл на месте, package корректен.

**Проблема 2 (ApiPaths.TEST/TEST_AUTH):**  
`ApiPaths.kt` объявлен в том же пакете `ru.tbcarus.photo_cloud_client.api`, что и `TestService.java`. `const val` в Kotlin `object` компилируется в Java-байткод как `public static final String` — это **compile-time constant**, допустимая в Java-аннотациях. Java-импорт не нужен: оба файла в одном пакете.

### Результат сборки

```
./gradlew clean assembleDebug
BUILD SUCCESSFUL in 24s
```

Единственный вывод — deprecation-предупреждение: `Divider` → `HorizontalDivider` в `AuthScreen.kt`. Это не ошибка, не блокирует сборку.

**Вывод:** компиляция после Этапа 2B работает корректно. Никаких исправлений для восстановления сборки не потребовалось.

---

## 2. Анализ дефекта testAuth/Authorization header

### 2.1 Как объявлен TestService.testAuth()

```java
// TestService.java, строка 13
@GET(ApiPaths.TEST_AUTH)
Call<TestResponse> testAuth(@Header("Authorization") String token);
```

`@Header("Authorization")` — Retrofit-аннотация, которая добавляет заголовок `Authorization` со значением переданного аргумента.

### 2.2 Как он вызывается из AuthRepository

```kotlin
// AuthRepository.kt, строка 56
val resp = apiServiceFactory.authTestService().testAuth("").execute()
```

Передаётся пустая строка `""`. Retrofit формирует заголовок: `Authorization: Bearer `.

### 2.3 Добавляет ли AuthInterceptor Authorization ко всем запросам authClient

Да. `authClient` строится в `NetworkModule` с `authInterceptor`:

```kotlin
// NetworkModule.kt, строки 47-48
.addInterceptor(authInterceptor)
```

`AuthInterceptor.intercept()`:
```kotlin
val authed = original.newBuilder()
    .header("Authorization", "Bearer ${tokens.accessToken}")
    .build()
return chain.proceed(authed)
```

### 2.4 Ключевой факт: `.header()` заменяет, `.addHeader()` добавляет

`Request.Builder.header(name, value)` в OkHttp **заменяет** все существующие значения для данного имени заголовка. Это не `addHeader()` (который добавляет новое значение).

### 2.5 Фактическое поведение при вызове testAuth("")

**Сценарий A: токены есть (пользователь залогинен)**

| Шаг | Состояние заголовка Authorization |
|---|---|
| Retrofit строит запрос | `Authorization: Bearer ` (пустая строка из `testAuth("")`) |
| `AuthInterceptor.intercept()` вызывается | — |
| `tokens != null` → `.header("Authorization", "Bearer <token>")` | Заменяет предыдущее значение |
| Финальный запрос | `Authorization: Bearer <реальный_токен>` ✓ |

Работает корректно — но только потому, что interceptor молча перетирает пустой заголовок.

**Сценарий B: токены отсутствуют (пользователь не залогинен)**

| Шаг | Состояние заголовка Authorization |
|---|---|
| Retrofit строит запрос | `Authorization: Bearer ` (пустая строка) |
| `AuthInterceptor.intercept()` вызывается | — |
| `tokens == null` → `return chain.proceed(original)` | Interceptor пропускает, оригинал не изменяется |
| Финальный запрос | `Authorization: Bearer ` (невалидный заголовок) ✗ |

Серверу отправляется `Authorization: Bearer ` — технически невалидный Bearer token. Сервер, скорее всего, вернёт 401 и поведение внешне нормальное, но это лишний и некорректный заголовок.

### 2.6 Есть ли двойной Authorization header

**Нет.** Двойного заголовка нет, потому что `.header()` заменяет, а не добавляет. Однако в сценарии B отправляется пустой Bearer header — это дефект, даже если не сразу заметный.

### 2.7 Корень проблемы

`@Header("Authorization") String token` в `TestService.testAuth()` — это остаток старого подхода, когда токен передавался явно в каждый вызов. После введения `AuthInterceptor` этот параметр стал избыточным. Вызов `testAuth("")` с пустой строкой — явный признак того, что параметр уже никому не нужен, но убрать его забыли.

---

## 3. Рекомендуемое минимальное исправление

### Что изменить

**1. `api/TestService.java`** — убрать `@Header`-параметр:

```java
// Было:
@GET(ApiPaths.TEST_AUTH)
Call<TestResponse> testAuth(@Header("Authorization") String token);

// Станет:
@GET(ApiPaths.TEST_AUTH)
Call<TestResponse> testAuth();
```

**2. `auth/AuthRepository.kt`** — убрать пустой аргумент:

```kotlin
// Было:
val resp = apiServiceFactory.authTestService().testAuth("").execute()

// Станет:
val resp = apiServiceFactory.authTestService().testAuth().execute()
```

### Что не трогать

- `AuthInterceptor` — работает корректно, менять не нужно.
- `TokenAuthenticator` — не связан с этим дефектом.
- `ApiServiceFactory.authTestService()` — не меняется, по-прежнему возвращает `TestService` через `authClient`.
- Остальные методы `TestService` (`testServer`) — не затронуты.

### Риски исправления

| Риск | Оценка | Примечание |
|---|---|---|
| Нарушение auth-flow | Нет | `authClient` + `AuthInterceptor` обеспечивают авторизацию — как и прежде |
| Двойной header после исправления | Нет | `@Header`-параметр убран, interceptor добавляет единственный корректный заголовок |
| Поведение при отсутствии токенов | Улучшается | Без токенов запрос пойдёт без Authorization header → честный 401 от сервера вместо `Authorization: Bearer ` |
| Обратная совместимость | Не применимо | `testAuth` вызывается только из `AuthRepository.testAuth()` |
| Компиляция | Нет | Retrofit генерирует код по интерфейсу, убирание параметра — корректное изменение контракта |

**Исправление безопасно и рекомендуется к реализации.**

---

## 4. Итог

| Пункт | Результат |
|---|---|
| Причина ошибок компиляции | Не воспроизведены на clean build; import и ApiPaths.TEST корректны |
| Минимальные исправления для восстановления сборки | Не потребовались |
| Как работает testAuth сейчас | Правильно при наличии токенов (interceptor заменяет пустой header); некорректно при их отсутствии (шлёт `Bearer `) |
| Двойной Authorization header | Нет — `.header()` заменяет, не добавляет |
| Рекомендованное исправление | Убрать `@Header("Authorization") String token` из `TestService.testAuth()` и `""` из вызова в `AuthRepository` |
| Финальный результат сборки | `BUILD SUCCESSFUL in 24s` (clean build) |
