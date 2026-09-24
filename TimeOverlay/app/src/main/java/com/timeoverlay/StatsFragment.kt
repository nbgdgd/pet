package com.timeoverlay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.timeoverlay.databinding.FragmentStatsBinding
import java.util.concurrent.Executors

/** Вкладка статистики: сколько времени съели приложения за выбранный период. */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: UsageStatsRepository
    private val adapter = AppUsageAdapter()
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var period = StatsPeriod.TODAY

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repository = UsageStatsRepository(requireContext())
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.periodGroup.check(R.id.periodToday)
        binding.periodGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            period = when (checkedId) {
                R.id.periodWeek -> StatsPeriod.WEEK
                R.id.periodMonth -> StatsPeriod.MONTH
                else -> StatsPeriod.TODAY
            }
            reload()
        }
        binding.permissionButton.setOnClickListener {
            startActivity(PermissionHelper.usageAccessIntent())
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        if (!PermissionHelper.hasUsageAccess(requireContext())) {
            showPermissionState()
            return
        }
        binding.permissionState.visibility = View.GONE

        val requested = period
        executor.execute {
            val usage = repository.load(requested)
            handler.post {
                // Экран мог закрыться или период смениться, пока считали.
                if (_binding == null || requested != period) return@post
                render(usage)
            }
        }
    }

    private fun render(usage: List<AppUsage>) {
        adapter.submit(usage)
        binding.totalValue.text = TimeFormat.formatLong(usage.sumOf { it.millis })
        binding.emptyState.visibility = if (usage.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (usage.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showPermissionState() {
        adapter.submit(emptyList())
        binding.totalValue.text = TimeFormat.formatLong(0)
        binding.emptyState.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.permissionState.visibility = View.VISIBLE
    }
}
