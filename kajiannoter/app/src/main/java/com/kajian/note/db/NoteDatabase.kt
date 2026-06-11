package com.kajian.note.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kajian.note.model.Folder
import com.kajian.note.model.Note

// ── NoteDao ───────────────────────────────────────────────────────────────────

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY createdAt DESC")
    fun getByFolder(folderId: Long): LiveData<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Query("SELECT * FROM notes WHERE title LIKE '%'||:q||'%' OR plainText LIKE '%'||:q||'%' ORDER BY createdAt DESC")
    fun search(q: String): LiveData<List<Note>>

    @Query("SELECT COUNT(*) FROM notes WHERE folderId = :folderId")
    suspend fun countByFolder(folderId: Long): Int

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(n: Note): Long

    @Update
    suspend fun update(n: Note)

    @Delete
    suspend fun delete(n: Note)
}

// ── FolderDao ─────────────────────────────────────────────────────────────────

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun getAll(): LiveData<List<Folder>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): Folder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: Folder): Long

    @Update
    suspend fun update(f: Folder)

    @Delete
    suspend fun delete(f: Folder)
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(entities = [Note::class, Folder::class], version = 6, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao

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
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN bookmarksJson TEXT NOT NULL DEFAULT '[]'")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add folder support to notes
                db.execSQL("ALTER TABLE notes ADD COLUMN folderId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN isPremiumContent INTEGER NOT NULL DEFAULT 0")
                // Create folders table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        emoji TEXT NOT NULL DEFAULT '📁',
                        colorHex TEXT NOT NULL DEFAULT '#00E676',
                        createdAt INTEGER NOT NULL,
                        noteCount INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: version bump for clean slate installs
            }
        }

        fun get(ctx: Context) = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(ctx.applicationContext, NoteDatabase::class.java, "kajian_v4_db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}

// ── Repository ────────────────────────────────────────────────────────────────

class NoteRepository(ctx: Context) {
    private val db = NoteDatabase.get(ctx)
    private val dao = db.noteDao()
    private val folderDao = db.folderDao()

    val all: LiveData<List<Note>> = dao.getAll()
    val allFolders: LiveData<List<Folder>> = folderDao.getAll()

    fun search(q: String) = dao.search(q)
    fun getByFolder(folderId: Long) = dao.getByFolder(folderId)

    suspend fun insert(n: Note) = dao.insert(n)
    suspend fun update(n: Note) = dao.update(n)
    suspend fun delete(n: Note) = dao.delete(n)
    suspend fun getById(id: Long) = dao.getById(id)
    suspend fun countAll() = dao.countAll()

    suspend fun insertFolder(f: Folder) = folderDao.insert(f)
    suspend fun updateFolder(f: Folder) = folderDao.update(f)
    suspend fun deleteFolder(f: Folder) = folderDao.delete(f)
    suspend fun getFolderById(id: Long) = folderDao.getById(id)
}
