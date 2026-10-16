package com.kroryan.astrovia.data

import com.kroryan.astrovia.data.vizier.VizieRService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VizierRowsTest {
    private fun votable(fields: List<String>, rows: List<List<String>>): String =
        "<?xml version=\"1.0\"?><VOTABLE><RESOURCE><TABLE>" +
            fields.joinToString("") { "<FIELD name=\"$it\"/>" } +
            "<DATA><TABLEDATA>" + rows.joinToString("") { r -> "<TR>" + r.joinToString("") { "<TD>$it</TD>" } + "</TR>" } +
            "</TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>"

    @Test
    fun readsVizierColumns() {
        val rows = VizieRService().rowsFromVotable(
            votable(listOf("_RAJ2000", "_DEJ2000", "Source", "Gmag"), listOf(listOf("83.8", "-5.4", "123", "12.5"))),
        )
        assertEquals(1, rows.size)
        assertEquals(83.8, rows[0].raDeg, 1e-9)
        assertEquals(12.5, rows[0].mag!!, 1e-9)
        assertEquals("123", rows[0].id)
    }

    @Test
    fun readsSimbadColumnsCaseInsensitively() {
        // A catalogue downloaded from SIMBAD (lower-case ra/dec/main_id/flux_v) can be shown too.
        val rows = VizieRService().rowsFromVotable(
            votable(listOf("main_id", "otype", "ra", "dec", "flux_v"), listOf(listOf("M  42", "HII", "83.82", "-5.39", "4.0"))),
        )
        assertEquals(1, rows.size)
        assertEquals("M  42", rows[0].id)
        assertEquals(4.0, rows[0].mag!!, 1e-9)
    }

    @Test
    fun onlyTheFirstTableIsReadAndBinaryIsRejected() {
        val two = votable(listOf("ra", "dec"), listOf(listOf("1", "2"))) .replace("</VOTABLE>", "") +
            "<TABLE><FIELD name=\"ra\"/><FIELD name=\"dec\"/><DATA><TABLEDATA><TR><TD>9</TD><TD>9</TD></TR></TABLEDATA></DATA></TABLE></VOTABLE>"
        assertEquals(1, VotableParser.parse(two).size)
        val binary = "<VOTABLE><RESOURCE><TABLE><FIELD name=\"ra\"/><DATA><BINARY2><STREAM>AAAA</STREAM></BINARY2></DATA></TABLE></RESOURCE></VOTABLE>"
        var failed = false
        try { VotableParser.parse(binary) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue("BINARY2 must be reported, not read as 0 rows", failed)
        assertNotNull(VizieRService().rowsFromVotable(votable(listOf("ra", "dec"), emptyList())))
    }
}
