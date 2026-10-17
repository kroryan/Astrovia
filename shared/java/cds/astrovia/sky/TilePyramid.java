// Astrovia shared code: tile pyramids of very large pictures (Zoomify and Deep Zoom layouts). GNU GPL v3.
package cds.astrovia.sky;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A very large picture (30 000 x 38 000 px) split in tiles of several resolutions: only the tiles in view are downloaded.
 * Levels are numbered 0 = smallest ... maxLevel = full resolution. Used by the Android gallery and the desktop viewer.
 */
public abstract class TilePyramid {
   public final int width, height, tileSize;

   protected TilePyramid(int width, int height, int tileSize) { this.width = width; this.height = height; this.tileSize = tileSize; }

   public abstract int getMaxLevel();
   public abstract String tileUrl(int level, int col, int row);

   public int levelWidth(int level) { return (int) Math.max(1, Math.ceil(width / Math.pow(2, getMaxLevel() - level))); }
   public int levelHeight(int level) { return (int) Math.max(1, Math.ceil(height / Math.pow(2, getMaxLevel() - level))); }
   public int cols(int level) { return (int) Math.ceil(levelWidth(level) / (double) tileSize); }
   public int rows(int level) { return (int) Math.ceil(levelHeight(level) / (double) tileSize); }

   /** Largest level that fits in a single tile (used for thumbnails). */
   public int thumbnailLevel() {
      for (int l = getMaxLevel(); l >= 0; l--) if (cols(l) == 1 && rows(l) == 1) return l;
      return 0;
   }

   /** Pixel rectangle {left, top, right, bottom} of a tile inside its level, ignoring overlaps. */
   public int[] tileRect(int level, int col, int row) {
      int l = col * tileSize, t = row * tileSize;
      return new int[] { l, t, Math.min(l + tileSize, levelWidth(level)), Math.min(t + tileSize, levelHeight(level)) };
   }

   /** Pixels of overlap present on the left / top of a tile image (Deep Zoom only). */
   public int overlapLeft(int col) { return 0; }
   public int overlapTop(int row) { return 0; }

   private static int attr(String xml, String name) {
      Matcher m = Pattern.compile(name + "=\"(\\d+)\"").matcher(xml);
      return m.find() ? Integer.parseInt(m.group(1)) : -1;
   }

   /** From the ImageProperties.xml text of a Zoomify folder; null when it cannot be read. */
   public static TilePyramid zoomify(String base, String xml) {
      int w = attr(xml, "WIDTH"), h = attr(xml, "HEIGHT");
      if (w < 0 || h < 0) return null;
      int ts = attr(xml, "TILESIZE");
      return new Zoomify(base.replaceAll("/+$", ""), w, h, ts > 0 ? ts : 256);
   }

   /** From the text of a .dzi file; null when it cannot be read. */
   public static TilePyramid deepZoom(String dziUrl, String xml) {
      int w = attr(xml, "Width"), h = attr(xml, "Height");
      if (w < 0 || h < 0) return null;
      Matcher f = Pattern.compile("Format=\"(\\w+)\"").matcher(xml);
      int ts = attr(xml, "TileSize"), ov = attr(xml, "Overlap");
      return new DeepZoom(dziUrl.endsWith(".dzi") ? dziUrl.substring(0, dziUrl.length() - 4) : dziUrl, w, h, ts > 0 ? ts : 254, Math.max(0, ov), f.find() ? f.group(1) : "jpg");
   }

   private static final class Zoomify extends TilePyramid {
      private final String base;
      private final int maxLevel;
      Zoomify(String base, int w, int h, int ts) {
         super(w, h, ts);
         this.base = base;
         int ww = w, hh = h, n = 0;
         while (ww > ts || hh > ts) { ww = (ww + 1) / 2; hh = (hh + 1) / 2; n++; }
         maxLevel = n;
      }
      public int getMaxLevel() { return maxLevel; }
      /** Index of the first tile of a level in the whole pyramid (tiles are numbered from the smallest tier). */
      private int first(int level) { int s = 0; for (int l = 0; l < level; l++) s += cols(l) * rows(l); return s; }
      public String tileUrl(int level, int col, int row) { return base + "/TileGroup" + ((first(level) + row * cols(level) + col) / 256) + "/" + level + "-" + col + "-" + row + ".jpg"; }
   }

   private static final class DeepZoom extends TilePyramid {
      private final String base, format;
      private final int overlap, maxLevel;
      DeepZoom(String base, int w, int h, int ts, int overlap, String format) {
         super(w, h, ts);
         this.base = base; this.overlap = overlap; this.format = format;
         maxLevel = (int) Math.ceil(Math.log(Math.max(w, h)) / Math.log(2));
      }
      public int getMaxLevel() { return maxLevel; }
      public String tileUrl(int level, int col, int row) { return base + "_files/" + level + "/" + col + "_" + row + "." + format; }
      public int overlapLeft(int col) { return col > 0 ? overlap : 0; }
      public int overlapTop(int row) { return row > 0 ? overlap : 0; }
   }
}
