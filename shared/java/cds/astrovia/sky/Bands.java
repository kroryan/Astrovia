// Astrovia shared code: wavelength bands (gamma ... radio) used to organise the Available data tree and filters. GNU GPL v3.
package cds.astrovia.sky;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Bands {
   private Bands() { }

   /** Band names in display order (highest energy first), then the special groups. */
   public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
         "Gamma-ray", "Hard X-ray", "Soft X-ray", "UV", "Optical", "Near-IR", "Mid-IR", "Far-IR", "Infrared", "Sub-mm / mm", "Radio", "Gas lines", "Solar system", "Other"));

   private static final String[] NAMES = { "Gamma-ray", "Hard X-ray", "Soft X-ray", "UV", "Optical", "Near-IR", "Mid-IR", "Far-IR", "Sub-mm / mm", "Radio" };
   /** Upper wavelength bound of each band, in metres. */
   private static final double[] UPPER = { 1e-11, 1.2e-10, 1e-8, 4e-7, 8e-7, 5e-6, 3e-5, 1e-3, 1e-2, Double.MAX_VALUE };

   /** Band of a wavelength range in metres (centre on a log scale), or null when there is no valid range. */
   public static String ofRange(Double minM, Double maxM) {
      if (minM == null || maxM == null || minM <= 0 || maxM < minM) return null;
      double c = Math.sqrt(minM * maxM);
      for (int i = 0; i < UPPER.length; i++) if (c < UPPER[i]) return NAMES[i];
      return NAMES[NAMES.length - 1];
   }

   /** Band from a free-text regime or folder name ("Image/Infrared/JWST", "X-ray"...), or null when it says nothing. */
   public static String ofText(String text) {
      String t = (text == null ? "" : text).toLowerCase(Locale.ROOT);
      String tt = t.trim();
      if (t.contains("gamma")) return "Gamma-ray";
      if (t.contains("x-ray") || t.contains("xray") || tt.equals("x") || t.contains("/x/") || t.startsWith("x/")) return t.contains("hard") ? "Hard X-ray" : "Soft X-ray";
      if (t.contains("uv") || t.contains("ultra")) return "UV";
      if (t.contains("optic") || t.contains("visible")) return "Optical";
      if (t.contains("near-ir") || t.contains("nir")) return "Near-IR";
      if (t.contains("mid-ir")) return "Mid-IR";
      if (t.contains("far-ir") || tt.equals("fir")) return "Far-IR";
      if (t.contains("infra") || tt.equals("ir")) return "Infrared";
      if (t.contains("millim") || t.contains("sub-mm") || t.contains("submm")) return "Sub-mm / mm";
      if (t.contains("radio") || t.contains("microwave")) return "Radio";
      if (t.contains("gas") || t.startsWith("hi ") || tt.equals("hi")) return "Gas lines";
      return null;
   }

   /** Band of an archive from its short description ("IR", "UV/Opt/IR", "X-ray", "Gamma"...); "Other" when unknown. */
   public static String ofArchive(String band) {
      String b = band == null ? "" : band.toLowerCase(Locale.ROOT);
      if (b.contains("gamma")) return "Gamma-ray";
      if (b.contains("x-ray")) return "Soft X-ray";
      if (b.contains("uv")) return "UV";
      if (b.contains("opt")) return "Optical";
      if (b.contains("fir")) return "Far-IR";
      if (b.contains("ir")) return "Infrared";
      if (b.contains("mm")) return "Sub-mm / mm";
      if (b.contains("radio")) return "Radio";
      return "Other";
   }
}
