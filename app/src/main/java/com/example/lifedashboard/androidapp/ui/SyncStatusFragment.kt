package com.example.lifedashboard.androidapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lifedashboard.androidapp.databinding.FragmentSyncStatusBinding
import com.example.lifedashboard.androidapp.model.SyncRecord
import com.example.lifedashboard.androidapp.util.DatabaseHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SyncStatusFragment : Fragment() {

    private var _binding: FragmentSyncStatusBinding? = null
    private val binding get() = _binding!!
    private lateinit var syncAdapter: SyncHistoryAdapter
    private lateinit var dbHelper: DatabaseHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSyncStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dbHelper = DatabaseHelper(requireContext())

        // RecyclerViewの初期化
        setupRecyclerView()

        // 更新ボタンの設定
        binding.refreshButton.setOnClickListener {
            loadSyncHistory()
            Snackbar.make(binding.root, "同期履歴を更新しました", Snackbar.LENGTH_SHORT).show()
        }

        // 履歴をロード
        loadSyncHistory()
    }

    private fun setupRecyclerView() {
        syncAdapter = SyncHistoryAdapter(emptyList())
        binding.syncHistoryRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = syncAdapter
        }
    }

    private fun loadSyncHistory() {
        lifecycleScope.launch {
            try {
                val syncRecords = dbHelper.getSyncHistory()

                if (syncRecords.isEmpty()) {
                    binding.emptyStateText.visibility = View.VISIBLE
                } else {
                    binding.emptyStateText.visibility = View.GONE
                }

                // 同期成功率の計算
                val totalRecords = syncRecords.size
                val successRecords = syncRecords.count { it.success }
                val successRate = if (totalRecords > 0) {
                    (successRecords.toFloat() / totalRecords) * 100
                } else {
                    0f
                }

                // ステータス表示更新
                binding.syncRateText.text = "同期成功率: ${String.format("%.1f", successRate)}%"
                binding.totalSyncsText.text = "合計同期試行: $totalRecords"
                binding.successfulSyncsText.text = "成功: $successRecords"
                binding.failedSyncsText.text = "失敗: ${totalRecords - successRecords}"

                // 最新のN件を表示
                syncAdapter.updateData(syncRecords.take(50))

            } catch (e: Exception) {
                Snackbar.make(binding.root, "同期履歴の読み込みに失敗しました: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * 同期履歴を表示するためのアダプター
 */
class SyncHistoryAdapter(private var syncRecords: List<SyncRecord>) :
    RecyclerView.Adapter<SyncHistoryAdapter.SyncViewHolder>() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    class SyncViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val timeText: android.widget.TextView = view.findViewById(android.R.id.text1)
        val statusText: android.widget.TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SyncViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return SyncViewHolder(view)
    }

    override fun onBindViewHolder(holder: SyncViewHolder, position: Int) {
        val syncRecord = syncRecords[position]
        holder.timeText.text = syncRecord.timestamp.format(dateFormatter)

        if (syncRecord.success) {
            holder.statusText.text = "成功: ${syncRecord.recordCount}件のデータを同期"
            holder.statusText.setTextColor(android.graphics.Color.GREEN)
        } else {
            holder.statusText.text = "失敗: ${syncRecord.errorMessage ?: "不明なエラー"}"
            holder.statusText.setTextColor(android.graphics.Color.RED)
        }
    }

    override fun getItemCount() = syncRecords.size

    fun updateData(newRecords: List<SyncRecord>) {
        syncRecords = newRecords
        notifyDataSetChanged()
    }
}