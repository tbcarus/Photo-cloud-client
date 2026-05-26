# Анализ перед Этапом 2 рефакторинга Android-клиента

Цель Этапа 2: стабилизировать DI / server settings / Retrofit creation без изменения пользовательской логики и без добавления новой функциональности.

## 1. Текущее состояние

### AppPreferences / DataStore

`AppPreferences` находится в `app/src/main/java/ru/tbcarus/photo_cloud_client/utils/AppPreferences.kt`.

Сейчас это `object`, который сам объявляет `Context.dataStore` через `preferencesDataStore(name = "settings")` и требует `Context` в каждом методе.

Хранимые данные:
- `ip` - строковый IP сервера;
- `port` - строковый порт сервера.

Публичные методы:
- `getIp(context): Flow<String?>`;
- `getPort(context): Flow<String?>`;
- `saveConnection(context, ip, port)`.

Фактические использования:
- `NetworkViewModel.init` читает `getIp(context).first()` и `getPort(context).first()`;
- `NetworkViewModel.testConnection` после успешного ping вызывает `saveConnection(context, ip, port)`.

Других прямых использований `AppPreferences`, `getIp`, `getPort` и `saveConnection` в `app/src/main/java` не найдено.

Кто читает/пишет IP, port, baseUrl:
- IP/port читает и пишет только `NetworkViewModel`;
- persisted `baseUrl` сейчас не хранится, он каждый раз собирается из IP/port как `http://$ip:$port/`;
- runtime `baseUrl` хранится в `BaseUrlProvider.baseUrl`;
- `NetworkViewModel` пишет `BaseUrlProvider.baseUrl` при загрузке сохраненных IP/port и после успешного теста соединения.

Минимальная замена на DI-класс:
- заменить `object AppPreferences` на `@Singleton class ServerPreferences @Inject constructor(@ApplicationContext context: Context)`;
- оставить тот же DataStore `settings` и те же ключи `ip` / `port`, чтобы не менять пользовательские данные;
- сигнатуры сделать без `Context`: `val ip: Flow<String?>`, `val port: Flow<String?>` или `fun getIp(): Flow<String?>`, `fun getPort(): Flow<String?>`, `suspend fun saveConnection(ip: String, port: String)`;
- `NetworkViewModel` должен получать `ServerPreferences` через Hilt вместо `@ApplicationContext Context` + `AppPreferences`.

Риск сигнатур небольшой, потому что сейчас меняется только конструктор и внутренние вызовы `NetworkViewModel`. При добавлении `ServerRepository` ViewModel может вообще не знать о DataStore.

### BaseUrlProvider

`BaseUrlProvider` находится в `app/src/main/java/ru/tbcarus/photo_cloud_client/di/BaseUrlProvider.kt`.

Сейчас это `@Singleton class` с mutable property:
- `var baseUrl: String = ""`;
- `val isReady: Boolean get() = baseUrl.isNotBlank()`.

Где изменяется:
- `NetworkViewModel.init`: если сохранены IP и port, устанавливает `http://$ip:$port/`;
- `NetworkViewModel.testConnection`: после успешного ping сохраняет `baseUrl`.

Где читается:
- `AuthRepository.isReady`;
- `AuthRepository.plainService`;
- `AuthRepository.authService`;
- `AuthRepository.authTestService`;
- `TokenAuthenticator.authenticate`.

Вывод: на runtime-уровне `BaseUrlProvider` сейчас является единственным source of truth для `baseUrl`. При этом persisted source of truth для адреса - DataStore IP/port. Между ними есть ручная синхронизация через `NetworkViewModel`.

На Этапе 2A его можно оставить runtime holder-ом, чтобы не переписывать auth/session flow. Достаточно централизовать запись в него через `ServerRepository` после успешного теста соединения и при загрузке сохраненных настроек.

Риски при замене:
- если заменить `BaseUrlProvider` на реактивный/персистентный источник сразу, можно затронуть auth, refresh token и создание Retrofit;
- если начать кешировать Retrofit-сервисы, смена `baseUrl` перестанет применяться к уже созданным сервисам;
- `baseUrl` не синхронизирован между потоками, но текущая модель с ad-hoc Retrofit частично маскирует это.

### NetworkViewModel

