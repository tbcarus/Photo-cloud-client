# 16. Known Gaps and Risks

> Аудит As-Is Android-клиента PhotoCloud. Дата: 2026-09-10, HEAD `2b9ab69`.
> Пути — относительно `app/src/main/java/ru/tbcarus/photo_cloud_client/`. Метки — см. [00-executive-summary.md](00-executive-summary.md).
> Это единственный оценочный документ аудита. Каждый пункт: **проблема → доказательство → последствие → severity**. Redesign не предлагается.
> Severity: **CRITICAL** — основная функция не работает в штатном сценарии · **HIGH** — вероятная потеря функции, данных или утечка · **MEDIUM** — ограниченное влияние или требует стечения условий · **LOW** — косметика, долг, редкие случаи.

---

## Сводка

| ID | Проблема | Severity |
| --- | --- | --- |
| C1 | Фоновый sync не работает в процессе без UI (пустой `baseUrl`) | CRITICAL |
| I1 | Head-of-line blocking очереди upload | HIGH |
| D1 | Локальный индекс не привязан к аккаунту/серверу | HIGH |
| G1 | `BODY`-логирование: секреты и полные тела upload | HIGH |
| J1 | Модальный неотменяемый loader блокирует UI во время sync | HIGH |
| B1 | Глобальная инициализация живёт в `NetworkViewModel` | HIGH |
| C2 | `ContentObserver` привязан к жизни процесса | MEDIUM |
| F1 | `KEEP` + неограниченный backoff глотают события | MEDIUM |
| F2 | Неограниченные retry | MEDIUM |
| F4 | One-time work не отменяется при logout | MEDIUM |
| H1 | Refresh `5xx`/`400` очищает токены | MEDIUM |
| H2 | После очистки токенов нет re-login и `reconcile()` | MEDIUM |
| H4 | Пароль не маскируется | MEDIUM |
| G2 | Cleartext разрешён только для эмулятора/localhost [INFERRED] | MEDIUM |
| G3 | Только HTTP, без TLS | MEDIUM |
| G5 | `Content-Length` из MediaStore | MEDIUM |
| E1 | Stale-удаление при неполной выборке MediaStore | MEDIUM |
| E2 | Вероятная вырезка GPS из загружаемых байтов [INFERRED] | MEDIUM |
| I2 | Нет ручного retry; `PERMISSION`/`FILE_NOT_FOUND` терминальны | MEDIUM |
| D2 | Гонки scan ↔ стадии | MEDIUM |
| D3 | Нет сверки с сервером | MEDIUM |
| C4 | Backup/restore EncryptedSharedPreferences [INFERRED] | MEDIUM |
| A1 | Видео не синхронизируется | MEDIUM |
| A2 | Нет ручного запуска sync | MEDIUM |
| K1 | Нет тестов | MEDIUM |
| остальные | см. ниже | LOW |

---

## A. Functional gaps

### A1. Видео не синхронизируется — MEDIUM
**Доказательство.** Коллекция `MediaStore.Images` (`MediaStoreRepository.kt:62`), observer на `Images` (`MediaChangeObserver.kt:51`), guard `image/*` (`FileUploadRepository.kt:80`), нет `READ_MEDIA_VIDEO`.
**Последствие.** Видео из DCIM не резервируется, хотя сервер его принимает.

### A2. Нет ручного запуска синхронизации и ручного retry — MEDIUM
**Доказательство.** Кнопки удалены в `6f21d79`; `enqueueOneTimeSync()` вызывается только из `MediaChangeObserver`.
**Последствие.** Пользователь не может ускорить синхронизацию или повторить неудачные файлы; зависит от observer/periodic/backoff.

### A3. Нет работы с серверным содержимым — LOW
**Доказательство.** Не используются `GET /files`, `download`, `DELETE`, папки (см. 05 §8).
**Последствие.** Приложение не позволяет проверить, что лежит в облаке. Для backup-клиента это рамки продукта, а не дефект.

### A4. Settings и Profile — заглушки — LOW
**Доказательство.** `ui/screens/SettingsScreen.kt`, `ProfileScreen.kt`.
**Последствие.** Две из пяти вкладок пусты; настроек синхронизации нет.

