package com.kroryan.astrovia.archive

import com.kroryan.astrovia.data.archive.ArchiveHit
import com.kroryan.astrovia.data.archive.ArchiveKind
import com.kroryan.astrovia.data.archive.ArchiveSource
import com.kroryan.astrovia.data.hips.HiPSBands
import com.kroryan.astrovia.data.hips.HiPSSurvey
import com.kroryan.astrovia.ui.ChartData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDataTest {
    private val src = ArchiveSource("x", "X", "M", "IR", ArchiveKind.OBSERVATIONS, "t", "ra", "dec", null, null, null, null, "")
    private fun hit(vararg f: Pair<String, String>) = ArchiveHit(src, "id", "t", 0.0, 0.0, emptyList(), null, mapOf(*f))

    @Test
    fun yearsFromIsoMjdAndYear() {
        assertEquals(2010, ChartData.year("2010-10-01 10:03:54.849"))
        assertEquals(2024, ChartData.year("60400.5"))   // MJD 60400 = 2024-04
        assertEquals(1999, ChartData.year("1999"))
        assertEquals(null, ChartData.year("abc"))
    }

    @Test
    fun groupsAndYears() {
        val hits = listOf(hit("instrument" to "NIRCAM", "start_time" to "2023-01-02"), hit("instrument" to "NIRCAM", "start_time" to "2023-05-02"), hit("instrument" to "MIRI", "start_time" to "2024-01-02"))
        val g = ChartData.groups(hits)!!
        assertEquals("instrument", g.first); assertEquals("NIRCAM" to 2, g.second[0])
        assertEquals(mapOf(2023 to 2, 2024 to 1), ChartData.years(hits)!!.second)
    }

    @Test
    fun wavelengthAndBands() {
        assertEquals(1e-6 to 5e-6, ChartData.wavelength(hit("em_min" to "1e-6", "em_max" to "5e-6")))
        assertNotNull(ChartData.bandRange("UV/Opt/IR"))
        assertTrue(ChartData.bandRange("X-ray")!!.second <= 1e-8)
    }

    @Test
    fun hipsBands() {
        fun s(cat: String, reg: String = "", lo: Double? = null, hi: Double? = null) = HiPSSurvey("i", "t", "u", category = cat, regime = reg, emMin = lo, emMax = hi)
        assertEquals("Radio", HiPSBands.of(s("Image/Radio/ACT")))
        assertEquals("Infrared", HiPSBands.of(s("Image/Infrared/JWST")))
        assertEquals("Near-IR", HiPSBands.of(s("Image/Infrared/JWST", "Infrared", 1e-6, 3e-6)))
        assertEquals("Gamma-ray", HiPSBands.of(s("Image/Gamma-ray/EGRET")))
        assertEquals("Hard X-ray", HiPSBands.of(s("", "", 1e-11, 1e-10)))
        assertEquals("Optical", HiPSBands.of(s("", "", 4.5e-7, 7e-7)))
        assertEquals("Solar system", HiPSBands.of(s("Solar system/Mars")))
        assertEquals("Soft X-ray", HiPSBands.of(s("", "X-ray")))
        assertEquals("Gas lines", HiPSBands.of(s("Image/Gas-lines/CO")))
        assertEquals("Soft X-ray", HiPSBands.ofArchive("X-ray"))
        assertEquals("Infrared", HiPSBands.ofArchive("IR"))
    }
}
