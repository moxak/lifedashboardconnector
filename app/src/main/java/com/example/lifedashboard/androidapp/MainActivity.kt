// MainActivity.kt
package com.example.lifedashboard.androidapp

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.lifedashboard.androidapp.databinding.ActivityMainBinding
import com.example.lifedashboard.androidapp.service.UsageStatsUploadWorker
import com.example.lifedashboard.androidapp.settings.SettingsActivity
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 権限の確認を最初に行い、結果に基づいて初期化
        if (checkAndRequestPermissions()) {
            // 権限がある場合のみ初期化
            setupPeriodicWorker()
        }

        // 設定ボタンのクリックリスナー
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 手動同期ボタンのクリックリスナー
        binding.syncNowButton.setOnClickListener {
            if (hasUsageStatsPermission()) {
                triggerImmediateSync()
            } else {
                binding.statusText.text = "使用状況へのアクセス権限がありません。"
            }
        }
    }

    // 使用統計へのアクセス権限チェック - boolean値を返すように修正
    private fun checkAndRequestPermissions(): Boolean {
        if (!hasUsageStatsPermission()) {
            AlertDialog.Builder(this)
                .setTitle("使用状況へのアクセス許可が必要です")
                .setMessage("このアプリがあなたのスマートフォンの使用状況を収集するには、使用状況へのアクセス許可が必要です。設定画面で許可してください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("キャンセル") { _, _ ->
                    // 権限がないとアプリは機能しないことを伝える
                    binding.statusText.text = "使用状況へのアクセス権限がありません。アプリは正常に機能しません。"
                }
                .setCancelable(false)
                .show()
            return false
        } else {
            binding.statusText.text = "使用状況へのアクセス権限があります。データ収集が可能です。"
            return true
        }
    }

    // 使用統計権限の確認
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // 定期的なデータアップロードの設定を変更
    private fun setupPeriodicWorker() {
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(false)  // デバイスがアイドル状態でなくても実行
            .setRequiresBatteryNotLow(true)  // バッテリー残量が少なくない場合のみ実行
            .setRequiresCharging(false)  // 充電中でなくても実行
            .build()

        val uploadWorkRequest = PeriodicWorkRequestBuilder<UsageStatsUploadWorker>(
            6, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "usage_stats_upload",
            ExistingPeriodicWorkPolicy.KEEP,
            uploadWorkRequest
        )
    }

    // 手動同期の実行
    private fun triggerImmediateSync() {
        val uploadWorkRequest = PeriodicWorkRequestBuilder<UsageStatsUploadWorker>(
            0, TimeUnit.MILLISECONDS
        ).build()

        WorkManager.getInstance(this).enqueue(uploadWorkRequest)
        binding.statusText.text = "同期を開始しました..."
    }
}