### A5. `serverFileId` не заполняется для файлов, найденных на pre-check — LOW
**Доказательство.** `ChecksumPrecheckRepository.kt:20, 122-125`.
**Последствие.** Для части `SYNCED`-файлов нельзя адресовать серверную запись (download/delete в будущем потребуют `GET /files`).

### A6. Нет предварительного фильтра по размеру > 100 MiB — LOW
**Доказательство.** Лимит нигде не проверяется; `413` → `FAILED(HTTP_413)`.
**Последствие.** До отказа передаётся до ~100 MiB трафика, в том числе по мобильной сети.

### A7. Нет сценариев восстановления пароля, подтверждения email, предупреждения о невозможности resend — LOW
**Доказательство.** Отсутствие соответствующего кода; см. `02-auth-contract.md` §8–9.
**Последствие.** Пользователь, не получивший письмо или забывший пароль, не получает помощи в приложении.

## B. Architecture

### B1. Глобальная инициализация приложения привязана к UI-ViewModel — HIGH
**Доказательство.** `BaseUrlProvider.baseUrl` заполняется только в `NetworkViewModel.init`/`testConnection` (`NetworkViewModel.kt:36, 70`); там же первый `reconcile()` (`:41`). `App.onCreate` пуст.
**Последствие.** Всё, что не проходит через Activity (WorkManager в новом процессе), работает без адреса сервера и без observer. Корень C1 и C2.

### B2. State machine распределена без единого владельца и без guard-условий — MEDIUM
**Доказательство.** Статусы меняют `MediaFileRepository`, `ChecksumRepository`, `ChecksumPrecheckRepository`, `FileUploadRepository`; UPDATE не проверяют текущий статус (04 §5).
**Последствие.** Корректность переходов держится на порядке вызовов и `Mutex`; гонки (D2) и сложность изменений.

### B3. Связь auth ↔ sync односторонняя и ручная — MEDIUM
**Доказательство.** `reconcile()` вызывается вручную из VM; `TokenAuthenticator.clear()` его не вызывает; logout не трогает Room и one-time work.
**Последствие.** Состояние фонового sync расходится с состоянием сессии (H2, F4, D1).

### B4. Пакет `media` смешивает data, domain, background и scheduling — LOW
**Доказательство.** 24 файла разных ролей в одном пакете (18).
**Последствие.** Затрудняет навигацию и тестирование.

### B5. Сетевой слой: Retrofit на каждый вызов, Java `Call` API — LOW
**Доказательство.** `ApiServiceFactory.kt:21-61`; `FileUploadRepository.kt:99` (на каждый файл).
**Последствие.** Лишние аллокации; блокирующие вызовы не отменяются корутинами.

### B6. Дублирование логики проверки разрешения (4 копии) — LOW
**Доказательство.** `MediaStoreRepository.kt:34-41`, `ChecksumRepository.kt:65-73`, `PeriodicSyncCoordinator.kt:52-60`, `FilesScreen.kt:47-53`.
**Последствие.** Добавление видео или partial access потребует синхронной правки 4 мест.

## C. Android lifecycle

### C1. Фоновая синхронизация не работает в процессе, запущенном без UI — CRITICAL
**Доказательство.** `BaseUrlProvider.baseUrl = ""` по умолчанию (`di/BaseUrlProvider.kt:8`), заполняется только из `NetworkViewModel`. `SyncWorker.runSync()`: `if (baseUrl.isBlank() || …) { SERVER_UNAVAILABLE; return Result.retry() }` (`SyncWorker.kt:50-55`).
**Последствие.** После перезагрузки устройства, убийства процесса системой или свайпа из recents (если процесс завершён) ни periodic, ни отложенная one-time работа ничего не загрузят, пока пользователь не откроет приложение. Работа бесконечно ретраится с растущим backoff. Основной сценарий «автозагрузка в фоне» выполняется только в сессии, где UI открывался.

### C2. `MediaChangeObserver` существует только в памяти процесса — MEDIUM
**Доказательство.** Регистрация в `start()` из `reconcile()`; нет восстановления из worker/`App`.
**Последствие.** После гибели процесса новые фото загружаются только periodic-работой (которая сама упирается в C1). **[INFERRED]** В замороженном кэшированном процессе события откладываются.

