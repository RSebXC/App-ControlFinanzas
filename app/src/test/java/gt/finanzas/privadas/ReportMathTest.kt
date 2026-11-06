package gt.finanzas.privadas

import org.junit.Assert.*
import org.junit.Test

class ReportMathTest {
    @Test fun consecutiveMonthsCrossYearsAndKeepMissingMonths() {
        assertEquals(listOf("2025-10", "2025-11", "2025-12", "2026-01", "2026-02", "2026-03"), ReportMath.monthsEndingAt("2026-03"))
    }
    @Test fun percentagesHandleRefundsAndEmptyMonths() {
        assertEquals(0, ReportMath.share(-500, 1000))
        assertEquals(0, ReportMath.share(500, 0))
        assertEquals(25, ReportMath.share(250, 1000))
        assertNull(ReportMath.changePercent(1000, 0))
        assertNull(ReportMath.changePercent(1000, -500))
        assertEquals("-50.0%", ReportMath.changePercent(500, 1000))
        assertEquals("+25.0%", ReportMath.changePercent(1250, 1000))
    }
    @Test fun sharesDoNotOverflowIntegerCents() {
        assertEquals(50, ReportMath.share(Long.MAX_VALUE / 2, Long.MAX_VALUE))
        assertEquals("-1.5k", ReportMath.compact(-150000))
    }
}
