package com.example.lifedashboard.androidapp.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lifedashboard.androidapp.databinding.FragmentUsageSummaryBinding
import com.example.lifedashboard.data.AppUsageItem
import com.example.lifedashboard.data.PhoneUsageRepository
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class UsageSummaryFragment : Fragment() {

    private var _binding: FragmentUsageSummaryBinding? = null
    private val binding get() = _binding!!
    private lateinit var appUsageAdapter: AppUsageAdapter
    private lateinit var repository: PhoneUsageRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUsageSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = PhoneUsageRepository(requireContext())

        // RecyclerViewの初期化
        setupRecyclerView()

        // 更新ボタンの設定
        binding.refreshButton.setOnClickListener {
            loadUsageSummary()
            Snackbar.make(binding.root, "使用状況データを更新しました", Snackbar.LENGTH_SHORT).show()
        }

        // 使用状況データをロード
        loadUsageSummary()

        // チャートの初期設定
        setupChart()
    }

    private fun setupRecyclerView() {
        appUsageAdapter = AppUsageAdapter(emptyList())
        binding.appUsageRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = appUsageAdapter
        }
    }

    private fun setupChart() {
        binding.usageBarChart.apply {
            description.isEnabled = false
            legend.isEnabled = true

            // X軸の設定
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return "${value.toInt()}時"
                }
            }

            // タッチ操作設定
            isDoubleTapToZoomEnabled = false
            setPinchZoom(false)
            setScaleEnabled(false)
        }
    }

    private fun loadUsageSummary() {
        lifecycleScope.launch {
            try {
                // 当日の使用状況データを取得
                val today = LocalDate.now()
                val phoneUsageData = repository.getDailySummary(today)

                if (phoneUsageData != null) {
                    // 基本情報の表示
                    val totalHours = phoneUsageData.totalUsageTime / 60
                    val totalMinutes = phoneUsageData.totalUsageTime % 60

                    binding.totalUsageText.text = "総使用時間: ${totalHours}時間${totalMinutes}分"
                    binding.unlockCountText.text = "画面ロック解除回数: ${phoneUsageData.screenUnlocks}回"
                    binding.dateText.text = "日付: ${
                        LocalDate.parse(phoneUsageData.date).format(
                            DateTimeFormatter.ofPattern("yyyy年MM月dd日")
                        )
                    }"

                    // バッテリーレベルの表示（あれば）
                    phoneUsageData.batteryLevel?.let {
                        binding.batteryLevelText.text = "バッテリーレベル: ${it}%"
                        binding.batteryLevelText.visibility = View.VISIBLE
                    } ?: run {
                        binding.batteryLevelText.visibility = View.GONE
                    }

                    // 通知数の表示（あれば）
                    phoneUsageData.notifications?.let {
                        binding.notificationCountText.text = "通知数: ${it}件"
                        binding.notificationCountText.visibility = View.VISIBLE
                    } ?: run {
                        binding.notificationCountText.visibility = View.GONE
                    }

                    // アプリ使用状況リストの更新
                    appUsageAdapter.updateData(phoneUsageData.appUsage)

                    // 時間別使用パターンを取得してグラフを描画
                    loadHourlyPattern(today)

                    binding.usageSummaryLayout.visibility = View.VISIBLE
                    binding.emptyStateText.visibility = View.GONE
                } else {
                    binding.usageSummaryLayout.visibility = View.GONE
                    binding.emptyStateText.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                Snackbar.make(binding.root, "使用状況データの読み込みに失敗しました: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun loadHourlyPattern(date: LocalDate) {
        try {
            // 直近一週間の時間別使用パターンを取得
            val startDate = date.minusDays(6)
            val hourlyPattern = repository.getHourlyUsagePattern(startDate, date)

            if (hourlyPattern != null && hourlyPattern.isNotEmpty()) {
                // グラフデータの作成
                val entries = hourlyPattern.map { (hour, minutes) ->
                    BarEntry(hour.toFloat(), minutes.toFloat())
                }.sortedBy { it.x }

                val dataSet = BarDataSet(entries, "平均使用時間（分）").apply {
                    color = Color.BLUE
                    valueTextColor = Color.BLACK
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return String.format(Locale.getDefault(), "%.1f", value)
                        }
                    }
                }

                val barData = BarData(dataSet)
                binding.usageBarChart.data = barData
                binding.usageBarChart.invalidate()

                binding.chartContainer.visibility = View.VISIBLE
            } else {
                binding.chartContainer.visibility = View.GONE
            }
        } catch (e: Exception) {
            binding.chartContainer.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * アプリ使用状況を表示するためのアダプター
 */
class AppUsageAdapter(private var appUsageItems: List<AppUsageItem>) :
    RecyclerView.Adapter<AppUsageAdapter.AppUsageViewHolder>() {

    class AppUsageViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val appNameText: android.widget.TextView = view.findViewById(android.R.id.text1)
        val usageText: android.widget.TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppUsageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return AppUsageViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppUsageViewHolder, position: Int) {
        val appUsage = appUsageItems[position]
        holder.appNameText.text = appUsage.appName

        val hours = appUsage.usageTime / 60
        val minutes = appUsage.usageTime % 60

        if (hours > 0) {
            holder.usageText.text = "${hours}時間${minutes}分 (${appUsage.openCount}回起動)"
        } else {
            holder.usageText.text = "${minutes}分 (${appUsage.openCount}回起動)"
        }
    }

    override fun getItemCount() = appUsageItems.size

    fun updateData(newItems: List<AppUsageItem>) {
        appUsageItems = newItems
        notifyDataSetChanged()
    }
}