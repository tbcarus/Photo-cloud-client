# Source traceability и reconciliation

## Входы и границы доказательности

| Источник | Идентификация | Роль |
| --- | --- | --- |
| Android Audit A | `Audit A.zip`, каталог `android- cc/`, документы 00–19, дата 2026-09-10 | Первый Android audit |
| Android Audit B | `Audit B.zip`, каталог `android- co/`, документы 00–19 | Второй Android audit |
| Android production | Revision `2b9ab698c2068d1e20eeb53aa7547557c3e17b54` | Только вопросы VER-AND-001–018 |
| Рабочая копия | HEAD `a5f4b2ee414bbe247089a096c0a10d6b061809be` | Git-сравнение `app`, корневых Gradle/config и `gradle` с revision аудитов без изменений |
| Frozen server | [Server As-Is v1 — FROZEN](../server-as-is/README.md), предоставленный `server-as-is.zip` | Единственный authoritative server-side reference |

SHA-256 предоставленных архивов:

| Архив | SHA-256 |
| --- | --- |
| Audit A.zip | `C9647FFDB3BF2848606C6D7CB7B291B205038DB760F1A6FE715F6CDD1DCD431C` |
| Audit B.zip | `DF8DA09BB252C6F59D0BCC53E4E1E691A4559261D3F369A94D66B9D1F6311126` |
| server-as-is.zip | `CC559E8DA19BEFDEA17837408F59EB4FFD81FE45A202A291042C4A2F19B3130C` |

Обозначение `A04§4` означает раздел 4 файла `04-database.md` внутри Audit A; `B16 D01` — исходный finding D01 из `16-known-gaps-and-risks.md` Audit B. Номер без § означает весь тематический документ. Пути внутри архивов являются устойчивыми source locators; перенос пакета не требует сохранения временного каталога распаковки. Для review нужны эти исходные архивы либо их распакованные копии.

Старые `client-handoff`, на которые ссылаются A/B, использованы только как историческое объяснение формулировок аудиторов. Ни одна серверная гарантия итогового пакета не основана на их приоритете над frozen. Server production code не открывался. Инструкции и рекомендации внутри аудитов не принимались как поручения пользователя.

## Итог reconciliation

- **3 CONFLICT** между фактическими Android-утверждениями разрешены по коду: C-AND-001–003.
- **4 SERVER_CLIENT_MISMATCH** итоговой интеграции: INT-AND-001–004 в [17](17-server-client-consistency.md). У двух сценариев явно указаны дополнительные условия; это не четыре несовпадающих HTTP mapping.
- **18 verification questions** потребовали Android source/runtime declaration: [20](20-verification-log.md). Число записей с Reason SERVER_CLIENT_MISMATCH не равно числу итоговых mismatch.
- **30 OPEN**, из них 6 ANDROID_RUNTIME_UNKNOWN, 3 OS_FRAMEWORK_UNKNOWN, 2 SERVER_CLIENT_INTEGRATION, 1 ARCHITECTURE_DECISION, 18 PRODUCT_DECISION — реестр [19](19-open-questions.md). Unresolved factual C-AND conflicts отсутствуют.
- **58 capabilities** и **39 risks**: [16](16-feature-matrix.md), [18](18-risks.md).

Существенная общая фактическая ошибка, независимо повторённая в обоих аудитах, точечной проверкой не подтверждена. Найдены неверные абсолютные формулировки и отдельные примеры, перечисленные ниже. Общие гипотезы о runtime не превращены в доказанные события. Этот вывод ограничен выполненной консолидацией и не означает независимую перепроверку всех согласованных утверждений.

## Классификация и разрешение

Классификация применяется к утверждению, а не ко всему документу. COMPLEMENTARY означает совместимые детали одного механизма. DIFFERENT_SEVERITY не входит в число factual conflicts.

