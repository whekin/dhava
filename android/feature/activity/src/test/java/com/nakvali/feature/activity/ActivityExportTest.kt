package com.nakvali.feature.activity

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ActivityExportTest {
    @Test fun `saving copies exact bytes and closes the provider stream`() {
        val source = Files.createTempFile("nakvali-export", ".gpx").toFile()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        source.writeBytes(bytes)
        var closed = false
        val target = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        try {
            copyExportFile(source) { target }
            assertArrayEquals(bytes, target.toByteArray())
            assertArrayEquals(bytes, source.readBytes())
            assertTrue(closed)
        } finally { source.delete() }
    }

    @Test fun `missing cached export never opens destination`() {
        val directory = Files.createTempDirectory("nakvali-missing-export").toFile()
        try {
            assertThrows(IOException::class.java) {
                copyExportFile(File(directory, "missing.gpx")) { fail("Must not open destination"); null }
            }
        } finally { directory.delete() }
    }

    @Test fun `provider refusal and write failures propagate instead of reporting success`() {
        val source = Files.createTempFile("nakvali-export", ".gz").toFile()
        source.writeBytes(byteArrayOf(1, 2, 3))
        try {
            assertThrows(IllegalStateException::class.java) { copyExportFile(source) { null } }
            var closed = false
            assertThrows(IOException::class.java) {
                copyExportFile(source) {
                    object : OutputStream() {
                        override fun write(value: Int) { throw IOException("Storage full") }
                        override fun close() { closed = true }
                    }
                }
            }
            assertTrue(closed)
        } finally { source.delete() }
    }
}
