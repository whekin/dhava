package com.nakvali.core.recording

import com.nakvali.fusion.CanonicalProgress

/** A retained GPS preview can outlive the calculation that produced it. */
data class CanonicalLoadEvent(val progress: CanonicalProgress, val finished: Boolean)
