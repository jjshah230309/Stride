package com.jashith.stride.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.core.io.ActivityFile
import com.jashith.stride.core.io.AutoBackup
import com.jashith.stride.core.io.Backup
import com.jashith.stride.core.io.Exporter
import com.jashith.stride.core.model.BackupSettings
import com.jashith.stride.core.store.StrideFiles
import com.jashith.stride.files.FileOps
import com.jashith.stride.ui.DateFmt
import com.jashith.stride.ui.Dest
import com.jashith.stride.ui.LocalGraph
import com.jashith.stride.ui.LocalNavigator
import com.jashith.stride.ui.components.ButtonRow
import com.jashith.stride.ui.components.FormSection
import com.jashith.stride.ui.components.PickerRow
import com.jashith.stride.ui.components.SubPage
import com.jashith.stride.ui.components.ToggleRow
import com.jashith.stride.ui.components.ValueRow
import com.jashith.stride.ui.rememberStoreState
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

private data class Pending(val file: File, val summary: Backup.Summary, val deleteAfter: Boolean)

@Composable
fun DataScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberStoreState()
    val backup = state.settings.backup

    var working by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRecalc by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }
    var savedCount by remember { mutableStateOf(AutoBackup.existing(store.files).size) }

    val backupName = remember { Backup.suggestedFilename() }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val snapshot = store.backupSnapshot()
        working = "Writing backup…"; progress = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                        Backup.write(snapshot, store.files, out, progress = { p -> progress = p.toFloat() })
                    }
                }
                message = "Backup saved."
            } catch (e: Exception) {
                error = e.message ?: "The backup could not be written."
            }
            working = null
        }
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                // Copy it somewhere we own before doing anything slow with it.
                val local = withContext(Dispatchers.IO) {
                    store.files.prepare()
                    val target = File(store.files.exportsDirectory, "incoming-backup")
                    target.delete()
                    context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                    target
                }
                val summary = withContext(Dispatchers.IO) { local.inputStream().use { Backup.inspect(it) } }
                pending = Pending(local, summary, deleteAfter = true)
            } catch (e: Exception) {
                error = (e as? Backup.Failure)?.message ?: "That file is not a Stride backup."
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { importNote = FileOps.importSummary(FileOps.importActivities(context, store, uris)) }
    }

    fun runBackup() {
        val snapshot = store.backupSnapshot()
        working = "Writing backup…"; progress = 0f
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    store.files.prepare()
                    Backup.writeToFile(snapshot, store.files, File(store.files.exportsDirectory, backupName), progress = { p -> progress = p.toFloat() })
                }
                working = null
                FileOps.share(context, file, "application/octet-stream", "Share backup")
            } catch (e: Exception) {
                working = null
                error = e.message ?: "The backup could not be written."
            }
        }
    }

    fun runRestore(p: Pending, mode: Backup.RestoreMode) {
        val existing = store.activityIDs
        working = if (mode == Backup.RestoreMode.Replace) "Replacing your data…" else "Merging backup…"
        progress = 0f
        pending = null
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    p.file.inputStream().use { Backup.read(it, store.files, existing, mode) { f -> progress = f.toFloat() } }
                }
                store.apply(restored, mode, restoreSettings = mode == Backup.RestoreMode.Replace)
                store.rematchAllSegments()
                message = "Restored ${restored.activities.size} activities." + if (restored.skipped > 0) " ${restored.skipped} entries could not be read." else ""
                if (p.deleteAfter) p.file.delete()
            } catch (e: Exception) {
                error = e.message ?: "The backup could not be restored."
            }
            working = null
        }
    }

    SubPage("Data", onBack = { nav.pop() }) {
        if (store.damagedFiles.isNotEmpty()) {
            FormSection {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Some data could not be read", style = StrideType.Body.copy(fontSize = 14.sp, color = StrideColors.Warning, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    Text(
                        "These files were set aside rather than overwritten, so nothing has been destroyed: ${store.damagedFiles.joinToString(", ")}. The saved copies have a .damaged suffix in the app's private folder. Restoring a backup is the easiest way to recover.",
                        style = StrideType.Footnote,
                    )
                }
            }
        }
        working?.let { label ->
            FormSection {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = StrideType.Footnote)
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = StrideColors.Green, trackColor = StrideColors.Track)
                }
            }
        }
        val idle = working == null

        FormSection(
            header = "Backup",
            footer = "One file holding every activity and its full GPS track, plus your segments, routes, gear, goals and settings. It restores on Android and on iPhone, so it is also how you move your training between the two.",
        ) {
            ButtonRow("Back up now and share…", { runBackup() }, enabled = idle)
            ButtonRow("Save a backup to a folder…", { saveBackup.launch(backupName) }, enabled = idle)
            ButtonRow("Restore from a backup…", { restorePicker.launch(arrayOf("*/*")) }, enabled = idle)
            ValueRow("Saved backups", "$savedCount", onClick = { nav.push(Dest.SavedBackups) }, divider = false)
        }

        FormSection(
            header = "Automatic",
            footer = "Checked each time Stride opens. Backups are kept in the app's own folder; open Saved backups to share one or save a copy somewhere safe, such as cloud storage. Older ones beyond the number you keep are deleted.",
        ) {
            ToggleRow("Back up automatically", backup.automatic, { on -> store.updateSettings { it.copy(backup = it.backup.copy(automatic = on)) } })
            if (backup.automatic) {
                PickerRow("How often", BackupSettings.intervalChoices.map { it.first }, backup.intervalDays,
                    { d -> BackupSettings.intervalChoices.firstOrNull { it.first == d }?.second ?: "Every $d days" },
                    { d -> store.updateSettings { it.copy(backup = it.backup.copy(intervalDays = d)) } })
                PickerRow("Keep the last", listOf(3, 5, 10, 20), backup.keepCount, { "$it backups" },
                    { k -> store.updateSettings { it.copy(backup = it.backup.copy(keepCount = k)) } })
            }
            val last = backup.lastRun
            if (last != null) ValueRow("Last backup", DateFmt.relativeDay(last))
            else if (backup.automatic) ValueRow("Last backup", "Due on next launch")
            val next = backup.nextDue()
            if (next != null && last != null) ValueRow("Next", if (!next.isAfter(Instant.now())) "Due now" else DateFmt.shortDay(next), divider = false)
        }

        FormSection(header = "Export", footer = "A spreadsheet of every activity's totals. Individual tracks export as GPX, TCX or FIT from each activity's own menu.") {
            ButtonRow("Export activities as CSV", {
                scope.launch {
                    val csv = withContext(Dispatchers.Default) { Exporter.csv(state.activities, state.settings.units) }
                    val file = FileOps.writeExport(store.files, "stride-activities.csv", csv.toByteArray())
                    FileOps.share(context, file, "text/csv", "Export CSV")
                }
            }, divider = false)
        }

        FormSection(
            header = "Import",
            footer = "GPX, TCX and FIT are all understood, and the format is worked out from the file itself rather than its name. A bulk export from Strava, Garmin, Wahoo or Coros can be selected all at once.",
        ) {
            ButtonRow("Import activity files…", { importPicker.launch(ActivityFile.readableMimeTypes) }, enabled = idle, divider = importNote != null)
            importNote?.let { Text(it, Modifier.padding(16.dp), style = StrideType.Footnote.copy(color = StrideColors.Positive)) }
        }

        FormSection(header = "Maintenance") {
            ButtonRow("Rebuild every segment leaderboard", { scope.launch { store.rematchAllSegments(); message = "Segment leaderboards rebuilt." } }, enabled = idle)
            ButtonRow("Recalculate all activities", { confirmRecalc = true }, enabled = idle)
            ValueRow("Activities", "${state.activities.size}")
            ValueRow("Segments", "${state.segments.size}")
            ValueRow("Segment efforts", "${state.segmentEfforts.size}")
            ValueRow("Routes", "${state.routes.size}")
            ValueRow("Storage", Formatter.formatFileSize(context, folderSize(store.files.root)), divider = false)
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null; if (p.deleteAfter) p.file.delete() },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "This backup was made ${DateFmt.shortDay(p.summary.createdAt)} and holds ${p.summary.activities} activities, " +
                        "${p.summary.segments} segments, ${p.summary.routes} routes and ${p.summary.goals} goals.",
                )
            },
            confirmButton = { TextButton(onClick = { runRestore(p, Backup.RestoreMode.Merge) }) { Text("Merge — keep what is here") } },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { runRestore(p, Backup.RestoreMode.Replace) }) { Text("Replace everything", color = StrideColors.Negative) }
                    TextButton(onClick = { pending = null; if (p.deleteAfter) p.file.delete() }) { Text("Cancel") }
                }
            },
        )
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text("Done") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
    error?.let { AlertDialog(onDismissRequest = { error = null }, title = { Text("Something went wrong") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
    if (confirmRecalc) {
        AlertDialog(
            onDismissRequest = { confirmRecalc = false },
            title = { Text("Recalculate every activity from its raw track?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRecalc = false
                    working = "Recalculating…"; progress = 0f
                    scope.launch { store.reanalyseAll { p -> progress = p.toFloat() }; working = null; message = "All activities recalculated." }
                }) { Text("Recalculate") }
            },
            dismissButton = { TextButton(onClick = { confirmRecalc = false }) { Text("Cancel") } },
        )
    }
}

private fun folderSize(root: File): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

/** The backups already on the phone: share one out, put one back, or clear space. */
@Composable
fun SavedBackupsScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val store = graph.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf(AutoBackup.existing(store.files)) }
    var pending by remember { mutableStateOf<Pair<AutoBackup.Entry, Backup.Summary>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf<AutoBackup.Entry?>(null) }

    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = copying
        copying = null
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        scope.launch { message = if (FileOps.copyTo(context, entry.file, uri)) "Copy saved." else "The copy could not be saved." }
    }

    fun restore(mode: Backup.RestoreMode) {
        val (entry, _) = pending ?: return
        val existing = store.activityIDs
        pending = null
        working = true
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { entry.file.inputStream().use { Backup.read(it, store.files, existing, mode) } }
                store.apply(restored, mode, restoreSettings = mode == Backup.RestoreMode.Replace)
                store.rematchAllSegments()
                message = "Restored ${restored.activities.size} activities."
            } catch (e: Exception) {
                message = e.message ?: "The backup could not be restored."
            }
            working = false
        }
    }

    SubPage("Saved Backups", onBack = { nav.pop() }) {
        if (entries.isEmpty()) {
            Text("No backups yet. Turn on automatic backups, or use \"Back up now\".", style = StrideType.Footnote)
        }
        entries.forEach { entry ->
            FormSection {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(DateFmt.relativeDay(entry.created), style = StrideType.Body.copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    Text(Formatter.formatFileSize(context, entry.byteCount) + " · " + entry.filename, style = StrideType.Body.copy(fontSize = 11.sp, color = StrideColors.SecondaryText))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 2.dp)) {
                        listOf<Pair<String, () -> Unit>>(
                            "Share" to { FileOps.share(context, entry.file, "application/octet-stream", "Share backup") },
                            "Save copy" to { copying = entry; saveCopy.launch(entry.filename) },
                            "Restore" to {
                                scope.launch {
                                    val summary = withContext(Dispatchers.IO) { runCatching { entry.file.inputStream().use { Backup.inspect(it) } }.getOrNull() }
                                    if (summary == null) message = "That backup could not be read." else pending = entry to summary
                                }
                            },
                            "Delete" to { AutoBackup.delete(entry); entries = AutoBackup.existing(store.files) },
                        ).forEach { (label, action) ->
                            Text(
                                label, Modifier.clickable(enabled = !working, onClick = action).padding(vertical = 4.dp),
                                style = StrideType.Body.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (label == "Delete") StrideColors.Negative else StrideColors.Green),
                            )
                        }
                    }
                }
            }
        }
    }

    pending?.let { (_, summary) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = { Text("Made ${DateFmt.shortDay(summary.createdAt)} with ${summary.activities} activities, ${summary.segments} segments and ${summary.routes} routes.") },
            confirmButton = { TextButton(onClick = { restore(Backup.RestoreMode.Merge) }) { Text("Merge — keep what is here") } },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { restore(Backup.RestoreMode.Replace) }) { Text("Replace everything", color = StrideColors.Negative) }
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                }
            },
        )
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text("Done") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
}
