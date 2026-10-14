package com.jashith.stride.core.io

import java.io.ByteArrayOutputStream

/**
 * A deliberately independent FIT writer for tests: it can produce shapes our own encoder never
 * writes (big-endian, any field order, omitted fields, developer fields, unknown messages, a local
 * type redefined mid-file, compressed-timestamp headers).
 */
class FitTestBuilder {
    private val body = ByteArrayOutputStream()

    class Field(val number: Int, val size: Int, val baseType: Int)

    fun num(v: Long, size: Int, big: Boolean): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) {
            val shift = 8 * i
            val byte = ((v shr shift) and 0xFF).toByte()
            if (big) out[size - 1 - i] = byte else out[i] = byte
        }
        return out
    }

    fun definition(local: Int, global: Int, fields: List<Field>, big: Boolean = false, developer: List<Field>? = null) {
        body.write(0x40 or (local and 0x0F) or (if (developer != null) 0x20 else 0))
        body.write(0)
        body.write(if (big) 1 else 0)
        body.write(num(global.toLong(), 2, big))
        body.write(fields.size)
        for (f in fields) {
            body.write(f.number); body.write(f.size); body.write(f.baseType)
        }
        if (developer != null) {
            body.write(developer.size)
            for (f in developer) {
                body.write(f.number); body.write(f.size); body.write(f.baseType)
            }
        }
    }

    fun data(local: Int, vararg chunks: ByteArray) {
        body.write(local and 0x0F)
        for (c in chunks) body.write(c)
    }

    fun raw(vararg bytes: Int) {
        for (b in bytes) body.write(b)
    }

    fun build(headerSize: Int = 14, writeCrc: Boolean = true, declaredSize: Long? = null): ByteArray {
        val content = body.toByteArray()
        val header = ByteArrayOutputStream()
        header.write(headerSize)
        header.write(0x10)
        header.write(num(2000, 2, false))
        header.write(num(declaredSize ?: content.size.toLong(), 4, false))
        header.write(".FIT".toByteArray())
        if (headerSize == 14) header.write(num(Fit.crc16(header.toByteArray()).toLong(), 2, false))
        val withoutCrc = header.toByteArray() + content
        val crc = if (writeCrc) Fit.crc16(withoutCrc) else 0
        return withoutCrc + num(crc.toLong(), 2, false)
    }
}
