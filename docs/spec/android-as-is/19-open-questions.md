# Open questions

Осталось **30 OPEN**. Ни один вопрос не отменяет установленный client/server факт. Types отделяют runtime evidence, внешнюю интеграцию и неназначенные решения. CAMERA bootstrap, no rotation, existing/duplicate semantics, upload target fallback, serverFileId и точная retry формула уже разрешены и не перечислены как неизвестное поведение кода.

## OPEN-AND-001

**Type:** OS_FRAMEWORK_UNKNOWN

### Current As-Is

Source задаёт WorkManager requests; cold URL gate и отсутствие observer после death установлены.

### Question

Каковы фактические сроки и последовательности запуска/доставки событий на поддерживаемых OS/OEM после reboot, force-stop, Doze и потери constraints?

### Why it matters

От этого зависит наблюдаемая задержка backup; кодовый дефект URL уже не unknown.

## OPEN-AND-002

**Type:** OS_FRAMEWORK_UNKNOWN

### Current As-Is

Запрашивается READ_MEDIA_IMAGES без отдельной selected-photo модели; successful snapshot определяет stale DELETE.

### Question

Какую видимость и permission state предоставляет конкретный Android34+ selected-access режим этой сборке?

### Why it matters

Сужение snapshot может удалить локальную history без удаления оригиналов.

## OPEN-AND-003

**Type:** OS_FRAMEWORK_UNKNOWN

### Current As-Is

Нет location permission/setRequireOriginal; клиент читает обычный content URI.

### Question

Какие bytes/GPS возвращаются для geotagged media при фактических grants и OS/provider?

### Why it matters

Это определяет побайтовую полноту копии; nullable server GPS сам не доказывает redaction.

## OPEN-AND-004

**Type:** ANDROID_RUNTIME_UNKNOWN

### Current As-Is

BODY logging включён; RequestBody повторно открывает URI.

### Question

Каковы фактические дополнительные чтения, RAM peak и объём/содержимое Logcat при больших uploads?

### Why it matters

Размер нагрузки и реальное binary logging не измерены.

## OPEN-AND-005

**Type:** ANDROID_RUNTIME_UNKNOWN

### Current As-Is

Content-Length=size при size>0, поток отдельно открывается в writeTo.

### Question

Совпадает ли MediaStore SIZE с фактически отдаваемой длиной для используемых providers и как проявляется расхождение?

### Why it matters

Повторяемое IO/length failure может задержать очередь.

## OPEN-AND-006

**Type:** ANDROID_RUNTIME_UNKNOWN

### Current As-Is

Backup exclusions и обработка ошибки создания encrypted storage отсутствуют; Room ID без device scope.

### Question

Как ведут себя tokens/Keystore/Room при восстановлении конкретной установки или переносе устройства?

### Why it matters

Сохранённые данные и ключи могут восстановиться несогласованно.

## OPEN-AND-007

**Type:** ANDROID_RUNTIME_UNKNOWN

### Current As-Is

Blocking execute/hash/write не связаны с cancellation; catch Exception включает cancellation.

### Question

В какой момент реально прекращаются IO/side effects при cancel/constraint loss на используемом runtime?

### Why it matters

Logout/cancel нельзя считать измеренным барьером окончания запросов.

## OPEN-AND-008

**Type:** ANDROID_RUNTIME_UNKNOWN

### Current As-Is

Есть migrations1→2→3, fresh schema3 и destructive fallback; historical FAILED/null установлены.

### Question

Какие исторические DB варианты реально установлены и как проходит их migration/downgrade/open validation?

### Why it matters

Аудитные исторические dev-схемы не доказывают состояние пользовательских установок; нового migration audit нет.

## OPEN-AND-009

**Type:** ANDROID_RUNTIME_UNKNOWN

### Current As-Is

Полный scan, поэлементные writes и последовательные stage loops, без performance tests.

### Question

Каковы throughput, длительность первичного backup, battery/RAM cost и частота interruption на реальных библиотеках?

### Why it matters

Работоспособность на больших объёмах статически не измеряется.

