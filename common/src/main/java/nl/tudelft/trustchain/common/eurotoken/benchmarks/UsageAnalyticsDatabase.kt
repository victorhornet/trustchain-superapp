package nl.tudelft.trustchain.common.eurotoken.benchmarks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransactionStartEvent::class,
        TransactionErrorEvent::class,
        TransactionDoneEvent::class,
        TransactionCancelEvent::class,
        TransferStartEvent::class,
        TransferErrorEvent::class,
        TransferDoneEvent::class,
        TransferCancelledEvent::class,
        TransactionCheckpointStartEvent::class,
        TransactionCheckpointEndEvent::class,
        OfflineBlockSyncState::class
    ],
    version = 5, // Increment if schema changes.
    exportSchema = false
)
abstract class UsageAnalyticsDatabase : RoomDatabase() {

    abstract fun usageEventsDao(): UsageEventsDao
    abstract fun offlineBlockSyncDao(): OfflineBlockSyncDao

    companion object {
        @Volatile
        private var INSTANCE: UsageAnalyticsDatabase? = null

        fun getInstance(context: Context): UsageAnalyticsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UsageAnalyticsDatabase::class.java,
                    "eurotoken_usage_analytics_db"
                )
                    // Add migrations here if you change schema later
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
