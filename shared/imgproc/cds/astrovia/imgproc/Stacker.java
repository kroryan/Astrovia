// Astrovia image processing engine: calibration, registration and stacking of many frames. GNU GPL v3.
package cds.astrovia.imgproc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.*;

/**
 * Stacking of astronomical frames, one band of rows at a time so that even large pictures fit in the memory of a phone:
 * every frame is calibrated (bias, dark, flat), optionally debayered and registered by translation, written to a temporary
 * file, and the frames are then combined row band by row band (average, median or sigma clipping).
 */
public final class Stacker {
   private Stacker() { }

   public static final int AVERAGE = 0, MEDIAN = 1, SIGMA_CLIP = 2;

   public interface Progress {
      void step(String message, double fraction);
      boolean cancelled();
   }

   public static final class Params {
      public int mode = SIGMA_CLIP;
      public double kLow = 3, kHigh = 3;
      public boolean register = true;
      public boolean normalize = true;
      public boolean debayer = false;
      public String bayerPattern = null;      // overrides the header
      public File dark, flat, bias;           // master frames (build them with {@link #master})
      public File tempDir = new File(System.getProperty("java.io.tmpdir"));
   }

   public static final class Result {
      public Img image;
      public int used, rejected;
      public List<String> notes = new ArrayList<String>();
   }

   /** Master calibration frame: the median of the frames (or their mean for 2 frames). */
   public static Img master(List<File> files, Params p, Progress prog) throws IOException {
      if (files.isEmpty()) throw new IOException("No frames");
      Params q = new Params(); q.mode = files.size() > 2 ? MEDIAN : AVERAGE; q.register = false; q.normalize = false; q.tempDir = p.tempDir;
      return stack(files, q, prog).image;
   }

   private static Img maybeDebayer(Img im, Params p) {
      if (!p.debayer || im.c != 1) return im;
      String pat = p.bayerPattern != null ? p.bayerPattern : im.bayerPattern();
      return pat == null ? im : Ops.debayer(im, pat);
   }

   /** Calibrates one light frame in place: (light - dark) / flat, with the flat normalised to its median. */
   static Img calibrate(Img light, Img dark, Img bias, Img flatNorm) {
      for (int k = 0; k < light.c; k++) {
         float[] a = light.p[k];
         float[] d = dark != null && dark.w == light.w && dark.h == light.h ? dark.p[Math.min(k, dark.c - 1)] : null;
         float[] b = d == null && bias != null && bias.w == light.w && bias.h == light.h ? bias.p[Math.min(k, bias.c - 1)] : null;
         float[] f = flatNorm != null && flatNorm.w == light.w && flatNorm.h == light.h ? flatNorm.p[Math.min(k, flatNorm.c - 1)] : null;
         for (int i = 0; i < a.length; i++) {
            float v = a[i];
            if (d != null) v -= d[i]; else if (b != null) v -= b[i];
            if (f != null && f[i] > 1e-6f) v /= f[i];
            a[i] = v;
         }
      }
      return light;
   }

   private static Img normalisedFlat(Img flat, Img bias) {
      Img f = flat.copy();
      for (int k = 0; k < f.c; k++) {
         if (bias != null && bias.w == f.w && bias.h == f.h) for (int i = 0; i < f.p[k].length; i++) f.p[k][i] -= bias.p[Math.min(k, bias.c - 1)][i];
         double med = Img.stats(f.p[k], f.w, f.h).median;
         if (Math.abs(med) < 1e-9) med = 1;
         for (int i = 0; i < f.p[k].length; i++) f.p[k][i] /= med;
      }
      return f;
   }

