package gt.finanzas.privadas

import java.math.BigDecimal
import java.math.RoundingMode

/** Hypothetical plans in integer cents. No account reads, writes, interest or promised returns. */
object SavingsPlanner {
    data class Draft(
        val initial:Long?=null, val monthly:Long?=null, val months:Int?=null,
        val extra:Long=0, val withdrawal:Long=0, val monthlyWithdrawal:Long=0,
        val goal:Long?=null, val totalContribution:Long?=null,
        val ambiguousContribution:Long?=null, val startsThisMonth:Boolean=false
    )
    data class Month(val number:Int,val opening:Long,val contribution:Long,val extra:Long,val withdrawal:Long,val closing:Long)
    data class Projection(val plan:Draft,val rows:List<Month>,val added:Long,val withdrawn:Long,val finalBalance:Long,
        val goalGap:Long?,val requiredMonthly:Long?)
    data class Interpretation(val draft:Draft,val clarification:String?=null,val error:String?=null)
    private const val number="([+-]?\\d[\\d.,]*)"
    private const val amount="(?:q\\s*\\.?\\s*)?$number"
    private const val cadence="(?:al\\s+mes|cada\\s+mes|mensuales?|por\\s+mes)"
    private val words=mapOf("un" to 1,"uno" to 1,"dos" to 2,"tres" to 3,"cuatro" to 4,"cinco" to 5,"seis" to 6,"siete" to 7,"ocho" to 8,"nueve" to 9,"diez" to 10,"once" to 11,"doce" to 12)