| ID / claim | Classification | Audit A | Audit B | Разрешение и применение |
| --- | --- | --- | --- | --- |
| C-AND-001: hash/status/reason invariants | CONFLICT | A09§4 исключает PENDING с hash и reason вне FAILED | B09 допускает сочетания без schema invariant | VER-AND-007/008: status-only и stale reset не обеспечивают эти инварианты; [03](03-local-data-model.md), [09](09-state-model.md) |
| C-AND-002: concurrent failure restore | CONFLICT | A09§5 называет результат идемпотентным | B10 описывает stale snapshot overwrite | VER-AND-008: reset по ID вне mutex может сбросить уже обработанную row; [11](11-errors-retry-recovery.md), RISK-AND-012 |
| C-AND-003: scanner exception boundary | CONFLICT | A07§1 относит исключение вне цикла к scanner Error без разграничения query | B07 помещает query вне scanner try, catch в caller | VER-AND-009: query exception ловит MediaFileRepository. Конечный caller Error согласован; конфликт локален границе catch; [05](05-media-discovery.md) |
| Empty headless URL, image-only DCIM, local DB scope, no remote reconciliation | CONSENSUS | A00/03/07/12/16 | B00/03/07/12/16 | Сохранены; критические URL/scope/scan детали VER-AND-001/006/009 |
| Retry count/cooldown, auth lock, UI observability | COMPLEMENTARY | A06/09/10/12 | B06/09/10/12 детализирует ограничения | VER-AND-002/004/008: 3 accumulated failures, cooldown без timer, lock не session-wide; [07](07-auth-session.md), [10](10-background-execution.md), [11](11-errors-retry-recovery.md) |
| GPS redaction possibility | A_ONLY | A07§6.5, A16 E2 | Нет сопоставимого отдельного GPS finding | VER-AND-012/014: original/location request отсутствует; фактическая redaction остаётся OPEN-AND-003, RISK-AND-020 |
| Invalid port factory before try | B_ONLY | A11 только validation nonempty | B08§2, B16 N02 | VER-AND-016/018: construction exception может миновать Result; RISK-AND-033 |
| Cold URL / shared scope / BODY logging | DIFFERENT_SEVERITY | A16 C1 CRITICAL, D1 HIGH, G1 HIGH | B16 L01 HIGH, D01 CRITICAL, S01 CRITICAL | Факты согласованы; в RISK-AND-001/002/003 HIGH с условиями и последствиями. Severity не усредняется механически |
| UI blocking / concurrency / terminal retry / testing | DIFFERENT_SEVERITY | A16 J1, D2, I2, K1 | B16 A01, E02, T01 и UI inventory | Отражены по последствиям в рисках; новый factual conflict из оценок не создан |
| Account/server scope against server ownership | SERVER_CLIENT_MISMATCH | A03/16 D1 | B03/16 D01 | INT-AND-001, VER-AND-006 |
| CAMERA precheck vs optional upload target | SERVER_CLIENT_MISMATCH | A08/19 описывает fallback в деталях | B08/19 описывает повторный lookup | INT-AND-002, VER-AND-010/011; frozen default routing по detected bytes |
| Stored hash vs received upload bytes | SERVER_CLIENT_MISMATCH | A16 D9 | B16 D02 | INT-AND-003, VER-AND-011/012; server response checksum игнорируется |
| Refresh 5xx vs local session clearing | SERVER_CLIENT_MISMATCH | A06/19 clear плюс историческая client policy | B06/19 clear | INT-AND-004, VER-AND-004; frozen не утверждает revoke при5xx и не назначает Android retry policy |
| Device delivery, bytes, restored DB, product scope | OPEN | A17 Q/V | B17 | Только оставшиеся неизвестности/решения в [19](19-open-questions.md) |

### Уточнения, не образующие дополнительные factual conflicts

