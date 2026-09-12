# Verified Android As-Is Specification — PhotoCloud

- Version: `PhotoCloud Android As-Is v1`
- Status: `FROZEN`
- Production revision: `2b9ab698c2068d1e20eeb53aa7547557c3e17b54`
- Review: `PASS_WITH_MINOR_FIXES → all REV-AND-001…006 closed`
- Server baseline: `PhotoCloud Server As-Is v1 — FROZEN`
- Freeze record: [22-freeze-record.md](22-freeze-record.md)

Дата консолидации: 2026-09-12.

Пакет описывает фактический Android-клиент `ru.tbcarus.photo_cloud_client`: локальную модель, работу с фотографиями, HTTP-вызовы, сессию, фоновые задания и интерфейс. Это клиентская основа для будущей System As-Is v1.

Production revision: `2b9ab698c2068d1e20eeb53aa7547557c3e17b54`. Рабочий HEAD при консолидации: `a5f4b2ee414bbe247089a096c0a10d6b061809be`; Git-сравнение production/config путей не выявило отличий от revision аудитов. Приложение, tests и Gradle не изменялись.

Единственный источник серверного поведения — [PhotoCloud Server As-Is v1 — FROZEN](../server-as-is/README.md). Этот пакет распакован из предоставленного server-as-is.zip без изменения содержимого. Его существующая серверная запись freeze не является freeze Android.

Обычный текст означает AS-IS. IMPLEMENTED означает наличие активного механизма в указанном объёме, а не доказанный успех на устройстве. PARTIAL — механизм с существенными ограничениями; STUB — доступная заглушка; UNUSED — объявленный элемент без активного использования; NOT PRESENT — отсутствующая возможность; OPEN — нерешённый вопрос или предел доказательности.

Начало чтения: [сводка](00-android-as-is-summary.md), [pipeline](08-sync-pipeline.md), [state model](09-state-model.md), [интеграция](17-server-client-consistency.md). Стабильные capability ID находятся в [матрице](16-feature-matrix.md).

## Состав

| Документ | Содержание |
| --- | --- |
| [00](00-android-as-is-summary.md) | Сводка клиента |
| [01](01-system-boundary.md) | Границы ответственности |
| [02](02-architecture.md) | Архитектура и зависимости |
| [03](03-local-data-model.md) | Локальные сущности и identity |
| [04](04-room-database.md) | Room, запросы, миграции |
| [05](05-media-discovery.md) | MediaStore и локальные изменения |
| [06](06-network-api.md) | Десять используемых HTTP-операций |
| [07](07-auth-session.md) | Login, refresh, logout и сессия |
| [08](08-sync-pipeline.md) | Стадии синхронизации |
| [09](09-state-model.md) | Persisted и runtime state |
| [10](10-background-execution.md) | WorkManager, observer, lifecycle |
| [11](11-errors-retry-recovery.md) | Ошибки и восстановление |
| [12](12-ui-current-state.md) | Пользовательские экраны |
| [13](13-configuration.md) | Константы и настройки |
| [14](14-security-privacy.md) | Защита данных и логирование |
| [15](15-testing-current-state.md) | Текущее тестовое покрытие |
| [16](16-feature-matrix.md) | Android capability ID |
| [17](17-server-client-consistency.md) | Сверка с frozen сервером |
| [18](18-risks.md) | Риски и последствия |
| [19](19-open-questions.md) | Нерешённые вопросы |
| [20](20-verification-log.md) | Только обращения к Android-коду |
| [21](21-source-traceability.md) | Источники, reconciliation, coverage |
| [22](22-freeze-record.md) | Android As-Is v1 — Freeze Record |

## Пределы проверки

Основа — полные пакеты Android Audit A и B, а не новый аудит repository. Код использован по 18 ограниченным verification questions. Реестр источников и разрешённых противоречий находится в 20–21. Серверный production code не читался.

Build, tests, emulator/device и live HTTP не запускались. Доказанные ветки кода отделены от условных последствий гонок и неизвестных свойств конкретного runtime. Производительность, фактические данные установок, доставка событий ОС и состояние развёрнутого сервера не удостоверяются этим пакетом.
