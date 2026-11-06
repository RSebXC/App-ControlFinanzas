package gt.finanzas.privadas

import java.time.LocalDate
import java.time.YearMonth

/** Deterministic local analysis. Never invents missing transactions or bank balances. */
object FinanceAnalysis {
    fun cumulative(daily: List<Long>): List<Long> {
        var total=0L
        return daily.map { total+=it;total }
    }
    fun chartDays(month: String, today: LocalDate = LocalDate.now()): Int {
        val m=YearMonth.parse(month)
        return if(m==YearMonth.from(today)) today.dayOfMonth else m.lengthOfMonth()
    }
    fun questionMonth(question: String, selected: String): String {
        val q=BankParser.fold(question)
        Regex("\\b(20\\d{2}-(?:0[1-9]|1[0-2]))\\b").find(q)?.let { return it.value }
        val names=listOf("enero","febrero","marzo","abril","mayo","junio","julio","agosto","septiembre","octubre","noviembre","diciembre")
        val named=names.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(q) }
        if(named>=0) {
            val year=Regex("\\b20\\d{2}\\b").find(q)?.value?.toInt() ?: YearMonth.parse(selected).year
            return YearMonth.of(year,named+1).toString()
        }
        val comparison=listOf("compar","cambio","diferencia").any { q.contains(it) }
        return if(q.contains("mes pasado") && !comparison) YearMonth.parse(selected).minusMonths(1).toString() else selected
    }
}
