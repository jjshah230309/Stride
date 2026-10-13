package com.jashith.stride.core.io

import com.jashith.stride.core.model.SportType
import com.jashith.stride.core.model.TrackPoint
import java.time.Instant
import java.util.Locale

/**
 * One way in for every supported file. Sniffs the contents rather than trusting the extension,
 * because exports arrive with all sorts of names.
 */
object ActivityFile {

    enum class Format {
        Gpx, Tcx, Fit;

        val displayName: String get() = name.uppercase(Locale.ROOT)
    }

    data class Imported(
        val format: Format,
        val name: String,
        val sport: SportType?,
        val startDate: Instant,
        val points: List<TrackPoint>,
        val isRoute: Boolean = false,
        /** Set when the file itself says its data is damaged. */
        val warning: String? = null,
    )

    sealed class Failure(message: String) : Exception(message) {
        class Unrecognised : Failure("That is not a GPX, TCX or FIT file.")
        class Empty : Failure("The file has no track data in it.")
    }

    /** MIME types and extensions a file picker should offer. */
    val readableMimeTypes: Array<String> = arrayOf("application/gpx+xml", "application/vnd.garmin.tcx+xml", "application/xml", "text/xml", "application/octet-stream", "*/*")

    fun detect(data: ByteArray, filename: String = ""): Format? {
        // FIT announces itself at a fixed offset in the header.
        if (data.size > 12) {
            if (data[8] == '.'.code.toByte() && data[9] == 'F'.code.toByte() && data[10] == 'I'.code.toByte() && data[11] == 'T'.code.toByte()) {
                return Format.Fit
            }
        }

        // For XML, look at the opening tags rather than the whole document.
        val head = String(data, 0, minOf(data.size, 4096), Charsets.UTF_8)
        if (head.contains("<TrainingCenterDatabase")) return Format.Tcx
        if (head.contains("<gpx") || head.contains("<trk") || head.contains("<rte")) return Format.Gpx

        return when (extension(filename)) {
            "fit" -> Format.Fit
            "tcx" -> Format.Tcx
            "gpx" -> Format.Gpx
            else -> null
        }
    }

    @Throws(Exception::class)
    fun load(data: ByteArray, filename: String = "", now: () -> Instant = { Instant.now() }): Imported {
        val format = detect(data, filename) ?: throw Failure.Unrecognised()

        when (format) {
            Format.Fit -> {
                val decoder = FitDecoder(now)
                val result = decoder.decode(data)
                if (result.points.isEmpty()) throw Failure.Empty()
                return Imported(
                    format = Format.Fit,
                    name = result.name.ifEmpty { defaultName(filename) },
                    sport = result.sport,
                    startDate = result.startDate,
                    points = result.points,
                    warning = if (decoder.checksumMismatch) "The file's checksum did not match, so it may be incomplete." else null,
                )
            }

            Format.Tcx -> {
                val result = TcxImporter(now).parse(data)
                if (result == null || result.points.isEmpty()) throw Failure.Empty()
                // A TCX Id is a timestamp, which makes a poor activity name.
                val name = if (result.name.isEmpty() || result.name.startsWith("20")) defaultName(filename) else result.name
                return Imported(Format.Tcx, name, result.sport, result.startDate, result.points)
            }

            Format.Gpx -> {
                val result = GpxImporter(now).parse(data)
                if (result == null || result.points.isEmpty()) throw Failure.Empty()
                return Imported(
                    format = Format.Gpx,
                    name = result.name.ifEmpty { defaultName(filename) },
                    sport = sport(fromName = result.name),
                    startDate = result.startDate,
                    points = result.points,
                    isRoute = result.isRoute,
                )
            }
        }
    }

    private fun extension(filename: String): String {
        val dot = filename.lastIndexOf('.')
        return if (dot < 0) "" else filename.substring(dot + 1).lowercase(Locale.ROOT)
    }

    private fun stem(filename: String): String {
        val dot = filename.lastIndexOf('.')
        return if (dot <= 0) filename else filename.substring(0, dot)
    }

    fun defaultName(filename: String): String {
        val s = stem(filename)
        return if (s.isEmpty()) "Imported activity" else s
    }

    /** Last resort when the file does not say which sport it was. */
    fun sport(fromName: String): SportType? {
        val lower = fromName.lowercase(Locale.ROOT)
        if (lower.contains("ride") || lower.contains("cycl") || lower.contains("bike")) return SportType.Ride
        if (lower.contains("walk")) return SportType.Walk
        if (lower.contains("hike")) return SportType.Hike
        if (lower.contains("run")) return SportType.Run
        return null
    }
}
