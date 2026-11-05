package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf

/**
 * Подготовка Compose UI-теста под Robolectric, выполняется до правила Compose.
 * 1. Манифест ui-test-manifest в unit-тесты не попадает (без includeAndroidResources): активность регистрируется здесь.
 * 2. Фоновые задачи Room прежних тестовых классов завершаются уже после сброса SQLite в Robolectric, и
 *    kotlinx-coroutines-test приписывает их исключения первому следующему runTest — то есть правилу Compose.
 *    Пустой runTest принимает эти чужие исключения; исключения самого теста по-прежнему его валят.
 */
class ComposeTestSetup : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = ComponentName(app, ComponentActivity::class.java)
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
        try {
            runTest { }
        } catch (e: IllegalStateException) {
            // UncaughtExceptionsBeforeTest (класс internal): исключения фоновых задач предыдущих тестов, не этого.
            if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
        }
    }
}
