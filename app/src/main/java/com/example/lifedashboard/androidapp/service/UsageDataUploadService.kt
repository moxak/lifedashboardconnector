// app/src/main/java/com/example/lifedashboard/androidapp/service/UsageDataUploadService.kt
package com.example.lifedashboard.androidapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.lifedashboard.androidapp.R
import com.example.lifedashboard.data.PhoneUsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class UsageDataUploadService : LifecycleService() {

    private val TAG = "UsageDataUploadService"
    private lateinit var repository: PhoneUsageRepository
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        repository = PhoneUsageRepository(applicationContext)

        // 通知チャンネル作成
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (isRunning) return START_STICKY

        isRunning = true

        // フォアグラウンドサービスとして起動
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("使用状況収集")
            .setContentText("使用状況データを収集・同期しています")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // ウェイクロック取得（CPU実行を継続する）
        acquireWakeLock()

        // 5分おきにデータを収集・送信するコルーチン
        lifecycleScope.launch {
            while (isRunning) {
                try {
                    // 現在時間帯のデータを収集
                    val now = LocalDateTime.now()
                    val formattedTime = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    Log.d(TAG, "[$formattedTime] データ収集開始")

                    val records = repository.collectCurrentHourUsageStats()

                    // 収集したデータをアップロード
                    val success = repository.uploadHourlyUsageStats(records)

                    if (success) {
                        Log.d(TAG, "[$formattedTime] データ同期成功")
                    } else {
                        Log.e(TAG, "[$formattedTime] データ同期失敗")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "データ収集/同期中にエラー発生", e)
                }

                // 5分待機
                delay(5 * 60 * 1000L)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "使用状況収集サービス",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "スマートフォンの使用状況データを定期的に収集します"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LifeDashboard:UsageDataUploadServiceWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10分間WakeLockを維持
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "usage_data_service_channel"
        private const val NOTIFICATION_ID = 123

        fun startService(context: Context) {
            val intent = Intent(context, UsageDataUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, UsageDataUploadService::class.java)
            context.stopService(intent)
        }
    }
}