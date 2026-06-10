# Этап 5B-4 — Минимальный FilesScreen для локального индекса фото: анализ перед реализацией

Документ — только анализ. Код не меняется, классы не создаются. Цель — зафиксировать минимальную архитектуру UI-слоя для отображения локального индекса фото и ручного запуска scan.

---

## 0. Контекст входа (проверено в коде)

| Элемент | Текущее состояние |
| --- | --- |
| `FilesScreen.kt` | stub: `CenteredText("Files")`, нет ViewModel, нет параметров |
| `NavigationGraph.kt` | `composable(Routes.Files) { FilesScreen() }` — без ViewModel |
| `Routes.Files` | `"files"` — уже существует |
| `MainScreen.kt` | имеет `SnackbarHostState`, но никуда не пробрасывает |
| `LoadingDialog` | есть: `CircularProgressIndicator` внутри Dialog, без параметров |
| `Dialogs.kt` | `showDialog(message, status, onDismiss)` — AlertDialog с OK-кнопкой |
| `ConnectionStatus` | enum NONE / SUCCESS / ERROR / LOADING с цветами — специфичен для network |
| `MediaFileDao.getAll()` | `ORDER BY createdAt DESC` — уже отсортирован |
| Существующий ViewModel-паттерн | `@HiltViewModel`, `MutableStateFlow(UiState)`, `viewModelScope.launch` |

---

## 1. Текущий FilesScreen и navigation

`FilesScreen` — полная заглушка, вызывает `CenteredText("Files")`. Нет ViewModel, нет state, нет параметров.

В `NavigationGraph` подключён прямолинейно: `composable(Routes.Files) { FilesScreen() }`. `NetworkViewModel` туда приходит параметром из `MainActivity → MainScreen → NavigationGraph`. Для `FilesViewModel` лучше **не повторять этот паттерн** и использовать `hiltViewModel()` прямо внутри `composable`-блока.

Необходимые изменения в `NavigationGraph`:
```kotlin
composable(Routes.Files) {
    val viewModel: FilesViewModel = hiltViewModel()
    FilesScreen(viewModel = viewModel)
}
```

Это единственная правка `NavigationGraph.kt`. Не нужно трогать `MainScreen`, `MainActivity` или параметры `NavigationGraph`.

---

## 2. FilesViewModel — рекомендуемая структура

### Пакет
`ui/screens/files/FilesViewModel.kt` и `ui/screens/files/FilesUiState.kt`

Аналог: `ui/screens/auth/AuthViewModel.kt` и `auth/AuthUiState.kt`.

### Конструктор
```kotlin
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val mediaFileRepository: MediaFileRepository
) : ViewModel()
```

`ChecksumRepository` — **не инжектировать** (см. §9). `MediaFileRepository` достаточен для scan + observeAll.

### Поля и методы
```
uiState: StateFlow<FilesUiState>   — подписка для UI
init { подписаться на observeAll() и постоянно обновлять files }
fun scanPhotos()                   — запуск scan в viewModelScope
fun clearMessage()                 — сброс message
```

`scanPhotos()` под капотом:
1. `_uiState.update { it.copy(isScanning = true, message = null, permissionDenied = false) }`
2. `when (val outcome = mediaFileRepository.scanImages())`
3. `ScanOutcome.Success` → форматировать ScanResult в `message`, `isScanning = false`
4. `ScanOutcome.PermissionDenied` → `permissionDenied = true`, `isScanning = false`
5. `ScanOutcome.Error` → `message = outcome.message`, `isScanning = false`

---

## 3. FilesUiState — рекомендуемая структура

```kotlin
data class FilesUiState(
    val files: List<MediaFile> = emptyList(),
    val isScanning: Boolean = false,
    val lastScanResult: ScanResult? = null,
    val permissionDenied: Boolean = false,
    val message: String? = null
)
```

