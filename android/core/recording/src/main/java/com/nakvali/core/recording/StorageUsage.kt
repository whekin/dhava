package com.nakvali.core.recording

import java.io.File

/** Aggregate size of the files behind one storage settings row. */
data class DirectoryUsage(val fileCount: Int, val totalBytes: Long)

/**
 * Sums the regular files in [dir] whose names end with [suffix] (every file
 * when null). A missing directory counts as empty and subdirectories are not
 * entered — all Nakvali data directories are flat. Performs file IO; call off
 * the main thread.
 */
fun directoryUsage(dir: File, suffix: String? = null): DirectoryUsage {
    val files = dir.listFiles().orEmpty()
        .filter { it.isFile && (suffix == null || it.name.endsWith(suffix)) }
    return DirectoryUsage(
        fileCount = files.size,
        totalBytes = files.sumOf { it.length() },
    )
}