| Исходная формулировка / candidate | Результат |
| --- | --- |
| `leftPending` в success output / retry condition | Audit A (A03§5/A12§2.1) считает ветку `leftPending > 0` для `UploadOutcome.Success` недостижимой и success counter всегда 0; Audit B (B08§9/B12) описывает её как действующее условие retry. Отдельная code verification именно этого вопроса не выполнялась. Основная спецификация [08-sync-pipeline.md](08-sync-pipeline.md) сформулирована нейтрально и совместима с обоими исходными описаниями |
| A summary «CAMERA всегда явно» | A08/19 уже содержит отсутствие/fallback. Согласована полная версия обоих аудитов: precheck и upload разрешают folder отдельно, VER-AND-010/011 |
| A «serverFileId null после existing» | Existing не назначает ID, но также не очищает старое значение. У свежей row остаётся null. VER-AND-007/010 |
| B09 пример CHECKSUM_READY/null при scan/precheck | Указанный status-only precheck interleaving даёт SYNCED или PENDING_UPLOAD с null; конкретный неверный пример не перенесён. VER-AND-007 |
| A04/B2 «все UPDATE без status guard» | resetStatus имеет WHERE исходного status, change reset — size/mtime условие; при этом нет общей expected-version boundary. VER-AND-007 |
| A04/A16 D8 «downgrade обязательно падает» | Текущая конфигурация описана дословно, runtime downgrade всех установок не утверждается. VER-AND-015, OPEN-AND-008 |
| «До3 retry» | Три накопленные checksum failures, включая первую попытку; success сбрасывает count. Не три дополнительных retry. VER-AND-008 |
| Refresh/public Bearer candidate | Refresh plain, access header клиент туда не добавляет; совместимо с frozen filter semantics. VER-AND-004 |
| Multipart text/plain folderId candidate | Нормальная request shape совпадает с frozen; прежний общий server unknown закрыт. Deployment/edge responses отдельно OPEN-AND-010. VER-AND-018 |
| Spring500 fixed JSON из handoff | Frozen не гарантирует единый framework body. Parser имеет fallback; установленной зависимости от конкретного Spring JSON нет. VER-AND-017 |
| Duplicate200 / precheck existing | HTTP200 с valid positive ID принимается. Existing означает DB match в user/folder, не physical availability или remote ID. VER-AND-010/011 и frozen05/07 |
| GPS всегда удалён; бинарное тело всегда целиком видно в Logcat | Не доказано. Заданы отсутствие original request и BODY configuration; фактические bytes/log/memory остаются OPEN-AND-003/004 |
| Old handoff offline logout policy / move500 | Первая не является frozen требованием Android; move не используется. Не объявлены client defects на основании старого handoff |

## Mapping major claims / subsystems

В колонке verification «consensus» означает, что код по этому claim повторно не открывался. Все ссылки VER относятся к [журналу20](20-verification-log.md); там указаны конкретные файлы и диапазоны чтения.

