package gt.finanzas.privadas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Base local privada. Los saldos son snapshots actuales más cambios posteriores a la fecha de corte. */
class FinanceDb(ctx: Context) : SQLiteOpenHelper(ctx, "mis_finanzas.db", null, 1) {
    data class Account(val id: Long, val bank: String, val name: String, val last4: String,
        val type: String, val opening: Long?, val asOf: Long)
    data class Tx(val id: Long, val accountId: Long, val accountLast4: String, val kind: String,
        val category: String, val merchant: String, val cents: Long, val whenMs: Long,
        val raw: String, val source: String, val groupId: Long?)
    data class Bill(val id: Long, val title: String, val cents: Long, val day: Int, val category: String, val paidYm: String)
    data class Budget(val category: String, val cents: Long)
    data class ImportResult(var added: Int = 0, var duplicates: Int = 0, var ignored: Int = 0)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, bank TEXT NOT NULL,
            name TEXT NOT NULL, last4 TEXT NOT NULL, type TEXT NOT NULL, opening INTEGER,
            as_of INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL, account_last4 TEXT NOT NULL, kind TEXT NOT NULL,
            category TEXT NOT NULL, merchant TEXT NOT NULL, cents INTEGER NOT NULL,
            when_ms INTEGER NOT NULL, raw TEXT NOT NULL, source TEXT NOT NULL,
            source_key TEXT NOT NULL UNIQUE, group_id INTEGER)""")
        db.execSQL("CREATE INDEX tx_when_idx ON tx(when_ms)")
        db.execSQL("CREATE INDEX tx_account_idx ON tx(account_id)")
        db.execSQL("""CREATE TABLE budgets(category TEXT PRIMARY KEY, cents INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE bills(id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL, cents INTEGER NOT NULL, day INTEGER NOT NULL,
            category TEXT NOT NULL, paid_ym TEXT NOT NULL DEFAULT '')""")
    }
    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) { /* primera versión */ }

    companion object {
        private val lock = Any()
        fun ym(time: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(time))
        fun firstOfMonth(time: Long = System.currentTimeMillis()): Long {
            val c = Calendar.getInstance(); c.timeInMillis = time
            c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
        }
        fun format(cents: Long): String = "Q" + String.format(Locale.US, "%,.2f", cents / 100.0)
        fun parseAmount(s: String): Long? = try {
            java.math.BigDecimal(s.trim().replace("Q", "", true).replace(",", ""))
                .movePointRight(2).longValueExact().takeIf { it >= 0 }
        } catch (_: Exception) { null }
        fun digest(s: String): String = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun accounts(): List<Account> {
        val out = mutableListOf<Account>()
        readableDatabase.rawQuery("SELECT id, bank, name, last4, type, opening, as_of FROM accounts ORDER BY id", null).use { c ->
            while(c.moveToNext()) out += Account(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),
                if(c.isNull(5)) null else c.getLong(5), c.getLong(6))
        }; return out
    }

    fun createAccount(bank: String, name: String, last4: String, type: String, opening: Long?): Long {
        require(name.isNotBlank())
        synchronized(lock) {
            val db = writableDatabase
            return db.insertOrThrow("accounts", null, ContentValues().apply {
                put("bank", bank.trim()); put("name", name.trim()); put("last4", last4.trim())
                put("type", type); if(opening == null) putNull("opening") else put("opening", opening)
                put("as_of", System.currentTimeMillis())
            })
        }
    }

    fun updateBalanceSnapshot(id: Long, cents: Long) = synchronized(lock) {
        writableDatabase.update("accounts", ContentValues().apply {
            put("opening", cents); put("as_of", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    private fun resolveAccount(db: SQLiteDatabase, p: BankParser.Parsed): Long {
        db.rawQuery("SELECT id FROM accounts WHERE bank=? AND last4=? LIMIT 1", arrayOf(p.bank,p.accountLast4)).use {
            if(it.moveToFirst()) return it.getLong(0)
        }
        // Desconocemos saldo inicial; no inventarlo. Se solicitará saldo actual.
        return db.insertOrThrow("accounts", null, ContentValues().apply {
            put("bank", p.bank); put("name", "${p.bank} ${p.accountType} •${p.accountLast4}")
            put("last4", p.accountLast4); put("type", p.accountType)
            putNull("opening"); put("as_of", System.currentTimeMillis())
        })
    }

    private fun canonicalForDuplicateCheck(raw: String): String = raw.trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(?i)^(?:BANRURAL:|BIMOVIL:)\\s*"), "")
        .trim().lowercase(Locale.ROOT)

    fun importLine(raw: String, whenMs: Long, source: String): String = synchronized(lock) {
        val parsed = BankParser.parse(raw) ?: return@synchronized "ignored"
        val db = writableDatabase
        val canonical = canonicalForDuplicateCheck(raw)
        val transactionTime = parsed.embeddedTime ?: whenMs
        val key = digest("${source}|${transactionTime}|${canonical}")
        // No duplicar si notificación y SMS del mismo movimiento llegan casi juntos.
        db.rawQuery("SELECT when_ms, raw FROM tx WHERE when_ms BETWEEN ? AND ?", arrayOf(
            (transactionTime-120_000).toString(), (transactionTime+120_000).toString())).use { c ->
            while(c.moveToNext()) if(canonicalForDuplicateCheck(c.getString(1)) == canonical)
                return@synchronized "duplicate"
        }
        val accountId = resolveAccount(db, parsed)
        val inserted = db.insertWithOnConflict("tx", null, ContentValues().apply {
            put("account_id", accountId); put("account_last4", parsed.accountLast4)
            put("kind", parsed.kind); put("category", parsed.category); put("merchant", parsed.merchant)
            put("cents", parsed.amountCents); put("when_ms", transactionTime)
            put("raw", raw); put("source", source); put("source_key", key)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if(inserted == -1L) return@synchronized "duplicate"
        pairTransferIfUnambiguous(db, inserted)
        "added"
    }

    /** Sólo emparejar movimientos vía APP móvil en cuentas DISTINTAS, misma cuantía y máximo 15 minutos. */
    private fun pairTransferIfUnambiguous(db: SQLiteDatabase, id: Long) {
        val tx = txById(db,id) ?: return
        if(tx.kind !in listOf("PENDING_DEBIT", "PENDING_CREDIT") ||
            !BankParser.fold(tx.merchant).contains("unidad app movil banrural")) return
        val opposite = if(tx.kind == "PENDING_DEBIT") "PENDING_CREDIT" else "PENDING_DEBIT"
        val candidates = mutableListOf<Long>()
        db.rawQuery("""SELECT id, merchant, account_id FROM tx WHERE kind=? AND cents=? AND
            when_ms BETWEEN ? AND ? AND account_id != ? AND group_id IS NULL""",
            arrayOf(opposite,tx.cents.toString(),(tx.whenMs-900_000).toString(),
                (tx.whenMs+900_000).toString(),tx.accountId.toString())).use { c ->
            while(c.moveToNext()) if(BankParser.fold(c.getString(1)).contains("unidad app movil banrural"))
                candidates += c.getLong(0)
        }
        if(candidates.size != 1) return
        val other = candidates.single()
        // Si aparece un candidato alternativo, mantener ambos como pendientes hasta revisión.
        val pendingSame = db.rawQuery("""SELECT COUNT(*) FROM tx WHERE kind=? AND cents=? AND
            when_ms BETWEEN ? AND ? AND account_id != ? AND group_id IS NULL""",
            arrayOf(tx.kind, tx.cents.toString(), (tx.whenMs-900_000).toString(),
                (tx.whenMs+900_000).toString(), other.toString())).use { it.moveToFirst(); it.getInt(0) }
        if(pendingSame != 1) return
        val group = minOf(id,other)
        db.update("tx", ContentValues().apply {
            put("kind", if(tx.kind=="PENDING_DEBIT") "TRANSFER_OUT" else "TRANSFER_IN")
            put("group_id",group)
        }, "id=?", arrayOf(id.toString()))
        db.update("tx", ContentValues().apply {
            put("kind", if(tx.kind=="PENDING_DEBIT") "TRANSFER_IN" else "TRANSFER_OUT")
            put("group_id",group)
        }, "id=?", arrayOf(other.toString()))
    }

    private fun txById(db: SQLiteDatabase,id:Long): Tx? {
        db.rawQuery("SELECT id,account_id,account_last4,kind,category,merchant,cents,when_ms,raw,source,group_id FROM tx WHERE id=?", arrayOf(id.toString())).use { c ->
            if(c.moveToFirst()) return txOf(c)
        }; return null
    }
    private fun txOf(c: android.database.Cursor) = Tx(c.getLong(0),c.getLong(1),c.getString(2),
        c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getLong(7),c.getString(8),c.getString(9),
        if(c.isNull(10)) null else c.getLong(10))

    fun transactions(max:Int=5000): List<Tx> {
        val out = mutableListOf<Tx>()
        readableDatabase.rawQuery("SELECT id,account_id,account_last4,kind,category,merchant,cents,when_ms,raw,source,group_id FROM tx ORDER BY when_ms DESC,id DESC LIMIT $max",null).use { c ->
            while(c.moveToNext())out+=txOf(c)
        }; return out
    }

    fun addManual(accountId:Long, cents:Long, kind:String, category:String, description:String, whenMs:Long=System.currentTimeMillis()) {
        synchronized(lock) {
            val db=writableDatabase
            val acct=accounts().firstOrNull{it.id==accountId} ?: return@synchronized
            db.insertOrThrow("tx",null,ContentValues().apply {
                put("account_id",accountId);put("account_last4",acct.last4)
                put("kind",kind);put("category",category);put("merchant",description)
                put("cents",cents);put("when_ms",whenMs);put("raw","")
                put("source","manual");put("source_key",digest("manual|${System.nanoTime()}|$description"))
            })
        }
    }
    fun classify(id:Long,kind:String,category:String) = synchronized(lock) {
        val database=writableDatabase
        database.beginTransaction()
        try {
            val current=txById(database,id)
            val breakPair=current?.groupId!=null && kind !in listOf("TRANSFER_IN","TRANSFER_OUT")
            if(breakPair){
                database.execSQL("""UPDATE tx SET kind=CASE WHEN kind='TRANSFER_IN' THEN
                    'PENDING_CREDIT' ELSE 'PENDING_DEBIT' END, group_id=NULL WHERE group_id=? AND id!=?""",
                    arrayOf(current!!.groupId,id))
            }
            database.update("tx",ContentValues().apply {
                put("kind",kind); put("category",category)
                if(kind !in listOf("TRANSFER_IN","TRANSFER_OUT"))putNull("group_id")
            },"id=?",arrayOf(id.toString()))
            database.setTransactionSuccessful()
        }finally{database.endTransaction()}
    }
    fun deleteTx(id:Long) = synchronized(lock) {
        // Si borra un miembro de un traslado, el otro vuelve a estar pendiente.
        val db=writableDatabase
        val tx=txById(db,id) ?: return@synchronized
        if(tx.groupId!=null)db.execSQL("""UPDATE tx SET kind=CASE WHEN kind='TRANSFER_IN' THEN
            'PENDING_CREDIT' ELSE 'PENDING_DEBIT' END, group_id=NULL WHERE group_id=? AND id!=?""",arrayOf(tx.groupId,id))
        db.delete("tx","id=?",arrayOf(id.toString()))
    }
    fun accountBalance(a:Account):Long? {
        val base=a.opening ?:return null
        var net=0L
        readableDatabase.rawQuery("SELECT kind,cents FROM tx WHERE account_id=? AND when_ms>?",arrayOf(a.id.toString(),a.asOf.toString())).use { c ->
            while(c.moveToNext()){
                val positive=c.getString(0) in listOf("INCOME","PENDING_CREDIT","REFUND","TRANSFER_IN")
                net += if(positive)c.getLong(1) else -c.getLong(1)
            }
        }
        return base+net
    }
    fun monthTotals(time:Long=System.currentTimeMillis()):Triple<Long,Long,Long> {
        val a=firstOfMonth(time); val c=Calendar.getInstance(); c.timeInMillis=a;c.add(Calendar.MONTH,1)
        var spent=0L;var income=0L;var pending=0L
        readableDatabase.rawQuery("SELECT kind,cents FROM tx WHERE when_ms>=? AND when_ms<?",arrayOf(a.toString(),c.timeInMillis.toString())).use { cur ->
            while(cur.moveToNext())when(cur.getString(0)) {
                "EXPENSE" -> spent+=cur.getLong(1)
                "REFUND" -> spent-=cur.getLong(1)
                "INCOME" -> income+=cur.getLong(1)
                "PENDING_DEBIT","PENDING_CREDIT" -> pending++
            }
        };return Triple(spent,income,pending)
    }
    fun categorySpent(category:String,time:Long=System.currentTimeMillis()):Long {
        val a=firstOfMonth(time);val c=Calendar.getInstance();c.timeInMillis=a;c.add(Calendar.MONTH,1)
        var sum=0L
        readableDatabase.rawQuery("SELECT kind,cents FROM tx WHERE category=? AND when_ms>=? AND when_ms<? AND kind IN ('EXPENSE','REFUND')",
            arrayOf(category,a.toString(),c.timeInMillis.toString())).use { cur ->
            while(cur.moveToNext())sum+=if(cur.getString(0)=="REFUND")-cur.getLong(1) else cur.getLong(1)
        };return sum
    }
    fun breakdown(time:Long=System.currentTimeMillis()):List<Pair<String,Long>> {
        val a=firstOfMonth(time);val c=Calendar.getInstance();c.timeInMillis=a;c.add(Calendar.MONTH,1)
        val out=mutableListOf<Pair<String,Long>>()
        readableDatabase.rawQuery("""SELECT category, SUM(CASE WHEN kind='REFUND' THEN -cents ELSE cents END)
            FROM tx WHERE kind IN ('EXPENSE','REFUND') AND when_ms>=? AND when_ms<? GROUP BY category ORDER BY 2 DESC""",
            arrayOf(a.toString(),c.timeInMillis.toString())).use {cur->while(cur.moveToNext())out+=cur.getString(0) to cur.getLong(1)}
        return out
    }

    /** Resumen del historial importado, sin limitarse a los 400 movimientos mostrados en pantalla. */
    data class Coverage(val total: Long, val sms: Long, val oldest: Long?, val newest: Long?,
        val pending: Long, val months: List<Pair<String, Long>>)

    fun historyCoverage(): Coverage {
        val database = readableDatabase
        var total = 0L; var sms = 0L; var pending = 0L
        var oldest: Long? = null; var newest: Long? = null
        database.rawQuery("""SELECT COUNT(*),
            COALESCE(SUM(CASE WHEN source='sms' THEN 1 ELSE 0 END),0),
            MIN(when_ms), MAX(when_ms),
            COALESCE(SUM(CASE WHEN kind IN ('PENDING_DEBIT','PENDING_CREDIT') THEN 1 ELSE 0 END),0)
            FROM tx""",null).use { c -> if(c.moveToFirst()) {
                total=c.getLong(0);sms=c.getLong(1)
                if(!c.isNull(2)) oldest=c.getLong(2)
                if(!c.isNull(3)) newest=c.getLong(3)
                pending=c.getLong(4)
            }}
        val months=mutableListOf<Pair<String,Long>>()
        database.rawQuery("""SELECT strftime('%Y-%m', when_ms / 1000, 'unixepoch', 'localtime'), COUNT(*)
            FROM tx GROUP BY 1 ORDER BY 1 DESC""",null).use { c ->
            while(c.moveToNext()) months += (c.getString(0)?:"Sin fecha") to c.getLong(1)
        }
        return Coverage(total,sms,oldest,newest,pending,months)
    }

    data class Reconciliation(
        val count: Long, val credits: Long, val debits: Long,
        val pendingCount: Long, val pendingCredits: Long, val pendingDebits: Long,
        val first: Long?, val last: Long?, val predicted: Long, val actual: Long
    ) {
        val difference: Long get() = actual-predicted
    }

    /** Requiere saldo inicial INDEPENDIENTE del banco, en el instante de inicio del intervalo.
     * No se usa el snapshot actual porque eso produciría una coincidencia circular.
     * Incluye pendientes en la variación de saldo, pero los informa por separado.
     */
    fun reconcile(accountId: Long, fromExclusive: Long, toInclusive: Long,
        bankOpening: Long, bankClosing: Long): Reconciliation {
        require(fromExclusive<toInclusive)
        val database=readableDatabase
        var count=0L;var credits=0L;var debits=0L
        var pendingCount=0L;var pendingCredits=0L;var pendingDebits=0L
        var first:Long?=null;var last:Long?=null
        database.rawQuery("""SELECT kind,cents,when_ms FROM tx
            WHERE account_id=? AND when_ms>? AND when_ms<=?
            ORDER BY when_ms""",arrayOf(accountId.toString(),fromExclusive.toString(),toInclusive.toString())).use { c ->
            while(c.moveToNext()) {
                val kind=c.getString(0);val amount=c.getLong(1);val date=c.getLong(2)
                count++
                if(first==null) first=date
                last=date
                if(kind in listOf("INCOME","PENDING_CREDIT","REFUND","TRANSFER_IN")) credits+=amount
                else debits+=amount
                if(kind=="PENDING_CREDIT") {pendingCount++;pendingCredits+=amount}
                if(kind=="PENDING_DEBIT") {pendingCount++;pendingDebits+=amount}
            }
        }
        return Reconciliation(count,credits,debits,pendingCount,pendingCredits,pendingDebits,
            first,last,bankOpening+credits-debits,bankClosing)
    }


    /** Reportes por mes: solo gastos confirmados y devoluciones afectan consumo.
      * Abonos sin verificar y transferencias se informan por separado, nunca como ingreso confirmado.
      */
    data class MonthReport(val ym:String, val expenses:Long, val income:Long, val refunds:Long,
        val pendingDebits:Long, val pendingCredits:Long, val transferOut:Long, val transferIn:Long,
        val pendingCount:Long, val count:Long)

    fun monthsAvailable():List<String> {
        val result=mutableListOf<String>()
        readableDatabase.rawQuery("SELECT strftime('%Y-%m', when_ms / 1000, 'unixepoch', 'localtime') FROM tx GROUP BY 1 ORDER BY 1 DESC",null).use{c ->
            while(c.moveToNext()) c.getString(0)?.let {result+=it}
        }
        if(ym() !in result) result.add(0,ym())
        return result.distinct().sortedDescending()
    }

    private fun monthRange(month:String):Pair<Long,Long> {
        require(Regex("\\d{4}-\\d{2}").matches(month))
        val first=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse("$month-01")!!
        val c=Calendar.getInstance().apply{time=first;add(Calendar.MONTH,1)}
        return first.time to c.timeInMillis
    }

    fun monthReport(month:String):MonthReport {
        val (a,b)=monthRange(month)
        var expenses=0L;var income=0L;var refunds=0L;var pd=0L;var pc=0L;var to=0L;var ti=0L;var n=0L;var pending=0L
        readableDatabase.rawQuery("SELECT kind,cents FROM tx WHERE when_ms>=? AND when_ms<?",arrayOf(a.toString(),b.toString())).use{c->
            while(c.moveToNext()) {
                val cents=c.getLong(1);n++
                when(c.getString(0)) {
                    "EXPENSE"->expenses+=cents
                    "REFUND"->refunds+=cents
                    "INCOME"->income+=cents
                    "PENDING_DEBIT"->{pd+=cents;pending++}
                    "PENDING_CREDIT"->{pc+=cents;pending++}
                    "TRANSFER_OUT"->to+=cents
                    "TRANSFER_IN"->ti+=cents
                }
            }
        }
        return MonthReport(month,expenses-refunds,income,refunds,pd,pc,to,ti,pending,n)
    }

    fun monthCategories(month:String):List<Pair<String,Long>> {
        val (a,b)=monthRange(month)
        val result=mutableListOf<Pair<String,Long>>()
        readableDatabase.rawQuery("""SELECT category, SUM(CASE WHEN kind='REFUND' THEN -cents ELSE cents END) AS total
            FROM tx WHERE when_ms>=? AND when_ms<? AND kind IN ('EXPENSE','REFUND') GROUP BY category ORDER BY total DESC""",
            arrayOf(a.toString(),b.toString())).use{c->while(c.moveToNext())result+=c.getString(0) to c.getLong(1)}
        return result
    }

    fun monthTransactions(month:String,category:String?=null,limit:Int=2000):List<Tx>{
        val (a,b)=monthRange(month)
        val results=mutableListOf<Tx>()
        val where=if(category==null)"" else " AND category=? AND kind IN ('EXPENSE','REFUND')"
        val args=if(category==null)arrayOf(a.toString(),b.toString()) else arrayOf(a.toString(),b.toString(),category)
        readableDatabase.rawQuery("SELECT id,account_id,account_last4,kind,category,merchant,cents,when_ms,raw,source,group_id FROM tx WHERE when_ms>=? AND when_ms<?$where ORDER BY when_ms DESC LIMIT ${limit.coerceIn(1,5000)}",args).use{c->
            while(c.moveToNext())results+=txOf(c)
        }
        return results
    }

    /** CSV separado por ; para Excel en español; no incluye el contenido SMS original. */
    fun exportMonthCsv(month:String):String {
        fun field(value:String)= "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
        val d=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault())
        val header="Fecha;Banco;Cuenta;Tipo;Categoria;Comercio;Monto_GTQ;Origen"
        val ac=accounts().associateBy{it.id}
        val rows=monthTransactions(month,limit=5000).map{t->
            listOf(d.format(Date(t.whenMs)),ac[t.accountId]?.bank?:"",t.accountLast4,t.kind,
                t.category,t.merchant,String.format(Locale.US,"%.2f",t.cents/100.0),t.source).joinToString(";"){field(it)}
        }
        return (listOf(header)+rows).joinToString("\r\n")+"\r\n"
    }

    /** Corrección explícita y conservadora de reglas previas.
      * No toca categorías que el usuario ya definió. La cuenta y el monto nunca cambian.
      */
    fun refreshUncategorizedFromParser():Int = synchronized(lock){
        val database=writableDatabase
        val updates=mutableListOf<Triple<Long,String,String>>()
        database.rawQuery("""SELECT id, raw, kind FROM tx
            WHERE category='Por revisar' AND source IN ('sms','paste','notification') AND raw!=''""",null).use {c->
            while(c.moveToNext()){
                val p=BankParser.parse(c.getString(1))?:continue
                val previousKind=c.getString(2)
                val proposedKind=if(previousKind=="EXPENSE" && p.kind=="PENDING_DEBIT")"PENDING_DEBIT" else previousKind
                val proposedCategory=if(p.category!="Por revisar" && previousKind=="EXPENSE")p.category else "Por revisar"
                if(proposedKind!=previousKind || proposedCategory!="Por revisar")
                    updates+=Triple(c.getLong(0),proposedKind,proposedCategory)
            }
        }
        database.beginTransaction()
        try{
            for((id,kind,category) in updates)
                database.update("tx",ContentValues().apply{put("kind",kind);put("category",category)},"id=?",arrayOf(id.toString()))
            database.setTransactionSuccessful()
        } finally {database.endTransaction()}
        updates.size
    }

    fun budgets():List<Budget> {
        val out=mutableListOf<Budget>()
        readableDatabase.rawQuery("SELECT category,cents FROM budgets ORDER BY category",null).use {c->while(c.moveToNext())out+=Budget(c.getString(0),c.getLong(1))}
        return out
    }
    fun setBudget(category:String,cents:Long) = synchronized(lock) {
        writableDatabase.insertWithOnConflict("budgets",null,ContentValues().apply { put("category",category);put("cents",cents) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun bills():List<Bill>{
        val out=mutableListOf<Bill>()
        readableDatabase.rawQuery("SELECT id,title,cents,day,category,paid_ym FROM bills ORDER BY day",null).use {c->while(c.moveToNext())out+=Bill(c.getLong(0),c.getString(1),c.getLong(2),c.getInt(3),c.getString(4),c.getString(5))}
        return out
    }
    fun addBill(title:String,cents:Long,day:Int,cat:String) = synchronized(lock){
        writableDatabase.insertOrThrow("bills",null,ContentValues().apply {put("title",title);put("cents",cents);put("day",day);put("category",cat);put("paid_ym","")})
    }
    fun togglePaid(id:Long,paid:Boolean)=synchronized(lock){
        writableDatabase.update("bills",ContentValues().apply{put("paid_ym",if(paid)ym() else "")},"id=?",arrayOf(id.toString()))
    }
    fun removeBill(id:Long)=synchronized(lock){writableDatabase.delete("bills","id=?",arrayOf(id.toString()))}

    /** Registra dos asientos balanceados, no un gasto. */
    fun manualTransfer(from:Long,to:Long,cents:Long,description:String) = synchronized(lock) {
        require(from!=to && cents>0)
        val accounts=accounts()
        val a=accounts.first{it.id==from};val b=accounts.first{it.id==to}
        val database=writableDatabase
        database.beginTransaction()
        try {
            val whenMs=System.currentTimeMillis()
            val common=ContentValues().apply {
                put("category","Transferencia");put("merchant",description);put("cents",cents)
                put("when_ms",whenMs);put("raw","");put("source","manual")
            }
            val out=ContentValues(common).apply {
                put("account_id",from);put("account_last4",a.last4);put("kind","TRANSFER_OUT")
                put("source_key",digest("manual-out|${System.nanoTime()}"))
            }
            val id=database.insertOrThrow("tx",null,out)
            val inc=ContentValues(common).apply {
                put("account_id",to);put("account_last4",b.last4);put("kind","TRANSFER_IN")
                put("source_key",digest("manual-in|${System.nanoTime()}"))
                put("group_id",id)
            }
            database.insertOrThrow("tx",null,inc)
            database.update("tx",ContentValues().apply{put("group_id",id)},"id=?",arrayOf(id.toString()))
            database.setTransactionSuccessful()
        } finally {database.endTransaction()}
    }

    /** Exportación local explícita, sin red. El JSON contiene datos financieros sensibles. */
    fun exportJson():String = synchronized(lock){
        val root=org.json.JSONObject().put("format","mis-finanzas-private-v1")
        val fields=mapOf(
            "accounts" to listOf("id","bank","name","last4","type","opening","as_of"),
            "tx" to listOf("id","account_id","account_last4","kind","category","merchant","cents","when_ms","raw","source","source_key","group_id"),
            "budgets" to listOf("category","cents"),
            "bills" to listOf("id","title","cents","day","category","paid_ym")
        )
        fields.forEach{(table,columns)->
            val arr=org.json.JSONArray()
            readableDatabase.rawQuery("SELECT ${columns.joinToString(",")} FROM $table",null).use{c ->
                while(c.moveToNext()){
                    val row=org.json.JSONObject()
                    columns.forEachIndexed{i,col ->when(c.getType(i)){
                        android.database.Cursor.FIELD_TYPE_NULL -> row.put(col,org.json.JSONObject.NULL)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(col,c.getLong(i))
                        else -> row.put(col,c.getString(i))
                    }}
                    arr.put(row)
                }
            }
            root.put(table,arr)
        }
        root.toString(2)
    }

    fun restoreJson(content:String) = synchronized(lock) {
        val json=org.json.JSONObject(content)
        require(json.optString("format")=="mis-finanzas-private-v1") { "Archivo incompatible" }
        val fields=linkedMapOf(
            "accounts" to listOf("id","bank","name","last4","type","opening","as_of"),
            "tx" to listOf("id","account_id","account_last4","kind","category","merchant","cents","when_ms","raw","source","source_key","group_id"),
            "budgets" to listOf("category","cents"),
            "bills" to listOf("id","title","cents","day","category","paid_ym")
        )
        fields.keys.forEach{json.getJSONArray(it)} // Comprobar estructura antes de borrar datos.
        val database=writableDatabase
        database.beginTransaction()
        try {
            listOf("tx","accounts","budgets","bills").forEach{database.delete(it,null,null)}
            fields.forEach{(table,columns)->
                val arr=json.getJSONArray(table)
                for(i in 0 until arr.length()){
                    val row=arr.getJSONObject(i)
                    val values=ContentValues()
                    columns.forEach{col ->
                        if(!row.has(col) || row.isNull(col))values.putNull(col)
                        else if(col in listOf("id","opening","as_of","account_id","cents","when_ms","group_id","day"))values.put(col,row.getLong(col))
                        else values.put(col,row.getString(col))
                    }
                    database.insertOrThrow(table,null,values)
                }
            }
            database.setTransactionSuccessful()
        }finally{database.endTransaction()}
    }
}
