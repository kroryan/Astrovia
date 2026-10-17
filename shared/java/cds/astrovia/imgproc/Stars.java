// Astrovia image processing engine: star detection, quality measures and translation registration. GNU GPL v3.
package cds.astrovia.imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Stars {
   private Stars() { }

   public static final class Star {
      public double x, y, flux, peak, fwhm, roundness;
   }

   /** Summary of a star field: useful to judge focus, tracking and seeing. */
   public static final class Report {
      public int count;
      public double medianFwhm, medianRoundness, background, noise;
      public List<Star> stars = new ArrayList<Star>();
      public String toString() { return String.format("%d stars  FWHM %.2f px  roundness %.2f  background %.4g  noise %.4g", count, medianFwhm, medianRoundness, background, noise); }
   }

   /**
    * Finds stars on a plane: local maxima {@code nSigma} above the sky, centroided on a 7x7 box; FWHM from the second moments.
    * At most {@code maxStars} brightest are kept.
    */
   public static Report detect(float[] a, int w, int h, double nSigma, int maxStars) {
      Report r = new Report();
      Img.Stats s = Img.stats(a, w, h);
      // local background: stars sit on nebulae and gradients, so they are measured on a high-pass picture
      float[] bg = Ops.gauss(a, w, h, 10);
      float[] hp = new float[a.length];
      for (int i = 0; i < hp.length; i++) hp[i] = a[i] - bg[i];
      float[] sm = new float[a.length];               // 3x3 mean to reduce noise peaks
      for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
         float t = 0;
         for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) t += hp[(y + j) * w + x + i];
         sm[y * w + x] = t / 9f;
      }
      float[] sample = Img.sampleOf(sm, 200000);
      float med = Img.median(sample.clone());
      for (int i = 0; i < sample.length; i++) sample[i] = Math.abs(sample[i] - med);
      double noiseSm = Math.max(1.4826 * Img.median(sample), 1e-12);
      r.background = s.median; r.noise = Math.max(s.noise, 1e-12);
      double thr = nSigma * noiseSm;
      List<Star> found = new ArrayList<Star>();
      for (int y = 4; y < h - 4; y++) for (int x = 4; x < w - 4; x++) {
         float v = sm[y * w + x];
         if (v < thr) continue;
         boolean max = true;
         for (int j = -2; j <= 2 && max; j++) for (int i = -2; i <= 2; i++) { if ((i != 0 || j != 0) && sm[(y + j) * w + x + i] > v) { max = false; break; } }
         if (!max) continue;
         Star st = measure(a, w, h, x, y, bg[y * w + x]);
         if (st != null && st.fwhm >= 1.2 && st.fwhm <= 14 && st.roundness >= 0.55) found.add(st);
      }
      Collections.sort(found, new Comparator<Star>() { public int compare(Star p, Star q) { return Double.compare(q.flux, p.flux); } });
      if (found.size() > maxStars) found = new ArrayList<Star>(found.subList(0, maxStars));
      r.stars = found; r.count = found.size();
      if (!found.isEmpty()) {
         float[] f = new float[found.size()], q = new float[found.size()];
         for (int i = 0; i < f.length; i++) { f[i] = (float) found.get(i).fwhm; q[i] = (float) found.get(i).roundness; }
         r.medianFwhm = Img.median(f); r.medianRoundness = Img.median(q);
      }
      return r;
   }

   private static Star measure(float[] a, int w, int h, int cx, int cy, double bg) {
      double sw = 0, sx = 0, sy = 0;
      double peak = 0;
      for (int j = -3; j <= 3; j++) for (int i = -3; i <= 3; i++) {
         double v = a[(cy + j) * w + cx + i] - bg;
         if (v > peak) peak = v;
         if (v > 0) { sw += v; sx += v * (cx + i); sy += v * (cy + j); }
      }
      if (sw <= 0 || peak <= 0) return null;
      double mx = sx / sw, my = sy / sw;
      double vxx = 0, vyy = 0; double half = peak / 2;
      for (int j = -3; j <= 3; j++) for (int i = -3; i <= 3; i++) {
         double v = a[(cy + j) * w + cx + i] - bg;
         if (v <= 0) continue;
         double dx = cx + i - mx, dy = cy + j - my;
         vxx += v * dx * dx; vyy += v * dy * dy;
      }
      vxx /= sw; vyy /= sw;
      if (vxx <= 0 || vyy <= 0) return null;
      Star st = new Star();
      st.x = mx; st.y = my; st.flux = sw; st.peak = peak;
      // second moments of a clipped 7x7 box under-estimate wide stars: scale to a Gaussian sigma
      double sigma = Math.sqrt((vxx + vyy) / 2) * 1.25;
      st.fwhm = 2.3548 * sigma;
      st.roundness = Math.min(vxx, vyy) / Math.max(vxx, vyy);
      return st;
   }

   /**
    * Translation between two star lists (reference minus current), by voting on pairs of stars among the brightest ones and
    * refining with the matched pairs. Returns {dx, dy, matches} or null when the fields do not match.
    */
   public static double[] translation(List<Star> ref, List<Star> cur, int maxShift) {
      int n = Math.min(60, ref.size()), m = Math.min(60, cur.size());
      if (n < 3 || m < 3) return null;
      int size = 2 * maxShift + 1;
      int[][] votes = new int[size][size];
      for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
         int dx = (int) Math.round(ref.get(i).x - cur.get(j).x), dy = (int) Math.round(ref.get(i).y - cur.get(j).y);
         if (Math.abs(dx) > maxShift || Math.abs(dy) > maxShift) continue;
         votes[dy + maxShift][dx + maxShift]++;
      }
      int best = 0, bx = 0, by = 0;
      for (int y = 1; y < size - 1; y++) for (int x = 1; x < size - 1; x++) {
         int v = 0;
         for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) v += votes[y + j][x + i];
         if (v > best) { best = v; bx = x - maxShift; by = y - maxShift; }
      }
      if (best < 3) return null;
      double sx = 0, sy = 0; int cnt = 0;
      for (int i = 0; i < n; i++) {
         for (int j = 0; j < m; j++) {
            double dx = ref.get(i).x - cur.get(j).x, dy = ref.get(i).y - cur.get(j).y;
            if (Math.abs(dx - bx) <= 2 && Math.abs(dy - by) <= 2) { sx += dx; sy += dy; cnt++; break; }
         }
      }
      if (cnt < 3) return null;
      return new double[] { sx / cnt, sy / cnt, cnt };
   }

   /** Bilinear shift by (dx, dy) pixels; pixels that come from outside the picture become NaN. */
   public static float[] shift(float[] a, int w, int h, double dx, double dy) {
      float[] o = new float[a.length];
      for (int y = 0; y < h; y++) {
         double sy = y - dy; int y0 = (int) Math.floor(sy); double fy = sy - y0;
         for (int x = 0; x < w; x++) {
            double sx = x - dx; int x0 = (int) Math.floor(sx); double fx = sx - x0;
            if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= h) { o[y * w + x] = Float.NaN; continue; }
            float v00 = a[y0 * w + x0], v10 = a[y0 * w + x0 + 1], v01 = a[(y0 + 1) * w + x0], v11 = a[(y0 + 1) * w + x0 + 1];
            o[y * w + x] = (float) ((v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy);
         }
      }
      return o;
   }
}
