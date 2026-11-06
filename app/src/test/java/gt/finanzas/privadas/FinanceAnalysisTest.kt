package gt.finanzas.privadas

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceAnalysisTest {
    @Test fun cumulativeIncludesRefundsAndDaysWithoutPurchases() {
        assertEquals(listOf(1000L,1000L,-500L,1500L),FinanceAnalysis.cumulative(listOf(1000,0,-1500,2000)))
        assertEquals(emptyList<Long>(),FinanceAnalysis.cumulative(emptyList()))
    }
    @Test fun questionsResolveExplicitAndRelativeMonths() {
        assertEquals("2025-12",FinanceAnalysis.questionMonth("Gastos del mes pasado","2026-01"))
        assertEquals("2026-01",FinanceAnalysis.questionMonth("Compara con el mes pasado","2026-01"))
        assertEquals("2025-09",FinanceAnalysis.questionMonth("¿Cuánto gasté en septiembre 2025?","2026-11"))
        assertEquals("2024-02",FinanceAnalysis.questionMonth("Resumen 2024-02","2026-11"))
        assertEquals("2026-03",FinanceAnalysis.questionMonth("Comida de marzo","2026-11"))
    }
    @Test fun currentMonthStopsAtTodayAndLeapYearsHaveAllDays() {
        val today=LocalDate.of(2026,11,6)
        assertEquals(6,FinanceAnalysis.chartDays("2026-11",today))
        assertEquals(29,FinanceAnalysis.chartDays("2024-02",today))
    }
}
