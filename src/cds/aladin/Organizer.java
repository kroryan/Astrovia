// Astrovia: alternative organisations of the "Available data" tree (by wavelength, by mission), shared in spirit
// with the Android app. Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.util.Locale;

/** Computes the tree path of a collection for the chosen organisation. */
final class Organizer {
   private Organizer() { }

   static final int DEFAULT = 0, WAVELENGTH = 1, MISSION = 2, CENTRE = 3;
   static final String[] NAMES = { "Default (folders)", "By wavelength", "By mission", "By data centre" };

   /** Band of a wavelength range in metres, or null. The logic is shared with the Android app (cds.astrovia.sky.Bands). */
   static String bandOfRange(double minM, double maxM) { return cds.astrovia.sky.Bands.ofRange(minM, maxM); }

   /** Band from a free-text regime or folder name, or null. */
   static String bandOfText(String text) { return cds.astrovia.sky.Bands.ofText(text); }

   private static double num(String s) {
      try { return s == null ? Double.NaN : Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
   }

   /**
    * Path (folders only, no label) for a record. {@code category} is the original folder ("Image/Optical/DSS"),
    * {@code regime}, {@code emMin}, {@code emMax} come from the MocServer record.
    */
   static String folders(int mode, String category, String regime, String emMin, String emMax, String id) {
      String cat = category == null ? "" : category;
      String[] seg = cat.isEmpty() ? new String[0] : cat.split("/");
      if (seg.length > 0 && (seg[0].equals("Solar system") || seg[0].equals("Deprecated") || seg[0].equals("Ancillary") || seg[0].equals("Outreach"))) return cat;
      String kind = seg.length > 0 ? seg[0] : "Others";
      if (mode == WAVELENGTH) {
         String band = bandOfRange(num(emMin), num(emMax));
         if (band == null) band = bandOfText(regime);
         if (band == null) band = bandOfText(cat);
         if (band == null) band = "Other";
         String rest = seg.length > 2 ? "/" + join(seg, 2) : (seg.length > 1 && bandOfText(seg[1]) == null ? "/" + seg[1] : "");
         return "By wavelength/" + band + "/" + kind + rest;
      }
      if (mode == MISSION) {
         String mission = seg.length > 2 ? seg[seg.length - 1] : (seg.length > 1 ? seg[1] : (id != null && id.contains("/") ? id.substring(0, id.indexOf('/')) : "Others"));
         return "By mission/" + mission + "/" + kind;
      }
      if (mode == CENTRE) {
         String p = id != null && id.contains("/") ? id.substring(0, id.indexOf('/')) : "Others";
         String centre = p.equals("CDS") ? "CDS (Strasbourg)" : p.equals("ESAVO") ? "ESA" : p;
         return "By data centre/" + centre + "/" + kind;
      }
      return cat;
   }

   /** Band of an archive source from its short description ("IR", "UV/Opt/IR", "X-ray"...). */
   static String bandOfArchive(String band) { return cds.astrovia.sky.Bands.ofArchive(band); }

   private static String join(String[] a, int from) {
      StringBuilder sb = new StringBuilder();
      for (int i = from; i < a.length; i++) { if (sb.length() > 0) sb.append('/'); sb.append(a[i]); }
      return sb.toString();
   }
}
