// Astrovia shared code: numeric columns of the binary-table extensions of a FITS file (light curves). GNU GPL v3.
package cds.astrovia.imgproc;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FitsTables {
   private FitsTables() { }

   public static final class Table {
      public String extname, object;
      public double bjdRef = Double.NaN;
      public List<String> columns = new ArrayList<String>();
      /** Scalar numeric columns, as doubles with TSCAL / TZERO applied. */
      public Map<String, double[]> data = new LinkedHashMap<String, double[]>();
   }

   private static double dbl(String s, double d) { try { return s == null ? d : Double.parseDouble(s.replace('D', 'E')); } catch (Exception e) { return d; } }
   private static int num(String s, int d) { try { return s == null ? d : (int) Double.parseDouble(s); } catch (Exception e) { return d; } }

   /** All BINTABLE extensions with their numeric columns (up to maxRows rows each). */
   public static List<Table> read(File f, int maxRows) throws IOException {
      RandomAccessFile raf = new RandomAccessFile(f, "r");
      try {
         if (raf.length() > Integer.MAX_VALUE) throw new IOException("File larger than 2 GB");
         ByteBuffer buf = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length());
         List<Table> out = new ArrayList<Table>();
         for (FitsIO.Hdu h : FitsIO.hdus(buf)) if ("BINTABLE".equals(h.extension())) out.add(table(buf, h, maxRows));
         return out;
      } finally { raf.close(); }
   }

   private static Table table(ByteBuffer buf, FitsIO.Hdu hdu, int maxRows) {
      Map<String, String> h = hdu.header;
      Table t = new Table();
      t.extname = h.get("EXTNAME"); t.object = h.get("OBJECT");
      if (h.containsKey("BJDREFI")) t.bjdRef = dbl(h.get("BJDREFI"), 0) + dbl(h.get("BJDREFF"), 0);
      int nrows = Math.min(num(h.get("NAXIS2"), 0), maxRows), rowLen = num(h.get("NAXIS1"), 0), nf = num(h.get("TFIELDS"), 0);
      ByteBuffer bb = buf.duplicate().order(ByteOrder.BIG_ENDIAN);
      int off = 0;
      for (int c = 1; c <= nf; c++) {
         String form = h.get("TFORM" + c) == null ? "" : h.get("TFORM" + c).trim();
         String name = h.get("TTYPE" + c) == null ? "col" + c : h.get("TTYPE" + c);
         int i = 0;
         while (i < form.length() && Character.isDigit(form.charAt(i))) i++;
         int rep = i == 0 ? 1 : Integer.parseInt(form.substring(0, i));
         char code = i < form.length() ? Character.toUpperCase(form.charAt(i)) : 'X';
         int w;
         switch (code) { case 'L': case 'B': case 'A': w = 1; break; case 'I': w = 2; break; case 'J': case 'E': w = 4; break; case 'K': case 'D': case 'C': case 'P': w = 8; break; case 'M': case 'Q': w = 16; break; default: w = (rep + 7) / 8; }
         int size = code == 'X' ? w : w * rep;
         t.columns.add(name);
         if (rep == 1 && "BIJKED".indexOf(code) >= 0) {
            double sc = dbl(h.get("TSCAL" + c), 1), ze = dbl(h.get("TZERO" + c), 0);
            double[] a = new double[nrows];
            for (int r = 0; r < nrows; r++) {
               long p = hdu.dataStart + (long) r * rowLen + off;
               if (p + w > buf.limit()) { a[r] = Double.NaN; continue; }
               int q = (int) p;
               double v;
               switch (code) {
                  case 'B': v = bb.get(q) & 0xFF; break;
                  case 'I': v = bb.getShort(q); break;
                  case 'J': v = bb.getInt(q); break;
                  case 'K': v = bb.getLong(q); break;
                  case 'E': v = bb.getFloat(q); break;
                  default: v = bb.getDouble(q);
               }
               a[r] = v * sc + ze;
            }
            t.data.put(name, a);
         }
         off += size;
      }
      return t;
   }
}
