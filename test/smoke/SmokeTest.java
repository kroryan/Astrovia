// Headless smoke test of the desktop app: run it through tools/smoke-desktop.sh (needs a display, e.g. xvfb-run).
package cds.aladin;

import cds.astrovia.imgproc.*;
import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import javax.swing.SwingUtilities;

public class SmokeTest {
   static int failures = 0;
   static void check(boolean ok, String what) { System.out.println((ok ? "ok    " : "FAIL  ") + what); if (!ok) failures++; }

   public static void main(String[] a) throws Exception {
      // a synthetic star field written as FITS, then read, processed and stacked through the shared engine
      File dir = java.nio.file.Files.createTempDirectory("astrovia_smoke").toFile();
      Img img = new Img(300, 200, 1); Random r = new Random(3);
      for (int i = 0; i < img.p[0].length; i++) img.p[0][i] = 100 + (float) r.nextGaussian() * 3;
      for (int s = 0; s < 12; s++) { int cx = 20 + r.nextInt(260), cy = 20 + r.nextInt(160); for (int y = -6; y <= 6; y++) for (int x = -6; x <= 6; x++) img.p[0][(cy + y) * 300 + cx + x] += 1500 * Math.exp(-(x * x + y * y) / 6.0); }
      File f1 = new File(dir, "a.fits"); FitsIO.write(img, f1);
      File f2 = new File(dir, "b.fits"); FitsIO.write(shifted(img), f2);
      Img back = FitsIO.read(f1);
      check(back.w == 300 && back.h == 200, "FITS write / read");
      check(Stars.detect(back.p[0], 300, 200, 6, 100).count >= 8, "star detection");
      check(Ops.autoStretch(back, 0.25, -2.8, true).p[0].length == 60000, "auto stretch");
      Stacker.Params p = new Stacker.Params(); p.tempDir = dir;
      Stacker.Result res = Stacker.stack(java.util.Arrays.asList(f1, f2), p, null);
      check(res.used == 2, "stack of 2 frames");
      // the windows of the app build and answer
      final ArchivesDialog[] ad = new ArchivesDialog[1]; final ProcessDialog[] pd = new ProcessDialog[1];
      SwingUtilities.invokeAndWait(new Runnable() { public void run() { ad[0] = new ArchivesDialog(null, null); pd[0] = new ProcessDialog(null, null); } });
      Method load = ProcessDialog.class.getDeclaredMethod("load", File.class); load.setAccessible(true);
      SwingUtilities.invokeAndWait(new Runnable() { public void run() { try { load.invoke(pd[0], f1); } catch (Exception e) { throw new RuntimeException(e); } } });
      Thread.sleep(1500);
      check(pd[0].getTitle().contains("300 x 200"), "processing window loads a FITS");
      check(cds.astrovia.sky.Planner.all().size() > 5 && cds.astrovia.sky.Visibility.windows(90, 0, 85, 135, 2461120.1, 365, 0.25).size() > 0, "planner and visibility");
      System.out.println(failures == 0 ? "SMOKE TEST PASSED" : "SMOKE TEST FAILED (" + failures + ")");
      System.exit(failures == 0 ? 0 : 1);
   }

   static Img shifted(Img im) { Img o = im.copy(); o.p[0] = Stars.shift(im.p[0], im.w, im.h, 4, -3); for (int i = 0; i < o.p[0].length; i++) if (Float.isNaN(o.p[0][i])) o.p[0][i] = 100; return o; }
}
