// Astrovia shared code: the ADQL cone queries sent to the archives. GNU GPL v3.
package cds.astrovia.sky;

import java.util.Locale;

public final class Adql {
   private Adql() { }

   private static String tail(String where, String orderCol) {
      return (where == null || where.isEmpty() ? "" : " AND (" + where + ")") + (orderCol == null || orderCol.isEmpty() ? "" : " ORDER BY " + orderCol + " ASC");
   }

   private static String circle(String raCol, String decCol, double ra, double dec, double rad) {
      return String.format(Locale.US, "1=CONTAINS(POINT('ICRS',%s,%s),CIRCLE('ICRS',%.6f,%.6f,%.6f))", raCol, decCol, ra, dec, Math.max(0.0005, Math.min(90.0, rad)));
   }

   /** Rows of a table inside a cone; with an order column the brightest (smallest) come first so a row limit keeps the useful ones. */
   public static String cone(String table, String raCol, String decCol, double ra, double dec, double radiusDeg, int top, String where, String orderCol) {
      return String.format(Locale.US, "SELECT TOP %d * FROM %s WHERE ", top, table) + circle(raCol, decCol, ra, dec, radiusDeg) + tail(where, orderCol);
   }

   /** Source whose "tap" is this value is a VizieR table, read through VizieR's own cone-search service (no TAP involved). */
   public static final String VIZIER = "vizier";

   /** VizieR cone search as a VOTable: the table id is the VizieR catalogue name (e.g. II/246/out); _RAJ2000/_DEJ2000 are added in degrees. */
   public static String vizierCone(String table, double ra, double dec, double radiusDeg, int max) {
      String c;
      try {
         c = java.net.URLEncoder.encode(String.format(Locale.US, "%.6f %+.6f", ra, dec), "UTF-8");
         table = java.net.URLEncoder.encode(table, "UTF-8");
      } catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
      return String.format(Locale.US, "https://vizier.cds.unistra.fr/viz-bin/votable?-source=%s&-c=%s&-c.rm=%.5f&-out.add=_RAJ,_DEJ&-out.max=%d&-out.meta=DhuL&-oc.form=dm",
         table, c, Math.max(0.0005, Math.min(90.0, radiusDeg)) * 60.0, max);
   }

   /** SIMBAD's TAP counts a cone very slowly (a minute or more); reading the first rows is much faster, so those sources are counted that way. */
   public static boolean countsByRows(String tapUrl) { return tapUrl != null && tapUrl.contains("sim-tap"); }

   /** At most [limit] rows of a cone, one column: counting them gives "limit or more" quickly. */
   public static String countRows(String table, String raCol, String decCol, double ra, double dec, double radiusDeg, String where, int limit) {
      return String.format(Locale.US, "SELECT TOP %d %s FROM %s WHERE ", limit, raCol, table) + circle(raCol, decCol, ra, dec, radiusDeg) + tail(where, null);
   }

   /** Number of rows of a table inside a cone. */
   public static String count(String table, String raCol, String decCol, double ra, double dec, double radiusDeg, String where) {
      return "SELECT COUNT(*) AS n FROM " + table + " WHERE " + circle(raCol, decCol, ra, dec, radiusDeg) + tail(where, null);
   }
}
