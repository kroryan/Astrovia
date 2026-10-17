// Astrovia image processing engine: FITS reading (mono / RGB planes) and writing. GNU GPL v3.
package cds.astrovia.imgproc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.*;

public final class FitsIO {
   private FitsIO() { }

   private static final int BLOCK = 2880;
   /** Keywords kept in [Img.header]; the rest of the primary header is not needed to process the picture. */
   private static final Set<String> KEEP = new HashSet<String>(Arrays.asList("OBJECT", "TELESCOP", "INSTRUME", "DATE-OBS", "EXPTIME", "EXPOSURE", "FILTER", "BAYERPAT", "XBAYROFF", "YBAYROFF",
         "CTYPE1", "CTYPE2", "CRPIX1", "CRPIX2", "CRVAL1", "CRVAL2", "CDELT1", "CDELT2", "CD1_1", "CD1_2", "CD2_1", "CD2_2", "PC1_1", "PC1_2", "PC2_1", "PC2_2", "CROTA2", "LONPOLE", "RADESYS", "EQUINOX", "CUNIT1", "CUNIT2", "XPIXSZ", "YPIXSZ", "FOCALLEN", "CCD-TEMP", "GAIN", "IMAGETYP"));

   /** Where the data of an image HDU is inside the file. */
   public static final class Layout {
      public int bitpix, nx, ny, nz;
      public double bscale = 1, bzero = 0;
      public long dataStart;
      public Map<String, String> header = new LinkedHashMap<String, String>();
      public int bytes() { return Math.abs(bitpix) / 8; }
   }

   private static String value(String card) {
      String t = card.substring(10).trim();
      if (t.startsWith("'")) {
         StringBuilder sb = new StringBuilder(); int i = 1;
         while (i < t.length()) { char c = t.charAt(i); if (c == '\'') { if (i + 1 < t.length() && t.charAt(i + 1) == '\'') { sb.append('\''); i += 2; continue; } else break; } sb.append(c); i++; }
         return sb.toString().trim();
      }
      int s = t.indexOf('/');
      return (s >= 0 ? t.substring(0, s) : t).trim();
   }

   private static double dbl(String s, double d) { try { return s == null ? d : Double.parseDouble(s.replace('D', 'E')); } catch (Exception e) { return d; } }
   private static int num(String s, int d) { try { return s == null ? d : (int) Double.parseDouble(s); } catch (Exception e) { return d; } }

   /** Finds the first image HDU with 2 or more axes. */
   public static Layout layout(ByteBuffer buf) throws IOException {
      long len = buf.limit();
      long pos = 0;
      byte[] sig = new byte[9];
      if (len < BLOCK) throw new IOException("Not a FITS file");
      ByteBuffer d0 = buf.duplicate(); d0.get(sig);
      if (!new String(sig, "US-ASCII").equals("SIMPLE  =")) throw new IOException("Not a FITS file");
      for (int hdu = 0; hdu < 64 && pos + BLOCK <= len; hdu++) {
         Layout l = new Layout();
         boolean ended = false; long p = pos;
         while (!ended && p + BLOCK <= len) {
            byte[] block = new byte[BLOCK];
            ByteBuffer d = buf.duplicate(); d.position((int) p); d.get(block);
            for (int i = 0; i < 36; i++) {
               String card = new String(block, i * 80, 80, "US-ASCII");
               String key = card.substring(0, 8).trim();
               if (key.equals("END")) { ended = true; break; }
               if (card.length() > 10 && card.charAt(8) == '=' && !l.header.containsKey(key)) l.header.put(key, value(card));
            }
            p += BLOCK;
         }
         if (!ended) break;
         l.bitpix = num(l.header.get("BITPIX"), 8);
         int naxis = num(l.header.get("NAXIS"), 0);
         l.nx = num(l.header.get("NAXIS1"), 0); l.ny = num(l.header.get("NAXIS2"), 0); l.nz = naxis >= 3 ? Math.max(1, num(l.header.get("NAXIS3"), 1)) : 1;
         l.bscale = dbl(l.header.get("BSCALE"), 1); l.bzero = dbl(l.header.get("BZERO"), 0);
         l.dataStart = p;
         long elems = naxis == 0 ? 0 : 1;
         for (int a = 1; a <= naxis; a++) elems *= num(l.header.get("NAXIS" + a), 0);
         long dataBytes = (Math.abs(l.bitpix) / 8) * (long) num(l.header.get("GCOUNT"), 1) * (num(l.header.get("PCOUNT"), 0) + elems);
         String xt = l.header.get("XTENSION");
         if (naxis >= 2 && (hdu == 0 || "IMAGE".equals(xt)) && l.nx > 0 && l.ny > 0) return l;
         pos = p + ((dataBytes + BLOCK - 1) / BLOCK) * BLOCK;
      }
      throw new IOException("No image in this FITS file");
   }

   private static double pixel(ByteBuffer bb, int bitpix, int p) {
      switch (bitpix) {
         case 8: return bb.get(p) & 0xFF;
         case 16: return bb.getShort(p);
         case 32: return bb.getInt(p);
         case 64: return bb.getLong(p);
         case -32: return bb.getFloat(p);
         default: return bb.getDouble(p);
      }
   }

