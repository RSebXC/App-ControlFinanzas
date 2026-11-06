package gt.finanzas.privadas

import java.time.YearMonth
import kotlin.math.abs

/** Read-only presentation calculations. All persisted amounts remain integer cents. */
object ReportMath {
    fun monthsEndingAt(month: String, count: Int = 6): List<String> {
        require(count in 1..12)
        val end = YearMonth.parse(month)
        return (count - 1 downTo 0).map { end.minusMonths(it.toLong()).toString() }
    }

    fun share(amount: Long, positiveTotal: Long): Int =
        if (positiveTotal <= 0 || amount <= 0) 0
        else (amount.toDouble() / positiveTotal * 100).toInt().coerceIn(0, 100)

    fun changePercent(current: Long, previous: Long): String? =
        if (previous <= 0) null
        else String.format(java.util.Locale.US, "%+.1f%%", (current.toDouble() - previous) / previous * 100)

    fun compact(cents: Long): String {
        val value = cents / 100.0
        val magnitude = abs(value)
        return when {
            magnitude >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000)
            magnitude >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000)
            else -> String.format(java.util.Locale.US, "%.0f", value)
        }
    }
}
