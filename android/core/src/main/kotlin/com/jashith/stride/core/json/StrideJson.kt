package com.jashith.stride.core.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The single JSON configuration for every file the app reads or writes, mirroring
 * iOS `Store.encoder` / `Store.decoder`.
 *
 * - `encodeDefaults`: Swift's synthesized decoder requires every non-optional key, even ones
 *   that have a default in code, so defaults must always be written.
 * - `explicitNulls = false`: Swift omits nil optionals.
 * - `ignoreUnknownKeys`: unknown keys are ignored by Swift as well.
 * - NaN / Infinity stay disallowed: Swift's encoder throws on them too, and a single one
 *   would make iOS refuse the whole file.
 */
@OptIn(ExperimentalSerializationApi::class)
val StrideJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
