package ru.tbcarus.photo_cloud_client.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Доступ к выбранному дереву SAF: только чтение, без разрешений на всё хранилище. */
@Singleton
open class SourceAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "SourceAccessChecker"
    }

    private val resolver get() = context.contentResolver

    /** Сохраняет разрешение на чтение после перезапуска; true — оно действительно есть в persistedUriPermissions. */
    open fun persist(treeUri: Uri): Boolean {
        try {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Разрешение выбора уже недействительно, например после гибели процесса.
            Log.w(TAG, "Не удалось сохранить доступ к папке: ${e.message}")
            return false
        }
        return isPersisted(treeUri)
    }

    /**
     * PERMISSION_LOST — только при отсутствии сохранённого разрешения. Ошибка запроса при сохранённом
     * разрешении — временная недоступность провайдера или папки, а не отзыв доступа.
     */
    open fun check(treeUri: Uri): SourceAccess {
        if (!isPersisted(treeUri)) return SourceAccess.PERMISSION_LOST
        return try {
            val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { if (it.moveToFirst()) SourceAccess.AVAILABLE else SourceAccess.UNAVAILABLE }
                ?: SourceAccess.UNAVAILABLE
        } catch (e: Exception) {
            Log.w(TAG, "Папка источника недоступна: ${e.message}")
            SourceAccess.UNAVAILABLE
        }
    }

    private fun isPersisted(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
}
