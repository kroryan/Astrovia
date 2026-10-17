// Astrovia: explorer of very large outreach pictures (Webb, Hubble, Euclid) through their tile pyramids.
// Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import cds.astrovia.sky.TilePyramid;
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

   /** Opens the pyramid of a picture (".dzi" Deep Zoom file or a Zoomify folder); the geometry is shared with the Android app. */
   static TilePyramid open(String tilesUrl) throws java.io.IOException {
      TilePyramid p;
      if (tilesUrl.endsWith(".dzi")) p = TilePyramid.deepZoom(tilesUrl, new String(ArchivesDialog.fetch(tilesUrl), "UTF-8"));
      else p = TilePyramid.zoomify(tilesUrl, new String(ArchivesDialog.fetch(tilesUrl.replaceAll("/+$", "") + "/ImageProperties.xml"), "UTF-8"));
      if (p == null) throw new java.io.IOException("Unreadable tile description");
      return p;
   }

   private final TilePyramid p;
   private double scale = 1, ox, oy;
   private boolean placed = false;
   private int dragX, dragY;
   private final Map<String, BufferedImage> cache = Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>(256, 0.75f, true) {
      protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 260; }
   });
   private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
   private final ExecutorService pool = Executors.newFixedThreadPool(5);

   OutreachViewer(TilePyramid p) {
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

   private double fit() { return Math.min(getWidth() / (double) p.width, getHeight() / (double) p.height); }

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
      double f = Math.pow(2, p.getMaxLevel() - level);
      int cols = p.cols(level), rows = p.rows(level);
      for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
         int l = c * p.tileSize, t = r * p.tileSize, rr = Math.min(l + p.tileSize, p.levelWidth(level)), bb = Math.min(t + p.tileSize, p.levelHeight(level));
         double sx = ox + l * f * s, sy = oy + t * f * s, dw = (rr - l) * f * s, dh = (bb - t) * f * s;
         if (sx + dw < 0 || sy + dh < 0 || sx > getWidth() || sy > getHeight()) continue;
         BufferedImage b = tile(p.tileUrl(level, c, r));
         if (b == null) continue;
         int lo = p.overlapLeft(c), to = p.overlapTop(r);
         g.drawImage(b, (int) sx, (int) sy, (int) Math.ceil(sx + dw), (int) Math.ceil(sy + dh), lo, to, Math.min(b.getWidth(), lo + rr - l), Math.min(b.getHeight(), to + bb - t), null);
      }
   }

   protected void paintComponent(Graphics g0) {
      super.paintComponent(g0);
      Graphics2D g = (Graphics2D) g0;
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      if (!placed && getWidth() > 0) { double fit = fit(); ox = (getWidth() - p.width * fit) / 2; oy = (getHeight() - p.height * fit) / 2; placed = true; }
      double s = fit() * scale;
      int want = (int) Math.max(0, Math.min(p.getMaxLevel(), Math.ceil(p.getMaxLevel() + Math.log(s) / Math.log(2))));
      int th = p.thumbnailLevel(), back = Math.max(th, Math.min(th + 1, want));
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
            final TilePyramid pyr = open(pic.tilesUrl);
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
