package hu.szobek.szemkimelo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Pontos ébresztők (AlarmManager) – a telefon alvó módjában is megbízhatóan szólnak. */
object Scheduler {
    const val ACTION_BREAK = "hu.szobek.szemkimelo.action.BREAK"
    const val ACTION_WATER = "hu.szobek.szemkimelo.action.WATER"
    const val ACTION_LUNCH = "hu.szobek.szemkimelo.action.LUNCH"
    const val ACTION_LUNCH_SNOOZED = "hu.szobek.szemkimelo.action.LUNCH_SNOOZED"

    private fun pendingIntent(ctx: Context, action: String): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun set(ctx: Context, action: String, atMillis: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(ctx, action)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactAllowed) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel(ctx: Context, action: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pendingIntent(ctx, action))
    }

    fun cancelAll(ctx: Context) {
        listOf(ACTION_BREAK, ACTION_WATER, ACTION_LUNCH, ACTION_LUNCH_SNOOZED).forEach { cancel(ctx, it) }
    }

    /** A következő ebédidő (ma, ha még nem múlt el, különben holnap). */
    fun nextLunchMillis(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
