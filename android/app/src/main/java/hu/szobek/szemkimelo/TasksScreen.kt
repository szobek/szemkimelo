package hu.szobek.szemkimelo

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private fun fmtToday(day: LocalDate): String {
    val month = day.month.getDisplayName(TextStyle.FULL, Locale("hu"))
    val name = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("hu"))
    return "%d. %s %d., %s".format(day.year, month, day.dayOfMonth, name)
}

/** A napi teendők lapja: nyitott teendők (átmennek másnapra) és a ma elvégzettek. */
@Composable
fun TasksScreen() {
    val ctx = LocalContext.current
    val store = remember { TaskStore(ctx) }
    var input by remember { mutableStateOf("") }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var editTarget by remember { mutableStateOf<TaskItem?>(null) }
    var deleteTarget by remember { mutableStateOf<TaskItem?>(null) }

    // Ha éjfélkor az app nyitva van, a "ma" is frissüljön.
    val owner = ctx as ComponentActivity
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                today = LocalDate.now()
                delay(30_000)
            }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val version = store.version // feliratkozás a változásokra
    val open = store.openTasks()
    val doneToday = store.doneOn(today)

    fun addTask() {
        if (store.add(input) != null) input = ""
    }

    editTarget?.let { t ->
        NoteInputDialog("Teendő szerkesztése", "Teendő", t.text, onDismiss = { editTarget = null }) { text ->
            store.edit(t.id, text)
            editTarget = null
        }
    }
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Törlés") },
            text = { Text("Biztosan törlöd ezt a teendőt?\n${t.text}") },
            confirmButton = { TextButton(onClick = { store.delete(t.id); deleteTarget = null }) { Text("Törlés") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Mégse") } }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard("Teendők") {
            Text(fmtToday(today), color = Muted, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Új teendő") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTask() })
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(onClick = { addTask() }, enabled = input.isNotBlank()) { Text("Hozzáadás") }
            }
            Text(
                "Nyitott: ${open.size}   ·   Ma kész: ${doneToday.size}",
                color = Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
            if (open.isEmpty() && doneToday.isEmpty()) {
                Text("Nincs teendő. Vegyél fel egyet fent.", color = Muted, fontSize = 14.sp)
            }
            open.forEachIndexed { i, t ->
                TaskRow(
                    t, canUp = i > 0, canDown = i < open.lastIndex,
                    onToggle = { store.toggle(t.id) },
                    onUp = { store.move(t.id, -1) }, onDown = { store.move(t.id, 1) },
                    onEdit = { editTarget = t }, onDelete = { deleteTarget = t }
                )
            }
            doneToday.forEach { t ->
                TaskRow(
                    t, canUp = false, canDown = false,
                    onToggle = { store.toggle(t.id) }, onUp = {}, onDown = {},
                    onEdit = { editTarget = t }, onDelete = { deleteTarget = t }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TaskRow(
    task: TaskItem,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.isDone, onCheckedChange = { onToggle() })
            Text(
                task.text, Modifier.weight(1f),
                color = if (task.isDone) Muted else Color.Unspecified,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null
            )
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (!task.isDone) {
                TextButton(onClick = onUp, enabled = canUp) { Text("▲") }
                TextButton(onClick = onDown, enabled = canDown) { Text("▼") }
            }
            TextButton(onClick = onEdit) { Text("Szerkesztés") }
            TextButton(onClick = onDelete) { Text("Törlés") }
        }
    }
}
