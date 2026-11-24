package com.aisocial.copilot.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String,
    val tone: String,
    val length: String,
    val emotion: Int,
    val initiative: Int,
    val humor: Int,
    val catchphrases: String,
    val forbidden: String,
    val customInstruction: String,
    val presetKey: String?,
    val isPreset: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val createdAt: Long,
    val inputText: String,
    val intentText: String,
    val personaName: String,
    val analysis: String,
    val toneJudge: String,
    val strategy: String,
    val repliesJson: String,
    val modelLabel: String,
    /** Which message the model decided it was answering (v1.1.3+). */
    @ColumnInfo(defaultValue = "") val replyingTo: String = "",
)

@Dao
interface PersonaDao {

    @Query("SELECT * FROM personas ORDER BY isPreset DESC, updatedAt DESC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PersonaEntity?

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int

    @Query("SELECT * FROM personas WHERE presetKey = :key LIMIT 1")
    suspend fun findByPresetKey(key: String): PersonaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PersonaEntity): Long

    @Update
    suspend fun update(entity: PersonaEntity)

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM personas WHERE presetKey IS NOT NULL")
    suspend fun deleteAllPresets()
}

@Entity(tableName = "persona_books")
data class PersonaBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val content: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface PersonaBookDao {

    @Query("SELECT * FROM persona_books ORDER BY isActive DESC, updatedAt DESC")
    fun observeAll(): Flow<List<PersonaBookEntity>>

    @Query("SELECT * FROM persona_books WHERE isActive = 1 LIMIT 1")
    suspend fun findActive(): PersonaBookEntity?

    @Query("SELECT * FROM persona_books WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PersonaBookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PersonaBookEntity): Long

    @Update
    suspend fun update(entity: PersonaBookEntity)

    @Query("DELETE FROM persona_books WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE persona_books SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE persona_books SET isActive = 1, updatedAt = :now WHERE id = :id")
    suspend fun activate(id: Long, now: Long)
}

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntity): Long

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [PersonaEntity::class, HistoryEntity::class, PersonaBookEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun historyDao(): HistoryDao
    abstract fun personaBookDao(): PersonaBookDao

    companion object {
        /** v1.1 adds the 人设书 table without touching existing personas or history. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `persona_books` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`isActive` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        /** v1.1.3 remembers which message the model thought it was answering. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `history` ADD COLUMN `replyingTo` TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
