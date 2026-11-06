package gt.finanzas.privadas

import android.app.Notification
import android.content.Intent
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Sólo guarda SMS bancarios parseables; descarta silenciosamente las demás notificaciones. */
class BankNotificationListener : NotificationListenerService() {
    companion object {
        const val ACTION_MOVEMENTS_CHANGED = "gt.finanzas.privadas.MOVEMENTS_CHANGED"
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if(sbn==null) return
        // Una autorización del sistema no implica sincronización perpetua: respetar el
        // interruptor explícito que la persona puede pausar desde la aplicación.
        if (!getSharedPreferences("sms_sync_prefs", Context.MODE_PRIVATE).getBoolean("auto_sms_enabled", false)) return
        val pkg=sbn.packageName.lowercase()
        // Evitamos capturar contenidos de apps ajenas (WhatsApp, email, etc.).
        val isSms = pkg.contains("messag") || pkg.contains("mms") || pkg.contains("sms")
        if(!isSms) return
        val extras=sbn.notification?.extras ?: return
        val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big=extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val plain=extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val content=big.ifBlank { plain }
        if(content.isBlank())return
        // Admitir texto bancario sin prefijo de marca, pero sólo si un patrón estricto
        // reconoce la cuenta, el monto y una operación válida. No conservar otros SMS.
        val candidates = listOf(
            content,
            if(title.contains("BANRURAL",true)) "BANRURAL: $content" else content,
            if(title.contains("BiMovil",true) || title.contains("Banco Industrial",true)) "BiMovil: $content" else content
        ).distinct()
        val text = candidates.firstOrNull { BankParser.parse(it) != null } ?: return
        // No enviamos el SMS mediante broadcast: la interfaz sólo recibe un aviso vacío.
        val outcome=FinanceDb(applicationContext).use { db ->
            db.importLine(text, sbn.postTime.takeIf{it>0}?:System.currentTimeMillis(),"notification")
        }
        if(outcome=="added") {
            sendBroadcast(Intent(ACTION_MOVEMENTS_CHANGED).setPackage(packageName))
        }
    }
}