## OPEN-AND-010

**Type:** SERVER_CLIENT_INTEGRATION

### Current As-Is

Routes/parts согласованы с frozen API; actual deployed revision/proxy, framework error wire и доступ к LAN endpoint с текущим cleartext XML на целевом устройстве не проверены.

### Question

Какой endpoint/revision фактически развёрнут, доступен ли он текущей сборке на целевом устройстве и какие binding/error responses наблюдаются за его proxy?

### Why it matters

Отделяет зафиксированный contract от deployment; frozen OPEN-SRV-030 не разрешён здесь. Normal folderId text/plain shape не остаётся неизвестным.

## OPEN-AND-011

**Type:** PRODUCT_DECISION

### Current As-Is

Scope — Images/DCIM, vendor folders попадают только если находятся под DCIM; video/outside media отсутствуют. Android ищет серверную CAMERA, а при omitted folderId действует frozen default routing.

### Question

Каков заявленный пользователю охват media types и локальных папок и предполагается ли выбор серверной папки назначения?

### Why it matters

Фактический scope установлен; неизвестно его соответствие продуктовым ожиданиям.

## OPEN-AND-012

**Type:** PRODUCT_DECISION

### Current As-Is

Local delete удаляет Room row, server copy остаётся. Обнаруженное изменение size/mtime запускает pipeline заново; новые bytes могут создать новую серверную запись, а прежний checksum может пройти existing. Удаления старой серверной копии клиент не выполняет.

### Question

Какова политика retention локально удалённых и изменённых фото?

### Why it matters

Определяет смысл backup/history; ответ не выводится из текущего поведения.

## OPEN-AND-013

**Type:** PRODUCT_DECISION

### Current As-Is

Server gallery/download/mutations и remote reconciliation отсутствуют.

### Question

Какова ожидаемая роль Android при remote delete/move и просмотре/управлении облачным содержимым?

### Why it matters

Server capabilities существуют, но их неиспользование само не defect.

## OPEN-AND-014

**Type:** PRODUCT_DECISION

### Current As-Is

Одна DB/token pair; account/server change не очищает старое состояние.

### Question

Какой scope использования нескольких аккаунтов и серверов предполагается для одной установки?

### Why it matters

Устанавливает ожидания владельца продукта без назначения новой схемы хранения.

## OPEN-AND-015

**Type:** PRODUCT_DECISION

### Current As-Is

Offline logout может оставить пару; server logout отзывает refresh, access живёт до exp.

### Question

Что пользователь должен понимать под завершённым logout и сообщением об ошибке/истечении session?

### Why it matters

Нужна продуктовая семантика local exit; server revoke semantics уже известны.

## OPEN-AND-016

**Type:** PRODUCT_DECISION

### Current As-Is

Network UI поддерживает только HTTP IPv4/port; effective LAN runtime не проверен.

### Question

Какой deployment/transport заявлен продуктом: доверенная LAN или доступ через внешний endpoint?

### Why it matters

Позволяет оценить текущие ограничения без назначения HTTPS implementation.

## OPEN-AND-017

**Type:** PRODUCT_DECISION

### Current As-Is

CONNECTED допускает metered network; periodic требует batteryNotLow.

### Question

Каковы допустимые условия расхода мобильного трафика, roaming и батареи?

### Why it matters

Текущие constraints не содержат пользовательского выбора.

## OPEN-AND-018

**Type:** PRODUCT_DECISION

### Current As-Is

Manual sync/retry/cancel отсутствуют; FAILED recovery ограничен CHECKSUM_IO.

### Question

Каковы ожидания пользователя по ручному контролю и восстановлению исчерпанных/permanent failures?

### Why it matters

Кодовая retry политика известна, продуктовые ожидания не назначены.

## OPEN-AND-019

**Type:** PRODUCT_DECISION

### Current As-Is

Background имеет observer/periodic, cold URL limitation и no foreground notification.

### Question

Какие гарантии автономности, задержки, уведомления и долгого начального backup заявляются?

### Why it matters

