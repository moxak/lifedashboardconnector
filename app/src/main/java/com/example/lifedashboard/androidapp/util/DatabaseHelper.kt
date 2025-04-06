package com.example.lifedashboard.androidapp.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.lifedashboard.androidapp.model.SyncRecord
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 同期履歴を保存するためのデータベースヘルパー
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "lifedashboard.db"
        private const val DATABASE_VERSION = 1

        // 同期履歴テーブル
        private const val TABLE_SYNC_HISTORY = "sync_history"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_SUCCESS = "success"
        private const val COLUMN_RECORD_COUNT = "record_count"
        private const val COLUMN_ERROR_MESSAGE = "error_message"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 同期履歴テーブルの作成
        val createTableQuery = """
            CREATE TABLE $TABLE_SYNC_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TIMESTAMP TEXT NOT NULL,
                $COLUMN_SUCCESS INTEGER NOT NULL,
                $COLUMN_RECORD_COUNT INTEGER NOT NULL,
                $COLUMN_ERROR_MESSAGE TEXT
            )
        """.trimIndent()

        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // アップグレード時は一旦テーブルを削除して再作成
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SYNC_HISTORY")
        onCreate(db)
    }

    /**
     * 同期履歴を追加
     */
    fun addSyncRecord(syncRecord: SyncRecord): Long {
        val db = writableDatabase

        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, syncRecord.timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            put(COLUMN_SUCCESS, if (syncRecord.success) 1 else 0)
            put(COLUMN_RECORD_COUNT, syncRecord.recordCount)
            put(COLUMN_ERROR_MESSAGE, syncRecord.errorMessage)
        }

        // 古いレコードを削除（最大300件まで保持）
        db.execSQL("DELETE FROM $TABLE_SYNC_HISTORY WHERE $COLUMN_ID IN (SELECT $COLUMN_ID FROM $TABLE_SYNC_HISTORY ORDER BY $COLUMN_ID DESC LIMIT -1 OFFSET 300)")

        // 新しいレコードを追加
        val id = db.insert(TABLE_SYNC_HISTORY, null, values)
        db.close()

        return id
    }

    /**
     * 同期履歴を取得（最新順）
     */
    fun getSyncHistory(): List<SyncRecord> {
        val syncRecords = mutableListOf<SyncRecord>()
        val db = readableDatabase

        val query = "SELECT * FROM $TABLE_SYNC_HISTORY ORDER BY $COLUMN_ID DESC"

        val cursor = db.rawQuery(query, null)

        if (cursor.moveToFirst()) {
            val idIndex = cursor.getColumnIndex(COLUMN_ID)
            val timestampIndex = cursor.getColumnIndex(COLUMN_TIMESTAMP)
            val successIndex = cursor.getColumnIndex(COLUMN_SUCCESS)
            val recordCountIndex = cursor.getColumnIndex(COLUMN_RECORD_COUNT)
            val errorMessageIndex = cursor.getColumnIndex(COLUMN_ERROR_MESSAGE)

            do {
                val id = if (idIndex >= 0) cursor.getLong(idIndex) else 0
                val timestampStr = if (timestampIndex >= 0) cursor.getString(timestampIndex) else ""
                val success = if (successIndex >= 0) cursor.getInt(successIndex) == 1 else false
                val recordCount = if (recordCountIndex >= 0) cursor.getInt(recordCountIndex) else 0
                val errorMessage = if (errorMessageIndex >= 0 && !cursor.isNull(errorMessageIndex)) cursor.getString(errorMessageIndex) else null

                val timestamp = LocalDateTime.parse(timestampStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                syncRecords.add(SyncRecord(id, timestamp, success, recordCount, errorMessage))
            } while (cursor.moveToNext())
        }

        cursor.close()
        db.close()

        return syncRecords
    }

    /**
     * 同期成功数を取得
     */
    fun getSuccessCount(): Int {
        val db = readableDatabase
        val query = "SELECT COUNT(*) FROM $TABLE_SYNC_HISTORY WHERE $COLUMN_SUCCESS = 1"
        val cursor = db.rawQuery(query, null)

        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }

        cursor.close()
        db.close()

        return count
    }

    /**
     * 同期失敗数を取得
     */
    fun getFailureCount(): Int {
        val db = readableDatabase
        val query = "SELECT COUNT(*) FROM $TABLE_SYNC_HISTORY WHERE $COLUMN_SUCCESS = 0"
        val cursor = db.rawQuery(query, null)

        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }

        cursor.close()
        db.close()

        return count
    }
}