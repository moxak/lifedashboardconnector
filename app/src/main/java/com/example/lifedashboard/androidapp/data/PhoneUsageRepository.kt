// PhoneUsageRepository.kt
package com.example.lifedashboard.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.lifedashboard.androidapp.data.LifeDashboardAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.concurrent.TimeUnit

class PhoneUsageRepository(private val context: Context) {

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val api = LifeDashboardAPI(context)
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val TAG = "PhoneUsageRepository"

    // 指定した日付の使用状況データを収集し、時間別に分割
    suspend fun collectHourlyUsageStats(date: LocalDate): List<HourlyUsageRecord> {
        val startTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTime = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 時間別・アプリ別の使用状況を格納するマップ
        // Map<時間, Map<アプリ名, AppUsageInfo>>
        val hourlyAppUsageMap = mutableMapOf<Int, MutableMap<String, AppUsageInfo>>()

        // 24時間分の空のマップを初期化
        for (hour in 0..23) {
            hourlyAppUsageMap[hour] = mutableMapOf()
        }

        var screenUnlocks = 0
        var notifications = getNotificationCount() ?: 0
        val batteryLevel = getBatteryLevel()

        // イベントデータの取得
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        // アプリごとの使用開始時間を記録
        val appStartTimes = mutableMapOf<String, Long>()
        val appStartHours = mutableMapOf<String, Int>()

        Log.d(TAG, "収集開始: $date")

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName

            // 画面のロック解除をカウント
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && packageName == "com.android.systemui") {
                screenUnlocks++
            }

            // アプリのフォアグラウンド移動時（開始時間を記録）
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                appStartTimes[packageName] = event.timeStamp

