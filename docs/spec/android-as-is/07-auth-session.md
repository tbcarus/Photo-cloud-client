# Authentication и session

## Login и persistence

Auth UI использует одну форму email/password для register и login. URL должен быть настроен; notReady блокирует также Test Auth и Logout. Формат email и длина/trim пароля клиентом не валидируются. Register показывает message, не выполняет auto-login или встроенный confirm flow.

AuthRepository.login выполняет plain request и требует body с двумя nonblank token strings. AuthViewModel сохраняет Tokens, вызывает reconcile и показывает успех. Неудачный login сам не очищает предыдущую пару.

EncryptedPrefsTokenStorage хранит accessToken и refreshToken в secure_tokens. MasterKey AES256_GCM, keys AES256_SIV, values AES256_GCM. getTokens требует наличие обеих строк, но не проверяет blank/expiry. save/clear вызывают preferences.apply и затем обновляют StateFlow. Пароль не persisted; строка формы не очищается явно после login/logout.

Пара не связана с account/server namespace. isLoggedIn означает tokens != null, а userEmail — декодированный JWT sub для показа. JwtUtils.isExpired UNUSED. Наличие пары не удостоверяет пригодность session.

## Bearer и refresh

AuthInterceptor читает текущий storage для каждого auth request; при отсутствии Tokens отправляет request без Bearer. Срок access локально не проверяет.

TokenAuthenticator:

1. responseCount по цепочке priorResponse >=2 → null; повторного refresh в этой цепочке нет.
2. Под synchronized(lock) читает текущий URL и Tokens. Blank URL или отсутствие Tokens → null.
3. Если failed request содержит access и он отличается от current access, повторяет с current без refresh.
4. Иначе делает plain POST refresh-token с текущим refresh.
5. Non-2xx → storage.clear и null.
6. 2xx без body/access → null без clear.
7. Non-null access сохраняется вместе с прежним refresh, request повторяется с новым Bearer.

Пустая строка access не отвергается на шаге 7. Проверка отличается от login. Повторный 401 после replay прекращает refresh, но эта ветка сама tokens не очищает.

Refresh не содержит access Authorization и не использует auth client. Это соответствует public refresh и избегает frozen server filter-сценария «expired Bearer отклонён до controller». Сервер выдаёт только новый access, не ротирует и не продлевает refresh — [SRV-AUTH-005](../server-as-is/06-auth-security.md).

| Refresh outcome | Pair | Исходный request |
| --- | --- | --- |
| 2xx + non-null access | Новый access, прежний refresh | Один replay |
| 2xx body/access null | Сохраняется | Authenticator возвращает null |
| 2xx access blank | Сохраняется blank access | Replay, затем обычная обработка ответа |
| 4xx | Очищается | Authenticator возвращает null |
| 5xx | Очищается, несмотря на отсутствие доказательства revoke | То же |
| IOException/network timeout | Сам authenticator не очищает | Exception уходит вызывающему repository |
| Повторный 401 | Guard сам не очищает | Ответ передаётся caller |

Synchronized refresh защищает только этот authenticator. Login/logout/storage writes и URL change используют другой flow. In-flight refresh может перезаписать более новую session или вернуть старую пару после logout; occurrence runtime не воспроизводилась. URL для refresh берётся в момент authenticate, а не обязательно с host исходного request.

## Logout

AuthRepository получает refresh и выполняет protected POST logout. После HTTP response очищает пару, затем проверяет success. Поэтому 4xx/5xx показываются как ошибка после local clear. IOException, factory exception или отсутствие Tokens до clear не гарантируют local exit.

AuthViewModel вызывает reconcile после полученного Result. Если tokens очищены, coordinator отменяет periodic и останавливает observer; при retained tokens и остальных условиях может оставить их включёнными.

Logout не очищает Room, IP/port, SyncStatusStore и email/password формы. One-time photo_sync не отменяется. Worker не имеет entry auth gate и может продолжить scan/hash, затем получить auth errors; отмена periodic не гарантирует немедленной остановки уже идущего blocking HTTP.

Frozen сервер отзывается только refresh; уже выданный access живёт до exp. Его HTTP 404 unknown refresh не объявлен успешным ответом; Android показывает ошибку. Offline local-clear policy сервером не назначается — [17](17-server-client-consistency.md).

## Session UI и background

AuthViewModel подписан на tokensFlow; clear переключает AuthScreen на форму, если ViewModel жив. Автоматической навигации, уведомления «сессия истекла» или обязательного login gate нет.

verifySession при ready+tokens вызывает Test Auth, но игнорирует Result, выключая isVerifying в finally. Ошибка сети может оставить UI logged-in. Явная кнопка Test Auth показывает результат.

TokenAuthenticator.clear не вызывает reconcile, а coordinator не подписан на tokensFlow. Periodic/observer могут продолжать работу до следующего явного reconcile. Эти действия не создают новых credentials автоматически.
