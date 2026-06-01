package com.kajian.note.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kajian.note.model.Note

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Query("SELECT * FROM notes WHERE title LIKE '%'||:q||'%' OR plainText LIKE '%'||:q||'%' ORDER BY createdAt DESC")
    fun search(q: String): LiveData<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(n: Note): Long

    @Update
    suspend fun update(n: Note)

    @Delete
    suspend fun delete(n: Note)
}

@Database(entities = [Note::class], version = 3, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var INSTANCE: NoteDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN audioPath TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN summaryText TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(ctx: Context) = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(ctx.applicationContext, NoteDatabase::class.java, "kajian_v3_db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
        }
    }
}

class NoteRepository(ctx: Context) {
    private val dao = NoteDatabase.get(ctx).noteDao()
    val all: LiveData<List<Note>> = dao.getAll()
    fun search(q: String) = dao.search(q)
    suspend fun insert(n: Note) = dao.insert(n)
    suspend fun update(n: Note) = dao.update(n)
    suspend fun delete(n: Note) = dao.delete(n)
    suspend fun getById(id: Long) = dao.getById(id)
}
