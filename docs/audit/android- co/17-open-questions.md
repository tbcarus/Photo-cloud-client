# 17. Open questions

[INFERRED] Здесь только требования/эксплуатационные сведения, которых нельзя достоверно вывести из исходников. Факты клиента не подменяются предполагаемыми ответами. Уже разрешённые по коду вопросы server handoff перечислены в19.

| Вопрос | Установленное As-Is | Чего не хватает |
| --- | --- | --- |
| Должен ли backup гарантированно работать без открытия UI после reboot/process death? | BaseUrl восстанавливает NetworkViewModel; headless worker начинает пустым | Product reliability/SLA и acceptance scenarios |
| Это резервное копирование или двусторонняя синхронизация? | Upload-only DCIM Images + local index | Политика remote/local изменений |
| Какие папки и типы обязаны включаться? | DCIM/% Images; video нет | Нужны ли Pictures/Screenshots/Downloads/video/RAW/vendor-specific folders |
| Должны ли локальные удаления удалять remote copy? | Room row удаляется, server нет | Retention/backup policy |
| Что делать после remote delete/move? | SYNCED никогда повторно не сверяется | Suppression, повторный upload или отражение удаления |
| Что считать user-visible «синхронизировано»? | Existing hash→SYNCED без file ID/проверки bytes | Нужны ли ID, physical availability, полный audit trail |
| Как должны вести себя несколько аккаунтов и серверов? | Одна token pair и общая DB/queue | Разрешён ли switch; что сохранять/очищать/переносить |
| Что должен означать logout при offline/timeout? | IO failure может оставить local tokens | Local exit vs подтверждённый server revoke UX |
| Должен ли logout/reset/ban действовать немедленно? | Server access живёт до exp; Android blanket guarantee нет | Security product expectation |
| Какой transport/deployment поддерживается? | IPv4 HTTP UI; explicit emulator/localhost cleartext XML | HTTPS/domain/IPv6, trusted network и актуальный server revision |
| Разрешён ли cellular/metered/roaming sync? | CONNECTED, periodic batteryNotLow | Wi-Fi-only/charging/user toggle требования |
| Каков приемлемый расход батареи/трафика и размер библиотеки? | Полный scan + последовательные batches, infinite stage loops | Профили устройств, объёмы, performance targets |
| Когда и как повторять permanent/исчерпанные failures? | Manual retry нет; CHECKSUM_IO max3, cooldown5min | User actions, разрешение конфликтов/permission recovery |
| Как показывать отсутствие доступа/selected photos? | One-time launcher, generic card | Поддерживаемые Android/OEM versions и ожидаемая UX partial access |
| Что делать с изменением bytes при upload? | Нет snapshot или сравнения server hash | Product definition/version/history intent |
| Как интерпретировать локальное createdAt и remote capturedAt? | Local epoch ms fallback; server local datetime string, ignored | Timezone/display/sorting semantics общей системы |
| Что делать с нормализованным remote filename/duplicate old name? | Client сохраняет local displayName, response name ignored | Нужен ли unified naming/reconciliation UI |
| Какие metadata нужны пользователю? | Response metadata игнорируется | EXIF/GPS/dimensions/video duration requirements |
| Нужны ли register confirm/password reset deep links в Android? | Register request есть, остального нет | Завершённый account onboarding/recovery journey |
| Как действовать при server existing metadata без physical bytes? | Precheck считает SYNCED, server repair protocol отсутствует | Recovery ownership/операционный процесс |
| Должны ли быть ограничения размера до отправки? | Server413, client предварительно не проверяет100MiB | UX/трафик policy для large file |
| Нужны ли progress/cancel/resume/manual sync controls? | Files — монитор, button нет | UX scope, server capabilities ограничены |
| Какие logs/backup данные допустимы в release? | BODY all builds, allowBackup true/templates | Требования к diagnostics/retention/доступу к logs |
| Какие runtime проверки обязательны перед выпуском? | Product tests отсутствуют | Device/OS matrix, offline/reboot/concurrency/migration acceptance |

[DOCUMENTED] Дополнительные серверные неопределённости — [08-client-relevant-open-questions](<C:/projects/photo-cloud-server/docs/audit/server - co/client-handoff/08-client-relevant-open-questions.md>). Не все они остаются открытыми о коде: bootstrap CAMERA, no rotation, API aliases, retry413 и local identity установлены этим аудитом. Неизвестные продуктовые решения не объявлены ошибками реализации.