### C3. Нет foreground-режима для долгого начального sync — LOW
**Доказательство.** Нет `setForeground`/`setExpedited`/foreground service.
**Последствие.** **[INFERRED]** Прогон ограничен окном исполнения JobScheduler и прерывается. Прогресс сохраняется, поэтому начальная синхронизация большой библиотеки растягивается на много прогонов с backoff.

### C4. Backup/restore зашифрованных prefs и ошибки Keystore — MEDIUM
**Доказательство.** `allowBackup="true"`, правила пусты (`AndroidManifest.xml:12-14`); `EncryptedSharedPreferences.create` без `try/catch` (`EncryptedPrefsTokenStorage.kt:17-23`).
**Последствие.** **[INFERRED]** После восстановления на другом устройстве или при повреждении Keystore приложение может падать при создании `TokenStorage`. Room-индекс из backup с чужими `mediaStoreId` будет полностью заменён первым scan.

### C5. Остановка worker'а отображается как ошибка — LOW
**Доказательство.** `catch (e: Exception)` ловит `CancellationException` → `SyncStatus.ERROR` (`SyncWorker.kt:38-44`).
**Последствие.** Ложное «Ошибка синхронизации» в UI.

## D. Data consistency

### D1. Локальный индекс не привязан к аккаунту и серверу — HIGH
**Доказательство.** В `MediaFile` нет user/server id; logout не очищает Room (`AuthRepository.logout`); смена IP не сбрасывает Room (`NetworkViewModel.testConnection`).
**Последствие.** После входа другим пользователем или переключения на другой сервер все `SYNCED`-файлы считаются загруженными и никогда не будут отправлены новому владельцу. `serverFileId` указывают на записи чужого аккаунта или сервера.

### D2. Гонки между scan и стадиями — MEDIUM
**Доказательство.** `MediaFileRepository.scanImages` без `Mutex`, вызывается из UI и из двух разных unique-работ; UPDATE без проверки статуса.
**Последствие.** **[INFERRED]** Возможны `SYNCED`/`PENDING_UPLOAD` с `checksum = NULL` и `CHECKSUM_READY` с устаревшим checksum (09 §4). Эти записи уже не самоисправляются.

### D3. Нет сверки с сервером — MEDIUM
**Доказательство.** `SYNCED` терминален; `GET /files` не используется; ответ upload (папка, checksum) не проверяется.
**Последствие.** Файл, удалённый на сервере, никогда не будет загружен повторно. `SYNCED` в папке `Files` вместо `CAMERA` (upload без `folderId`) не будет исправлен.

### D4. Миграция `1→2` вызвала массовый сброс индекса — LOW
**Доказательство.** `lastModified INTEGER NOT NULL DEFAULT 0` + `resetChecksumIfFileChanged` (04 §4.1).
**Последствие.** Однократный полный перехэш и потеря `serverFileId` на обновлённых установках.

### D5. `FAILED`-строки до Stage 5K не восстанавливаются — LOW
**Доказательство.** `failureReason IS NULL` не проходит фильтр `getRetryableFailed` (04 §4.2).
**Последствие.** Навсегда `FAILED` без причины.

### D6. `serverFileId` теряется при безобидных изменениях файла; `SYNCED` неоднозначен — LOW
**Доказательство.** `resetChecksumIfFileChanged` обнуляет `serverFileId` при любом изменении `DATE_MODIFIED`; pre-check ставит `SYNCED` без id.
**Последствие.** Со временем доля `SYNCED` без `serverFileId` растёт.

### D7. Нет транзакций Room — LOW
**Доказательство.** 04 §3.1.
**Последствие.** Прерванный scan оставляет промежуточное состояние, которое следующий scan исправляет; лишние эмиссии `Flow`.

### D8. Destructive migration и отсутствие поддержки downgrade — LOW
**Доказательство.** `AppDatabase.kt:66-68`.
**Последствие.** Потеря индекса при пропуске миграции; падение при откате APK.

### D9. Локальный checksum не сравнивается с серверным — LOW
**Доказательство.** `FileItemDto.checksum` не читается.
**Последствие.** Не выявляется изменение файла между hashing и upload.

## E. MediaStore

