package com.jashith.stride.core.recording

/** Decodes the Bluetooth Heart Rate Measurement characteristic (0x2A37). Port of iOS `parseHeartRate`. */
object HeartRateMeasurement {

    /** The first byte's low bit says whether the value is 8-bit or 16-bit. Null when the packet is too short. */
    fun parse(bytes: ByteArray): Int? {
        if (bytes.size <= 1) return null
        val is16Bit = (bytes[0].toInt() and 0x01) == 0x01
        if (is16Bit) {
            if (bytes.size <= 2) return null
            return (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        }
        return bytes[1].toInt() and 0xFF
    }
}
