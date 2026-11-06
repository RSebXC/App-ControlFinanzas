package gt.finanzas.privadas

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Calendar

/** Recordatorio diario inexacto. Android puede diferirlo por ahorro de batería. */
object ReminderSchedule {
    const val CHANNEL="finance_reminders"
    fun createChannel(context:Context){
        if(Build.VERSION.SDK_INT >=26) {
            val nm=context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL,"Pagos y presupuestos",NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    fun schedule(context:Context){
        createChannel(context)
        val alarm=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending=PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cal=Calendar.getInstance().apply {set(Calendar.HOUR_OF_DAY,9);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}
        if(cal.timeInMillis<=System.currentTimeMillis())cal.add(Calendar.DAY_OF_MONTH,1)
        alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP,cal.timeInMillis,AlarmManager.INTERVAL_DAY,pending)
    }
}
class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?) {
        if(intent?.action==Intent.ACTION_BOOT_COMPLETED) ReminderSchedule.schedule(context)
    }
}
class ReminderReceiver:BroadcastReceiver(){
    override fun onReceive(context: Context,intent: Intent?) {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        FinanceDb(context).use { db ->
            val now=Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            val pending=db.bills().filter{it.paidYm!=FinanceDb.ym() && it.day<=now+3 }
            val exceeded=db.budgets().filter{it.cents>0 && db.categorySpent(it.category)*100>=it.cents*80}
            if(pending.isEmpty() && exceeded.isEmpty())return
            val parts=mutableListOf<String>()
            if(pending.isNotEmpty())parts+="Pendiente: "+pending.take(3).joinToString { "${it.title} ${FinanceDb.format(it.cents)}" }
            if(exceeded.isNotEmpty())parts+="Presupuesto al 80%: "+exceeded.take(2).joinToString { it.category }
            val n=android.app.Notification.Builder(context,ReminderSchedule.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Mis Finanzas · recordatorio")
                .setContentText(parts.joinToString(" · ").take(150))
                .setStyle(android.app.Notification.BigTextStyle().bigText(parts.joinToString("\n")))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context,1,Intent(context,MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(101,n)
        }
    }
}
