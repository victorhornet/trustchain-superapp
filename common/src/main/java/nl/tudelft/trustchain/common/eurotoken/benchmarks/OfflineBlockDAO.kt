package nl.tudelft.trustchain.common.eurotoken.benchmarks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OfflineBlockSyncDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncState(state: OfflineBlockSyncState)

    @Query("SELECT blockHash FROM offline_block_sync_state WHERE isSynced = 0")
    suspend fun getUnsyncedBlockHashes(): List<String>

    @Query("UPDATE offline_block_sync_state SET isSynced = 1, status = 'Synced' WHERE blockHash = :blockHash")
    suspend fun markAsSynced(blockHash: String)

    // no blockid, just blockhash
    @Query("SELECT * FROM offline_block_sync_state WHERE blockHash = :blockHash")
    suspend fun getSyncState(blockHash: String): OfflineBlockSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSyncState(syncState: OfflineBlockSyncState)
}
