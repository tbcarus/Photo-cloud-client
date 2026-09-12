# User-facing screens

Приложение содержит MainActivity/MainScreen и пять bottom navigation destinations. Стартовый route — Network. Все вкладки доступны независимо от Tokens/URL; обязательного auth navigation gate нет. Navigation использует launchSingleTop/restoreState без popUpTo/saveState; повторные tab entries могут накапливаться.

| Экран | Статус | Отображение и действия |
| --- | --- | --- |
| Network | IMPLEMENTED | IP/port, Test, connection state, loading и диалог message; success сохраняет адрес |
| Login/Auth | PARTIAL | До входа email/password, Register/Login/Test Auth; после входа JWT subject, Logout/Test Auth |
| Files | PARTIAL | Текстовый Room list, counts, результат UI scan, permission/error cards и общий sync outcome |
| Settings | STUB | Centered text Settings, controls отсутствуют |
| Profile | STUB | Centered text Profile; рабочий фрагмент email/logout расположен в Auth |

## Network

Поле host принимает IPv4; port проверяется лишь на непустоту. Ввод trim перед формированием HTTP URL. Успешный Test сохраняет IP/port и меняет BaseUrlProvider; неуспех сохраняет прежний URL. При init сохранённый URL назначается до auto-test. Message появляется в диалоге в том числе на старте.

Нет HTTPS/domain/IPv6/path-prefix selector. Смена endpoint не очищает токены/Room. Некорректный port может привести к exception создания Retrofit вне Result handling и оставить isLoading без штатного завершения.

## Auth

Password — обычный OutlinedTextField без masking/Password keyboard configuration. Форма не проверяет email/password bounds. Значение password остаётся в ViewModel после login/logout.

isLoggedIn отражает наличие пары, не successful verify. verifySession использует loading, но не анализирует Result. Explicit Test Auth показывает message; Auth loading отображается также во время обычных операций. Очистка токенов переключает Auth на форму через Flow; navigation/notification об истечении session отсутствуют.

Встроенных confirm email, resend/reset password, logout-all/others и server profile calls нет. Регистрация показывает ответ без auto-login.

## Files и permissions

При LaunchedEffect(Unit) Files проверяет tokens+URL. При уже имеющемся permission вызывает reconcile без scan; при отсутствии — системный permission launcher. Grant вызывает reconcile и scanPhotos; denial тоже вызывает scanPhotos для PermissionDenied card. Отдельного onResume/reaction на последующее изменение configured state нет.

Список строится из всей Room таблицы DESC createdAt. Строки показывают имя, relativePath, размер, status, наличие checksum и краткую failure reason. Изображения/миниатюры/preview, server list и serverFileId не показаны.

Counts: Total и семь используемых MediaFileStatus; LOCAL_DELETED не имеет отдельного counter. LastScanResult обновляется только UI scan, а не Worker scan. insertedOrUpdated — число строк snapshot, не число реально изменившихся rows.

SyncStatusCard показывает один из четырёх общих outcomes, без timestamp/подробной ошибки. SUCCESS может сохраняться при FAILED items; PermissionDenied worker не пишет новый outcome. Periodic running не отображается в isSyncing.

## Loading и controls

Files показывает LoadingDialog при isScanning || isSyncing. isSyncing означает RUNNING только photo_sync. Dialog имеет пустой onDismissRequest; пользователь не закрывает его штатным dismiss. Пока Files отображает диалог, он закрывает взаимодействие и с нижней навигацией. Periodic work само по себе loader не включает.

Нет пользовательских Sync now, Scan, Pre-check, Upload, retry FAILED, reset index, cancel, byte progress или resume. Наличие repository methods не является UI capability. Нет перехода в settings после окончательного отказа permission; текст card о «следующем этапе» не отражает уже существующий launcher.

SnackbarHost объявлен, активного showSnackbar flow нет. Фоновые errors представлены общим outcome и per-file reason; retries/time/server error UUID не видны.
