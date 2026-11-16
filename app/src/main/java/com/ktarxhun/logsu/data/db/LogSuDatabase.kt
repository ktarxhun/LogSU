package com.ktarxhun.logsu.data.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.data.model.GuestEvent
import com.ktarxhun.logsu.data.model.GuestSession

@Database(
    entities = [CallRecord::class, GuestSession::class, GuestEvent::class],
    version = 3,
    exportSchema = false
)
abstract class LogSuDatabase : RoomDatabase() {
    abstract fun callRecordDao(): CallRecordDao
    abstract fun guestSessionDao(): GuestSessionDao
    abstract fun guestEventDao(): GuestEventDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE guest_sessions ADD COLUMN videoFilePath TEXT")
                db.execSQL("ALTER TABLE guest_sessions ADD COLUMN touchFilePath TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE guest_sessions ADD COLUMN audioFilePath TEXT")
            }
        }

        @Volatile
        private var INSTANCE: LogSuDatabase? = null

        fun getDatabase(context: Context): LogSuDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LogSuDatabase::class.java,
                    "logsu_database.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