                // イベント発生時の時間（0-23）を記録
                val calendar = Calendar.getInstance().apply { timeInMillis = event.timeStamp }
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                appStartHours[packageName] = hour
            }

            // アプリのバックグラウンド移動時（使用時間を計算）
            else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                val startTime = appStartTimes[packageName]
                val startHour = appStartHours[packageName]

                if (startTime != null && startHour != null) {
                    val usageTime = event.timeStamp - startTime

                    // パッケージ名からアプリ名を取得
                    val appName = getAppNameFromPackageName(packageName)

                    // 現在の時間（終了時）を取得
                    val calendar = Calendar.getInstance().apply { timeInMillis = event.timeStamp }
                    val endHour = calendar.get(Calendar.HOUR_OF_DAY)

                    // 1時間以内の使用の場合、単一の時間帯に割り当て
                    if (startHour == endHour) {
                        // 既存の時間帯データを更新
                        updateHourlyAppUsage(hourlyAppUsageMap, startHour, appName, usageTime, 1)
                    } else {
                        // 時間帯をまたぐ使用の場合、使用時間を比例配分
                        // 開始時間の分数を計算
                        val startCalendar = Calendar.getInstance().apply { timeInMillis = startTime }
                        val startMinute = startCalendar.get(Calendar.MINUTE)
                        val startSecond = startCalendar.get(Calendar.SECOND)
                        val minutesInStartHour = 60 - startMinute - (startSecond / 60.0)

                        // 終了時間の分数を計算
                        val endMinute = calendar.get(Calendar.MINUTE)
                        val endSecond = calendar.get(Calendar.SECOND)
                        val minutesInEndHour = endMinute + (endSecond / 60.0)

                        // 開始時間帯の使用時間を計算（最大60分）
                        val startHourUsage = (minutesInStartHour / 60.0) * usageTime
                        updateHourlyAppUsage(hourlyAppUsageMap, startHour, appName, startHourUsage.toLong(), 1)

                        // 中間の時間帯がある場合（1時間以上の使用）
                        var currentHour = (startHour + 1) % 24
                        while (currentHour != endHour) {
                            // 1時間フルの使用時間
                            updateHourlyAppUsage(
                                hourlyAppUsageMap,
                                currentHour,
                                appName,
                                TimeUnit.MINUTES.toMillis(60),
                                0 // 中間時間はアプリ起動回数にカウントしない
                            )
                            currentHour = (currentHour + 1) % 24
                        }

                        // 終了時間帯の使用時間を計算
                        val endHourUsage = (minutesInEndHour / 60.0) * usageTime
                        updateHourlyAppUsage(hourlyAppUsageMap, endHour, appName, endHourUsage.toLong(), 0)
                    }

                    // 終了したアプリの開始時間を削除
                    appStartTimes.remove(packageName)
                    appStartHours.remove(packageName)
                }
            }
        }

        // 日付をフォーマット（YYYY-MM-DD）
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // 時間帯ごとの使用統計をHourlyUsageRecordに変換
        val hourlyRecords = mutableListOf<HourlyUsageRecord>()

        // 各時間帯のデータを処理
        for (hour in 0..23) {
            val appUsageMap = hourlyAppUsageMap[hour] ?: continue

            // その時間に使用されたアプリがなければスキップ
            if (appUsageMap.isEmpty()) continue

            // アプリごとの使用状況をリストに変換
            val appUsageItems = appUsageMap.values.map { appInfo ->
                AppUsageItem(
                    appName = appInfo.appName,
                    usageTime = TimeUnit.MILLISECONDS.toMinutes(appInfo.usageTime).toInt(),
                    openCount = appInfo.openCount
                )
            }.sortedByDescending { it.usageTime }

            // 時間帯の総使用時間を計算
            val hourlyTotalTime = appUsageItems.sumOf { it.usageTime }

            // 使用時間が0分の時間帯はスキップ
            if (hourlyTotalTime == 0) continue

            // 時間帯別レコードを作成
            hourlyRecords.add(
                HourlyUsageRecord(
                    date = dateStr,
                    hour = hour,
                    appUsage = appUsageItems,
                    totalUsageTime = hourlyTotalTime,
                    screenUnlocks = if (hour == 0) screenUnlocks else 0, // 画面ロック解除はhour=0の記録にのみ含める
                    notifications = if (hour == 0) notifications else 0,  // 通知数はhour=0の記録にのみ含める
                    batteryLevel = if (hour == 0) batteryLevel else null, // バッテリーレベルはhour=0の記録にのみ含める
                    timestamp = Instant.now().toString()
                )
            )
        }

        Log.d(TAG, "収集完了: ${hourlyRecords.size}時間分のデータ")
        return hourlyRecords
    }

    // 時間別アプリ使用情報を更新するヘルパーメソッド
    private fun updateHourlyAppUsage(
        hourlyMap: MutableMap<Int, MutableMap<String, AppUsageInfo>>,
        hour: Int,
        appName: String,
        usageTime: Long,
        openCount: Int
    ) {
        val hourAppMap = hourlyMap[hour] ?: mutableMapOf<String, AppUsageInfo>().also { hourlyMap[hour] = it }

        val existingInfo = hourAppMap[appName]
        if (existingInfo != null) {
            existingInfo.usageTime += usageTime
            existingInfo.openCount += openCount
        } else {
            hourAppMap[appName] = AppUsageInfo(
                appName = appName,
                usageTime = usageTime,
                openCount = openCount
            )
        }
    }

    // 時間別データのアップロード
    suspend fun uploadHourlyUsageStats(records: List<HourlyUsageRecord>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (records.isEmpty()) {
                    Log.w(TAG, "アップロードするデータが空です")
                    return@withContext true
                }

                // APIを通じてデータをアップロード
                val result = api.uploadHourlyUsageData(records)

                // 最終同期日時を保存
                if (result) {
                    val editor = preferences.edit()
                    editor.putString("last_sync_date", records.first().date)
                    editor.putString("last_sync_time", records.first().timestamp)
                    editor.apply()

                    Log.d(TAG, "時間別データのアップロードに成功: ${records.size}レコード")
                } else {
                    Log.e(TAG, "時間別データのアップロードに失敗")
                }

                result
            } catch (e: Exception) {
                Log.e(TAG, "アップロード中にエラーが発生", e)
                false
            }
        }
    }

    // パッケージ名からアプリ名を取得
    private fun getAppNameFromPackageName(packageName: String): String {
        val packageManager = context.packageManager
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            // アプリ名が取得できない場合はパッケージ名を使用
            packageName.substringAfterLast('.')
        }
    }

    // 通知数の取得（システムAPIの制約によりダミー実装）
    private fun getNotificationCount(): Int? {
        // 実際の実装では通知リスナーサービスからのデータ取得が必要
        // 今回はデモ用にランダム値を返す
        return (20..150).random()
    }

    // バッテリーレベルの取得
    private fun getBatteryLevel(): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            -1
        }
    }

    // 日次データを取得（集計用）
    suspend fun getDailySummary(date: LocalDate): PhoneUsageData? {
        // API経由で取得した時間別データから日次サマリーを作成
        val hourlyRecords = api.getHourlyUsageData(date)

        if (hourlyRecords.isNullOrEmpty()) {
            return null
        }

        // 全アプリの使用時間を集計
        val appUsageMap = mutableMapOf<String, AppUsageInfo>()
        var totalUsageTime = 0
        var screenUnlocks = 0
        var notifications = 0
        var batteryLevel: Int? = null

        hourlyRecords.forEach { record ->
            // 各時間帯のアプリ使用情報を集計
            record.appUsage.forEach { app ->
                val existingApp = appUsageMap[app.appName]
                if (existingApp != null) {
                    existingApp.usageTime += TimeUnit.MINUTES.toMillis(app.usageTime.toLong())
                    existingApp.openCount += app.openCount
                } else {
                    appUsageMap[app.appName] = AppUsageInfo(
                        appName = app.appName,
                        usageTime = TimeUnit.MINUTES.toMillis(app.usageTime.toLong()),
                        openCount = app.openCount
                    )
                }
            }

            totalUsageTime += record.totalUsageTime
            screenUnlocks += record.screenUnlocks

            // 通知とバッテリーレベルは最初のレコードから取得
            if (record.notifications != null && record.notifications > 0) {
                notifications = record.notifications
            }

            if (batteryLevel == null && record.batteryLevel != null) {
                batteryLevel = record.batteryLevel
            }
        }

        // アプリ使用状況をリストに変換
        val appUsageItems = appUsageMap.values.map { appInfo ->
            AppUsageItem(
                appName = appInfo.appName,
                usageTime = TimeUnit.MILLISECONDS.toMinutes(appInfo.usageTime).toInt(),
                openCount = appInfo.openCount
            )
        }.sortedByDescending { it.usageTime }

        // 日付を取得
        val dateStr = hourlyRecords.first().date

        return PhoneUsageData(
            totalUsageTime = totalUsageTime,
            appUsage = appUsageItems,
            screenUnlocks = screenUnlocks,
            notifications = notifications,
            batteryLevel = batteryLevel,
            date = dateStr,
            timestamp = Instant.now().toString()
        )
    }
}

// データモデル
data class PhoneUsageData(
    val totalUsageTime: Int,
    val appUsage: List<AppUsageItem>,
    val screenUnlocks: Int,
    val notifications: Int?,
    val batteryLevel: Int?,
    val date: String,
    val timestamp: String
)

// 時間別使用記録（新しいモデル）
data class HourlyUsageRecord(
    val date: String,         // YYYY-MM-DD形式
    val hour: Int,            // 0-23の時間
    val appUsage: List<AppUsageItem>,
    val totalUsageTime: Int,  // この時間帯の合計使用時間（分）
    val screenUnlocks: Int,   // 画面ロック解除回数（hour=0のレコードにのみ含む）
    val notifications: Int?,  // 通知数（hour=0のレコードにのみ含む）
    val batteryLevel: Int?,   // バッテリーレベル（hour=0のレコードにのみ含む）
    val timestamp: String     // ISO形式のタイムスタンプ
)

data class AppUsageItem(
    val appName: String,
    val usageTime: Int,
    val openCount: Int
)

data class AppUsageInfo(
    val appName: String,
    var usageTime: Long,
    var openCount: Int
)