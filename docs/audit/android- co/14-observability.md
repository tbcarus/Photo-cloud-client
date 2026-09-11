# 14. Observability

## Источники наблюдаемости

| Mechanism | Что записывает/показывает | Ограничения |
| --- | --- | --- |
| [CONFIRMED] HttpLoggingInterceptor BODY | HTTP request/response headers и bodies согласно библиотеке | Без debug gating/redaction; оба clients |
| [CONFIRMED] BASE_URL Log.d | Конструируемый http URL | Раскрывает адрес/port, но не отдельный credential |
| [CONFIRMED] NetworkViewModel Log.e | Ошибка подключения + Throwable | Нет structured error ID |
| [CONFIRMED] TokenAuthenticator Log.w | Blank URL skip refresh | Не логирует token явно этим сообщением |
| [CONFIRMED] ChecksumRepository Log.w | mediaStoreId + exception.message / null stream | No stack trace в этих per-file messages; пути/URI могут быть в exception |
| [CONFIRMED] SyncWorker Log.e/w/i | Generic exception stack; unavailable server; restored FAILED count | Полной summary стадий в Log.i нет |
| [CONFIRMED] MediaChangeObserver Log.i | Registered/stopped | Не event IDs/debounce outcome |
| [CONFIRMED] WorkManager outputData |7 aggregate counters при success | Retry не выдаёт structured error; UI output не читает |
| [CONFIRMED] SyncStatusStore | Last status+timestamp | Только память, один результат для всех workers |
| [CONFIRMED] FilesScreen | Local filenames/path/size/status/hash presence/reason, counters | Нет thumbnail, HTTP details, retry count/timer, server ID |
| [CONFIRMED] Auth/Network dialogs | Message + ConnectionStatus.title | Auth error fields сведены в одну строку |

Источники: [NetworkModule.kt:21](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:21>), [NetworkViewModel.kt:60](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/network/NetworkViewModel.kt:60>), [ChecksumRepository.kt:105](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:105>), [SyncWorker.kt:36](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:36>), [MediaChangeObserver.kt:48](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/MediaChangeObserver.kt:48>), [FilesScreen.kt:258](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:258>).

## Ошибки, потеря контекста и UI

[CONFIRMED] ApiErrorParser читает UUID id, но не возвращает его. Code обычно уступает message и также теряется. Permanent upload failure сохраняет только HTTP_400/409/413, без server text. Worker stage errors дают generic ERROR/RETRY_SCHEDULED в card; actual exception.message не сохраняется в SyncStatus. Last timestamp записывается, но не отображается. [ApiErrorParser.kt:29](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/core/network/ApiErrorParser.kt:29>), [SyncStatusStore.kt:21](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncStatusStore.kt:21>), [FilesScreen.kt:183](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:183>).

[PARTIAL] UI воспринимает running one-time, не periodic. LastScanResult обновляется только scan через VM, Worker scan его не устанавливает. SUCCESS может сосуществовать с FAILED items; PermissionDenied в Worker не меняет последний outcome. Room rows обновляются Flow без общего snapshot всех стадий.

[CONFIRMED] Нет structured JSON logs, app metrics/tracing, crash-reporting SDK, persistent sync history, per-file progress, correlation request ID propagation, developer diagnostics screen, экспортов logs, in-app WorkManager diagnostics. SnackbarHost объявлен без showSnackbar callers. Test Auth и Network Test — реальные доступные диагностические кнопки во всех variants; прежние Files debug buttons удалены.

## Чувствительные данные

[RISK] BODY logger может раскрывать пароль из AuthRequest, access/refresh из login response, refresh из refresh/logout body и новый access. Эти bodies действительно проходят configured logging client; значения в аудит не копировались. Logging-before-auth порядок означает, что нельзя без отдельной проверки обещать логирование Authorization initial request; риск secret body достаточен и независим от этого. [NetworkModule.kt:49](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/di/NetworkModule.kt:49>), [AuthRequest.java:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/AuthRequest.java:3>), [LoginResponseDto.kt:3](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/api/models/LoginResponseDto.kt:3>).

[RISK] Multipart metadata/имена и ответная media metadata тоже доступны logger; ContentUriRequestBody сам streaming, но BODY logging способен буферизовать/читать request body до network send. Это **[INFERRED]** library-level эффект выбранного уровня logging; конкретное memory usage не измерялось. Большой upload не следует считать end-to-end constant-memory только по наличию потокового RequestBody.

[CONFIRMED] Логи checksum включают ID и сообщение исключения, а ContentUriRequestBody IOException содержит URI. Files UI показывает local filename/path; пароль в login UI не замаскирован. Backup exclusions отсутствуют. Источники: [ContentUriRequestBody.kt:31](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ContentUriRequestBody.kt:31>), [AuthScreen.kt:74](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/auth/AuthScreen.kt:74>).

## Комментарии против текущего поведения

[INCONSISTENCY] TODO про будущий runtime permission/checksum/upload/WorkManager в нескольких файлах устарели: активные callers уже есть. Комментарий SyncWorker о terminal FAILED верен для upload permanent, но не отражает существующий CHECKSUM_IO auto restore. Надпись permission card «будет запрошено на следующем этапе» противоречит actual launcher. При аудите поведение восстановлено по коду, не по этим comments. [FilesScreen.kt:169](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/ui/screens/FilesScreen.kt:169>), [ChecksumRepository.kt:70](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/ChecksumRepository.kt:70>), [SyncWorker.kt:95](<C:/projects/Photocloudclient/app/src/main/java/ru/tbcarus/photo_cloud_client/media/SyncWorker.kt:95>).

