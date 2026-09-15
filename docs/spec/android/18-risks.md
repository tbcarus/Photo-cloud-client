# Android risks

Severity описывает последствия при указанных условиях: CRITICAL — непосредственная широкая утрата контроля/данных; HIGH — существенная потеря backup, согласованности или конфиденциальности; MEDIUM — ограниченные сбои/эксплуатационные последствия; LOW — локальные ограничения. Это единая шкала пакета, а не максимум severity двух аудитов. Runtime hypotheses не объявлены воспроизведёнными инцидентами.

Фактические отсутствующие product capabilities перечислены в16; само неиспользование более широкого server API здесь не объявлено дефектом. Evidence A/B обозначает архив и документ из21; VER — точечную проверку20; SRV — серверную capability. INT — семантический случай17.

| ID | Area | Description | Evidence | Consequence | Severity |
| --- | --- | --- | --- | --- | --- |
| RISK-AND-001 | Background | Новый headless process имеет пустой BaseUrlProvider; Worker не читает DataStore. | VER-AND-001/003 | Backup не доходит до локальных стадий до UI restore. | HIGH |
| RISK-AND-002 | Identity | Room и credentials не разделены по аккаунту/серверу. | VER-AND-005/006; INT-AND-001 | Чужой для текущего scope SYNCED скрывает missing backup; pending/credentials используют новый context. | HIGH |
| RISK-AND-003 | Logging | BODY logging без build guard пропускает password/token bodies. | VER-AND-004/014 | Чувствительные данные доступны логирующему механизму во всех builds. | HIGH |
| RISK-AND-004 | Transport | UI формирует только HTTP IPv4/port. | VER-AND-001/016 | При разрешённом соединении credentials/media передаются без TLS. | HIGH |
| RISK-AND-005 | Password UI | Пароль не маскируется и не очищается в форме после session actions. | VER-AND-005/013 | Ввод виден на экране и остаётся в памяти ViewModel. | MEDIUM |
| RISK-AND-006 | Refresh | Любая5xx refresh очищает pair. | VER-AND-004; INT-AND-004 | Временный server failure теряет локальную session; фон не отменяется автоматически. | HIGH |
| RISK-AND-007 | Logout | Exception до storage.clear может оставить tokens; URL обязателен. | VER-AND-005 | Offline Logout не гарантирует local exit. | HIGH |
| RISK-AND-008 | Scheduling | Authenticator clear и изменение URL/permissions не вызывают reactive reconcile. | VER-AND-001/002/004/005 | Расписание/observer могут расходиться с готовностью сессии. | HIGH |
| RISK-AND-009 | Logout/background | One-time photo_sync не отменяется при logout; auth entry gate отсутствует. | VER-AND-002/003/005 | Ранее поставленная работа продолжает локальные стадии и auth retries. | HIGH |
| RISK-AND-010 | Session concurrency | Refresh lock не охватывает login/logout/URL writes. | VER-AND-004/005/006 | Условный поздний refresh может перезаписать новую session или вернуть старую пару. | HIGH |
| RISK-AND-011 | State concurrency | Scan и стадии обновляют одну row без content version/общего mutex. | VER-AND-007/009/010/011 | Условные status/hash inconsistencies и потеря результата при concurrent DELETE. | HIGH |
| RISK-AND-012 | Retry restore | Два restore могут выбрать один snapshot; reset по ID без old-status guard. | VER-AND-007/008 | Запоздалый reset перезаписывает уже продвинувшееся состояние; PENDING может сохранить hash/ID. | HIGH |
| RISK-AND-013 | Queue | Первый transient upload прерывает newest-first очередь, attempt limit отсутствует. | VER-AND-008/011 | Воспроизводимая ошибка нового файла задерживает более старые pending. | HIGH |
| RISK-AND-014 | Recovery | FAILED кроме eligible CHECKSUM_IO не восстанавливаются; manual retry нет. | VER-AND-008/013; A/B10,15 | Возврат доступа к неизменному файлу сам не выводит его из FAILED. | HIGH |
| RISK-AND-015 | Media visibility | Успешный неполный scan удаляет отсутствующие/пропущенные ID. | VER-AND-009 | Потеря локальной history и повторная индексация; не удаление оригиналов. | HIGH |
| RISK-AND-016 | Byte identity | Size/mtime heuristic и независимые hash/upload streams без response comparison. | VER-AND-007/009/011/012; INT-AND-003 | Незамеченное изменение байтов или local hash, не соответствующий remote content. | HIGH |
| RISK-AND-017 | Upload target | Lookup error в upload превращается в omitted folderId. | VER-AND-010/011/018; INT-AND-002; SRV-FILE-002 | При server detection вне IMAGE/VIDEO и CAMERA pre-check возможен SYNCED объекта в FILES. | HIGH |
| RISK-AND-018 | Remote state | SYNCED не перепроверяется по server events. | A/B01,08,09,19; VER-AND-007/010 | Remote delete/move может оставить бессрочно устаревший local status. | HIGH |
| RISK-AND-019 | Backup evidence | Existing и duplicate200 не проверяют/не чинят server bytes. | VER-AND-010/011; SRV-SYNC-002, SRV-FILE-003 | Local SYNCED возможен при логической записи с недоступными physical bytes. | HIGH |
| RISK-AND-020 | Media privacy | ACCESS_MEDIA_LOCATION/setRequireOriginal отсутствуют. | VER-AND-012/014; A07§5.1 | Возможен redacted stream вместо оригинала; фактический GPS effect OPEN-AND-003. | MEDIUM |
| RISK-AND-021 | Upload length | contentLength берётся из SIZE при size>0. | VER-AND-012 | Несовпадение metadata/stream может давать повторяемый IO failure; runtime OPEN-AND-005. | MEDIUM |
| RISK-AND-022 | Upload memory | Streaming RequestBody проходит BODY logger. | VER-AND-012/014; A14§3.1, B14 | Возможны дополнительное чтение/буферизация и рост RAM/log output; объём не измерен. | MEDIUM |
| RISK-AND-023 | Network policy | Cleartext XML явно описывает emulator/localhost, без base-config. | VER-AND-014 | Доступ к другому LAN host не подтверждён runtime; ошибочный config может блокировать соединение. | MEDIUM |
| RISK-AND-024 | Backup/Keystore | Backup exclusions и storage corruption/restore recovery отсутствуют. | VER-AND-006/014 | Перенос encrypted prefs/Room может дать неработоспособное восстановление или неверную local identity. | MEDIUM |
| RISK-AND-025 | Cancellation | Blocking IO не связан с Call.cancel; cancellation не выделена из catch Exception. | VER-AND-003/008/011/012/018 | Запрос отмены не гарантирует немедленного окончания сетевых/file side effects. | HIGH |
| RISK-AND-026 | KEEP/event loss | KEEP подавляет follow-up при unfinished one-time; observer только в памяти. | VER-AND-002 | Новое фото после scan может ждать следующего события/periodic/backoff; exact latency неизвестна. | MEDIUM |
| RISK-AND-027 | Completion UI | SUCCESS не требует отсутствия FAILED/unchanged; PermissionDenied не обновляет outcome. | VER-AND-003/008/010/011 | Карточка может восприниматься как более полное подтверждение backup, чем фактический прогон. | MEDIUM |
| RISK-AND-028 | Loading UI | Files использует non-dismissible modal loader для scan/one-time RUNNING. | VER-AND-002/013 | Взаимодействие с Files и нижней навигацией блокируется на время отображения диалога. | MEDIUM |
| RISK-AND-029 | Persistence | Destructive fallback включён; миграции не имеют product tests. | VER-AND-015; A/B04,13 | При применении fallback локальная queue/history теряется; исторические runtime outcomes не подтверждены. | HIGH |
| RISK-AND-030 | Historical failures | После2→3 старые FAILED имеют null reason/time. | VER-AND-008/015 | Auto retry их не выбирает; user retry отсутствует. | MEDIUM |
| RISK-AND-031 | Scale | Полный snapshot, поэлементные SQL updates, без дополнительных indexes; UI получает всю таблицу. | A/B04,07,12; VER-AND-007/009 | RAM/DB/UI нагрузка растёт с библиотекой; практический предел не измерен. | MEDIUM |
| RISK-AND-032 | Timestamps | При отсутствии всех dates now используется и как lastModified. | VER-AND-009 | Повторные scan могут снова сбрасывать hash/status неизменных байтов. | LOW |
| RISK-AND-033 | Network input | Port лишь nonempty; создание Retrofit для ping вне try/Result. | VER-AND-001/016/018 | Invalid URL exception может оставить UI loading и незавершённую coroutine action. | MEDIUM |
| RISK-AND-034 | Permission/UI lifecycle | LaunchedEffect(Unit), нет onResume recheck/settings action. | VER-AND-013; A/B07,12 | Grant/revoke/config change может не получить ожидаемого UI bootstrap до reentry. | MEDIUM |
| RISK-AND-035 | Observability | Server error id/code/detail, retry count и время не доступны как полная история в UI. | VER-AND-003/017; A/B14 | Фоновую ошибку сложно связать с server logs; periodic running не виден. | MEDIUM |
| RISK-AND-036 | Testing | Только два template tests; product automated coverage нет. | A/B13,15; без чтения tests | Корректность критических session/state/background flows не закреплена regression tests. | HIGH |
| RISK-AND-037 | Configuration/maintenance | Смешанные direct versions, неиспользуемые helpers и устаревшие comments/дополнительный manifest. | A/B02,11,14,16,18 | Документированные defaults/comments могут вводить сопровождение в заблуждение; incompatibility не доказана. | LOW |
| RISK-AND-038 | Traffic/size | CONNECTED не Wi-Fi-only; client max-size guard отсутствует. | VER-AND-002/011; SRV-STORAGE-003 | Крупное фото читается/отправляется до серверного отказа, включая metered network. | MEDIUM |
| RISK-AND-039 | Local-first latency | Public ping предшествует restore/scan/hash в Worker. | VER-AND-003 | При server outage индекс и hash через background path не обновляются; UI scan отдельный. | LOW |
