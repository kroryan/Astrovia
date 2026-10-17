package com.kroryan.astrovia.archive

import com.kroryan.astrovia.model.Target
import com.kroryan.astrovia.model.TargetLists
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetListsTest {
    @Test
    fun parsesCsvJsonAndNames() {
        val (t, names) = TargetLists.parse("name,ra_deg,dec_deg\n\"M 42\",83.82,-5.39\nNGC 602,22.35,-73.56\nVega\n# comment\n")
        assertEquals(2, t.size); assertEquals("M 42", t[0].name); assertEquals(-73.56, t[1].decDeg, 1e-9); assertEquals(listOf("Vega"), names)
    }

    @Test
    fun csvRoundTrip() {
        val l = listOf(Target("M 1", 83.63, 22.01), Target("a \"quoted\" name", 1.0, 2.0))
        val (back, names) = TargetLists.parse(TargetLists.toCsv(l))
        assertEquals(2, back.size); assertEquals(0, names.size); assertEquals(83.63, back[0].raDeg, 1e-9)
    }
}