### Отдельные counters (pending/hashing/checksumReady/failed) — НЕ нужны сейчас
`files: List<MediaFile>` уже содержит `status` каждого файла. Если нужны counters:
- **ViewModel** считает их как `val pendingCount = files.count { it.status == PENDING }` — простые вычисляемые свойства на основе `files`.
- Хранить отдельно в `UiState` нет смысла: дублирование данных, риск рассинхрона.
- В `FilesUiState` добавлять поля `pendingCount`, `failedCount` и т.п. — **нет**.
- На 5B-4 достаточно отображать `status` в строке файла; aggregate-счётчики — будущий этап.

### `lastScanResult` vs `message`
`lastScanResult: ScanResult?` в state — полезно, чтобы не форматировать строку внутри ViewModel (форматирование — ответственность UI). Вариант: держать оба поля — `lastScanResult` для данных и `message` для ошибок/не-Success состояний. ViewModel не строит строки, UI читает `ScanResult` напрямую. **Рекомендуется такой подход** вместо форматирования в ViewModel.

Итоговая уточнённая структура:
```kotlin
data class FilesUiState(
    val files: List<MediaFile> = emptyList(),
    val isScanning: Boolean = false,
    val lastScanResult: ScanResult? = null,    // данные последнего успешного scan
    val permissionDenied: Boolean = false,     // нет разрешения на медиа
    val errorMessage: String? = null           // ошибка прогона (не per-file)
)
```

---

## 4. Какие классы создать / файлы изменить

### Создать
| Файл | Пакет |
| --- | --- |
| `ui/screens/files/FilesUiState.kt` | data class |
| `ui/screens/files/FilesViewModel.kt` | `@HiltViewModel` |

### Изменить
| Файл | Изменение |
| --- | --- |
| `ui/screens/FilesScreen.kt` | полностью переписать (было заглушкой); принять `viewModel: FilesViewModel` как параметр |
| `ui/components/NavigationGraph.kt` | добавить `hiltViewModel()` в `composable(Routes.Files)` |

### НЕ трогать
`MainScreen.kt`, `MainActivity.kt`, `Routes.kt`, `AppModule.kt`, auth/network/session, DAO, Room schema, `MediaStoreRepository`, `ChecksumRepository`.

---

## 5. DAO: нужно ли менять для ordered list?

**Нет.** `MediaFileDao.getAll()` уже имеет `ORDER BY createdAt DESC`. `MediaFileRepository.observeAll()` делегирует в него. Никаких изменений DAO не требуется.

---

## 6. Как отображать permission denied

- В `FilesUiState.permissionDenied = true`.
- UI показывает текстовый баннер/предупреждение: например, `Card` или простой `Text` с иконкой.
- Кнопку "Открыть настройки" (`Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)`) — **не добавлять**.
- Кнопку runtime permission request — **не добавлять**.
- Оставить `TODO` в ViewModel/FilesScreen: `// TODO: runtime-запрос разрешений будет добавлен на UI-этапе.`

Минимальный UI:
```
⚠️ Нет доступа к фотографиям.
Разрешение будет запрошено на следующем этапе.
```

---

## 7. Как отображать scan result

`lastScanResult: ScanResult?` читается прямо в UI (не форматируется в ViewModel). Простой `Text`:
```
Результат: отсканировано 128, добавлено/обновлено 5, удалено устаревших 0
```
Форматирование — inline в FilesScreen через `private fun formatScanResult(result: ScanResult): String`. Никакого отдельного утилитного класса.

При `errorMessage != null` — показывать его вместо `lastScanResult`.

`LoadingDialog` из компонентов — **переиспользовать** для `isScanning = true`:
```kotlin
if (uiState.isScanning) { LoadingDialog() }
```

---

## 8. Как отображать список файлов

### LazyColumn, без thumbnails
`LazyColumn` — обязательно, не `Column` со `forEach`. Весь список ленивый; первые-N ограничение не нужно.

Thumbnails (Coil/Glide для `uri`) — **не добавлять** на этом этапе. Это увеличит scope (добавление зависимости) и замедлит разработку. Можно добавить как отдельный шаг.

### Строка файла (минимально)
Каждый `MediaFile` — один `ListItem` или `Card`:

