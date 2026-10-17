// Astrovia image processing engine: point operations and filters. GNU GPL v3.
package cds.astrovia.imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Low-to-medium level processing of astronomical pictures: stretch, background gradient removal, noise reduction,
 * sharpening / deconvolution, cosmetic correction, colour tools, debayering and geometry. Every operation returns a new
 * image and leaves its input untouched, so the caller can keep an undo history.
 */
public final class Ops {
   private Ops() { }

   private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

   // ---------------------------------------------------------------------------------------------- stretch

   /** Midtones transfer function: 0 -> 0, 1 -> 1, m -> 0.5. */
   public static double mtf(double m, double x) {
      if (x <= 0) return 0;
      if (x >= 1) return 1;
      if (x == m) return 0.5;
      return (m - 1) * x / ((2 * m - 1) * x - m);
   }

   /** Auto-stretch of a linear image to a displayable [0,1] picture (shadow clipping + midtone balance, PixInsight "STF" style). */
   public static Img autoStretch(Img in, double targetMedian, double shadowSigmas, boolean linked) {
      Img out = in.like(in.c);
      double[] c0 = new double[in.c], mid = new double[in.c], mn = new double[in.c], rg = new double[in.c];
      for (int k = 0; k < in.c; k++) {
         Img.Stats s = Img.stats(in.p[k], in.w, in.h);
         mn[k] = s.min; rg[k] = Math.max(1e-30, s.max - s.min);
         double med = (s.median - s.min) / rg[k], mad = s.mad / rg[k];
         c0[k] = Math.max(0, med + shadowSigmas * 1.4826 * mad);
         double mp = (med - c0[k]) / (1 - c0[k]);
         mid[k] = mtf(targetMedian, Math.max(1e-9, mp));
      }
      if (linked && in.c > 1) { double a = 0, b = 0; for (int k = 0; k < in.c; k++) { a += c0[k]; b += mid[k]; } for (int k = 0; k < in.c; k++) { c0[k] = a / in.c; mid[k] = b / in.c; } }
      for (int k = 0; k < in.c; k++) {
         float[] src = in.p[k], dst = out.p[k];
         double den = 1 - c0[k];
         for (int i = 0; i < src.length; i++) {
            double x = (src[i] - mn[k]) / rg[k];
            dst[i] = (float) mtf(mid[k], clamp01((x - c0[k]) / den));
         }
      }
      return out;
   }

   /** Hyperbolic arcsine stretch: [0,1] normalised data, [strength] controls how much the faint signal is lifted. */
   public static Img asinh(Img in, double strength, double blackPoint) {
      Img out = in.like(in.c);
      double norm = 1.0 / asinh(strength);
      for (int k = 0; k < in.c; k++) {
         Img.Stats s = Img.stats(in.p[k], in.w, in.h);
         double lo = s.median + blackPoint * 1.4826 * s.mad, rg = Math.max(1e-30, s.max - lo);
         for (int i = 0; i < in.p[k].length; i++) {
            double x = clamp01((in.p[k][i] - lo) / rg);
            out.p[k][i] = (float) (asinh(strength * x) * norm);
         }
      }
      return out;
   }

   private static double asinh(double x) { return Math.log(x + Math.sqrt(x * x + 1)); }

   // ---------------------------------------------------------------------------------------------- background

   /**
    * Removes a smooth gradient (light pollution, vignetting): the sky background is measured on a grid of small boxes
    * avoiding stars, a polynomial surface of the given order (1..4) is fitted and subtracted.
    */
   public static Img removeBackground(Img in, int grid, int order) {
      Img out = in.like(in.c);
      for (int k = 0; k < in.c; k++) out.p[k] = removeBackgroundPlane(in.p[k], in.w, in.h, Math.max(4, Math.min(24, grid)), Math.max(1, Math.min(4, order)));
      return out;
   }

