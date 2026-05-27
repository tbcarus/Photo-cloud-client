# Client stage 4B cleanup analysis

## Scope

Цель 4B: закрыть мелкий технический долг после auth/network этапов перед переходом к файловой части. Новую функциональность не добавлять.

Проверены:

- `app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthUiState.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/TestService.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/*`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/AuthInterceptor.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/TokenStorage.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/server/ServerRepository.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/media/*`

## Что найдено

| Area | Finding | Impact | Recommendation |
| --- | --- | --- | --- |
| Dead utility | `utils/getHttpStatusDescription.kt` больше не используется в `app/src/main/java`; 4A перевёл auth errors на `ApiErrorParser`. | Мёртвый файл, путаница с новым error handling. | Удалить в 4B. |
| DTO cleanup | Старый `AuthResponse.java` отсутствует. Используются `LoginResponseDto` и `RefreshResponseDto`. | Основной DTO-долг 4A закрыт. | Ничего не делать. |
| Response DTO | `AuthService.register/logout` всё ещё возвращают `Map<String, String>` для `{ "message": ... }`. | Работает, но тип неявный. Не мешает файловой части. | Отложить или заменить на `MessageResponseDto` только если хочется полного DTO cleanup. |
| Unused repository method | `AuthRepository.clearTokens()` не используется. | Малый API-шум. | Удалить в 4B, если нет планов использовать извне. |
| Unused token helpers | `TokenStorage.getAccess()`, `getRefresh()`, `saveAccess()` не используются. | Малый API-шум; `saveAccess()` может провоцировать обход `saveTokens`. | Удалить в 4B или оставить до появления файлового слоя, если там ожидается прямой доступ к access/refresh. |
| Old imports | `AuthViewModel.kt` импортирует `Tokens`, но тип явно не используется. | Warning/cleanup. | Удалить import в 4B. |
| Deprecated Compose API | `AuthScreen.kt` использует `androidx.compose.material3.Divider` и `Divider()`. | Deprecated warning. | Заменить на `HorizontalDivider` в 4B. |
| Token display | UI больше не показывает access/refresh token. `ProfileContent` показывает только `userEmail`, извлечённый из JWT subject. | ОК, требование “токены убраны из UI” соблюдено. | Ничего не делать. |
| `verifySession` calls | `verifySession()` вызывается один раз в `init`; других вызовов нет. | ОК. Нет лишней повторной проверки. | Ничего не делать. |
| `isVerifying` | `AuthUiState.isVerifying` включает `LoadingDialog` на стартовой проверке и сбрасывается в `finally`. | ОК. Не используется для показа сообщений, только для loading state. | Ничего не делать. |
| `TokenAuthenticator` | 4A исправил stale-token check через token из failed request и использует `RefreshResponseDto`. | ОК для текущего auth-flow. | Не трогать в 4B. |
| Error parser | `ApiErrorParser` используется в `AuthRepository`. `ServerRepository` для public test ещё читает raw `errorBody`. | Не ломает auth; public test можно унифицировать позже. | Не обязательно до файлов. |
| Local media model | `media/MediaFile`, `MediaFileDao`, `AppDatabase` уже существуют, но не связаны с серверными file/folder DTO. | Не блокер cleanup; это вход в файловую часть. | Не менять в 4B. |

## Auth/UI cleanup

- `Divider` в `AuthScreen.kt` стоит заменить на `HorizontalDivider`.
- Следов отображения токенов в UI не найдено: `AuthUiState` не хранит access/refresh, экран показывает только `userEmail`.
- `verifySession()` не размножился: один вызов в `AuthViewModel.init`.
- `isVerifying` используется корректно: включает загрузку только на время фоновой проверки и сбрасывается через `finally`.
- `AuthViewModel` содержит лишний import `ru.tbcarus.photo_cloud_client.core.storage.Tokens`.

## Logout

Обычный logout после 4A не сломан:

- `AuthRepository.logout()` берёт refresh token из storage.
- Вызывает protected `POST /api/v1/auth/logout`.
- После получения HTTP response вызывает `storage.clear()`.
- При non-2xx response локальные токены всё равно уже очищены, а ошибка отдаётся через `ApiErrorParser`.

Ограничение: если `execute()` бросит network exception до получения response, `storage.clear()` не выполнится. Это не ломает обычный successful logout, но оставляет UX-кейс “сервер недоступен, хочу выйти локально”.

Force logout сейчас лучше отложить:

- это уже не cleanup, а новое поведение/logout mode;
- перед файловой частью не блокирует локальную модель;
- если добавлять, нужно явно решить UX: отдельная кнопка, fallback после failed logout или всегда local clear в `finally`.

Минимально допустимый 4B может оставить force logout вне этапа.

## Что обязательно исправить до файлов

Обязательных блокеров для перехода к локальной модели файлов не найдено.

Практически обязательный cleanup, чтобы не тащить мусор дальше:

1. Удалить `utils/getHttpStatusDescription.kt`.
2. Заменить deprecated `Divider` на `HorizontalDivider`.
3. Убрать unused import `Tokens` из `AuthViewModel.kt`.

Опционально в том же 4B:

4. Удалить неиспользуемый `AuthRepository.clearTokens()`.
5. Удалить или оставить осознанно helper-методы `TokenStorage.getAccess()`, `getRefresh()`, `saveAccess()`.

## Что можно отложить

- Force logout/local logout fallback.
- `MessageResponseDto` вместо `Map<String, String>` для register/logout.
- Унификация `ServerRepository.testConnection()` через `ApiErrorParser`.
- Любые file/folder/checksum Retrofit service, DTO и repository.
- Перевод Java DTO/request classes в Kotlin.
- Изменение `TokenAuthenticator.refreshService()` и Hilt dependency graph.
- Изменение локальной Room-модели `MediaFile` до начала файлового этапа.

## Recommended minimal 4B

Минимальный 4B должен быть чистым cleanup без поведения:

1. `AuthScreen.kt`: заменить `Divider` import/use на `HorizontalDivider`.
2. `AuthViewModel.kt`: удалить unused import `Tokens`.
3. Удалить `utils/getHttpStatusDescription.kt`.
4. При желании удалить `AuthRepository.clearTokens()` как unused wrapper.
5. Не добавлять force logout, file API, folder API или sync API.

## Files to touch

Минимально:

- `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/utils/getHttpStatusDescription.kt`

Если чистить unused wrappers:

- `app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/TokenStorage.kt`

Не трогать в 4B:

- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/ApiPaths.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/AuthService.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/api/TestService.java`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/AuthInterceptor.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiServiceFactory.kt`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/media/*`
- `app/src/main/java/ru/tbcarus/photo_cloud_client/core/db/AppDatabase.kt`
