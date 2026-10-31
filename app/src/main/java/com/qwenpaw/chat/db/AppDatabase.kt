package com.qwenpaw.chat.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        /** 老数据使用的默认账号 id,迁移时为历史消息补上 accountId */
        const val LEGACY_DEFAULT_ACCOUNT_ID = "__legacy_default_account__"

        /**
         * v1 -> v2:为 messages 表新增 accountId 列(非空,默认指向旧账号占位 id),
         * 并在 (accountId, sessionId) 上建立复合索引。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN accountId TEXT NOT NULL DEFAULT '$LEGACY_DEFAULT_ACCOUNT_ID'"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_accountId_sessionId ON messages(accountId, sessionId)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "qwenpaw_chat_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
