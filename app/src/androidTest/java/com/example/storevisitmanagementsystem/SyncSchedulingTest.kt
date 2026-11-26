package com.example.storevisitmanagementsystem

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.storevisitmanagementsystem.sync.SyncManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncSchedulingTest {
    @Test
    fun newMutationAppendsWithoutCancellingRunningWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        // Use a unique account with no session or records. The real successor exits early.
        val userId = "sync_test_" + UUID.randomUUID()
        val workName = "StoreVisitSyncWork_$userId"
        val first = OneTimeWorkRequestBuilder<HoldSyncTestWorker>().build()
        HoldSyncTestWorker.release = CompletableDeferred()

        try {
            withTimeout(20_000) {
                manager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, first)
                manager.getWorkInfoByIdFlow(first.id).first { it?.state == WorkInfo.State.RUNNING }

                SyncManager.triggerImmediateSync(context, userId)
                val chained = manager.getWorkInfosForUniqueWorkFlow(workName).first { it.size == 2 }
                assertEquals(WorkInfo.State.RUNNING, chained.single { it.id == first.id }.state)
                assertEquals(WorkInfo.State.BLOCKED, chained.single { it.id != first.id }.state)

                HoldSyncTestWorker.release.complete(Unit)
                val finished = manager.getWorkInfosForUniqueWorkFlow(workName)
                    .first { it.size == 2 && it.all { info -> info.state.isFinished } }
                assertEquals(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED), finished.map { it.state })
            }
        } finally {
            HoldSyncTestWorker.release.complete(Unit)
            manager.cancelUniqueWork(workName)
        }
    }
}

class HoldSyncTestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        lateinit var release: CompletableDeferred<Unit>
    }

    override suspend fun doWork(): Result {
        release.await()
        return Result.success()
    }
}
