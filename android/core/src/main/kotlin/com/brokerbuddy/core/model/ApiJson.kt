package com.brokerbuddy.core.model

import kotlinx.serialization.json.Json

/**
 * The JSON settings the app uses for every API call. Kept here (not in the app) so the
 * contract tests decode server responses exactly as the app does.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    coerceInputValues = true
}
