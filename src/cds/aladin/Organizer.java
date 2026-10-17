// Astrovia: alternative organisations of the "Available data" tree (by wavelength, by mission), shared in spirit
// with the Android app. Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.util.Locale;

/** Computes the tree path of a collection for the chosen organisation. */
final class Organizer {
   private Organizer() { }

   static final int DEFAULT = 0, WAVELENGTH = 1, MISSION = 2, CENTRE = 3;
   static final String[] NAMES = { "Default (folders)", "By wavelength", "By mission", "By data centre" };

   /** Bands ordered from the highest to the lowest energy; the numbers are wavelength upper bounds in metres. */
   private static final String[] BAND = { "Gamma-ray", "Hard X-ray", "Soft X-ray", "UV", "Optical", "Near-IR", "Mid-IR", "Far-IR", "Sub-mm / mm", "Radio" };
   private static final double[] UPPER = { 1e-11, 1.2e-10, 1e-8, 4e-7, 8e-7, 5e-6, 3e-5, 1e-3, 1e-2, Double.MAX_VALUE };

   /** Band of a wavelength range in metres (centre on a log scale), or null. */
   static String bandOfRange(double minM, double maxM) {
      if (!(minM > 0) || !(maxM >= minM)) return null;
      double c = Math.sqrt(minM * maxM);
      for (int i = 0; i < UPPER.length; i++) if (c < UPPER[i]) return BAND[i];
      return BAND[BAND.length - 1];
   }

   /** Band from the free-text regime or the folder of the collection (fallback when there is no wavelength range). */
   static String bandOfText(String text) {
      if (text == null) return null;
      String t = text.toLowerCase(Locale.ROOT);
      if (t.contains("gamma")) return "Gamma-ray";
      if (t.contains("x-ray") || t.contains("xray") || t.equals("x") || t.contains("/x/") || t.startsWith("x/")) return t.contains("hard") ? "Hard X-ray" : "Soft X-ray";
      if (t.contains("euv") || t.contains("uv") || t.contains("ultra")) return "UV";
      if (t.contains("optic") || t.contains("visible")) return "Optical";
      if (t.contains("near-ir") || t.contains("nir")) return "Near-IR";
      if (t.contains("mid-ir") || t.contains("mir")) return "Mid-IR";
      if (t.contains("far-ir") || t.contains("fir")) return "Far-IR";
      if (t.contains("infra")) return "Infrared";
      if (t.contains("millim") || t.contains("sub-mm") || t.contains("submm")) return "Sub-mm / mm";
      if (t.contains("radio") || t.contains("microwave")) return "Radio";
      if (t.contains("gas")) return "Gas lines";
      return null;
   }

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
         if (band == null) band = "Other wavelengths";
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

   /** Band of an archive source from its short description ("IR", "UV/Opt/IR", "X-ray", "Gamma"...). */
   static String bandOfArchive(String band) {
      String b = band == null ? "" : band.toLowerCase(Locale.ROOT);
      if (b.contains("gamma")) return "Gamma-ray";
      if (b.contains("x-ray")) return "Soft X-ray";
      if (b.contains("uv")) return "UV";
      if (b.contains("opt")) return "Optical";
      if (b.contains("fir")) return "Far-IR";
      if (b.contains("ir")) return "Infrared";
      if (b.contains("mm")) return "Sub-mm / mm";
      if (b.contains("radio")) return "Radio";
      return "Other wavelengths";
   }

   private static String join(String[] a, int from) {
      StringBuilder sb = new StringBuilder();
      for (int i = from; i < a.length; i++) { if (sb.length() > 0) sb.append('/'); sb.append(a[i]); }
      return sb.toString();
   }
}