   static float[] removeBackgroundPlane(float[] a, int w, int h, int grid, int order) {
      int bw = Math.max(8, w / grid), bh = Math.max(8, h / grid);
      List<double[]> pts = new ArrayList<double[]>();   // x, y (normalised), value
      float[] buf = new float[bw * bh];
      for (int by = 0; by + bh <= h; by += bh) for (int bx = 0; bx + bw <= w; bx += bw) {
         int n = 0;
         for (int y = by; y < by + bh; y++) for (int x = bx; x < bx + bw; x++) buf[n++] = a[y * w + x];
         float[] t = java.util.Arrays.copyOf(buf, n);
         float med = Img.median(t.clone());
         float[] dev = new float[n];
         for (int i = 0; i < n; i++) dev[i] = Math.abs(t[i] - med);
         float mad = Img.median(dev);
         double lim = med + 2.0 * 1.4826 * mad;
         float[] kept = new float[n]; int kn = 0;
         for (int i = 0; i < n; i++) if (t[i] <= lim) kept[kn++] = t[i];
         if (kn < n / 3) continue;                         // a box full of nebula or stars
         pts.add(new double[] { 2.0 * (bx + bw / 2.0) / w - 1, 2.0 * (by + bh / 2.0) / h - 1, Img.median(java.util.Arrays.copyOf(kept, kn)) });
      }
      int terms = (order + 1) * (order + 2) / 2;
      if (pts.size() < terms + 2) return a.clone();
      double[] coef = null;
      List<double[]> use = new ArrayList<double[]>(pts);
      for (int it = 0; it < 3; it++) {
         coef = fitPoly(use, order);
         if (coef == null) return a.clone();
         double ss = 0;
         for (double[] q : use) { double r = q[2] - evalPoly(coef, order, q[0], q[1]); ss += r * r; }
         double sd = Math.sqrt(ss / use.size());
         List<double[]> nu = new ArrayList<double[]>();
         for (double[] q : pts) if (Math.abs(q[2] - evalPoly(coef, order, q[0], q[1])) <= 2.0 * sd + 1e-12) nu.add(q);
         if (nu.size() < terms + 2) break;
         use = nu;
      }
      float[] out = new float[a.length];
      double offset = 0; int cnt = 0;
      for (double[] q : use) { offset += evalPoly(coef, order, q[0], q[1]); cnt++; }
      offset /= Math.max(1, cnt);
      for (int y = 0; y < h; y++) {
         double ny = 2.0 * (y + 0.5) / h - 1;
         for (int x = 0; x < w; x++) out[y * w + x] = (float) (a[y * w + x] - (evalPoly(coef, order, 2.0 * (x + 0.5) / w - 1, ny) - offset));
      }
      return out;
   }

   private static double evalPoly(double[] c, int order, double x, double y) {
      double s = 0; int t = 0;
      for (int i = 0; i <= order; i++) for (int j = 0; j <= order - i; j++) s += c[t++] * Math.pow(x, i) * Math.pow(y, j);
      return s;
   }

   private static double[] fitPoly(List<double[]> pts, int order) {
      int n = (order + 1) * (order + 2) / 2;
      double[][] m = new double[n][n + 1];
      for (double[] q : pts) {
         double[] b = new double[n]; int t = 0;
         for (int i = 0; i <= order; i++) for (int j = 0; j <= order - i; j++) b[t++] = Math.pow(q[0], i) * Math.pow(q[1], j);
         for (int r = 0; r < n; r++) { for (int c = 0; c < n; c++) m[r][c] += b[r] * b[c]; m[r][n] += b[r] * q[2]; }
      }
      for (int col = 0; col < n; col++) {
         int piv = col;
         for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
         if (Math.abs(m[piv][col]) < 1e-12) return null;
         double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
         for (int r = 0; r < n; r++) if (r != col) { double f = m[r][col] / m[col][col]; for (int c = col; c <= n; c++) m[r][c] -= f * m[col][c]; }
      }
      double[] x = new double[n];
      for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
      return x;
   }

   // ---------------------------------------------------------------------------------------------- filters

