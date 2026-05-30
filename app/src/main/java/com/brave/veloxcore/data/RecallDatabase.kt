package com.brave.veloxcore.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


@Database(entities = [CaptureEntity::class], version = 2)
  abstract class RecallDatabase : RoomDatabase() {
      abstract fun captureDao(): CaptureDao

      companion object {
          @Volatile
          private var INSTANCE: RecallDatabase? = null

          private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("""
              CREATE VIRTUAL TABLE IF NOT EXISTS captures_fts
              USING fts4(textContent, content=captures)
          """.trimIndent())
      }
  }


          fun getInstance(context: Context): RecallDatabase {
              return INSTANCE ?: synchronized(this) {
                  INSTANCE ?: Room.databaseBuilder(
                      context.applicationContext,
                      RecallDatabase::class.java,
                      "recall_db"
                  ).addMigrations(MIGRATION_1_2)
                  .build()
                  .also { INSTANCE = it }
              }
          }
      }
  }