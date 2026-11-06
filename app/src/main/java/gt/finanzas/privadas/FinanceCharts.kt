package gt.finanzas.privadas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Canvas chart with a shared scale, a real zero line and negative net expenses. */
@android.annotation.SuppressLint("ViewConstructor") // Created programmatically with report data.
class FinanceTrendView(
    context: Context,
    private val reports: List<FinanceDb.MonthReport>,
    private val selected: String,
    private val onMonthSelected: (String) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val incomeColor = Color.rgb(0, 119, 82)
    private val expenseColor = Color.rgb(193, 69, 76)
    private fun px(value: Float) = value * density
    private val labelFormat = DateTimeFormatter.ofPattern("MMM yy", Locale("es", "GT"))
    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Ingresos y gastos netos en quetzales. " + reports.joinToString(". ") {
            "${it.ym}: ingresos ${FinanceDb.format(it.income)}, gastos netos ${FinanceDb.format(it.expenses)}" +
                if (it.count == 0L) ", sin movimientos registrados" else ""
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (reports.isEmpty()) return
        val left = px(51f)
        val right = width - px(5f)
        val top = px(20f)
        val bottom = height - px(33f)
        val high = (reports.maxOf { maxOf(it.income, it.expenses, 0L) }).coerceAtLeast(100L).toDouble()
        val low = reports.minOf { minOf(it.expenses, 0L) }.toDouble()
        val range = high - low
        fun y(value: Double) = (top + (high - value) / range * (bottom - top)).toFloat()
        val zero = y(0.0)
        paint.textSize = px(10f)
        for (step in 0..4) {
            val value = high - range * step / 4
            val lineY = y(value)
            paint.color = Color.rgb(229, 236, 233)
            paint.strokeWidth = px(1f)
            canvas.drawLine(left, lineY, right, lineY, paint)
            paint.color = Color.rgb(83, 105, 97)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(ReportMath.compact(value.toLong()), left - px(7f), lineY + px(3f), paint)
        }
        paint.color = Color.rgb(140, 157, 150)
        canvas.drawLine(left, zero, right, zero, paint)
        val group = (right - left) / reports.size
        reports.forEachIndexed { index, report ->
            val center = left + group * (index + .5f)
            val barWidth = group * .24f
            fun bar(value: Long, x: Float, color: Int) {
                if (value == 0L) return
                val end = y(value.toDouble())
                paint.color = color
                canvas.drawRoundRect(x, minOf(zero, end), x + barWidth, maxOf(zero, end), px(3f), px(3f), paint)
            }
            bar(report.income, center - barWidth - px(2f), incomeColor)
            bar(report.expenses, center + px(2f), expenseColor)
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (report.ym == selected) incomeColor else Color.rgb(83, 105, 97)
            paint.isFakeBoldText = report.ym == selected
            canvas.drawText(YearMonth.parse(report.ym).format(labelFormat), center, height - px(12f), paint)
            paint.isFakeBoldText = false
            if (report.count == 0L) {
                paint.color = Color.rgb(83, 105, 97)
                canvas.drawText("—", center, zero - px(5f), paint)
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (event.x >= px(51f) && event.x <= width - px(5f) && reports.isNotEmpty()) {
                val index = ((event.x - px(51f)) / ((width - px(56f)) / reports.size)).toInt().coerceIn(reports.indices)
                onMonthSelected(reports[index].ym)
            }
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
