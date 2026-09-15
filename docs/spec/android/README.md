# PhotoCloud Android Specification

Это living specification текущего компонента. Она описывает актуальное подтверждённое техническое поведение Android-клиента `ru.tbcarus.photo_cloud_client` — локальную модель, работу с фотографиями, HTTP-вызовы, сессию, фоновые задания и интерфейс — и обновляется вместе с осознанными изменениями поведения и реализации.

При изменении поведения сначала принимается решение на соответствующем верхнем уровне (для системного поведения — в [system/](../../../../system-spec/docs/spec/system/README.md)), затем обновляются component specification и code. Направление: `system/ → server/ + android/ → production code`. Расхождение spec и code требует явного `CHECK`. Существующие OPEN и риски сохраняются до их явного пересмотра.

Git хранит историю предыдущих состояний, включая прежний freeze. Отдельная неизменяемая копия этой спецификации не поддерживается.

Источник серверного поведения — [PhotoCloud Server Specification](../../../../PhotoCloudServer/docs/spec/server/README.md). Происхождение подтверждённых фактов и revision первоначальных аудитов указаны в [реестре источников](21-source-traceability.md).

Обычный текст означает AS-IS. IMPLEMENTED означает наличие активного механизма в указанном объёме, а не доказанный успех на устройстве. PARTIAL — механизм с существенными ограничениями; STUB — доступная заглушка; UNUSED — объявленный элемент без активного использования; NOT PRESENT — отсутствующая возможность; OPEN — нерешённый вопрос или предел доказательности.

Начало чтения: [сводка](00-android-summary.md), [pipeline](08-sync-pipeline.md), [state model](09-state-model.md), [интеграция](17-server-client-consistency.md). Стабильные capability ID находятся в [матрице](16-feature-matrix.md).

## Состав

| Документ | Содержание |
| --- | --- |
| [00](00-android-summary.md) | Сводка клиента |
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
| [17](17-server-client-consistency.md) | Согласованность с Server Specification |
| [18](18-risks.md) | Риски и последствия |
| [19](19-open-questions.md) | Нерешённые вопросы |
| [20](20-verification-log.md) | Только обращения к Android-коду |
| [21](21-source-traceability.md) | Источники, reconciliation, coverage |

## Пределы проверки

Основа — полные пакеты Android Audit A и B, а не новый аудит repository. Код использован по 18 ограниченным verification questions. Реестр источников и разрешённых противоречий находится в 20–21. Серверный production code не читался.

Build, tests, emulator/device и live HTTP не запускались. Доказанные ветки кода отделены от условных последствий гонок и неизвестных свойств конкретного runtime. Производительность, фактические данные установок, доставка событий ОС и состояние развёрнутого сервера не удостоверяются этим пакетом.
