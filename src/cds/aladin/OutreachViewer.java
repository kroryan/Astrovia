// Astrovia: explorer of very large outreach pictures (Webb, Hubble, Euclid) through their tile pyramids.
// Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Pan (drag) and zoom (wheel) over a tile pyramid; only the visible tiles are downloaded. */
final class OutreachViewer extends JPanel {

   /** One picture of the gallery. */
   static final class Pic {
      String id, title, description, credit, tilesUrl, mission;
      double ra, dec, fov;
      double[] cornersRa = new double[0], cornersDec = new double[0];

      String thumbnailUrl() {
         return tilesUrl.endsWith(".dzi") ? tilesUrl.substring(0, tilesUrl.length() - 4) + "_files/8/0_0.jpg" : tilesUrl.replaceAll("/+$", "") + "/TileGroup0/0-0-0.jpg";
      }

      String webPage() {
         if (tilesUrl.contains("esawebb.org")) return "https://esawebb.org/images/" + id + "/";
         if (tilesUrl.contains("esahubble.org")) return "https://esahubble.org/images/" + id + "/";
         return null;
      }

      public String toString() { return title; }
   }

   /** Tile pyramid: levels 0 (smallest) ... maxLevel (full size). */
   abstract static class Pyr {
      final int w, h, ts;
      int maxLevel;
      Pyr(int w, int h, int ts) { this.w = w; this.h = h; this.ts = ts; }
      int lw(int l) { return (int) Math.max(1, Math.ceil(w / Math.pow(2, maxLevel - l))); }
      int lh(int l) { return (int) Math.max(1, Math.ceil(h / Math.pow(2, maxLevel - l))); }
      int cols(int l) { return (int) Math.ceil(lw(l) / (double) ts); }
      int rows(int l) { return (int) Math.ceil(lh(l) / (double) ts); }
      abstract String url(int l, int c, int r);
      int overlapLeft(int c) { return 0; }
      int overlapTop(int r) { return 0; }
      int thumbLevel() { for (int l = maxLevel; l >= 0; l--) if (cols(l) == 1 && rows(l) == 1) return l; return 0; }
   }

   static final class Zoomify extends Pyr {
      private final String base;
      Zoomify(String base, int w, int h, int ts) {
         super(w, h, ts);
         this.base = base;
         int ww = w, hh = h, n = 0;
         while (ww > ts || hh > ts) { ww = (ww + 1) / 2; hh = (hh + 1) / 2; n++; }
         maxLevel = n;
      }
      private int first(int level) { int s = 0; for (int l = 0; l < level; l++) s += cols(l) * rows(l); return s; }
      String url(int l, int c, int r) { return base + "/TileGroup" + ((first(l) + r * cols(l) + c) / 256) + "/" + l + "-" + c + "-" + r + ".jpg"; }
   }

   static final class DeepZoom extends Pyr {
      private final String base, fmt;
      private final int overlap;
      DeepZoom(String base, int w, int h, int ts, int overlap, String fmt) {
         super(w, h, ts);
         this.base = base; this.overlap = overlap; this.fmt = fmt;
         maxLevel = (int) Math.ceil(Math.log(Math.max(w, h)) / Math.log(2));
      }
      String url(int l, int c, int r) { return base + "_files/" + l + "/" + c + "_" + r + "." + fmt; }
      int overlapLeft(int c) { return c > 0 ? overlap : 0; }
      int overlapTop(int r) { return r > 0 ? overlap : 0; }
   }

