@file:UseSerializers(InstantSerializer::class, UuidSerializer::class)

package com.jashith.stride.core.io

import com.jashith.stride.core.json.InstantSerializer
import com.jashith.stride.core.json.StrideJson
import com.jashith.stride.core.json.UuidSerializer
import com.jashith.stride.core.model.Activity
import com.jashith.stride.core.model.AppSettings
import com.jashith.stride.core.model.Gear
import com.jashith.stride.core.model.Goal
import com.jashith.stride.core.model.SavedRoute
import com.jashith.stride.core.model.Segment
import com.jashith.stride.core.model.SegmentEffort
import com.jashith.stride.core.model.TrackPoint
import com.jashith.stride.core.store.StrideFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * A complete, portable copy of everything the app knows. Identical to the iOS format.
 *
 * One JSON object per line: a header, then one line per activity carrying its full GPS track.
 * Written and read a line at a time, so a library with hundreds of long rides never has to fit
 * in memory at once.
 */
object Backup {

    const val FILE_EXTENSION = "stridebackup"
    const val FORMAT_VERSION = 1

    @Serializable
    data class Manifest(
        val version: Int,
        val createdAt: Instant,
        val activityCount: Int,
        val trackCount: Int,
    )

    @Serializable
    data class Header(
        val manifest: Manifest,
        val settings: AppSettings,
        val segments: List<Segment>,
        val segmentEfforts: List<SegmentEffort>,
        val routes: List<SavedRoute>,
        val gear: List<Gear>,
        val goals: List<Goal>,
    )

    @Serializable
    data class Entry(val activity: Activity, val track: List<TrackPoint>)

    /** Everything needed to write a backup, captured up front so file work can move to a background thread. */
    data class Snapshot(
        val settings: AppSettings,
        val activities: List<Activity>,
        val segments: List<Segment>,
        val segmentEfforts: List<SegmentEffort>,
        val routes: List<SavedRoute>,
        val gear: List<Gear>,
        val goals: List<Goal>,
    )

    data class Summary(
        val activities: Int,
        val segments: Int,
        val routes: Int,
        val gear: Int,
        val goals: Int,
        val createdAt: Instant,
    )

    enum class RestoreMode {
        /** Keep what is already here and add anything the backup has that is new. */
        Merge,

        /** Throw away everything on the phone and use the backup instead. */
        Replace,
    }

    sealed class Failure(message: String) : Exception(message) {
        class CouldNotCreateFile : Failure("Could not create the backup file.")
        class NotABackup : Failure("That file is not a Stride backup.")
        class Unreadable : Failure("The backup could not be opened.")
    }

    /** The result of reading a backup, ready to hand to the store. */
    data class Restored(
        val settings: AppSettings,
        val activities: List<Activity>,
        val segments: List<Segment>,
        val segmentEfforts: List<SegmentEffort>,
        val routes: List<SavedRoute>,
        val gear: List<Gear>,
        val goals: List<Goal>,
        val skipped: Int,
    )

