package nl.tudelft.trustchain.common.eurotoken.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.trustchain.common.eurotoken.GatewayStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageAnalyticsDatabase

// handles delayed gossiping when offline
class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Starting offline block sync check")

        return try {
            val ipv8 = IPv8Android.getInstance()
            val trustChainCommunity = ipv8.getOverlay<TrustChainCommunity>()
                ?: return Result.failure()

            val offlineBlockSyncDao = UsageAnalyticsDatabase.getInstance(applicationContext).offlineBlockSyncDao()

            // more efficient
            // lets first check whether we have any unsynced blocks
            val unsyncedHashes = offlineBlockSyncDao.getUnsyncedBlockHashes()
            if (unsyncedHashes.isEmpty()) {
                Log.d("SyncWorker", "No unsynced blocks found - canceling periodic sync")

                cancelPeriodicSyncIfEmpty()
                return Result.success()
            }

            // are there any peers to sync with?
            val availablePeers = trustChainCommunity.getPeers()
            if (availablePeers.isEmpty()) {
                Log.d("SyncWorker", "No peers available for syncing ${unsyncedHashes.size} blocks, will retry later")
                return Result.retry()
            }

            Log.d("SyncWorker", "Found ${unsyncedHashes.size} unsynced blocks and ${availablePeers.size} peers, starting sync...")

            val gatewayStore = GatewayStore.getInstance(applicationContext)
            val transactionRepository = TransactionRepository(
                trustChainCommunity,
                gatewayStore,
                offlineBlockSyncDao,
                applicationContext
            )

            // lets sync
            transactionRepository.syncOfflineTransactions()

            val remainingUnsyncedHashes = offlineBlockSyncDao.getUnsyncedBlockHashes()
            if (remainingUnsyncedHashes.isEmpty()) {
                Log.d("SyncWorker", "All blocks synced successfully - canceling periodic sync")
                cancelPeriodicSyncIfEmpty()
            } else {
                Log.d("SyncWorker", "Sync completed, ${remainingUnsyncedHashes.size} blocks still pending")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync worker failed", e)
            Result.retry()
        }
    }

    // TODO remove this ugly function name
    // works for now and clear atleast
    private fun cancelPeriodicSyncIfEmpty() {
        WorkManager.getInstance(applicationContext)
            .cancelUniqueWork("OfflineTransactionSync")
        Log.d("SyncWorker", "Canceled periodic sync - no blocks to sync")
    }

    companion object {
        // used when offline blocks are created so we try immediately to sync
        @Suppress("DEPRECATION")
        fun scheduleImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediateSync = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(2, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "ImmediateSyncWork",
                ExistingWorkPolicy.REPLACE,
                immediateSync
            )

            Log.d("SyncWorker", "Scheduled immediate sync for new offline blocks")
        }

        @Suppress("DEPRECATION")
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicSync = PeriodicWorkRequestBuilder<SyncWorker>(5, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "OfflineTransactionSync",
                ExistingPeriodicWorkPolicy.REPLACE,
                periodicSync
            )

            Log.d("SyncWorker", "Scheduled 5-minute periodic sync")
        }
    }
}

// class SyncWorker(appContext: Context, workerParams: WorkerParameters):
//     CoroutineWorker(appContext, workerParams) {

//     override suspend fun doWork(): Result {
//         Log.d("SyncWorker", "Starting offline block sync")

//         // Initialize dependencies required by the TransactionRepository
//         val ipv8 = IPv8Android.getInstance()
//         val trustChainCommunity = ipv8.getOverlay<TrustChainCommunity>()
//             ?: return Result.failure()

//         val gatewayStore = GatewayStore.getInstance(applicationContext)
//         val offlineBlockSyncDao = UsageAnalyticsDatabase.getInstance(applicationContext).offlineBlockSyncDao()

//         val transactionRepository = TransactionRepository(
//             trustChainCommunity,
//             gatewayStore,
//             offlineBlockSyncDao,
//             applicationContext
//         )

//         // Call the synchronization function
//         transactionRepository.syncOfflineTransactions()

//         Log.d("SyncWorker", "Offline block sync completed")
//         return Result.success()
//     }
// }
