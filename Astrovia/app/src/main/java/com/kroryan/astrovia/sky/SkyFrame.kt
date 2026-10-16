package com.kroryan.astrovia.sky

/**
 * Celestial frame in which a HiPS survey is published (`hips_frame` property).
 * The viewer works internally in ICRS; surveys in other frames are rotated on the fly.
 */
enum class SkyFrame {
    /** Equatorial ICRS / J2000: the viewer's native frame. */
    ICRS,

    /** Galactic coordinates (l, b). */
    GALACTIC,

    /**
     * Body-fixed frame of a planet, moon or the Sun (`hips_frame = mars`, `moon`, `titan`...).
     * Those maps have no relation to the celestial sphere, so they are shown in their own
     * (longitude, latitude) system, exactly like Aladin does: no rotation is applied.
     */
    BODY,
    ;

    /** Rotates a unit vector expressed in this frame into ICRS. */
    fun toIcrs(x: Double, y: Double, z: Double): DoubleArray = when (this) {
        ICRS, BODY -> doubleArrayOf(x, y, z)
        // Galactic -> ICRS is the transpose of the standard ICRS -> Galactic matrix.
        GALACTIC -> doubleArrayOf(
            M00 * x + M10 * y + M20 * z,
            M01 * x + M11 * y + M21 * z,
            M02 * x + M12 * y + M22 * z,
        )
    }

    /** (lon, lat) in degrees in this frame -> (ra, dec) in ICRS. */
    fun toIcrsRaDec(lonDeg: Double, latDeg: Double): DoubleArray {
        if (this != GALACTIC) return doubleArrayOf(lonDeg, latDeg)
        val v = Vec3.fromRaDec(lonDeg, latDeg)
        val r = toIcrs(v.x, v.y, v.z)
        return Vec3(r[0], r[1], r[2]).toRaDec()
    }

    companion object {
        // ICRS -> Galactic rotation matrix (Hipparcos / IAU), rows are the galactic axes.
        private const val M00 = -0.0548755604162154
        private const val M01 = -0.8734370902348850
        private const val M02 = -0.4838350155487132
        private const val M10 = 0.4941094278755837
        private const val M11 = -0.4448296299600112
        private const val M12 = 0.7469822444972189
        private const val M20 = -0.8676661490190047
        private const val M21 = -0.1980763734312015
        private const val M22 = 0.4559837761750669

        /** Maps a `hips_frame` / `coordsys` value to a frame. Any body name (mars, moon, titan...) is [BODY]. */
        fun parse(value: String?): SkyFrame = when (value?.trim()?.lowercase()) {
            null, "", "equatorial", "icrs", "icrf", "j2000", "fk5", "c" -> ICRS
            "galactic", "g" -> GALACTIC
            else -> BODY
        }
    }
}
