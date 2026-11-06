package gt.finanzas.privadas

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Emulator-only upgrade probe: never touches a physical phone or existing personal data. */
class UpgradeSmokeTest : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var activityRoot: View
    override fun onCreate(arguments: Bundle?) {
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "This test only runs on an emulator" }
            check(args.getString("allowSyntheticData") == "emulator-only") { "Explicit emulator test argument required" }
            val ctx = targetContext
            val prefs = ctx.getSharedPreferences("upgrade_test_probe", 0)
            if (args.getString("mode") == "prepare") {
                FinanceDb(ctx).use { db ->
                    check(db.accounts().isEmpty() && db.transactions().isEmpty()) { "Use an empty test emulator" }
                    val account = db.createAccount("Banco DEMO", "Cuenta de prueba", "1111", "Monetaria", 250000)
                    val month = YearMonth.now()
                    for (offset in listOf(0L, 1L, 3L, 4L, 5L)) {
                        val date = month.minusMonths(offset).atDay(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        db.addManual(account, 180000 + offset * 10000, "INCOME", "Otros", "Ingreso DEMO", date)
                        db.addManual(account, 15000 + offset * 1000, "EXPENSE", "Comida", "Café DEMO", date)
                        db.addManual(account, 26000, "EXPENSE", "Servicios", "Servicio DEMO", date)
                        db.addManual(account, 8000, "EXPENSE", "Gasolina", "Estación DEMO", date)
                        if (offset == 4L) db.addManual(account, 120000, "REFUND", "Compras", "Devolución DEMO", date)
                    }
                    db.addManual(account, 4000, "PENDING_DEBIT", "Por revisar", "Débito DEMO")
                    db.setBudget("Comida", 80000)
                    db.addBill("Pago DEMO", 20000, 15, "Servicios")
                    prefs.edit().putString("snapshot", FinanceDb.digest(db.exportJson()))
                        .putInt("count", db.transactions().size).commit()
                }
                result.putString("stream", "PASS: synthetic accounts, transactions, budget and bill prepared on version ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}\n")
            } else {
                FinanceDb(ctx).use { db ->
                    check(FinanceDb.digest(db.exportJson()) == prefs.getString("snapshot", null)) { "Stored financial data changed across the update" }
                    check(db.transactions().size == prefs.getInt("count", -1))
                    check(db.readableDatabase.version == 1)
                    check(db.monthReport(YearMonth.now().minusMonths(4).toString()).expenses < 0)
                    check(db.monthReport(YearMonth.now().minusMonths(2).toString()).count == 0L)
                    val read=FinanceReadModel(db)
                    ReportMath.monthsEndingAt(YearMonth.now().toString()).forEach { month ->
                        check(read.dailyNet(month).sum()==db.monthReport(month).expenses) { "Daily aggregation differs from monthly report" }
                    }
                    check(read.largestPurchase(YearMonth.now().toString())?.second==26000L)
                    check(read.topMerchants(YearMonth.now().toString()).first().first=="Servicio DEMO")
                }
                ctx.getSharedPreferences("display_preferences", 0).edit().putBoolean("hide_amounts", false).commit()
                val activity = startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activityRoot = activity.window.decorView
                waitForIdleSync()
                checkVisible(activity, "Tu dinero, con claridad.")
                settleFrames()
                val incomeMetric=allViews(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="Q1,800.00" }
                check(incomeMetric.getGlobalVisibleRect(android.graphics.Rect())) { "Income metric is collapsed or not visible" }
                screenshot("home")
                clickDescription(activity, "Ocultar montos")
                checkVisible(activity, "Gráfica oculta. Pulsa el ojo para mostrarla.")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().none { Regex("Q[0-9]").containsMatchIn(it.text) })
                screenshot("privacy")
                clickDescription(activity, "Mostrar montos")
                click(activity, "Reportes")
                checkVisible(activity, "Tus números, en perspectiva.")
                screenshot("months")
                runOnMainSync {
                    val chart = allViews(activity.window.decorView).filterIsInstance<FinanceTrendView>().first()
                    scrollToView(activity,chart)
                }
                screenshot("trend")
                click(activity, "Barras")
                click(activity, "12 meses")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="12 meses" }.isSelected)
                click(activity, "6 meses")
                runOnMainSync {
                    val donut=allViews(activity.window.decorView).filterIsInstance<CategoryDonutView>().first()
                    scrollToView(activity,donut)
                }
                screenshot("categories")
                click(activity, "Movimientos")
                runOnMainSync {
                    allViews(activity.window.decorView).filterIsInstance<EditText>().first().setText("cafe")
                }
                waitForIdleSync()
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("5 resultados") })
                screenshot("search")
                runOnMainSync {
                    allViews(activity.window.decorView).filterIsInstance<EditText>().first().setText("")
                }
                click(activity,"Por revisar")
                waitForIdleSync()
                checkVisible(activity, "Débito DEMO")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("1 resultado") })
                click(activity, "Cuentas")
                checkVisible(activity, "Cuenta de prueba")
                screenshot("accounts")
                click(activity,"Asistente")
                checkVisible(activity,"Pregúntale a tus números.")
                screenshot("assistant")
                click(activity,"Resumen del mes")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Ingresos confirmados: Q1,800.00") })
                screenshot("conversation")
                runOnMainSync {
                    allViews(activity.window.decorView).filterIsInstance<EditText>().first().setText("Mayor compra")
                }
                clickDescription(activity,"Consultar mis finanzas")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Mayor compra confirmada") && it.text.toString().contains("Servicio DEMO") })
                clickDescription(activity,"Ocultar montos")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().none { Regex("Q[0-9]").containsMatchIn(it.text) })
                clickDescription(activity,"Mostrar montos")
                click(activity,"Mis presupuestos")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Límites actuales aplicados") })
                fun sendQuestion(value:String) {
                    runOnMainSync {allViews(activity.window.decorView).filterIsInstance<EditText>().first().setText(value)}
                    clickDescription(activity,"Consultar mis finanzas")
                }
                sendQuestion("Si ahorro Q500 al mes durante 2 meses y ya tengo Q1000")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("tendrías Q2,000.00") })
                sendQuestion("¿Y si son 3 meses?")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("tendrías Q2,500.00") })
                sendQuestion("Y si retiro Q200")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("tendrías Q2,300.00") })
                screenshot("savings")
                clickDescription(activity,"Ocultar montos")
                check(allViews(activity.window.decorView).none {it is SavingsProjectionView})
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().filter {it.text.toString().startsWith("Al terminar")}.none {Regex("Q[0-9]").containsMatchIn(it.text)})
                clickDescription(activity,"Mostrar montos")
                click(activity,"Limpiar conversación")
                sendQuestion("Si ahorro Q500 al mes durante 2 meses")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any {it.text.toString().contains("¿Con cuánto ahorro inicial")})
                sendQuestion("ya tengo Q1000")
                for(months in 3..11)sendQuestion("Y si son $months meses")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any {it.text.toString().contains("11 meses, tendrías Q6,500.00")})
                FinanceDb(ctx).use { db ->
                    check(FinanceDb.digest(db.exportJson()) == prefs.getString("snapshot", null)) { "UI navigation modified financial data" }
                }
                result.putString("stream", "PASS: complete database preserved; reports, search, assistant, savings scenarios, missing inputs, withdrawals, privacy and follow-up context beyond eight messages checked\n")
                runOnMainSync { activity.finish() }
            }
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun allViews(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()

    private fun checkVisible(activity: Activity, label: String) {
        check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == label }) { "Missing UI label: $label" }
    }

    private fun click(activity: Activity, label: String) {
        runOnMainSync {
            var target: View = allViews(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == label }
            while (!target.hasOnClickListeners()) target = target.parent as View
            check(target.performClick())
        }
        waitForIdleSync()
        settleFrames()
    }

    private fun clickDescription(activity:Activity,label:String) {
        runOnMainSync {
            check(allViews(activity.window.decorView).first { it.contentDescription?.toString()==label }.performClick())
        }
        waitForIdleSync()
        settleFrames()
    }

    private fun settleFrames() {
        val rendered=CountDownLatch(1)
        runOnMainSync { activityRoot.postOnAnimation { activityRoot.postOnAnimation { rendered.countDown() } } }
        check(rendered.await(3,TimeUnit.SECONDS)) { "Rendering timed out" }
    }

    private fun scrollToView(activity:Activity,target:View) {
        val scroll=allViews(activity.window.decorView).filterIsInstance<ScrollView>().first()
        val content=scroll.getChildAt(0)
        var node=target
        var top=0
        while(node!==content){top+=node.top;node=node.parent as View}
        scroll.scrollTo(0,(top-72*activity.resources.displayMetrics.density).toInt().coerceAtLeast(0))
    }

    private fun screenshot(name: String) {
        // UI idle can precede rendering. Wait for two frames before capturing pixels.
        val rendered = CountDownLatch(1)
        runOnMainSync {
            activityRoot.postOnAnimation { activityRoot.postOnAnimation { rendered.countDown() } }
        }
        check(rendered.await(3, TimeUnit.SECONDS)) { "Rendering timed out" }
        uiAutomation.waitForIdle(100, 2000)
        val bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val file = File(targetContext.getExternalFilesDir(null), "qa/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
