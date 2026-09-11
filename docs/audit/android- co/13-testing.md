# 13. Testing and verification

## Найденные тесты

[CONFIRMED] Полная инвентаризация app/src/test и app/src/androidTest обнаружила только два template tests:

| Test | Type | Assert | Что подтверждает по содержимому |
| --- | --- | --- | --- |
| ExampleUnitTest.addition_isCorrect | JVM JUnit4 | assertEquals(4,2+2) | Шаблон инфраструктуры, не логику приложения |
| ExampleInstrumentedTest.useAppContext | AndroidJUnit4 | targetContext.packageName == ru.tbcarus.photo_cloud_client | Application ID на instrumented runtime при запуске |

Источники: [ExampleUnitTest.kt:12](<C:/projects/Photocloudclient/app/src/test/java/ru/tbcarus/photo_cloud_client/ExampleUnitTest.kt:12>), [ExampleInstrumentedTest.kt:16](<C:/projects/Photocloudclient/app/src/androidTest/java/ru/tbcarus/photo_cloud_client/ExampleInstrumentedTest.kt:16>). Custom test fixtures, fake repositories/DAO, MockWebServer, Room migration tests, WorkManager test helpers и actual Compose tests не найдены. Test dependencies не означают покрытие.

## Coverage matrix

| Subsystem | Tests found | Covered scenarios | Important missing cases |
| --- | --- | --- | --- |
| [CONFIRMED] Build/test scaffolding |2 template tests | Arithmetic/package name | Product behavior |
| [CONFIRMED] AuthViewModel | NOT FOUND | — | login error, token flow, verify result, logout transport failure |
| [CONFIRMED] NetworkViewModel | NOT FOUND | — | bad port/URL, DataStore error, URL change+tokens, cold restore |
| [CONFIRMED] FilesViewModel/UI | NOT FOUND | — | permission bootstrap/revoke, periodic running, failed visibility |
| [CONFIRMED] Auth/network repositories | NOT FOUND | — | concurrent401, refresh500 vs401, logout/refresh race, parser non-JSON |
| [CONFIRMED] Room/DAO | NOT FOUND | — | every transition, conflicting writers, delete0-row update, historical FAILED |
| [CONFIRMED] Migrations | NOT FOUND | — |1→2→3 with data, fresh vs migrated defaults, unsupported versions |
| [CONFIRMED] MediaStore scan | NOT FOUND | — | DCIM scope, null cursor, malformed row, move, partial grant, changed timestamps |
| [CONFIRMED] Checksum | NOT FOUND | — | known SHA256 vectors, empty/large stream, IO recovery, count/cooldown, cancellation |
| [CONFIRMED] Precheck | NOT FOUND | — | max500, same hash many local IDs, missing CAMERA, incomplete partition, no progress |
| [CONFIRMED] Upload | NOT FOUND | — | duplicate200,413, invalid id, streamed body replay, file changes after hash |
| [CONFIRMED] Worker/scheduler | NOT FOUND | — | headless start/reboot, concurrent periodic/one-time, constraints, recovery, logout |
| [CONFIRMED] Integration Android/server | NOT FOUND | — | Real protocol, metadata mismatch, remote deletion, account isolation |
| [CONFIRMED] UI/navigation | NOT FOUND | — | Tabs/back stack, password visibility, empty state, permission permanent denial |

## Что проверено в рамках аудита

[CONFIRMED] Выполнены read-only inventory/search, полное чтение Android Kotlin/Java source и конфигурации, Room schema2/3, обоих manifest files, обоих тестов; поиск endpoints/entities/DAOs/ViewModels/Workers/TODO/callers; сопоставление восьми server handoff документов. В отчётах приведены ссылки на конкретные source lines; coverage checklist —18.

[CONFIRMED] **Gradle build/tests, emulator/device tests и live API requests в этом аудите не запускались.** Требование пользователя допускает создание только отчётов; сборка создала бы build/generated/test artifacts. Новые тесты и production fixes не добавлялись. Поэтому BUILD PASS/TEST PASS не заявляются; даже успешный шаблонный тест не подтвердил бы backup correctness.

[PARTIAL] Static evidence не доказывает работоспособность merged manifest/DI generation, migration execution, OEM scheduling, реального network-security policy для LAN, permission behavior Android14+ или end-to-end upload. Такие пункты в risks отмечены как условные/непроверенные, а не воспроизведённые ошибки.

## Неизвестные runtime характеристики

[INFERRED] Из кода нельзя получить coverage percentage, crash-free rate, throughput/battery cost, max practical library size, фактически установленный server revision или успешность хранения/восстановления Keystore. Нет воспроизводимых сценарных отчётов, подтверждающих эти показатели. Runtime acceptance criteria — открытый вопрос17, не результат двух template tests.

## Контроль артефактов аудита

[CONFIRMED] Созданы ровно20 отчётов00–19. Проверены локальные ссылки на существование файлов и допустимость номера source line, согласованность числа столбцов Markdown-таблиц и парность Mermaid/code fences. Полная source inventory включает82 Kotlin/Java production файла. Повторное сравнение SHA-256 для110 файлов app/src, app/schemas и gradle не выявило изменений или удалений; git diff tracked files пуст. Это проверка сохранности и структуры отчётов, не runtime test приложения.
