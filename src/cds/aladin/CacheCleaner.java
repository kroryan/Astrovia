// Astrovia: shows how much disk the caches and temporary files use and clears them, without touching offline collections,
// lists, settings or downloaded HiPS. Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;

final class CacheCleaner {
   private CacheCleaner() { }

   static File dataDir() { return new File(System.getProperty("user.home"), ".astrovia"); }

   /** Folders that only hold re-creatable data: Aladin's cache, archive answers, processed pictures, light curves and temporary folders. */
   static List<File> targets() {
      List<File> l = new ArrayList<File>();
      File d = dataDir();
      for (String n : new String[] { "Cache", "archives", "processed", "lightcurves" }) { File f = new File(d, n); if (f.exists()) l.add(f); }
      File[] tmp = d.listFiles();
      if (tmp != null) for (File f : tmp) if (f.isDirectory() && f.getName().startsWith(".astrovia.")) l.add(f);
      return l;
   }

   static long size(File f) {
      if (f.isFile()) return f.length();
      long s = 0;
      File[] c = f.listFiles();
      if (c != null) for (File x : c) s += size(x);
      return s;
   }

   static boolean delete(File f) {
      File[] c = f.listFiles();
      if (c != null) for (File x : c) delete(x);
      return f.delete();
   }

   static String human(long b) { return b >= 1 << 30 ? String.format("%.1f GB", b / 1073741824.0) : b >= 1 << 20 ? String.format("%.1f MB", b / 1048576.0) : String.format("%.0f KB", b / 1024.0); }

   /** Asks, then clears. Offline HiPS and catalogues, lists, settings and your processed work outside the data folder are not touched. */
   static void clear(Window owner) {
      List<File> t = targets();
      long total = 0;
      for (File f : t) total += size(f);
      if (t.isEmpty() || total == 0) { JOptionPane.showMessageDialog(owner, "The cache is already empty.", "Clear cache", JOptionPane.INFORMATION_MESSAGE); return; }
      int ok = JOptionPane.showConfirmDialog(owner,
            "Clear " + human(total) + " of cache and temporary files?\n\nYour offline collections (HiPS and catalogues), lists, settings and saved files are not touched.\nAstrovia will download again what it needs.",
            "Clear cache", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
      if (ok != JOptionPane.OK_OPTION) return;
      long freed = 0;
      for (File f : t) { long s = size(f); if (delete(f)) freed += s; }
      JOptionPane.showMessageDialog(owner, "Freed " + human(freed) + ".", "Clear cache", JOptionPane.INFORMATION_MESSAGE);
   }
}
