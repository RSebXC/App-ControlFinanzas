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
import android.graphics.drawable.RippleDrawable
import java.time.YearMonth
import java.time.LocalDate
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
    private var chartMonths = 6
    private var chartLines = true
    private var scrollToConversation = false
    private val assistantQuestions = mutableListOf<String>()
    private var assistantDraft = ""
    private val readModel by lazy { FinanceReadModel(db) }
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
    private val bg = FinanceDesign.background
    private val ink = FinanceDesign.ink
    private val green = FinanceDesign.teal
    private val darkGreen = FinanceDesign.navy
    private val softGreen = FinanceDesign.mint
    private val muted = FinanceDesign.muted
    private val red = FinanceDesign.coral
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
            chartMonths = it.getInt("chartMonths", 6)
            chartLines = it.getBoolean("chartLines", true)
            assistantQuestions.addAll(it.getStringArrayList("questions").orEmpty().takeLast(8))
            assistantDraft = it.getString("assistantDraft", "")
        }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("month", selectedMonth)
        outState.putString("query", movementQuery)
        outState.putString("filter", movementFilter)
        outState.putInt("chartMonths", chartMonths)
        outState.putBoolean("chartLines", chartLines)
        outState.putStringArrayList("questions", ArrayList(assistantQuestions))
        outState.putString("assistantDraft", assistantDraft)
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
            "Pagos", "Presupuestos" -> { page = "Más"; render() }
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
    private fun drawable(color:Int,radius:Int=18):GradientDrawable=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius).toFloat()
    }
    private fun text(s:String,size:Float=15f,bold:Boolean=false,color:Int=ink):TextView=TextView(this).apply {
        text=s;textSize=size;setTextColor(color)
        typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(),1.06f)
    }
    private fun gap(h:Int=10)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))}
    private fun go(destination:String) { page=destination;render() }
    private fun touch(view:View,click:()->Unit) {
        view.isClickable=true;view.isFocusable=true
        view.foreground=RippleDrawable(ColorStateList.valueOf(0x18007e77),null,drawable(Color.WHITE,18))
        view.setOnClickListener{click()}
    }
    private fun iconBubble(name:String,tint:Int=green,surface:Int=softGreen,size:Int=44)=ImageView(this).apply {
        setImageDrawable(FinanceIcon(name,tint,dp(23)))
        background=drawable(surface,14);setPadding(dp(11),dp(11),dp(11),dp(11))
        layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun iconButton(name:String,label:String,click:()->Unit)=iconBubble(name,ink,Color.TRANSPARENT,48).apply {
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription=label
        touch(this,click)
    }
    private fun inferredIcon(label:String):String {
        val q=BankParser.fold(label)
        return when {
            q.contains("export")||q.contains("copia")||q.contains("respaldo") -> "download"
            q.contains("sincr")||q.contains("actualizar movimientos")||q.contains("importar")||q.contains("leer sms") -> "refresh"
            q.contains("asistente")||q.contains("consult") -> "spark"
            q.contains("presupuest")||q.contains("limite") -> "budget"
            q.contains("pago") -> "calendar"
            q.contains("transfer")||q.contains("traslado") -> "transfer"
            q.contains("agregar")||q.contains("nuevo")||q.startsWith("+") -> "add"
            q.contains("revis") -> "clock"
            q.contains("saldo")||q.contains("cuenta") -> "wallet"
            q.contains("reporte")||q.contains("graf")||q.contains("cifras")||q.contains("mes") -> "chart"
            else -> "next"
        }
    }
    private fun cleanLabel(label:String)=label.trim().trimStart('+','▥','⇄','↻','◷','◫','✧').trim()
    private fun button(s:String,onClick:()->Unit):TextView=text(cleanLabel(s),14f,true,Color.WHITE).apply {
        minHeight=dp(50);gravity=Gravity.CENTER
        background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(green,Color.rgb(0,145,130))).apply {cornerRadius=dp(16).toFloat()}
        setPadding(dp(18),dp(14),dp(18),dp(14));touch(this,onClick)
    }
    private fun miniButton(s:String,click:()->Unit):TextView=text(cleanLabel(s),13f,true,green).apply {
        minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL
        background=drawable(Color.WHITE,14).apply{setStroke(dp(1),FinanceDesign.line)}
        setPadding(dp(14),dp(12),dp(14),dp(12))
        setCompoundDrawablesRelative(FinanceIcon(inferredIcon(s),green,dp(19)),null,null,null)
        compoundDrawablePadding=dp(9);touch(this,click)
    }
    private fun actionTile(symbol:String,title:String,subtitle:String,click:()->Unit):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        background=drawable(Color.WHITE,20).apply {setStroke(dp(1),FinanceDesign.line)}
        setPadding(dp(14),dp(14),dp(14),dp(14))
        val name=if(symbol in listOf("spark","budget","calendar","shield","refresh","wallet"))symbol else inferredIcon(title)
        addView(iconBubble(name))
        val words=LinearLayout(this@MainActivity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(8),0)
            addView(text(title,14f,true));addView(gap(3));addView(text(subtitle,12f,false,muted))
        }
        addView(words,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        addView(ImageView(this@MainActivity).apply {setImageDrawable(FinanceIcon("next",muted,dp(18)))},LinearLayout.LayoutParams(dp(18),dp(18)))
        contentDescription="$title. $subtitle";touch(this,click)
    }
    private fun autoSyncTile():LinearLayout=actionTile("refresh",
        if(autoSmsEnabled())"Sincronización activa" else "Sincronización automática",
        if(autoSmsEnabled())"Lectura de SMS autorizada · Toca para pausar" else "Elige si quieres importar los SMS nuevos"
    ){toggleAutomaticSync()}
    private fun card():LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;background=drawable(Color.WHITE,22)
        setPadding(dp(18),dp(18),dp(18),dp(18))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(14)}
    }
    private fun sectionTitle(t:String){pageBody.addView(gap(8));pageBody.addView(text(t,19f,true));pageBody.addView(gap(12))}
    private fun pageTitle(title:String,subtitle:String) {
        pageBody.addView(text(title,27f,true));pageBody.addView(gap(5));pageBody.addView(text(subtitle,13f,false,muted));pageBody.addView(gap(20))
    }
    private fun row(left:String,right:String,rightColor:Int=ink):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        addView(text(left,13f,false,muted),LinearLayout.LayoutParams(0,-2,1f))
        addView(text(right,14f,true,rightColor).apply{gravity=Gravity.END},LinearLayout.LayoutParams(0,-2,1f))
    }
    private fun pill(label:String,color:Int=green,surface:Int=softGreen)=text(label,11f,true,color).apply {
        background=drawable(surface,9);setPadding(dp(9),dp(5),dp(9),dp(5))
        layoutParams=LinearLayout.LayoutParams(-2,-2)
    }
    private fun divider()=View(this).apply{setBackgroundColor(FinanceDesign.line);layoutParams=LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(12);bottomMargin=dp(12)}}
    private fun edit(hint:String,initial:String="",numeric:Boolean=false):EditText=EditText(this).apply {
        setSingleLine(true);setText(initial);setHint(hint);textSize=14f;setTextColor(ink);setHintTextColor(muted)
        background=drawable(Color.WHITE,14).apply {setStroke(dp(1),FinanceDesign.line)}
        if(numeric)inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setPadding(dp(15),dp(15),dp(15),dp(15));minimumHeight=dp(50)
    }
    private fun block(vararg views:View):ScrollView=ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(12))
            views.forEach {addView(it);addView(gap(8))}
        })
    }
    private fun dropdown(items:List<String>):Spinner=Spinner(this).apply {
        adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,items)
        minimumHeight=dp(48)
    }
    private fun alert(t:String,m:String)=AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("Entendido",null).show()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun metric(label:String,amount:Long,icon:String,tint:Int):LinearLayout=card().apply {
        setPadding(dp(15),dp(15),dp(15),dp(15))
        val top=LinearLayout(this@MainActivity).apply {gravity=Gravity.CENTER_VERTICAL}
        top.addView(iconBubble(icon,tint,if(tint==red)0xffffedf0.toInt() else softGreen,34))
        top.addView(text(label,12f,false,muted).apply{setPadding(dp(8),0,0,0)},LinearLayout.LayoutParams(0,-2,1f));addView(top)
        addView(gap(11));addView(text(money(amount),21f,true).apply {
            setSingleLine(true);setAutoSizeTextTypeUniformWithConfiguration(14,21,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        })
    }
    private fun metricPair(left:LinearLayout,right:LinearLayout):LinearLayout=LinearLayout(this).apply {
        addView(left,LinearLayout.LayoutParams(0,-2,1f));addView(View(this@MainActivity),LinearLayout.LayoutParams(dp(12),1))
        addView(right,LinearLayout.LayoutParams(0,-2,1f));layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)}
    }
    private data class QuickAction(val label:String,val icon:String,val action:()->Unit)
    private fun quickActions(items:List<QuickAction>):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP
        items.forEach { item ->
            val tile=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(2),dp(4),dp(2),dp(8))
                addView(iconBubble(item.icon,green,Color.WHITE,52));addView(gap(8))
                addView(text(item.label,11.5f,true).apply{gravity=Gravity.CENTER})
                contentDescription=item.label;touch(this,item.action)
            };addView(tile,LinearLayout.LayoutParams(0,-2,1f))
        }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)}
    }
    private fun segments(labels:List<String>,selected:Int,click:(Int)->Unit):LinearLayout=LinearLayout(this).apply {
        background=drawable(0xffeaf0f4.toInt(),13);setPadding(dp(4),dp(4),dp(4),dp(4))
        labels.forEachIndexed { i,label ->
            val item=text(label,12f,i==selected,if(i==selected)ink else muted).apply {
                gravity=Gravity.CENTER;minHeight=dp(40);background=drawable(if(i==selected)Color.WHITE else Color.TRANSPARENT,10)
                isSelected=i==selected;contentDescription=label+if(i==selected)", seleccionado" else "";touch(this){click(i)}
            };addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
    }
    private fun render(preserveScroll:Boolean=false){
        val previousScroll=if(preserveScroll && ::root.isInitialized)root.findViewWithTag<ScrollView>("page-scroll")?.scrollY?:0 else 0
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener {view,insets ->
            val safe=insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            val keyboard=insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(safe.left,safe.top,safe.right,maxOf(safe.bottom,keyboard.bottom));insets
        }
        setContentView(root)
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(8),dp(12),dp(8))}
        if(page=="Inicio") header.addView(ImageView(this).apply{setImageResource(R.drawable.ic_brand_mark)},LinearLayout.LayoutParams(dp(40),dp(40)))
        else header.addView(iconButton("back","Volver"){navigateBack()})
        val words=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)
            addView(text(if(page=="Inicio")"MIS FINANZAS" else when(page){"Meses"->"Reportes";"Más"->"Herramientas";else->page},if(page=="Inicio")12f else 18f,true))
            if(page=="Inicio")addView(text("Tu espacio financiero",11f,false,muted))
        }
        header.addView(words,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(iconButton(if(amountsHidden())"eyeoff" else "eye",if(amountsHidden())"Mostrar montos" else "Ocultar montos") {
            uiPrefs.edit().putBoolean("hide_amounts",!amountsHidden()).apply();render(true)
        })
        header.addView(iconButton("settings","Herramientas y ajustes"){go("Más")})
        root.addView(header)
        val scroll=ScrollView(this).apply{tag="page-scroll";isVerticalScrollBarEnabled=false;isSmoothScrollingEnabled=true}
        pageBody=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(14),dp(20),dp(28))}
        scroll.addView(pageBody);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(page){"Inicio"->home();"Meses"->monthsPage();"Movimientos"->movements();"Cuentas"->accounts();"Más"->morePage();"Pagos"->bills();"Presupuestos"->budgets();"Asistente"->assistant()}
        if(page=="Asistente")root.addView(assistantComposer())
        val nav=LinearLayout(this).apply{setBackgroundColor(Color.WHITE);setPadding(dp(8),dp(9),dp(8),dp(8));elevation=dp(2).toFloat()}
        listOf(Triple("Inicio","Inicio","home"),Triple("Meses","Reportes","chart"),Triple("Movimientos","Movimientos","activity"),Triple("Cuentas","Cuentas","wallet"),Triple("Asistente","Asistente","spark")).forEach{(route,label,icon) ->
            val active=page==route
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(FinanceIcon(icon,if(active)green else muted,dp(23)))
                    setPadding(dp(16),dp(5),dp(16),dp(5));background=drawable(if(active)softGreen else Color.TRANSPARENT,14)
                },LinearLayout.LayoutParams(dp(56),dp(34)))
                addView(gap(4));addView(text(label,10f,active,if(active)green else muted).apply{gravity=Gravity.CENTER})
                isSelected=active;contentDescription=label+if(active)", sección actual" else "";touch(this){go(route)}
            };nav.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
        root.addView(nav)
        if(scrollToConversation){scrollToConversation=false;scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
        else if(previousScroll>0)scroll.post{scroll.scrollTo(0,previousScroll)}
    }

    private fun morePage(){
        pageTitle("Tu centro de control.","Organiza tus compromisos y decide cómo usar tus datos.")
        listOf(Triple("Pagos y suscripciones","Pagos","calendar"),Triple("Presupuestos","Presupuestos","budget"),Triple("Asistente local","Asistente","spark")).forEach{(title,destination,icon)->
            pageBody.addView(actionTile(icon,title,when(destination){"Pagos"->"Recordatorios y compromisos del mes";"Presupuestos"->"Límites que tú defines por categoría";else->"Explora tus registros con preguntas"}){go(destination)})
            pageBody.addView(gap(10))
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
        pageTitle("Tus números, en perspectiva.","Explora patrones y compara tus registros.")
        val picker=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;background=drawable(Color.WHITE,16);setPadding(dp(4),dp(4),dp(4),dp(4))
            addView(iconButton("back","Mes anterior"){selectedMonth=YearMonth.parse(selectedMonth).minusMonths(1).toString();render()})
            addView(text(monthLabel(selectedMonth),16f,true).apply {
                gravity=Gravity.CENTER;minHeight=dp(48);touch(this){
                    val options=(db.monthsAvailable()+selectedMonth).distinct().sortedDescending()
                    AlertDialog.Builder(this@MainActivity).setTitle("Elegir mes").setItems(options.map{monthLabel(it)}.toTypedArray()){_,i->selectedMonth=options[i];render()}.show()
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(iconButton("next","Mes siguiente"){selectedMonth=YearMonth.parse(selectedMonth).plusMonths(1).toString();render()})
        };pageBody.addView(picker);pageBody.addView(gap(16))
        val r=db.monthReport(selectedMonth)
        val previous=db.monthReport(YearMonth.parse(selectedMonth).minusMonths(1).toString())
        val total=card()
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(text("GASTOS NETOS",11f,true,muted),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(pill(if(selectedMonth==FinanceDb.ym())"Mes en curso" else "Resumen mensual"));total.addView(head)
        total.addView(gap(11));total.addView(text(money(r.expenses),34f,true))
        if(!amountsHidden() && r.count>0 && previous.count>0)ReportMath.changePercent(r.expenses,previous.expenses)?.let {
            total.addView(gap(7));total.addView(text("$it frente al mes anterior",12f,true,if(r.expenses>previous.expenses)red else green))
        }
        total.addView(gap(10));total.addView(text("Compras menos devoluciones · ${r.count} movimientos",12f,false,muted))
        pageBody.addView(total)
        pageBody.addView(metricPair(metric("Ingresos",r.income,"down",green),metric("Balance del mes",r.income-r.expenses,"chart",if(r.income-r.expenses<0)red else green)))
        pageBody.addView(trendCard(selectedMonth))
        categoryCard(selectedMonth)
        pulseCard(selectedMonth)
        val merchants=readModel.topMerchants(selectedMonth)
        if(merchants.isNotEmpty()) {
            sectionTitle("Comercios principales")
            val box=card();merchants.forEachIndexed{i,(name,amount)->
                box.addView(row(name,money(amount)));if(i<merchants.lastIndex)box.addView(divider())
            };box.addView(gap(10));box.addView(text("Gasto neto agrupado por nombre registrado.",11f,false,muted));pageBody.addView(box)
        }
        val info=card()
        info.addView(row("Por revisar","${r.pendingCount} movimientos"));info.addView(divider())
        info.addView(row("Débitos pendientes",money(r.pendingDebits)));info.addView(gap(8));info.addView(row("Abonos pendientes",money(r.pendingCredits)))
        info.addView(divider());info.addView(row("Traslados enviados",money(r.transferOut)));info.addView(gap(8));info.addView(row("Traslados recibidos",money(r.transferIn)))
        info.addView(gap(12));info.addView(text("El balance es ingreso menos gasto neto. Los pendientes y traslados se muestran aparte.",12f,false,muted))
        if(selectedMonth==FinanceDb.ym()) {info.addView(gap(8));info.addView(text("Comparación de un mes en curso con el anterior completo: los períodos tienen distinta duración.",12f,false,muted))}
        pageBody.addView(info)
        pageBody.addView(button("Exportar reporte CSV"){
            monthForExport=selectedMonth
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
                addCategory(Intent.CATEGORY_OPENABLE);type="text/csv";putExtra(Intent.EXTRA_TITLE,"mis-finanzas-$selectedMonth.csv")
            },703)
        })
        pageBody.addView(gap(12));pageBody.addView(text("Basado en los movimientos registrados. No sustituye el estado de cuenta del banco.",12f,false,muted))
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

    private fun meter(amount:Long,total:Long,tint:Int=green):ProgressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=1000;progress=if(total<=0)0 else (amount.toDouble()/total*1000).toInt().coerceIn(0,1000)
        progressTintList=ColorStateList.valueOf(tint);progressBackgroundTintList=ColorStateList.valueOf(FinanceDesign.line)
        layoutParams=LinearLayout.LayoutParams(-1,dp(7))
        contentDescription=if(total>0) "${ReportMath.share(amount,total)} por ciento" else "Sin límite definido"
    }
    private fun trendCard(month:String):LinearLayout=card().apply {
        addView(text("Evolución del dinero",18f,true));addView(gap(5));addView(text("Ingresos y gastos netos · Quetzales",12f,false,muted));addView(gap(14))
        addView(segments(listOf("3 meses","6 meses","12 meses"),listOf(3,6,12).indexOf(chartMonths)){
            chartMonths=listOf(3,6,12)[it];render(true)
        });addView(gap(10))
        addView(segments(listOf("Líneas","Barras"),if(chartLines)0 else 1){chartLines=it==0;render(true)})
        addView(gap(14))
        if(amountsHidden()) addView(text("Gráfica oculta. Pulsa el ojo para mostrarla.",13f,false,muted))
        else {
            val reports=ReportMath.monthsEndingAt(month,chartMonths).map{db.monthReport(it)}
            addView(FinanceTrendView(this@MainActivity,reports,month,chartLines){selectedMonth=it;go("Meses")},LinearLayout.LayoutParams(-1,dp(230)))
            val legend=LinearLayout(this@MainActivity).apply{gravity=Gravity.CENTER}
            legend.addView(pill("Ingresos",green,softGreen));legend.addView(View(this@MainActivity),LinearLayout.LayoutParams(dp(10),1));legend.addView(pill("Gastos",red,0xffffedf0.toInt()))
            addView(legend);addView(gap(10));addView(text("Toca un mes para explorarlo. — indica que no hay registros; las líneas se interrumpen en esos meses.",11f,false,muted))
            addView(gap(10));addView(miniButton("Ver cifras del período"){
                alert("Ingresos y gastos",reports.joinToString("\n\n"){
                    "${monthLabel(it.ym)}\nIngresos ${money(it.income)} · Gastos netos ${money(it.expenses)}"+if(it.count==0L)"\nSin movimientos registrados" else ""
                })
            })
        }
    }
    private fun pulseCard(month:String) {
        val daily=readModel.dailyNet(month).take(FinanceAnalysis.chartDays(month))
        val c=card()
        c.addView(text("El ritmo de tu mes",18f,true));c.addView(gap(5));c.addView(text("Gasto neto acumulado por día",12f,false,muted));c.addView(gap(12))
        if(amountsHidden())c.addView(text("Gráfica oculta. Pulsa el ojo para mostrarla.",13f,false,muted))
        else if(db.monthReport(month).count==0L)c.addView(text("Este mes aún no tiene movimientos registrados.",14f,false,muted))
        else {
            c.addView(ExpensePulseView(this,daily),LinearLayout.LayoutParams(-1,dp(160)))
            c.addView(gap(6));c.addView(row("Acumulado al día ${daily.size}",money(daily.sum())))
            c.addView(gap(8));c.addView(text("Incluye devoluciones. Solo registros, sin proyectar gastos futuros.",11f,false,muted))
        };pageBody.addView(c)
    }
    private fun categoryCard(month:String,compact:Boolean=false) {
        sectionTitle("¿Dónde se va tu dinero?")
        val cats=db.monthCategories(month);val total=cats.sumOf{it.second.coerceAtLeast(0)}
        val c=card()
        if(cats.isEmpty()) {
            c.addView(iconBubble("chart"));c.addView(gap(12));c.addView(text("Tu distribución aparecerá aquí",16f,true));c.addView(gap(5))
            c.addView(text("Clasifica tus compras y descubre tus categorías principales.",13f,false,muted))
        } else {
            if(!amountsHidden() && total>0) {
                val chartRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
                chartRow.addView(CategoryDonutView(this,cats),LinearLayout.LayoutParams(dp(152),dp(160)))
                val legend=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
                cats.take(3).forEachIndexed{i,(name,amount)->
                    legend.addView(text("●  $name",12f,true,FinanceDesign.categoryColors[i%FinanceDesign.categoryColors.size]))
                    legend.addView(text(if(amount>0) "${ReportMath.share(amount,total)}% del gasto" else "Devolución neta",11f,false,muted));legend.addView(gap(10))
                }
                chartRow.addView(legend,LinearLayout.LayoutParams(0,-2,1f));c.addView(chartRow);c.addView(divider())
            }
            cats.take(if(compact)3 else Int.MAX_VALUE).forEachIndexed {i,(name,amount)->
                val item=LinearLayout(this).apply {
                    gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(9),0,dp(9));minimumHeight=dp(54)
                    addView(iconBubble(FinanceDesign.categoryIcon(name),FinanceDesign.categoryColors[i%FinanceDesign.categoryColors.size],0xfff1f5f8.toInt(),36))
                    addView(text(name,13f,true).apply{setPadding(dp(10),0,dp(6),0)},LinearLayout.LayoutParams(0,-2,1f))
                    addView(text(money(amount),14f,true))
                    contentDescription="$name. ${money(amount)}. Ver movimientos";touch(this){categoryDetails(month,name)}
                };c.addView(item)
            }
            c.addView(gap(8));c.addView(text("Dona: proporción del gasto neto positivo. Las devoluciones se descuentan de cada categoría.",11f,false,muted))
            if(compact){c.addView(gap(12));c.addView(miniButton("Explorar el reporte completo"){selectedMonth=month;go("Meses")})}
        };pageBody.addView(c)
    }
    private fun transactionRow(t:FinanceDb.Tx):LinearLayout {
        val positive=t.kind in listOf("INCOME","PENDING_CREDIT","REFUND","TRANSFER_IN")
        val transfer=t.kind in listOf("TRANSFER_IN","TRANSFER_OUT")
        val pending=t.kind in listOf("PENDING_DEBIT","PENDING_CREDIT")
        val tint=if(transfer)muted else if(positive)green else red
        return LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;background=drawable(Color.WHITE,18);setPadding(dp(13),dp(14),dp(13),dp(14))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)}
            addView(iconBubble(if(pending)"clock" else if(transfer)"transfer" else if(t.kind=="INCOME")"down" else FinanceDesign.categoryIcon(t.category),tint,if(positive)softGreen else 0xfff3f5f9.toInt(),42))
            val details=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(11),0,dp(8),0)
                addView(text(t.merchant,13f,true).apply{maxLines=2})
                addView(gap(4));addView(text(if(pending)"Por revisar" else t.category,11f,false,if(pending)red else muted))
            };addView(details,LinearLayout.LayoutParams(0,-2,1f))
            val values=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.END
                addView(text((if(positive)"+" else "−")+money(t.cents),14f,true,tint));addView(gap(4))
                addView(text(SimpleDateFormat("dd MMM",Locale("es","GT")).format(Date(t.whenMs)),10.5f,false,muted))
            };addView(values);contentDescription="${t.merchant}. ${kindLabel(t.kind)}. ${money(t.cents)}. Revisar movimiento";touch(this){txDetail(t)}
        }
    }

    private fun home(){
        pageTitle("Tu dinero, con claridad.",monthLabel(FinanceDb.ym()))
        val accounts=db.accounts();val unknown=accounts.count{it.opening==null}
        val known=accounts.mapNotNull{db.accountBalance(it)}.sum();val report=db.monthReport(FinanceDb.ym())
        val hero=card().apply{
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xff174c54.toInt(),0xff102b38.toInt())).apply{cornerRadius=dp(24).toFloat()}
            setPadding(dp(22),dp(20),dp(22),dp(20))
        }
        val line=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        line.addView(text("SALDO CONOCIDO",11f,true,0xffb8dbd5.toInt()),LinearLayout.LayoutParams(0,-2,1f))
        line.addView(iconBubble("wallet",0xff8fe6d2.toInt(),0x22ffffff,34));hero.addView(line)
        hero.addView(gap(9))
        hero.addView(text(if(accounts.isEmpty()||unknown==accounts.size)"—" else money(known),34f,true,Color.WHITE).apply {
            setSingleLine(true);setAutoSizeTextTypeUniformWithConfiguration(20,34,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        })
        hero.addView(gap(8));hero.addView(text(when {
            accounts.isEmpty()->"Configura tu primera cuenta para comenzar"
            unknown==accounts.size->"Configura tus saldos reales para ver el total"
            unknown>0->"Total parcial · $unknown cuenta(s) sin saldo"
            else->"${accounts.size} cuenta(s) · Saldo estimado de tus registros"
        },12f,false,0xffc0dcd9.toInt()))
        hero.addView(gap(16));hero.addView(text("Balance del mes  ${money(report.income-report.expenses)}",12f,true,0xff91ead0.toInt()))
        touch(hero){go("Cuentas")};hero.contentDescription="Resumen de cuentas. Abrir Cuentas";pageBody.addView(hero)
        pageBody.addView(metricPair(metric("Ingresos del mes",report.income,"down",green),metric("Gastos netos",report.expenses,"up",red)))
        pageBody.addView(quickActions(listOf(QuickAction("Agregar","add"){manualDialog()},QuickAction("Actualizar","refresh"){refreshRecentSms()},QuickAction("Presupuestos","budget"){go("Presupuestos")},QuickAction("Pagos","calendar"){go("Pagos")})))
        if(report.pendingCount>0){
            pageBody.addView(actionTile("clock","${report.pendingCount} ${if(report.pendingCount==1L)"movimiento por revisar" else "movimientos por revisar"}","Dales una categoría y mejora tu panorama"){
                movementFilter="Por revisar";movementQuery="";go("Movimientos")
            });pageBody.addView(gap(14))
        }
        val spotlight=actionTile("spark","Dale contexto a tus números","Explora tu mes con el asistente local"){go("Asistente")}
        spotlight.background=drawable(0xffeeebfa.toInt(),20);pageBody.addView(spotlight);pageBody.addView(gap(18))
        pulseCard(FinanceDb.ym())
        categoryCard(FinanceDb.ym(),true)
        val budgets=db.budgets().take(2)
        if(budgets.isNotEmpty()){
            sectionTitle("Tus límites, a la vista")
            val c=card();budgets.forEachIndexed {i,b ->
                val used=db.categorySpent(b.category);c.addView(row(b.category,"${money(used)} / ${money(b.cents)}"))
                if(!amountsHidden()) {c.addView(gap(7));c.addView(meter(used,b.cents,if(used>b.cents)red else green))}
                if(i<budgets.lastIndex)c.addView(divider())
            };c.addView(gap(12));c.addView(miniButton("Ver presupuestos"){go("Presupuestos")});pageBody.addView(c)
        }
        sectionTitle("Últimos movimientos")
        val recent=db.transactions(3)
        if(recent.isEmpty())pageBody.addView(actionTile("add","Tu historia empieza aquí","Registra un movimiento o importa tus SMS"){manualDialog()})
        else recent.forEach{pageBody.addView(transactionRow(it))}
        pageBody.addView(gap(6));pageBody.addView(miniButton("Ver todos los movimientos"){movementFilter="Todos";movementQuery="";go("Movimientos")})
    }

    private fun accounts(){
        pageTitle("Todo en un lugar.","Tus cuentas, tu efectivo y sus saldos estimados.")
        pageBody.addView(quickActions(listOf(QuickAction("Agregar","add"){addAccountDialog()},QuickAction("Transferir","transfer"){transferDialog()},QuickAction("Respaldo","download"){backupMenu()},QuickAction("Conciliar","shield"){reconciliationDialog()})))
        val accounts=db.accounts()
        if(accounts.isEmpty())pageBody.addView(actionTile("wallet","Agrega tu primera cuenta","Puedes dejar el saldo pendiente si no lo conoces"){addAccountDialog()})
        accounts.forEachIndexed {i,a ->
            val tint=FinanceDesign.categoryColors[i%FinanceDesign.categoryColors.size]
            val c=card();val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            top.addView(iconBubble("wallet",tint,0xfff0f4f8.toInt(),42))
            val labels=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(11),0,0,0);addView(text(a.bank,13f,true));addView(text(a.type,11f,false,muted))}
            top.addView(labels,LinearLayout.LayoutParams(0,-2,1f));top.addView(pill(accountLabel(a.last4),muted,0xfff0f4f8.toInt()));c.addView(top)
            c.addView(gap(18));c.addView(text(a.name,14f,false,muted));c.addView(gap(5))
            c.addView(text(db.accountBalance(a)?.let{money(it)}?:"Saldo por configurar",25f,true,if(a.opening==null)muted else ink))
            c.addView(gap(16));c.addView(miniButton("Actualizar saldo real"){balanceDialog(a)});pageBody.addView(c)
        }
        pageBody.addView(text("El saldo real que registres será el nuevo punto de partida. Los movimientos anteriores no se descuentan otra vez.",12f,false,muted))
        pageBody.addView(gap(12));pageBody.addView(miniButton("Ver cobertura del historial"){historyCoverageDialog()})
    }

    private fun addAccountDialog(){
        val banks=listOf("Banrural","Banco Industrial","G&T Continental","BAC","Promerica","Bantrab","Efectivo","Otro")
        val bank=dropdown(banks);val name=edit("Nombre: Ahorros, Principal...")
        val last=edit("Últimos 4 dígitos (opcional)")
        last.inputType=InputType.TYPE_CLASS_NUMBER
        val type=dropdown(listOf("Monetaria","Ahorro","Tarjeta","Efectivo","Otro"))
        val balance=edit("Saldo actual en Q (opcional)",numeric=true)
        val view=block(text("Banco",13f),bank,name,last,text("Tipo de cuenta",13f),type,balance)
        AlertDialog.Builder(this).setTitle("Nueva cuenta").setView(view).setNegativeButton("Cancelar",null)
            .setPositiveButton("Guardar"){_,_->
                val b=FinanceDb.parseAmount(balance.text.toString())
                if(name.text.isBlank()||(balance.text.isNotBlank()&&b==null)){toast("Escribe un nombre y un saldo válido, o deja el saldo pendiente.");return@setPositiveButton}
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
        pageTitle("Cada movimiento cuenta.","Encuentra, revisa y organiza tu historial.")
        val actions=LinearLayout(this).apply {
            addView(button("Agregar movimiento"){manualDialog()},LinearLayout.LayoutParams(0,-2,1f))
            addView(iconButton("refresh","Actualizar movimientos"){refreshRecentSms()})
        };pageBody.addView(actions);pageBody.addView(gap(14))
        val search=edit("Buscar comercio, categoría o cuenta",movementQuery).apply {
            setCompoundDrawablesRelative(FinanceIcon("search",muted,dp(19)),null,null,null);compoundDrawablePadding=dp(10)
            contentDescription="Buscar movimientos por comercio, categoría o cuenta"
        };pageBody.addView(search);pageBody.addView(gap(12))
        val filters=listOf("Todos","Por revisar","Gastos","Ingresos","Traslados")
        val filterRow=LinearLayout(this)
        val scroller=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(filterRow)}
        pageBody.addView(scroller);pageBody.addView(gap(13))
        val resultText=text("",11f,false,muted);pageBody.addView(resultText);pageBody.addView(gap(12))
        val history=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};pageBody.addView(history)
        val all=db.transactions(5000)
        fun updateHistory() {
            history.removeAllViews()
            val query=BankParser.fold(movementQuery.trim())
            val filtered=all.filter{t ->
                val typeMatches=when(movementFilter){"Por revisar"->t.kind in listOf("PENDING_DEBIT","PENDING_CREDIT");"Gastos"->t.kind in listOf("EXPENSE","REFUND");"Ingresos"->t.kind=="INCOME";"Traslados"->t.kind in listOf("TRANSFER_IN","TRANSFER_OUT");else->true}
                typeMatches&&(query.isEmpty()||BankParser.fold("${t.merchant} ${t.category} ${t.accountLast4}").contains(query))
            }
            resultText.text="${filtered.size} ${if(filtered.size==1)"resultado" else "resultados"} · últimos ${all.size} registros (máx. 5,000)"
            if(filtered.isEmpty())history.addView(card().apply {
                addView(iconBubble("search"));addView(gap(12));addView(text(if(all.isEmpty())"Comienza tu historial" else "Sin coincidencias",17f,true))
                addView(gap(5));addView(text(if(all.isEmpty())"Agrega un movimiento o importa tus SMS desde Herramientas." else "Prueba otra búsqueda o selecciona Todos.",13f,false,muted))
            })
            var previousDay="";val date=SimpleDateFormat("dd MMM yyyy",Locale("es","GT"))
            filtered.take(movementLimit).forEach{t->
                val day=date.format(Date(t.whenMs))
                if(day!=previousDay){history.addView(gap(8));history.addView(text(day,12f,true,muted));history.addView(gap(9));previousDay=day}
                history.addView(transactionRow(t))
            }
            if(filtered.size>movementLimit)history.addView(miniButton("Mostrar 80 más"){movementLimit+=80;updateHistory()})
        }
        fun updateFilters(){
            filterRow.removeAllViews()
            filters.forEach { filter ->
                val chip=text(filter,12f,true,if(movementFilter==filter)Color.WHITE else muted).apply {
                    gravity=Gravity.CENTER;minHeight=dp(44);setPadding(dp(15),dp(10),dp(15),dp(10))
                    background=drawable(if(movementFilter==filter)green else Color.WHITE,13);isSelected=movementFilter==filter
                    touch(this){movementFilter=filter;movementLimit=80;updateFilters();updateHistory()}
                };filterRow.addView(chip,LinearLayout.LayoutParams(-2,-2).apply{rightMargin=dp(7)})
            }
        }
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){movementQuery=s?.toString().orEmpty();movementLimit=80;updateHistory()}
            override fun afterTextChanged(s:Editable?){}
        });updateFilters();updateHistory()
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
        pageTitle("Lo que viene.","Tus compromisos recurrentes, sin perderlos de vista.")
        val scheduled=db.bills();val owed=scheduled.filter{it.paidYm!=FinanceDb.ym()}
        pageBody.addView(metricPair(metric("Por pagar",owed.sumOf{it.cents},"calendar",red),metric("Marcado pagado",scheduled.filter{it.paidYm==FinanceDb.ym()}.sumOf{it.cents},"check",green)))
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
            c.addView(gap(5));c.addView(text(if(paid)"Pagado este mes" else "Pendiente · ${bill.category}",13f,false,if(paid)green else red))
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
        pageTitle("Haz espacio a lo importante.","Tus límites mensuales por categoría.")
        pageBody.addView(button("+ Asignar presupuesto mensual"){addBudgetDialog()})
        pageBody.addView(gap(15))
        val all=db.budgets()
        if(all.isEmpty())pageBody.addView(text("Define tus límites: Q600 para gasolina, Q800 para comida, etc.",14f,false,muted))
        all.forEach{budget->
            val spent=db.categorySpent(budget.category)
            val left=budget.cents-spent
            val c=card()
            val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            header.addView(iconBubble(FinanceDesign.categoryIcon(budget.category)))
            header.addView(text(budget.category,16f,true).apply{setPadding(dp(11),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
            header.addView(pill(if(left<0)"Superado" else "En control",if(left<0)red else green,if(left<0)0xffffedf0.toInt() else softGreen));c.addView(header)
            c.addView(gap(18));c.addView(text(money(spent),26f,true));c.addView(gap(4));c.addView(text("de ${money(budget.cents)} asignados",12f,false,muted))
            if(!amountsHidden()){c.addView(gap(16));c.addView(meter(spent,budget.cents,if(left<0)red else green))}
            c.addView(gap(12));c.addView(row(if(left<0)"Exceso" else "Margen disponible",money(kotlin.math.abs(left)),if(left<0)red else green))
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
        val hero=card().apply {
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xffeeeafa.toInt(),0xffe8f5f4.toInt())).apply{cornerRadius=dp(24).toFloat()}
        }
        hero.addView(iconBubble("spark",FinanceDesign.purple,Color.WHITE,48));hero.addView(gap(15))
        hero.addView(text("Pregúntale a tus números.",27f,true));hero.addView(gap(8))
        hero.addView(text("Una mirada más completa a tus gastos, tus límites y lo que viene.",14f,false,muted));hero.addView(gap(13))
        hero.addView(pill("Privado · Sin internet",FinanceDesign.purple,Color.WHITE));pageBody.addView(hero)
        pageBody.addView(text("Consultando ${monthLabel(selectedMonth)}",12f,true,muted));pageBody.addView(gap(13))
        val report=db.monthReport(selectedMonth)
        pageBody.addView(metricPair(metric("Balance del mes",report.income-report.expenses,"chart",green),metric("Gasto neto",report.expenses,"up",red)))
        sectionTitle("Empieza por una pregunta")
        listOf(
            listOf("Resumen del mes","Comparar meses"),
            listOf("Mis presupuestos","Comercios principales"),
            listOf("Pagos pendientes","Mayor compra")
        ).forEach { options ->
            val line=LinearLayout(this)
            options.forEachIndexed{i,label ->
                val action=miniButton(label){askAssistant(label)}
                action.minimumHeight=dp(58)
                line.addView(action,LinearLayout.LayoutParams(0,-2,1f).apply{if(i==0)rightMargin=dp(8)})
            };pageBody.addView(line);pageBody.addView(gap(8))
        }
        pageBody.addView(gap(4));pageBody.addView(actionTile("budget","Simular un ahorro","Explora un monto sin cambiar tus registros"){savingsSimulation()})
        pageBody.addView(gap(18))
        if(assistantQuestions.isEmpty()) {
            val intro=card();intro.addView(text("Puedo ayudarte a explorar",15f,true));intro.addView(gap(7))
            intro.addView(text("• ¿Cuánto gasté en comida el mes pasado?\n• ¿Qué cambió en octubre?\n• ¿Dónde gasté más en 2026-09?\n• ¿Cuál fue mi mayor compra?",13f,false,muted))
            pageBody.addView(intro)
        } else {
            sectionTitle("Tu conversación")
            assistantQuestions.forEach { question ->
                val bubble=card().apply {background=drawable(0xffe9f3f1.toInt(),18);setPadding(dp(16),dp(13),dp(16),dp(13))}
                bubble.addView(text(question,14f,true));pageBody.addView(bubble)
                val answer=card();val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
                head.addView(iconBubble("spark",FinanceDesign.purple,0xfff0edfc.toInt(),32));head.addView(text("Asistente",12f,true,FinanceDesign.purple).apply{setPadding(dp(9),0,0,0)});answer.addView(head)
                answer.addView(gap(12));answer.addView(text(analysisReply(question),14f));pageBody.addView(answer)
            }
            pageBody.addView(miniButton("Limpiar conversación"){assistantQuestions.clear();render(true)});pageBody.addView(gap(12))
        }
        pageBody.addView(text("Asistente basado en reglas locales, sin IA externa. La conversación dura esta sesión.",11f,false,muted))
    }
    private fun assistantComposer():LinearLayout=LinearLayout(this).apply {
        gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(10),dp(12),dp(10));setBackgroundColor(Color.WHITE)
        val question=edit("Pregunta por tus finanzas…",assistantDraft).apply {
            contentDescription="Escribe una pregunta para el asistente"
            filters=arrayOf(android.text.InputFilter.LengthFilter(200))
            background=drawable(bg,15);imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){assistantDraft=s?.toString().orEmpty()}
                override fun afterTextChanged(s:Editable?){}
            })
        }
        fun send() {
            val query=question.text.toString().trim()
            if(query.isBlank())toast("Escribe una pregunta para comenzar") else {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(question.windowToken,0)
                askAssistant(query)
            }
        }
        question.setOnEditorActionListener {_,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND){send();true}else false}
        addView(question,LinearLayout.LayoutParams(0,-2,1f));addView(View(this@MainActivity),LinearLayout.LayoutParams(dp(10),1))
        addView(iconBubble("send",Color.WHITE,green,48).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="Consultar mis finanzas";touch(this){send()}
        })
    }
    private fun askAssistant(question:String) {
        assistantDraft=""
        assistantQuestions.add(question.take(200))
        if(assistantQuestions.size>8)assistantQuestions.removeAt(0)
        scrollToConversation=true;render()
    }
    private fun analysisReply(question:String):String {
        val q=BankParser.fold(question)
        val month=FinanceAnalysis.questionMonth(question,selectedMonth)
        val report=db.monthReport(month);val cats=db.monthCategories(month)
        val title=monthLabel(month)
        val pending=if(report.pendingCount>0)"\n\n${report.pendingCount} ${if(report.pendingCount==1L)"movimiento por revisar puede" else "movimientos por revisar pueden"} cambiar este análisis." else ""
        val category=categories.firstOrNull{BankParser.fold(it) in q} ?: if("cine" in q)"Entretenimiento" else null
        val answer=when {
            q.contains("compar")||q.contains("cambio")||q.contains("diferencia") -> {
                val prev=YearMonth.parse(month).minusMonths(1).toString();val prior=db.monthReport(prev)
                if(report.count==0L||prior.count==0L)"No hay registros suficientes en ambos meses para comparar $title con ${monthLabel(prev)}."
                else {
                    val difference=report.expenses-prior.expenses
                    "$title frente a ${monthLabel(prev)}\n\nGasto neto: ${money(report.expenses)}\nGasto anterior: ${money(prior.expenses)}\nDiferencia: ${if(difference>=0)"+" else "−"}${money(kotlin.math.abs(difference))}"+
                        (if(!amountsHidden())ReportMath.changePercent(report.expenses,prior.expenses)?.let{" ($it)"}.orEmpty() else "")+
                        if(month==FinanceDb.ym())"\n\nEl mes actual está en curso y el anterior es un mes completo; la comparación tiene distinta duración." else ""
                }
            }
            q.contains("presupuest")||q.contains("limite") -> {
                val budgets=db.budgets()
                if(budgets.isEmpty())"Todavía no tienes límites por categoría. Puedes crearlos en Presupuestos."
                else "Límites actuales aplicados a $title:\n\n"+budgets.joinToString("\n\n") {
                    val spent=cats.firstOrNull{c->c.first==it.category}?.second?:0L;val left=it.cents-spent
                    "${it.category}: ${money(spent)} de ${money(it.cents)}. "+if(left<0)"Exceso ${money(-left)}." else "Margen ${money(left)}."
                }+"\n\nLos límites no tienen historial de versiones; se usan los que tienes definidos hoy."
            }
            q.contains("pago")||q.contains("pagar")||q.contains("debo") -> {
                if(month!=FinanceDb.ym())"Los recordatorios solo conservan su estado de pago más reciente. Consulta Pagos para revisar el mes actual; no puedo reconstruir obligaciones históricas."
                else {
                    val owed=db.bills().filter{it.paidYm!=FinanceDb.ym()}
                    if(owed.isEmpty())"No hay pagos recurrentes marcados como pendientes este mes. Esto no descarta obligaciones que no hayas registrado."
                    else "Pagos pendientes: ${money(owed.sumOf{it.cents})}\n\n"+owed.joinToString("\n"){"• Día ${it.day}: ${it.title} · ${money(it.cents)}"}+"\n\nMarcar un recordatorio como pagado no registra otro gasto."
                }
            }
            q.contains("por revisar")||q.contains("pendiente") ->
                "$title: ${report.pendingCount} movimientos por revisar.\n\nDébitos: ${money(report.pendingDebits)}\nAbonos: ${money(report.pendingCredits)}\n\nEstos importes están excluidos de los ingresos y gastos confirmados. Revísalos en Movimientos."
            q.contains("mayor compra")||q.contains("mayor gasto")||q.contains("compra mas") -> {
                val purchase=readModel.largestPurchase(month)
                purchase?.let{"Mayor compra confirmada de $title:\n\n${it.first}\n${money(it.second)}\n\nSe compara cada compra individual, antes de descontar devoluciones."}?:"No hay compras confirmadas en $title."
            }
            q.contains("comercio") -> {
                val merchants=readModel.topMerchants(month)
                if(merchants.isEmpty())"No hay comercios con gasto neto positivo en $title."
                else "Comercios con mayor gasto neto en $title:\n\n"+merchants.joinToString("\n"){"• ${it.first}: ${money(it.second)}"}+"\n\nAgrupados por el nombre registrado; las devoluciones se descuentan cuando usan ese mismo nombre."
            }
            category!=null -> {
                val spent=cats.firstOrNull{it.first==category}?.second?:0L
                "$category en $title: ${money(spent)} de gasto neto.\n\nIncluye compras menos devoluciones clasificadas en esa categoría. Los débitos sin confirmar quedan aparte."
            }
            q.contains("saldo")||q.contains("disponible")||q.contains("tengo") -> {
                val all=db.accounts();val known=all.mapNotNull{db.accountBalance(it)};val missing=all.count{it.opening==null}
                if(known.isEmpty())"No hay saldos reales configurados. Registra el saldo actual de cada cuenta en Cuentas; no se puede deducir tu saldo bancario a partir de SMS incompletos."
                else "Saldo estimado conocido: ${money(known.sum())}."+(if(missing>0)"\n\nFaltan saldos de $missing cuenta(s); el total es parcial." else "")+"\n\nEs el saldo actual calculado desde tus puntos de partida, no un saldo histórico ni una consulta al banco."
            }
            q.contains("donde")||q.contains("categoria") -> {
                if(cats.isEmpty())"No hay categorías de gasto registradas en $title."
                else "Distribución de gasto neto en $title:\n\n"+cats.take(5).joinToString("\n"){"• ${it.first}: ${money(it.second)}"}
            }
            q.contains("ahorr") ->
                "El balance confirmado de $title es ${money(report.income-report.expenses)}: ingresos menos gastos netos.\n\nEs un cálculo de registros, no una cantidad garantizada para ahorrar. Usa Simular un ahorro para explorar un monto sin cambiar tus datos."
            else -> {
                val largest=cats.firstOrNull{it.second>0}
                "$title\n\nIngresos confirmados: ${money(report.income)}\nGastos netos: ${money(report.expenses)}\nBalance del mes: ${money(report.income-report.expenses)}\nDevoluciones: ${money(report.refunds)}"+
                    (largest?.let{"\n\nCategoría principal: ${it.first}, ${money(it.second)}."}?:"\n\nSin categorías con gasto neto positivo.")+"\n\nPuedes preguntar por comercios, categorías, presupuestos, pagos, tu mayor compra o comparar meses. También puedes indicar un mes, como septiembre 2026."
            }
        }
        return answer+pending
    }
    private fun savingsSimulation() {
        val amount=edit("Monto que quieres apartar en Q",numeric=true)
        AlertDialog.Builder(this).setTitle("Simular un ahorro")
            .setMessage("Se calcula sobre el balance confirmado de ${monthLabel(selectedMonth)}. No modifica cuentas ni movimientos.")
            .setView(block(amount)).setNegativeButton("Cancelar",null).setPositiveButton("Simular"){_,_->
                val target=FinanceDb.parseAmount(amount.text.toString())
                if(target==null||target<=0){toast("Escribe un monto mayor que cero");return@setPositiveButton}
                val r=db.monthReport(selectedMonth);val balance=r.income-r.expenses
                alert("Tu escenario","Balance confirmado: ${money(balance)}\nMonto a apartar: ${money(target)}\nResultado aritmético: ${money(balance-target)}\n\nEsto no verifica disponibilidad bancaria ni descuenta obligaciones futuras o movimientos pendientes. No se guardó ningún cambio.")
            }.show()
    }
}