Определяет требуемый продуктовый смысл фонового режима, не изменяя As-Is.

## OPEN-AND-020

**Type:** PRODUCT_DECISION

### Current As-Is

SYNCED означает logical existing или upload id>0; возможен null serverFileId, bytes не перепроверяются.

### Question

Что именно обозначает пользовательское подтверждение «синхронизировано»?

### Why it matters

Различает локальный итог, logical server record, ID и physical availability.

## OPEN-AND-021

**Type:** PRODUCT_DECISION

### Current As-Is

Оригинальные bytes/GPS явно не запрашиваются; Android самостоятельно EXIF не редактирует.

### Question

Каковы ожидания по побайтовой копии и допустимости хранения/передачи геопозиции?

### Why it matters

Полнота backup и privacy — решение владельца, а не ответ статической проверки.

## OPEN-AND-022

**Type:** PRODUCT_DECISION

### Current As-Is

Client size guard отсутствует; frozen service limit100MiB, отказ413 terminal.

### Question

Как должен пользователь интерпретировать media, превышающие существующий серверный лимит?

### Why it matters

Размер сервера известен; пользовательская политика больших файлов не назначена.

## OPEN-AND-023

**Type:** PRODUCT_DECISION

### Current As-Is

Есть register/login; встроенных confirm/resend/reset flows нет; часть server resend/page STUB.

### Question

Каков продуктовый scope account onboarding и восстановления доступа через Android?

### Why it matters

Неиспользуемые API и server заглушки не задают завершённый user journey.

## OPEN-AND-024

**Type:** PRODUCT_DECISION

### Current As-Is

Settings/Profile — заглушки; диагностические кнопки и стартовый ping dialog доступны.

### Question

Каково назначение этих destinations и диагностических элементов в пользовательском продукте?

### Why it matters

Текущий UI установлен, назначения пустых вкладок не придуманы.

## OPEN-AND-025

**Type:** PRODUCT_DECISION

### Current As-Is

Last sync только in-memory; BODY all builds; backup templates без exclusions.

### Question

Каковы допустимые история, диагностические данные, доступ к logs и backup retention?

### Why it matters

Это неизвестная политика хранения/эксплуатации, а не новая implementation requirement.

## OPEN-AND-026

**Type:** PRODUCT_DECISION

### Current As-Is

Local dates epoch ms; server LocalDateTime strings/name/metadata игнорируются.

### Question

Какую общую хронологию, имена и metadata должен видеть пользователь?

### Why it matters

Текущая local row не является зеркалом server FileItem; wire types согласованы.

## OPEN-AND-027

**Type:** PRODUCT_DECISION

### Current As-Is

Device registration и remote sync отсутствуют; сервер owner-scoped, local device namespace нет.

### Question

Каков ожидаемый multi-device сценарий одного пользователя?

### Why it matters

Другие устройства могут менять server state, которое Android не наблюдает.

## OPEN-AND-028

**Type:** ARCHITECTURE_DECISION

### Current As-Is

Отдельные stage mutex, mutable URL/pair и нет content generation/pipeline transaction.

### Question

Какие границы согласованности считаются частью общей системной модели при session switch и concurrent processing?

### Why it matters

Условия гонок установлены; архитектурное решение об общем инварианте не назначается.

## OPEN-AND-029

**Type:** PRODUCT_DECISION

### Current As-Is

Product automated tests отсутствуют; runtime metrics/matrix не заданы входами.

### Question

Каковы поддерживаемые устройства, масштабы библиотеки и критерии продуктовой проверки?

### Why it matters

Отделяет статическую Verified specification от runtime пригодности.

## OPEN-AND-030

**Type:** SERVER_CLIENT_INTEGRATION

### Current As-Is

Frozen existing/duplicate не проверяют и не чинят missing bytes; Android repair flow отсутствует.

### Question

Есть ли внешний эксплуатационный процесс обнаружения и восстановления потерянных server bytes и кто им управляет?

### Why it matters

Приложения такого процесса не задают; вопрос о внешней эксплуатации, а не о неизвестном server endpoint.