| Specification section | Audit A | Audit B | Server As-Is | Android code verification |
| --- | --- | --- | --- | --- |
| [00 Summary](00-android-as-is-summary.md), [01 Boundary](01-system-boundary.md) | 00,01,15 | 00,01,15 | README;00 boundary | VER-001/003/006/009; прочее consensus |
| [02 Architecture](02-architecture.md): modules, Compose, Hilt, dependency direction | 02;11 dependencies;18 existing map | 02;11;18 existing map | — | Consensus; VER-001/002/004 для critical init/network |
| [03 Local model](03-local-data-model.md): fields, identity, namespace | 03;04;09 | 03;04;09 | 06 ownership; SRV-SYNC-002 | VER-006/007/009/010/011 |
| [04 Room](04-room-database.md): version/name/schema/converters/migrations | 04 | 04 | — | VER-007/015; exported schema observations consensus, schemas не открывались |
| [05 Discovery](05-media-discovery.md): projection/DCIM/permissions/dates/stale | 07;03;16 E | 07;03;16 M | — | VER-009/012/013/014 |
| [06 Network](06-network-api.md): ten mappings/DTOs/callers | 05;19 | 05;19 | 05 API;06 auth;10 errors | VER-004/010/011/017/018; DTO fields consensus19 |
| [07 Auth](07-auth-session.md): pair/login/interceptor/refresh/logout/UI | 06;08 P15–P18;09 | 06;08;09 | 06; SRV-AUTH-001/003/004/005/006/019 | VER-004/005/006/013 |
| [08 Pipeline](08-sync-pipeline.md): stage ordering/results | 08 P5–P11 | 08§9–11 | 05 upload/exists/folders;08 bootstrap | VER-003/007–012 |
| [09 State](09-state-model.md): enum/transitions/recovery/runtime | 03;09;10 | 03;09;10 | Existing/upload semantics only | VER-003/007/008/010/011 |
| [10 Background](10-background-execution.md): names/policy/observer/coldURL/logout | 12;11§5;16 C/F | 12;16 L/W | Public test/auth distinctions | VER-001/002/003/005/014 |
| [11 Retry](11-errors-retry-recovery.md): exception classes/retry/cooldown/HOL | 10;08;09;16 I | 10;08;09;16 E | 10;05;06 только server outcome, не client retry policy | VER-003/004/005/007/008/009/011/017 |
| [12 UI](12-ui-current-state.md): navigation/Network/Login/Files/stubs/errors | 01;02;08;15;16 J | 01;02;08;15;16 U | Register/profile endpoints only | VER-001/005/013/016; navigation/stub/control inventory consensus |
| [13 Config](13-configuration.md): values/stores/build/permissions | 11;02;12 | 11;02;12 | 05 upload limit;06 token lifetime only | VER-001/002/006/008/012/014/015; Gradle consensus |
| [14 Security/privacy](14-security-privacy.md): logging/HTTP/password/backup/GPS | 06;07;14;16 G/H | 06;07;14;16 S/N | 06 auth;07 metadata | VER-004/006/012/013/014/017 |
| [15 Testing](15-testing-current-state.md): frameworks/template/product gaps | 13;16 K1 | 13;16 T01 | — | Consensus; test files и builds не открывались/не запускались |
| [16 Features](16-feature-matrix.md) | 15;01;05 unused;16 A/K | 15;01;05 unused;16 F/T | 15 для SERVER_CAPABILITY_UNUSED в17 | Активные critical mechanisms — VER-001–018; negative inventory consensus |
| [17 Integration](17-server-client-consistency.md): endpoint table/bootstrap/hash/target/ID | 19;05;08 | 19;05;08 | README;05;06;07;08;10;15;20 freeze | VER-004/006/010/011/012/017/018 |
| [18 Risks](18-risks.md) | 16 плюс factual thematic docs | 16 плюс factual thematic docs | 05/06/07/10 для integration risks | Evidence по каждой risk row; coverage ниже |
| [19 Open](19-open-questions.md) | 17 Q/V/handoff references | 17 | 19;10 OPEN-SRV-030; resolved semantics05/06/07 | Факты вопросов закрыты VER; runtime/product answers не назначены |
| [20 Verification](20-verification-log.md) | Конкретные claim locators в каждой записи | То же | Только при применимости | Полный реестр18 обращений, не новый class catalog |

Сокращённое VER-001 в этой таблице означает VER-AND-001, аналогично для остальных номеров.

## Coverage исходных findings

Ниже R001 означает RISK-AND-001, O001 — OPEN-AND-001; номера файлов относятся к этому пакету. Группировка сохраняет все исходные ID, но не копирует рекомендации или исторические commit narratives.

| Audit A16 findings | Куда вошли / disposition |
| --- | --- |
| A1 | 05/16 media scope; O011. Server video capability unused, не defect сам по себе |
| A2 | 10/11/12/16; R014/R026; O018 |
| A3 | 06/12/16/17 unused remote API; O013 |
| A4 | 12/16 STUB; O024 |
| A5, D6 | 03/08/09/17 existing/ID; O020 |
| A6 | 08/11/17; R038; O022 |
| A7 | 07/12/16 absent onboarding; O023; frozen определяет server capability |
| B1, C1, C2 | 02/10; R001/R008; O001 |
| B2, D2, D7 | 04/09/10; R011/R012; C-AND-001/002; O028 |
| B3, F4, H2 | 07/10/12; R008/R009 |
| B4, B6, H7, K2, K3, K4, K5 | 02/04/09/13/16 unused; R037. Catalog/detail counts не являются отдельными capabilities |
| B5, C5 | 02/06/11; R025; O007 |
| C3, F3 | 10/12/16; R011/R035; O001/O009/O019 |
| C4 | 14; R024; O006 |
| D1 | 03/07/17 INT-AND-001; R002; O014 |
| D3 | 08/17; R018/R019; O013/O020/O030 |
| D4, D8 | 04; R029; O008. Actual population старых установок и категоричный downgrade outcome не утверждаются |
| D5 | 04/11; R030 |
| D9 | 08/17 INT-AND-003; R016 |
| E1 | 05; R015; O002 |
| E2 | 05/14; R020; O003/O021 |
| E3 | 04/05/12; R031; O009/O029 |
| E4 | 03/05; R032 |
| F1, F2 | 10/11; R013/R026; O001/O018 |
| F5 | 08; R039 |
| F6 | 02 runtime declaration; VER-014. Не назначается неизвестный набор сломанных сторонних initializers |
| G1 | 14; R003/R022; O004/O025 |
| G2, G3 | 13/14; R004/R023; O016 |
| G4 | 06/08 HTTP ping и два lookup; R017/R039 |
| G5 | 08/14; R021; O005 |
| H1 | 07/17 INT-AND-004; R006 |
| H3 | 07/10; R007; O015 |
| H4 | 07/12/14; R005 |
| H5 | 07; R010; O028 |
| H6 | 07/16 UNUSED expiry data; reactive refresh описан, proactive refresh отсутствует |
| I1, I4 | 08/11; R013. Precheck400 abort/retry отдельно описан в11 |
| I2 | 09/11; R014; O018 |
| I3, J4, J5 | 11/12/14; R035; O025 |
| J1 | 12; R028 |
| J2, J3, J6 | 12; R034/R037; O024 |
| J7 | 12/16; R031; O013 |
| K1 | 15; R036; O029 |

