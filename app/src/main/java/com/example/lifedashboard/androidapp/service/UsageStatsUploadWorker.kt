package com.example.lifedashboard.androidapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.lifedashboard.androidapp.R
import com.example.lifedashboard.androidapp.model.SyncRecord
import com.example.lifedashboard.androidapp.util.DatabaseHelper
import com.example.lifedashboard.data.HourlyUsageRecord
import com.example.lifedashboard.data.PhoneUsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class UsageStatsUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = PhoneUsageRepository(context)
    private val dbHelper = DatabaseHelper(context)
    private val TAG = "UsageStatsUploadWorker"

    @RequiresApi(Build.VERSION_CODES.Q)
    override suspend fun doWork(): Result {
        // フォアグラウンドサービスとして実行（Android 8.0以上の要件）
        setForeground(createForegroundInfo())

        return withContext(Dispatchers.IO) {
            try {
                // 現在の時間帯のデータのみを収集する
                val now = LocalDateTime.now()
                val formattedTime = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                Log.d(TAG, "[$formattedTime] 現在の利用統計データを収集します")

                // 現在の時間の使用状況データのみを収集
                val hourlyRecords = repository.collectCurrentHourUsageStats()

                Log.d(TAG, "合計${hourlyRecords.size}件のデータを収集しました")

                // 収集したデータのアップロード
                val isSuccessful = repository.uploadHourlyUsageStats(hourlyRecords)

                // 同期結果をデータベースに記録
                recordSyncResult(isSuccessful, hourlyRecords, null)

                if (isSuccessful) {
                    Log.d(TAG, "データアップロードに成功しました")
                    Result.success()
                } else {
                    Log.w(TAG, "データアップロードに失敗しました。後で再試行します")
                    Result.retry()
                }
            } catch (e: Exception) {
                Log.e(TAG, "データ処理中にエラーが発生しました", e)
                e.printStackTrace()

                // エラー情報を記録
                recordSyncResult(false, emptyList(), e.message ?: "不明なエラー")

                Result.failure()
            }
        }
    }

    /**
     * 同期結果をデータベースに記録
     */
    private fun recordSyncResult(success: Boolean, records: List<HourlyUsageRecord>, errorMessage: String?) {
        val syncRecord = SyncRecord(
            timestamp = LocalDateTime.now(),
            success = success,
            recordCount = records.size,
            errorMessage = errorMessage
        )

        dbHelper.addSyncRecord(syncRecord)
    }

    // フォアグラウンド通知の作成
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "usage_stats_service_channel"
        val notificationId = 1

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "使用状況収集サービス",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("使用状況データを収集中")
            .setContentText("スマートフォンの使用状況データを収集しています")
            .setSmallIcon(android.R.drawable.ic_popup_sync) // 標準アイコンを使用
            .setOngoing(true)
            .build()

        // Android Q (API 29) 以上ではForegroundServiceTypeを指定できる
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}