### E1. Stale-удаление при неполной выборке — MEDIUM
**Доказательство.** `MediaFileRepository.kt:65-74` удаляет всё, чего нет в выборке; битые строки курсора пропускаются молча (`MediaStoreRepository.kt:128-130`).
**Последствие.** Partial access (Android 14, [INFERRED]), временно недоступный том, ошибка чтения строки → удаление строк индекса со всей sync-историей. После восстановления — полный повторный pipeline.

### E2. Байты фото, вероятно, читаются с вырезанной геопозицией — MEDIUM
**Доказательство.** Нет `ACCESS_MEDIA_LOCATION` и `MediaStore.setRequireOriginal`.
**Последствие.** **[INFERRED]** На сервер уходит не оригинал: GPS в EXIF отсутствует, серверные `latitude/longitude` = `null`. Для backup-продукта это потеря данных. Требует проверки (17 V3).

### E3. Полный скан и полный список в памяти на каждом прогоне — LOW
**Доказательство.** `loadImages()` без пагинации; `getAll()` в UI; нет индексов по `status`/`createdAt`.
**Последствие.** **[INFERRED]** Линейный рост времени прогона и потребления памяти на больших библиотеках.

### E4. Fallback `createdAt = now()` создаёт вечный «изменённый» файл — LOW
**Доказательство.** `MediaStoreRepository.kt:106-110`: при отсутствии всех дат `createdAt = now`, `lastModified = createdAt`.
**Последствие.** Такой файл сбрасывается в `PENDING` и перехэшируется на каждом scan.

## F. WorkManager

### F1. `KEEP` + backoff до 5 ч отбрасывают события observer — MEDIUM
**Доказательство.** `ExistingWorkPolicy.KEEP` (`SyncScheduler.kt:45-49`); backoff не настроен.
**Последствие.** **[INFERRED]** Пока `photo_sync` ждёт повтора, новые фото не запускают sync. Без ручной кнопки пользователь не может это обойти.

### F2. Неограниченные повторы — MEDIUM
**Доказательство.** `Result.retry()` без проверки `runAttemptCount`.
**Последствие.** Бесконечные прогоны при устойчивых ошибках (C1, I1, `401` после logout), каждый с полным scan.

### F3. One-time и periodic выполняются параллельно; UI видит только one-time — LOW
**Доказательство.** Разные unique-имена; `observeIsSyncing` по `photo_sync`.
**Последствие.** Двойная работа scan; индикатор не отражает periodic.

### F4. One-time работа не отменяется при logout — MEDIUM
**Доказательство.** `AuthViewModel.logout` → `reconcile()` отменяет только periodic.
**Последствие.** **[INFERRED]** После выхода отложенный прогон продолжит индексировать и хэшировать фото и слать неавторизованные запросы с backoff.

### F5. Проверка сервера блокирует локальные стадии — LOW
**Доказательство.** Ping до restore/scan/checksum (`SyncWorker.kt:50-55`).
**Последствие.** Без сервера индекс и checksum не обновляются; UI показывает устаревший список.

### F6. Удалён весь `InitializationProvider` — LOW
**Доказательство.** `AndroidManifest.xml:37-41` (`tools:node="remove"` на провайдере).
**Последствие.** **[INFERRED]** Отключены все App Startup-инициализаторы, а не только WorkManager.

## G. Networking

### G1. `HttpLoggingInterceptor.Level.BODY` во всех сборках — HIGH
**Доказательство.** `di/NetworkModule.kt:21-22`; подключён к обоим клиентам.
**Последствие.** **[INFERRED]** Пароль, access и refresh token попадают в Logcat. Тело каждого upload целиком буферизуется в памяти и печатается в лог (14 §3.1): риск OOM на больших файлах, лишнее чтение с диска, утечка содержимого фото в системный лог и bug-report'ы.

### G2. Cleartext разрешён только для `10.0.2.2`/`localhost`, а клиент поддерживает только HTTP — MEDIUM
**Доказательство.** `network_security_config.xml` без `base-config`; `usesCleartextTraffic="true"`; URL всегда `http://`.
**Последствие.** **[INFERRED]** На физическом устройстве HTTP к LAN-IP сервера может блокироваться политикой (`CLEARTEXT communication … not permitted`), и приложение работает только на эмуляторе. Требует проверки (17 V1).

