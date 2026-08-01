package com.dhava.core.recording

import okhttp3.Request

internal const val DHAVA_ACCESS_KEY_HEADER = "X-Dhava-Access-Key"

/** Adds the private-alpha perimeter key without interfering with device Bearer identity. */
internal fun Request.Builder.withDhavaAccessKey(
    accessKey: String = BuildConfig.API_ACCESS_KEY,
): Request.Builder = apply {
    if (accessKey.isNotBlank()) {
        header(DHAVA_ACCESS_KEY_HEADER, accessKey)
    }
}
