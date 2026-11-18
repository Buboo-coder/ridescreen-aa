// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale

/**
 * Small line chart for the fuel log, drawn by hand (no chart library). Points are plotted left to
 * right in the order given; the y range is fitted to the data with a little headroom.
 */
class FuelChartView(context: Context) : View(context) {

    data class Point(val label: String, val value: Double)

    private var points: List<Point> = emptyList()
    private var unit: String = ""
    private var decimals = 1

    private val d = resources.displayMetrics.density
    private val sp = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    var lineColor = 0xFFD1A955.toInt()
    var textColor = 0xFF9AA39B.toInt()
    var gridColor = 0x33FFFFFF

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * sp }

    fun setData(data: List<Point>, unitLabel: String, decimalPlaces: Int) {
        points = data
        unit = unitLabel
        decimals = decimalPlaces
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (190 * d).toInt())
    }

    private fun fmt(v: Double) = String.format(Locale.getDefault(), "%.${decimals}f", v)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        grid.color = gridColor
        line.color = lineColor
        dot.color = lineColor
        fill.color = (lineColor and 0x00FFFFFF) or 0x22000000
        text.color = textColor

        val left = 44f * d
        val right = width - 14f * d
        val top = 16f * d
        val bottom = height - 24f * d
        if (right <= left || bottom <= top) return

        var lo = points.minOf { it.value }
        var hi = points.maxOf { it.value }
        if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.15
        lo -= pad; hi += pad
        if (lo < 0 && points.minOf { it.value } >= 0) lo = 0.0

        fun yOf(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun xOf(i: Int) = if (points.size == 1) (left + right) / 2f else left + (right - left) * i / (points.size - 1)

        // Grid + y labels (3 lines).
        text.textAlign = Paint.Align.RIGHT
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2.0
            val y = yOf(v)
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText(fmt(v), left - 6f * d, y + 4f * d, text)
        }

        // Area + line.
        if (points.size >= 2) {
            val area = Path().apply {
                moveTo(xOf(0), bottom)
                points.forEachIndexed { i, p -> lineTo(xOf(i), yOf(p.value)) }
                lineTo(xOf(points.size - 1), bottom)
                close()
            }
            canvas.drawPath(area, fill)
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(xOf(i), yOf(p.value)) else path.lineTo(xOf(i), yOf(p.value)) }
            canvas.drawPath(path, line)
        }
        points.forEachIndexed { i, p -> canvas.drawCircle(xOf(i), yOf(p.value), 4f * d, dot) }

        // Last value above its dot, with the unit.
        val last = points.last()
        text.textAlign = if (points.size == 1) Paint.Align.CENTER else Paint.Align.RIGHT
        canvas.drawText("${fmt(last.value)} $unit", xOf(points.size - 1) + if (points.size == 1) 0f else 4f * d, yOf(last.value) - 9f * d, text)

        // X labels: first, middle, last (when they fit).
        val idx = if (points.size >= 3) listOf(0, points.size / 2, points.size - 1) else points.indices.toList()
        for (i in idx) {
            text.textAlign = when {
                points.size == 1 -> Paint.Align.CENTER
                i == 0 -> Paint.Align.LEFT
                i == points.size - 1 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(points[i].label, xOf(i), height - 6f * d, text)
        }
    }
}
