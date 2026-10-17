package com.kroryan.astrovia.archive

import com.kroryan.astrovia.data.archive.VotableTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

class VotableTableTest {
    private val head = """<?xml version="1.0"?><VOTABLE version="1.4" xmlns="http://www.ivoa.net/xml/VOTable/v1.3"><RESOURCE type="results">
        <INFO name="QUERY_STATUS" value="OK"/><TABLE>
        <FIELD name="name" datatype="char" arraysize="*"/><FIELD name="ra" datatype="double"/><FIELD name="n" datatype="int"/>"""

    @Test
    fun tabledata() {
        val x = head + "<DATA><TABLEDATA><TR><TD>A</TD><TD>1.5</TD><TD>3</TD></TR><TR><TD>B</TD><TD>2.5</TD><TD>4</TD></TR></TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>"
        val r = VotableTable.parse(x)
        assertEquals(2, r.size); assertEquals("B", r[1]["name"]); assertEquals("1.5", r[0]["ra"])
    }

    @Test
    fun binaryStream() {
        val o = ByteArrayOutputStream(); val d = DataOutputStream(o)
        fun row(n: String, ra: Double, k: Int) { d.writeInt(n.length); d.write(n.toByteArray()); d.writeDouble(ra); d.writeInt(k) }
        row("GRB120403857", 83.82, 7); row("GRB2", -5.5, 8)
        val x = head + "<DATA><BINARY><STREAM encoding=\"base64\">" + Base64.getEncoder().encodeToString(o.toByteArray()) + "</STREAM></BINARY></DATA></TABLE></RESOURCE></VOTABLE>"
        val r = VotableTable.parse(x)
        assertEquals(2, r.size)
        assertEquals("GRB120403857", r[0]["name"]); assertEquals("83.82", r[0]["ra"]); assertEquals("8", r[1]["n"])
    }

    @Test
    fun errorStatusThrows() {
        val x = """<VOTABLE><RESOURCE><INFO name="QUERY_STATUS" value="ERROR">Bad table</INFO></RESOURCE></VOTABLE>"""
        try { VotableTable.parse(x); throw AssertionError("should fail") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("Bad table")) }
    }
}
