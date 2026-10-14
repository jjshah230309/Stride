package com.jashith.stride.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.jashith.stride.core.io.ActivityFile
import com.jashith.stride.core.store.StrideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The small amount of Storage Access Framework and share-sheet plumbing the app needs. */
object FileOps {

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    suspend fun readBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }

    suspend fun copyTo(context: Context, source: File, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(destination, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
    }

    /** Opens the system share sheet for a file inside the app's private folder. */
    fun share(context: Context, file: File, mimeType: String, title: String = "Share") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Writes into the app's Exports folder, replacing any earlier file of that name, and returns it. */
    suspend fun writeExport(files: com.jashith.stride.core.store.StrideFiles, filename: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        files.prepare()
        val safe = filename.replace('/', '-').replace('\\', '-')
        File(files.exportsDirectory, safe).also { com.jashith.stride.core.store.StrideFiles.writeAtomically(it, bytes) }
    }

    data class ImportOutcome(val imported: Int, val routesSkipped: Int, val failures: List<String>)

    /** Reads GPX, TCX and FIT files and stores each as a new activity. Bad files are reported, not fatal. */
    suspend fun importActivities(context: Context, store: StrideStore, uris: List<Uri>): ImportOutcome {
        var imported = 0
        var routes = 0
        val failures = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(context, uri)
            val data = readBytes(context, uri)
            if (data == null) {
                failures += "$name could not be read."
                continue
            }
            try {
                val file = withContext(Dispatchers.Default) { ActivityFile.load(data, name) }
                if (file.isRoute) {
                    routes += 1
                    continue
                }
                val activity = withContext(Dispatchers.Default) { ActivityFile.makeActivity(file, store.current.settings.profile) }
                store.add(activity, file.points)
                imported += 1
            } catch (e: Exception) {
                failures += "$name: ${e.message ?: "could not be imported."}"
            }
        }
        return ImportOutcome(imported, routes, failures)
    }

    fun importSummary(outcome: ImportOutcome): String = buildString {
        if (outcome.imported > 0) append(if (outcome.imported == 1) "Imported 1 activity." else "Imported ${outcome.imported} activities.")
        if (outcome.routesSkipped > 0) {
            if (isNotEmpty()) append(' ')
            append(if (outcome.routesSkipped == 1) "1 file was a route, not an activity; import it from Routes." else "${outcome.routesSkipped} files were routes, not activities; import them from Routes.")
        }
        if (outcome.failures.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append(outcome.failures.joinToString("\n"))
        }
        if (isEmpty()) append("Nothing was imported.")
    }
}
