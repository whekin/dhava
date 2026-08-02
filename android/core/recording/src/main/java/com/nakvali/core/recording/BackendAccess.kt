package com.nakvali.core.recording

import okhttp3.Request

internal const val NAKVALI_ACCESS_KEY_HEADER = "X-Nakvali-Access-Key"

/** Adds the private-alpha perimeter key without interfering with device Bearer identity. */
internal fun Request.Builder.withNakvaliAccessKey(
    accessKey: String = BuildConfig.API_ACCESS_KEY,
): Request.Builder = apply {
    if (accessKey.isNotBlank()) {
        header(NAKVALI_ACCESS_KEY_HEADER, accessKey)
    }
}
