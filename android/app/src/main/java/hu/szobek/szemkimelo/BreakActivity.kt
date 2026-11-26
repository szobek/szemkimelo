package hu.szobek.szemkimelo

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Teljes képernyős szünet-képernyő, ami a zárolt képernyő fölött is megjelenik és felkapcsolja a
 * kijelzőt (mint az ébresztő appok). A szolgáltatás teljes képernyős értesítéssel indítja.
 */
class BreakActivity : Activity() {
    private var content: BreakContent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Teljes képernyő: az állapotsor és a navigációs sáv is el van rejtve.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val prefs = Prefs(this)
        val c = BreakContent(this)
        content = c
        setContentView(
            c.build(prefs.breakSeconds, prefs.workMinutes, BreakContent.SOLID_BG) {
                // Kihagyás vagy lejárat: a szolgáltatás újraindítja a munkaidőt.
                ReminderService.send(this, ReminderService.ACTION_BREAK_FINISHED)
                finish()
            }
        )
        c.start()
    }

    @Deprecated("A vissza gomb kihagyja a szünetet")
    override fun onBackPressed() {
        content?.finish() ?: finish()
    }

    override fun onDestroy() {
        content?.stop()
        super.onDestroy()
    }
}
