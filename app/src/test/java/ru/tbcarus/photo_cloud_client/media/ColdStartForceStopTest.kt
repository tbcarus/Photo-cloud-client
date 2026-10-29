package ru.tbcarus.photo_cloud_client.media

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.background.systemjob.SystemJobService
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.util.concurrent.Executor

/**
 * Холодный старт после `am kill` с настоящим WorkManager 2.9 (A4.3).
 * Последний выход процесса — REASON_USER_REQUESTED, поэтому ForceStopRunnable отменяет все job приложения
 * и перепланирует задания. Очередь задач WorkManager выполняется вручную, по порядку постановки:
 * сначала то, что поставила инициализация, затем постановка из [ProcessStartSync].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ColdStartForceStopTest {

    private companion object {
        const val STALE_TRIGGER_JOB_ID = 1
    }

    /** Executor, задачи которого выполняются только вызовом [drain] — в порядке постановки. */
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) = synchronized(tasks) { tasks.addLast(command) }
        fun drain() {
            while (true) {
                val next = synchronized(tasks) { tasks.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val jobScheduler get() = context.getSystemService(JobScheduler::class.java)

    @After
    fun tearDown() {
        // Следующие тесты в этой песочнице ставят свой тестовый WorkManager через delegate.
        WorkManagerImpl.setDelegate(null)
    }

    @Test
    fun providerQueuedAtProcessStartSurvivesForceStopRescheduling() {
        shadowOf(context).grantPermissions(mediaReadPermission)
        // Прошлый процесс завершён `am kill`: Android записал REASON_USER_REQUESTED.
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setPid(4242)
                .setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
                .setTimestamp(System.currentTimeMillis())
                .build()
        )
        // Job наблюдателя из прошлого процесса, который «поднял» этот процесс.
        scheduleStaleTriggerJob()

        // Тот же путь сборки, что у WorkManager.initialize(): конструктор WorkManagerImpl первым ставит
        // ForceStopRunnable в последовательную очередь. Базу передаём явно: ресурс выбора тестовой БД
        // в unit-тестах проекта недоступен, а in-memory вариант (как в work-testing) допускает доступ из потока теста.
        val taskExecutor = ManualExecutor()
        val configuration = Configuration.Builder()
            .setTaskExecutor(taskExecutor)
            .setExecutor(ManualExecutor()) // Workers не выполняются: проверяем только планирование.
            .setWorkerFactory(PendingWorkerFactory)
            .build()
        val workTaskExecutor = WorkManagerTaskExecutor(configuration.taskExecutor)
        val database = WorkDatabase.create(context, workTaskExecutor.serialTaskExecutor, configuration.clock, true)
        WorkManagerImpl.setDelegate(WorkManagerImpl(context, configuration, workTaskExecutor, database))
        // Как в App.onCreate(): постановка сразу после инициализации, без ожидания.
        ProcessStartSync(context, SyncScheduler(context)).onProcessStart()

        taskExecutor.drain()

        val impl = WorkManagerImpl.getInstance(context)
        // Сработала именно ветка force-stop ForceStopRunnable.
        assertNotNull(impl.workDatabase.preferenceDao().getLongValue("last_force_stop_ms"))
        // cancelAll отменил job, который разбудил процесс.
        assertTrue(jobScheduler.allPendingJobs.none { it.id == STALE_TRIGGER_JOB_ID })
        // Поставщик пережил отмену: задание активно и запланировано в JobScheduler.
        val provider = impl.workDatabase.workSpecDao()
            .getWorkSpecIdAndStatesForName(SyncScheduler.PROVIDER_WORK_NAME).single()
        assertTrue(provider.state.name, provider.state == WorkInfo.State.ENQUEUED || provider.state == WorkInfo.State.RUNNING)
        val providerJobs = jobScheduler.allPendingJobs.filter { it.extras.getString("EXTRA_WORK_SPEC_ID") == provider.id }
        assertEquals(1, providerJobs.size)
    }

    private fun scheduleStaleTriggerJob() {
        val extras = PersistableBundle().apply { putString("EXTRA_WORK_SPEC_ID", "stale-media-trigger") }
        val job = JobInfo.Builder(STALE_TRIGGER_JOB_ID, ComponentName(context, SystemJobService::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
            .setExtras(extras)
            .build()
        assertEquals(JobScheduler.RESULT_SUCCESS, jobScheduler.schedule(job))
    }

    /** Worker, который никогда не завершается: состояние задания остаётся результатом планирования. */
    private object PendingWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker = object : ListenableWorker(appContext, workerParameters) {
            override fun startWork(): ListenableFuture<Result> = SettableFuture.create()
        }
    }
}
