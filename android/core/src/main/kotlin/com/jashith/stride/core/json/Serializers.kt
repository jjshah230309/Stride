package com.jashith.stride.core.json

import com.jashith.stride.core.model.Coord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** iOS writes `uuidString`, which is upper case. It reads either case. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) =
        encoder.encodeString(value.toString().uppercase(Locale.ROOT))
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/**
 * iOS uses `.iso8601`: UTC, whole seconds, trailing `Z` (`2024-05-01T07:12:33Z`).
 * It cannot decode fractional seconds, so we never write them. We do accept them on the way in,
 * along with numeric offsets, because files from other sources may carry either.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(formatIso(value))
    override fun deserialize(decoder: Decoder): Instant = parseIso(decoder.decodeString())

    fun formatIso(value: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS))

    fun parseIso(text: String): Instant =
        try {
            Instant.parse(text)
        } catch (_: Exception) {
            OffsetDateTime.parse(text).toInstant()
        }
}

/** A coordinate is a two-element array `[lat, lon]`, never an object. */
object CoordSerializer : KSerializer<Coord> {
    private val delegate = ListSerializer(Double.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Coord) =
        delegate.serialize(encoder, listOf(value.lat, value.lon))
    override fun deserialize(decoder: Decoder): Coord {
        val values = delegate.deserialize(decoder)
        require(values.size >= 2) { "A coordinate needs latitude and longitude" }
        return Coord(values[0], values[1])
    }
}
