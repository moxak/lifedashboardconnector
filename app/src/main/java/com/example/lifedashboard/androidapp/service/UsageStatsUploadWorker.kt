// UsageStatsUploadWorker.kt
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
import com.example.lifedashboard.data.HourlyUsageRecord
import com.example.lifedashboard.data.PhoneUsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class UsageStatsUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = PhoneUsageRepository(context)
    private val TAG = "UsageStatsUploadWorker"

    @RequiresApi(Build.VERSION_CODES.Q)
    override suspend fun doWork(): Result {
        // フォアグラウンドサービスとして実行（Android 8.0以上の要件）
        setForeground(createForegroundInfo())

        return withContext(Dispatchers.IO) {
            try {
                // 直近1週間のデータを収集
                val today = LocalDate.now()
                val startDate = today.minusDays(6) // 今日含めた1週間（今日 + 過去6日）
                Log.d(TAG, "${startDate}から${today}までの利用統計データを収集します")

                // 日付別の収集結果を保持するリスト
                val allHourlyRecords = mutableListOf<HourlyUsageRecord>()

                // 各日のデータを収集
                for (daysAgo in 6 downTo 0) {
                    val targetDate = today.minusDays(daysAgo.toLong())
                    Log.d(TAG, "${targetDate}の利用統計データを収集中...")

                    // 時間別データの収集
                    val dailyHourlyRecords = repository.collectHourlyUsageStats(targetDate)
                    allHourlyRecords.addAll(dailyHourlyRecords)
                    Log.d(TAG, "${targetDate}: ${dailyHourlyRecords.size}件の時間別データを収集しました")
                }

                Log.d(TAG, "合計${allHourlyRecords.size}件のデータを収集しました")

                // 収集したデータのアップロード
                val isSuccessful = repository.uploadHourlyUsageStats(allHourlyRecords)

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
                Result.failure()
            }
        }
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