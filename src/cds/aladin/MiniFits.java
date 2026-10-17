// Astrovia: minimal FITS binary-table reader (light curves). Part of Astrovia, GNU GPL v3.
package cds.aladin;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Reads numeric columns of the BINTABLE extensions of a FITS file. */
final class MiniFits {
   private MiniFits() { }

   static final class Table {
      String extname;
      String object;
      double bjdRef = Double.NaN;
      List<String> columns = new ArrayList<String>();
      Map<String, double[]> data = new LinkedHashMap<String, double[]>();
   }

   private static String card(byte[] b, int off) { return new String(b, off, 80, java.nio.charset.StandardCharsets.US_ASCII); }

   private static String value(String card) {
      String t = card.substring(10).trim();
      if (t.startsWith("'")) {
         StringBuilder sb = new StringBuilder();
         int i = 1;
         while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '\'') { if (i + 1 < t.length() && t.charAt(i + 1) == '\'') { sb.append('\''); i += 2; continue; } else break; }
            sb.append(c); i++;
         }
         return sb.toString().trim();
      }
      int s = t.indexOf('/');
      return (s >= 0 ? t.substring(0, s) : t).trim();
   }

   /** All BINTABLE extensions with their numeric scalar columns (up to maxRows rows). */
   static List<Table> read(File f, int maxRows) throws IOException {
      byte[] all;
      DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
      try { all = new byte[(int) f.length()]; in.readFully(all); } finally { in.close(); }
      if (all.length < 2880 || !new String(all, 0, 9, java.nio.charset.StandardCharsets.US_ASCII).equals("SIMPLE  =")) throw new IOException("Not a FITS file");
      List<Table> out = new ArrayList<Table>();
      long pos = 0;
      while (pos + 2880 <= all.length) {
         Map<String, String> h = new LinkedHashMap<String, String>();
         boolean ended = false;
         long p = pos;
         while (!ended && p + 2880 <= all.length) {
            for (int i = 0; i < 36; i++) {
               String c = card(all, (int) p + i * 80);
               String key = c.substring(0, 8).trim();
               if (key.equals("END")) { ended = true; break; }
               if (c.length() > 10 && c.charAt(8) == '=' && !h.containsKey(key)) h.put(key, value(c));
            }
            p += 2880;
         }
         if (!ended) break;
         int naxis = toInt(h.get("NAXIS"), 0), bitpix = toInt(h.get("BITPIX"), 8);
         long elems = naxis == 0 ? 0 : 1;
         for (int a = 1; a <= naxis; a++) elems *= toInt(h.get("NAXIS" + a), 0);
         long dataBytes = (Math.abs(bitpix) / 8) * toInt(h.get("GCOUNT"), 1) * (toInt(h.get("PCOUNT"), 0) + elems);
         if ("BINTABLE".equals(h.get("XTENSION"))) out.add(table(all, p, h, maxRows));
         pos = p + ((dataBytes + 2879) / 2880) * 2880;
      }
      return out;
   }

   private static int toInt(String s, int d) { try { return s == null ? d : (int) Double.parseDouble(s); } catch (Exception e) { return d; } }
   private static double toDbl(String s, double d) { try { return s == null ? d : Double.parseDouble(s.replace('D', 'E')); } catch (Exception e) { return d; } }

   private static Table table(byte[] all, long start, Map<String, String> h, int maxRows) {
      Table t = new Table();
      t.extname = h.get("EXTNAME");
      t.object = h.get("OBJECT");
      if (h.containsKey("BJDREFI")) t.bjdRef = toDbl(h.get("BJDREFI"), 0) + toDbl(h.get("BJDREFF"), 0);
      int nrows = Math.min(toInt(h.get("NAXIS2"), 0), maxRows), rowLen = toInt(h.get("NAXIS1"), 0), nf = toInt(h.get("TFIELDS"), 0);
      ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
      int off = 0;
      for (int c = 1; c <= nf; c++) {
         String form = h.get("TFORM" + c) == null ? "" : h.get("TFORM" + c).trim();
         String name = h.get("TTYPE" + c) == null ? "col" + c : h.get("TTYPE" + c);
         int i = 0; while (i < form.length() && Character.isDigit(form.charAt(i))) i++;
         int rep = i == 0 ? 1 : Integer.parseInt(form.substring(0, i));
         char code = i < form.length() ? Character.toUpperCase(form.charAt(i)) : 'X';
         int w;
         switch (code) { case 'L': case 'B': case 'A': w = 1; break; case 'I': w = 2; break; case 'J': case 'E': w = 4; break; case 'K': case 'D': case 'C': case 'P': w = 8; break; case 'M': case 'Q': w = 16; break; default: w = (rep + 7) / 8; }
         int size = code == 'X' ? w : w * rep;
         t.columns.add(name);
         if (rep == 1 && "BIJKED".indexOf(code) >= 0) {
            double sc = toDbl(h.get("TSCAL" + c), 1), ze = toDbl(h.get("TZERO" + c), 0);
            double[] a = new double[nrows];
            for (int r = 0; r < nrows; r++) {
               int p = (int) (start + (long) r * rowLen + off);
               if (p + w > all.length) { a[r] = Double.NaN; continue; }
               double v;
               switch (code) {
                  case 'B': v = all[p] & 0xFF; break;
                  case 'I': v = bb.getShort(p); break;
                  case 'J': v = bb.getInt(p); break;
                  case 'K': v = bb.getLong(p); break;
                  case 'E': v = bb.getFloat(p); break;
                  default: v = bb.getDouble(p);
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
