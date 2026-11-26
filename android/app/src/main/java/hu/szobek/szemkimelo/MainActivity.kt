package hu.szobek.szemkimelo

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF14213D)
private val CardBg = Color(0xFF1C2D52)
private val Accent = Color(0xFF4CC9F0)
private val Muted = Color(0xFFA9B4C9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Bg, background = Bg, surface = Bg,
                    onSurface = Color.White, onBackground = Color.White
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) { MainScreen() }
            }
        }
    }
}

private fun fmtRemaining(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun openOverlaySettings(ctx: Context) = ctx.startActivity(
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
)

private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/** Kéri az akkumulátor-optimalizálás kikapcsolását (ha a rendszer nem engedi, a listát nyitja meg). */
private fun requestIgnoreBatteryOptimizations(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        )
    } catch (_: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun fmtClock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun openFullScreenIntentSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
        )
    }
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    var running by remember { mutableStateOf(prefs.running) }
    var workMin by remember { mutableIntStateOf(prefs.workMinutes) }
    var breakSec by remember { mutableIntStateOf(prefs.breakSeconds) }
    var lunchOn by remember { mutableStateOf(prefs.lunchEnabled) }
    var lunchH by remember { mutableIntStateOf(prefs.lunchHour) }
    var lunchM by remember { mutableIntStateOf(prefs.lunchMinute) }
    var waterOn by remember { mutableStateOf(prefs.waterEnabled) }
    var waterMin by remember { mutableIntStateOf(prefs.waterMinutes) }
    var sound by remember { mutableStateOf(prefs.sound) }
    var vibrate by remember { mutableStateOf(prefs.vibrate) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nextBreak by remember { mutableLongStateOf(prefs.nextBreakAt) }
    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var canFullScreen by remember { mutableStateOf(Notifications.canUseFullScreenIntent(ctx)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(ctx)) }
    var lastFired by remember { mutableLongStateOf(prefs.lastBreakFired) }
    var lastPlanned by remember { mutableLongStateOf(prefs.lastBreakPlanned) }
    var lastMode by remember { mutableStateOf(prefs.lastBreakMode) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // Az állapotot csak akkor frissítjük, amikor az app látszik (háttérben ne pörögjön feleslegesen).
    val lifecycleOwner = ctx as ComponentActivity
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                nextBreak = prefs.nextBreakAt
                running = prefs.running
                canDraw = Settings.canDrawOverlays(ctx)
                canFullScreen = Notifications.canUseFullScreenIntent(ctx)
                batteryOk = isIgnoringBatteryOptimizations(ctx)
                lastFired = prefs.lastBreakFired
                lastPlanned = prefs.lastBreakPlanned
                lastMode = prefs.lastBreakMode
                delay(500)
            }
        }
    }

    /**
     * Beállítás módosítása után újra kell számolni az ütemezést. Gyors léptetésnél (nyomva tartott
     * gomb) csak a változások megállta után küldjük el, nem minden lépésnél.
     */
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(workMin, waterMin, waterOn, lunchOn, lunchH, lunchM) {
        if (firstRun) {
            firstRun = false
            return@LaunchedEffect
        }
        delay(700)
        if (prefs.running) ReminderService.send(ctx, ReminderService.ACTION_RESCHEDULE)
    }

    fun startReminders() {
        prefs.running = true
        running = true
        ReminderService.send(ctx, ReminderService.ACTION_START)
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startReminders() }

    fun requestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReminders()
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Engedélyek a figyelmeztetéshez") },
            text = {
                Text(
                    buildString {
                        append("Hogy a szünet minden felett és zárolt képernyőn is megjelenjen, két engedély kell:\n")
                        if (!canDraw) append("\n• Megjelenítés más appok felett")
                        if (!canFullScreen) append("\n• Teljes képernyős értesítések")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    if (!canDraw) openOverlaySettings(ctx) else openFullScreenIntentSettings(ctx)
                }) { Text("Beállítás") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    requestNotificationsThenStart()
                }) { Text("Indítás így") }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "👀 Szemkímélő", fontSize = 30.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
        )
        Text(
            "20-20-20 szabály: 20 percenként nézz 20 másodpercig ~6 méterre.",
            color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // --- Állapot
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Következő szünet", color = Muted, fontSize = 14.sp)
                Text(
                    when {
                        !running -> "Leállítva"
                        nextBreak == 0L -> "Szünet…"
                        else -> fmtRemaining(nextBreak - now)
                    },
                    color = Accent, fontSize = 48.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                if (running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            ReminderService.send(ctx, ReminderService.ACTION_BREAK_NOW)
                        }) { Text("Szünet most") }
                        OutlinedButton(onClick = {
                            ReminderService.send(ctx, ReminderService.ACTION_STOP)
                            running = false
                        }) { Text("Leállítás") }
                    }
                    TextButton(onClick = {
                        ReminderService.send(ctx, ReminderService.ACTION_TEST)
                    }) { Text("Teszt: szünet 10 mp múlva") }
                } else {
                    Button(onClick = {
                        if (!canDraw || !canFullScreen) showPermissionDialog = true
                        else requestNotificationsThenStart()
                    }) { Text("Indítás") }
                }
                if (lastFired > 0L) {
                    Spacer(Modifier.height(6.dp))
                    val delay = if (lastPlanned > 0L) ((lastFired - lastPlanned) / 1000) else null
                    Text(
                        buildString {
                            append("Utolsó szünet: ${fmtClock(lastFired)}")
                            if (delay != null) append(" (késés: $delay mp)")
                            if (lastMode.isNotEmpty()) append("\n$lastMode")
                        },
                        color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                }
            }
        }

        // --- Engedélyek a megjelenéshez
        SectionCard("Megjelenés minden felett") {
            PermissionRow(
                granted = canDraw,
                grantedText = "Más appok felett: engedélyezve",
                missingText = "Más appok felett: nincs engedélyezve. Nélküle a szünet csak értesítésként jön.",
                onFix = { openOverlaySettings(ctx) }
            )
            PermissionRow(
                granted = canFullScreen,
                grantedText = "Zárolt képernyőn: engedélyezve",
                missingText = "Zárolt képernyőn: nincs engedélyezve. Nélküle a szünet zárolt telefonon csak értesítésként jön.",
                onFix = { openFullScreenIntentSettings(ctx) }
            )
            PermissionRow(
                granted = batteryOk,
                grantedText = "Akkumulátor-optimalizálás kikapcsolva",
                missingText = "Az akkumulátor-optimalizálás késleltetheti vagy leállíthatja az időzítőt a háttérben.",
                onFix = { requestIgnoreBatteryOptimizations(ctx) }
            )
        }

        // --- Szünet
        SectionCard("Szünet") {
            Stepper("Munka (perc)", workMin, 1, 180, 1) { workMin = it; prefs.workMinutes = it }
            Stepper("Szünet (másodperc)", breakSec, 5, 300, 5) { breakSec = it; prefs.breakSeconds = it }
            SwitchRow("Hangjelzés", sound) { sound = it; prefs.sound = it }
            SwitchRow("Rezgés a szünet elején", vibrate) { vibrate = it; prefs.vibrate = it }
        }

        // --- Ebéd
        SectionCard("Ebéd") {
            SwitchRow("Ebéd emlékeztető", lunchOn) { lunchOn = it; prefs.lunchEnabled = it }
            if (lunchOn) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Időpont", Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m ->
                            lunchH = h; lunchM = m
                            prefs.lunchHour = h; prefs.lunchMinute = m
                        }, lunchH, lunchM, true).show()
                    }) { Text("%02d:%02d".format(lunchH, lunchM)) }
                }
            }
        }

        // --- Ivás
        SectionCard("Ivás") {
            SwitchRow("Ivás emlékeztető", waterOn) { waterOn = it; prefs.waterEnabled = it }
            if (waterOn) {
                Stepper("Időköz (perc)", waterMin, 15, 240, 15) { waterMin = it; prefs.waterMinutes = it }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionCard(title: String? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Accent)
            }
            content()
        }
    }
}

