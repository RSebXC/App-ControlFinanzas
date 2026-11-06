package gt.finanzas.privadas

import java.time.YearMonth
import java.time.ZoneId

/** Extra report queries are read-only and do not impose the transaction-list display limit. */
class FinanceReadModel(private val db: FinanceDb) {
    private fun range(month: String): Array<String> {
        val m=YearMonth.parse(month);val zone=ZoneId.systemDefault()
        return arrayOf(m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli().toString(),m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli().toString())
    }
    fun dailyNet(month: String): List<Long> {
        val days=MutableList(YearMonth.parse(month).lengthOfMonth()){0L}
        db.readableDatabase.rawQuery("""SELECT CAST(strftime('%d',when_ms/1000,'unixepoch','localtime') AS INTEGER),
            SUM(CASE WHEN kind='REFUND' THEN -cents ELSE cents END) FROM tx
            WHERE when_ms>=? AND when_ms<? AND kind IN ('EXPENSE','REFUND') GROUP BY 1""",range(month)).use { c ->
            while(c.moveToNext()) { val index=c.getInt(0)-1;if(index in days.indices)days[index]=c.getLong(1) }
        }
        return days
    }
    fun topMerchants(month: String): List<Pair<String,Long>> {
        val values=mutableListOf<Pair<String,Long>>()
        db.readableDatabase.rawQuery("""SELECT merchant,SUM(CASE WHEN kind='REFUND' THEN -cents ELSE cents END) AS total
            FROM tx WHERE when_ms>=? AND when_ms<? AND kind IN ('EXPENSE','REFUND')
            GROUP BY merchant HAVING total>0 ORDER BY total DESC,merchant LIMIT 5""",range(month)).use { c ->
            while(c.moveToNext()) values+=c.getString(0) to c.getLong(1)
        }
        return values
    }
    fun largestPurchase(month: String): Pair<String,Long>? =
        db.readableDatabase.rawQuery("""SELECT merchant,cents FROM tx WHERE when_ms>=? AND when_ms<? AND kind='EXPENSE'
            ORDER BY cents DESC,when_ms DESC LIMIT 1""",range(month)).use { c ->
            if(c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }
}
