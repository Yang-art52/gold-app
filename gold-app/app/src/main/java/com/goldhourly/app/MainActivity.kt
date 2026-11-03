package com.goldhourly.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private val BG = Color.parseColor("#0D1117")
        private val TEXT = Color.parseColor("#E6E9EF")
        private val DIM = Color.parseColor("#8B94A3")
        private val UP = Color.parseColor("#F2B53C")
        private val DOWN = Color.parseColor("#3FBF7F")
        private val CARD = Color.parseColor("#1A2029")
    }

    private lateinit var tvPrice: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvChange: TextView
    private lateinit var tvUpdate: TextView
    private lateinit var tvState: TextView
    private lateinit var chart: ChartView
    private lateinit var et: EditText
    private lateinit var btnSave: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnBattery: Button

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var lastErr = false

    private val tick = object : Runnable {
        override fun run() {
            if (!hasPointForNow()) refresh()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        Notifier.ensureChannel(this)
        HourlyAlarm.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        fillThreshold()
        render()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!hasPointForNow()) refresh()
    }

    override fun onStart() {
        super.onStart()
        handler.postDelayed(tick, 60_000)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
    }

    // ---------- 界面 ----------

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(dp(16), dp(18), dp(16), dp(12))

        root.addView(text("黄金 XAU/USD · 美元/盎司", 12f, DIM))

        tvPrice = text("--", 44f, TEXT)
        tvPrice.typeface = Typeface.DEFAULT_BOLD
        root.addView(tvPrice, lp(2))

        tvSub = text("", 14f, DIM)
        root.addView(tvSub)

        tvChange = text("", 13f, DIM)
        root.addView(tvChange, lp(2))

        chart = ChartView(this)
        root.addView(
            chart,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) }
        )

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        et = EditText(this)
        et.hint = "底价 元/克"
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.setTextColor(TEXT)
        et.setHintTextColor(DIM)
        et.textSize = 16f
        et.setBackgroundColor(CARD)
        et.setPadding(dp(10), dp(8), dp(10), dp(8))
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        btnSave = button("保存底价")
        row.addView(
            btnSave,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) }
        )
        root.addView(row, lp(10))

        tvState = text("未设置底价", 12f, DIM)
        root.addView(tvState, lp(6))

        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.CENTER_VERTICAL
        tvUpdate = text("--", 12f, DIM)
        foot.addView(tvUpdate, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnBattery = button("允许后台")
        foot.addView(
            btnBattery,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
        )
        btnRefresh = button("刷新")
        foot.addView(btnRefresh)
        root.addView(foot, lp(8))

        btnSave.setOnClickListener { saveThreshold() }
        btnRefresh.setOnClickListener { refresh() }
        btnBattery.setOnClickListener { askBattery() }
        return root
    }

    private fun text(s: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
    }

    private fun button(s: String): Button = Button(this).apply {
        text = s
        textSize = 13f
        isAllCaps = false
        setTextColor(TEXT)
        setBackgroundColor(CARD)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), dp(6), dp(14), dp(6))
    }

    private fun lp(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- 数据 ----------

    private fun hasPointForNow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return PriceStore.hours(this).any { it.first == hour }
    }

    private fun refresh() {
        if (busy) return
        busy = true
        Thread {
            val ok = try {
                Sync.run(this)
            } catch (_: Exception) {
                false
            }
            handler.post {
                busy = false
                lastErr = !ok
                render()
            }
        }.start()
    }

    private fun render() {
        val rate = PriceStore.fx(this)
        val usdPoints = PriceStore.hours(this)
        val points = usdPoints.map {
            it.first to (if (rate > 0.0) it.second * rate / GoldApi.OZ_GRAMS else it.second)
        }
        chart.setPoints(points)

        val threshold = PriceStore.threshold(this)
        tvState.text = if (threshold > 0.0) {
            String.format(Locale.US, "底价 %.1f 元/克 · 每小时后台检查", threshold)
        } else {
            "未设置底价（不会提醒）"
        }
        btnBattery.text = if (HourlyAlarm.ignoringBattery(this)) "后台已开" else "允许后台"

        if (points.isEmpty()) {
            tvPrice.text = "--"
            tvSub.text = ""
            tvChange.text = ""
            tvUpdate.text = if (lastErr) "网络异常，稍后自动重试" else "读取中…"
            return
        }

        val last = points.last()
        val lastUsd = usdPoints.last().second
        if (rate > 0.0) {
            tvPrice.text = String.format(Locale.US, "%.1f", last.second)
            tvSub.text = String.format(Locale.US, "元/克 · 国际 %.1f 美元/盎司", lastUsd)
        } else {
            tvPrice.text = String.format(Locale.US, "%.1f", last.second)
            tvSub.text = "美元/盎司 · 汇率暂不可用"
        }

        if (points.size >= 2) {
            val prev = points[points.size - 2].second
            val diff = last.second - prev
            val pct = if (prev > 0.0) diff / prev * 100.0 else 0.0
            val sign = if (diff > 0.0) "▲ +" else if (diff < 0.0) "▼ " else "± "
            tvChange.text = sign + String.format(Locale.US, "%.1f", Math.abs(diff)) +
                String.format(Locale.US, "（%.2f%%）较上一小时", Math.abs(pct))
            tvChange.setTextColor(if (diff > 0.0) UP else if (diff < 0.0) DOWN else DIM)
        } else {
            tvChange.text = "今日第一个小时"
            tvChange.setTextColor(DIM)
        }

        val cal = Calendar.getInstance()
        cal.timeInMillis = PriceStore.lastTs(this)
        tvUpdate.text = String.format(
            Locale.US, "更新 %02d:%02d · 今日 %d 点",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), points.size
        )
    }

    // ---------- 底价 ----------

    private fun fillThreshold() {
        val t = PriceStore.threshold(this)
        if (t > 0.0) et.setText(String.format(Locale.US, "%.1f", t))
    }

    private fun saveThreshold() {
        val v = et.text.toString().trim().toDoubleOrNull()
        if (v == null || v <= 0.0) {
            toast("请输入有效底价，例如 905")
            return
        }
        PriceStore.setThreshold(this, v)
        PriceStore.setArmed(this, true)
        et.clearFocus()
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(et.windowToken, 0)
        render()
        toast("已保存：不高于 " + String.format(Locale.US, "%.1f", v) + " 元/克时提醒")
        if (!hasPointForNow()) refresh()
    }

    private fun askBattery() {
        if (HourlyAlarm.ignoringBattery(this)) {
            toast("后台运行已允许")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("请在系统设置中允许本应用后台运行")
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
