# Stage 4B — Cleanup Analysis

Анализ технического долга после Этапов 2A–4A. Цель — зафиксировать, что мешает, что можно убрать и что безопасно отложить.

## Проверенные файлы

`utils/getHttpStatusDescription.kt`, `utils/JwtUtils.kt`, `utils/Routes.kt`,
`api/AuthService.java`, `api/TestService.java`,
`api/models/AuthRequest.java`, `api/models/RefreshTokenRequest.java`, `api/models/LogoutRequest.java`,
`auth/AuthRepository.kt`, `auth/AuthUiState.kt`,
`ui/screens/auth/AuthScreen.kt`, `ui/screens/auth/AuthViewModel.kt`,
`ui/MainScreen.kt`, `ui/components/NavigationGraph.kt`, `ui/components/Dialogs.kt`,
`media/MediaFile.kt`, `media/MediaFileStatus.kt`, `media/MediaFileDao.kt`,
`core/db/AppDatabase.kt`, `di/AppModule.kt`, `di/StorageModule.kt`

---

## 1. Неиспользуемые классы и утилиты

### `utils/getHttpStatusDescription.kt` — мёртвый код

После Этапа 4A импорт и вызов убраны из `AuthRepository.kt`. Функция нигде не вызывается в `app/src`. Файл можно удалить без последствий.

```
app\src\main\java\ru\tbcarus\photo_cloud_client\utils\getHttpStatusDescription.kt
```

**Статус: обязательно удалить в 4B.**

---

### `JwtUtils.isExpired()` — мёртвый метод

`JwtUtils.getSubject()` используется в `AuthViewModel:31` — нужен.  
`JwtUtils.isExpired()` нигде не вызывается. Не используется ни в `AuthInterceptor`, ни в `TokenAuthenticator`, ни в ViewModel.

```kotlin
// JwtUtils.kt
fun isExpired(jwt: String): Boolean { ... }  // нет ни одного вызова в src
fun getSubject(jwt: String): String? { ... }  // используется в AuthViewModel
```

**Статус: удалить мёртвый метод или убрать тело и оставить TODO-заглушку, если понадобится при proactive refresh.**

---

### Java-классы request DTO — работают, но несогласованы

Оставшиеся `.java` в Kotlin-проекте:

| Файл | Используется |
|---|---|
| `api/models/AuthRequest.java` | `AuthRepository.kt` — `AuthRequest(email, password)` |
| `api/models/RefreshTokenRequest.java` | `TokenAuthenticator.kt` — `RefreshTokenRequest(current.refreshToken)` |
| `api/models/LogoutRequest.java` | `AuthRepository.kt` — `LogoutRequest(refresh)` |
| `api/AuthService.java` | `ApiServiceFactory.kt`, `TokenAuthenticator.kt` |
| `api/TestService.java` | `ApiServiceFactory.kt`, `ServerRepository.kt` |

Функционируют корректно через Java/Kotlin interop. Компилируются и проходят build. Технического долга по поведению нет — только несоответствие стилю Kotlin-проекта.

**Статус: можно отложить. Не блокируют ничего. Рекомендуется конвертировать вместе с файловой частью, когда появятся новые DTO.**

---

## 2. Auth/UI cleanup

### `AuthScreen.kt:107` — deprecated `Divider`

```kotlin
import androidx.compose.material3.Divider  // deprecated
...
Divider()  // строка 107
```

Должно быть:
```kotlin
import androidx.compose.material3.HorizontalDivider
...
HorizontalDivider()
```

Вызывает предупреждение компилятора при каждом build. Одностроченная правка.

**Статус: обязательно исправить в 4B.**

---

### Токены в UI — чисто

Нигде в UI-слое токены (`accessToken`, `refreshToken`) не отображаются и не передаются в `UiState`. `AuthUiState` хранит только `userEmail`, извлечённый из JWT через `JwtUtils.getSubject()`. **Проблем нет.**

---

### `verifySession()` — логика корректна

Вызывается один раз в `AuthViewModel.init`. Внутри запускает `repo.testAuth()`, результат намеренно игнорируется — цель только инициировать цикл 401→refresh→retry:

```kotlin
private fun verifySession() {
    if (!repo.isReady()) return
    if (repo.getTokens() == null) return
    viewModelScope.launch {
        _uiState.update { it.copy(isVerifying = true) }
        try { repo.testAuth() }  // результат не нужен
        finally { _uiState.update { it.copy(isVerifying = false) } }
    }
}
```

**Поведение корректно.** Если access token жив — запрос проходит. Если истёк — `TokenAuthenticator` обновляет токены. Если refresh тоже истёк — `storage.clear()` → `tokensFlow` → UI выходит из "залогинен".

