package com.me2.android.notifications

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Me2InitiativeSchedulerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: Me2InitiativeScheduler

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        workManager = WorkManager.getInstance(context)
        scheduler = Me2InitiativeScheduler(context)
    }

    @Test fun repeatedStartupDoesNotDuplicatePeriodicAndPostSilenceReplaces() {
        scheduler.ensureScheduled()
        val first = workManager.getWorkInfosForUniqueWork(Me2InitiativeScheduler.PERIODIC_WORK).get().single().id
        repeat(4) {
            scheduler.ensureScheduled()
            scheduler.schedulePostSilenceEval()
        }
        val periodic = workManager.getWorkInfosForUniqueWork(Me2InitiativeScheduler.PERIODIC_WORK).get()
        assertEquals(listOf(first), periodic.map { it.id })
        assertEquals(1, workManager.getWorkInfosForUniqueWork(Me2InitiativeScheduler.EVENT_WORK).get().size)
    }

    @Test fun interactionCancelsPostSilenceEval() {
        scheduler.schedulePostSilenceEval()
        assertEquals(1, workManager.getWorkInfosForUniqueWork(Me2InitiativeScheduler.EVENT_WORK).get().size)
        scheduler.cancelPostSilenceEval()
        assertTrue(
            workManager.getWorkInfosForUniqueWork(Me2InitiativeScheduler.EVENT_WORK).get()
                .all { it.state == WorkInfo.State.CANCELLED }
        )
    }

    @Test fun cancellingInitiativesCancelsBothWorkTypes() {
        scheduler.ensureScheduled()
        scheduler.schedulePostSilenceEval()
        scheduler.cancel()
        for (name in listOf(Me2InitiativeScheduler.PERIODIC_WORK, Me2InitiativeScheduler.EVENT_WORK)) {
            assertTrue(workManager.getWorkInfosForUniqueWork(name).get().all { it.state == WorkInfo.State.CANCELLED })
        }
    }
}