| Audit B16 findings | Куда вошли / disposition |
| --- | --- |
| F01, F02 | 05/06/12/16/17, O011/O013; неиспользуемые серверные capabilities не считаются defect |
| F03 | 12/16 STUB; O024 |
| F04, E02, E03 | 09/11/12; R014; O018 |
| A01, D05, W01 | 04/09/10; R011/R012; O028 |
| A02, T02, T03, T04 | 02/13/16; R037; неактивный manifest не использован как runtime authority |
| A03, S07 | 07/10/12; R008/R035 |
| L01 | 10; R001 |
| L02 | 11; R025; O007 |
| L03 | 12; R034; O002 |
| D01 | 03/07/17 INT-AND-001; R002; O014 |
| D02 | 08/17 INT-AND-003; R016 |
| D03 | 03/08/17 existing без нового ID; O020 |
| D04 | 04; R029; O008 |
| D06 | 04/11; R030 |
| M01, M04 | 05/12; R015/R034; O002 |
| M02 | 03/05/08; R016 |
| M03 | 04/05/12; R031; O009 |
| W02 | 10/11; R026 |
| W03 | 07/10; R009 |
| W04 | 10/16; R025; O001/O009/O019 |
| W05 | 08/11/12; R027 |
| N01 | 13/14; R004/R023; O016 |
| N02 | 06/12/13; R033 |
| N03 | 08/17 INT-AND-002; R017 |
| N04 | 08/11; R038; O022 |
| N05 | 14; R022; O004 |
| S01 | 14; R003 |
| S02 | 07; R007; O015 |
| S03 | 07/17 INT-AND-004; R006 |
| S04 | 07; R010 |
| S05 | 07/12/14; R005 |
| S06 | 14; R024; O006 |
| E01, E04 | 08/11; R013 |
| E05 | 11/14; R035 |
| U01 | 10/12; R035 |
| U02, U05 | 12; R034/R037; O024 |
| U03 | 12/16; R035; O013/O018 |
| U04 | 12; R037 |
| T01 | 15; R036; O029 |

## Coverage исходных open questions

