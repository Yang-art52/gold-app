package com.goldhourly.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** 今日 0–23 点折线图，自绘，无第三方图表库 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pts: List<Pair<Int, Double>> = emptyList()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E2530.toInt()
        strokeWidth = 1f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0C04A.toInt()
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0C04A.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5B6472.toInt()
        textSize = dp(10f)
    }

    fun setPoints(p: List<Pair<Int, Double>>) {
        pts = p
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 80f || h < 60f) return
        val left = dp(40f)
        val right = w - dp(8f)
        val top = dp(10f)
        val bottom = h - dp(18f)
        val iw = right - left
        val ih = bottom - top
        if (pts.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("读取中…", w / 2f, h / 2f, textPaint)
            return
        }
        var lo = pts.minOf { it.second }
        var hi = pts.maxOf { it.second }
        val pad = max((hi - lo) * 0.18, 0.5)
        lo -= pad
        hi += pad
        for (i in 0..2) {
            val y = top + ih * i / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
            val v = hi - (hi - lo) * i / 2.0
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                String.format(java.util.Locale.US, "%.0f", v),
                left - dp(6f), y + dp(4f), textPaint
            )
        }
        textPaint.textAlign = Paint.Align.CENTER
        for (hour in intArrayOf(0, 6, 12, 18, 23)) {
            canvas.drawText(hour.toString(), left + iw * hour / 23f, h - dp(4f), textPaint)
        }
        val xAt = { hour: Int -> left + iw * hour / 23f }
        val yAt = { v: Double -> (top + ih * (1.0 - (v - lo) / (hi - lo))).toFloat() }
        if (pts.size == 1) {
            canvas.drawCircle(xAt(pts[0].first), yAt(pts[0].second), dp(3f), dotPaint)
            return
        }
        val path = Path()
        pts.forEachIndexed { index, p ->
            if (index == 0) path.moveTo(xAt(p.first), yAt(p.second))
            else path.lineTo(xAt(p.first), yAt(p.second))
        }
        canvas.drawPath(path, linePaint)
        for (p in pts) canvas.drawCircle(xAt(p.first), yAt(p.second), dp(2.4f), dotPaint)
    }
}
