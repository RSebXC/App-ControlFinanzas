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
import android.widget.Spinner
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
                }
                ctx.getSharedPreferences("display_preferences", 0).edit().putBoolean("hide_amounts", false).commit()
                val activity = startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activityRoot = activity.window.decorView
                waitForIdleSync()
                checkVisible(activity, "Tu panorama")
                screenshot("home")
                click(activity, "Ocultar Q")
                checkVisible(activity, "Gráfica oculta. Pulsa Ver Q para mostrarla.")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().none { Regex("Q[0-9]").containsMatchIn(it.text) })
                screenshot("privacy")
                click(activity, "Ver Q")
                click(activity, "Meses")
                checkVisible(activity, "Tus reportes")
                screenshot("months")
                runOnMainSync {
                    val chart = allViews(activity.window.decorView).filterIsInstance<FinanceTrendView>().first()
                    chart.requestRectangleOnScreen(android.graphics.Rect(0, 0, chart.width, chart.height), true)
                }
                screenshot("trend")
                click(activity, "Movimientos")
                runOnMainSync {
                    allViews(activity.window.decorView).filterIsInstance<EditText>().first().setText("cafe")
                }
                waitForIdleSync()
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("5 resultados") })
                screenshot("search")
                runOnMainSync {
                    allViews(activity.window.decorView).filterIsInstance<EditText>().first().setText("")
                    allViews(activity.window.decorView).filterIsInstance<Spinner>().first().setSelection(1)
                }
                waitForIdleSync()
                checkVisible(activity, "Débito DEMO")
                check(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("1 resultado") })
                click(activity, "Cuentas")
                checkVisible(activity, "Cuenta de prueba")
                screenshot("accounts")
                FinanceDb(ctx).use { db ->
                    check(FinanceDb.digest(db.exportJson()) == prefs.getString("snapshot", null)) { "UI navigation modified financial data" }
                }
                result.putString("stream", "PASS: upgrade preserved complete database; home, privacy, monthly reports, missing month, refunds, accent-insensitive search, pending filter and accounts checked\n")
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
