package ru.tbcarus.photo_cloud_client.ui.screens.files

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tbcarus.photo_cloud_client.media.CameraCheck
import ru.tbcarus.photo_cloud_client.media.CameraCheckCounts
import ru.tbcarus.photo_cloud_client.media.CameraCheckError
import ru.tbcarus.photo_cloud_client.media.CameraCheckStage
import ru.tbcarus.photo_cloud_client.media.CameraCheckState
import ru.tbcarus.photo_cloud_client.media.CameraCheckView
import ru.tbcarus.photo_cloud_client.ui.screens.library.ComposeTestSetup

/** Карточка «Проверка копий»: в каждом состоянии видны нужные действия и пауза. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class CameraCheckCardTest {

    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeTestSetup()).around(compose)

    private var view by mutableStateOf<CameraCheckView?>(null)
    private val clicks = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            CameraCheckCard(
                view = view,
                busy = false,
                message = null,
                actions = CameraCheckActions(
                    onStart = { clicks += "start" },
                    onCancel = { clicks += "cancel" },
                    onRetry = { clicks += "retry" },
                    onConfirm = { clicks += "confirm" },
                    onDefer = { clicks += "defer" },
                    onDismiss = { clicks += "dismiss" }
                )
            )
        }
    }

    private fun check(state: CameraCheckState, stage: CameraCheckStage, counts: CameraCheckCounts = CameraCheckCounts(), deferred: Boolean = false, error: CameraCheckError? = null) =
        CameraCheckView(
            CameraCheck(runId = "r", state = state, stage = stage, error = error, serverUrl = "u", accountId = "a", startedAt = 1, deferred = deferred),
            counts
        )

    @Test
    fun runningShowsProgressPauseAndCancel() {
        show()
        compose.onNodeWithText("Проверить копии").performClick()

        view = check(CameraCheckState.RUNNING, CameraCheckStage.HASH, CameraCheckCounts(total = 4, prepared = 1))
        compose.onNodeWithText("Шаг 2 из 4: Вычисление checksum — 1 / 4 (25%)").assertIsDisplayed()
        compose.onNodeWithText(CHECK_PAUSE_TEXT).assertIsDisplayed()
        compose.onNodeWithText(CHECK_CANCEL_HINT).assertIsDisplayed()
        compose.onNodeWithText("Отменить проверку").performClick()

        assertEquals(listOf("start", "cancel"), clicks)
    }

    @Test
    fun errorOffersRetryAndCancel() {
        view = check(CameraCheckState.ERROR, CameraCheckStage.SERVER, error = CameraCheckError.SERVER_UNAVAILABLE)
        show()
        compose.onNodeWithText("Сервер не отвечает.").assertIsDisplayed()
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithText("Отменить проверку").performClick()
        assertEquals(listOf("retry", "cancel"), clicks)
    }

    @Test
    fun resultOffersUploadOrLaterAndDeferredKeepsPauseVisible() {
        val counts = CameraCheckCounts(total = 5, confirmed = 2, missing = 3)
        view = check(CameraCheckState.AWAITING_DECISION, CameraCheckStage.DONE, counts)
        show()
        compose.onNodeWithText("Не найдено на сервере: 3").assertIsDisplayed()
        compose.onNodeWithText("Позже").performClick()

        view = check(CameraCheckState.AWAITING_DECISION, CameraCheckStage.DONE, counts, deferred = true)
        compose.onNodeWithText("Автоматическая загрузка Camera приостановлена: 3 файла ждут решения.").assertIsDisplayed()
        compose.onNodeWithText("Загрузить 3 файла").performClick()
        compose.onNodeWithText("Отказаться от проверки").performClick()

        assertEquals(listOf("defer", "confirm", "cancel"), clicks)
    }

    @Test
    fun deferredResultIsCollapsedAndOpensOnRequest() {
        val counts = CameraCheckCounts(total = 5, confirmed = 4, missing = 1)
        view = check(CameraCheckState.AWAITING_DECISION, CameraCheckStage.DONE, counts, deferred = true)
        show()
        compose.onNodeWithText("Автоматическая загрузка Camera приостановлена: 1 файл ждёт решения.").assertIsDisplayed()
        compose.onNodeWithText("Загрузить 1 файл").assertIsDisplayed()
        compose.onNodeWithText("Не найдено на сервере: 1").assertDoesNotExist()

        compose.onNodeWithText("Показать результат").performClick()
        compose.onNodeWithText("Не найдено на сервере: 1").assertIsDisplayed()
        compose.onNodeWithText("Скрыть результат").performClick()
        compose.onNodeWithText("Не найдено на сервере: 1").assertDoesNotExist()
        assertEquals(emptyList<String>(), clicks)
    }

    @Test
    fun emptyCameraSaysThereIsNothingToCheck() {
        view = CameraCheckView(
            CameraCheck(runId = "r", state = CameraCheckState.FINISHED, stage = CameraCheckStage.DONE, serverUrl = "u",
                accountId = "a", startedAt = 1, finishedAt = 2),
            CameraCheckCounts()
        )
        show()
        compose.onNodeWithText("Нет фотографий для проверки.").assertIsDisplayed()
        compose.onNodeWithText("Все проверенные файлы есть на сервере.").assertDoesNotExist()
    }
}
