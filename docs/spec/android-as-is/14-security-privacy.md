# Security и privacy — текущие механизмы

## Credentials и storage

Access/refresh хранятся в EncryptedSharedPreferences secure_tokens. MasterKey AES256_GCM опирается на Android Keystore; keys AES256_SIV, values AES256_GCM. Пара доступна через synchronous getTokens и StateFlow mirror. Конструктор storage не содержит обработки corruption/Keystore creation errors.

Room и DataStore не имеют прикладного шифрования. В Room есть local content URI, filename/path, size/time, checksum, sync status и optional remote ID. Account/server namespace отсутствует. AllowBackup=true; backup/data extraction templates не исключают tokens/Room/settings. Фактический перенос и recovery ключа не проверены.

Password не сохраняется в preferences, но хранится открытой строкой AuthUiState и не маскируется UI. После login/logout форма явно не очищается. FLAG_SECURE, biometric gate и password reset UI в сведённом срезе отсутствуют.

## HTTP и Authorization

URL строится только как HTTP IPv4/port. При разрешённом соединении пароль, токены и media передаются без TLS. Приложение не задаёт certificate pinning или HTTPS selector. Manifest/XML configuration не является доказательством доступности любого LAN host; конкретный effective runtime оставлен OPEN.

AuthInterceptor отправляет текущий access всем requests auth client. Plain register/login/refresh/public test не используют Bearer. Смена server URL не очищает credentials, а refresh использует текущий BaseUrlProvider. Область проверки сервером — frozen principal/ownership, а не локальная Room identity.

JWT sub декодируется для показа без проверки подписи на клиенте; server authorization не выводится из этого UI helper. Срок access локально не проверяется. Logout и refresh failures описаны в [07](07-auth-session.md).

## Логирование

HttpLoggingInterceptor BODY подключён к обоим clients без build guard и redactHeader. Через него проходят register/login password bodies, login token pair, refresh request/response и logout refresh body. Приложение не применяет masking этих данных.

Logger установлен перед AuthInterceptor: не утверждается, что каждый initial Authorization header обязательно записывается. Риск secret bodies существует независимо от этого порядка. Также логируются URL, checksum request bodies, filenames/metadata; собственные Log вызовы включают base URL, mediaStoreId и exception messages, иногда URI/stacktrace.

ContentUriRequestBody сам пишет поток в sink. BODY logging может дополнительно читать и буферизовать request body; наличие streaming body не доказывает постоянную память end-to-end. Фактический объём памяти и бинарного Logcat output не измерен и не описывается как воспроизведённая утечка конкретного фото.

Crash reporting, analytics SDK, structured traces, persistent sync history и server error ID correlation отсутствуют. HTTP logging не заменяет эти механизмы.

## Media privacy

Клиент читает Images/DCIM по runtime permission и не изменяет оригиналы. Нет video permission, отдельного selected-photos flow, ACCESS_MEDIA_LOCATION или setRequireOriginal. EXIF/GPS клиент не извлекает/редактирует самостоятельно.

Поток ContentResolver может зависеть от OS grants и MediaStore redaction; наличие GPS и побайтовая идентичность оригиналу остаются runtime вопросами. Frozen сервер извлекает metadata из полученных bytes best effort; nullable GPS сервера не доказывает само по себе Android redaction.

Изображения не staging-копируются в private folder, upload response metadata клиент не сохраняет. Stale delete удаляет историю Room, не оригиналы и не серверную копию.

Риски и условные последствия перечислены в [18](18-risks.md). Этот раздел фиксирует механизмы, не представляет новый security audit или требования к защите.