`NetworkViewModel` сейчас выполняет сразу несколько ролей:
- держит `NetworkUiState`;
- валидирует IP/port через `isValidIpAddress`;
- читает сохраненные IP/port из DataStore;
- собирает `baseUrl`;
- пишет `BaseUrlProvider.baseUrl`;
- создает Retrofit вручную для `TestService`;
- выполняет sync `testServer().execute()` на `Dispatchers.IO`;
- сохраняет IP/port в DataStore после успешного ответа;
- маппит `IOException`, `HttpException`, `Exception` в UI-сообщения.

Что можно вынести в `ServerRepository`:
- чтение сохраненных server settings;
- сохранение IP/port;
- сборку `baseUrl`;
- запись в `BaseUrlProvider`;
- ping/test connection через plain `OkHttpClient` + `TestService`.

Что лучше пока оставить во ViewModel:
- `NetworkUiState`, `isLoading`, `connectionStatus`, `message`;
- обработчики `onIpChange`, `onPortChange`, `clearMessage`;
- базовую UI-валидацию IP/port и текущие тексты ошибок, чтобы не менять поведение экрана;
- маппинг результата repository в существующие поля UI.

Для сохранения текущего поведения UI важно оставить:
- автозагрузку сохраненных IP/port в `init`;
- автоматический `testConnection()` после загрузки сохраненных настроек;
- сохранение IP/port только после успешного ping;
- установку `ConnectionStatus.SUCCESS/ERROR` и `isLoading` в тех же сценариях.

### Retrofit creation points

Вручную `Retrofit.Builder()` сейчас создается в трех местах:
- `AuthRepository.plainService()` - `AuthService` на `@Named("plain") OkHttpClient`;
- `AuthRepository.authService()` - `AuthService` на `@Named("auth") OkHttpClient`;
- `AuthRepository.authTestService()` - `TestService` на `@Named("auth") OkHttpClient`;
- `TokenAuthenticator.refreshService(baseUrl)` - `AuthService` на plain client;
- `NetworkViewModel.testConnection()` - `TestService` на plain client.

Что можно безопасно убрать на Этапе 2A:
- ручное создание Retrofit из `NetworkViewModel`, перенеся его в `ServerRepository` или небольшой внутренний factory-метод repository.

Что лучше оставить до Этапа 3:
- `AuthRepository.plainService/authService/authTestService`;
- `TokenAuthenticator.refreshService`;
- Java Retrofit interfaces `AuthService` и `TestService`;
- auth/session flow.

Причина: auth-цепочка связана с `AuthInterceptor`, `TokenAuthenticator`, token refresh и динамическим `baseUrl`. Централизация Retrofit для auth без отдельного решения о смене base URL может изменить поведение.

### Hilt / DI

Текущие DI-модули:
- `AppModule` - предоставляет `AppDatabase` и `MediaFileDao`;
- `StorageModule` - биндинг `EncryptedPrefsTokenStorage` как `TokenStorage`;
- `NetworkModule` - предоставляет logging interceptor, `@Named("plain") OkHttpClient`, `@Named("auth") OkHttpClient`;
- `BaseUrlProvider` создается constructor injection через `@Singleton @Inject constructor`.

Что можно добавить без риска на Этапе 2A:
- `ServerPreferences` как `@Singleton` constructor-injected класс;
- `ServerRepository` как `@Singleton` constructor-injected класс;
- при необходимости `DataStore<Preferences>` provider, но проще и менее инвазивно оставить `preferencesDataStore` extension внутри файла storage-класса и инжектить только `ApplicationContext`.

Отдельный `RepositoryModule` не обязателен, если `ServerRepository` имеет `@Inject constructor`. Он понадобится только если появятся интерфейсы (`ServerRepository` interface + implementation) или внешние фабрики.

Рекомендуемая инъекция:
- `NetworkViewModel @Inject constructor(serverRepository: ServerRepository)`;
- `ServerRepository @Inject constructor(serverPreferences: ServerPreferences, baseUrlProvider: BaseUrlProvider, @Named("plain") plainClient: OkHttpClient)`;
- `ServerPreferences @Inject constructor(@ApplicationContext context: Context)`.

## 2. Предлагаемый минимальный план Этапа 2A

