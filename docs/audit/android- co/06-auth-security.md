# 06. Authentication and security As-Is

## UI, request и session storage

[CONFIRMED] Login route ведёт в AuthScreen, где isLoggedIn переключает LoginContent/ProfileContent. Email/password хранятся в AuthUiState без проверки формата/длины и без trim. Password — обычный OutlinedTextField без PasswordVisualTransformation. После успешного входа и logout значение password явно не очищается. Register не выполняет auto-login и не открывает confirmation flow. [AuthScreen.kt:55](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt:55>), [AuthViewModel.kt:59](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:59>).

[CONFIRMED] AuthRepository.login формирует Tokens только при nonblank accessToken/refreshToken; сохраняет их AuthViewModel.onSuccess, затем reconcile. isLoggedIn означает tokens!=null, не проверенную сервером session. userEmail декодируется из JWT sub без signature validation; это display-only значение, авторизацию выполняет сервер. JwtUtils.isExpired существует, callers отсутствуют. [AuthRepository.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt:38>), [AuthViewModel.kt:26](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:26>), [JwtUtils.kt:7](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/utils/JwtUtils.kt:7>).

[CONFIRMED] Физический storage: Android private SharedPreferences с именем secure_tokens (типичный путь /data/user/0/ru.tbcarus.photo_cloud_client/shared_prefs/secure_tokens.xml; конкретный user/profile path на устройстве не проверен). MasterKey AES256_GCM; key encryption AES256_SIV, values AES256_GCM. Две строки под accessToken/refreshToken. Запись/clear через apply(), после этого синхронно обновляется MutableStateFlow. При чтении требуется наличие обеих строк, пустые строки storage сам не отвергает. [EncryptedPrefsTokenStorage.kt:14](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/storage/EncryptedPrefsTokenStorage.kt:14>).

[PARTIAL] Шифрование токенов есть, но нет account/server namespaces, обработки corruption/Keystore restore failure, собственного migration storage или token encryption test. allowBackup=true, backup/data extraction templates не исключают secure_tokens/Room/settings. Фактический перенос и восстановление ключей здесь не проверялись; recovery policy кодом не задана. [backup_rules.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/backup_rules.xml>), [data_extraction_rules.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/data_extraction_rules.xml>).

## Interceptor / authenticator

[CONFIRMED] AuthInterceptor берёт актуальную пару и добавляет Authorization: Bearer access; без токенов отправляет original request. Нет проверки exp. plain client не содержит auth interceptor/authenticator, поэтому login/register/refresh не получают expired Bearer. [AuthInterceptor.kt:8](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/AuthInterceptor.kt:8>), [NetworkModule.kt:24](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:24>).

[CONFIRMED] Алгоритм TokenAuthenticator:

1. Если responseCount по priorResponse >=2, вернуть null: один auth replay на request chain.
2. Под synchronized(lock) прочитать текущий baseUrl; blank→skip.
3. Считать access из failed request и текущие Tokens; отсутствие Tokens→skip.
4. Если failed token отличается от current access, повторить с current без refresh.
5. Иначе plain Retrofit POST refresh-token с текущим refresh.
6. Non2xx→storage.clear()→null.
7. Body/access null→null, storage сохраняется. Проверки access.isBlank нет.
8. Сохранить Tokens(newAccess, oldRefresh), повторить исходный request с новым Authorization.

Источник: [TokenAuthenticator.kt:34](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/TokenAuthenticator.kt:34>). Refresh token rotation не ожидается. Multipart body открывает content URI повторно при replay. [ContentUriRequestBody.kt:28](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt:28>).

[PARTIAL] Lock сериализует refresh внутри данного authenticator/auth client и уменьшает duplicate refresh для конкурентных401. Он не защищает login/logout/storage.saveTokens, смену URL и work pipeline. [INFERRED] Если logout очистит storage, пока refresh HTTP in-flight, завершившийся refresh может записать старую session обратно; возможен аналогичный overwrite более нового login. Если URL сменился, authenticator использует текущий URL, хотя401 мог прийти от прежнего. Смена host не очищает pair.

## Expiration и failure paths

