package hu.szobek.szemkimelo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** Egy mentett munkaidő-bejegyzés. */
data class WorkEntry(val id: Int, val startMs: Long, val seconds: Int, val note: String) {
    val day: LocalDate
        get() = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
}

enum class WorkState { IDLE, RUNNING, PAUSED }

/**
 * Munkaidő-stopper adatai. Egy "szakasz" a jelenleg mért időtartam: indítható, szüneteltethető,
 * folytatható. Részidőnél vagy leállításnál a szakaszból bejegyzés lesz megjegyzéssel.
 * Az állapotot időbélyegekkel tároljuk, ezért az app bezárása vagy a folyamat leállítása után is
 * helyesen folytatódik a futó mérés.
 */
class WorkLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "worklog.json")

    var entries: List<WorkEntry> = emptyList()
        private set
    private var nextId = 1
    private var active = false
    private var beginAt = 0L
    private var accumMs = 0L
    private var runSince: Long? = null

    /** Minden változásnál nő; a felület ebből tudja, hogy újra kell rajzolnia. */
    var version by mutableIntStateOf(0)
        private set

    init {
        load()
    }

    val state: WorkState
        get() = when {
            !active -> WorkState.IDLE
            runSince != null -> WorkState.RUNNING
            else -> WorkState.PAUSED
        }

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long {
        if (!active) return 0L
        val running = runSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        return accumMs + running
    }

    // ---------------------------------------------------------------- műveletek
    fun start(now: Long = System.currentTimeMillis()) {
        if (state == WorkState.RUNNING) return
        if (!active) {
            active = true
            beginAt = now
            accumMs = 0L
        }
        runSince = now
        changed()
    }

    fun pause(now: Long = System.currentTimeMillis()) {
        val since = runSince ?: return
        accumMs += (now - since).coerceAtLeast(0L)
        runSince = null
        changed()
    }

    private fun addEntry(seconds: Int, note: String): WorkEntry {
        val entry = WorkEntry(nextId++, beginAt, seconds, note.trim())
        entries = entries + entry
        return entry
    }

    /** Részidő: a szakasz eddigi ideje bejegyzés lesz, a mérés új szakasszal megy tovább. */
    fun split(note: String, now: Long = System.currentTimeMillis()): WorkEntry? {
        if (!active) return null
        val seconds = ((elapsedMs(now) + 500) / 1000).toInt()
        if (seconds < 1) return null
        val entry = addEntry(seconds, note)
        val wasRunning = state == WorkState.RUNNING
        beginAt = now
        accumMs = 0L
        runSince = if (wasRunning) now else null
        changed()
        return entry
    }

    /** Leállítás: a maradék idő bejegyzés lesz, és nincs futó szakasz. */
    fun stop(note: String, now: Long = System.currentTimeMillis()): WorkEntry? {
        if (!active) return null
        val seconds = ((elapsedMs(now) + 500) / 1000).toInt()
        val entry = if (seconds >= 1) addEntry(seconds, note) else null
        active = false
        accumMs = 0L
        runSince = null
        changed()
        return entry
    }

    fun editNote(id: Int, note: String) {
        entries = entries.map { if (it.id == id) it.copy(note = note.trim()) else it }
        changed()
    }

    fun delete(id: Int) {
        entries = entries.filter { it.id != id }
        changed()
    }

    // ---------------------------------------------------------------- összesítő
    fun dayEntries(day: LocalDate): List<WorkEntry> =
        entries.filter { it.day == day }.sortedBy { it.startMs }

    fun dayTotalSeconds(day: LocalDate): Int = entries.filter { it.day == day }.sumOf { it.seconds }

    // ---------------------------------------------------------------- export
    fun monthEntries(month: YearMonth): List<WorkEntry> =
        entries.filter { YearMonth.from(it.day) == month }.sortedBy { it.startMs }

    /**
     * A hónap bejegyzései és napi összesítői CSV-ben (pontosvesszős, magyar Excelhez, UTF-8 BOM-mal).
     * Ha a teendők is megvannak, a hónapban elvégzettek és az összes nyitott teendő is bekerül.
     */
    fun monthCsv(month: YearMonth, tasks: TaskStore? = null): String {
        val sb = StringBuilder("\uFEFF")
        fun row(vararg cells: String) {
            sb.append(cells.joinToString(";") { csvCell(it) }).append("\r\n")
        }
        row("Dátum", "Kezdés", "Időtartam", "Óra", "Megjegyzés")
        val perDay = sortedMapOf<LocalDate, Int>()
        for (e in monthEntries(month)) {
            val t = Instant.ofEpochMilli(e.startMs).atZone(ZoneId.systemDefault())
            perDay[e.day] = (perDay[e.day] ?: 0) + e.seconds
            row(
                csvDate(e.day), "%02d:%02d".format(t.hour, t.minute), fmtHms(e.seconds * 1000L),
                csvHours(e.seconds), csvSafe(e.note)
            )
        }
        sb.append("\r\n")
        row("Napi összesítő")
        row("Dátum", "", "Összesen", "Óra")
        perDay.forEach { (d, s) -> row(csvDate(d), "", fmtHms(s * 1000L), csvHours(s)) }
        val total = perDay.values.sum()
        row("Összesen (%04d.%02d.)".format(month.year, month.monthValue), "", fmtHms(total * 1000L), csvHours(total))
        if (tasks != null) {
            val sections = listOf(
                "Elvégzett teendők" to tasks.doneInMonth(month).map { it.doneMs!! to it.text },
                "Nyitott teendők" to tasks.openTasks().map { it.createdMs to it.text }
            )
            for ((title, items) in sections) {
                sb.append("\r\n")
                row(title)
                row("Dátum", "Idő", "Teendő") // elvégzés, nyitottnál létrehozás ideje
                for ((ms, text) in items) {
                    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                    row(csvDate(t.toLocalDate()), "%02d:%02d".format(t.hour, t.minute), csvSafe(text))
                }
            }
        }
        return sb.toString()
    }

    /** Az importálás eredménye: új, már meglévő (kihagyott) és hibás sorok, valamint új teendők száma. */
    data class ImportResult(val added: Int, val skipped: Int, val bad: Int, val tasksAdded: Int = 0)

    /**
     * Exportált CSV visszaolvasása. Összefésül: ami már megvan (kezdés percre, időtartam, megjegyzés),
     * azt kihagyja. Ha a teendők (TaskStore) meg vannak adva, az "Elvégzett teendők" és "Nyitott teendők"
     * szakaszt is visszatölti. Ha a szöveg nem munkaidő-export, IllegalArgumentException.
     */
    fun importCsv(text: String, tasks: TaskStore? = null): ImportResult {
        val rows = parseCsv(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty() && rows[0].take(3).map { it.trim() } == listOf("Dátum", "Kezdés", "Időtartam")) {
            "Ez nem munkaidő-export fájl."
        }
        fun key(startMs: Long, seconds: Int, note: String) = Triple(Math.floorDiv(startMs, 60_000L), seconds, note)
        val known = entries.map { key(it.startMs, it.seconds, it.note) }.toMutableSet()
        val fresh = mutableListOf<WorkEntry>()
        var skipped = 0
        var bad = 0
        var tasksAdded = 0
        var section = "entries"
        var headerNext = false
        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) { section = ""; continue } // üres sor: új szakasz (vagy a vége)
            val title = if (row.count { it.isNotBlank() } == 1) row[0].trim() else ""
            when (title) {
                "Napi összesítő" -> { section = "daily"; headerNext = false; continue }
                "Elvégzett teendők" -> { section = "done"; headerNext = true; continue }
                "Nyitott teendők" -> { section = "open"; headerNext = true; continue }
            }
            if (section == "done" || section == "open") {
                if (headerNext) { headerNext = false; continue }
                if (tasks == null) continue
                val task = runCatching {
                    val d = row[0].trim().split(".")
                    val hm = row[1].trim().split(":")
                    val whenMs = java.time.LocalDateTime.of(d[0].toInt(), d[1].toInt(), d[2].toInt(), hm[0].toInt(), hm[1].toInt())
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    var t = row[2].trim()
                    if (t.length >= 2 && t[0] == '\'' && t[1] in "=+-@") t = t.substring(1)
                    whenMs to t
                }.getOrNull()
                if (task == null || task.second.isEmpty()) {
                    bad++
                } else if (tasks.addImported(task.second, task.first, if (section == "done") task.first else null)) {
                    tasksAdded++
                }
                continue
            }
            if (section != "entries") continue
            val parsed = runCatching {
                val d = row[0].trim().split(".")
                val hm = row[1].trim().split(":")
                val dur = row[2].trim().split(":").map { it.toInt() }
                val start = java.time.LocalDateTime.of(d[0].toInt(), d[1].toInt(), d[2].toInt(), hm[0].toInt(), hm[1].toInt())
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                var note = row.getOrNull(4)?.trim() ?: ""
                if (note.length >= 2 && note[0] == '\'' && note[1] in "=+-@") note = note.substring(1)
                Triple(start, dur[0] * 3600 + dur[1] * 60 + dur[2], note)
            }.getOrNull()
            if (parsed == null || parsed.second < 1) {
                bad++
                continue
            }
            if (!known.add(key(parsed.first, parsed.second, parsed.third))) {
                skipped++
                continue
            }
            fresh += WorkEntry(nextId++, parsed.first, parsed.second, parsed.third)
        }
        if (fresh.isNotEmpty()) {
            entries = entries + fresh
            changed()
        }
        return ImportResult(fresh.size, skipped, bad, tasksAdded)
    }

    /** Egyszerű CSV-olvasó pontosvesszővel; kezeli az idézőjeles, akár többsoros mezőket is. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> cell.append(c)
                c == '"' -> quoted = true
                c == ';' -> { row.add(cell.toString()); cell.clear() }
                c == '\r' -> {}
                c == '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    private fun csvDate(d: LocalDate) = "%04d.%02d.%02d.".format(d.year, d.monthValue, d.dayOfMonth)

    private fun csvHours(seconds: Int) = "%.2f".format(Locale.ROOT, seconds / 3600.0).replace('.', ',')

    /** Az Excel ne értelmezze képletként a '=', '+', '-', '@' jellel kezdődő megjegyzést. */
    private fun csvSafe(text: String) = if (text.firstOrNull() in listOf('=', '+', '-', '@')) "'$text" else text

    private fun csvCell(s: String) =
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---------------------------------------------------------------- tárolás
    private fun changed() {
        version++
        save()
    }

    private fun save() {
        val arr = JSONArray()
        entries.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("start", it.startMs)
                    .put("seconds", it.seconds).put("note", it.note)
            )
        }
        val current = JSONObject()
            .put("active", active).put("begin_at", beginAt).put("accum", accumMs)
            .put("run_since", runSince ?: JSONObject.NULL)
        val data = JSONObject().put("entries", arr).put("current", current)
        runCatching {
            val tmp = File(file.parentFile, "worklog.json.tmp")
            tmp.writeText(data.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(data.toString())
                tmp.delete()
            }
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val data = JSONObject(file.readText())
            val arr = data.optJSONArray("entries") ?: JSONArray()
            entries = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                WorkEntry(o.getInt("id"), o.getLong("start"), o.getInt("seconds"), o.optString("note", ""))
            }
            nextId = (entries.maxOfOrNull { it.id } ?: 0) + 1
            val cur = data.optJSONObject("current")
            if (cur != null && cur.optBoolean("active")) {
                active = true
                beginAt = cur.optLong("begin_at")
                accumMs = cur.optLong("accum")
                runSince = if (cur.isNull("run_since")) null else cur.getLong("run_since")
            }
        }
    }
}

fun fmtHms(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}

/** Rövid, olvasható időtartam: "3 ó 12 p", "45 p", "30 mp". */
fun fmtDur(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    return when {
        h > 0 -> "%d ó %02d p".format(h, m)
        m > 0 -> "%d p".format(m)
        else -> "%d mp".format(s)
    }
}