   static float[] gauss(float[] a, int w, int h, double sigma) {
      if (sigma < 0.3) return a.clone();
      int r = (int) Math.ceil(3 * sigma);
      float[] k = new float[2 * r + 1];
      double sum = 0;
      for (int i = -r; i <= r; i++) { k[i + r] = (float) Math.exp(-(i * i) / (2 * sigma * sigma)); sum += k[i + r]; }
      for (int i = 0; i < k.length; i++) k[i] /= sum;
      float[] t = new float[a.length], o = new float[a.length];
      for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
         float s = 0;
         for (int i = -r; i <= r; i++) { int xx = x + i; xx = xx < 0 ? 0 : xx >= w ? w - 1 : xx; s += a[y * w + xx] * k[i + r]; }
         t[y * w + x] = s;
      }
      for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
         float s = 0;
         for (int i = -r; i <= r; i++) { int yy = y + i; yy = yy < 0 ? 0 : yy >= h ? h - 1 : yy; s += t[yy * w + x] * k[i + r]; }
         o[y * w + x] = s;
      }
      return o;
   }

   public static Img gaussianBlur(Img in, double sigma) {
      Img o = in.like(in.c);
      for (int k = 0; k < in.c; k++) o.p[k] = gauss(in.p[k], in.w, in.h, sigma);
      return o;
   }

   /** Median filter of radius 1 (3x3) or 2 (5x5): removes salt-and-pepper noise and small defects. */
   public static Img median(Img in, int radius) {
      int r = Math.max(1, Math.min(2, radius));
      Img o = in.like(in.c);
      int n = (2 * r + 1) * (2 * r + 1);
      float[] win = new float[n];
      for (int k = 0; k < in.c; k++) {
         float[] a = in.p[k], d = o.p[k]; int w = in.w, h = in.h;
         for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int m = 0;
            for (int j = -r; j <= r; j++) { int yy = Math.max(0, Math.min(h - 1, y + j)); for (int i = -r; i <= r; i++) win[m++] = a[yy * w + Math.max(0, Math.min(w - 1, x + i))]; }
            d[y * w + x] = Img.select(win, 0, n - 1, n / 2);
         }
      }
      return o;
   }

   /** Unsharp mask: adds [amount] times the detail lost by a Gaussian blur of [sigma] pixels. */
   public static Img unsharp(Img in, double sigma, double amount) {
      Img o = in.like(in.c);
      for (int k = 0; k < in.c; k++) {
         float[] b = gauss(in.p[k], in.w, in.h, sigma);
         for (int i = 0; i < b.length; i++) o.p[k][i] = (float) (in.p[k][i] + amount * (in.p[k][i] - b[i]));
      }
      return o;
   }

   /** Richardson-Lucy deconvolution with a Gaussian point-spread function (sigma in pixels, 10 to 40 iterations). */
   public static Img deconvolve(Img in, double sigma, int iterations) {
      Img o = in.like(in.c);
      for (int k = 0; k < in.c; k++) {
         int n = in.p[k].length; float[] obs = new float[n];
         float mn = Float.MAX_VALUE;
         for (float v : in.p[k]) if (v < mn) mn = v;
         for (int i = 0; i < n; i++) obs[i] = in.p[k][i] - mn + 1e-6f;
         float[] est = obs.clone(), ratio = new float[n];
         for (int it = 0; it < iterations; it++) {
            float[] conv = gauss(est, in.w, in.h, sigma);
            for (int i = 0; i < n; i++) ratio[i] = obs[i] / Math.max(conv[i], 1e-9f);
            float[] corr = gauss(ratio, in.w, in.h, sigma);
            for (int i = 0; i < n; i++) est[i] *= corr[i];
         }
         for (int i = 0; i < n; i++) o.p[k][i] = est[i] + mn - 1e-6f;
      }
      return o;
   }

   /** Replaces isolated hot (and cold) pixels by the median of their neighbours; returns the number of pixels fixed in [fixed]. */
   public static Img cosmetic(Img in, double hotSigma, double coldSigma, int[] fixed) {
      Img o = in.copy();
      int count = 0;
      float[] nb = new float[8];
      for (int k = 0; k < in.c; k++) {
         Img.Stats s = Img.stats(in.p[k], in.w, in.h);
         double noise = Math.max(s.noise, 1e-12);
         float[] a = in.p[k], d = o.p[k]; int w = in.w, h = in.h;
         for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int m = 0;
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) if (i != 0 || j != 0) nb[m++] = a[(y + j) * w + x + i];
            float[] t = nb.clone();
            float med = Img.median(t);
            double dv = a[y * w + x] - med;
            if ((hotSigma > 0 && dv > hotSigma * noise) || (coldSigma > 0 && -dv > coldSigma * noise)) { d[y * w + x] = med; count++; }
         }
      }
      if (fixed != null && fixed.length > 0) fixed[0] = count;
      return o;
   }

   // ---------------------------------------------------------------------------------------------- colour

   /** Removes the green cast of colour pictures (average neutral protection): green is limited to the mean of red and blue. */
   public static Img scnr(Img in, double amount) {
      if (in.c != 3) return in.copy();
      Img o = in.copy();
      for (int i = 0; i < in.w * in.h; i++) {
         float lim = (in.p[0][i] + in.p[2][i]) / 2f;
         if (in.p[1][i] > lim) o.p[1][i] = (float) (in.p[1][i] * (1 - amount) + lim * amount);
      }
      return o;
   }

   /** Colour saturation: -1 = grey, 0 = unchanged, +1 = twice as colourful. */
   public static Img saturation(Img in, double amount) {
      if (in.c != 3) return in.copy();
      Img o = in.like(3);
      for (int i = 0; i < in.w * in.h; i++) {
         float l = (in.p[0][i] + in.p[1][i] + in.p[2][i]) / 3f;
         for (int k = 0; k < 3; k++) o.p[k][i] = (float) Math.max(0, l + (in.p[k][i] - l) * (1 + amount));
      }
      return o;
   }

   /** White balance by sky background: red and blue are scaled so that their background median equals the green one. */
   public static Img whiteBalance(Img in) {
      if (in.c != 3) return in.copy();
      Img o = in.copy();
      double g = Img.stats(in.p[1], in.w, in.h).median;
      for (int k = 0; k < 3; k += 2) {
         double m = Img.stats(in.p[k], in.w, in.h).median;
         double f = m > 1e-12 ? g / m : 1;
         for (int i = 0; i < o.p[k].length; i++) o.p[k][i] = (float) (in.p[k][i] * f);
      }
      return o;
   }

   /** Bilinear debayering of a raw colour-camera picture; the pattern ("RGGB", "BGGR", "GRBG", "GBRG") is the one seen from the top row. */
   public static Img debayer(Img raw, String pattern) {
      if (raw.c != 1) throw new IllegalArgumentException("Debayering needs a raw single-plane picture");
      String pat = pattern == null ? "RGGB" : pattern.toUpperCase();
      if (pat.length() != 4) throw new IllegalArgumentException("Unknown Bayer pattern " + pattern);
      int w = raw.w, h = raw.h;
      Img o = raw.like(3);
      o.header.remove("BAYERPAT");
      byte[] color = new byte[4];
      for (int i = 0; i < 4; i++) color[i] = (byte) (pat.charAt(i) == 'R' ? 0 : pat.charAt(i) == 'G' ? 1 : 2);
      for (int y = 0; y < h; y++) {
         int top = h - 1 - y;      // FITS row 0 is the bottom of the picture
         for (int x = 0; x < w; x++) {
            float[] sum = new float[3]; int[] cnt = new int[3];
            for (int j = -1; j <= 1; j++) {
               int yy = y + j; if (yy < 0 || yy >= h) continue;
               int ty = h - 1 - yy;
               for (int i = -1; i <= 1; i++) {
                  int xx = x + i; if (xx < 0 || xx >= w) continue;
                  int c = color[(ty & 1) * 2 + (xx & 1)];
                  sum[c] += raw.p[0][yy * w + xx]; cnt[c]++;
               }
            }
            for (int c = 0; c < 3; c++) o.p[c][y * w + x] = cnt[c] > 0 ? sum[c] / cnt[c] : raw.p[0][y * w + x];
         }
      }
      return o;
   }

   // ---------------------------------------------------------------------------------------------- geometry

   private static void dropWcs(Img o) { for (String k : new String[] { "CTYPE1", "CTYPE2", "CRPIX1", "CRPIX2", "CRVAL1", "CRVAL2", "CDELT1", "CDELT2", "CD1_1", "CD1_2", "CD2_1", "CD2_2", "PC1_1", "PC1_2", "PC2_1", "PC2_2", "CROTA2", "LONPOLE", "CUNIT1", "CUNIT2" }) o.header.remove(k); }

   /** Averages n x n pixels into one (n = 2, 3, 4): less noise, smaller picture. */
   public static Img bin(Img in, int n) {
      int nw = in.w / n, nh = in.h / n;
      Img o = new Img(nw, nh, in.c);
      o.header.putAll(in.header);
      for (int k = 0; k < in.c; k++) for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) {
         float s = 0;
         for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) s += in.p[k][(y * n + j) * in.w + x * n + i];
         o.p[k][y * nw + x] = s / (n * n);
      }
      scaleWcs(o, n, 0, 0);
      return o;
   }

   /** Rectangle (x, y from the lower left like FITS, in pixels). */
   public static Img crop(Img in, int x0, int y0, int cw, int ch) {
      x0 = Math.max(0, Math.min(in.w - 1, x0)); y0 = Math.max(0, Math.min(in.h - 1, y0));
      cw = Math.max(1, Math.min(in.w - x0, cw)); ch = Math.max(1, Math.min(in.h - y0, ch));
      Img o = new Img(cw, ch, in.c);
      o.header.putAll(in.header);
      for (int k = 0; k < in.c; k++) for (int y = 0; y < ch; y++) System.arraycopy(in.p[k], (y0 + y) * in.w + x0, o.p[k], y * cw, cw);
      scaleWcs(o, 1, x0, y0);
      return o;
   }

   /** Keeps the sky coordinates right after a bin or a crop (reference pixel and pixel scale). */
   private static void scaleWcs(Img o, int bin, int cropX, int cropY) {
      try {
         String a = o.header.get("CRPIX1"), b = o.header.get("CRPIX2");
         if (a == null || b == null) return;
         o.header.put("CRPIX1", String.valueOf((Double.parseDouble(a) - cropX - 0.5) / bin + 0.5));
         o.header.put("CRPIX2", String.valueOf((Double.parseDouble(b) - cropY - 0.5) / bin + 0.5));
         if (bin != 1) for (String k : new String[] { "CDELT1", "CDELT2", "CD1_1", "CD1_2", "CD2_1", "CD2_2" }) { String v = o.header.get(k); if (v != null) o.header.put(k, String.valueOf(Double.parseDouble(v) * bin)); }
      } catch (NumberFormatException e) { dropWcs(o); }
   }

   public static Img rotate90(Img in, boolean clockwise) {
      Img o = new Img(in.h, in.w, in.c);
      o.header.putAll(in.header); dropWcs(o);
      for (int k = 0; k < in.c; k++) for (int y = 0; y < in.h; y++) for (int x = 0; x < in.w; x++) {
         int nx, ny;
         // FITS row 0 is the bottom: a clockwise turn of the picture sends (x, y) to (y, w-1-x) in FITS coordinates
         if (clockwise) { nx = y; ny = in.w - 1 - x; } else { nx = in.h - 1 - y; ny = x; }
         o.p[k][ny * o.w + nx] = in.p[k][y * in.w + x];
      }
      return o;
   }

   public static Img flip(Img in, boolean horizontal) {
      Img o = in.like(in.c); dropWcs(o);
      for (int k = 0; k < in.c; k++) for (int y = 0; y < in.h; y++) for (int x = 0; x < in.w; x++)
         o.p[k][y * in.w + x] = horizontal ? in.p[k][y * in.w + (in.w - 1 - x)] : in.p[k][(in.h - 1 - y) * in.w + x];
      return o;
   }

   public static Img invert(Img in) {
      Img o = in.like(in.c);
      for (int k = 0; k < in.c; k++) { Img.Stats s = Img.stats(in.p[k], in.w, in.h); for (int i = 0; i < in.p[k].length; i++) o.p[k][i] = (float) (s.max + s.min - in.p[k][i]); }
      return o;
   }

   /** Linear black/white points (data values) mapped to [0,1]. */
   public static Img levels(Img in, double black, double white) {
      Img o = in.like(in.c);
      double rg = Math.max(1e-30, white - black);
      for (int k = 0; k < in.c; k++) for (int i = 0; i < in.p[k].length; i++) o.p[k][i] = (float) clamp01((in.p[k][i] - black) / rg);
      return o;
   }
}
