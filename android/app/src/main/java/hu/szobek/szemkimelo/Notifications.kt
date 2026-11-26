package hu.szobek.szemkimelo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import java.util.Date

object Notifications {
    private const val CH_SERVICE = "service"
    private const val CH_ALERT = "alerts" // hang nélküli (a hang kikapcsolt állapothoz)
    private const val CH_ALERT_SOUND = "alerts_sound" // az alapértelmezett értesítési hanggal
    private const val CH_BREAK = "break_alert" // a szünet teljes képernyős értesítése (hangot a szolgáltatás játssza)

    const val ID_FOREGROUND = 1
    const val ID_WATER = 2
    const val ID_LUNCH = 3
    const val ID_BREAK = 4

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Futás jelzése", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Állandó értesítés, amíg az emlékeztető be van kapcsolva"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Emlékeztetők (hang nélkül)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ivás és ebéd emlékeztetők hang nélkül"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT_SOUND, "Emlékeztetők (hanggal)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ivás és ebéd emlékeztetők értesítési hanggal"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_BREAK, "Szünet jelzése", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A 20 perc leteltekor megjelenő szünet-képernyő (zárolt képernyőn is)"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /** Android 14-től a teljes képernyős értesítéshez (zárolt képernyős megjelenés) külön engedély kell. */
    fun canUseFullScreenIntent(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getForegroundService(
            ctx, code, Intent(ctx, ReminderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun foreground(ctx: Context, nextBreakAt: Long): Notification {
        val text = if (nextBreakAt > 0) {
            "Következő szünet: " + DateFormat.getTimeFormat(ctx).format(Date(nextBreakAt))
        } else {
            "Szünet folyamatban…"
        }
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Szemkímélő fut")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Szünet most", serviceAction(ctx, ReminderService.ACTION_BREAK_NOW, 1))
            .addAction(0, "Leállítás", serviceAction(ctx, ReminderService.ACTION_STOP, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun alertBuilder(
        ctx: Context, title: String, text: String,
        channel: String = if (Prefs(ctx).sound) CH_ALERT_SOUND else CH_ALERT,
    ) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))

    private fun show(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // nincs értesítési engedély – nem tehetünk többet
        }
    }

    fun water(ctx: Context) = show(
        ctx, ID_WATER,
        alertBuilder(ctx, "💧 Igyál egy pohár vizet!", "Frissítsd fel magad, és mozgasd meg a nyakad.")
            .setTimeoutAfter(30_000)
            .build()
    )

    fun lunch(ctx: Context) = show(
        ctx, ID_LUNCH,
        alertBuilder(ctx, "🍽️ Ebédidő!", "Állj fel, egyél valami jót, és pihentesd a szemed is.")
            .addAction(0, "Rendben", serviceAction(ctx, ReminderService.ACTION_LUNCH_DONE, 3))
            .addAction(0, "Később (10 perc)", serviceAction(ctx, ReminderService.ACTION_LUNCH_SNOOZE, 4))
            .build()
    )

    /**
     * A szünet értesítése. Zárolt képernyőn vagy kikapcsolt kijelzőnél a teljes képernyős értesítés
     * ([fullScreen]) a [BreakActivity]-t indítja a zárolt képernyő fölött. Egyébként felugró
     * értesítés (ha nincs "megjelenítés más appok felett" engedély).
     * A hangot és a rezgést a szolgáltatás kezeli, ezért a csatorna néma.
     */
    fun breakAlert(ctx: Context, seconds: Int, workMinutes: Int, fullScreen: Boolean) {
        val builder = alertBuilder(
            ctx, "👀 Letelt a $workMinutes perc!",
            "Nézz egy ~6 méterre lévő dologra $seconds másodpercig, és pislogj néhányat.",
            channel = CH_BREAK
        ).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(seconds * 1000L)

        if (fullScreen) {
            val activity = PendingIntent.getActivity(
                ctx, 5, Intent(ctx, BreakActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setFullScreenIntent(activity, true)
        }
        show(ctx, ID_BREAK, builder.build())
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }
}
