package com.kroryan.astrovia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNoticeTest {
    @Test
    fun oneDownloadShowsItsLine() {
        val (title, text) = DownloadNotice.format(listOf("DSS2 color: 120 tiles, 5.4 MB"))
        assertEquals("Astrovia download", title)
        assertEquals("DSS2 color: 120 tiles, 5.4 MB", text)
    }

    @Test
    fun severalDownloadsAreSummarised() {
        val (title, text) = DownloadNotice.format(listOf("A: 1 tiles, 1 KB", "Gaia: 2.0 MB"))
        assertEquals("Astrovia: 2 downloads", title)
        assertTrue(text.contains("A: 1 tiles") && text.contains("Gaia: 2.0 MB"))
    }

    @Test
    fun noDownloadsShowsAClosingMessage() {
        assertEquals("Finishing...", DownloadNotice.format(emptyList()).second)
    }

    @Test
    fun linesAndSizes() {
        assertEquals("DSS: 34 tiles, 1.5 MB", DownloadNotice.hipsLine("DSS", 34, (1.5 * 1048576).toLong()))
        assertEquals("Simbad: 512 B", DownloadNotice.catalogLine("Simbad", 512))
        assertEquals("2.0 GB", DownloadNotice.human(2L * 1073741824))
    }
}