### G3. Только HTTP, без TLS; нет hostname — MEDIUM
**Доказательство.** `"http://$ip:$port/"` (`NetworkViewModel.kt:60`), IPv4-валидация.
**Последствие.** Пароль, токены и фото передаются открытым текстом. HTTPS reverse-proxy (предполагаемый серверной стороной, `06-server-capabilities.md` §7) использовать нельзя. Вне доверенной сети — HIGH.

### G4. Health-check через статический `/test`; двойной резолв `CAMERA` — LOW
**Доказательство.** `SyncWorker.kt:51`; `FolderRepository` вызывается из pre-check и upload.
**Последствие.** «Сервер жив» не означает «БД/хранилище доступны»; 4 лишних запроса на каждый прогон.

### G5. `Content-Length` берётся из MediaStore `SIZE` — MEDIUM
**Доказательство.** `ContentUriRequestBody.contentLength()` (`:26`).
**Последствие.** **[INFERRED]** Если фактический размер отличается от `SIZE`, OkHttp бросит `ProtocolException` → transient → head-of-line blocking (I1).

## H. Auth

### H1. Любой неуспешный refresh очищает токены — MEDIUM
**Доказательство.** `TokenAuthenticator.kt:62-65`.
**Последствие.** Временный `5xx`/`503` сервера на refresh разлогинивает пользователя. Контракт требует менять состояние только на `401`/`403`.

### H2. После очистки токенов нет re-login-сценария и `reconcile()` — MEDIUM
**Доказательство.** `TokenAuthenticator` не знает о `PeriodicSyncCoordinator`; навигации на вход нет.
**Последствие.** Фоновая синхронизация тихо ломается (`401` → retry); пользователь узнаёт об этом, только открыв вкладку Login.

### H3. Logout: токены остаются при сетевой ошибке; требуется `baseUrl`; `404` как ошибка — LOW
**Доказательство.** `AuthRepository.kt:55-64`; `AuthViewModel.notReady()`.
**Последствие.** Невозможно выйти без сети или без настроенного сервера.

### H4. Поле пароля не маскируется — MEDIUM
**Доказательство.** `AuthScreen.kt:74-79` без `PasswordVisualTransformation`.
**Последствие.** Пароль виден на экране и в записи экрана; может попасть в словарь клавиатуры.

### H5. Гонки refresh ↔ logout/login — LOW
**Доказательство.** `saveTokens` в authenticator без проверки, что сессия не сменилась (06 §2.11).
**Последствие.** **[INFERRED]** Кратковременное «воскрешение» сессии после logout.

### H6. Нет проактивного refresh — LOW
**Доказательство.** `JwtUtils.isExpired` не используется.
**Последствие.** Upload, начатый с истекающим access, передаётся повторно целиком.

### H7. `security-crypto` в alpha-версии — LOW
**Доказательство.** `1.1.0-alpha06`.
**Последствие.** Зависимость от нестабильного API.

## I. Error recovery

### I1. Head-of-line blocking очереди upload — HIGH
**Доказательство.** Transient-ошибка → `PENDING_UPLOAD` + `return UploadOutcome.Error` (`FileUploadRepository.kt:100-143`); выборка `ORDER BY createdAt DESC LIMIT 20` (`MediaFileDao.kt:139-140`).
**Последствие.** Если конкретный файл стабильно вызывает transient-ошибку (`5xx` сервера на этом содержимом, несовпадение длины, ошибка чтения в `writeTo`), он при каждом прогоне идёт первым и обрывает прогон. Все более старые файлы не загружаются никогда.

### I2. Нет ручного retry; «временные по природе» причины считаются permanent — MEDIUM
**Доказательство.** `FailureReason.isRetryable` true только для `CHECKSUM_IO`; `PERMISSION`/`FILE_NOT_FOUND` терминальны.
**Последствие.** Файлы, недоступные в момент прогона (том не смонтирован, разрешение восстановлено), остаются `FAILED` навсегда.

### I3. Текст ошибок теряется — LOW
**Доказательство.** `UploadOutcome.Error.message`, `ChecksumPrecheckOutcome.Error.message` не сохраняются и не логируются; в UI только enum.
**Последствие.** Диагностика проблем на устройстве пользователя затруднена.

### I4. `400` pre-check повторяется бесконечно — LOW
**Доказательство.** Любой не-`2xx` pre-check → `Error` → `retry()`.
**Последствие.** Детерминированная ошибка валидации (на практике недостижимая) зациклит прогоны.

