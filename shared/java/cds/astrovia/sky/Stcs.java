// Astrovia shared code: parser of the footprint notations used by the archives (STC-S "Polygon ICRS ...", "Circle ...", "Box ..."). GNU GPL v3.
package cds.astrovia.sky;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Stcs {
   private Stcs() { }

   private static final Pattern NUM = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");
   private static final Pattern SHAPE = Pattern.compile("(?i)(polygon|circle|box)");
   private static final Pattern FRAME = Pattern.compile("(?i)\\b(icrs|fk5|j2000|galactic|geocenter|unknownframe|union|intersection|not)\\b");

   /** Shapes of a region as closed polygons {ra[], dec[]} in degrees ("Union ICRS (Polygon ... Polygon ...)" gives several). */
   public static List<double[][]> parse(String text) {
      List<double[][]> out = new ArrayList<double[][]>();
      if (text == null || text.trim().isEmpty()) return out;
      List<int[]> marks = new ArrayList<int[]>();       // {start, end of the keyword, kind}
      Matcher m = SHAPE.matcher(text);
      while (m.find()) marks.add(new int[] { m.start(), m.end(), kind(m.group(1)) });
      for (int i = 0; i < marks.size(); i++) {
         int bodyEnd = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
         String body = FRAME.matcher(text.substring(marks.get(i)[1], bodyEnd)).replaceAll(" ");
         List<Double> v = new ArrayList<Double>();
         Matcher nm = NUM.matcher(body);
         while (nm.find()) v.add(Double.parseDouble(nm.group()));
         int kind = marks.get(i)[2];
         if (kind == 0 && v.size() >= 6) {
            int n = v.size() / 2;
            double[] ra = new double[n], dec = new double[n];
            for (int k = 0; k < n; k++) { ra[k] = v.get(2 * k); dec[k] = v.get(2 * k + 1); }
            out.add(new double[][] { ra, dec });
         } else if (kind == 1 && v.size() >= 3) out.add(circle(v.get(0), v.get(1), v.get(2)));
         else if (kind == 2 && v.size() >= 4) out.add(box(v.get(0), v.get(1), v.get(2), v.get(3)));
      }
      return out;
   }

   private static int kind(String s) { s = s.toLowerCase(); return s.equals("polygon") ? 0 : s.equals("circle") ? 1 : 2; }

   private static double[][] circle(double ra, double dec, double r) {
      int n = 32;
      double cd = Math.max(0.05, Math.cos(Math.toRadians(dec)));
      double[] x = new double[n], y = new double[n];
      for (int i = 0; i < n; i++) { x[i] = ra + r * Math.cos(2 * Math.PI * i / n) / cd; y[i] = dec + r * Math.sin(2 * Math.PI * i / n); }
      return new double[][] { x, y };
   }

   private static double[][] box(double ra, double dec, double w, double h) {
      double cd = Math.max(0.05, Math.cos(Math.toRadians(dec)));
      double dx = w / 2 / cd, dy = h / 2;
      return new double[][] { { ra - dx, ra + dx, ra + dx, ra - dx }, { dec - dy, dec - dy, dec + dy, dec + dy } };
   }
}