   /** Reads a whole image: mono, or 3 planes as RGB (the first 3 planes of a cube). NaN pixels become the image median. */
   public static Img read(File f) throws IOException {
      RandomAccessFile raf = new RandomAccessFile(f, "r");
      try {
         if (raf.length() > Integer.MAX_VALUE) throw new IOException("File larger than 2 GB");
         ByteBuffer buf = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length());
         Layout l = layout(buf);
         int planes = l.nz >= 3 ? 3 : 1;
         if (l.bitpix != 8 && l.bitpix != 16 && l.bitpix != 32 && l.bitpix != 64 && l.bitpix != -32 && l.bitpix != -64) throw new IOException("Unsupported BITPIX " + l.bitpix);
         Img img = new Img(l.nx, l.ny, planes);
         ByteBuffer bb = buf.duplicate().order(ByteOrder.BIG_ENDIAN);
         long planeBytes = (long) l.nx * l.ny * l.bytes();
         for (int k = 0; k < planes; k++) {
            float[] a = img.p[k];
            for (int i = 0; i < a.length; i++) {
               long pos = l.dataStart + k * planeBytes + (long) i * l.bytes();
               if (pos + l.bytes() > buf.limit()) { a[i] = Float.NaN; continue; }
               a[i] = (float) (pixel(bb, l.bitpix, (int) pos) * l.bscale + l.bzero);
            }
            fixNaN(a);
         }
         for (Map.Entry<String, String> e : l.header.entrySet()) if (KEEP.contains(e.getKey())) img.header.put(e.getKey(), e.getValue());
         return img;
      } finally { raf.close(); }
   }

   /** Replaces NaN / infinite values by the median so the filters do not spread them. */
   static void fixNaN(float[] a) {
      boolean any = false;
      for (float v : a) if (Float.isNaN(v) || Float.isInfinite(v)) { any = true; break; }
      if (!any) return;
      float m = Img.median(Img.sampleOf(a, 100000));
      for (int i = 0; i < a.length; i++) if (Float.isNaN(a[i]) || Float.isInfinite(a[i])) a[i] = m;
   }

   /** Reads rows [y0, y1) of the first plane as floats (for band-wise stacking). */
   public static float[] readRows(ByteBuffer buf, Layout l, int y0, int y1) {
      ByteBuffer bb = buf.duplicate().order(ByteOrder.BIG_ENDIAN);
      float[] out = new float[(y1 - y0) * l.nx];
      for (int i = 0; i < out.length; i++) {
         long pos = l.dataStart + ((long) y0 * l.nx + i) * l.bytes();
         out[i] = pos + l.bytes() > buf.limit() ? Float.NaN : (float) (pixel(bb, l.bitpix, (int) pos) * l.bscale + l.bzero);
      }
      return out;
   }

   /** Writes a 32-bit float FITS file (mono or 3 planes) keeping the useful keywords. */
   public static void write(Img img, File f, String... history) throws IOException {
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16));
      try {
         List<String> cards = new ArrayList<String>();
         cards.add(card("SIMPLE", "T"));
         cards.add(card("BITPIX", "-32"));
         cards.add(card("NAXIS", img.c == 1 ? "2" : "3"));
         cards.add(card("NAXIS1", String.valueOf(img.w)));
         cards.add(card("NAXIS2", String.valueOf(img.h)));
         if (img.c == 3) { cards.add(card("NAXIS3", "3")); cards.add(card("CTYPE3", "'RGB'")); }
         for (Map.Entry<String, String> e : img.header.entrySet()) {
            String v = e.getValue();
            boolean numeric = v.matches("[-+]?\\d+\\.?\\d*([eE][-+]?\\d+)?");
            cards.add(card(e.getKey(), numeric || v.equals("T") || v.equals("F") ? v : "'" + v.replace("'", "''") + "'"));
         }
         cards.add(String.format("%-80s", "HISTORY Processed with Astrovia"));
         for (String h : history) cards.add(String.format("%-80s", ("HISTORY " + h).substring(0, Math.min(80, ("HISTORY " + h).length()))));
         cards.add(String.format("%-80s", "END"));
         StringBuilder sb = new StringBuilder();
         for (String c : cards) sb.append(c);
         while (sb.length() % BLOCK != 0) sb.append(' ');
         out.write(sb.toString().getBytes("US-ASCII"));
         ByteBuffer bb = ByteBuffer.allocate(img.w * 4).order(ByteOrder.BIG_ENDIAN);
         long written = 0;
         for (int k = 0; k < img.c; k++) for (int y = 0; y < img.h; y++) {
            bb.clear();
            for (int x = 0; x < img.w; x++) bb.putFloat(img.p[k][y * img.w + x]);
            out.write(bb.array(), 0, img.w * 4);
            written += img.w * 4L;
         }
         int pad = (int) ((BLOCK - written % BLOCK) % BLOCK);
         out.write(new byte[pad]);
      } finally { out.close(); }
   }

   private static String card(String key, String value) {
      String k = String.format("%-8s", key.length() > 8 ? key.substring(0, 8) : key);
      String s = k + "= " + (value.startsWith("'") ? String.format("%-20s", value) : String.format("%20s", value));
      return String.format("%-80s", s.length() > 80 ? s.substring(0, 80) : s);
   }
}
