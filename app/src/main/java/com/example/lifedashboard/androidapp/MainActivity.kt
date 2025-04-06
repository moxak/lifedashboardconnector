package com.example.lifedashboard.androidapp

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.lifedashboard.androidapp.databinding.ActivityMainBinding
import com.example.lifedashboard.androidapp.service.UsageDataUploadService
import com.example.lifedashboard.androidapp.service.UsageStatsUploadWorker
import com.example.lifedashboard.androidapp.settings.SettingsActivity
import com.example.lifedashboard.androidapp.ui.ViewPagerAdapter
import com.google.android.material.tabs.TabLayoutMediator
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 権限の確認を最初に行い、結果に基づいて初期化
        if (checkAndRequestPermissions()) {
            // 権限がある場合のみ初期化
            setupPeriodicWorker()

            // バックグラウンドサービスも開始
            startBackgroundService()
        }

        // ViewPagerとTabLayoutの設定
        setupViewPager()

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

    /**
     * ViewPagerとTabLayoutの設定
     */
    private fun setupViewPager() {
        viewPagerAdapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = viewPagerAdapter

        // TabLayoutとViewPagerを連携
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "同期状況"
                1 -> "使用サマリー"
                else -> "タブ$position"
            }
        }.attach()
    }

    /**
     * バックグラウンドサービスの開始
     */
    private fun startBackgroundService() {
        try {
            // フォアグラウンドサービスの起動
            UsageDataUploadService.startService(this)
            Log.d("MainActivity", "バックグラウンドサービスを開始しました")
        } catch (e: Exception) {
            Log.e("MainActivity", "バックグラウンドサービス開始時にエラーが発生: ${e.message}")
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
            // バッテリー最適化除外の確認と要求
            checkBatteryOptimization()

            binding.statusText.text = "使用状況へのアクセス権限があります。データ収集が可能です。"
            return true
        }
    }

    /**
     * バッテリー最適化除外の確認と要求
     */
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val packageName = packageName

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // バッテリー最適化の対象になっている場合は、除外を要求するダイアログを表示
                AlertDialog.Builder(this)
                    .setTitle("バッテリー最適化の除外")
                    .setMessage("バックグラウンドでの安定した動作のため、バッテリー最適化から除外することをお勧めします。")
                    .setPositiveButton("設定を開く") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("後で") { _, _ -> }
                    .show()
            }
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

    // 定期的なデータアップロードの設定を変更 - 5分間隔に
    private fun setupPeriodicWorker() {
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(false)  // デバイスがアイドル状態でなくても実行
            .setRequiresBatteryNotLow(true)  // バッテリー残量が少なくない場合のみ実行
            .setRequiresCharging(false)  // 充電中でなくても実行
            .build()

        val uploadWorkRequest = PeriodicWorkRequestBuilder<UsageStatsUploadWorker>(
            5, TimeUnit.MINUTES  // 6時間から5分に変更
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

    override fun onDestroy() {
        super.onDestroy()
        // バックグラウンドサービスは継続して実行
    }
}