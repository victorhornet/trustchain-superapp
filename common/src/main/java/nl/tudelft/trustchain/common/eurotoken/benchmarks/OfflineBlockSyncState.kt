package nl.tudelft.trustchain.common.eurotoken.benchmarks

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_block_sync_state")
data class OfflineBlockSyncState(
    @PrimaryKey val blockHash: String,
    val status: String = "Pending", // pending / syncing / synced
    val isSynced: Boolean = false
)
