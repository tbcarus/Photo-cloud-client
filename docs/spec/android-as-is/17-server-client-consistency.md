# Server–client consistency

Authoritative reference: **[PhotoCloud Server As-Is v1 — FROZEN](../server-as-is/README.md)**. Все серверные значения в этом разделе взяты из frozen API/auth/storage/capability документов. Здесь не используется historical handoff как действующий contract.

## Классификация

CONSISTENT — совпадение в указанном объёме. CLIENT_ASSUMPTION_WEAKER_THAN_SERVER — клиент использует/проверяет лишь часть значимой серверной семантики; это не автоматически defect. SERVER_CAPABILITY_UNUSED — capability или mapping сервера не вызывается. MISMATCH — конкретное семантическое расхождение при описанных условиях. OPEN_SYSTEM_QUESTION — неизвестность среды или неназначенная политика общей системы.

**10/10 используемых method/path и базовых request/response shapes согласованы.** Это не означает полной согласованности lifecycle. Ниже отдельно перечислены **4 SERVER_CLIENT_MISMATCH**, отражённые как MISMATCH. Они не являются четырьмя ошибками routes/DTO и не означают четыре воспроизведённых runtime инцидента.

## Endpoint verification against frozen API

Общий prefix /api/v1. Для auth requests Android использует current Bearer; plain не добавляет его. JSON field shapes раскрыты в [06](06-network-api.md); error mapping — [11](11-errors-retry-recovery.md).

| Method/path | Frozen request → response / auth | Android use и значимые ошибки | Classification / reference |
| --- | --- | --- | --- |
| GET /test | Без body →200 message; public, static без DB/FS readiness | Worker gate и Network Test; non-2xx/network → failure; blank URL не посылает request | CONSISTENT wire; readiness — ограничение; SRV-OPS-002, API §GET test |
| GET /test/auth | Без body →200 message; access | Auth client,401→refresh; explicit Test показывает Result, init игнорирует | CONSISTENT wire; CLIENT_ASSUMPTION_WEAKER_THAN_SERVER в session UI |
| POST /auth/register | email/password, password4..20 →201 message; public | Plain JSON; server validation400/conflict409 показываются; SMTP message не проверяет delivery | CONSISTENT; SRV-AUTH-001/019 |
| POST /auth/login | email/password →200 accessToken/refreshToken; public | Plain; nonblank validation ответа;400/401 без refresh; no expiresIn expectation | CONSISTENT; SRV-AUTH-003/004 |
| POST /auth/refresh-token | refreshToken body →200 accessToken; public; no rotation | Plain без Bearer;401 invalid/403 revoked и прочие non-2xx очищают pair; IO сохраняет | CONSISTENT wire/public header; MISMATCH INT-AND-004 для5xx; SRV-AUTH-005 |
| POST /auth/logout | refreshToken body →200 message; access+ownership | Auth replay; clear после HTTP response,404/403/5xx показываются error; IO до clear | CONSISTENT remote request; local offline policy OPEN-AND-015; SRV-AUTH-006 |
| GET /folders/root | Без body →200 FolderDto; access; lazy ROOT | Body.id, null body error; DB/runtime lookup failure → folder Error | CONSISTENT; SRV-FOLDER-001 |
| GET /folders/{id}/children | Root Long ID →200 direct FolderDto array; access;404 missing/foreign | Exact CAMERA type, null array→absence; non-2xx→Error | CONSISTENT normal response; malformed-null tolerance слабее; SRV-FOLDER-003 |
| POST /files/checksums/exists | folderId +1..500 raw hex64 →200 lowercase existing/missing; access/user+folder | <=500 rows→lowercase/dedup;400/404/401→whole-stage retry; existing status-only | CONSISTENT shape/normalization; ограничение logical evidence; SRV-SYNC-002 |
| POST /files/upload | Multipart file +optional folderId →200 FileItemDto new/existing; access | image/ local guard; id>0 success;400/409/413 FAILED; остальные retry; target/hash не проверяются | CONSISTENT wire/duplicate; MISMATCH INT-AND-002/003; SRV-FILE-001/002/003 |

Frozen общие controlled errors включают id/code/message/fieldErrors; framework/IO errors не имеют обещанного единого JSON. Android parser умеет raw fallback или HTTP N; нельзя объявить fixed Spring-default JSON обязательным server response. Детальная wire неопределённость остаётся в frozen OPEN-SRV-030, а не разрешается Android-аудитами.

## Интеграционные расхождения

| ID | Classification | Android факт | Frozen факт | Условие и последствие |
| --- | --- | --- | --- | --- |
| INT-AND-001 | MISMATCH | Room SYNCED/serverFileId и token pair без account/server scope; switch их не сбрасывает | FileItem/folder/existing привязаны к principal/user и конкретному серверу; SRV-AUTH-004, SRV-SYNC-002 | После смены аккаунта/host локальный успех прежнего scope используется для нового; старые SYNCED не проверяются, pending идёт с текущей pair, credentials могут направиться на новый host |
| INT-AND-002 | MISMATCH | Pre-check использует CAMERA; повторный lookup error в upload даёт omitted folderId; response target не проверяется | Default target по detected bytes: IMAGE/VIDEO→CAMERA, остальные→FILES; explicit любой own folder; SRV-FILE-002 | При successful CAMERA pre-check и последующей lookup error + non-image/video server detection upload может создать файл в FILES, локально SYNCED; само отсутствие folderId при first CAMERA bootstrap не mismatch |
| INT-AND-003 | MISMATCH | Hash вычислен отдельным чтением; upload принимает id, не response checksum/size | Сервер hash/size считает по принятым bytes; SRV-MEDIA-001 | При изменении bytes после hashing/pre-check локальный hash и подтверждённый remote object могут разойтись; обычный стабильный файл не означает mismatch |
| INT-AND-004 | MISMATCH | Любой non-2xx refresh, включая5xx, очищает pair | Invalid/revoked refresh имеет конкретные401/403; успешный refresh не потребляет token; server error сам не означает revoke; SRV-AUTH-005, errors10 | При временной5xx клиент теряет локальную session без установленной недействительности refresh; это смысловая потеря session, а не нарушение назначенной сервером client retry policy |

