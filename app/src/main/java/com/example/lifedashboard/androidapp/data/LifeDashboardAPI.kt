// LifeDashboardAPI.kt
package com.example.lifedashboard.androidapp.data

import android.content.Context
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import androidx.preference.PreferenceManager
import com.example.lifedashboard.data.AppUsageItem
import com.example.lifedashboard.data.HourlyUsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class LifeDashboardAPI(private val context: Context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val authUtil = AuthUtil(context)
    private val TAG = "LifeDashboardAPI"

    // APIエンドポイントのURLを設定ファイルから取得
    private fun getApiBaseUrl(): String {
        return preferences.getString(
            "api_base_url",
            "https://lifedashboard.vercel.app/api"
        ) ?: "https://lifedashboard.vercel.app/api"
    }

    // 時間別使用状況データをアップロード
    suspend fun uploadHourlyUsageData(records: List<HourlyUsageRecord>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 認証状態を確認
                if (!authUtil.isAuthenticated()) {
                    Log.e(TAG, "未認証状態でアップロードが試行されました")
                    return@withContext false
                }

                // 認証情報を取得
                val userId = authUtil.getUserId() ?: return@withContext false
                val token = authUtil.getAuthToken() ?: return@withContext false

                var successCount = 0

                // 各時間ごとのレコードを個別にアップロード
                for (record in records) {
                    // JSONオブジェクトを作成
                    val jsonBody = JSONObject().apply {
                        put("user_id", userId)
                        put("date", record.date)
                        put("hour", record.hour)
                        put("total_usage_time", record.totalUsageTime)

                        // アプリの使用状況をJSON配列に変換
                        val appUsageArray = JSONArray()
                        record.appUsage.forEach { app ->
                            val appJson = JSONObject().apply {
                                put("appName", app.appName)
                                put("usageTime", app.usageTime)
                                put("openCount", app.openCount)
                            }
                            appUsageArray.put(appJson)
                        }
                        put("app_usage", appUsageArray)

                        // hour=0のレコードにのみ含める追加情報
                        if (record.hour == 0) {
                            put("screen_unlocks", record.screenUnlocks)
                            record.notifications?.let { put("notifications", it) }
                            record.batteryLevel?.let { put("battery_level", it) }
                        }

                        put("timestamp", record.timestamp)
                    }

                    // HTTP POSTリクエストを送信
                    val url = URL("${getApiBaseUrl()}/hourly-usage")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.doOutput = true

                    // リクエストボディの送信
                    OutputStreamWriter(connection.outputStream).use { writer ->
                        writer.write(jsonBody.toString())
                        writer.flush()
                    }

                    // レスポンスコードの確認
                    val responseCode = connection.responseCode
                    Log.d(TAG, "Hour ${record.hour} response code: $responseCode")

                    // 認証エラーの場合はトークン検証を試みてループを抜ける
                    if (responseCode == 401) {
                        val isTokenValid = authUtil.validateToken()
                        if (isTokenValid) {
                            // トークンは有効だが他の問題がある場合
                            Log.d(TAG, "トークンは有効ですが、アップロードに失敗しました")
                        } else {
                            // トークンが無効な場合
                            Log.d(TAG, "トークンが無効です。再認証が必要です")
                        }
                        return@withContext false
                    }

                    if (responseCode == 200 || responseCode == 201) {
                        successCount++
                    }
                }

                // 全レコードの半分以上が成功していれば成功と判定
                return@withContext successCount >= records.size / 2
            } catch (e: Exception) {
                Log.e(TAG, "時間別データアップロード中にエラーが発生", e)
                return@withContext false
            }
        }
    }

    // 特定の日付の時間別使用状況データを取得
    suspend fun getHourlyUsageData(date: LocalDate): List<HourlyUsageRecord>? {
        return withContext(Dispatchers.IO) {
            try {
                // 認証状態を確認
                if (!authUtil.isAuthenticated()) {
                    Log.e(TAG, "未認証状態でデータ取得が試行されました")
                    return@withContext null
                }

                // 認証情報を取得
                val token = authUtil.getAuthToken() ?: return@withContext null

                // 日付をフォーマット
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

                // HTTPリクエストを作成
                val url = URL("${getApiBaseUrl()}/hourly-usage?date=${dateStr}")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")

                // レスポンスの確認
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.e(TAG, "時間別データ取得エラー: $responseCode")

                    // 認証エラーの場合はトークン検証
                    if (responseCode == 401) {
                        authUtil.validateToken()
                    }

                    return@withContext null
                }

                // レスポンスの解析
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)

                if (!jsonResponse.optBoolean("success", false)) {
                    Log.e(TAG, "APIエラー: ${jsonResponse.optString("error", "不明なエラー")}")
                    return@withContext null
                }

                // データ配列の解析
                val dataArray = jsonResponse.getJSONArray("data")
                val result = mutableListOf<HourlyUsageRecord>()

                for (i in 0 until dataArray.length()) {
                    val dataObj = dataArray.getJSONObject(i)

                    // アプリ使用状況の解析
                    val appUsageArray = dataObj.getJSONArray("app_usage")
                    val appUsageList = mutableListOf<AppUsageItem>()

                    for (j in 0 until appUsageArray.length()) {
                        val appObj = appUsageArray.getJSONObject(j)
                        appUsageList.add(
                            AppUsageItem(
                                appName = appObj.getString("appName"),
                                usageTime = appObj.getInt("usageTime"),
                                openCount = appObj.getInt("openCount")
                            )
                        )
                    }

                    // HourlyUsageRecordオブジェクトの作成
                    result.add(
                        HourlyUsageRecord(
                            date = dataObj.getString("date"),
                            hour = dataObj.getInt("hour"),
                            appUsage = appUsageList,
                            totalUsageTime = dataObj.getInt("total_usage_time"),
                            screenUnlocks = dataObj.optInt("screen_unlocks", 0),
                            notifications = if (dataObj.has("notifications")) dataObj.getInt("notifications") else null,
                            batteryLevel = if (dataObj.has("battery_level")) dataObj.getInt("battery_level") else null,
                            timestamp = dataObj.getString("timestamp")
                        )
                    )
                }

                return@withContext result
            } catch (e: Exception) {
                Log.e(TAG, "時間別データ取得中にエラーが発生", e)
                return@withContext null
            }
        }
    }

    // 特定の期間の時間別使用パターンを取得
    suspend fun getHourlyUsagePattern(startDate: LocalDate, endDate: LocalDate): Map<Int, Double>? {
        return withContext(Dispatchers.IO) {
            try {
                // 認証状態を確認
                if (!authUtil.isAuthenticated()) {
                    Log.e(TAG, "未認証状態でデータ取得が試行されました")
                    return@withContext null
                }

                // 認証情報を取得
                val token = authUtil.getAuthToken() ?: return@withContext null

                // 日付をフォーマット
                val startDateStr = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val endDateStr = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

                // HTTPリクエストを作成
                val url = URL("${getApiBaseUrl()}/hourly-usage/pattern?startDate=${startDateStr}&endDate=${endDateStr}")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")

                // レスポンスの確認
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.e(TAG, "使用パターン取得エラー: $responseCode")
                    return@withContext null
                }

                // レスポンスの解析
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)

                if (!jsonResponse.optBoolean("success", false)) {
                    Log.e(TAG, "APIエラー: ${jsonResponse.optString("error", "不明なエラー")}")
                    return@withContext null
                }

                // パターンデータの解析
                val patternObj = jsonResponse.getJSONObject("pattern")
                val hourlyPattern = mutableMapOf<Int, Double>()

                // 時間ごとの平均使用時間を取得
                for (hour in 0..23) {
                    val hourKey = hour.toString()
                    if (patternObj.has(hourKey)) {
                        hourlyPattern[hour] = patternObj.getDouble(hourKey)
                    } else {
                        hourlyPattern[hour] = 0.0
                    }
                }

                return@withContext hourlyPattern
            } catch (e: Exception) {
                Log.e(TAG, "パターン取得中にエラーが発生", e)
                return@withContext null
            }
        }
    }

    // ログイン - AuthUtilに委譲
    suspend fun authenticate(email: String, password: String): AuthResult {
        return authUtil.login(email, password)
    }

    // トークン検証 - AuthUtilに委譲
    suspend fun validateToken(): Boolean {
        return authUtil.validateToken()
    }

    // ログアウト - AuthUtilに委譲
    fun logout() {
        authUtil.logout()
    }
}