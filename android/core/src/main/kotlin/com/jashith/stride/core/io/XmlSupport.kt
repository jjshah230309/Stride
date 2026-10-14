package com.jashith.stride.core.io

import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import javax.xml.parsers.SAXParserFactory

/**
 * SAX works on both the JVM and Android (StAX does not exist on Android). Namespace processing is
 * off so element names keep their prefixes, as with the iOS XMLParser.
 */
internal fun parseXml(data: ByteArray, handler: DefaultHandler) {
    val factory = SAXParserFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isValidating = false
    // Activity files never need a DTD; refuse entities so a hostile file cannot reach out or balloon.
    // Android's parser does not know these features and is already safe, hence the guards.
    for ((feature, value) in listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
    )) {
        try {
            factory.setFeature(feature, value)
        } catch (_: Exception) {
        }
    }
    factory.newSAXParser().parse(ByteArrayInputStream(data), handler)
}

/** Swift's `Double("...")`: null when the text is not a number. Rejects the suffixes Java tolerates. */
internal fun parseDouble(text: String): Double? {
    if (text.isEmpty()) return null
    val last = text.last()
    if (last == 'f' || last == 'F' || last == 'd' || last == 'D') return null
    return text.toDoubleOrNull()
}

/** A coordinate as plain decimal text, never scientific notation, which XML decimals do not allow. */
internal fun plainDecimal(value: Double): String {
    val text = value.toString()
    return if (text.contains('E') || text.contains('e')) BigDecimal(text).stripTrailingZeros().toPlainString() else text
}

internal fun escapeXml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
