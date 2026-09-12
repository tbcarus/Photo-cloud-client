# PhotoCloud Android — текущее состояние

PhotoCloud Android автоматически отправляет фотографии устройства на настроенный HTTP-сервер. Источник — изображения MediaStore в `DCIM/` и его подпапках. Клиент ведёт локальный индекс и очередь в Room, вычисляет SHA-256, проверяет наличие содержимого в серверной CAMERA и загружает недостающее.

Фактическая модель — одностороннее копирование device → server. Клиент не читает серверную галерею, не скачивает файлы и не сверяет уже синхронизированные записи с удалениями или перемещениями на сервере. Удаление локального фото убирает строку из индекса, но не вызывает удаление серверной копии.

## Устройство клиента

| Область | Текущее устройство |
| --- | --- |
| Сборка | Один Gradle module `:app`; Kotlin 2.0.21, Java Retrofit interfaces/requests; minSdk 29, target/compileSdk 35, JVM 17 |
| UI | Jetpack Compose Material3, Navigation Compose, ViewModel и StateFlow |
| Связи | Hilt; конкретные repositories; отдельного domain/use-case module нет |
| Локальная БД | Room 2.7.0, `photo_cloud.db`, version 3; одна таблица `media_files`, 14 полей |
| Настройки | DataStore Preferences `settings`: IP и порт |
| Сессия | Одна пара access/refresh в EncryptedSharedPreferences `secure_tokens`, ключ через Android Keystore |
| Сеть | Retrofit 2.11.0/Gson; OkHttp 4.12.0; plain и auth clients; синхронный `Call.execute()` |
| Фон | WorkManager 2.9.0: один SyncWorker, отдельные one-time и periodic requests; ContentObserver |

Локальная identity — `MediaStore._ID`, записанный как `mediaStoreId`. В Room нет account ID, server URL, folder ID, volume или device namespace. `serverFileId` появляется только из успешного upload response с положительным `id`. Pre-check меняет статус на `SYNCED`, не заполняя ID. Поэтому `SYNCED` с `serverFileId=null` — штатное состояние, а не достаточное доказательство повреждения БД.

## Основной поток

Перед локальными стадиями SyncWorker требует непустой in-memory URL и успешный public `GET /api/v1/test`. Затем он возвращает подходящие `FAILED(CHECKSUM_IO)` в очередь, выполняет полный MediaStore scan, хеширование, CAMERA lookup, checksum pre-check и upload.

Scan вставляет новые ID, обновляет метаданные, сбрасывает checksum/status/server ID при изменении size или mtime и физически удаляет невидимые в успешном snapshot строки. Он не различает физическое удаление фото, выход из DCIM и сужение видимости.

Checksum обрабатывает `PENDING` партиями 100; pre-check — до 500 строк с дедупликацией checksum; upload выбирает партии 20, отправляя файлы последовательно. Это размеры выборок, а не предел числа файлов за worker.

CAMERA разрешается через root → direct children → `folderType == "CAMERA"`. Android её не создаёт отдельным вызовом. При отсутствии CAMERA pre-check переводит готовые строки в upload без exists-запроса. Upload разрешает CAMERA повторно; отсутствие **или ошибка** lookup приводит к отсутствию `folderId` в multipart. Серверный default target определяется сервером по фактическому типу байтов; детали — [17](17-server-client-consistency.md).

Upload принимает любой 2xx с `id>0`, включая HTTP 200 existing. Он сохраняет ID, но не сравнивает ответные checksum, size, MIME или folderId с локальными значениями. Неизменный URI не означает неизменные байты между hashing и отправкой.

## Сессия и выполнение в фоне

Login сохраняет непустую пару токенов. AuthInterceptor добавляет Bearer из текущего storage; TokenAuthenticator при 401 сериализует refresh и допускает один auth replay. Refresh использует plain client без access header, сохраняет прежний refresh и новый access. Любой завершившийся non-2xx refresh очищает пару; network exception и 2xx без body/access её не очищают.

Logout очищает токены после полученного HTTP response, включая ошибочный. Исключение до этой точки может оставить пару. Logout требует настроенный URL, не очищает Room и не отменяет one-time work.

Periodic `photo_sync_periodic` имеет интервал 1 час, UPDATE, CONNECTED и battery-not-low. Observer с debounce 3 секунды ставит `photo_sync` с KEEP и CONNECTED. Разные unique names допускают пересечение worker; mutex защищает отдельные checksum/pre-check/upload стадии, но не весь pipeline, scan или retry restore.

В новом процессе BaseUrlProvider пуст. Сохранённые IP/port восстанавливает NetworkViewModel при создании UI; App и Worker этого не делают. Headless worker останавливается на URL gate до восстановления адреса. Observer также существует только в памяти процесса. Точное исполнение после reboot, force-stop и ограничений ОС — [OPEN](19-open-questions.md).

## Пользовательский интерфейс

Пять вкладок доступны независимо от сессии. Network — стартовая: IPv4/port и Test. Login содержит регистрацию, вход, Test Auth; после входа — JWT subject и Logout. Files — текстовый список Room, счётчики, результат scan и общий итог sync. Settings и отдельная Profile — STUB.

Ручных Sync now, retry FAILED, cancel или byte progress нет. Files показывает модальный неотменяемый loader при UI scan или RUNNING one-time sync; periodic в этот флаг не входит. Общий SUCCESS может сосуществовать с FAILED и оставшимися CHECKSUM_READY.

## Границы возможностей

Реализованы image discovery, локальное хранение, SHA-256, folder pre-check, single-file upload, приём duplicate 200, причины FAILED и восстановление HASHING/UPLOADING на входе соответствующих стадий.

PARTIAL: автономное background execution, session lifecycle, обработка permissions и retry. Для CHECKSUM_IO допускаются три накопленные неуспешные попытки с eligibility cooldown 5 минут; отдельного таймера на cooldown нет. Прочие FAILED автоматически не возвращаются. Transient upload возвращается в очередь и обрывает прогон, что может задерживать старые файлы.

NOT PRESENT: video scan, server browsing/download/mutations, remote reconciliation, account/server isolation, resumable upload, пользовательские настройки sync и product-level automated tests. Два существующих теста — шаблонные.

BODY HTTP logging включён в обоих клиентах во всех builds; password UI не маскирует ввод. URL формируется только как HTTP IPv4/port. Runtime эффект location redaction и фактическая память/вывод logger не измерялись. Риски, без назначения исправлений, сведены в [18](18-risks.md).
