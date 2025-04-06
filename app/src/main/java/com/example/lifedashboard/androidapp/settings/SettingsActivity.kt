package com.example.lifedashboard.androidapp.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lifedashboard.androidapp.data.AuthUtil
import com.example.lifedashboard.androidapp.data.LifeDashboardAPI
import com.example.lifedashboard.androidapp.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * アプリ設定画面
 * - ユーザー認証
 * - API設定
 * - 同期間隔設定
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var api: LifeDashboardAPI
    private lateinit var authUtil: AuthUtil
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // APIと認証ユーティリティの初期化
        api = LifeDashboardAPI(this)
        authUtil = AuthUtil(this)

        // 設定の読み込み
        loadSettings()

        // ログインボタンのクリックリスナー
        binding.loginButton.setOnClickListener {
            // 状態に応じてログインまたはログアウト
            if (authUtil.isAuthenticated()) {
                logoutUser()
            } else {
                val email = binding.emailInput.text.toString()
                val password = binding.passwordInput.text.toString()

                if (email.isNotEmpty() && password.isNotEmpty()) {
                    authenticateUser(email, password)
                } else {
                    Toast.makeText(this, "メールアドレスとパスワードを入力してください", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // API URLの保存ボタン
        binding.saveApiUrlButton.setOnClickListener {
            val apiUrl = binding.apiUrlInput.text.toString()
            if (apiUrl.isNotEmpty()) {
                saveApiUrl(apiUrl)
            }
        }

        // 同期間隔の設定
        binding.syncIntervalButton.setOnClickListener {
            val hours = binding.syncIntervalInput.text.toString().toIntOrNull()
            if (hours != null && hours > 0) {
                saveSyncInterval(hours)
            } else {
                Toast.makeText(this, "有効な時間を入力してください", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 画面表示時に設定を読み込む
     */
    override fun onResume() {
        super.onResume()
        // 認証状態を再確認
        updateAuthStatus()
    }

    /**
     * 設定の読み込み
     */
    private fun loadSettings() {
        // APIのURL
        val apiUrl = preferences.getString("api_base_url", "")
        binding.apiUrlInput.setText(apiUrl)

        // 同期間隔
        val syncInterval = preferences.getInt("sync_interval_hours", 6)
        binding.syncIntervalInput.setText(syncInterval.toString())

        // 認証状態の更新
        updateAuthStatus()

        // Emailをプリセット（保存されている場合）
        val savedEmail = preferences.getString("saved_email", "")
        if (savedEmail?.isNotEmpty() == true) {
            binding.emailInput.setText(savedEmail)
        }
    }

    /**
     * 認証状態の表示を更新
     */
    private fun updateAuthStatus() {
        if (authUtil.isAuthenticated()) {
            val userId = authUtil.getUserId()
            val email = preferences.getString("user_email", "不明なユーザー")

            binding.authStatusText.text = "認証済み: $email (ID: ${userId?.take(8)}...)"
            binding.loginButton.text = "ログアウト"

            // 入力欄を無効化
            binding.emailInput.isEnabled = false
            binding.passwordInput.isEnabled = false
        } else {
            binding.authStatusText.text = "未認証"
            binding.loginButton.text = "ログイン"

            // 入力欄を有効化
            binding.emailInput.isEnabled = true
            binding.passwordInput.isEnabled = true
        }
    }

    /**
     * ユーザー認証を実行
     */
    private fun authenticateUser(email: String, password: String) {
        // UI状態の更新
        binding.loginButton.isEnabled = false
        binding.authStatusText.text = "認証中..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // AuthUtilを使った認証
                    authUtil.login(email, password)
                }

                if (result.success && result.userId != null) {
                    // 認証成功
                    // メールアドレスを次回のために保存
                    val editor = preferences.edit()
                    editor.putString("saved_email", email)
                    editor.apply()

                    runOnUiThread {
                        updateAuthStatus()
                        Toast.makeText(this@SettingsActivity, "ログインしました", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // 認証失敗
                    runOnUiThread {
                        binding.authStatusText.text = "認証失敗: ${result.errorMessage ?: "不明なエラー"}"
                        binding.loginButton.isEnabled = true
                        Toast.makeText(this@SettingsActivity, "ログインに失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                // 例外処理
                runOnUiThread {
                    binding.authStatusText.text = "認証エラー: ${e.localizedMessage}"
                    binding.loginButton.isEnabled = true
                    Toast.makeText(this@SettingsActivity, "通信エラーが発生しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * ログアウト処理
     */
    private fun logoutUser() {
        // AuthUtilを使ったログアウト
        authUtil.logout()

        // パスワードフィールドをクリア（メールアドレスは保持）
        binding.passwordInput.setText("")

        // UI状態の更新
        updateAuthStatus()
        Toast.makeText(this, "ログアウトしました", Toast.LENGTH_SHORT).show()
    }

    /**
     * API URLの保存
     */
    private fun saveApiUrl(apiUrl: String) {
        val editor = preferences.edit()
        editor.putString("api_base_url", apiUrl)
        editor.apply()

        Toast.makeText(this, "API URLを保存しました", Toast.LENGTH_SHORT).show()
    }

    /**
     * 同期間隔の保存
     */
    private fun saveSyncInterval(hours: Int) {
        val editor = preferences.edit()
        editor.putInt("sync_interval_hours", hours)
        editor.apply()

        Toast.makeText(this, "同期間隔を${hours}時間に設定しました", Toast.LENGTH_SHORT).show()
    }
}