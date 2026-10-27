package ru.tbcarus.photo_cloud_client.media

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Разрешения экрана Settings: уровень доступа к фото по версии Android, геоданные и признак
 * блокировки повторного запроса. Состояние каждый раз читается у Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MediaPermissionRequestTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun android14DistinguishesFullPartialAndNoAccess() {
        assertEquals(MediaAccess.NONE, context.mediaAccess())

        // «Выбранные фото»: Android выдаёт только READ_MEDIA_VISUAL_USER_SELECTED — это не полный доступ.
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(MediaAccess.PARTIAL, context.mediaAccess())
        assertFalse(context.hasMediaReadPermission())

        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(MediaAccess.FULL, context.mediaAccess())
        assertTrue(context.hasMediaReadPermission())
    }

    @Test
    fun android14RequestsFullAndPartialTogether() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            mediaPermissionRequest
        )
    }

    @Test
    @Config(sdk = [33])
    fun android13UsesReadMediaImagesWithoutPartialAccess() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), mediaPermissionRequest)
        // До Android 14 частичного доступа нет: посторонний USER_SELECTED не считается выбранными фото.
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(MediaAccess.NONE, context.mediaAccess())

        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(MediaAccess.FULL, context.mediaAccess())
    }

    @Test
    @Config(sdk = [32])
    fun android12UsesReadExternalStorage() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), mediaPermissionRequest)
        assertEquals(MediaAccess.NONE, context.mediaAccess())

        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(MediaAccess.FULL, context.mediaAccess())
    }

    @Test
    fun mediaLocationIsReadFromAndroidEveryTime() {
        assertFalse(context.hasMediaLocationPermission())
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        assertTrue(context.hasMediaLocationPermission())
        // Отзыв в системных настройках виден при следующей проверке — копии состояния нет.
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        assertFalse(context.hasMediaLocationPermission())
    }

    @Test
    fun singleDenialWithoutRationaleIsNotTreatedAsBlocked() {
        // Первый запрос или диалог, закрытый без ответа: rationale=false и до, и после.
        assertFalse(blocked(rationaleBefore = false, rationaleAfter = false, deniedBefore = false))
        // Первый отказ, после которого Android ещё покажет диалог.
        assertFalse(blocked(rationaleBefore = false, rationaleAfter = true, deniedBefore = false))
    }

    @Test
    fun repeatedOrFinalDenialIsBlocked() {
        // Повторный запрос снова не дал разрешения и rationale нет — диалог больше не показывается.
        assertTrue(blocked(rationaleBefore = false, rationaleAfter = false, deniedBefore = true))
        // Пользователь только что выбрал «больше не спрашивать»: rationale исчез.
        assertTrue(blocked(rationaleBefore = true, rationaleAfter = false, deniedBefore = false))
        assertFalse(
            isPermissionRequestBlocked(
                granted = true, rationaleBeforeRequest = true, rationaleAfterRequest = false, deniedBefore = true
            )
        )
    }

    @Test
    fun appSettingsIntentOpensThisAppDetails() {
        val intent = context.appSettingsIntent()

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    private fun blocked(rationaleBefore: Boolean, rationaleAfter: Boolean, deniedBefore: Boolean) =
        isPermissionRequestBlocked(
            granted = false,
            rationaleBeforeRequest = rationaleBefore,
            rationaleAfterRequest = rationaleAfter,
            deniedBefore = deniedBefore
        )
}
