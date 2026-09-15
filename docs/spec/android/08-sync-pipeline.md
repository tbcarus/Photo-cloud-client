# Sync pipeline

SyncWorker выполняет один последовательный прогон: URL/public ping → failed restore → scan/Room → checksum → resolve CAMERA/pre-check → повторный resolve CAMERA/upload → итог. Последовательность относится к одному worker; другие worker или UI scan могут пересечься с ним.

## Стадии

| Стадия / input | Persistent state до | Операция / server call | Успех и продолжение | Ошибка / остановка / retry |
| --- | --- | --- | --- | --- |
| Gate: BaseUrlProvider | Любые media states | Blank check; GET /test если URL есть | Продолжить после successful Result | SERVER_UNAVAILABLE + WM retry, локальные стадии не начинаются |
| Restore | FAILED CHECKSUM_IO,count<3,time<=now−5min | Один snapshot <=500; resetFailedForRetry | PENDING, reason/time clear, count/hash/ID сохранены | Exception → ERROR + WM retry |
| Scan | Старый индекс | Полный Images/DCIM query; insert/reset/update/stale delete | Новый PENDING; изменённые сброшены; невидимые удалены; продолжить | PermissionDenied → bare WM success; Error → ERROR+retry; частичные SQL сохраняются |
| Hash | PENDING и оставшийся HASHING | Permission guard; под mutex HASHING→PENDING; партии100; open URI/SHA-256 | CHECKSUM_READY + hash, clear failure/count/time | Per-file FAILED продолжает очередь; stage Error → retry; PermissionDenied → bare success |
| Folder для pre-check | CHECKSUM_READY с nonnull hash | GET root, GET root/children, exact CAMERA type | CAMERA id → exists; absence → весь ready/non-null hash в PENDING_UPLOAD без exists | Folder Error → ERROR+retry, upload в этом worker не начинается |
| Exists | CHECKSUM_READY, snapshot <=500 | Lowercase/group/dedup; POST folderId+checksums | Existing→SYNCED status-only; missing→PENDING_UPLOAD; продолжить партии | Non-2xx/null body/exception → retry; прежние партии сохраняются |
| Upload preparation | PENDING_UPLOAD/UPLOADING | Под mutex UPLOADING→PENDING_UPLOAD; новый root/children lookup | Один folderIdPart на upload run | Absence **или Error** → null part, продолжить upload |
| Upload file | PENDING_UPLOAD, партии20 DESC createdAt | MIME image/ guard; pre-open; UPLOADING; multipart POST /files/upload | 2xx id>0→SYNCED/serverFileId; clear failure/count/time; следующий файл | Permanent failure→FAILED и следующий; transient→PENDING_UPLOAD и stop; generic exception→worker catch |
| Итог | Сохранившиеся rows | SyncStatusStore + WorkManager Result | SUCCESS+output если upload не просит retry | Upload Error→RETRY_SCHEDULED+retry; generic exception→ERROR+retry |

## CAMERA и pre-check

FolderRepository ищет первого прямого потомка ROOT с folderType=CAMERA, не по имени. Root body null — Error; null children body трактуется как пустой список. Android не делает create-folder request и не кэширует ID.

При отсутствии CAMERA ready rows с non-null checksum переводятся в pending upload. Это bootstrap, а не проверка existing на сервере. Сама CAMERA создаётся серверным default upload по типу байтов, установленному сервером.

При наличии CAMERA запрос содержит до 500 уникальных lowercase hashes, полученных максимум из 500 локальных строк. Локальные дубли в партии получают один результат. Existing имеет приоритет, если некорректный response включил hash в оба списка. Hash вне обоих списков остаётся CHECKSUM_READY. Если ни одна строка партии не изменила статус, цикл заканчивается Success; отдельного retry для неполной partition нет. Эта ветка защитная: серверный response определяет полную partition валидного запроса.

Existing меняет только статус и сохраняет прежний serverFileId. В обычном fresh path он null. Результат не означает физическую проверку серверных байтов.

## Upload и completion

Перед каждым файлом проверяется local MIME.startsWith("image/"), затем возможность открыть/закрыть URI. Последующее writeTo открывает URI снова. Size, hash и folder не закреплены immutable snapshot на весь pipeline.

Upload 2xx с положительным id принимает новый и existing FileItem одинаково. Ответные checksum/size/folderId не проверяются. HTTP 400/409/413, NOT_IMAGE, pre-open FILE_NOT_FOUND/PERMISSION сохраняют FAILED и не останавливают следующие файлы. Прочие HTTP, IOException и invalid success id останавливают upload после возврата записи в PENDING_UPLOAD.

Партии 100/500/20 повторяются до опустошения очереди или выхода по ошибке/no-progress. Не существует общего лимита «20 uploads за worker». Все hashing завершаются до первого pre-check/upload данного worker.

WM success не означает «каждое фото SYNCED»: per-file FAILED и no-progress CHECKSUM_READY этому не препятствуют. PermissionDenied на scan/hash завершает bare success без нового SyncStatusRecord/output; прежняя карточка может сохраниться.

## Sequence

```mermaid
sequenceDiagram
    participant T as Observer / periodic
    participant W as SyncWorker
    participant M as MediaStore
    participant D as Room
    participant S as HTTP Server
    T->>W: Run with constraints
    W->>W: Require in-memory URL
    W->>S: GET /test
    S-->>W: Successful response
    W->>D: Restore eligible CHECKSUM_IO
    W->>M: Query Images / DCIM
    M-->>W: Snapshot
    W->>D: Insert / reset / update / delete stale
    W->>M: Open streams and SHA-256
    W->>D: CHECKSUM_READY or per-file FAILED
    W->>S: GET root, GET children
    break Folder lookup error
        W->>W: ERROR, return retry
    end
    alt CAMERA found
        W->>S: POST checksums/exists, folderId, up to 500 hashes
        S-->>W: existing / missing
        W->>D: SYNCED status-only / PENDING_UPLOAD
    else CAMERA absent
        W->>D: Ready with hash to PENDING_UPLOAD
    end
    break Pre-check request error
        W->>W: ERROR, return retry
    end
    Note over W,S: Upload reached only after successful pre-check stage
    W->>D: Recover UPLOADING to PENDING_UPLOAD
    W->>S: Resolve CAMERA again
    S-->>W: ID, absence, or error
    Note over W,S: Absence or error produces omitted folderId
    loop Pending files until completion or transient error
        W->>M: Pre-open, then reopen for body
        W->>D: UPLOADING
        W->>S: POST multipart file and optional folderId
        S-->>W: HTTP result
        W->>D: SYNCED with ID, FAILED, or PENDING_UPLOAD
    end
    W->>W: Record outcome; WM success or retry
```

Диаграмма показывает ветки, а не runtime trace. После ветки folder Error прогона не существует продолжения в upload.
