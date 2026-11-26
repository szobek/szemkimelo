package hu.szobek.szemkimelo

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Melyik megjegyzés-párbeszédet mutatjuk. */
private sealed interface NoteDialog {
    /** `at`: a gombnyomás pillanata; a megjegyzés írása nem számít bele a mért időbe. */
    data class Split(val at: Long) : NoteDialog
    data class Stop(val at: Long) : NoteDialog
    data class Edit(val entry: WorkEntry) : NoteDialog
}

private fun fmtTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
        .let { "%02d:%02d".format(it.hour, it.minute) }

private fun fmtDay(day: LocalDate, today: LocalDate): String {
    val name = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("hu"))
    val base = "%04d.%02d.%02d. %s".format(day.year, day.monthValue, day.dayOfMonth, name)
    return if (day == today) "$base (ma)" else base
}

@Composable
fun WorkLogScreen() {
    val ctx = LocalContext.current
    val log = remember { WorkLog(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var dialog by remember { mutableStateOf<NoteDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkEntry?>(null) }

    // Korábban exportált CSV visszatöltése (a Drive-on vagy a telefonon lévő fájlból).
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val msg = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                require(bytes.size <= 5_000_000) { "A fájl túl nagy ahhoz, hogy munkaidő-export legyen." }
                val r = log.importCsv(String(bytes, Charsets.UTF_8))
                "Új bejegyzés: ${r.added}, már megvolt: ${r.skipped}" +
                    if (r.bad > 0) ", hibás sor: ${r.bad}" else ""
            }.getOrElse { "Nem sikerült az importálás: ${it.message ?: "ismeretlen hiba"}" }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    // A kijelzett idő csak akkor frissül, amikor az app látszik.
    val owner = ctx as ComponentActivity
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val version = log.version // feliratkozás a változásokra
    val state = log.state
    val elapsed = log.elapsedMs(now)
    val elapsedSec = (elapsed / 1000).toInt()
    val today = LocalDate.now()

    val entries = log.dayEntries(day)
    val runningPart = if (day == today && state != WorkState.IDLE) elapsedSec else 0
    val total = log.dayTotalSeconds(day) + runningPart

    dialog?.let { d ->
        val (title, label, initial) = when (d) {
            is NoteDialog.Split ->
                Triple("Részidő mentése", "Megjegyzés a részidőhöz (${fmtDur(((log.elapsedMs(d.at) + 500) / 1000).toInt())})", "")
            is NoteDialog.Stop ->
                Triple("Leállítás", "Megjegyzés a bejegyzéshez (${fmtDur(((log.elapsedMs(d.at) + 500) / 1000).toInt())})", "")
            is NoteDialog.Edit -> Triple("Megjegyzés szerkesztése", "Megjegyzés", d.entry.note)
        }
        NoteInputDialog(title, label, initial, onDismiss = { dialog = null }) { note ->
            when (d) {
                is NoteDialog.Split -> { log.split(note, d.at); day = LocalDate.now() }
                is NoteDialog.Stop -> { log.stop(note, d.at); day = LocalDate.now() }
                is NoteDialog.Edit -> log.editNote(d.entry.id, note)
            }
            dialog = null
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Törlés") },
            text = { Text("Biztosan törlöd ezt a bejegyzést?\n${fmtTime(target.startMs)} · ${fmtDur(target.seconds)}") },
            confirmButton = {
                TextButton(onClick = { log.delete(target.id); deleteTarget = null }) { Text("Törlés") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Mégse") } }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (state) {
                        WorkState.IDLE -> "Nincs futó mérés"
                        WorkState.RUNNING -> "Fut"
                        WorkState.PAUSED -> "Szüneteltetve"
                    },
                    color = Muted, fontSize = 14.sp
                )
                Text(fmtHms(elapsed), color = Accent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (state == WorkState.RUNNING) log.pause() else log.start() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (state) {
                                WorkState.IDLE -> "Indítás"
                                WorkState.RUNNING -> "Szünet"
                                WorkState.PAUSED -> "Folytatás"
                            },
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { dialog = NoteDialog.Split(System.currentTimeMillis()) },
                        enabled = state != WorkState.IDLE && elapsedSec >= 1,
                        modifier = Modifier.weight(1f)
                    ) { Text("Részidő", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            if (elapsedSec >= 1) dialog = NoteDialog.Stop(System.currentTimeMillis()) else log.stop("")
                        },
                        enabled = state != WorkState.IDLE,
                        modifier = Modifier.weight(1f)
                    ) { Text("Leállítás", maxLines = 1) }
                }
            }
        }

        SectionCard("Napi összesítő") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { day = day.minusDays(1) }) { Text("‹", fontSize = 22.sp) }
                Text(
                    fmtDay(day, today), Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { day = day.plusDays(1) }) { Text("›", fontSize = 22.sp) }
            }
            if (day != today) {
                TextButton(onClick = { day = today }, modifier = Modifier.fillMaxWidth()) { Text("Ugrás mára") }
            }
            Text(
                "Összesen: ${fmtDur(total)}" +
                    if (runningPart > 0) " (futó: ${fmtDur(runningPart)})" else "",
                color = Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
            )

            if (entries.isEmpty()) {
                Text("Nincs bejegyzés erre a napra.", color = Muted, fontSize = 14.sp)
            }
            entries.forEach { e ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "${fmtTime(e.startMs)}  ·  ${fmtDur(e.seconds)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (e.note.isNotEmpty()) Text(e.note, color = Muted, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { dialog = NoteDialog.Edit(e) }) { Text("Szerkesztés") }
                        TextButton(onClick = { deleteTarget = e }) { Text("Törlés") }
                    }
                }
            }
        }
        val month = YearMonth.from(day)
        val monthLabel = "%d. %s".format(month.year, month.month.getDisplayName(TextStyle.FULL, Locale("hu")))
        OutlinedButton(
            onClick = {
                if (log.monthEntries(month).isEmpty()) {
                    Toast.makeText(ctx, "$monthLabel hónapban nincs mentett bejegyzés.", Toast.LENGTH_SHORT).show()
                } else {
                    shareMonth(ctx, log, month, monthLabel)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("$monthLabel exportálása (CSV)", maxLines = 1) }
        OutlinedButton(
            onClick = { importPicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Importálás exportált CSV-ből", maxLines = 1) }
        Spacer(Modifier.height(8.dp))
    }
}

/** A hónap CSV-jét ideiglenes fájlba írja, és megnyitja a rendszer megosztó ablakát. */
private fun shareMonth(ctx: Context, log: WorkLog, month: YearMonth, label: String) {
    runCatching {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "munkaido-%04d-%02d.csv".format(month.year, month.monthValue))
        file.writeText(log.monthCsv(month))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Munkaidő – $label")
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "$label exportálása"))
    }.onFailure {
        Toast.makeText(ctx, "Az exportálás nem sikerült.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun NoteInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(label) }, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Mentés") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } }
    )
}
