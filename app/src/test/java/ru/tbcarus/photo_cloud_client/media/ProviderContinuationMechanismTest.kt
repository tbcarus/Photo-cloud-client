package ru.tbcarus.photo_cloud_client.media

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Механизм продолжения Поставщика (A8.1) на настоящем WorkManager 2.9: вместо ProviderWorker
 * под его именем класса выполняется сценарный Worker, который делает то же, что и Поставщик в конце
 * прогона, — регистрирует продолжение через [SyncScheduler.continueProvider] и возвращает результат.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProviderContinuationMechanismTest {

    /** Действие одного прогона; без заданного действия прогон просто завершается успехом. */
    private fun interface Step {
        suspend fun ScriptedWorker.run(): WorkResult
    }

    private class Run(val id: UUID, val oneTime: Boolean)

    private lateinit var context: Application
    private lateinit var scheduler: SyncScheduler
    private val workManager get() = WorkManager.getInstance(context)
    private val steps = ConcurrentLinkedQueue<Step>()
    private val runs = CopyOnWriteArrayList<Run>()

    private inner class ScriptedWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {

        val oneTime get() = SyncScheduler.ONE_TIME_TAG in tags

        override suspend fun doWork(): WorkResult {
            runs += Run(id, oneTime)
            val step = steps.poll() ?: return WorkResult.success()
            return with(step) { run() }
        }

        /** Как в Поставщике: продолжение подтверждено записью в базу WorkManager до возврата результата. */
        suspend fun registerContinuation() {
            scheduler.continueProvider(fromOneTimeRun = oneTime).await()
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? =
                if (workerClassName == ProviderWorker::class.java.name) ScriptedWorker(appContext, workerParameters) else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        )
        scheduler = SyncScheduler(context, SyncStatusStore())
    }

    @Test
    fun continuationStartsOnlyAfterCurrentRunSucceeds() = runBlocking {
        val release = CompletableDeferred<Unit>()
        steps += Step {
            registerContinuation()
            // Продолжение уже записано, но ждёт текущий прогон: тот не отменён и продолжает работать.
            release.await()
            WorkResult.success()
        }

        scheduler.enqueueProvider()
        awaitUntil { states() == listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED) }
        assertEquals(1, runs.size)

        release.complete(Unit)
        awaitUntil { runs.size == 2 && allFinished() }

        assertEquals(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED), states())
        assertTrue(runs.all { it.oneTime })
    }

    @Test
    fun externalKeepSignalsDoNotTouchTheChain() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val releaseContinuation = CompletableDeferred<Unit>()
        steps += Step {
            registerContinuation()
            release.await()
            WorkResult.success()
        }
        steps += Step {
            releaseContinuation.await()
            WorkResult.success()
        }
        scheduler.enqueueProvider()
        awaitUntil { states() == listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED) }

        // Наблюдатель MediaStore, старт процесса и UI ставят Поставщика с KEEP.
        repeat(3) { scheduler.enqueueProvider() }
        assertEquals(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), states())

        release.complete(Unit)
        // Пока продолжение выполняется, KEEP тоже ничего не добавляет.
        awaitUntil { states() == listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING) }
        repeat(3) { scheduler.enqueueProvider() }
        assertEquals(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING), states())

        releaseContinuation.complete(Unit)
        awaitUntil { allFinished() }

        assertEquals(2, runs.size)
        assertEquals(2, infos().size)
    }

    @Test
    fun seriesOfContinuationsKeepsAtMostOnePendingRunAndIsClearedAfterwards() = runBlocking {
        val maxUnfinished = java.util.concurrent.atomic.AtomicInteger()
        repeat(4) {
            steps += Step {
                registerContinuation()
                maxUnfinished.accumulateAndGet(infos().count { !it.state.isFinished }) { a, b -> maxOf(a, b) }
                WorkResult.success()
            }
        }

        scheduler.enqueueProvider()
        awaitUntil { runs.size == 5 && allFinished() }

        // Текущий прогон и одно ожидающее продолжение — не больше.
        assertEquals(2, maxUnfinished.get())
        assertEquals(5, infos().size)

        // Следующая постановка KEEP после окончания серии удаляет завершённые записи цепочки.
        scheduler.enqueueProvider()
        awaitUntil { runs.size == 6 && allFinished() }
        assertEquals(1, infos().size)
    }

    @Test
    fun periodicRunStartsOneTimeChainButNeverCancelsRunningOne() = runBlocking {
        val release = CompletableDeferred<Unit>()
        // 1. Разовый прогон держит цепочку RUNNING.
        steps += Step {
            release.await()
            WorkResult.success()
        }
        scheduler.enqueueProvider()
        awaitUntil { states() == listOf(WorkInfo.State.RUNNING) }
        val oneTimeId = infos().single().id

        // 2. Периодический прогон в это время регистрирует продолжение — KEEP по имени разовой задачи.
        steps += Step {
            registerContinuation()
            WorkResult.success()
        }
        val periodicId = runPeriodic()
        awaitUntil { runs.any { it.id == periodicId } && periodicInfo().state == WorkInfo.State.ENQUEUED }

        assertFalse(runs.single { it.id == periodicId }.oneTime)
        assertEquals(listOf(oneTimeId), infos().map { it.id })
        assertEquals(WorkInfo.State.RUNNING, infos().single().state)

        release.complete(Unit)
        awaitUntil { allFinished() }
        assertEquals(WorkInfo.State.SUCCEEDED, infos().single().state)

        // 3. Без активной разовой цепочки продолжение периодического прогона ставит новую разовую.
        steps += Step {
            registerContinuation()
            WorkResult.success()
        }
        WorkManagerTestInitHelper.getTestDriver(context)!!.apply {
            setPeriodDelayMet(periodicId)
            setAllConstraintsMet(periodicId)
        }
        awaitUntil { runs.count { it.id == periodicId } == 2 }
        awaitUntil { runs.count { it.oneTime } == 2 && allFinished() }
        assertEquals(1, infos().size)
    }

    @Test
    fun failedRunDoesNotLeaveBlockedContinuation() = runBlocking {
        steps += Step {
            registerContinuation()
            WorkResult.failure()
        }
        scheduler.enqueueProvider()
        awaitUntil { allFinished() }

        // Продолжение не осталось BLOCKED навсегда: оно завершено вместе с упавшим прогоном.
        assertEquals(listOf(WorkInfo.State.FAILED, WorkInfo.State.FAILED), states())

        // Следующие сигналы и продолжения снова работают: KEEP видит только завершённые записи.
        scheduler.enqueueProvider()
        awaitUntil { runs.size == 2 && allFinished() }
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), states())
    }

    @Test
    fun cancelledRunDoesNotLeaveBlockedContinuation() = runBlocking {
        val release = CompletableDeferred<Unit>()
        steps += Step {
            registerContinuation()
            release.await()
            WorkResult.success()
        }
        scheduler.enqueueProvider()
        awaitUntil { states() == listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED) }

        workManager.cancelUniqueWork(SyncScheduler.PROVIDER_WORK_NAME).await()
        awaitUntil { allFinished() }
        assertEquals(listOf(WorkInfo.State.CANCELLED, WorkInfo.State.CANCELLED), states())

        // APPEND_OR_REPLACE поверх отменённой цепочки начинает новую, а не ждёт отменённую.
        scheduler.continueProvider(fromOneTimeRun = true).await()
        awaitUntil { runs.size == 2 && allFinished() }
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), states())
    }

    @Test
    fun runRepeatedAfterRegisteringContinuationAddsOnlyFiniteExtraRuns() = runBlocking {
        // Модель сбоя после регистрации продолжения: прогон повторяется (как после перезапуска процесса)
        // и регистрирует продолжение ещё раз. Продолжения без работы ничего не регистрируют.
        steps += Step {
            registerContinuation()
            WorkResult.retry()
        }
        steps += Step {
            registerContinuation()
            WorkResult.success()
        }
        scheduler.enqueueProvider()
        awaitUntil { runs.size == 1 && infos().any { it.state == WorkInfo.State.ENQUEUED } }
        // Повтор после backoff: в тестовом WorkManager задержка снимается вручную.
        val firstId = runs.first().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(firstId)

        awaitUntil { allFinished() }
        // Первый прогон дважды + два добавленных продолжения — цепочка конечна.
        assertEquals(4, runs.size)
        assertEquals(firstId, runs[1].id)
        assertTrue(states().all { it == WorkInfo.State.SUCCEEDED })
    }

    private fun runPeriodic(): UUID {
        scheduler.enqueueProviderPeriodic()
        val id = workManager.getWorkInfosForUniqueWork(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).get().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        return id
    }

    private fun periodicInfo(): WorkInfo =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.PROVIDER_PERIODIC_WORK_NAME).get().single()

    private fun infos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.PROVIDER_WORK_NAME).get()

    /** Состояния цепочки в порядке запусков: сначала записи прогонов, затем ещё не запускавшиеся. */
    private fun states(): List<WorkInfo.State> {
        val order = runs.map { it.id }
        return infos().sortedBy { info -> order.indexOf(info.id).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { it.state }
    }

    private fun allFinished() = infos().all { it.state.isFinished }

    // Workers выполняются на Dispatchers.Default — ждём опросом с ограничением по времени.
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Условие не выполнено за отведённое время", condition())
    }
}