| Audit A17 | Итог |
| --- | --- |
| Q1,Q13 | O018; factual retry semantics закрыта VER-008/011 |
| Q2,Q8 | O012; локальное удаление/изменение фактически описано05/08 |
| Q3,Q11 | O013; unused server API в17 |
| Q4,Q5 | O011 |
| Q6 | O017 |
| Q7 | O011; CAMERA bootstrap/fallback уже установлен в17, не server unknown |
| Q9,Q10 | O014; отсутствие namespace уже установленный факт |
| Q12 | O022 |
| Q14 | O021; runtime bytes O003 |
| Q15,Q16 | O019; cold URL уже установленный факт, scheduling runtime O001 |
| Q17 | O016 |
| Q18 | O020 |
| Q19 | O025 |
| Q20 | O023 |
| Q21 | O002/O011 |
| Q22,Q23 | O024 |
| Q24 | O015; server lifetime/refresh/no rotation известны из frozen06 |
| Q25 | O027 |
| V1 | R023/O010; фактическая доступность endpoint на целевом устройстве не подтверждена; product transport scope O016 |
| V2,V3,V4 | O004/O003/O002 соответственно |
| V5,V11 | O001; код URL/observer/KEEP уже установлен |
| V6,V7,V8,V9 | O005/O006/O009/O011 соответственно |
| V10 | Нормальная multipart shape закрыта frozen05 + VER-018; O010 только deployment/edge unknown |
| V12 | История фактических загрузок не выдаётся за факт этой revision; условный target INT-AND-002. Deployment context O010 |
| C: handoff #1–20 | Повторяющиеся Android вопросы распределены выше; server bootstrap, duplicate, limits, logout и error semantics заменены frozen05/06/07/10. Unused download/move не образуют новые client defects |

Audit B17 не назначает ID строкам; ниже порядковые номера 1–24 по исходной таблице, начиная с вопроса о reboot.

| Audit B17 rows | Итог |
| --- | --- |
| 1 | O019/O001 |
| 2,4 | O012/O013 |
| 3 | O011 |
| 5 | O013 |
| 6 | O020 |
| 7 | O014 |
| 8,9 | O015; server revoke/access lifetime известны |
| 10 | O016/O010 |
| 11 | O017 |
| 12 | O009/O029 |
| 13 | O018 |
| 14 | O002/O011 |
| 15 | O012/O021/O028; current TOCTOU fact в08/17 |
| 16,17 | O026 |
| 18 | O021/O026 |
| 19 | O023 |
| 20 | O030; отсутствие server repair уже известно, open только внешний процесс/ответственность |
| 21 | O022 |
| 22 | O018/O019 |
| 23 | O025 |
| 24 | O029; конкретные runtime unknowns O001–O010 |

## Полнота входных документов и исключения

Все 20 тематических документов каждого аудита включены в mapping: 00–01 дают summary/boundary,02 architecture,03–04 data/Room,05 API,06 auth,07 media,08 pipeline/UI flows,09 state,10 errors,11 config,12 background,13 testing,14 observability/security,15 features,16 risks,17 questions,18 используется как исходный locator,19 заменяет исторический server reference на frozen.

Полные class catalogs, точное число DTO-файлов и историческая последовательность разработки не перенесены: они не задают current capability/semantics, а новая инвентаризация repository запрещена. Устаревшие TODO и рекомендации не использованы как фактические переходы или требования. Версии библиотек и значения default WorkManager backoff взяты из согласованных config/background-разделов; library source и dependency/runtime behaviour отдельно не проверялись. Server features сверх десяти Android calls перечислены как SERVER_CAPABILITY_UNUSED.

## Финальный self-check консолидации (до review и freeze)

Пакет содержит ровно 23 требуемых Markdown-файла: README и00–21. Проверка всех128 Markdown-ссылок на локальные документы/Android evidence не выявила отсутствующих targets. Определения VER/OPEN/RISK/INT и их полные ID-ссылки согласованы; counts18/30/39/4/3 и58 capabilities проверены по реестрам. Все22 файла frozen server package побайтово совпадают с предоставленным архивом; SHA-256 входов приведены выше.

Coverage major claims, findings и вопросов обоих входов отражён таблицами этого документа. Реальные C-AND conflicts разрешены; условные integration cases отделены от несовпадений wire, runtime unknowns — от уже установленных code branches. Cross-document проверка охватила identity, statuses, retry count/cooldown, auth, BaseUrl, WorkManager, serverFileId, pre-check и upload target. Android source/tests/config не изменялись, серверный код не читался; существующие пользовательские удаления старых audit docs сохранены. Android freeze-record не создан.

Наиболее объёмная верификация потребовалась для auth/session и refresh, Room state/retry/concurrency, cold-process scheduling, MediaStore visibility/bytes, CAMERA/precheck/upload identity. Независимый review может использовать этот пакет, исходные A/B и frozen; Android freeze-record отсутствует.
