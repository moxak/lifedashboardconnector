package com.example.lifedashboard.androidapp.model

import java.time.LocalDateTime

/**
 * 同期履歴を表すデータクラス
 */
data class SyncRecord(
    val id: Long = 0,
    val timestamp: LocalDateTime,
    val success: Boolean,
    val recordCount: Int,
    val errorMessage: String? = null
)