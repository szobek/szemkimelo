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

/** Egy teendő. `doneMs == null`: még nincs kész. */
data class TaskItem(val id: Int, val text: String, val createdMs: Long, val doneMs: Long?) {
    val isDone: Boolean get() = doneMs != null
    val doneDay: LocalDate?
        get() = doneMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
}

/**
 * Napi teendők. Egyetlen lista van: a nem kész teendők minden nap megmaradnak (másnapra "átmennek"),
 * a kész teendő pedig azon a napon számít elvégzettnek, amikor kipipálták.
 */
class TaskStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "tasks.json")

    var tasks: List<TaskItem> = emptyList()
        private set
    private var nextId = 1

    /** Minden változásnál nő; a felület ebből tudja, hogy újra kell rajzolnia. */
    var version by mutableIntStateOf(0)
        private set

    init {
        load()
    }

    // ---------------------------------------------------------------- műveletek
    fun add(text: String, now: Long = System.currentTimeMillis()): TaskItem? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val item = TaskItem(nextId++, t, now, null)
        tasks = tasks + item
        changed()
        return item
    }

    /** Kipipálja a teendőt, vagy (ha kész volt) újra megnyitja. */
    fun toggle(id: Int, now: Long = System.currentTimeMillis()) {
        tasks = tasks.map { if (it.id == id) it.copy(doneMs = if (it.isDone) null else now) else it }
        changed()
    }

    fun edit(id: Int, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        tasks = tasks.map { if (it.id == id) it.copy(text = t) else it }
        changed()
    }

    fun delete(id: Int) {
        tasks = tasks.filter { it.id != id }
        changed()
    }

    /** Egy nyitott teendőt a többi nyitott teendőhöz képest feljebb (-1) vagy lejjebb (+1) tesz. */
    fun move(id: Int, delta: Int) {
        val target = tasks.firstOrNull { it.id == id } ?: return
        if (target.isDone) return
        val openIdx = tasks.indices.filter { !tasks[it].isDone }
        val pos = openIdx.indexOf(tasks.indexOf(target))
        val newPos = pos + if (delta < 0) -1 else 1
        if (newPos !in openIdx.indices) return
        val list = tasks.toMutableList()
        val a = openIdx[pos]
        val b = openIdx[newPos]
        list[a] = tasks[b].also { list[b] = tasks[a] }
        tasks = list
        changed()
    }

    // ---------------------------------------------------------------- lekérdezések
    fun openTasks(): List<TaskItem> = tasks.filter { !it.isDone }

    fun doneOn(day: LocalDate): List<TaskItem> =
        tasks.filter { it.doneDay == day }.sortedBy { it.doneMs }

    fun doneInMonth(month: YearMonth): List<TaskItem> =
        tasks.filter { it.doneDay?.let { d -> YearMonth.from(d) == month } == true }.sortedBy { it.doneMs }

    /** A mai lista: az összes nyitott teendő (sorrendben), utána a ma elvégzettek. */
    fun todayView(today: LocalDate = LocalDate.now()): List<TaskItem> = openTasks() + doneOn(today)

    /** Importált teendő felvétele; false, ha már megvan (nyitottnál szöveg, késznél kész-perc + szöveg). */
    fun addImported(text: String, createdMs: Long, doneMs: Long?): Boolean {
        val exists = tasks.any {
            it.text == text && when {
                doneMs == null -> !it.isDone
                else -> it.doneMs != null && Math.floorDiv(it.doneMs, 60_000L) == Math.floorDiv(doneMs, 60_000L)
            }
        }
        if (exists) return false
        tasks = tasks + TaskItem(nextId++, text, createdMs, doneMs)
        changed()
        return true
    }

    // ---------------------------------------------------------------- tárolás
    private fun changed() {
        version++
        save()
    }

    private fun save() {
        val arr = JSONArray()
        tasks.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("text", it.text).put("created", it.createdMs)
                    .put("done", it.doneMs ?: JSONObject.NULL)
            )
        }
        val data = JSONObject().put("tasks", arr).toString()
        runCatching {
            val tmp = File(file.parentFile, "tasks.json.tmp")
            tmp.writeText(data)
            if (!tmp.renameTo(file)) {
                file.writeText(data)
                tmp.delete()
            }
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val arr = JSONObject(file.readText()).optJSONArray("tasks") ?: JSONArray()
            tasks = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                TaskItem(
                    o.getInt("id"), o.getString("text"), o.optLong("created"),
                    if (o.isNull("done")) null else o.getLong("done")
                )
            }
            nextId = (tasks.maxOfOrNull { it.id } ?: 0) + 1
        }
    }
}
