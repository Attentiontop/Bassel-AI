package com.attentiontop.basselai

import android.content.Context
import androidx.room.*

@Entity(tableName = "ai_models_table")
data class ModelEntity(
    @PrimaryKey val providerName: String,
    val apiKey: String,
    val modelId: String
)

@Dao
interface ModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateModel(model: ModelEntity)

    @Query("SELECT * FROM ai_models_table WHERE providerName = :provider")
    suspend fun getModelByProvider(provider: String): ModelEntity?

    @Query("SELECT * FROM ai_models_table")
    suspend fun getAllModels(): List<ModelEntity>
}

@Database(entities = [ModelEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bassel_ai_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