## J. UX

### J1. Модальный неотменяемый `LoadingDialog` во время sync — HIGH
**Доказательство.** `if (state.isScanning || state.isSyncing) LoadingDialog()` (`FilesScreen.kt:85-87`); `Dialog(onDismissRequest = {})` (`LoadingDialog.kt:17`).
**Последствие.** Пока выполняется one-time sync (observer срабатывает через 3 с после каждого фото), вкладка Files закрыта неотменяемым окном поверх всего приложения, включая нижнюю навигацию. Начальная синхронизация большой библиотеки блокирует приложение на всё время прогона.

### J2. Диалог с ответом сервера при каждом запуске — LOW
**Доказательство.** `NetworkViewModel.init` → `testConnection()` → `message` → `showDialog`.
**Последствие.** Навязчивый диалог «All good! Permit all connection» на старте.

### J3. Устаревший текст и нет выхода после окончательного отказа в разрешении — LOW
**Доказательство.** «Разрешение будет запрошено на следующем этапе.» (`FilesScreen.kt:174`); TODO перехода в настройки (`:57`).
**Последствие.** Пользователь не понимает, как включить синхронизацию.

### J4. Статус синхронизации неполон — LOW
**Доказательство.** `SyncStatusStore` в памяти; `timestamp` не отображается; periodic не отражается; `PermissionDenied` не пишет статус.
**Последствие.** Пользователь не видит, когда была последняя успешная синхронизация.

### J5. Сырые сообщения сервера, смешение языков — LOW
**Доказательство.** `ApiErrorParser` → UI; английские метки и счётчики рядом с русскими текстами.
**Последствие.** Непоследовательный UX.

### J6. Навигация накапливает стек и пересоздаёт ViewModel — LOW
**Доказательство.** `navigate(route) { launchSingleTop; restoreState }` без `popUpTo(saveState = true)` (`BottomNavigationBar.kt:31-34`).
**Последствие.** **[INFERRED]** Повторные переходы создают новые записи back stack и новые `AuthViewModel` (каждый делает `verifySession`, то есть сетевой запрос).

### J7. Список без миниатюр, вся таблица в UI-state — LOW
**Доказательство.** `FilesUiState.files = List<MediaFile>` из `getAll()`.
**Последствие.** Для пользователя это технический монитор, а не галерея; на больших библиотеках растут память и время рекомпозиции.

## K. Maintainability

### K1. Нет автотестов — MEDIUM
**Доказательство.** 13.
**Последствие.** Изменения state machine, миграций и retry-логики не защищены.

### K2. SQL-литералы вместо параметров enum — LOW
**Доказательство.** `'FAILED'`, `'PENDING'`, `'SYNCED'` в `MediaFileDao`.
**Последствие.** Переименование enum ломает запросы без ошибки компиляции.

### K3. Мёртвый и неиспользуемый код — LOW
**Доказательство.** `LOCAL_DELETED`, `FailureReason.isRetryable`, `JwtUtils.isExpired`, `TokenStorage.getAccess/getRefresh/saveAccess`, 5 DAO-методов, `FilesViewModel.clearMessages`, `catch (HttpException)` в `ServerRepository`, ветка `leftPending > 0`.
**Последствие.** Вводит в заблуждение о фактических возможностях.

### K4. Устаревшие комментарии — LOW
**Доказательство.** `MediaFileStatus.FAILED` «будет повтор»; `AuthViewModel.kt:115` «очищает токены в обоих исходах»; `SyncResult` «Отдельного UI нет»; `FilesViewModel.kt:35, 42` про кнопки; `ContentUriRequestBody` «не загружая файл целиком в память».
**Последствие.** Комментарии расходятся с кодом.

### K5. Сборочный долг — LOW
**Доказательство.** Каталог версий расходится с `app/build.gradle.kts`; лишний `app/AndroidManifest.xml`; `androidx.compose.compiler:compiler:1.5.12` как `implementation` при Kotlin 2.0 compose-плагине; смешение Java/Kotlin; `lifecycle-viewmodel-compose 2.7.0` против `lifecycle-runtime 2.8.1`.
**Последствие.** Путаница при обновлении зависимостей.
