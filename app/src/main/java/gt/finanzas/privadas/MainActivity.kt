package gt.finanzas.privadas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var db: FinanceDb
    private lateinit var root: LinearLayout
    private lateinit var pageBody: LinearLayout
    private var page = "Inicio"
    private val uiPrefs by lazy { getSharedPreferences("display_preferences", Context.MODE_PRIVATE) }
    private fun amountsHidden() = uiPrefs.getBoolean("hide_amounts", false)
    private fun money(cents: Long) = if (amountsHidden()) "Q ••••" else FinanceDb.format(cents)
    private var movementQuery = ""
    private var movementFilter = "Todos"
    private var movementLimit = 80
    private var backCallback: OnBackInvokedCallback? = null
    private var selectedMonth = FinanceDb.ym()
    private var monthForExport = FinanceDb.ym()
    private val syncRunning = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val syncPrefs by lazy { getSharedPreferences("sms_sync_prefs", Context.MODE_PRIVATE) }
    private var updatesReceiverRegistered = false
    private var smsObserverRegistered = false
    private val refreshRunnable = Runnable {
        if (!isFinishing && !isDestroyed) render()
    }
    private val recentScanRunnable = Runnable { if (autoSmsEnabled()) importSmsHistory(silent = true, recentOnly = true) }
    private val movementReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BankNotificationListener.ACTION_MOVEMENTS_CHANGED) scheduleUiRefresh()
        }
    }
    private val smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (autoSmsEnabled()) scheduleRecentScan()
        }
    }
    private fun autoSmsEnabled() = syncPrefs.getBoolean("auto_sms_enabled", false)
    private fun scheduleUiRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 350L)
    }
    private fun scheduleRecentScan() {
        mainHandler.removeCallbacks(recentScanRunnable)
        mainHandler.postDelayed(recentScanRunnable, 700L)
    }
    private fun startSmsObserverIfAllowed() {
        if (smsObserverRegistered || !autoSmsEnabled() ||
            checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        try {
            contentResolver.registerContentObserver(Telephony.Sms.Inbox.CONTENT_URI, true, smsObserver)
            smsObserverRegistered = true
        } catch (_: Exception) { /* El acceso puede estar restringido por Android */ }
    }
    private fun stopSmsObserver() {
        if (smsObserverRegistered) {
            contentResolver.unregisterContentObserver(smsObserver)
            smsObserverRegistered = false
        }
        mainHandler.removeCallbacks(recentScanRunnable)
    }
    private val bg = Color.rgb(246,249,248)
    private val ink = Color.rgb(23,49,43)
    private val green = Color.rgb(0,119,82)
    private val darkGreen = Color.rgb(11,77,62)
    private val softGreen = Color.rgb(231,246,238)
    private val muted = Color.rgb(100,121,114)
    private val red = Color.rgb(193,69,76)
    private val categories = listOf("Gasolina","Comida","Servicios","Suscripciones","Compras","Entretenimiento","Hogar","Transporte","Salud","Educación","Otros","Por revisar")
    private val kinds = listOf("EXPENSE","INCOME","REFUND","TRANSFER_OUT","TRANSFER_IN","PENDING_DEBIT","PENDING_CREDIT")
    private val kindLabels = listOf("Gasto","Ingreso confirmado","Devolución","Traslado enviado","Traslado recibido","Débito por revisar","Abono por revisar")

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        db=FinanceDb(this)
        window.statusBarColor = bg
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        ReminderSchedule.schedule(this)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),600)
        // Android 13+ usa el gesto Atrás del sistema; también mantenemos soporte en Android 8–12.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { navigateBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!
            )
        }
        savedInstanceState?.let {
            page = it.getString("page", "Inicio")
            selectedMonth = it.getString("month", FinanceDb.ym())
            movementQuery = it.getString("query", "")
            movementFilter = it.getString("filter", "Todos")
        }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("month", selectedMonth)
        outState.putString("query", movementQuery)
        outState.putString("filter", movementFilter)
        super.onSaveInstanceState(outState)
    }
    // The legacy overload is only reached below Android 13; Android 13+ uses the private flag.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        if (!updatesReceiverRegistered) {
            val filter = IntentFilter(BankNotificationListener.ACTION_MOVEMENTS_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(movementReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(movementReceiver, filter)
            updatesReceiverRegistered = true
        }
    }
    override fun onResume() {
        super.onResume()
        // Si el listener recibió SMS mientras la pantalla estaba cerrada, se reflejan al regresar.
        if (::db.isInitialized) render()
        startSmsObserverIfAllowed()
        if (autoSmsEnabled() && checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
            scheduleRecentScan()
    }
    override fun onPause() {
        stopSmsObserver()
        super.onPause()
    }
    override fun onStop() {
        if (updatesReceiverRegistered) {
            unregisterReceiver(movementReceiver)
            updatesReceiverRegistered = false
        }
        mainHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }
    override fun onDestroy(){
        mainHandler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
        db.close()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { navigateBack() }

    private fun navigateBack() {
        when (page) {
            "Pagos", "Presupuestos", "Asistente" -> { page = "Más"; render() }
            "Inicio" -> AlertDialog.Builder(this)
                .setTitle("¿Cerrar Mis Finanzas?")
                .setMessage("Tus movimientos y saldos se guardan en el teléfono.")
                .setNegativeButton("Seguir aquí", null)
                .setPositiveButton("Cerrar") { _, _ -> finish() }
                .show()
            else -> { page = "Inicio"; render() }
        }
    }
    private fun dp(n:Int):Int=(n*resources.displayMetrics.density+0.5f).toInt()
    private fun drawable(color:Int,radius:Int=16):GradientDrawable=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius).toFloat()
    }
    private fun text(s:String,size:Float=15f,bold:Boolean=false,color:Int=ink):TextView=TextView(this).apply {
        text=s;textSize=size;setTextColor(color)
        typeface=Typeface.create(if(bold)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(),1.10f)
    }
    private fun gap(h:Int=10)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))}
    private fun button(s:String,onClick:()->Unit):TextView=text(s,14f,true,Color.WHITE).apply {
        isFocusable=true;minHeight=dp(48);gravity=Gravity.CENTER;background=drawable(green,16);setPadding(dp(14),dp(16),dp(14),dp(16));setOnClickListener{onClick()}
    }
    private fun miniButton(s:String,click:()->Unit):TextView=text(s,12f,true,green).apply {
        isFocusable=true;minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL;background=drawable(softGreen,13);setPadding(dp(14),dp(14),dp(14),dp(14));
        setOnClickListener{click()}
    }
    private fun actionTile(symbol:String, title:String, subtitle:String, click:()->Unit):LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), Color.rgb(211,232,222))
            }
            setPadding(dp(14),dp(13),dp(14),dp(13))
            elevation = dp(1).toFloat()
            val circle = text(symbol,23f,true,green).apply {
                gravity = Gravity.CENTER
                background = drawable(softGreen,15)
            }
            addView(circle,LinearLayout.LayoutParams(dp(49),dp(49)))
            val words=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(12),0,dp(6),0)
                addView(text(title,15f,true,darkGreen))
                addView(gap(3))
                addView(text(subtitle,11.5f,false,muted))
            }
            addView(words,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            addView(text("›",25f,true,green))
            isClickable=true;isFocusable=true
            contentDescription="$title. $subtitle"
            setOnClickListener{click()}
        }
    private fun autoSyncTile():LinearLayout = actionTile(
        if(autoSmsEnabled())"✓" else "↻",
        if(autoSmsEnabled())"Sincronización automática activa" else "Activar sincronización automática",
        if(autoSmsEnabled())"Al abrir la app y cuando llegan SMS visibles" else "Autorizar lectura de SMS nuevos en el teléfono"
    ) { toggleAutomaticSync() }

    private fun card():LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        background=drawable(Color.WHITE,20).apply { setStroke(dp(1), Color.rgb(225,235,230)) }
        setPadding(dp(18),dp(18),dp(18),dp(18)); elevation=dp(1).toFloat()
        val lp=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin=dp(12);layoutParams=lp
    }
    private fun sectionTitle(t:String){pageBody.addView(text(t,19f,true));pageBody.addView(gap(10))}
    private fun row(left:String,right:String,rightColor:Int=ink):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        addView(text(left,14f,false,muted),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        addView(text(right,15f,true,rightColor).apply { gravity=Gravity.END },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
    }
    private fun edit(hint:String,initial:String="",numeric:Boolean=false):EditText=EditText(this).apply {
        setSingleLine(true);setText(initial);setHint(hint);textSize=15f
        if(numeric)inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setPadding(dp(9),dp(10),dp(9),dp(10))
    }
    private fun block(vararg views:View):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))
        views.forEach { addView(it) }
    }
    private fun dropdown(items:List<String>):Spinner=Spinner(this).apply {
        adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,items)
    }
    private fun alert(t:String,m:String)=AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("Entendido",null).show()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()

    private fun render(){
        root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
        // Android 15 y 16 colocan la ventana detrás de las barras del sistema por defecto.
        // Respetar sus insets evita que el reloj se dibuje encima del ícono y del título.
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.displayCutout()
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        setContentView(root)
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(15),dp(12),dp(15),dp(10))
        }
        if (page != "Inicio") {
            val back = text("←",24f,true,darkGreen).apply {
                gravity=Gravity.CENTER
                contentDescription=if (page in listOf("Pagos","Presupuestos","Asistente")) "Volver a Más" else "Volver a Inicio"
                setOnClickListener { navigateBack() }
            }
            header.addView(back,LinearLayout.LayoutParams(dp(35),dp(42)))
        }
        val logo=ImageView(this).apply {
            setImageResource(gt.finanzas.privadas.R.mipmap.ic_launcher)
            scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription="Ícono de Mis Finanzas"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(37),dp(37)))
        val headerText=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(9),0,0,0)}
        headerText.addView(text("Mis Finanzas",18f,true,darkGreen))
        headerText.addView(text("Tu dinero, en tu teléfono",10.5f,false,muted))
        header.addView(headerText,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val privacy = miniButton(if(amountsHidden()) "Ver Q" else "Ocultar Q") {
            uiPrefs.edit().putBoolean("hide_amounts", !amountsHidden()).apply()
            render()
        }.apply { contentDescription=if(amountsHidden()) "Mostrar montos" else "Ocultar montos en pantalla" }
        header.addView(privacy)
        root.addView(header)
        val scroll=ScrollView(this).apply {isFillViewport=false;isVerticalScrollBarEnabled=false;isSmoothScrollingEnabled=true}
        val up= text("↑",22f,true,green).apply {
            gravity=Gravity.CENTER
            contentDescription="Volver al principio de la pantalla"
            visibility=View.GONE
            setOnClickListener { scroll.smoothScrollTo(0,0) }
        }
        header.addView(up,LinearLayout.LayoutParams(dp(38),dp(40)))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            up.visibility=if(y>dp(220)) View.VISIBLE else View.GONE
        }
        pageBody=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(28))}
        scroll.addView(pageBody)
        root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        when(page){
            "Inicio" -> home()
            "Meses" -> monthsPage()
            "Movimientos" -> movements()
            "Cuentas" -> accounts()
            "Más" -> morePage()
            "Pagos" -> bills()
            "Presupuestos" -> budgets()
            "Asistente" -> assistant()
        }
        val navigation=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            background=drawable(Color.WHITE,0)
            setPadding(dp(5),dp(7),dp(5),dp(6))
            elevation=dp(4).toFloat()
        }
        val choices=listOf("Inicio" to "⌂", "Meses" to "▥", "Movimientos" to "⇄", "Cuentas" to "▣", "Más" to "☷")
        choices.forEach {(destination,symbol)->
            val active=if(page in listOf("Pagos","Presupuestos","Asistente"))destination=="Más" else page==destination
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                background=drawable(if(active)softGreen else Color.TRANSPARENT,13)
                setPadding(dp(3),dp(7),dp(3),dp(7))
                setOnClickListener{page=destination;render()}
            }
            item.isFocusable=true
            item.isSelected=active
            item.contentDescription=destination + if(active) ", sección actual" else ""
            item.addView(text(symbol,22f,true,if(active)green else muted).apply{gravity=Gravity.CENTER})
            item.addView(text(destination,10f,active,if(active)green else muted).apply{gravity=Gravity.CENTER})
            navigation.addView(item,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        }
        root.addView(navigation)
    }

    private fun morePage(){
        sectionTitle("Organiza tu dinero")
        pageBody.addView(text("Herramientas para administrar tus pagos y tomar mejores decisiones.",14f,false,muted))
        pageBody.addView(gap(14))
        listOf(
            Triple("◷  Pagos y suscripciones","Pagos","Recordatorios y vencimientos"),
            Triple("◫  Presupuestos","Presupuestos","Límites por categoría"),
            Triple("✧  Asistente local","Asistente","Consultas a tus movimientos")
        ).forEach {(title,destination,subtitle)->
            val c=card()
            c.addView(text(title,17f,true,darkGreen))
            c.addView(gap(6));c.addView(text(subtitle,13f,false,muted))
            c.setOnClickListener{page=destination;render()}
            pageBody.addView(c)
        }
        sectionTitle("Datos y privacidad")
        pageBody.addView(autoSyncTile())
        pageBody.addView(gap(8))
        pageBody.addView(miniButton("Importar SMS históricos del teléfono"){askSmsPermission()})
        pageBody.addView(gap(8))
        pageBody.addView(miniButton("Importar mensajes copiados"){pasteImportDialog()})
        pageBody.addView(gap(8))
        pageBody.addView(actionTile("♧", "Notificaciones bancarias", "Funciona con sincronización activa y notificaciones visibles") { openNotificationPermission() })
        pageBody.addView(gap(8))
        pageBody.addView(miniButton("Respaldo y restauración"){backupMenu()})
        pageBody.addView(gap(8))
        pageBody.addView(miniButton("Ver cobertura del historial"){historyCoverageDialog()})
        pageBody.addView(gap(8))
        pageBody.addView(miniButton("Actualizar categorías antiguas (revisar cambios)"){
            AlertDialog.Builder(this).setTitle("Actualizar clasificación anterior")
                .setMessage("Solo se revisarán movimientos importados que todavía estén en la categoría 'Por revisar'. No se modificarán gastos ya clasificados manualmente en otra categoría. Algunos débitos ambiguos podrán pasar de Gasto a Pendiente. Haz una copia de seguridad antes de continuar.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Aplicar"){_,_->
                    val changed=db.refreshUncategorizedFromParser()
                    alert("Revisión aplicada","Se actualizaron $changed movimientos antiguos. Comprueba sus categorías antes de confiar en los reportes.")
                    render()
                }.show()
        })
    }

    private fun monthsPage(){
        sectionTitle("Tus reportes")
        pageBody.addView(text("Revisa tus gastos, ingresos confirmados y operaciones pendientes. Los traslados internos no cuentan como consumo.",13f,false,muted))
        pageBody.addView(gap(14))
        val options=db.monthsAvailable()
        val monthOptions=(options + selectedMonth).distinct().sortedDescending()
        val chooser=dropdown(monthOptions.map{monthLabel(it)})
        chooser.setSelection(monthOptions.indexOf(selectedMonth).coerceAtLeast(0))
        val chooseCard=card()
        chooseCard.addView(text("Selecciona el mes",12f,true,muted))
        chooseCard.addView(chooser)
        chooseCard.addView(gap(7))
        chooseCard.addView(miniButton("Ver reporte seleccionado"){
            selectedMonth=monthOptions[chooser.selectedItemPosition];render()
        })
        pageBody.addView(chooseCard)
        val r=db.monthReport(selectedMonth)
        val total=card()
        total.addView(text(monthLabel(selectedMonth).uppercase(Locale.getDefault()),12f,true,muted))
        total.addView(gap(5))
        total.addView(text("Gastos netos",13f,false,muted))
        total.addView(text(money(r.expenses),30f,true,darkGreen))
        total.addView(gap(9))
        total.addView(row("Ingresos confirmados",money(r.income),green))
        total.addView(gap(8));total.addView(row("Débitos por revisar",money(r.pendingDebits),red))
        total.addView(gap(8));total.addView(row("Créditos por revisar",money(r.pendingCredits),green))
        total.addView(gap(8));total.addView(row("Traslados entre cuentas (salida)",money(r.transferOut)))
        total.addView(gap(8));total.addView(row("Traslados entre cuentas (entrada)",money(r.transferIn)))
        total.addView(gap(8));total.addView(row("Balance confirmado del mes",money(r.income-r.expenses),if(r.income-r.expenses<0)red else green))
        total.addView(gap(5));total.addView(text("Ingresos menos gastos netos; no es el saldo bancario.",12f,false,muted))
        total.addView(gap(9));total.addView(text("${r.count} movimientos registrados · ${r.pendingCount} pendientes de revisión",12f,false,muted))
        pageBody.addView(total)
        val prev=java.util.Calendar.getInstance().apply {
            time=SimpleDateFormat("yyyy-MM-dd",Locale.US).parse("$selectedMonth-01")!!
            add(Calendar.MONTH,-1)
        }
        val prevMonth=SimpleDateFormat("yyyy-MM",Locale.US).format(prev.time)
        val prevReport=db.monthReport(prevMonth)
        val compare=card()
        compare.addView(text("Frente a ${monthLabel(prevMonth)}",15f,true))
        compare.addView(gap(7))
        if(prevReport.count==0L || r.count==0L)compare.addView(text("No hay datos suficientes para comparar ambos meses.",13f,false,muted))
        else {
            if(selectedMonth==FinanceDb.ym()) {
                compare.addView(text("Mes en curso frente al mes anterior completo: los períodos tienen distinta duración.",12f,false,muted))
                compare.addView(gap(7))
            }
            val delta=r.expenses-prevReport.expenses
            compare.addView(row("Gasto anterior",money(prevReport.expenses)))
            compare.addView(gap(7));compare.addView(row("Variación",(if(delta>=0)"+" else "−")+money(kotlin.math.abs(delta)),if(delta>0)red else green))
            if(!amountsHidden()) ReportMath.changePercent(r.expenses,prevReport.expenses)?.let {
                compare.addView(gap(5));compare.addView(text("$it respecto al gasto anterior",12f,false,muted))
            }
        }
        pageBody.addView(compare)
        sectionTitle("Tu evolución")
        pageBody.addView(trendCard(selectedMonth))
        categoryCard(selectedMonth)
        pageBody.addView(button("Exportar reporte de ${monthLabel(selectedMonth)} (CSV)"){
            monthForExport=selectedMonth
            val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="text/csv"
                putExtra(Intent.EXTRA_TITLE,"mis-finanzas-$selectedMonth.csv")
            }
            startActivityForResult(intent,703)
        })
        pageBody.addView(gap(11))
        pageBody.addView(text("Los SMS pueden estar incompletos. Esta pantalla es un análisis de los mensajes detectados, no un estado de cuenta oficial.",12f,false,muted))
    }

    private fun monthLabel(value:String):String = try {
        val date=SimpleDateFormat("yyyy-MM-dd",Locale.US).parse("$value-01")!!
        SimpleDateFormat("MMMM yyyy",Locale("es","GT")).format(date).replaceFirstChar {it.uppercase()}
    } catch (_:Exception){value}

    private fun categoryDetails(month:String,category:String){
        val list=db.monthTransactions(month,category,250)
        val date=SimpleDateFormat("dd/MM HH:mm",Locale.getDefault())
        val items=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(10),dp(14),dp(10))
        }
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=true;addView(items) }
        val dialog=AlertDialog.Builder(this)
            .setTitle("$category · ${monthLabel(month)}")
            .setView(scroll)
            .setPositiveButton("Cerrar",null)
            .create()
        if(list.isEmpty()) items.addView(text("Sin movimientos",14f,false,muted))
        list.forEach { t ->
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(9),dp(11),dp(9),dp(11))
            }
            item.addView(text("${date.format(Date(t.whenMs))}  ·  ${t.merchant.take(52)}",14f,true))
            val sign=if(t.kind in listOf("REFUND","INCOME","TRANSFER_IN","PENDING_CREDIT")) "+" else "−"
            item.addView(text("$sign${money(t.cents)} · cuenta ••${t.accountLast4}",13f,false,muted))
            item.setOnClickListener { dialog.dismiss(); txDetail(t) }
            items.addView(item)
            items.addView(View(this).apply{setBackgroundColor(Color.rgb(236,242,239))},
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1)))
        }
        // Máxima altura del panel para que la lista sea desplazable sin tapar la barra inferior.
        scroll.layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(480))
        dialog.show()
    }

    private fun trendCard(month: String): LinearLayout = card().apply {
        addView(text("Ingresos y gastos · 6 meses",16f,true))
        addView(gap(5))
        addView(text("Escala en Q · Verde: ingresos · Coral: gastos netos",12f,false,muted))
        addView(gap(12))
        if(amountsHidden()) addView(text("Gráfica oculta. Pulsa Ver Q para mostrarla.",14f,false,muted))
        else {
            val reports=ReportMath.monthsEndingAt(month).map { db.monthReport(it) }
            addView(FinanceTrendView(this@MainActivity,reports,month) {
                selectedMonth=it;page="Meses";render()
            },LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(240)))
            addView(text("Toca un mes para ver su reporte. — indica que no hay movimientos registrados.",12f,false,muted))
            if(reports.any { it.expenses<0 }) {
                addView(text("Las devoluciones que superan las compras se muestran bajo cero.",12f,false,muted))
            }
            // Text alternative also supports screen readers and exact comparisons.
            addView(miniButton("Ver cifras de los 6 meses") {
                alert("Evolución en quetzales",reports.joinToString("\n\n") {
                    "${monthLabel(it.ym)}\nIngresos: ${money(it.income)} · Gastos netos: ${money(it.expenses)}" +
                        if(it.count==0L) "\nSin movimientos registrados" else ""
                })
            })
        }
    }

    private fun categoryCard(month: String) {
        sectionTitle("Distribución de gastos")
        val cats=db.monthCategories(month)
        val c=card()
        if(cats.isEmpty()) {
            c.addView(text("Sin gastos clasificados",16f,true))
            c.addView(gap(5))
            c.addView(text("Registra una compra o revisa tus movimientos pendientes para ver su distribución.",14f,false,muted))
        } else {
            val positiveTotal=cats.sumOf { it.second.coerceAtLeast(0) }
            c.addView(text("Porcentaje de categorías con gasto neto positivo. Las devoluciones se descuentan de su categoría.",12f,false,muted))
            cats.forEach { (name,amount) ->
                val share=ReportMath.share(amount,positiveTotal)
                val item=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(0,dp(14),0,dp(10));minimumHeight=dp(56)
                    isFocusable=true
                    addView(row("$name  ›",money(amount)))
                    if(!amountsHidden()) {
                        addView(gap(5))
                        addView(text(if(amount<0) "Devoluciones superiores a compras" else "$share% del gasto distribuido",12f,false,muted))
                        addView(ProgressBar(this@MainActivity,null,android.R.attr.progressBarStyleHorizontal).apply {
                            max=100;progress=share
                            progressTintList=ColorStateList.valueOf(green)
                            progressBackgroundTintList=ColorStateList.valueOf(softGreen)
                        })
                    }
                    contentDescription="$name. ${money(amount)}. Ver movimientos de la categoría"
                    setOnClickListener { categoryDetails(month,name) }
                }
                c.addView(item)
            }
        }
        pageBody.addView(c)
    }

    private fun home(){
        sectionTitle("Tu panorama")
        pageBody.addView(text(monthLabel(FinanceDb.ym()),14f,false,muted))
        pageBody.addView(gap(14))
        val accounts=db.accounts();val known=accounts.mapNotNull{db.accountBalance(it)}.sum()
        val unknown=accounts.count{it.opening==null}
        val totals=db.monthTotals()
        val summary=card().apply{background=drawable(darkGreen,23)}
        summary.addView(text("SALDO TOTAL CONOCIDO",12f,true,Color.rgb(201,231,222)))
        summary.addView(gap(5))
        summary.addView(text(if(accounts.isEmpty() || unknown==accounts.size) "—" else money(known),33f,true,Color.WHITE))
        summary.addView(gap(5))
        summary.addView(text(if(accounts.isEmpty())"Agrega tus cuentas para empezar"
            else if(unknown==accounts.size) "Todavía no sabemos tu saldo real · No es Q0"
            else if(unknown>0)"⚠ $unknown cuenta(s) sin saldo real · Total parcial"
            else "Suma de saldos ingresados y movimientos nuevos",
            12f,false,Color.rgb(225,241,235)))
        pageBody.addView(summary)
        if(unknown>0) {
            pageBody.addView(miniButton("Configurar los saldos reales de $unknown cuenta(s)  ›") {
                page="Cuentas";render()
            })
            pageBody.addView(gap(10))
        }
        val summary2=card().apply {
            addView(text("Este mes",17f,true))
            addView(gap(12))
            addView(row("Gastos netos",money(totals.first),red))
            addView(gap(9));addView(row("Ingresos confirmados",money(totals.second),green))
            addView(gap(9));addView(row("Balance confirmado",money(totals.second-totals.first)))
            addView(gap(7));addView(text("Ingresos menos gastos; excluye pendientes y traslados.",12f,false,muted))
        }
        pageBody.addView(summary2)
        if(totals.third>0) {
            pageBody.addView(actionTile("!", "${totals.third} ${if(totals.third==1L) "movimiento" else "movimientos"} por revisar", "Confirma su tipo para mejorar tus reportes") {
                movementFilter="Por revisar";movementQuery="";page="Movimientos";render()
            })
            pageBody.addView(gap(12))
        }
        pageBody.addView(trendCard(FinanceDb.ym()))
        if(accounts.isEmpty())pageBody.addView(button("+ Configurar mi primera cuenta"){addAccountDialog()})
        pageBody.addView(button("▥  Ver reportes mensuales y gráficas"){page="Meses";render()})
        pageBody.addView(gap(12))
        sectionTitle("Accesos rápidos")
        pageBody.addView(miniButton("+ Agregar gasto o ingreso manual"){manualDialog()})
        pageBody.addView(gap(8))
        pageBody.addView(miniButton("Importar mensajes antiguos pegándolos"){pasteImportDialog()})
        pageBody.addView(gap(8))
        pageBody.addView(actionTile("↻", "Actualizar movimientos", "Leer SMS recientes sin duplicarlos") { refreshRecentSms() })
        pageBody.addView(gap(8))
        pageBody.addView(autoSyncTile())
        pageBody.addView(gap(15))
        categoryCard(FinanceDb.ym())
        val upcoming=db.bills().filter{it.paidYm!=FinanceDb.ym()}
        if(upcoming.isNotEmpty()){
            sectionTitle("Pendientes del mes")
            val c=card();upcoming.forEach{c.addView(row("Día ${it.day} · ${it.title}",money(it.cents)));c.addView(gap(9))}
            pageBody.addView(c)
        }
    }

    private fun accounts(){
        pageBody.addView(button("+ Agregar cuenta o efectivo"){addAccountDialog()})
        pageBody.addView(gap(8));pageBody.addView(miniButton("⇄ Transferir entre mis cuentas"){transferDialog()})
        pageBody.addView(gap(8));pageBody.addView(miniButton("Ver cobertura de SMS importados"){historyCoverageDialog()})
        pageBody.addView(gap(8));pageBody.addView(miniButton("Conciliar saldo con mi banco"){reconciliationDialog()})
        pageBody.addView(gap(8));pageBody.addView(miniButton("Guardar / recuperar copia de seguridad"){backupMenu()})
        pageBody.addView(gap(15))
        pageBody.addView(text("Para quitar 'Saldo pendiente', toca Actualizar saldo real en cada cuenta e ingresa el saldo que muestra el banco AHORA (con centavos). Los SMS anteriores sirven para reportes y no se descuentan otra vez. Revisa si las cuentas de BI son distintas o duplicadas.",13f,false,muted))
        pageBody.addView(gap(15))
        db.accounts().forEach{a->
            val c=card();c.addView(row("${a.bank} · ${a.type}",accountLabel(a.last4)))
            c.addView(gap(6));c.addView(text(a.name,16f,true))
            c.addView(gap(9))
            c.addView(text(db.accountBalance(a)?.let{money(it)} ?: "Saldo pendiente de configurar",21f,true,if(a.opening==null)red else ink))
            c.addView(gap(12))
            c.addView(miniButton("Actualizar saldo real"){balanceDialog(a)})
            pageBody.addView(c)
        }
    }
    private fun addAccountDialog(){
        val banks=listOf("Banrural","Banco Industrial","G&T Continental","BAC","Promerica","Bantrab","Efectivo","Otro")
        val bank=dropdown(banks);val name=edit("Nombre: Ahorros, Principal...")
        val last=edit("Últimos 4 dígitos (opcional)")
        last.inputType=InputType.TYPE_CLASS_NUMBER
        val type=dropdown(listOf("Monetaria","Ahorro","Tarjeta","Efectivo","Otro"))
        val balance=edit("Saldo actual en Q (ej. 2500.00)",numeric=true)
        val view=block(text("Banco",13f),bank,name,last,text("Tipo de cuenta",13f),type,balance)
        AlertDialog.Builder(this).setTitle("Nueva cuenta").setView(view).setNegativeButton("Cancelar",null)
            .setPositiveButton("Guardar"){_,_->
                val b=FinanceDb.parseAmount(balance.text.toString())
                if(name.text.isBlank()||b==null){toast("Escribe nombre y saldo válido. Si no lo sabes, escribe 0 y actualízalo luego.");return@setPositiveButton}
                db.createAccount(banks[bank.selectedItemPosition],name.text.toString(),last.text.toString(),type.selectedItem.toString(),b)
                render()
            }.show()
    }
    private fun balanceDialog(a:FinanceDb.Account){
        val input=edit("Saldo REAL de hoy",db.accountBalance(a)?.let{String.format(Locale.US,"%.2f",it/100.0)}?:"",numeric=true)
        AlertDialog.Builder(this).setTitle("Saldo real · ${a.name}")
            .setMessage("Consulta el saldo disponible AHORA en tu banco y escribe exactamente ese importe en quetzales, con centavos. Esto crea un nuevo punto de partida; los SMS anteriores no afectarán dos veces el saldo. No necesitas introducir un saldo de mayo.")
            .setView(block(input)).setNegativeButton("Cancelar",null).setPositiveButton("Actualizar"){_,_->
                val cents=FinanceDb.parseAmount(input.text.toString())
                if(cents==null)toast("Saldo inválido") else {db.updateBalanceSnapshot(a.id,cents);render()}
            }.show()
    }

    private fun movements(){
        sectionTitle("Tus movimientos")
        pageBody.addView(button("+ Agregar movimiento"){manualDialog()})
        pageBody.addView(gap(10))
        pageBody.addView(actionTile("↻", "Actualizar movimientos", "Leer SMS recientes sin duplicarlos") { refreshRecentSms() })
        pageBody.addView(gap(14))
        val search=edit("Buscar comercio, categoría o cuenta",movementQuery)
        search.contentDescription="Buscar movimientos por comercio, categoría o cuenta"
        pageBody.addView(search)
        val filters=listOf("Todos","Por revisar","Gastos","Ingresos","Traslados")
        val selector=dropdown(filters)
        selector.contentDescription="Filtrar movimientos por tipo"
        selector.setSelection(filters.indexOf(movementFilter).coerceAtLeast(0))
        pageBody.addView(selector)
        val resultText=text("",12f,false,muted)
        pageBody.addView(resultText)
        pageBody.addView(gap(10))
        val history=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        pageBody.addView(history)
        val all=db.transactions(5000)
        fun updateHistory() {
            history.removeAllViews()
            val query=BankParser.fold(movementQuery.trim())
            val filtered=all.filter { t ->
                val typeMatches=when(movementFilter) {
                    "Por revisar" -> t.kind in listOf("PENDING_DEBIT","PENDING_CREDIT")
                    "Gastos" -> t.kind in listOf("EXPENSE","REFUND")
                    "Ingresos" -> t.kind=="INCOME"
                    "Traslados" -> t.kind in listOf("TRANSFER_IN","TRANSFER_OUT")
                    else -> true
                }
                typeMatches && (query.isEmpty() || BankParser.fold("${t.merchant} ${t.category} ${t.accountLast4}").contains(query))
            }
            resultText.text="${filtered.size} ${if(filtered.size==1) "resultado" else "resultados"} · búsqueda en los últimos ${all.size} movimientos (máximo 5,000)"
            if(filtered.isEmpty()) history.addView(card().apply {
                addView(text(if(all.isEmpty()) "Comienza tu historial" else "Sin resultados",17f,true))
                addView(gap(6))
                addView(text(if(all.isEmpty()) "Agrega un movimiento o importa tus SMS desde Más." else "Prueba otro comercio o selecciona Todos.",14f,false,muted))
            })
            var previousDay=""
            val dayFormat=SimpleDateFormat("dd MMM yyyy",Locale("es","GT"))
            filtered.take(movementLimit).forEach { t ->
                val day=dayFormat.format(Date(t.whenMs))
                if(day!=previousDay) {
                    history.addView(text(day,13f,true,muted));history.addView(gap(8));previousDay=day
                }
                val positive=t.kind in listOf("INCOME","PENDING_CREDIT","REFUND","TRANSFER_IN")
                val pending=t.kind in listOf("PENDING_DEBIT","PENDING_CREDIT")
                val transfer=t.kind in listOf("TRANSFER_IN","TRANSFER_OUT")
                val c=card().apply {
                    isFocusable=true
                    setOnClickListener { txDetail(t) }
                    addView(row(t.merchant,(if(positive) "+" else "−")+money(t.cents),if(transfer)muted else if(positive)green else red))
                    addView(gap(6))
                    addView(text("${kindLabel(t.kind)} · ${t.category}",12f,true,if(pending)red else muted))
                    addView(text("${accountLabel(t.accountLast4.ifBlank { "manual" })} · ${SimpleDateFormat("HH:mm",Locale.US).format(Date(t.whenMs))}",12f,false,muted))
                }
                history.addView(c)
            }
            if(filtered.size>movementLimit) history.addView(miniButton("Mostrar 80 movimientos más") {
                movementLimit+=80;updateHistory()
            })
        }
        search.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                movementQuery=s?.toString().orEmpty();movementLimit=80;updateHistory()
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        selector.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                movementFilter=filters[position];movementLimit=80;updateHistory()
            }
            override fun onNothingSelected(parent:AdapterView<*>?) {}
        }
        updateHistory()
    }
    private fun accountLabel(code:String):String = if(code.length==4 && code.all{it.isDigit()}) "••$code" else code
    private fun kindLabel(kind:String):String=kindLabels.getOrNull(kinds.indexOf(kind))?:kind
    private fun txDetail(t:FinanceDb.Tx){
        val kind=dropdown(kindLabels);kind.setSelection(kinds.indexOf(t.kind).coerceAtLeast(0))
        val cat=dropdown(categories);cat.setSelection(categories.indexOf(t.category).coerceAtLeast(0))
        val details="${money(t.cents)} · ${t.merchant}\n${SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).format(Date(t.whenMs))}\nOrigen: ${t.source}"
        AlertDialog.Builder(this).setTitle("Clasificar movimiento")
            .setMessage(details).setView(block(text("Tipo",13f),kind,text("Categoría",13f),cat))
            .setNeutralButton("Eliminar"){_,_->AlertDialog.Builder(this).setTitle("¿Eliminar movimiento?")
                .setMessage("Si era una transferencia emparejada, el otro movimiento quedará pendiente.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Eliminar"){_,_->db.deleteTx(t.id);render()}.show()}
            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
                db.classify(t.id,kinds[kind.selectedItemPosition],categories[cat.selectedItemPosition]);render()
            }.show()
    }
    private fun manualDialog(){
        val accts=db.accounts()
        if(accts.isEmpty()){alert("Primero crea una cuenta","Agrega tu monetaria, ahorro o efectivo para asociar los movimientos.");return}
        val acct=dropdown(accts.map{it.name+" (${it.bank})"})
        val amount=edit("Monto Q, ejemplo: 45.50",numeric=true)
        val desc=edit("Descripción: gasolina, almuerzo, pago...")
        val type=dropdown(listOf("Gasto","Ingreso confirmado","Devolución","Traslado enviado","Traslado recibido"))
        val cat=dropdown(categories)
        AlertDialog.Builder(this).setTitle("Nuevo movimiento")
            .setView(block(text("Cuenta",13f),acct,amount,desc,text("Tipo",13f),type,text("Categoría",13f),cat))
            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
                val cents=FinanceDb.parseAmount(amount.text.toString())
                if(cents==null||cents<=0||desc.text.isBlank()){toast("Escribe descripción y monto mayor que cero");return@setPositiveButton}
                db.addManual(accts[acct.selectedItemPosition].id,cents,
                    kinds[type.selectedItemPosition],categories[cat.selectedItemPosition],desc.text.toString())
                render()
            }.show()
    }

    private fun pasteImportDialog(){
        val date=edit("Fecha yyyy-MM-dd",SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date()))
        val body=EditText(this).apply {hint="Pega movimientos Banrural o BI, uno por línea";minLines=6;maxLines=13;setGravity(Gravity.TOP);textSize=13f;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        val info=text("Los avisos que incluyen fecha y hora usarán esa fecha. Los demás usarán la fecha elegida abajo. Para históricos SIN fecha en el texto, impórtalos por día: de lo contrario el reporte mensual sería incorrecto.",12f,false,muted)
        val panel=block(info,date,body)
        val dialog=AlertDialog.Builder(this).setTitle("Importar mensajes copiados").setView(panel)
            .setNegativeButton("Cancelar",null).setPositiveButton("Analizar",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val format=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}
            val parsedDate=try{format.parse(date.text.toString())}catch(_:Exception){null}
            if(parsedDate==null){toast("Fecha inválida: usa yyyy-MM-dd");return@setOnClickListener}
            val lines=body.text.toString().lines().map{it.trim()}.filter{it.isNotBlank()}
            if(lines.isEmpty()){toast("Pega al menos un mensaje");return@setOnClickListener}
            // Medio día evita cambio de fecha por zonas horarias; un segundo por SMS para el emparejamiento.
            val noon=parsedDate.time+12*60*60*1000L
            Thread {
                val r=FinanceDb.ImportResult()
                FinanceDb(applicationContext).use { database -> lines.forEachIndexed{idx,s ->
                    when(database.importLine(s,noon+idx*1000L,"paste")){
                        "added"->r.added++;"duplicate"->r.duplicates++;else->r.ignored++
                    }
                }}
                runOnUiThread {toast("Agregados: ${r.added} · Duplicados: ${r.duplicates} · No reconocidos: ${r.ignored}");render()}
            }.start()
            dialog.dismiss()
        }}
        dialog.show()
    }
    private fun refreshRecentSms() {
        if(checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED) {
            importSmsHistory(silent=false,recentOnly=true)
        } else askSmsPermission()
    }
    private fun toggleAutomaticSync() {
        if(autoSmsEnabled()) {
            syncPrefs.edit().putBoolean("auto_sms_enabled",false).apply()
            stopSmsObserver()
            render()
            toast("Sincronización automática pausada")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Sincronizar nuevos SMS")
            .setMessage("Con tu permiso, Mis Finanzas revisará SMS bancarios nuevos cuando abras la app o mientras la estés usando. Con el acceso a notificaciones también puede detectar avisos compatibles en segundo plano. No accede a tu banca, ni envía mensajes a Internet. Algunos teléfonos restringen la lectura automática.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Activar"){_,_->
                if(checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED) {
                    enableAutomaticSync()
                } else requestPermissions(arrayOf(Manifest.permission.READ_SMS),501)
            }.show()
    }
    private fun enableAutomaticSync() {
        syncPrefs.edit().putBoolean("auto_sms_enabled",true).apply()
        startSmsObserverIfAllowed()
        importSmsHistory(silent=true,recentOnly=true)
        render()
    }
    private fun openNotificationPermission(){
        if (!autoSmsEnabled()) {
            alert("Primero activa la sincronización", "En Movimientos o Más, pulsa 'Activar sincronización automática' y autoriza la lectura de SMS. Después vuelve aquí para permitir las notificaciones bancarias, si quieres recibirlas también con la app cerrada.")
            return
        }
        alert("Acceso a notificaciones", "Android te pedirá autorizar Mis Finanzas. Sólo intentaremos guardar alertas bancarias de SMS compatibles de Banrural y BI, nunca conversaciones personales ni códigos. Si Android oculta la vista previa, esa operación no se capturará automáticamente.")
        try{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}catch(_:Exception){toast("No se pudo abrir la configuración")}
    }
    private fun askSmsPermission(){
        if(checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED){importSmsHistory();return}
        AlertDialog.Builder(this).setTitle("Leer SMS bancarios antiguos")
            .setMessage("Permitirás una lectura local, bajo tu control, de los SMS recibidos. Sólo se guardan movimientos Banrural y BI compatibles; no guardamos OTP ni mensajes personales. No se envían a internet. Esta función es opcional.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Permitir"){_,_->requestPermissions(arrayOf(Manifest.permission.READ_SMS),500)}.show()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==500){if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)importSmsHistory()
        else toast("Sin permiso SMS. Puedes seguir pegando mensajes manualmente.")}
        if(requestCode==501) {
            if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) enableAutomaticSync()
            else toast("La sincronización automática necesita tu autorización para leer SMS.")
        }
    }
    private fun importSmsHistory(silent:Boolean=false,recentOnly:Boolean=false){
        if (!syncRunning.compareAndSet(false,true)) {
            if(!silent)toast("Ya se está revisando el historial")
            return
        }
        Thread {
            val r=FinanceDb.ImportResult()
            val started=System.currentTimeMillis()
            try {
                val projection=arrayOf(Telephony.Sms.BODY,Telephony.Sms.DATE,Telephony.Sms.ADDRESS)
                val lastScan=syncPrefs.getLong("last_auto_scan_ms",0L)
                // Una importación manual completa sigue disponible; la automática sólo revisa
                // mensajes nuevos y un margen de 48 h para SMS que hayan llegado tarde.
                val since = if(recentOnly) {
                    if(lastScan>0L) (lastScan - 2L*24*60*60*1000L).coerceAtLeast(0L)
                    else (started - 7L*24*60*60*1000L).coerceAtLeast(0L)
                } else 0L
                val where = if(recentOnly) "${Telephony.Sms.DATE} >= ?" else null
                val args = if(recentOnly) arrayOf(since.toString()) else null
                val cursor = contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,projection,where,args,"date DESC")
                    ?: throw IllegalStateException("No se pudo abrir el historial SMS")
                cursor.use{ c ->
                    val bodyCol=c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateCol=c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val senderCol=c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    FinanceDb(applicationContext).use{database->
                        while(c.moveToNext()){
                            val raw=c.getString(bodyCol)?:continue
                            val sender=c.getString(senderCol).orEmpty()
                            val isBanrural=raw.contains("BANRURAL",true) || sender.contains("BANRURAL",true)
                            val isBi=raw.contains("BiMovil:",true) || sender.contains("BiMovil",true) || sender.contains("Banco Industrial",true)
                            val strict=BankParser.parse(raw)
                            if(!isBanrural && !isBi && strict==null) continue
                            val full=when {
                                strict!=null ->raw
                                isBi->"BiMovil: $raw"
                                isBanrural->"BANRURAL: $raw"
                                else->raw
                            }
                            when(database.importLine(full,c.getLong(dateCol),"sms")){
                                "added"->r.added++;"duplicate"->r.duplicates++;else->r.ignored++
                            }
                        }
                    }
                }
                // Registrar la hora de corte sólo si la lectura terminó correctamente.
                syncPrefs.edit().putLong("last_auto_scan_ms",started).apply()
                runOnUiThread {
                    if(!isFinishing && !isDestroyed) {
                        if(!silent) alert("Mensajes revisados",
                            "Nuevos movimientos: ${r.added}\nYa registrados: ${r.duplicates}\nAvisos no reconocidos: ${r.ignored}\n\nLos traslados internos se emparejan cuando existen ambas alertas.")
                        else if(r.added>0)toast("${r.added} movimiento(s) bancario(s) nuevos")
                        if(r.added>0)render()
                    }
                }
            }catch(e:SecurityException){if(!silent)runOnUiThread{alert("Permiso denegado",e.message?:"Android impidió leer los SMS.")}}
            catch(e:Exception){if(!silent)runOnUiThread{alert("No se pudieron leer los mensajes",e.message?:"Intenta pegarlos manualmente.")}}
            finally{syncRunning.set(false)}
        }.start()
    }

    private fun historyCoverageDialog(){
        val coverage=db.historyCoverage()
        val df=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault())
        val oldest=coverage.oldest?.let{df.format(Date(it))}?:"Sin movimientos"
        val newest=coverage.newest?.let{df.format(Date(it))}?:"Sin movimientos"
        val monthly=coverage.months.take(36).joinToString("\n") { "${it.first}: ${it.second} movimiento(s)" }
        val report="""Movimientos almacenados: ${coverage.total}
            |Importados directamente de SMS: ${coverage.sms}
            |Pendientes de clasificar: ${coverage.pending}
            |
            |Primer movimiento: $oldest
            |Último movimiento: $newest
            |
            |MOVIMIENTOS POR MES
            |${monthly.ifBlank { "Todavía no hay datos" }}
            |
            |Importante: esto indica los mensajes registrados, no prueba que el banco haya enviado todos sus movimientos por SMS. Confirma las fechas y completa los huecos con un estado de cuenta.
        """.trimMargin()
        alert("Cobertura del historial",report)
    }

    private fun reconciliationDialog(){
        val accounts=db.accounts()
        if(accounts.isEmpty()){
            alert("Sin cuentas","Importa primero tus SMS o agrega una cuenta para conciliar.");return
        }
        val options=dropdown(accounts.map { "${it.bank} ${it.type} ••${it.last4} (${it.name})" })
        val dateFormat=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).apply{isLenient=false}
        val calendar=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0)}
        val from=edit("Inicio: 2026-10-01 00:00",dateFormat.format(calendar.time))
        val until=edit("Fin: yyyy-MM-dd HH:mm",dateFormat.format(Date()))
        val opening=edit("Saldo bancario al INICIO (Q)",numeric=true)
        val closing=edit("Saldo bancario al FINAL (Q)",numeric=true)
        val instructions=text("Necesitas DOS saldos independientes del banco: uno al inicio del período (por ejemplo, de un estado de cuenta) y el real al final. La app calculará el esperado usando todos los SMS registrados en ese intervalo. Las cuentas no se modificarán.",12f,false,muted)
        val scroll=ScrollView(this)
        scroll.addView(block(instructions,gap(6),text("Cuenta",13f),options,
            text("Fecha y hora del saldo inicial",13f),from,
            text("Fecha y hora del saldo final",13f),until,
            text("Saldo inicial de ese momento",13f),opening,
            text("Saldo final real del banco",13f),closing))
        val dialog=AlertDialog.Builder(this).setTitle("Conciliar contra banco")
            .setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Comparar",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val begin=runCatching{dateFormat.parse(from.text.toString())?.time}.getOrNull()
            val end=runCatching{dateFormat.parse(until.text.toString())?.time}.getOrNull()
            val initial=FinanceDb.parseAmount(opening.text.toString())
            val actual=FinanceDb.parseAmount(closing.text.toString())
            if(begin==null || end==null || begin>=end || initial==null || actual==null){
                toast("Revisa fechas (aaaa-MM-dd HH:mm) y ambos saldos");return@setOnClickListener
            }
            val a=accounts[options.selectedItemPosition]
            val r=db.reconcile(a.id,begin,end,initial,actual)
            dialog.dismiss()
            val differenceLabel=if(r.difference==0L)"Coincide al centavo" else
                if(r.difference>0L)"El banco tiene ${money(r.difference)} MÁS que el cálculo" else
                    "El banco tiene ${money(-r.difference)} MENOS que el cálculo"
            val textReport="""Cuenta: ${a.name}
                |Desde: ${dateFormat.format(Date(begin))}
                |Hasta: ${dateFormat.format(Date(end))}
                |
                |Saldo inicial del banco: ${money(initial)}
                |+ Abonos / devoluciones: ${money(r.credits)}
                |- Débitos / compras: ${money(r.debits)}
                |= Saldo ESPERADO: ${money(r.predicted)}
                |
                |Saldo REAL del banco: ${money(actual)}
                |RESULTADO: $differenceLabel
                |
                |Movimientos considerados: ${r.count}
                |Pendientes de revisar: ${r.pendingCount}
                |  Abonos pendientes: ${money(r.pendingCredits)}
                |  Débitos pendientes: ${money(r.pendingDebits)}
                |
                |Esto NO garantiza que todos los SMS existan. Un movimiento faltante, duplicado, fecha diferente, comisión, interés o retiro sin alerta puede causar diferencias. Compara con tu estado de cuenta.
            """.trimMargin()
            alert("Resultado de conciliación",textReport)
        }}
        dialog.show()
    }

    private fun bills(){
        pageBody.addView(button("+ Agregar pago recurrente"){addBillDialog()})
        pageBody.addView(gap(12))
        pageBody.addView(text("Para alquiler, Claro, suscripciones, gasolina u otros recordatorios mensuales. Marca cada obligación cuando ya la pagaste.",13f,false,muted))
        pageBody.addView(gap(15))
        val bills=db.bills()
        if(bills.isEmpty())pageBody.addView(text("Todavía no hay pagos programados.",14f,false,muted))
        bills.forEach{bill ->
            val paid=bill.paidYm==FinanceDb.ym()
            val c=card()
            c.addView(row("${bill.title} · día ${bill.day}",money(bill.cents),if(paid)green else ink))
            c.addView(gap(5));c.addView(text(if(paid)"✓ Pagado este mes" else "Pendiente · ${bill.category}",13f,false,if(paid)green else red))
            c.addView(gap(10))
            val matched=possiblePaidMovement(bill)
            if(!paid && matched!=null){
                c.addView(text("Posible pago encontrado: ${matched.merchant.take(52)} · ${money(matched.cents)}. Confírmalo antes de marcar.",12f,true,green))
                c.addView(gap(8))
            }
            c.addView(miniButton(if(paid)"Marcar pendiente" else "Marcar pagado"){db.togglePaid(bill.id,!paid);render()})
            c.addView(gap(7))
            c.addView(miniButton("Eliminar recordatorio"){AlertDialog.Builder(this).setTitle("Eliminar ${bill.title}?")
                .setNegativeButton("Cancelar",null).setPositiveButton("Eliminar"){_,_->db.removeBill(bill.id);render()}.show()})
            pageBody.addView(c)
        }
    }
    private fun addBillDialog(){
        val title=edit("Ej. Pago Claro, gasolina")
        val amount=edit("Monto esperado en Q",numeric=true)
        val day=edit("Día de pago: 1 a 31",numeric=true)
        val cat=dropdown(categories)
        AlertDialog.Builder(this).setTitle("Pago / recordatorio mensual")
            .setView(block(title,amount,day,text("Categoría",13f),cat))
            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
                val money=FinanceDb.parseAmount(amount.text.toString());val d=day.text.toString().toIntOrNull()
                if(title.text.isBlank()||money==null||money<=0||d==null||d !in 1..31){toast("Verifica el título, el monto y el día");return@setPositiveButton}
                db.addBill(title.text.toString(),money,d,categories[cat.selectedItemPosition]);render()
            }.show()
    }
    private fun budgets(){
        pageBody.addView(button("+ Asignar presupuesto mensual"){addBudgetDialog()})
        pageBody.addView(gap(15))
        val all=db.budgets()
        if(all.isEmpty())pageBody.addView(text("Define tus límites: Q600 para gasolina, Q800 para comida, etc.",14f,false,muted))
        all.forEach{budget->
            val spent=db.categorySpent(budget.category)
            val left=budget.cents-spent
            val c=card()
            c.addView(row(budget.category,"Límite ${money(budget.cents)}"))
            c.addView(gap(8));c.addView(text("Gastado ${money(spent)} · ${if(left<0)"Excedido" else "Disponible"} ${money(kotlin.math.abs(left))}",14f,true,if(left<0)red else green))
            c.addView(gap(8))
            val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
                max=100;progress=if(budget.cents<=0)0 else ((spent*100)/budget.cents).toInt().coerceIn(0,100)
                progressTintList=android.content.res.ColorStateList.valueOf(if(left<0)red else green)
            };if(!amountsHidden())c.addView(bar)
            c.addView(gap(10));c.addView(miniButton("Cambiar límite"){budgetDialogFor(budget.category)})
            pageBody.addView(c)
        }
    }
    private fun addBudgetDialog(){
        val cat=dropdown(categories.filter{it!="Por revisar"})
        AlertDialog.Builder(this).setTitle("Categoría del presupuesto").setView(block(cat))
            .setNegativeButton("Cancelar",null).setPositiveButton("Continuar"){_,_->budgetDialogFor(cat.selectedItem.toString())}.show()
    }
    private fun budgetDialogFor(category:String){
        val old=db.budgets().find{it.category==category}
        val amount=edit("Límite mensual en Q",old?.let{String.format(Locale.US,"%.2f",it.cents/100.0)}?:"",true)
        AlertDialog.Builder(this).setTitle("Presupuesto: $category").setView(block(amount))
            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
                val limit=FinanceDb.parseAmount(amount.text.toString())
                if(limit==null||limit<=0)toast("Ingresa un límite válido") else {db.setBudget(category,limit);render()}
            }.show()
    }


    private fun possiblePaidMovement(bill:FinanceDb.Bill):FinanceDb.Tx? {
        val stop=setOf("pago","cuota","mensual","servicio","de","del","el","la","mi","mis")
        val tokens=BankParser.fold(bill.title).split(Regex("[^a-z0-9]+")).filter{it.length>=4 && it !in stop}
        if(tokens.isEmpty())return null
        return db.transactions().firstOrNull{t->
            t.kind=="EXPENSE" && t.cents==bill.cents && t.whenMs>=FinanceDb.firstOfMonth() &&
                tokens.any{BankParser.fold(t.merchant).contains(it)}
        }
    }
    private fun transferDialog(){
        val accts=db.accounts()
        if(accts.size<2){alert("Dos cuentas necesarias","Agrega tu monetaria y ahorro (u otras cuentas) para registrar un traslado.");return}
        val from=dropdown(accts.map{it.name});val to=dropdown(accts.map{it.name})
        to.setSelection(1)
        val amount=edit("Cantidad que trasladas en Q",numeric=true)
        val reason=edit("Descripción","Traslado entre mis cuentas")
        AlertDialog.Builder(this).setTitle("Traspaso, NO gasto")
            .setView(block(text("Desde",13f),from,text("Hacia",13f),to,amount,reason))
            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
                val cents=FinanceDb.parseAmount(amount.text.toString())
                if(from.selectedItemPosition==to.selectedItemPosition||cents==null||cents<=0){toast("Selecciona cuentas distintas y monto válido");return@setPositiveButton}
                db.manualTransfer(accts[from.selectedItemPosition].id,accts[to.selectedItemPosition].id,cents,reason.text.toString())
                render()
            }.show()
    }
    private fun backupMenu(){
        AlertDialog.Builder(this).setTitle("Respaldo privado de tus datos")
            .setMessage("Puedes guardar una copia JSON en un lugar que elijas, o restaurar una copia anterior. El archivo NO está cifrado: guárdalo en un sitio seguro. Restaurar reemplaza todos los datos actuales.")
            .setNegativeButton("Cancelar",null)
            .setNeutralButton("Restaurar"){_,_->
                val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/json"}
                startActivityForResult(intent,702)
            }
            .setPositiveButton("Guardar copia"){_,_->
                val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
                    addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
                    putExtra(Intent.EXTRA_TITLE,"mis-finanzas-${FinanceDb.ym()}.json")
                }
                startActivityForResult(intent,701)
            }.show()
    }
    @Deprecated("Se utiliza el selector estándar de documentos de Android")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK||data?.data==null)return
        val uri=data.data?:return
        try{
            when(requestCode){
                701->{
                    val payload=db.exportJson()
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(payload)}
                        ?:error("No se pudo escribir el archivo")
                    toast("Copia guardada. Protégela: contiene información financiera.")
                }
                703->{
                    val report=db.exportMonthCsv(monthForExport)
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(report)}
                        ?: error("No se pudo exportar el CSV")
                    toast("Reporte $monthForExport guardado")
                }
                702->{
                    val payload=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}
                        ?:error("No se pudo leer la copia")
                    AlertDialog.Builder(this).setTitle("¿Reemplazar todos los datos?")
                        .setMessage("Tus movimientos, cuentas, presupuestos y pagos actuales se reemplazarán por la copia seleccionada. No se puede deshacer.")
                        .setNegativeButton("Cancelar",null)
                        .setPositiveButton("Restaurar"){_,_->
                            try{db.restoreJson(payload);toast("Datos restaurados");render()}
                            catch(e:Exception){alert("No se pudo restaurar",e.message?:"Formato inválido")}
                        }.show()
                }
            }
        }catch(e:Exception){alert("Error de archivo",e.message?:"No se pudo procesar")}
    }

    private fun assistant(){
        sectionTitle("Asistente financiero local")
        pageBody.addView(text("Analiza tu historial, presupuestos y recordatorios sin internet. En esta versión funciona con reglas, no con una IA generativa externa.",13f,false,muted))
        pageBody.addView(gap(15))
        val suggestions=listOf("¿Qué me falta pagar?","¿Cuánto tengo disponible?","¿Cuánto gasté en gasolina?","¿Dónde gasté más?","¿Puedo ahorrar este mes?")
        suggestions.forEach { q ->
            pageBody.addView(miniButton(q){showAdvice(q)})
            pageBody.addView(gap(8))
        }
        pageBody.addView(gap(10))
        val question=edit("Escribe tu pregunta...")
        pageBody.addView(question);pageBody.addView(gap(9))
        pageBody.addView(button("Consultar mis finanzas"){showAdvice(question.text.toString())})
    }
    private fun showAdvice(question:String){
        val q=BankParser.fold(question)
        val all=db.accounts();val missing=all.count{it.opening==null}
        val total=all.mapNotNull{db.accountBalance(it)}.sum()
        val owed=db.bills().filter{it.paidYm!=FinanceDb.ym()}
        val payable=owed.sumOf{it.cents}
        val totals=db.monthTotals()
        val response=when{
            q.contains("pagar")||q.contains("pendiente")||q.contains("debo")||q.contains("recuerda") ->
                if(owed.isEmpty())"No has registrado pagos recurrentes pendientes este mes. Agrega tus obligaciones en la pestaña Pagos."
                else "Te falta registrar como pagados ${owed.size} compromiso(s) por ${money(payable)}:\n"+
                    owed.joinToString("\n"){"• Día ${it.day}: ${it.title} (${money(it.cents)})"}
            q.contains("gasolina")||q.contains("comida")||q.contains("suscrip")||q.contains("servicio")||q.contains("cine") -> {
                val category=when{q.contains("gasolina")->"Gasolina";q.contains("comida")->"Comida";q.contains("suscrip")->"Suscripciones";q.contains("servicio")->"Servicios";else->"Entretenimiento"}
                val spent=db.categorySpent(category);val budget=db.budgets().find{it.category==category}
                "En $category llevas ${money(spent)} de gastos netos este mes."+
                    (budget?.let{" Tu límite es ${money(it.cents)} y ${if(it.cents>=spent)"te quedan ${money(it.cents-spent)}" else "lo superaste por ${money(spent-it.cents)}"}."}?:" Agrega un presupuesto para saber cuánto te queda.")
            }
            q.contains("donde")||q.contains("mas")||q.contains("categoria") -> {
                val cat=db.breakdown().filter{it.second>0}.take(5)
                if(cat.isEmpty())"Todavía no hay gastos clasificados de este mes."
                else "Tus categorías más altas este mes son:\n"+cat.joinToString("\n"){"• ${it.first}: ${money(it.second)}"}
            }
            q.contains("ahorr")||q.contains("sobr") -> {
                "Cuentas configuradas: ${money(total)}. Pagos registrados pendientes: ${money(payable)}. Diferencia orientativa: ${money(total-payable)}."+
                    (if(missing>0)" Hay $missing cuentas sin saldo, por lo que es incompleta." else "")+
                    " Esto NO descuenta gastos futuros no registrados ni sustituye el saldo bancario."
            }
            q.contains("tengo")||q.contains("saldo")||q.contains("disponible") ->
                "El total calculado de tus cuentas con saldo configurado es ${money(total)}."+
                    (if(missing>0)" Hay $missing cuenta(s) pendientes; no es tu total definitivo." else "")+
                    " Recuerda comprobar tu saldo real y actualizarlo periódicamente."
            else -> "Este mes tienes ${money(totals.first)} en gastos clasificados, ${money(totals.second)} en ingresos confirmados y ${totals.third} movimientos pendientes de revisar. Puedes preguntarme por gasolina, comida, pagos, saldos o ahorro."
        }
        alert("Asistente · ${question.take(35)}",response)
    }
}
