package com.dhava.core.recording

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageUsageTest {
    @Test fun `sums only files matching the suffix in the flat directory`() {
        val root = Files.createTempDirectory("dhava-usage").toFile()
        root.resolve("a.jsonl.gz").writeBytes(ByteArray(10))
        root.resolve("b.jsonl.gz").writeBytes(ByteArray(5))
        root.resolve("index.json").writeBytes(ByteArray(7))
        root.resolve("nested").mkdirs()
        root.resolve("nested/c.jsonl.gz").writeBytes(ByteArray(99))

        assertEquals(DirectoryUsage(fileCount = 2, totalBytes = 15L), directoryUsage(root, ".jsonl.gz"))
    }

    @Test fun `null suffix counts every regular file but never directories`() {
        val root = Files.createTempDirectory("dhava-usage-all").toFile()
        root.resolve("a.gz").writeBytes(ByteArray(3))
        root.resolve("b.json").writeBytes(ByteArray(4))
        root.resolve("sub").mkdirs()

        assertEquals(DirectoryUsage(fileCount = 2, totalBytes = 7L), directoryUsage(root))
    }

    @Test fun `missing directory counts as empty`() {
        val root = Files.createTempDirectory("dhava-usage-missing").toFile()

        assertEquals(DirectoryUsage(fileCount = 0, totalBytes = 0L), directoryUsage(root.resolve("absent"), ".gz"))
    }
}
