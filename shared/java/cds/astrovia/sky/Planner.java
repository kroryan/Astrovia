// Astrovia shared code: instrument fields of view projected on the sky to plan observations. GNU GPL v3.
package cds.astrovia.sky;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Nominal fields of view (rounded published sizes, arcseconds, x east / y north at PA 0). Real apertures have gaps,
 * offsets and per-date orientation limits that the observatory tools model exactly: use them for proposals.
 */
public final class Planner {
   private Planner() { }

   public static final class Instrument {
      public final String key, label, mission, note;
      /** Each shape is a closed polygon: points {x, y} in arcseconds around the pointing. */
      public final List<double[][]> shapes;
      Instrument(String key, String label, String mission, String note, List<double[][]> shapes) { this.key = key; this.label = label; this.mission = mission; this.note = note; this.shapes = shapes; }
   }

   private static double[][] rect(double cx, double cy, double w, double h) {
      return new double[][] { { cx - w / 2, cy - h / 2 }, { cx + w / 2, cy - h / 2 }, { cx + w / 2, cy + h / 2 }, { cx - w / 2, cy + h / 2 } };
   }

   private static double[][] circle(double r) {
      int n = 48;
      double[][] p = new double[n][];
      for (int i = 0; i < n; i++) p[i] = new double[] { r * Math.cos(2 * Math.PI * i / n), r * Math.sin(2 * Math.PI * i / n) };
      return p;
   }

   private static Instrument inst(String key, String label, String mission, String note, double[][]... shapes) {
      return new Instrument(key, label, mission, note, Collections.unmodifiableList(Arrays.asList(shapes)));
   }

   private static final List<Instrument> ALL;
   static {
      List<Instrument> l = new ArrayList<Instrument>();
      l.add(inst("jwst_nircam", "JWST NIRCam (2 modules)", "JWST", "Short-wavelength modules A and B, ~44\" gap", rect(-86.5, 0, 129, 129), rect(86.5, 0, 129, 129)));
      l.add(inst("jwst_nirspec_msa", "JWST NIRSpec MSA", "JWST", "Four quadrants, 3.6' x 3.4'", rect(-52, 51, 100, 96), rect(52, 51, 100, 96), rect(-52, -51, 100, 96), rect(52, -51, 100, 96)));
      l.add(inst("jwst_niriss", "JWST NIRISS", "JWST", "", rect(0, 0, 132, 132)));
      l.add(inst("jwst_miri", "JWST MIRI imager", "JWST", "", rect(0, 0, 74, 113)));
      l.add(inst("hst_wfc3_uvis", "HST WFC3/UVIS", "HST", "", rect(0, 0, 162, 162)));
      l.add(inst("hst_wfc3_ir", "HST WFC3/IR", "HST", "", rect(0, 0, 136, 123)));
      l.add(inst("hst_acs_wfc", "HST ACS/WFC", "HST", "", rect(0, 51, 202, 100), rect(0, -51, 202, 100)));
      l.add(inst("euclid_vis", "Euclid VIS", "Euclid", "~0.79 x 0.71 deg", rect(0, 0, 2833, 2552)));
      l.add(inst("euclid_nisp", "Euclid NISP", "Euclid", "~0.76 x 0.72 deg", rect(0, 0, 2747, 2599)));
      l.add(inst("xmm_epic", "XMM-Newton EPIC (30')", "XMM-Newton", "", circle(900)));
      l.add(inst("chandra_acis_i", "Chandra ACIS-I", "Chandra", "16.9' x 16.9'", rect(0, 0, 1014, 1014)));
      l.add(inst("rubin_lsst", "Rubin LSST camera", "Rubin", "3.5 deg field", circle(6300)));
      l.add(inst("custom_1deg", "Custom 1° x 1°", "Custom", "", rect(0, 0, 3600, 3600)));
      ALL = Collections.unmodifiableList(l);
   }

   public static List<Instrument> all() { return ALL; }

   public static Instrument byKey(String key) {
      for (Instrument i : ALL) if (i.key.equals(key)) return i;
      return null;
   }

   /**
    * Sky position of a point [xArcsec] east / [yArcsec] north of the pointing in the instrument frame, rotated by [paDeg]
    * (position angle of the +y axis, measured from north towards east), by gnomonic de-projection.
    */
   public static double[] toSky(double raDeg, double decDeg, double paDeg, double xArcsec, double yArcsec) {
      double pa = Math.toRadians(paDeg);
      double e = xArcsec * Math.cos(pa) + yArcsec * Math.sin(pa);
      double n = -xArcsec * Math.sin(pa) + yArcsec * Math.cos(pa);
      double xi = Math.toRadians(e / 3600.0), eta = Math.toRadians(n / 3600.0);
      double ra0 = Math.toRadians(raDeg), d0 = Math.toRadians(decDeg);
      double den = Math.cos(d0) - eta * Math.sin(d0);
      double ra = ra0 + Math.atan2(xi, den);
      double dec = Math.atan2(Math.sin(d0) + eta * Math.cos(d0), Math.sqrt(xi * xi + den * den));
      return new double[] { (Math.toDegrees(ra) % 360.0 + 360.0) % 360.0, Math.toDegrees(dec) };
   }

   /** The polygons of a footprint on the sky: each element is {ra[], dec[]} in degrees. */
   public static List<double[][]> outline(Instrument f, double raDeg, double decDeg, double paDeg) {
      List<double[][]> out = new ArrayList<double[][]>();
      for (double[][] poly : f.shapes) {
         double[] ras = new double[poly.length], decs = new double[poly.length];
         for (int i = 0; i < poly.length; i++) { double[] s = toSky(raDeg, decDeg, paDeg, poly[i][0], poly[i][1]); ras[i] = s[0]; decs[i] = s[1]; }
         out.add(new double[][] { ras, decs });
      }
      return out;
   }
}