| Поле | Отображение |
| --- | --- |
| `displayName` | основной текст (заголовок) |
| `relativePath` | второстепенный текст (подзаголовок) |
| `size` | отформатированный размер (KB/MB) |
| `status` | короткий badge или суффикс: `PENDING`, `CHECKSUM_READY` и т.д. |
| `checksum` | маркер: `✓` если не null, `–` если null |

Форматирование — `private fun` в `FilesScreen.kt`:
```kotlin
private fun formatSize(bytes: Long): String = ...   // "1.2 MB" или "456 KB"
private fun formatStatus(status: MediaFileStatus): String = ...
```
Никакого отдельного util-класса пока не нужно.

### Нет элементов
Когда `files.isEmpty()` и `isScanning = false` — показать: `CenteredText("Нет локальных фото. Нажмите «Сканировать».")`.

---

## 9. Почему ChecksumRepository не подключаем

**`ChecksumRepository` не инжектировать в `FilesViewModel` на 5B-4.** Причины:

1. В `FilesScreen` нет UI для запуска checksum (кнопки "Вычислить хэши") — добавлять её вне scope.
2. `status` и `checksum` уже хранятся в `MediaFile` и приходят через `observeAll()`. Отображать их в строке файла — **можно без** `ChecksumRepository`.
3. Следующий логичный порядок: scan → checksum → pre-check → upload. Добавлять checksum UI без отдельного анализа конкретного этапа — преждевременно.

Что показываем: поле `status` и наличие `checksum` (null / not null) в строке файла — **без** кнопки запуска. Этого достаточно, чтобы визуально убедиться в работе 5B-3 через scan.

---

## 10. Что НЕ делать

- Runtime permission request (кнопка / launcher) — нет.
- Thumbnails / загрузка изображений (Coil, Glide) — нет.
- Кнопка "Вычислить checksum" — нет.
- Server pre-check (`POST /files/checksums/exists`) — нет.
- Upload / sync — нет.
- WorkManager / фоновый scan — нет.
- Папки (`folders`) — нет.
- Video scan / display — нет.
- `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` intent — нет.
- Изменения в `auth/network/session` — нет.
- Изменения в `DAO`, `Room schema`, `AppDatabase` — нет.
- Отдельный formatter-util класс (форматирование inline в FilesScreen) — нет.

---

## 11. Вопросы / уточнения перед реализацией

1. **Формат scan result:** показывать как inline `Text` под кнопкой или через системный Snackbar (`SnackbarHostState`)? `MainScreen` уже имеет `SnackbarHostState`, но не пробрасывает его. Рекомендация: inline `Text` в `FilesScreen`, проще и не требует рефакторинга `MainScreen`. Подтвердить.
2. **Permission denied:** `Card`/`Banner` вверху экрана или `AlertDialog`? Рекомендация: `Card` сверху (не блокирует UI). Если предпочтителен `AlertDialog` — уточнить.
3. **`FilesScreen` принимает `FilesViewModel` как параметр или получает его через `hiltViewModel()` внутри?** Рекомендация: параметр ViewModel (как у `NetworkScreen`) — лучше тестируемость. Уточнить.
4. **Счётчики статусов** (pending/hashing/failed) — показывать как строку-сводку над списком или нет? Рекомендация: нет на 5B-4, только в строке файла. Подтвердить.
5. **Кнопка "Scan photos"** — `Button` на весь экран вверху? Или `FloatingActionButton`? Рекомендация: `Button` вверху экрана над списком, чтобы не трогать `MainScreen`/`Scaffold`. Подтвердить.
6. **Порядок: scan при открытии экрана?** Автоматически запускать scan при первом открытии `FilesScreen`? Рекомендация: только по нажатию кнопки, не автоматически. Подтвердить.
7. **`lastScanResult` в `FilesUiState` или только `message: String?`?** Рекомендация: `lastScanResult: ScanResult?` + `errorMessage: String?` раздельно — UI сам форматирует. Подтвердить.
