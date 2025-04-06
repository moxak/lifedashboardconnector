package com.example.lifedashboard.androidapp.data

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 認証関連のユーティリティクラス
 */
class AuthUtil(private val context: Context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * APIエンドポイントのURLを設定から取得
     */
    private fun getApiBaseUrl(): String {
        return preferences.getString(
            "api_base_url",
            "https://lifedashboard.vercel.app/api"
        ) ?: "https://lifedashboard.vercel.app/api"
    }

    /**
     * ログイン処理
     * @param email ユーザーのメールアドレス
     * @param password ユーザーのパスワード
     * @return AuthResult ログイン結果
     */
    suspend fun login(email: String, password: String): AuthResult = suspendCoroutine { continuation ->
        try {
            // JSONオブジェクトを作成
            val jsonBody = JSONObject().apply {
                put("email", email)
                put("password", password)
            }

            // HTTP POSTリクエストを送信
            val url = URL("${getApiBaseUrl()}/auth/login")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // リクエストボディの送信
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            // レスポンスコードの確認
            val responseCode = connection.responseCode
            Log.d("AuthUtil", "Response code: $responseCode")

            if (responseCode == 200) {
                // 成功の場合、レスポンスボディを解析
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)

                // ユーザー情報とトークンを取得
                val userObj = jsonResponse.getJSONObject("user")
                val userId = userObj.getString("id")
                val userEmail = userObj.optString("email")
                val token = jsonResponse.getString("token")

                // 認証情報を保存
                saveAuthData(userId, userEmail, token)

                // 成功結果を返す
                continuation.resume(AuthResult(
                    success = true,
                    userId = userId,
                    token = token,
                    errorMessage = null
                ))
            } else {
                // エラーの場合、エラーメッセージを解析
                val errorStream = connection.errorStream ?: connection.inputStream
                val errorText = errorStream.bufferedReader().use { it.readText() }
                val jsonError = try {
                    JSONObject(errorText)
                } catch (e: Exception) {
                    JSONObject().apply { put("error", errorText) }
                }

                val errorMessage = jsonError.optString("error", "認証に失敗しました")
                Log.e("AuthUtil", "Auth error: $errorMessage")

                // エラー結果を返す
                continuation.resume(AuthResult(
                    success = false,
                    userId = null,
                    token = null,
                    errorMessage = errorMessage
                ))
            }
        } catch (e: Exception) {
            Log.e("AuthUtil", "Login exception", e)
            // 例外をスローして上位で処理
            continuation.resumeWithException(e)
        }
    }

    /**
     * トークンの検証
     * @return Boolean トークンが有効かどうか
     */
    suspend fun validateToken(): Boolean = suspendCoroutine { continuation ->
        try {
            // 保存されたトークンを取得
            val token = preferences.getString("auth_token", null)
            if (token == null) {
                continuation.resume(false)
                return@suspendCoroutine
            }

            // リクエストを作成
            val url = URL("${getApiBaseUrl()}/auth/login")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")

            // レスポンスを取得
            val responseCode = connection.responseCode
            Log.d("AuthUtil", "Token validation response: $responseCode")

            if (responseCode == 200) {
                // 成功の場合、レスポンスを解析
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)

                val valid = jsonResponse.optBoolean("valid", false)
                if (valid) {
                    // ユーザー情報を更新
                    val userObj = jsonResponse.optJSONObject("user")
                    if (userObj != null) {
                        val userId = userObj.getString("id")
                        val userEmail = userObj.optString("email")
                        saveUserInfo(userId, userEmail)
                    }
                }

                continuation.resume(valid)
            } else {
                // トークンが無効
                clearAuthData()
                continuation.resume(false)
            }
        } catch (e: Exception) {
            Log.e("AuthUtil", "Token validation error", e)
            clearAuthData()
            continuation.resume(false)
        }
    }

    /**
     * ログアウト処理
     */
    fun logout() {
        clearAuthData()
    }

    /**
     * 認証データを保存
     */
    private fun saveAuthData(userId: String, email: String?, token: String) {
        val editor = preferences.edit()
        editor.putString("user_id", userId)
        editor.putString("auth_token", token)
        if (email != null) {
            editor.putString("user_email", email)
        }
        editor.apply()
    }

    /**
     * ユーザー情報のみを保存/更新
     */
    private fun saveUserInfo(userId: String, email: String?) {
        val editor = preferences.edit()
        editor.putString("user_id", userId)
        if (email != null) {
            editor.putString("user_email", email)
        }
        editor.apply()
    }

    /**
     * 認証データをクリア
     */
    private fun clearAuthData() {
        val editor = preferences.edit()
        editor.remove("user_id")
        editor.remove("user_email")
        editor.remove("auth_token")
        editor.apply()
    }

    /**
     * 認証済みかどうかを確認
     */
    fun isAuthenticated(): Boolean {
        return preferences.contains("auth_token") && preferences.contains("user_id")
    }

    /**
     * ユーザーIDを取得
     */
    fun getUserId(): String? {
        return preferences.getString("user_id", null)
    }

    /**
     * トークンを取得
     */
    fun getAuthToken(): String? {
        return preferences.getString("auth_token", null)
    }
}

/**
 * 認証結果データクラス
 */
data class AuthResult(
    val success: Boolean,
    val userId: String?,
    val token: String?,
    val errorMessage: String?
)