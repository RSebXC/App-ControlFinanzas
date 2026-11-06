package gt.finanzas.privadas

import org.junit.Assert.*
import org.junit.Test

class SavingsPlannerTest {
    private fun ready(q:String,previous:SavingsPlanner.Draft?=null):SavingsPlanner.Draft {
        val parsed=requireNotNull(SavingsPlanner.interpret(q,previous))
        assertNull(parsed.error);assertNull(parsed.clarification)
        return parsed.draft
    }
    @Test fun initialPlusTwoMonthlyContributions() {
        val plan=ready("Si ahorro Q500 al mes durante 2 meses y ya tengo Q1,000, cuánto tendré")
        val r=SavingsPlanner.calculate(plan)
        assertEquals(200000L,r.finalBalance)
        assertEquals(listOf(150000L,200000L),r.rows.map{it.closing})
        assertEquals(100000L,r.added)
    }
    @Test fun extraAndWithdrawalsAreNotDoubleCounted() {
        val p=ready("Si ahorro Q500 cada mes durante dos meses y tengo Q1000, aporte extra Q200, retiro único Q100, retiro mensual Q50")
        val r=SavingsPlanner.calculate(p)
        assertEquals(200000L,r.finalBalance)
        assertEquals(120000L,r.added)
        assertEquals(20000L,r.withdrawn)
        assertEquals(listOf(155000L,200000L),r.rows.map{it.closing})
    }
    @Test fun followUpsKeepOtherAssumptions() {
        val original=ready("Si ahorro Q500 al mes por 2 meses y tengo Q1000")
        val three=ready("¿Y si son 3 meses?",original)
        val withdrawal=ready("Y si retiro Q200",three)
        val monthly=ready("Mejor Q700 al mes",withdrawal)
        assertEquals(100000L,monthly.initial)
        assertEquals(3,monthly.months)
        assertEquals(70000L,monthly.monthly)
        assertEquals(290000L,SavingsPlanner.calculate(monthly).finalBalance)
    }
    @Test fun missingStartingBalanceIsAskedInsteadOfUsingAnAccount() {
        val partial=SavingsPlanner.interpret("Si ahorro Q500 al mes durante 2 meses")!!
        assertNull(partial.draft.initial)
        assertTrue(partial.clarification!!.contains("ahorro inicial"))
        val completed=ready("Desde cero",partial.draft)
        assertEquals(100000L,SavingsPlanner.calculate(completed).finalBalance)
    }
    @Test fun ambiguousContributionRequiresClarificationAndTotalKeepsEveryCent() {
        val partial=SavingsPlanner.interpret("Si ahorro Q100.01 durante 3 meses y tengo Q0")!!
        assertNotNull(partial.clarification)
        val total=ready("En total",partial.draft)
        val r=SavingsPlanner.calculate(total)
        assertEquals(10001L,r.finalBalance)
        assertEquals(listOf(3333L,3333L,3335L),r.rows.map{it.contribution})
    }
    @Test fun goalRoundsRequiredMonthlyUpToTheCent() {
        val p=ready("Si ahorro Q10 al mes durante 3 meses y tengo Q0, meta Q100")
        val r=SavingsPlanner.calculate(p)
        assertEquals(7000L,r.goalGap)
        assertEquals(3334L,r.requiredMonthly)
    }
    @Test fun explicitOneOffWithdrawalCanProduceAVisibleShortfall() {
        val p=ready("Si ahorro Q10 al mes durante 2 meses y tengo Q0, retiro único Q100")
        assertEquals(-8000L,SavingsPlanner.calculate(p).finalBalance)
    }
    @Test fun formQuestionKeepsAllInputsAndResetsPreviousScenario() {
        val p=SavingsPlanner.Draft(initial=12345,monthly=6789,months=4,extra=150,withdrawal=200,monthlyWithdrawal=50,goal=99999,startsThisMonth=true)
        val parsed=ready(SavingsPlanner.question(p),SavingsPlanner.Draft(initial=999,monthly=999,months=1,extra=999))
        assertEquals(p,parsed)
        assertEquals(p,SavingsPlanner.decode(SavingsPlanner.encode(parsed)))
    }
    @Test fun reportQuestionsRemainReportsAndUnsupportedConditionsAreNotGuessed() {
        assertNull(SavingsPlanner.interpret("Cuánto gasté en comida el mes pasado"))
        assertNotNull(SavingsPlanner.interpret("Si ahorro Q500 al mes por 2 meses al 5% de interés")!!.error)
        assertNotNull(SavingsPlanner.interpret("Si ahorro Q500 al mes durante 121 meses y tengo Q0")!!.error)
        assertNotNull(SavingsPlanner.interpret("Si ahorro Q-500 al mes durante 2 meses y tengo Q0")!!.error)
    }
    @Test fun spanishDecimalAndThousandsAmountsAreExact() {
        assertEquals(120050L,SavingsPlanner.parseAmount("Q1,200.50"))
        assertEquals(120050L,SavingsPlanner.parseAmount("1200,50"))
        assertEquals(120050L,SavingsPlanner.parseAmount("1.200,50"))
        assertNull(SavingsPlanner.parseAmount("-5"))
    }
    @Test fun incompleteScenarioCanBeCompletedWithShortReplies() {
        val start=SavingsPlanner.interpret("Quiero proyectar mis ahorros")!!
        val initial=SavingsPlanner.interpret("1000",start.draft)!!
        val monthly=SavingsPlanner.interpret("Q500 al mes",initial.draft)!!
        val ready=ready("dos",monthly.draft)
        assertEquals(200000L,SavingsPlanner.calculate(ready).finalBalance)
    }
    @Test fun aMonthlyWithdrawalIsNeverTreatedAsAContribution() {
        val plan=ready("Si ahorro Q500 al mes por 2 meses y tengo Q1000, retiro Q50 cada mes")
        assertEquals(50000L,plan.monthly)
        assertEquals(5000L,plan.monthlyWithdrawal)
        assertEquals(190000L,SavingsPlanner.calculate(plan).finalBalance)
    }
    @Test fun unknownAmountsAndContradictoryCadencesAreNotSilentlyIgnored() {
        val previous=ready("Si ahorro Q500 al mes por 2 meses y tengo Q1000")
        assertNotNull(SavingsPlanner.interpret("Y si Q300",previous)!!.error)
        assertNotNull(SavingsPlanner.interpret("Y si aporte extra Q200 al mes",previous)!!.error)
        assertNotNull(SavingsPlanner.interpret("Y si son 1000 meses",previous)!!.error)
    }
    @Test fun overflowFailsInsteadOfWrappingSavingsToNegative() {
        val interpreted=SavingsPlanner.interpret("Si ahorro Q92233720368547758.07 al mes por 2 meses y tengo Q1")!!
        assertNotNull(interpreted.error)
    }
    @Test fun malformedGroupingCannotSilentlyChangeAnAmount() {
        assertNull(SavingsPlanner.parseAmount("1,2,3"))
        assertNull(SavingsPlanner.parseAmount("12,34.50"))
        assertNull(SavingsPlanner.parseAmount("1.2345"))
        assertEquals(123456L,SavingsPlanner.parseAmount("1,234.56"))
    }
}