---

### `isVerifying` — используется правильно

```kotlin
// AuthScreen.kt:44
if (state.status == ConnectionStatus.LOADING || state.isVerifying) {
    LoadingDialog()
}
```

Показывает индикатор во время `verifySession()`. Не конфликтует с остальными статусами. **Проблем нет.**

---

## 3. Logout

### Корректность logout после 4A

Текущий `AuthRepository.logout()`:

```kotlin
val refresh = storage.getTokens()?.refreshToken ?: throw Exception("No tokens")
val resp = apiServiceFactory.authAuthService().logout(LogoutRequest(refresh)).execute()
storage.clear()              // всегда, до проверки статуса
if (!resp.isSuccessful) {
    throw Exception(ApiErrorParser.parse(resp))
}
```

- Токены очищаются независимо от ответа сервера ✅
- На ошибке сервера `tokensFlow` всё равно эмитит `null` → UI разлогинен ✅
- Если `getTokens()` вернул `null` (токены уже очищены) — `Exception("No tokens")` до `storage.clear()`, но это no-op ✅

**Logout работает корректно.**

---

### Force logout / logout-all — нужен ли сейчас

Серверные endpoint-ы `POST /auth/logout-all` и `POST /auth/logout-others` существуют, но в клиенте не используются.

- `logout-all` нужен для управления несколькими сессиями (другие устройства).
- В текущем клиенте нет UI для этого, нет экрана устройств/сессий.
- Добавление `logout-all` без соответствующего UI бессмысленно.

**Статус: отложить. Не мешает переходу к файлам.**

---

## 4. Границы перед файловой частью

### Что уже готово

| Компонент | Статус |
|---|---|
| `MediaFile` Room entity | Определён, схема подходит для локального индекса |
| `MediaFileStatus` enum | Определён: `PENDING`, `PENDING_UPLOAD`, `UPLOADING`, `SYNCED`, `FAILED`, `LOCAL_DELETED` |
| `MediaFileDao` | Определён с нужными операциями |
| `AppDatabase` | Настроен, `fallbackToDestructiveMigration` помечен TODO для prod |
| `AppModule` — `provideMediaFileDao` | Есть, но **инжектировать пока некуда** — нет Repository/ViewModel для файлов |
| `FilesScreen` | Stub-заглушка `CenteredText("Files")` |

### Что потребуется для файловой части (не сейчас)

- `FileItemDto` / `FolderDto` — серверные response DTO
- `FileService` / `FolderService` — Retrofit интерфейсы
- `FileRepository` — сетевой слой
- `FilesViewModel` + реальный `FilesScreen`
- Решение про `MediaFile` vs `FileItemDto` — локальный индекс vs серверная модель

### Что НЕ мешает файловой части прямо сейчас

- Текущее состояние `AuthRepository`, `TokenAuthenticator`, `ApiServiceFactory` полностью готово — любой authenticated file request пройдёт через `AuthInterceptor` + `TokenAuthenticator` автоматически
- `ApiServiceFactory` легко расширяется новыми методами
- `ApiPaths` легко расширяется без конфликтов

---

## 5. Рекомендуемый минимальный 4B

### Обязательно (2 файла, 3 изменения)

| # | Действие | Файл |
|---|---|---|
| 1 | Удалить файл | `utils/getHttpStatusDescription.kt` |
| 2 | Удалить метод `isExpired()` из `JwtUtils` | `utils/JwtUtils.kt` |
| 3 | Заменить `Divider` → `HorizontalDivider` | `ui/screens/auth/AuthScreen.kt` |

### Можно отложить (не блокируют)

| Действие | Когда |
|---|---|
| Конвертировать Java DTO/Service → Kotlin | Вместе с созданием файловых DTO |
| Переименовать `Routes.Login` → `Routes.Auth` | Перед работой с навигацией |
| Реализовать `logout-all` | После появления UI управления сессиями |
| `AppDatabase` — полноценные миграции | Перед production/бета |

### Что не трогать в 4B

Всё остальное: `AuthRepository`, `TokenAuthenticator`, `ApiErrorParser`, все DTO, `ApiServiceFactory`, `NetworkModule`, `ServerRepository`, `MediaFile*`, `AppDatabase`.

---

## 6. Файлы, затрагиваемые в 4B

| Файл | Действие |
|---|---|
| `utils/getHttpStatusDescription.kt` | **Удалить** |
| `utils/JwtUtils.kt` | **Изменить** — удалить `isExpired()` |
| `ui/screens/auth/AuthScreen.kt` | **Изменить** — `Divider` → `HorizontalDivider` |
