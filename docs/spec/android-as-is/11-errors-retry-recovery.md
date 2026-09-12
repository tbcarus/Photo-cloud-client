# Ошибки, retry и recovery

Android различает per-file FAILED в Room, stage Outcome.Error(message), WorkManager Result и in-memory SyncStatus. Общей structured error-модели нет. Серверная retry policy не используется как источник client поведения.

## Failure matrix

| Событие | Persisted effect | Продолжение / retry | UI |
| --- | --- | --- | --- |
| Blank URL / unsuccessful public ping | Media state не меняется | Gate stop; SERVER_UNAVAILABLE + WM retry | Общая карточка при доступном status store |
| Нет permission до scan/hash | Нет изменений этой стадии; restore до scan уже мог выполниться | Bare WM success, без нового outcome | Permission card только при UI scan |
| Null cursor / query exception / cursor-level error | Apply не начинается | Scan Error → ERROR+retry | UI scan message или generic sync error |
| Malformed cursor row | Исключена из успешного snapshot; row может стать stale | Scan продолжается | Нет отдельной ошибки |
| Room/apply exception | Уже выполненные statements сохраняются | ERROR+retry | Generic outcome |
| Hash null stream / FNF | FAILED FILE_NOT_FOUND,count++,time | Следующий файл; auto restore нет | Краткая причина |
| Hash SecurityException | FAILED PERMISSION,count++,time | Следующий файл; grant сам row не восстанавливает | Permission denied |
| Hash IOException | FAILED CHECKSUM_IO,count++,time | Следующий файл; ограниченный eligible restore позже | Checksum I/O error |
| Hash прочий Exception | FAILED UNKNOWN,count++,time | Следующий файл; auto restore нет | Unknown error |
| Pre-check folder/HTTP/parser/network error | Неотработанные rows без stage write; прежние batches сохранены | ERROR+WM retry | Generic error |
| Неполная exists partition | Неуказанные hashes остаются CHECKSUM_READY | При no-progress Success; самостоятельного WM retry нет | Возможен SUCCESS |
| CAMERA lookup error перед upload | Сам по себе FAILED не пишет | Upload с omitted folderId | Lookup error не показывается |
| MIME не image/ | FAILED NOT_IMAGE,count++,time | Следующий файл | Not an image |
| Upload pre-open null/FNF/Security | FAILED FILE_NOT_FOUND/PERMISSION | Следующий файл | Краткая причина |
| Иной pre-open exception | Обычно PENDING_UPLOAD остаётся | Worker generic ERROR+retry | Generic error |
| Upload 400/409/413 | FAILED HTTP_x,count++,time | Следующий файл; auto restore нет. 409 зависит от non-CAMERA target; см. [17-server-client-consistency.md](17-server-client-consistency.md) / `INT-AND-002` | HTTP x без server text |
| Upload прочий HTTP, в том числе 401/403/404/429/5xx | PENDING_UPLOAD, без новых failure metadata | Stop upload; RETRY_SCHEDULED+retry | Общий итог |
| Upload 2xx без id>0 | PENDING_UPLOAD | То же | Общий итог |
| IOException во время upload/reopen/write | PENDING_UPLOAD | То же | Общий итог |
| Generic exception после UPLOADING | Может остаться UPLOADING | Worker catch ERROR+retry; позже stage recovery | Generic error |
| Refresh non-2xx | Tokens clear | Caller получает финальный auth result | Auth form через Flow; нет forced navigation |
| Refresh IOException | Authenticator сам пару не очищает | Caller-specific error/retry | Caller-specific |
| Logout IOException до clear | Pair может сохраниться | User может повторить Logout | Error dialog |

FAILED в checksum/permanent upload само по себе не делает worker Result.retry. Права могут пропасть между guard и чтением; pre-open и реальное writeTo имеют разные exception boundaries. SecurityException во время повторного открытия upload не относится к pre-open permanent branch.

## Ограниченный CHECKSUM_IO retry

Selector требует status=FAILED, reason=CHECKSUM_IO, retryCount<3, lastFailureAt<=now−300000, выбирает максимум 500 строк DESC createdAt. Один worker делает один restore snapshot после successful ping и до scan.

Первое неуспешное hashing увеличивает count до1. Допустимы ещё две неуспешные попытки; при count3 restore не выбирает row. Reset очищает reason/time, сохраняя count/hash/ID. Успешный hash/upload или content change обнуляет count.

Cooldown — eligibility, **не таймер**. Отдельной задачи на пять минут нет; нужен следующий worker. Если прогон завершился SUCCESS с CHECKSUM_IO failures, повтор зависит от periodic/observer/другого retry. Время wall-clock; изменение часов влияет на eligibility. Concurrent restore способен перезаписать продвинувшуюся строку; лимит не является глобальной exactly-once гарантией.

FAILED с другими reasons, с исчерпанным count или историческим null reason/time автоматически не восстанавливаются. Manual retry/reset нет. Content change может сбросить любой FAILED; stale deletion и повторное discovery создают новую строку.

## WorkManager retry и head-of-line

Stage errors/transient upload возвращают Result.retry без собственного attempt limit. Повтор начинается с gate/restore/полного scan; уже SYNCED не выбираются для upload, но content-change/гонки могут менять состояние.

Upload выбирает newest-first и останавливается на первом transient. Воспроизводимая ошибка одного нового файла задерживает более старые pending в последующих прогонах. Это условный head-of-line сценарий; отсутствие attempt limit не гарантирует, что ОС фактически выполнит бесконечное число попыток.

HTTP 403/404/401 после исчерпанного refresh и детерминированная 5xx-причина могут сохраняться без изменения условий. Android не использует Retry-After, machine error code, nextRetryAt или per-file HTTP attempt counter. Server move 500 не относится к этому flow, поскольку move не вызывается.

## Recovery и error presentation

HASHING recovery и UPLOADING recovery меняют только status на входе соответствующих стадий. До upload recovery должны пройти gate/scan/hash/pre-check. HTTP commit и Room markUploaded не атомарны; повтор с теми же bytes/principal/folder может получить existing 200, но target/bytes snapshot клиент не фиксирует.

ApiErrorParser возвращает fieldErrors→message→code→HTTP N, malformed JSON→raw body. UUID/code не сохраняются структурированно. Permanent upload хранит только reason; stage message не включается в SyncStatusRecord. В UI нет количества retries, времени failure, serverFileId или подробного текста фоновой ошибки.

Отдельный Result.failure worker не возвращает. Generic catch включает cancellation; record ERROR не доказывает прикладной failure вместо остановки библиотекой.
