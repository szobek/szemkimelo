package hu.szobek.szemkimelo

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * A háttérben futó szolgáltatás: az ébresztők (szünet, ivás, ebéd) itt futnak le.
 * Állandó értesítéssel fut, hogy a rendszer ne állítsa le.
 */
class ReminderService : Service() {
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: BreakOverlay? = null
    private var ringtone: Ringtone? = null
    private var breakActive = false

    private val finishRunnable = Runnable { finishBreak() }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RESTORE
        startInForeground()

        if (action == ACTION_STOP || (!prefs.running && action != ACTION_START)) {
            stopEverything()
            return START_NOT_STICKY
        }

        val now = System.currentTimeMillis()
        when (action) {
            ACTION_START, ACTION_RESCHEDULE -> {
                prefs.running = true
                scheduleAll()
            }
            ACTION_RESTORE -> updateForeground()
            Scheduler.ACTION_BREAK, ACTION_BREAK_NOW -> handleBreak()
            ACTION_BREAK_FINISHED -> if (breakActive) finishBreak()
            Scheduler.ACTION_WATER -> {
                if (prefs.waterEnabled) {
                    Notifications.water(this)
                    Scheduler.set(this, Scheduler.ACTION_WATER, now + prefs.waterMinutes * 60_000L)
                }
            }
            Scheduler.ACTION_LUNCH -> {
                if (prefs.lunchEnabled) {
                    Notifications.lunch(this)
                    Scheduler.set(
                        this, Scheduler.ACTION_LUNCH,
                        Scheduler.nextLunchMillis(prefs.lunchHour, prefs.lunchMinute)
                    )
                }
            }
            Scheduler.ACTION_LUNCH_SNOOZED -> Notifications.lunch(this)
            ACTION_LUNCH_SNOOZE -> {
                Notifications.cancel(this, Notifications.ID_LUNCH)
                Scheduler.set(this, Scheduler.ACTION_LUNCH_SNOOZED, now + 10 * 60_000L)
            }
            ACTION_LUNCH_DONE -> Notifications.cancel(this, Notifications.ID_LUNCH)
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- ütemezés
    private fun scheduleAll() {
        val now = System.currentTimeMillis()
        dismissBreak()

        prefs.nextBreakAt = now + prefs.workMinutes * 60_000L
        Scheduler.set(this, Scheduler.ACTION_BREAK, prefs.nextBreakAt)

        if (prefs.waterEnabled) {
            Scheduler.set(this, Scheduler.ACTION_WATER, now + prefs.waterMinutes * 60_000L)
        } else {
            Scheduler.cancel(this, Scheduler.ACTION_WATER)
        }
        if (prefs.lunchEnabled) {
            Scheduler.set(
                this, Scheduler.ACTION_LUNCH,
                Scheduler.nextLunchMillis(prefs.lunchHour, prefs.lunchMinute)
            )
        } else {
            Scheduler.cancel(this, Scheduler.ACTION_LUNCH)
        }
        Scheduler.cancel(this, Scheduler.ACTION_LUNCH_SNOOZED)
        updateForeground()
    }

    // ---------------------------------------------------------------- szünet
    /** Fel van-e oldva és be van-e kapcsolva a kijelző (ilyenkor jelenhet meg ablak más appok felett). */
    private fun deviceReady(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun handleBreak() {
        if (breakActive) return
        Scheduler.cancel(this, Scheduler.ACTION_BREAK)
        startBreak()
    }

    private fun startBreak() {
        breakActive = true
        prefs.nextBreakAt = 0
        updateForeground()
        if (prefs.vibrate) vibrate()
        if (prefs.sound) playSound()

        if (!deviceReady()) {
            // Zárolt képernyő vagy kikapcsolt kijelző: teljes képernyős értesítés indítja a szünet-képernyőt
            // a zárolt képernyő fölött (felkapcsolja a kijelzőt is). Az Activity lejárati biztosítékkal fut.
            Notifications.breakAlert(
                this, prefs.breakSeconds, prefs.workMinutes,
                fullScreen = Notifications.canUseFullScreenIntent(this)
            )
            handler.postDelayed(finishRunnable, (prefs.breakSeconds + 20) * 1000L)
            return
        }

        val shown = Settings.canDrawOverlays(this) && runCatching {
            overlay = BreakOverlay(this).also {
                it.show(prefs.breakSeconds, prefs.workMinutes) { finishBreak() }
            }
        }.isSuccess
        if (!shown) {
            // Nincs engedély (vagy a rendszer elutasította az ablakot): felugró értesítéssel jelzünk.
            overlay?.dismiss()
            overlay = null
            Notifications.breakAlert(this, prefs.breakSeconds, prefs.workMinutes, fullScreen = false)
            handler.postDelayed(finishRunnable, prefs.breakSeconds * 1000L)
        }
    }

    private fun finishBreak() {
        handler.removeCallbacks(finishRunnable)
        dismissBreak()
        if (!prefs.running) return
        prefs.nextBreakAt = System.currentTimeMillis() + prefs.workMinutes * 60_000L
        Scheduler.set(this, Scheduler.ACTION_BREAK, prefs.nextBreakAt)
        updateForeground()
    }

    private fun dismissBreak() {
        handler.removeCallbacks(finishRunnable)
        overlay?.dismiss()
        overlay = null
        ringtone?.stop()
        ringtone = null
        Notifications.cancel(this, Notifications.ID_BREAK)
        breakActive = false
    }

    private fun playSound() {
        runCatching {
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(
                this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                play()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
    }

    // ---------------------------------------------------------------- előtér-értesítés
    private fun startInForeground() {
        val n = Notifications.foreground(this, prefs.nextBreakAt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifications.ID_FOREGROUND, n)
        }
    }

    private fun updateForeground() {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_FOREGROUND, Notifications.foreground(this, prefs.nextBreakAt))
    }

    private fun stopEverything() {
        prefs.running = false
        prefs.nextBreakAt = 0
        Scheduler.cancelAll(this)
        dismissBreak()
        handler.removeCallbacksAndMessages(null)
        listOf(Notifications.ID_WATER, Notifications.ID_LUNCH, Notifications.ID_BREAK)
            .forEach { Notifications.cancel(this, it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ringtone?.stop()
        overlay?.dismiss()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "hu.szobek.szemkimelo.action.START"
        const val ACTION_STOP = "hu.szobek.szemkimelo.action.STOP"
        const val ACTION_RESCHEDULE = "hu.szobek.szemkimelo.action.RESCHEDULE"
        const val ACTION_RESTORE = "hu.szobek.szemkimelo.action.RESTORE"
        const val ACTION_BREAK_NOW = "hu.szobek.szemkimelo.action.BREAK_NOW"
        const val ACTION_BREAK_FINISHED = "hu.szobek.szemkimelo.action.BREAK_FINISHED"
        const val ACTION_LUNCH_SNOOZE = "hu.szobek.szemkimelo.action.LUNCH_SNOOZE"
        const val ACTION_LUNCH_DONE = "hu.szobek.szemkimelo.action.LUNCH_DONE"

        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, ReminderService::class.java).setAction(action)
            )
        }
    }
}