@Composable
private fun PermissionRow(granted: Boolean, grantedText: String, missingText: String, onFix: () -> Unit) {
    if (granted) {
        Text("✓ $grantedText", color = Muted, fontSize = 14.sp)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(missingText, color = Muted, fontSize = 14.sp)
            Button(onClick = onFix) { Text("Engedély megadása") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        StepButton("−", enabled = value > min) { onChange((value - step).coerceAtLeast(min)) }
        Text(
            value.toString(), Modifier.padding(horizontal = 14.dp),
            fontSize = 18.sp, fontWeight = FontWeight.Bold
        )
        StepButton("+", enabled = value < max) { onChange((value + step).coerceAtMost(max)) }
    }
}

/**
 * Léptető gomb: egy érintés egyet lép, nyomva tartva folyamatosan, egyre gyorsabban változtat.
 */
@Composable
private fun StepButton(label: String, enabled: Boolean, onStep: () -> Unit) {
    // A nyomva tartás közben változik az érték, ezért mindig a legfrissebb lambdát kell hívni.
    val currentStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)

    Box(
        Modifier
            .size(width = 56.dp, height = 40.dp)
            .clip(shape)
            .background(if (pressed && enabled) Accent.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (enabled) Muted else Muted.copy(alpha = 0.3f), shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (!currentEnabled) return@detectTapGestures
                    pressed = true
                    currentStep()
                    try {
                        coroutineScope {
                            val repeater = launch {
                                delay(450)
                                var n = 0
                                while (currentEnabled) {
                                    currentStep()
                                    n++
                                    delay(maxOf(35L, 140L - n * 6L))
                                }
                            }
                            tryAwaitRelease()
                            repeater.cancel()
                        }
                    } finally {
                        pressed = false
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 22.sp,
            color = if (enabled) Accent else Muted.copy(alpha = 0.4f)
        )
    }
}
