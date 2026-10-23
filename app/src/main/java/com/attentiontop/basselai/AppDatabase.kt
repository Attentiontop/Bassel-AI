package com.attentiontop.basselai

import android.content.Context
import androidx.room.*

// 1. جدول تخزين إعدادات الوكلاء والنماذج (Model/Agent Entity)
@Entity(tableName = "ai_models_table")
data class ModelEntity(
    @PrimaryKey val providerName: String, // مثال: Groq, Google Gemini, OpenRouter
    val apiKey: String,
    val modelId: String                   // مثال: gemini-1.5-flash أو نموذج OpenRouter المجاني
)

// 2. واجهة العمليات (DAO)
@Dao
interface ModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateModel(model: ModelEntity)

    @Query("SELECT * FROM ai_models_table WHERE providerName = :provider")
    suspend fun getModelByProvider(provider: String): ModelEntity?

    @Query("SELECT * FROM ai_models_table")
    suspend fun getAllModels(): List<ModelEntity>
}

// 3. قاعدة البيانات الرئيسية (AppDatabase)
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