   private static int attr(String xml, String name) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile(name + "=\"(\\d+)\"").matcher(xml);
      return m.find() ? Integer.parseInt(m.group(1)) : -1;
   }

   /** Opens the pyramid of a picture (".dzi" Deep Zoom file or a Zoomify folder). */
   static Pyr open(String tilesUrl) throws java.io.IOException {
      if (tilesUrl.endsWith(".dzi")) {
         String xml = new String(ArchivesDialog.fetch(tilesUrl), "UTF-8");
         java.util.regex.Matcher f = java.util.regex.Pattern.compile("Format=\"(\\w+)\"").matcher(xml);
         int w = attr(xml, "Width"), h = attr(xml, "Height");
         if (w < 0 || h < 0) throw new java.io.IOException("Bad .dzi file");
         return new DeepZoom(tilesUrl.substring(0, tilesUrl.length() - 4), w, h, attr(xml, "TileSize") > 0 ? attr(xml, "TileSize") : 254, Math.max(0, attr(xml, "Overlap")), f.find() ? f.group(1) : "jpg");
      }
      String base = tilesUrl.replaceAll("/+$", "");
      String xml = new String(ArchivesDialog.fetch(base + "/ImageProperties.xml"), "UTF-8");
      int w = attr(xml, "WIDTH"), h = attr(xml, "HEIGHT");
      if (w < 0 || h < 0) throw new java.io.IOException("Bad ImageProperties.xml");
      return new Zoomify(base, w, h, attr(xml, "TILESIZE") > 0 ? attr(xml, "TILESIZE") : 256);
   }

   private final Pyr p;
   private double scale = 1, ox, oy;
   private boolean placed = false;
   private int dragX, dragY;
   private final Map<String, BufferedImage> cache = Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>(256, 0.75f, true) {
      protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 260; }
   });
   private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
   private final ExecutorService pool = Executors.newFixedThreadPool(5);

   OutreachViewer(Pyr p) {
      this.p = p;
      setBackground(Color.BLACK);
      setPreferredSize(new Dimension(900, 620));
      addMouseWheelListener(new MouseWheelListener() { public void mouseWheelMoved(MouseWheelEvent e) {
         double fit = fit(), ns = Math.max(0.6, Math.min(400, scale * (e.getWheelRotation() < 0 ? 1.25 : 0.8))), k = ns / scale;
         ox = (ox - e.getX()) * k + e.getX(); oy = (oy - e.getY()) * k + e.getY(); scale = ns; repaint();
      } });
      addMouseListener(new MouseAdapter() { public void mousePressed(MouseEvent e) { dragX = e.getX(); dragY = e.getY(); } });
      addMouseMotionListener(new MouseMotionAdapter() { public void mouseDragged(MouseEvent e) { ox += e.getX() - dragX; oy += e.getY() - dragY; dragX = e.getX(); dragY = e.getY(); repaint(); } });
   }

   void dispose() { pool.shutdownNow(); }

   private double fit() { return Math.min(getWidth() / (double) p.w, getHeight() / (double) p.h); }

   private BufferedImage tile(final String url) {
      BufferedImage b = cache.get(url);
      if (b != null) return b;
      if (inFlight.add(url)) {
         pool.submit(new Runnable() { public void run() {
            try {
               BufferedImage img = ImageIO.read(new ByteArrayInputStream(ArchivesDialog.fetch(url)));
               if (img != null) cache.put(url, img);
            } catch (Exception ex) { /* tile missing: leave a gap */ }
            finally { inFlight.remove(url); SwingUtilities.invokeLater(new Runnable() { public void run() { repaint(); } }); }
         } });
      }
      return null;
   }

   private void drawLevel(Graphics2D g, int level, double s) {
      double f = Math.pow(2, p.maxLevel - level);
      int cols = p.cols(level), rows = p.rows(level);
      for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
         int l = c * p.ts, t = r * p.ts, rr = Math.min(l + p.ts, p.lw(level)), bb = Math.min(t + p.ts, p.lh(level));
         double sx = ox + l * f * s, sy = oy + t * f * s, dw = (rr - l) * f * s, dh = (bb - t) * f * s;
         if (sx + dw < 0 || sy + dh < 0 || sx > getWidth() || sy > getHeight()) continue;
         BufferedImage b = tile(p.url(level, c, r));
         if (b == null) continue;
         int lo = p.overlapLeft(c), to = p.overlapTop(r);
         g.drawImage(b, (int) sx, (int) sy, (int) Math.ceil(sx + dw), (int) Math.ceil(sy + dh), lo, to, Math.min(b.getWidth(), lo + rr - l), Math.min(b.getHeight(), to + bb - t), null);
      }
   }

   protected void paintComponent(Graphics g0) {
      super.paintComponent(g0);
      Graphics2D g = (Graphics2D) g0;
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      if (!placed && getWidth() > 0) { double fit = fit(); ox = (getWidth() - p.w * fit) / 2; oy = (getHeight() - p.h * fit) / 2; placed = true; }
      double s = fit() * scale;
      int want = (int) Math.max(0, Math.min(p.maxLevel, Math.ceil(p.maxLevel + Math.log(s) / Math.log(2))));
      int th = p.thumbLevel(), back = Math.max(th, Math.min(th + 1, want));
      drawLevel(g, th, s);
      if (back > th) drawLevel(g, back, s);
      if (want > back) { if (want - 2 > back) drawLevel(g, want - 2, s); drawLevel(g, want, s); }
      g.setColor(Color.LIGHT_GRAY);
      g.drawString("wheel = zoom, drag = pan", 8, getHeight() - 8);
   }

   /** Window with the explorer and the actions of a picture. */
   static void show(final Window owner, final Pic pic, final Aladin aladin) {
      new Thread(new Runnable() { public void run() {
         try {
            final Pyr pyr = open(pic.tilesUrl);
            SwingUtilities.invokeLater(new Runnable() { public void run() {
               final JDialog d = new JDialog(owner, pic.title, Dialog.ModalityType.MODELESS);
               final OutreachViewer v = new OutreachViewer(pyr);
               d.setLayout(new BorderLayout());
               d.add(v, BorderLayout.CENTER);
               JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
               JButton info = new JButton("Info"), sky = new JButton("Draw outline on the sky"), go = new JButton("Go to position"), web = new JButton("Web page");
               bar.add(info); bar.add(sky); bar.add(go); bar.add(web);
               d.add(bar, BorderLayout.SOUTH);
               info.addActionListener(e -> { JTextArea t = new JTextArea(pic.description + "\n\nCredit: " + pic.credit, 14, 60); t.setLineWrap(true); t.setWrapStyleWord(true); t.setEditable(false); JOptionPane.showMessageDialog(d, new JScrollPane(t), pic.title, JOptionPane.INFORMATION_MESSAGE); });
               go.addActionListener(e -> { if (aladin != null) { aladin.execAsyncCommand(String.format(Locale.US, "%.6f %.6f", pic.ra, pic.dec)); aladin.execAsyncCommand(String.format(Locale.US, "zoom %.4fdeg", Math.max(0.002, pic.fov * 1.4))); } });
               sky.addActionListener(e -> { if (aladin != null) drawOutline(aladin, pic); });
               web.addActionListener(e -> { String w = pic.webPage(); if (w != null && aladin != null) aladin.glu.showDocument("Http", w, true); });
               web.setEnabled(pic.webPage() != null);
               d.addWindowListener(new WindowAdapter() { public void windowClosed(WindowEvent e) { v.dispose(); } });
               d.pack(); d.setLocationRelativeTo(owner); d.setVisible(true);
            } });
         } catch (final Exception ex) {
            SwingUtilities.invokeLater(new Runnable() { public void run() { JOptionPane.showMessageDialog(owner, "Could not open this picture: " + ex.getMessage()); } });
         }
      } }, "Astrovia-open-picture").start();
   }

   /** Draws the picture's footprint in a new overlay plane and centres the view on it. */
   static void drawOutline(Aladin aladin, Pic pic) {
      aladin.execAsyncCommand("draw newtool(" + pic.title.replaceAll("[(),]", " ") + ")");
      if (pic.cornersRa.length >= 3) {
         StringBuilder sb = new StringBuilder("draw cyan polygon(");
         for (int i = 0; i < pic.cornersRa.length; i++) sb.append(i > 0 ? "," : "").append(String.format(Locale.US, "%.6f,%.6f", pic.cornersRa[i], pic.cornersDec[i]));
         aladin.execAsyncCommand(sb.append(")").toString());
      }
      aladin.execAsyncCommand(String.format(Locale.US, "%.6f %.6f", pic.ra, pic.dec));
      aladin.execAsyncCommand(String.format(Locale.US, "zoom %.4fdeg", Math.max(0.002, pic.fov * 1.4)));
   }
}
