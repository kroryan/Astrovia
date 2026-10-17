// Astrovia shared code (Android and desktop): when can a sky position be observed by a Sun-constrained observatory. GNU GPL v3.
package cds.astrovia.sky;

import java.util.ArrayList;
import java.util.List;

/** Low-precision solar ephemeris (about 0.01 degrees), enough for planning windows measured in days. */
public final class Visibility {
   private Visibility() { }

   private static final double J2000 = 2451545.0;

   /** Approximate published pointing limits (Sun angle, degrees); real schedules add more constraints. */
   public static final String[] PRESET_NAMES = { "JWST (Sun 85\u00b0-135\u00b0)", "Euclid (Sun ~87\u00b0-121\u00b0)", "Generic (Sun > 45\u00b0)" };
   public static final double[][] PRESET_LIMITS = { { 85, 135 }, { 87, 121 }, { 45, 180 } };

   public static double julianDate(long epochMillis) { return epochMillis / 86_400_000.0 + 2440587.5; }

   /** Sun RA/Dec in degrees at a Julian date. */
   public static double[] sunPosition(double jd) {
      double n = jd - J2000;
      double l = Math.toRadians((280.460 + 0.9856474 * n) % 360.0);
      double g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0);
      double lambda = l + Math.toRadians(1.915) * Math.sin(g) + Math.toRadians(0.020) * Math.sin(2 * g);
      double eps = Math.toRadians(23.439 - 0.0000004 * n);
      double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
      double dec = Math.asin(Math.sin(eps) * Math.sin(lambda));
      return new double[] { (Math.toDegrees(ra) + 360.0) % 360.0, Math.toDegrees(dec) };
   }

   /** Sun-target angle in degrees. */
   public static double elongation(double raDeg, double decDeg, double jd) {
      double[] s = sunPosition(jd);
      double a = Math.toRadians(raDeg), d = Math.toRadians(decDeg);
      double sa = Math.toRadians(s[0]), sd = Math.toRadians(s[1]);
      double c = Math.sin(d) * Math.sin(sd) + Math.cos(d) * Math.cos(sd) * Math.cos(a - sa);
      return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, c))));
   }

   /** Ranges {startJd, endJd} in which the Sun angle stays within [minSunDeg, maxSunDeg]. */
   public static List<double[]> windows(double raDeg, double decDeg, double minSunDeg, double maxSunDeg, double startJd, int days, double stepDays) {
      List<double[]> out = new ArrayList<double[]>();
      double open = Double.NaN;
      double end = startJd + days;
      for (double jd = startJd; jd <= end; jd += stepDays) {
         double e = elongation(raDeg, decDeg, jd);
         boolean ok = e >= minSunDeg && e <= maxSunDeg;
         if (ok && Double.isNaN(open)) open = jd;
         if (!ok && !Double.isNaN(open)) { out.add(new double[] { open, jd }); open = Double.NaN; }
      }
      if (!Double.isNaN(open)) out.add(new double[] { open, end });
      return out;
   }
}
