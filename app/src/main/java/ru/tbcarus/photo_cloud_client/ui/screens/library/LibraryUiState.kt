package ru.tbcarus.photo_cloud_client.ui.screens.library

import ru.tbcarus.photo_cloud_client.api.models.FileItemDto
import ru.tbcarus.photo_cloud_client.api.models.FolderDto

sealed interface LibraryStatus {
    /** Контекст пригоден, загрузка начнётся при открытии экрана. */
    data object Idle : LibraryStatus
    data object Loading : LibraryStatus
    data object Content : LibraryStatus
    data object NoServer : LibraryStatus
    data object NoSession : LibraryStatus
    data class Error(val message: String) : LibraryStatus
}

data class LibraryUiState(
    val status: LibraryStatus = LibraryStatus.Idle,
    // Открытые папки от ROOT до текущей: API не отдаёт путь и не ищет папку по ID.
    val path: List<FolderDto> = emptyList(),
    val folders: List<FolderDto> = emptyList(),
    val files: List<FileItemDto> = emptyList(),
    val hasNext: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val notice: String? = null,
    // Идёт отключение источника открытой папки: повторное нажатие не отправит второй запрос.
    val disabling: Boolean = false,
    // Меняется с каждой загрузкой каталога: новый список начинается с начала.
    val contentVersion: Int = 0
) {
    val currentFolder: FolderDto? get() = path.lastOrNull()
    val canGoUp: Boolean get() = path.size > 1
    /**
     * AutoUploads и дерево активного источника меняет только автоматическое резервирование. Вложенные папки
     * backupSource не имеют: принадлежность дереву определяет корень источника на открытом пути.
     */
    val inManagedTree: Boolean get() = currentFolder?.isAutoUploads == true || path.any { it.isActiveBackupSource }
    /** Открыта сама корневая папка активного источника: только у неё Server хранит backupSource. */
    val isSourceRoot: Boolean get() = status == LibraryStatus.Content && currentFolder?.isActiveBackupSource == true
    // В Camera файлы попадают только через backup Android.
    val canUpload: Boolean get() = status == LibraryStatus.Content && currentFolder?.isCamera == false && !inManagedTree
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()
}
