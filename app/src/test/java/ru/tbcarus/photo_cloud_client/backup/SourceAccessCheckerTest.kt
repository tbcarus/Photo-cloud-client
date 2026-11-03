package ru.tbcarus.photo_cloud_client.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Сохранённое разрешение SAF, его отзыв и недоступный провайдер — на ContentResolver Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SourceAccessCheckerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val checker = SourceAccessChecker(context)
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")

    @Test
    fun persistedPermissionSurvivesAndRevocationIsPermissionLost() {
        assertEquals(SourceAccess.PERMISSION_LOST, checker.check(tree))

        assertTrue(checker.persist(tree))
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })

        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(SourceAccess.PERMISSION_LOST, checker.check(tree))
    }

    @Test
    fun unreachableProviderWithSavedPermissionIsOnlyUnavailable() {
        checker.persist(tree)

        // Провайдер документов не зарегистрирован: запрос не выполняется, но разрешение не отозвано.
        assertEquals(SourceAccess.UNAVAILABLE, checker.check(tree))
    }
}
