// Astrovia shared code: which columns of a table are the time and the flux of a light curve. GNU GPL v3.
package cds.astrovia.sky;

import java.util.List;

public final class LightCurveCols {
   private LightCurveCols() { }

   public static final String[] TIME = { "TIME", "BTJD", "BJD", "HJD", "MJD", "JD", "TSTART", "T" };
   public static final String[] FLUX = { "PDCSAP_FLUX", "SAP_FLUX", "FLUX", "KSPSAP_FLUX", "FLUX_CORR", "MAG", "RATE", "COUNTS" };

   private static String find(List<String> cols, String[] names, String not) {
      for (String n : names) for (String c : cols) if (c.equalsIgnoreCase(n) && (not == null || !c.equalsIgnoreCase(not))) return c;
      return null;
   }

   /** {time column, flux column} or null when the table is not a light curve. */
   public static String[] detect(List<String> columns) {
      String t = find(columns, TIME, null);
      if (t == null) return null;
      String f = find(columns, FLUX, t);
      return f == null ? null : new String[] { t, f };
   }
}