| Ситуация | Android behavior | Server handoff comparison |
| --- | --- | --- |
| [CONFIRMED] Access expired401 | Authenticator→refresh→single replay | [DOCUMENTED] Согласовано |
| [CONFIRMED] Invalid login401 | Plain response→dialog; без refresh | [DOCUMENTED] INVALID_CREDENTIALS корректно не refresh-ится |
| [CONFIRMED] Refresh401/403 | Clear local tokens | [DOCUMENTED] Непригодный/revoked refresh; повтор тем же token не помогает |
| [INCONSISTENCY] Refresh500/other non2xx | Тоже clear | Сервер не объявляет transient response доказательством invalid token; клиент теряет session |
| [CONFIRMED] Refresh IOException | .execute exception не caught authenticator | Caller-specific error; current tokens сохраняются |
| [CONFIRMED] Refresh2xx empty body/access null | Return null, токены сохраняются | [DOCUMENTED] Некорректный success response; явной ошибки UI нет |
| [PARTIAL] Refresh2xx access="" | Сохраняет пустой access, затем replay | Login такую строку отвергает; storage/authenticator нет |
| [CONFIRMED] Replayed request401 | Loop guard прекращает refresh | Tokens автоматически не очищаются этой веткой |
| [CONFIRMED] Verify session | AuthViewModel.init вызывает testAuth если ready+tokens | Result игнорируется; только loader finally выключается |
| [PARTIAL] Session cleared by authenticator | tokensFlow меняет AuthUiState | Coordinator не подписан на flow; periodic/observer не отменяются автоматически |

Источник lifecycle: [AuthViewModel.kt:94](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:94>), [PeriodicSyncCoordinator.kt:38](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/PeriodicSyncCoordinator.kt:38>). [DOCUMENTED] Серверный access20min/refresh7days, refresh expiry не продлевается, access после logout остаётся действительным до exp. Клиент эти длительности не конфигурирует и не отображает. [Auth handoff](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/02-auth-contract.md>).

## Logout

[CONFIRMED] AuthViewModel требует настроенный URL. AuthRepository требует refresh; выполняет protected logout, затем storage.clear(), затем проверяет HTTP result. Поэтому любой завершившийся HTTP response очищает local pair, включая403/404/500. IOException до response, ошибка factory/неполученные tokens — failure до clear. VM всегда вызывает reconcile после Result, но при retained tokens periodic может остаться включённым. Комментарий «очищает токены в обоих исходах» не соответствует transport exception. [AuthRepository.kt:55](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/auth/AuthRepository.kt:55>), [AuthViewModel.kt:108](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthViewModel.kt:108>).

[CONFIRMED] Logout не очищает Room, настройки IP/port, SyncStatusStore, password/email и не отменяет one-time work. Reconcile отменяет только periodic и unregister observer, если условия больше не соблюдаются. SyncWorker не проверяет auth на входе. [RISK] Уже исполняющаяся работа может продолжить через sync HTTP calls; прекращение UI session не является барьером завершения upload.

## Transport и logging

[CONFIRMED] URL формируется исключительно с http://. Manifest указывает usesCleartextTraffic=true и networkSecurityConfig; XML явно разрешает cleartext для10.0.2.2/localhost. Отдельных build-specific безопасных настроек нет. [PARTIAL] Effective platform policy для других IPv4 зависит от network-security inheritance; runtime доступ к arbitrary LAN IP не подтверждён, поэтому manifest-флаг не трактуется как гарантия разрешения любого host. [NetworkViewModel.kt:60](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:60>), [network_security_config.xml](<C:/projects/Photocloudclient/app/src/main/res/xml/network_security_config.xml>).

[RISK] HttpLoggingInterceptor BODY применяется plain/auth без BuildConfig.DEBUG guard и без redactHeader. Login request body содержит пароль, login response оба JWT, refresh request/response refresh/access, logout request refresh. Это достаточно для риска disclosure даже если Authorization header не попал в logging из-за порядка interceptors (logging установлен до AuthInterceptor). Не утверждается, что каждый Authorization header реально выводится. [NetworkModule.kt:21](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:21>).

[CONFIRMED] Custom TLS pinning, biometric gate, FLAG_SECURE, profile/account isolation, credential redaction и secure logout tests не найдены. Это описание отсутствующего кода, не требование внедрения этих функций.