    fun parseAmount(value:String):Long? = try {
        var clean=value.trim().replace(Regex("(?i)^q\\s*\\.?\\s*"),"").trim().trimEnd('.',',')
        require(!clean.startsWith('-'))
        clean=clean.removePrefix("+")
        val valid=listOf("\\d+(?:\\.\\d{1,2})?","\\d+,\\d{1,2}","\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?","\\d{1,3}(?:\\.\\d{3})+,\\d{1,2}")
        require(valid.any{Regex(it).matches(clean)})
        clean=when {
            clean.contains(',')&&clean.contains('.') -> if(clean.lastIndexOf('.')>clean.lastIndexOf(','))clean.replace(",","") else clean.replace(".","").replace(',','.')
            clean.contains(',') -> if(clean.substringAfterLast(',').length in 1..2 && clean.count{it==','}==1)clean.replace(',','.') else clean.replace(",","")
            else -> clean
        }
        BigDecimal(clean).setScale(2,RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().takeIf{it>=0}
    } catch(_:Exception){null}

    fun calculate(plan:Draft):Projection {
        val initial=requireNotNull(plan.initial){"Falta el ahorro inicial"}
        val months=requireNotNull(plan.months){"Falta el plazo"}
        require(months in 1..120){"El plazo debe estar entre 1 y 120 meses"}
        require(plan.ambiguousContribution==null){"Aclara si el aporte es mensual o total"}
        require(plan.monthly!=null || plan.totalContribution!=null){"Falta el aporte"}
        require(plan.monthly==null || plan.totalContribution==null){"Elige aporte mensual o total"}
        require(listOfNotNull(initial,plan.monthly,plan.extra,plan.withdrawal,plan.monthlyWithdrawal,plan.goal,plan.totalContribution).all{it>=0}){"Los montos no pueden ser negativos"}
        var balance=initial;var added=0L;var withdrawn=0L
        val base=plan.totalContribution?.div(months)?:plan.monthly!!
        val remainder=plan.totalContribution?.rem(months)?:0L
        val rows=(1..months).map { month ->
            val contribution=Math.addExact(base,if(month==months)remainder else 0)
            val extra=if(month==1)plan.extra else 0
            val withdrawal=Math.addExact(plan.monthlyWithdrawal,if(month==1)plan.withdrawal else 0)
            val opening=balance
            balance=Math.subtractExact(Math.addExact(Math.addExact(balance,contribution),extra),withdrawal)
            added=Math.addExact(added,Math.addExact(contribution,extra));withdrawn=Math.addExact(withdrawn,withdrawal)
            Month(month,opening,contribution,extra,withdrawal,balance)
        }
        val gap=plan.goal?.let{Math.subtractExact(it,balance).coerceAtLeast(0)}
        val required=plan.goal?.let { goal ->
            val needed=Math.addExact(Math.subtractExact(Math.subtractExact(goal,initial),plan.extra),withdrawn).coerceAtLeast(0)
            needed/months + if(needed%months>0)1 else 0
        }
        return Projection(plan,rows,added,withdrawn,balance,gap,required)
    }

    /** A null result leaves ordinary historical/report questions to the existing assistant. */
    fun interpret(question:String,previous:Draft?=null):Interpretation? {
        val q=BankParser.fold(question).replace("ahoror","ahorro").trim().trimStart('¿','¡').trim()
        val follow=previous!=null && (Regex("^(?:y\\b|mejor\\b|en realidad\\b|cada mes\\b|al mes\\b|en total\\b|ya tengo\\b|tengo\\b|desde cero\\b|sin retiros\\b|sin extra\\b|sin meta\\b|retiro\\b|retirare\\b|saco\\b|aporte\\b|meta\\b|plazo\\b)").containsMatchIn(q) || Regex("^(?:q\\s*)?[\\d.,]+(?:\\s*meses?)?\\s*[?.]?$").matches(q) || Regex("^(?:un|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)(?:\\s*meses?)?[?.]?$").matches(q) || Regex("^$amount\\s*(?:quetzales\\s*)?$cadence[?.]?$").matches(q))
        val intent=(q.contains("ahorr")||q.contains("apart")||q.contains("proyect")||q.contains("juntar")) &&
            listOf("si ","proximo","durante","al mes","mensual","cada mes","tendre","lleg","proyect","simul","en total").any{q.contains(it)}
        val fresh=q.contains("nuevo escenario")||q.contains("otra simulacion")
        if(!intent && !follow && !fresh)return null
        var draft=if(fresh || (intent && !follow && q.startsWith("si ")))Draft() else previous?:Draft()
        if(Regex("%|interes|rendimiento|inflacion|quincen|semanal|cada semana|anual|ano\\b|\\busd\\b|dolar|euro|[$€]").containsMatchIn(q))
            return Interpretation(draft,error="Este plan usa aportes por mes, sin intereses ni rendimientos. Indica el total que apartarás cada mes y un plazo en meses; no puedo deducir esas condiciones.")
        if(Regex("(?:extra|adicional|retiro unico)\\s*$amount\\s*(?:quetzales\\s*)?$cadence").containsMatchIn(q))
            return Interpretation(draft,error="Los extras y retiros únicos se aplican una sola vez. Para montos recurrentes usa aporte mensual o retiro mensual; no los interpretaré como movimientos únicos.")
        val used=mutableListOf<IntRange>()
        var invalid=false
        fun get(pattern:String,excludeUsed:Boolean=true):Long? {
            val match=Regex(pattern).findAll(q).firstOrNull{m -> !excludeUsed || used.none{r->m.range.first<=r.last && r.first<=m.range.last}}?:return null
            val parsed=parseAmount(match.groupValues[1])
            used+=match.range
            if(parsed==null)invalid=true
            return parsed
        }
        val duration=Regex("(?<![\\w-])(-?\\d+|un|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)\\s*mes(?:es)?\\b").find(q)
        duration?.let { val value=it.groupValues[1];draft=draft.copy(months=value.toIntOrNull()?:words[value]);used+=it.range }
        if(follow && draft.months==null)words[q.trimEnd('?','.') ]?.let{draft=draft.copy(months=it);used+=q.indices}
        if(duration!=null && draft.months==null)return Interpretation(draft,error="El plazo debe estar entre 1 y 120 meses.")
        if(q.contains("desde cero")||q.contains("empiezo en cero")||q.contains("no tengo ahorros"))draft=draft.copy(initial=0)
        if(q.contains("sin retiros")||q.contains("no retiro"))draft=draft.copy(withdrawal=0,monthlyWithdrawal=0)
        if(q.contains("sin extra"))draft=draft.copy(extra=0)
        if(q.contains("sin meta"))draft=draft.copy(goal=null)
        if(q.contains("desde este mes"))draft=draft.copy(startsThisMonth=true)
        if(q.contains("desde el proximo mes"))draft=draft.copy(startsThisMonth=false)
        get("(?:ahorro inicial|saldo inicial|empiezo con|parto de|ya tengo|tengo ahorrad[oa]s?|tengo|llevo ahorrad[oa]s?)\\s*(?:de|es|:)?\\s*$amount")?.let{draft=draft.copy(initial=it)}
        get("(?:retiro mensual)\\s*(?:de|:)?\\s*$amount")?.let{draft=draft.copy(monthlyWithdrawal=it)}
        get("(?:retiro|retirare|saco|sacare|gasto|gastare|uso|usare)\\s*(?:de|:)?\\s*$amount\\s*(?:quetzales\\s*)?$cadence")?.let{draft=draft.copy(monthlyWithdrawal=it)}
        get("(?:retiro unico|retiro|retirare|saco|sacare|gasto|gastare|uso|usare)\\s*(?:de|:)?\\s*$amount")?.let{draft=draft.copy(withdrawal=it)}
        get("(?:aporte extra|extra|adicional|aporto extra)\\s*(?:de|:)?\\s*$amount")?.let{draft=draft.copy(extra=it)}
        get("(?:agrego|sumo|aporto)\\s*$amount\\s*(?:extra|adicional)")?.let{draft=draft.copy(extra=it)}
        get("(?:meta|objetivo|llegar a|llegar hasta)\\s*(?:de|:)?\\s*$amount")?.let{draft=draft.copy(goal=it)}
        get("(?:aporte total|ahorro total a aportar)\\s*(?:de|:)?\\s*$amount")?.let{draft=draft.copy(totalContribution=it,monthly=null,ambiguousContribution=null)}
        get("(?:ahorro|ahorrare|apartare|aparto|guardare)\\s*$amount\\s*(?:quetzales\\s*)?en total")?.let{draft=draft.copy(totalContribution=it,monthly=null,ambiguousContribution=null)}
        get("(?:aporte mensual|ahorro mensual)\\s*(?:de|:)?\\s*$amount")?.let{draft=draft.copy(monthly=it,totalContribution=null,ambiguousContribution=null)}
        get("$amount\\s*(?:quetzales\\s*)?$cadence")?.let{draft=draft.copy(monthly=it,totalContribution=null,ambiguousContribution=null)}
        val unspecified=get("\\b(?:ahorro|ahorrare|apartare|aparto|guardare)\\s*$amount")
        if(unspecified!=null)draft=if(follow && previous?.monthly!=null)draft.copy(monthly=unspecified,totalContribution=null,ambiguousContribution=null) else draft.copy(ambiguousContribution=unspecified,monthly=null,totalContribution=null)
        if(draft.ambiguousContribution!=null) {
            if(q.contains("cada mes")||q.contains("al mes")||q.contains("mensual"))draft=draft.copy(monthly=draft.ambiguousContribution,totalContribution=null,ambiguousContribution=null)
            else if(q.contains("en total"))draft=draft.copy(totalContribution=draft.ambiguousContribution,monthly=null,ambiguousContribution=null)
        }
        var appliedSimple=false
        if(follow && used.isEmpty()) {
            val simple=q.trim().trimEnd('?','.').trim()
            val numeric=parseAmount(simple)
            if(numeric!=null) {
                val updated=when {
                    draft.initial==null -> draft.copy(initial=numeric)
                    draft.monthly==null && draft.totalContribution==null && draft.ambiguousContribution==null -> draft.copy(monthly=numeric)
                    draft.months==null && simple.toIntOrNull()!=null -> draft.copy(months=simple.toInt())
                    else -> draft
                }
                appliedSimple=updated!=draft
                draft=updated
            }
        }
        if(invalid)return Interpretation(draft,error="No pude interpretar uno de los montos. Usa cantidades no negativas con hasta dos decimales, por ejemplo Q500.50.")
        if(draft.months!=null && draft.months !in 1..120)return Interpretation(draft,error="El plazo debe estar entre 1 y 120 meses.")
        val unknown=Regex("\\bq\\s*\\.?\\s*[+-]?\\d[\\d.,]*").findAll(q).any{m->used.none{r->m.range.first<=r.last && r.first<=m.range.last}}
        if(unknown && !appliedSimple)return Interpretation(draft,error="Hay un monto que no pude ubicar. Indica si es ahorro inicial, aporte mensual, aporte extra, retiro único, retiro mensual o meta. También puedes usar Planear mis ahorros.")
        if(follow && used.isEmpty() && !appliedSimple && draft==previous && !Regex("sin retiros|no retiro|sin extra|sin meta|desde").containsMatchIn(q))
            return Interpretation(draft,error="No identifiqué qué quieres cambiar. Prueba «y si son 3 meses», «mejor Q700 al mes» o «retiro único Q200».")
        val clarification=when {
            draft.ambiguousContribution!=null -> "¿Ese aporte es cada mes o es el total de todo el plazo? Responde «cada mes» o «en total»."
            draft.initial==null -> "¿Con cuánto ahorro inicial empezarás? Por ejemplo: «ya tengo Q1,000» o «desde cero». No usaré automáticamente el saldo de tus cuentas."
            draft.monthly==null && draft.totalContribution==null -> "¿Cuánto apartarás cada mes? Por ejemplo: «Q500 al mes». Si es un monto para todo el plazo, escribe «ahorro Q500 en total»."
            draft.months==null -> "¿Durante cuántos meses? Por ejemplo: «2 meses»."
            else -> null
        }
        return if(clarification!=null)Interpretation(draft,clarification=clarification)
        else try {calculate(draft);Interpretation(draft)}catch(_:ArithmeticException){Interpretation(draft,error="Los montos son demasiado grandes para calcular este escenario de forma exacta.")}
    }

    fun question(plan:Draft):String = buildString {
        fun value(cents:Long)=BigDecimal.valueOf(cents,2).toPlainString()
        append("Nuevo escenario. Ahorro inicial Q${value(requireNotNull(plan.initial))}; ")
        append(if(plan.totalContribution!=null)"aporte total Q${value(plan.totalContribution)}" else "aporte mensual Q${value(requireNotNull(plan.monthly))}")
        append("; plazo ${plan.months} meses; aporte extra Q${value(plan.extra)}; retiro único Q${value(plan.withdrawal)}; retiro mensual Q${value(plan.monthlyWithdrawal)}")
        plan.goal?.let{append("; meta Q${value(it)}")}
        append(if(plan.startsThisMonth)"; desde este mes" else "; desde el próximo mes")
    }
    fun encode(draft:Draft?):String = draft?.let{listOf(it.initial,it.monthly,it.months,it.extra,it.withdrawal,it.monthlyWithdrawal,it.goal,it.totalContribution,it.ambiguousContribution,if(it.startsThisMonth)1 else 0).joinToString("|"){v->v?.toString().orEmpty()}}?:""
    fun decode(value:String):Draft? = try {
        val v=value.split('|');if(v.size!=10)null else Draft(v[0].toLongOrNull(),v[1].toLongOrNull(),v[2].toIntOrNull(),v[3].toLong(),v[4].toLong(),v[5].toLong(),v[6].toLongOrNull(),v[7].toLongOrNull(),v[8].toLongOrNull(),v[9]=="1")
    }catch(_:Exception){null}
}
