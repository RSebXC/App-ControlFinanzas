package gt.finanzas.privadas

import java.io.File

/** All fixtures are invented, including dates, amounts and account labels. */
fun runParserChecks() {
    val old = File("../tests/fixtures/banrural_synthetic.txt").takeIf { it.exists() }
        ?: File("tests/fixtures/banrural_synthetic.txt")
    val lines = old.readLines().filter { it.isNotBlank() }
    val parsed = lines.mapNotNull { BankParser.parse(it) }
    check(lines.size == 17 && parsed.size == 17)
    check(parsed.all { it.accountLast4 in listOf("1111", "2222") })
    check(parsed.filter { it.kind == "EXPENSE" }.sumOf { it.amountCents } == 15550L)
    check(parsed.filter { it.kind == "REFUND" }.sumOf { it.amountCents } == 300L)
    check(parsed.filter { it.kind == "PENDING_DEBIT" }.sumOf { it.amountCents } == 69500L)
    check(parsed.filter { it.kind == "PENDING_CREDIT" }.sumOf { it.amountCents } == 123100L)

    val samples = listOf(
        "Se debito de tu cuenta Monetaria *** ****1111 la cantidad de Q25.00 en UNIDAD APP MOVIL BANRURAL, el 12/02/2026 a las 10:00.",
        "Se realizo una compra en POS de Q15.00 desde tu cuenta Monetaria *** ****1111 en TIENDA DEMO, el 12/02/2026 a las 10:01.",
        "Se debito de tu cuenta Ahorro *** ****2222 la cantidad de Q80.00 en TRANSFERENCIAS INMEDIATAS -TIF-, el 12/02/2026 a las 10:02.",
        "Se realizo una compra en POS de Q30.00 desde tu cuenta Monetaria *** ****1111 en NETFLIX DEMO, el 12/02/2026 a las 10:03.",
        "Se realizo una compra en POS de Q40.00 desde tu cuenta Monetaria *** ****1111 en CINE DEMO, el 12/02/2026 a las 10:04."
    ).map { BankParser.parse(it) ?: error("Synthetic modern sample was not recognized") }
    check(samples[0].kind == "PENDING_DEBIT" && samples[0].embeddedTime != null)
    check(samples[1].kind == "EXPENSE" && samples[1].amountCents == 1500L)
    check(samples[2].kind == "PENDING_DEBIT" && samples[2].accountType == "Ahorro")
    check(samples[3].category == "Suscripciones")
    check(samples[4].category == "Entretenimiento")

    val biCredit = BankParser.parse("BiMovil: Credito por Q.20.00 Cuenta MONE en la Agencia DEMO 12-Feb 10:00.")!!
    val biExpense = BankParser.parse("BiMovil: Consumo por Q.10.00 en MCDONALDS DEMO Cuenta BICHEQUE1 12-Feb 10:01.")!!
    check(biCredit.bank == "Banco Industrial" && biCredit.kind == "PENDING_CREDIT" && biCredit.accountLast4 == "MONE")
    check(biExpense.kind == "EXPENSE" && biExpense.category == "Comida")

    val creditText = "Recibiste Q75.00 en tu cuenta Monetaria *** ****1111 de UNIDAD APP MOVIL BANRURAL, el 12/02/2026 a las 10:05."
    val credit = BankParser.parse(creditText)!!
    val debit = BankParser.parse("Se debito de tu cuenta Ahorro *** ****2222 la cantidad de Q75.00 en UNIDAD APP MOVIL BANRURAL, el 12/02/2026 a las 10:05 .")!!
    val expense = BankParser.parse("Se realizo una compra en POS de Q75.00 desde tu cuenta Monetaria *** ****1111 en CLARO DEMO, el 12/02/2026 a las 10:06.")!!
    check(credit.kind == "PENDING_CREDIT" && debit.kind == "PENDING_DEBIT")
    check(credit.amountCents == debit.amountCents && credit.embeddedTime == debit.embeddedTime)
    check(credit.accountLast4 != debit.accountLast4)
    check(expense.kind == "EXPENSE" && expense.category == "Servicios")
    check(BankParser.parse("BANRURAL: $creditText") == credit)
    check(BankParser.parse("BANRURAL: codigo para PAGO DEMO Cuenta Monetaria ****1111 Q1.00") == null)
    check(BankParser.parse("BANRURAL: tu contrasena temporal DEMO Cuenta Monetaria ****1111 Q1.00") == null)
    check(BankParser.parse("BANRURAL: se Debito en POS Q.0.00 de tu Cuenta Monetaria ****1111 por consumo en: DEMO") == null)
    check(BankParser.parse("Recibiste Q75.00 en tu cuenta Monetaria sin identificador de cuenta de DEMO") == null)
}

fun main() {
    runParserChecks()
    println("OK: synthetic old/new Banrural formats, BI, transfer candidates, privacy and amounts")
}
