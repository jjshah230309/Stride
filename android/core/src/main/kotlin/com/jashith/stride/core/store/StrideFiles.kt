@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.jashith.stride.core.store

import com.jashith.stride.core.json.InstantSerializer
import com.jashith.stride.core.json.StrideJson
import com.jashith.stride.core.json.UuidSerializer
import com.jashith.stride.core.model.Lap
import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Flat JSON files in the app's private folder, laid out exactly like the iOS Documents folder
 * so a backup, or the whole folder, moves between platforms untouched.
 *
 * No database, no server, no account.
 */
class StrideFiles(
    val root: File,
    private val now: () -> Instant = { Instant.now() },
    private val log: (String) -> Unit = {},
) {
    val tracksDirectory = File(root, "Tracks")
    val photosDirectory = File(root, "Photos")
    val exportsDirectory = File(root, "Exports")
    val backupsDirectory = File(root, "Backups")

    private val _damagedFiles = CopyOnWriteArrayList<String>()

    /** Files that existed but could not be read. They were moved aside, not deleted. */
    val damagedFiles: List<String> get() = _damagedFiles.toList()

    fun prepare() {
        for (dir in listOf(root, tracksDirectory, photosDirectory, exportsDirectory, backupsDirectory)) {
            if (!dir.exists()) dir.mkdirs()
        }
    }

    fun file(name: String): File = File(root, name)

    // JSON files

    /** Write to a sibling first so a crash mid-write cannot destroy the file. */
    fun <T> save(serializer: KSerializer<T>, value: T, name: String): Boolean =
        try {
            prepare()
            val text = StrideJson.encodeToString(serializer, value)
            writeAtomically(file(name), text.toByteArray(StandardCharsets.UTF_8))
            true
        } catch (e: Exception) {
            log("Stride: failed to save $name: ${e.message}")
            false
        }

    /**
     * Returns null when the file is missing, or when it is unreadable. An unreadable file is moved
     * aside under a dated name rather than left in place, because the next save would otherwise
     * overwrite the original bytes and one bad decode would quietly erase a season of training.
     */
    fun <T> load(serializer: KSerializer<T>, name: String): T? {
        val target = file(name)
        if (!target.exists()) return null
        return try {
            StrideJson.decodeFromString(serializer, target.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            log("Stride: failed to load $name: ${e.message}")
            quarantine(target, name)
            null
        }
    }

    private fun quarantine(target: File, name: String) {
        val stamp = now().epochSecond
        val aside = File(root, "$name.damaged-$stamp")
        try {
            Files.move(target.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
            _damagedFiles.add(name)
            log("Stride: kept the unreadable copy at ${aside.name}")
        } catch (e: IOException) {
            log("Stride: could not set aside $name: ${e.message}")
        }
    }

    // Tracks

    fun trackFile(id: UUID): File = File(tracksDirectory, "${id.toString().uppercase()}.json")

    fun saveTrack(points: List<TrackPoint>, id: UUID): Boolean =
        try {
            prepare()
            val text = StrideJson.encodeToString(ListSerializer(TrackPoint.serializer()), points)
            writeAtomically(trackFile(id), text.toByteArray(StandardCharsets.UTF_8))
            true
        } catch (e: Exception) {
            log("Stride: failed to save track: ${e.message}")
            false
        }

    /** Returns an empty list when the track is missing or unreadable, and does not quarantine. */
    fun loadTrack(id: UUID): List<TrackPoint> {
        val target = trackFile(id)
        if (!target.exists()) return emptyList()
        return try {
            StrideJson.decodeFromString(ListSerializer(TrackPoint.serializer()), target.readText(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun hasTrack(id: UUID): Boolean = trackFile(id).exists()

    fun deleteTrack(id: UUID) {
        trackFile(id).delete()
    }

    // Photos

    fun photoFile(filename: String): File = File(photosDirectory, filename)

    fun savePhoto(data: ByteArray): String? =
        try {
            prepare()
            val filename = UUID.randomUUID().toString().uppercase() + ".jpg"
            writeAtomically(photoFile(filename), data)
            filename
        } catch (_: Exception) {
            null
        }

    fun deletePhoto(filename: String) {
        photoFile(filename).delete()
    }

    // Crash recovery

    /** A recording in progress is flushed here every few seconds so a crash does not lose the workout. */
    fun writeRecovery(snapshot: RecoverySnapshot): Boolean =
        save(RecoverySnapshot.serializer(), snapshot, RECOVERY_FILE)

    fun readRecovery(): RecoverySnapshot? = load(RecoverySnapshot.serializer(), RECOVERY_FILE)

    fun clearRecovery() {
        file(RECOVERY_FILE).delete()
    }

    companion object {
        const val ACTIVITIES = "activities.json"
        const val SEGMENTS = "segments.json"
        const val SEGMENT_EFFORTS = "segment-efforts.json"
        const val ROUTES = "routes.json"
        const val GEAR = "gear.json"
        const val GOALS = "goals.json"
        const val SETTINGS = "settings.json"
        const val RECOVERY_FILE = "recording-in-progress.json"

        /** Temp file, flush to disk, then rename over the target. */
        fun writeAtomically(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".tmp")
            java.io.FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

@Serializable
data class RecoverySnapshot(
    val activityID: UUID,
    val sport: SportType,
    val startDate: Instant,
    val points: List<TrackPoint>,
    val elapsed: Double,
    val pausedDuration: Double,
    val laps: List<Lap>,
    val savedAt: Instant,
)
