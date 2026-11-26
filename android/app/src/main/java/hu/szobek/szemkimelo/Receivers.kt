package hu.szobek.szemkimelo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Az ébresztők ide érkeznek, innen továbbítjuk a háttérszolgáltatásnak. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        ReminderService.send(context, action)
    }
}

/** Újraindítás után visszaállítja az ébresztőket (a rendszer törli őket). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).running) {
            ReminderService.send(context, ReminderService.ACTION_RESCHEDULE)
        }
    }
}
