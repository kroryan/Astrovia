// Astrovia image processing engine (shared by the Android app and the desktop app).
// Independent implementation of well-known algorithms. Part of Astrovia, GNU GPL v3.
package cds.astrovia.imgproc;

import java.util.LinkedHashMap;
import java.util.Map;

/** A float image with 1 (mono) or 3 (RGB) planes. Row 0 is the first FITS row (the bottom of the picture). */
public final class Img {
   public final int w, h, c;
   public final float[][] p;
   /** FITS header cards worth keeping (OBJECT, EXPTIME, BAYERPAT, WCS...), in order. */
   public final Map<String, String> header = new LinkedHashMap<String, String>();

   public Img(int w, int h, int c) {
      this.w = w; this.h = h; this.c = c;
      this.p = new float[c][w * h];
   }

   public Img copy() {
      Img o = new Img(w, h, c);
      for (int i = 0; i < c; i++) System.arraycopy(p[i], 0, o.p[i], 0, w * h);
      o.header.putAll(header);
      return o;
   }

   /** Same size and header, planes not copied (to be filled by an operation). */
   public Img like(int channels) {
      Img o = new Img(w, h, channels);
      o.header.putAll(header);
      return o;
   }

   public boolean isColor() { return c == 3; }

   /** Luminance (mean of the planes for colour images). */
   public float[] luma() {
      if (c == 1) return p[0];
      float[] l = new float[w * h];
      for (int i = 0; i < l.length; i++) l[i] = (p[0][i] + p[1][i] + p[2][i]) / 3f;
      return l;
   }

   public String bayerPattern() {
      String b = header.get("BAYERPAT");
      return b == null ? null : b.trim().toUpperCase();
   }

   // ----------------------------------------------------------------------------------------------- statistics

   /** Basic statistics of one plane. */
   public static final class Stats {
      public double min, max, mean, median, sigma, mad;
      /** Noise estimate (robust sigma of the pixel-to-pixel differences). */
      public double noise;
      public long count;
      public String toString() { return String.format("min %.4g  max %.4g  mean %.4g  median %.4g  sigma %.4g  MAD %.4g  noise %.4g", min, max, mean, median, sigma, mad, noise); }
   }

   public static Stats stats(float[] a, int w, int h) {
      Stats s = new Stats();
      double sum = 0, sum2 = 0; long n = 0; float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
      for (float v : a) { if (Float.isNaN(v) || Float.isInfinite(v)) continue; if (v < mn) mn = v; if (v > mx) mx = v; sum += v; sum2 += (double) v * v; n++; }
      if (n == 0) return s;
      s.count = n; s.min = mn; s.max = mx; s.mean = sum / n; s.sigma = Math.sqrt(Math.max(0, sum2 / n - s.mean * s.mean));
      float[] sample = sampleOf(a, 400000);
      s.median = median(sample);
      float[] dev = new float[sample.length];
      for (int i = 0; i < dev.length; i++) dev[i] = (float) Math.abs(sample[i] - s.median);
      s.mad = median(dev);
      // pixel-to-pixel differences cancel the smooth signal: robust sigma / sqrt(2)
      int step = Math.max(1, (w * (long) h > 800000) ? 3 : 1);
      java.util.ArrayList<Float> d = new java.util.ArrayList<Float>();
      for (int y = 0; y < h; y += step) for (int x = 1; x < w; x += step) d.add(Math.abs(a[y * w + x] - a[y * w + x - 1]));
      float[] da = new float[d.size()];
      for (int i = 0; i < da.length; i++) da[i] = d.get(i);
      s.noise = da.length == 0 ? 0 : median(da) * 1.4826 / Math.sqrt(2);
      return s;
   }

   public static float[] sampleOf(float[] a, int max) {
      int step = Math.max(1, a.length / max);
      int n = 0;
      float[] t = new float[a.length / step + 1];
      for (int i = 0; i < a.length; i += step) { float v = a[i]; if (!Float.isNaN(v) && !Float.isInfinite(v)) t[n++] = v; }
      return java.util.Arrays.copyOf(t, n);
   }

   /** Median of an array (the array is reordered). */
   public static float median(float[] a) {
      int n = a.length;
      if (n == 0) return 0;
      int k = n / 2;
      float v = select(a, 0, n - 1, k);
      if ((n & 1) == 0) { float lo = Float.NEGATIVE_INFINITY; for (int i = 0; i < k; i++) if (a[i] > lo) lo = a[i]; return (lo + v) / 2f; }
      return v;
   }

   /** Quickselect: k-th smallest of a[lo..hi]. */
   public static float select(float[] a, int lo, int hi, int k) {
      while (lo < hi) {
         float pivot = a[(lo + hi) >>> 1];
         int i = lo, j = hi;
         while (i <= j) {
            while (a[i] < pivot) i++;
            while (a[j] > pivot) j--;
            if (i <= j) { float t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
         }
         if (k <= j) hi = j; else if (k >= i) lo = i; else return a[k];
      }
      return a[k];
   }
}