    private val filenameFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)

    /** `Stride-2026-10-13-1635.stridebackup`, in local time like iOS. */
    fun suggestedFilename(at: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): String =
        "Stride-${filenameFormat.format(at)}.$FILE_EXTENSION"

    // Writing

    fun write(
        snapshot: Snapshot,
        files: StrideFiles,
        out: OutputStream,
        now: Instant = Instant.now(),
        progress: ((Double) -> Unit)? = null,
    ) {
        val writer = BufferedWriter(OutputStreamWriter(out, StandardCharsets.UTF_8), 1 shl 16)

        // Count tracks by looking for the files; each one is re-read individually below.
        val tracked = snapshot.activities.count { files.hasTrack(it.id) }

        val header = Header(
            manifest = Manifest(FORMAT_VERSION, now, snapshot.activities.size, tracked),
            settings = snapshot.settings,
            segments = snapshot.segments,
            segmentEfforts = snapshot.segmentEfforts,
            routes = snapshot.routes,
            gear = snapshot.gear,
            goals = snapshot.goals,
        )
        writer.write(StrideJson.encodeToString(Header.serializer(), header))
        writer.write("\n")

        val total = max(1, snapshot.activities.size).toDouble()
        snapshot.activities.forEachIndexed { index, activity ->
            val entry = Entry(activity, files.loadTrack(activity.id))
            writer.write(StrideJson.encodeToString(Entry.serializer(), entry))
            writer.write("\n")
            progress?.invoke((index + 1) / total)
        }
        writer.flush()
    }

    /** Writes via a temporary sibling so a half-written backup never replaces a good one. */
    fun writeToFile(
        snapshot: Snapshot,
        files: StrideFiles,
        destination: File,
        now: Instant = Instant.now(),
        progress: ((Double) -> Unit)? = null,
    ): File {
        destination.parentFile?.mkdirs()
        val temp = File(destination.path + ".tmp")
        try {
            temp.outputStream().use { write(snapshot, files, it, now, progress) }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return destination
    }

    // Reading

    private fun readerFor(input: InputStream): BufferedReader =
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), 1 shl 16)

    private fun readHeader(reader: BufferedReader): Header {
        val line = try {
            reader.readLine()
        } catch (_: Exception) {
            throw Failure.Unreadable()
        }
        return try {
            StrideJson.decodeFromString(Header.serializer(), line ?: throw Failure.NotABackup())
        } catch (e: Failure) {
            throw e
        } catch (_: Exception) {
            throw Failure.NotABackup()
        }
    }

    /** Peek at a backup without applying it, so the user can be told what is in it. */
    fun inspect(input: InputStream): Summary {
        val header = readerFor(input).use { readHeader(it) }
        return Summary(
            activities = header.manifest.activityCount,
            segments = header.segments.size,
            routes = header.routes.size,
            gear = header.gear.size,
            goals = header.goals.size,
            createdAt = header.manifest.createdAt,
        )
    }

    /**
     * Reads the file and writes each track straight to disk as it goes, so memory stays bounded by
     * the single largest activity. Entry lines that cannot be decoded are skipped and counted, so a
     * truncated or partly damaged file still restores everything up to the break.
     *
     * Like iOS, [Header.manifest] `version` is not checked.
     */
    fun read(
        input: InputStream,
        files: StrideFiles,
        existingActivityIds: Set<UUID>,
        mode: RestoreMode,
        progress: ((Double) -> Unit)? = null,
    ): Restored {
        readerFor(input).use { reader ->
            val header = readHeader(reader)
            val activities = ArrayList<Activity>()
            var skipped = 0
            var seen = 0
            val expected = max(1, header.manifest.activityCount).toDouble()

            while (true) {
                val line = try {
                    reader.readLine()
                } catch (_: Exception) {
                    break
                } ?: break
                if (line.isBlank()) continue
                seen += 1
                progress?.invoke(seen / expected)
                val entry = try {
                    StrideJson.decodeFromString(Entry.serializer(), line)
                } catch (_: Exception) {
                    skipped += 1
                    continue
                }
                if (mode == RestoreMode.Merge && existingActivityIds.contains(entry.activity.id)) continue
                if (entry.track.isNotEmpty()) files.saveTrack(entry.track, entry.activity.id)
                activities.add(entry.activity)
            }

            return Restored(
                settings = header.settings,
                activities = activities,
                segments = header.segments,
                segmentEfforts = header.segmentEfforts,
                routes = header.routes,
                gear = header.gear,
                goals = header.goals,
                skipped = skipped,
            )
        }
    }
}

/**
 * Writes a copy of everything on a schedule, into a folder the user can reach, and keeps only the
 * most recent few.
 */
object AutoBackup {

    data class Entry(val filename: String, val file: File, val created: Instant, val byteCount: Long)

    fun filename(at: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): String =
        Backup.suggestedFilename(at)

    /**
     * Everything already saved, newest first. Backups are write-once, so the modification time
     * is used as the creation time (Android file systems often cannot report a birth time).
     */
    fun existing(files: StrideFiles): List<Entry> {
        files.prepare()
        val list = files.backupsDirectory.listFiles { f -> f.isFile && f.extension == Backup.FILE_EXTENSION } ?: return emptyList()
        return list
            .map { Entry(it.name, it, Instant.ofEpochMilli(it.lastModified()), it.length()) }
            .sortedByDescending { it.created }
    }

    /** Delete the oldest until only [keep] remain. A non-positive count disables pruning. */
    fun prune(files: StrideFiles, keep: Int) {
        if (keep <= 0) return
        val all = existing(files)
        if (all.size <= keep) return
        for (entry in all.drop(keep)) entry.file.delete()
    }

    fun delete(entry: Entry) {
        entry.file.delete()
    }

    /** Writes a backup into [StrideFiles.backupsDirectory] and prunes. Call off the main thread. */
    fun run(
        snapshot: Backup.Snapshot,
        files: StrideFiles,
        keep: Int,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Entry {
        files.prepare()
        val destination = File(files.backupsDirectory, filename(now.atZone(zone)))
        Backup.writeToFile(snapshot, files, destination, now)
        prune(files, keep)
        return Entry(destination.name, destination, now, destination.length())
    }
}
