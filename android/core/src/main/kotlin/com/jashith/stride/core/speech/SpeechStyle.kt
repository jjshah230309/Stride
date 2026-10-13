package com.jashith.stride.core.speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How chatty the coach is. Content is chosen by the metric list; this decides
 * how much scaffolding goes around the numbers.
 */
@Serializable
enum class SpeechStyle {
    @SerialName("brief") Brief,
    @SerialName("natural") Natural,
    @SerialName("detailed") Detailed;

    val displayName: String
        get() = when (this) {
            Brief -> "Brief"
            Natural -> "Natural"
            Detailed -> "Detailed"
        }

    val explanation: String
        get() = when (this) {
            Brief -> "Numbers only. Fastest to hear mid-effort."
            Natural -> "Short labels, the way a training partner would say it."
            Detailed -> "Full sentences with units spelled out."
        }
}