   public static Result stack(List<File> frames, Params p, Progress prog) throws IOException {
      Result res = new Result();
      if (frames.size() < 2) throw new IOException("Stacking needs at least 2 frames");
      Img dark = p.dark != null ? FitsIO.read(p.dark) : null, bias = p.bias != null ? FitsIO.read(p.bias) : null;
      Img flat = p.flat != null ? normalisedFlat(FitsIO.read(p.flat), bias) : null;
      List<File> temps = new ArrayList<File>();
      List<double[]> norm = new ArrayList<double[]>();     // per frame: median, mad of plane 0
      Img refHeader = null; List<Stars.Star> refStars = null; int w = 0, h = 0, c = 0;
      try {
         for (int i = 0; i < frames.size(); i++) {
            if (prog != null && prog.cancelled()) throw new IOException("Cancelled");
            if (prog != null) prog.step("Preparing frame " + (i + 1) + " of " + frames.size(), 0.5 * i / frames.size());
            Img im;
            try { im = FitsIO.read(frames.get(i)); } catch (IOException e) { res.notes.add(frames.get(i).getName() + ": " + e.getMessage()); res.rejected++; continue; }
            im = calibrate(im, dark, bias, flat);
            im = maybeDebayer(im, p);
            if (refHeader == null) { refHeader = im; w = im.w; h = im.h; c = im.c; }
            else if (im.w != w || im.h != h || im.c != c) { res.notes.add(frames.get(i).getName() + ": different size, skipped"); res.rejected++; continue; }
            double dx = 0, dy = 0;
            if (p.register) {
               Stars.Report rep = Stars.detect(im.luma(), w, h, 6, 80);
               if (refStars == null) refStars = rep.stars;
               else {
                  double[] t = Stars.translation(refStars, rep.stars, Math.max(20, Math.min(w, h) / 4));
                  if (t == null) { res.notes.add(frames.get(i).getName() + ": stars did not match, skipped"); res.rejected++; continue; }
                  dx = t[0]; dy = t[1];
               }
            }
            if (dx != 0 || dy != 0) for (int k = 0; k < c; k++) im.p[k] = Stars.shift(im.p[k], w, h, dx, dy);
            Img.Stats st = Img.stats(im.p[0], w, h);
            norm.add(new double[] { st.median, Math.max(st.mad, 1e-12) });
            File t = File.createTempFile("astrovia_stack_", ".f32", p.tempDir);
            temps.add(t);
            writeRaw(im, t);
         }
         int n = temps.size();
         if (n < 2) throw new IOException("Fewer than 2 usable frames");
         res.used = n;
         Img out = new Img(w, h, c);
         out.header.putAll(refHeader.header);
         int bandRows = Math.max(1, (int) Math.min(h, (64L * 1024 * 1024) / ((long) n * w * 4)));
         RandomAccessFile[] files = new RandomAccessFile[n];
         FileChannel[] ch = new FileChannel[n];
         for (int i = 0; i < n; i++) { files[i] = new RandomAccessFile(temps.get(i), "r"); ch[i] = files[i].getChannel(); }
         try {
            float[][] band = new float[n][];
            for (int k = 0; k < c; k++) {
               for (int y0 = 0; y0 < h; y0 += bandRows) {
                  if (prog != null && prog.cancelled()) throw new IOException("Cancelled");
                  int rows = Math.min(bandRows, h - y0);
                  if (prog != null) prog.step("Combining", 0.5 + 0.5 * ((k * (double) h + y0) / (c * (double) h)));
                  for (int i = 0; i < n; i++) {
                     ByteBuffer bb = ByteBuffer.allocate(rows * w * 4).order(ByteOrder.BIG_ENDIAN);
                     ch[i].read(bb, ((long) k * h + y0) * w * 4L);
                     bb.flip();
                     float[] r = new float[rows * w];
                     bb.asFloatBuffer().get(r);
                     if (p.normalize && i > 0) { double s = norm.get(0)[1] / norm.get(i)[1], m0 = norm.get(0)[0], mi = norm.get(i)[0]; for (int q = 0; q < r.length; q++) r[q] = (float) ((r[q] - mi) * s + m0); }
                     band[i] = r;
                  }
                  float[] vals = new float[n];
                  for (int q = 0; q < rows * w; q++) {
                     int m = 0;
                     for (int i = 0; i < n; i++) { float v = band[i][q]; if (!Float.isNaN(v)) vals[m++] = v; }
                     out.p[k][y0 * w + q] = m == 0 ? Float.NaN : combine(vals, m, p);
                  }
               }
            }
         } finally { for (RandomAccessFile f : files) if (f != null) f.close(); }
         for (int k = 0; k < c; k++) fillHoles(out.p[k]);
         out.header.put("NCOMBINE", String.valueOf(n));
         res.image = out;
         return res;
      } finally { for (File t : temps) t.delete(); }
   }

   private static void fillHoles(float[] a) {
      float med = Float.NaN;
      for (int i = 0; i < a.length; i++) if (Float.isNaN(a[i])) { if (Float.isNaN(med)) med = Img.median(Img.sampleOf(a, 100000)); a[i] = med; }
   }

   private static float combine(float[] v, int n, Params p) {
      if (p.mode == AVERAGE) { double s = 0; for (int i = 0; i < n; i++) s += v[i]; return (float) (s / n); }
      if (p.mode == MEDIAN || n < 4) return Img.median(Arrays.copyOf(v, n));
      float[] t = Arrays.copyOf(v, n);
      int m = n;
      for (int it = 0; it < 5 && m >= 3; it++) {
         float med = Img.median(Arrays.copyOf(t, m));
         double s2 = 0; for (int i = 0; i < m; i++) s2 += (t[i] - med) * (double) (t[i] - med);
         double sd = Math.sqrt(s2 / m);
         if (sd < 1e-12) break;
         int k = 0;
         for (int i = 0; i < m; i++) if (t[i] >= med - p.kLow * sd && t[i] <= med + p.kHigh * sd) t[k++] = t[i];
         if (k == m) break;
         m = k;
      }
      double s = 0; for (int i = 0; i < m; i++) s += t[i];
      return (float) (s / m);
   }

   private static void writeRaw(Img im, File f) throws IOException {
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16));
      try {
         ByteBuffer bb = ByteBuffer.allocate(im.w * 4).order(ByteOrder.BIG_ENDIAN);
         for (int k = 0; k < im.c; k++) for (int y = 0; y < im.h; y++) { bb.clear(); for (int x = 0; x < im.w; x++) bb.putFloat(im.p[k][y * im.w + x]); out.write(bb.array(), 0, im.w * 4); }
      } finally { out.close(); }
   }
}
