# 00. Executive summary — Android PhotoCloud As-Is

Срез: **2026-09-11**, Android HEAD **2b9ab698c2068d1e20eeb53aa7547557c3e17b54**. Метод: статическое чтение исходников, ресурсов, Gradle и Room schemas; live Android/server execution не выполнялся. Production-код не изменялся.

## Источники и классификация

Android-код — источник истины о клиенте. Серверные сведения обозначены **[DOCUMENTED]** относительно этого аудита, даже если внутри server handoff они помечены CONFIRMED. Использованы все восемь переданных документов из [client-handoff](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff>), срез server 2026-09-10, HEAD 84f7a547aabceac0849baef520605dac7111e797. Их рекомендации и вопросы рассматриваются как входные данные, а не команды на изменение клиента. Отдельного api-contract.md в клиенте и переданной серверной docs-области не найдено.

| Метка | Значение в этом пакете |
| --- | --- |
| [CONFIRMED] | Непосредственно видно в Android-коде/конфигурации; не означает успешный runtime-тест |
| [DOCUMENTED] | Утверждение документа/комментария или серверного handoff |
| [INFERRED] | Вывод из цепочки вызовов; сценарий не воспроизведён |
| [PARTIAL] | Реализована лишь часть возможности/защиты |
| [UNUSED] | Объявление присутствует, активное использование не найдено |
| [INCONSISTENCY] | Расхождение между слоями, кодом/комментарием или клиентом/server semantics |
| [RISK] | Возможное негативное последствие с указанными условиями |

Метка перед вводным абзацем таблицы распространяется на её строки, если строка не имеет собственной метки. NOT FOUND означает отсутствие в обследованном срезе, а не утверждение о внешних компонентах.

## Назначение и готовность

[CONFIRMED] Это клиент автоматического копирования фотографий из **MediaStore.Images с RELATIVE_PATH LIKE DCIM/%** в системную серверную папку CAMERA. Он хранит индекс и очередь в Room, считает SHA-256 и отправляет только отсутствующее по folder-scoped pre-check содержимое. Это не двусторонняя синхронизация библиотеки: чтение серверной галереи, download, remote deletion reconciliation отсутствуют. Источники: [MediaStoreRepository.kt:58](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaStoreRepository.kt:58>), [SyncWorker.kt:47](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:47>), [ChecksumPrecheckRepository.kt:39](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumPrecheckRepository.kt:39>), [FileUploadRepository.kt:51](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/FileUploadRepository.kt:51>).

[CONFIRMED] Один Gradle module :app; Kotlin + Java Retrofit interfaces/requests; Compose Material3, Navigation Compose, ViewModel/StateFlow, Hilt, Room 2.7.0, WorkManager 2.9.0, Retrofit 2.11.0, OkHttp 4.12.0, Gson 2.10.1; minSdk29/targetSdk35, JVM17. Архитектура — presentation + конкретные repositories/infrastructure без отдельного domain/use-case module.

[CONFIRMED] Пять destinations: Network (старт), Login/Auth, Settings, Profile, Files. Network сохраняет IP/port после ping; Auth даёт register/login/test/logout; Files — текстовый монитор локальных фото и статусов. Settings и отдельный Profile — заглушки. Профиль с email и logout реально расположен внутри AuthScreen. [NavigationGraph.kt:19](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/components/NavigationGraph.kt:19>), [AuthScreen.kt:25](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt:25>), [FilesScreen.kt:94](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:94>).

[CONFIRMED] Единственная Room entity MediaFile, версия DB3, миграции 1→2→3, сохраняются URI, metadata, SHA-256, статус, serverFileId, failureReason/retryCount/lastFailureAt. Токены зашифрованы в secure_tokens preferences; IP/port — DataStore settings. Последний итог sync существует только в памяти.

[PARTIAL] Основной upload pipeline реализован, в том числе первый upload без существующей CAMERA, потоковое RequestBody, recovery HASHING/UPLOADING и ограниченный retry checksum IO. Фоновая работа ставится hourly periodic и по ContentObserver с debounce3s. Есть реальные ограничения cold start, отмены и согласованности состояния; готовность к надёжному автономному backup этим кодом и двумя шаблонными тестами не подтверждается.

## Ключевые результаты

| Область | Фактический результат |
| --- | --- |
| [CONFIRMED] API | Все 10 объявленных Retrofit calls совпадают с действующими server routes и базовыми wire shapes |
| [PARTIAL] Auth | Refresh без rotation согласован; есть lock и один auth replay. Logout с IOException не гарантирует local clear |
| [RISK] Account/server scoping | Room и токены не имеют привязки к server/account; переключение может смешать очередь, статусы и credentials |
| [RISK] Cold background process | BaseUrlProvider пуст по умолчанию, восстанавливается только NetworkViewModel; Worker в новом процессе может бесконечно откладываться до открытия UI |
| [RISK] Секреты | BODY logging включён для обоих клиентов во всех builds; login/refresh bodies содержат пароль/токены |
| [RISK] Concurrent pipelines | Разные unique names one-time/periodic; mutex только по отдельным стадиям, scan без mutex/общей транзакции |
| [RISK] Изменение bytes | Upload подтверждает только id, не сверяет ответный hash/size; файл может измениться после вычисления checksum |
| [PARTIAL] Error recovery | FAILED auto-retry только CHECKSUM_IO, максимум 3 неуспешных checksum-попытки, cooldown5min; manual retry отсутствует |
| [PARTIAL] Remote identity | Existing pre-check→SYNCED без serverFileId; последующего заполнения ID/list-sync нет |
| [CONFIRMED] Deletion | Исчезновение из scan→DELETE Room; LOCAL_DELETED не записывается; удаление сервера не вызывается |
| [CONFIRMED] Testing | Только addition_isCorrect и useAppContext; продуктовые сценарии не покрыты |

Доказательства и severity: [16-known-gaps-and-risks.md](<C:/projects/Photocloudclient/docs/audit/android/16-known-gaps-and-risks.md>), protocol/DTO comparison: [19-server-client-consistency.md](<C:/projects/Photocloudclient/docs/audit/android/19-server-client-consistency.md>), полная карта и coverage checklist: [18-code-map.md](<C:/projects/Photocloudclient/docs/audit/android/18-code-map.md>).

## Навигация по пакету

00 — выводы/метод; 01 — система; 02 — архитектура; 03 — модели; 04 — Room; 05 — API; 06 — auth/security; 07 — локальные медиа; 08 — процессы; 09 — state machines; 10 — errors/retry; 11 — configuration; 12 — background; 13 — testing; 14 — observability; 15 — feature matrix; 16 — gaps/risks; 17 — вопросы; 18 — code map; 19 — server/client consistency. Нумерация 00–18 соответствует запрошенному server audit; 07 зеркалит server file storage.

