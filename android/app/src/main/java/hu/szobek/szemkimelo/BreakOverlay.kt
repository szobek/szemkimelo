package hu.szobek.szemkimelo

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Teljes képernyős, sötétített visszaszámláló más appok felett (hang nélkül, pulzáló kerettel).
 * Működéséhez a "Megjelenítés más appok felett" engedély kell.
 */
class BreakOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var card: GradientDrawable? = null
    private var counter: TextView? = null
    private var progress: ProgressBar? = null
    private var endAt = 0L
    private var totalMs = 0L
    private var onDone: (() -> Unit)? = null
    private var done = false

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun text(
        value: String, sizeSp: Float, color: Int, bold: Boolean = false,
    ) = TextView(ctx).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun show(seconds: Int, workMinutes: Int, onDone: () -> Unit) {
        this.onDone = onDone
        totalMs = seconds * 1000L
        endAt = SystemClock.elapsedRealtime() + totalMs

        val cardBg = GradientDrawable().apply {
            setColor(BG)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(4), ACCENT)
        }
        card = cardBg

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBg
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        content.addView(text("👀", 56f, Color.WHITE))
        content.addView(
            text("Letelt a $workMinutes perc!", 28f, Color.WHITE, bold = true),
            linear(top = 8)
        )
        content.addView(
            text("Nézz egy ~6 méterre lévő dologra,\nés pislogj néhányat.", 17f, MUTED),
            linear(top = 8)
        )
        counter = text(seconds.toString(), 88f, ACCENT, bold = true).also {
            content.addView(it, linear(top = 12))
        }
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = totalMs.toInt()
            this.progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF22345C.toInt())
        }.also { content.addView(it, linear(top = 12, width = ViewGroup.LayoutParams.MATCH_PARENT)) }

        val skip = text("Kihagyom", 16f, Color.WHITE).apply {
            background = GradientDrawable().apply {
                setColor(0xFF22345C.toInt()); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { finish() }
        }
        content.addView(skip, linear(top = 20))

        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(OVERLAY_BG)
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    finish(); true
                } else false
            }
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                ).apply { setMargins(dp(24), 0, dp(24), 0) }
            )
        }
        root = frame

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(frame, params)
        frame.requestFocus()
        handler.post(tick)
    }

    private fun linear(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private val tick = object : Runnable {
        override fun run() {
            val remaining = endAt - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                finish(); return
            }
            counter?.text = ceil(remaining / 1000.0).toInt().toString()
            progress?.progress = (totalMs - remaining).toInt()

            // Pulzáló keret és szám a figyelemfelkeltéshez.
            val k = ((sin(SystemClock.elapsedRealtime() / 1000.0 * 5) + 1) / 2).toFloat()
            card?.setStroke(dp(4), ColorUtils.blendARGB(BG, GOLD, k))
            counter?.setTextColor(ColorUtils.blendARGB(ACCENT, Color.WHITE, k))
            handler.postDelayed(this, 80)
        }
    }

    private fun finish() {
        if (done) return
        done = true
        dismiss()
        onDone?.invoke()
    }

    /** Eltávolítja az ablakot (többször is hívható). */
    fun dismiss() {
        handler.removeCallbacks(tick)
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        root = null
    }

    private companion object {
        val BG = 0xFF14213D.toInt()
        val ACCENT = 0xFF4CC9F0.toInt()
        val GOLD = 0xFFFFD166.toInt()
        val MUTED = 0xFFA9B4C9.toInt()
        val OVERLAY_BG = 0xE6050A14.toInt()
    }
}
