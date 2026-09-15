# Testing current state

Product-level automated tests — **NOT PRESENT**. Существуют два шаблонных теста:

| Test | Framework / assertion | Покрытие |
| --- | --- | --- |
| ExampleUnitTest.addition_isCorrect | JUnit4; 2+2=4 | Шаблон инфраструктуры |
| ExampleInstrumentedTest.useAppContext | AndroidJUnit4; packageName=ru.tbcarus.photo_cloud_client | Application ID при instrumented запуске |

Объявлены JUnit 4.13.2, AndroidX ext junit 1.1.5, Espresso 3.5.1 и Compose ui-test-junit4 1.6.6. Наличие зависимостей не означает наличие UI/product tests.

Mock/fake fixtures, MockWebServer, Room migration tests, WorkManager test helpers, coroutines-test, Robolectric, Hilt test setup и actual Compose scenarios отсутствуют в сведённом тестовом inventory. Состояние CI-конфигурации установлено только Audit A: CI отсутствует. Audit B этот вопрос не устанавливает; в ходе consolidation CI отдельно не перепроверялся.

| Область | Product coverage |
| --- | --- |
| Auth/token persistence/refresh/logout/concurrent session changes | Отсутствует |
| Network URL restore/invalid port/server switch/error parser | Отсутствует |
| Room schema/migrations/queries/status transitions | Отсутствует |
| MediaStore permissions/partial visibility/stale deletion/metadata fallback | Отсутствует |
| SHA-256/checksum errors/cooldown/count restoration | Отсутствует |
| CAMERA bootstrap/pre-check partition/batch/duplicate identity | Отсутствует |
| Multipart upload/duplicate200/invalid ID/413/changed bytes | Отсутствует |
| Cold worker/reboot/KEEP/concurrent requests/cancellation | Отсутствует |
| UI permission flow/password/LoadingDialog/status meaning | Отсутствует |
| Android ↔ server end-to-end | Отсутствует |

Тестовый inventory сведён из обоих аудитов без повторного чтения tests или нового test audit. В ходе консолидации tests/build/device/live HTTP не запускались и не менялись. Успешная сборка или product test pass не заявляются.

Из текущего evidence нельзя получить coverage percentage, throughput/battery budget, crash-free rate, максимальную библиотеку или device compatibility matrix. Неизвестные runtime результаты и требуемый объём эксплуатации отделены в [19](19-open-questions.md).