Данные четыре пункта — проверенные client-side ветки плюс frozen semantics; условия их проявления указаны явно. Наличие/частота на реальной установке не проверены.

## Обязательные integration areas

### CAMERA bootstrap и upload target

GET root создаёт только ROOT, не CAMERA. Android получает root и direct children, выбирая first folderType CAMERA. Если CAMERA отсутствует, pre-check не вызывает exists и переводит ready/non-null hashes в pending. Android не вызывает create USER/system folder API.

Upload разрешает CAMERA независимо ещё раз. Для Success посылает decimal ID как text/plain part. Для NotCreatedYet **и Error** part отсутствует. Part выбирается один раз перед циклом upload: после первого default upload последующие файлы этого run могут также идти без explicit ID.

Frozen server default routing выполняется по Tika/FileType полученных байтов, не по Android MIME header. Поэтому обычный IMAGE bootstrap согласован, но local MIME.startsWith(image/) не гарантирует именно CAMERA при omitted target.

### Pre-check normalization, batch и existing

Client batch ограничен500 **строками**, затем checksum.lowercase/groupBy сокращает список; запрос непустой при вызове и содержит максимум500 уникальных hashes. Generated SHA-256 даёт hex64 без пробелов. Frozen сервер допускает оба регистра, без trim, проверяет max raw500 до dedup и нормализует lowercase. Формат согласован.

Existing относится к логической FileItem в указанной own folder. Это не reservation, не checksum проверка physical file и не возврат ID. Android status-only SYNCED соответствует факту существования записи на момент запроса, но не доказывает доступность байтов или актуальность после remote mutations. Это CLIENT_ASSUMPTION_WEAKER_THAN_SERVER для полноты подтверждения, а смысл пользовательского «синхронизировано» — OPEN-AND-020.

### Duplicate200 и serverFileId

Frozen server name-conflict `409` возникает только вне CAMERA. При explicit CAMERA target совпадение имени разрешено; поэтому для текущего Android flow `HTTP_409` прежде всего относится к fallback upload без `folderId`, если server default routing отправил объект не в CAMERA (`INT-AND-002`).

Frozen same-user/folder/checksum upload возвращает200 прежний DTO, имя, metadata и даты. Android принимает200 id>0 и записывает serverFileId, не считает duplicate409. Same checksum в другой folder создаёт иной объект; поэтому идемпотентность повтора условна сохранением principal/folder/bytes.

Pre-check не выдаёт file IDs; Android не делает последующего list-sync. SYNCED без ID является нормальным результатом. Это не DTO mismatch. Pre-check сохраняет уже имевшийся ID, не устанавливает null явно.

### Errors, public Bearer и локальная политика

Refresh выполняется plain client и не посылает access header: frozen invalid Bearer на public URL не создаёт ошибку в этом Android path. Access после logout может оставаться валидным до exp; Android не заявляет server-side немедленный revoke access.

Frozen не требует client-side offline logout, max retries, proactive refresh или трактовки logout404 как успеха. Android behaviour описано независимо. Upload403/404/401 и stage errors повторяются без client limit; это CLIENT_ASSUMPTION_WEAKER_THAN_SERVER/error recovery limitation, а не отдельно выдуманное серверное требование.

Server move500 не переносится на Android как используемая операция. При этом upload имеет собственные DB/IO failure paths в frozen spec; для него client5xx branch применим.

### Metadata, transport и storage

Android не отправляет checksum/capturedAt/status parts — согласовано. Server dates остаются opaque strings; local epoch ms не обещает общую хронологию. Response normalized filename/MIME/size/metadata игнорируются — CLIENT_ASSUMPTION_WEAKER_THAN_SERVER, не wire mismatch.

GPS redaction/Content-Length mismatch требуют runtime evidence; не классифицируются как безусловный integration defect. HTTP-only клиент и фактический proxy/deployment — OPEN-AND-010/016; frozen не устанавливает, что развёрнутый endpoint требует TLS.

## Server capability unused

SERVER_CAPABILITY_UNUSED: 26 mappings вне Android inventory. Реализованные: confirm registration; logout-all/others; reset request/confirm; profile read; folder create/rename/move/delete; file list/get/download/rename/move/copy/delete/full checksums; legacy upload alias POST /files. Семь оставшихся mappings — server STUB (resend activation/reset, reset page GET/POST, profile PATCH, settings GET/PATCH), также без client calls.

Video/general MIME acceptance, metadata extraction и отдельные server CRUD capabilities не делают их отсутствие на Android defect. Stable server IDs и статусы — [server matrix](../server-as-is/15-feature-matrix.md). Resumable upload, delta feed, tombstones и sync sessions отсутствуют в frozen server scope; Android их не предполагает.

## OPEN_SYSTEM_QUESTION

Открыты фактический deployment/wire крайние случаи (OPEN-AND-010), product meaning SYNCED (020), multi-device scope (027) и внешний процесс восстановления потерянных server bytes (030). Поведение существующего server code — CAMERA lifecycle, no rotation, logical existing, duplicate200 и отсутствие bytes repair — уже установлено frozen и не оставлено server unknown.
