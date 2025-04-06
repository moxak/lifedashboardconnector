package com.example.lifedashboard.androidapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.lifedashboard.androidapp.service.UsageStatsUploadWorker
import java.util.concurrent.TimeUnit
import androidx.preference.PreferenceManager

/**
 * デバイスの再起動後に定期的なデータアップロードを再設定するレシーバー
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            setupPeriodicWorker(context)
        }
    }

    /**
     * WorkManagerで定期的なデータアップロードを設定する
     */
    private fun setupPeriodicWorker(context: Context) {
        // 設定から同期間隔を取得（デフォルト：6時間）
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val syncIntervalMinutes = prefs.getInt("sync_interval_minutes", 5)

        // ワーカーの制約を設定
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)  // バッテリー残量が少なくない場合のみ実行
            .build()

        // 定期的なワーク要求の作成（時間単位から分単位に変更）
        val uploadWorkRequest = PeriodicWorkRequestBuilder<UsageStatsUploadWorker>(
            syncIntervalMinutes.toLong(), TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        // ワークマネージャーに登録
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "usage_stats_upload",
            ExistingPeriodicWorkPolicy.KEEP,  // 既存の作業をキープ
            uploadWorkRequest
        )
    }
}