1. Ввести `ServerPreferences`.
   - Сохранить DataStore name `settings`.
   - Сохранить ключи `ip` и `port`.
   - Убрать передачу `Context` из публичных методов.
   - Не менять формат хранимых данных.

2. Ввести `ServerRepository` для server settings и ping/test connection.
   - Методы уровня repository: загрузить IP/port, протестировать соединение, сохранить успешное соединение, обновить `BaseUrlProvider`.
   - Retrofit для `TestService` с plain client перенести из `NetworkViewModel` в repository.
   - На этом этапе не делать общий Retrofit singleton для auth.

3. Очистить `NetworkViewModel` от прямого Retrofit/DataStore.
   - Убрать `@ApplicationContext Context`, `AppPreferences`, `Retrofit`, `GsonConverterFactory`, `TestService`, прямой `OkHttpClient`.
   - Оставить ViewModel ответственным за UI-state, loading и сообщения.
   - Сохранить текущий сценарий: загрузить сохраненные настройки, показать их в UI, автоматически проверить соединение.

4. DI.
   - Использовать constructor injection для `ServerPreferences` и `ServerRepository`.
   - Отдельный `RepositoryModule` не создавать, пока нет интерфейсов или binding-ов.

## 3. Что НЕ трогать на Этапе 2A

- Java API interfaces (`AuthService`, `TestService`);
- `AuthRepository`;
- `TokenAuthenticator` logic;
- `AuthInterceptor` logic;
- auth/session flow;
- UI screens и навигацию;
- file/media/sync logic;
- Room `AppDatabase` и media entities/DAO;
- token storage implementation;
- общую централизованную auth Retrofit-фабрику.

## 4. Риски

### Перенос AppPreferences

Что может сломаться:
- потеря сохраненных IP/port, если поменять DataStore name или ключи;
- дублирование DataStore instance, если объявить несколько `preferencesDataStore` extension с одним именем в разных местах без аккуратной миграции;
- изменение момента чтения настроек, если repository начнет возвращать не `first()`, а live Flow;
- случайное сохранение настроек до успешного ping.

Проверки:
- запуск приложения с уже сохраненными IP/port;
- Network screen должен заполнить поля из DataStore;
- после успешного ping IP/port должны сохраниться;
- после перезапуска `BaseUrlProvider` должен восстановиться через загрузку настроек.

### Вынос testConnection из NetworkViewModel

Что может сломаться:
- текущие UI-сообщения и статусы, если repository начнет иначе маппить ошибки;
- `isLoading` может зависнуть при исключении, если ViewModel потеряет `finally`/единый update;
- автотест соединения в `init` может исчезнуть или запуститься до заполнения `uiState`;
- можно случайно начать сохранять IP/port при HTTP error.

Проверки:
- невалидный IP/пустой порт;
- недоступный сервер;
- сервер отвечает HTTP error;
- сервер отвечает successful response с `message`;
- сохранение происходит только при success.

### Централизация Retrofit

Что может сломаться:
- auth requests могут пойти на старый `baseUrl`, если создать singleton Retrofit до установки server URL;
- `TokenAuthenticator` может попасть в refresh-loop или использовать auth client вместо plain client;
- `AuthInterceptor` может начать добавлять токен к refresh/login/register, если перепутать clients;
- смена сервера после старта приложения не применится к закешированным сервисам.

Проверки:
- register/login/logout/testAuth после выбора сервера;
- 401 + refresh token path;
- пустой `baseUrl` до настройки сервера;
- смена IP/port на другой сервер в runtime.

## 5. Вопросы и уточнения перед реализацией

1. Подтвердить имя нового класса: `ServerPreferences` или более широкое `ServerSettingsStorage`.
2. Подтвердить модель результата `ServerRepository.testConnection`: вернуть простой sealed/result DTO для ViewModel или оставить `Result<String>` + отдельный status mapping во ViewModel.
3. Решить, должен ли `ServerRepository` сам валидировать IP/port или ViewModel пока остается владельцем UI-валидации.
4. Подтвердить, что на Этапе 2A `BaseUrlProvider` остается mutable runtime holder-ом без Flow/StateFlow.
5. Подтвердить, что auth Retrofit creation в `AuthRepository` и `TokenAuthenticator` переносится только на Этапе 3.

