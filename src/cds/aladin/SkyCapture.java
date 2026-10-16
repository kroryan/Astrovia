// Astrovia: capture of the sky picture of the current view (image only, maximum quality).
// Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import cds.tools.Util;

/**
 * "Save the sky view": exports ONLY the sky picture of the current view, not the application.
 *
 * <p>It does not copy pixels from the screen. It re-renders the current view off-screen
 * (the same code path as File &gt; Save the current view and as the script command {@code save})
 * without overlays, toolbars or panels, in a temporary view that is {@code k} times larger
 * with the zoom multiplied by {@code k}: the field of view is exactly the one on screen, with
 * {@code k} times more pixels per degree. Because the zoom is higher, the HiPS engine asks for
 * finer tiles, so the image has real extra detail (up to the survey's native resolution) and not
 * just interpolated pixels. The render waits until the tiles have arrived and the picture stops
 * changing before it is saved.
 */
final class SkyCapture {
   private SkyCapture() { }

   /** Largest area (pixels) we dare to allocate: ~28 % of the heap at 4 bytes per pixel. */
   static long maxPixels() { return (long)(Runtime.getRuntime().maxMemory() * 0.28 / 4.0); }

   /** Output size for a given on-screen size and integer scale. */
   static int[] outputSize(int w, int h, int k) { return new int[] { w * k, h * k }; }

   /** The scales (1..4) that fit in memory for this view. */
   static int maxScale(int w, int h) {
      int best = 1;
      for (int k = 2; k <= 4; k++) {
         if ((long)w * k * (long)h * k <= maxPixels() && w * k <= 16000 && h * k <= 16000) best = k;
      }
      return best;
   }

   // ------------------------------------------------------------------------ remembered choices

   /** Where captures go and the last options, kept in ~/.astrovia/capture.properties. */
   private static File prefsFile() {
      return new File(System.getProperty("user.home"), ".astrovia" + File.separator + "capture.properties");
   }

   private static Properties loadPrefs() {
      Properties p = new Properties();
      try (FileInputStream in = new FileInputStream(prefsFile())) { p.load(in); } catch (Exception e) { /* first run */ }
      return p;
   }

   private static void savePrefs(Options o) {
      try {
         Properties p = loadPrefs();
         File dir = o.file.getAbsoluteFile().getParentFile();
         if (dir != null) p.setProperty("dir", dir.getPath());
         p.setProperty("scale", String.valueOf(o.scale));
         p.setProperty("png", String.valueOf(o.png));
         p.setProperty("overlays", String.valueOf(o.overlays));
         File f = prefsFile();
         f.getParentFile().mkdirs();
         try (FileOutputStream out = new FileOutputStream(f)) { p.store(out, "Astrovia sky capture"); }
      } catch (Exception e) { /* preferences are optional */ }
   }

   /** The folder to suggest: the last one used, otherwise ~/Pictures/Astrovia. */
   static File captureDir() {
      String d = loadPrefs().getProperty("dir");
      if (d != null && d.trim().length() > 0) return new File(d);
      return new File(System.getProperty("user.home"), "Pictures" + File.separator + "Astrovia");
   }

   // ------------------------------------------------------------------------------ the button

   /** Camera button for the top-right bar, outside the sky view. */
   static final class Button extends JComponent implements MouseListener {
      private final Aladin aladin;
      private boolean hover = false;

      Button(Aladin aladin) {
         this.aladin = aladin;
         addMouseListener(this);
         Util.toolTip(this, aladin.chaine.getString("TIPSAVESKY"));
         setPreferredSize(new Dimension(40, 40));
         setMinimumSize(new Dimension(40, 40));
      }

      public void paintComponent(Graphics g0) {
         Graphics2D g = (Graphics2D)g0;
         g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
         int w = getWidth(), h = getHeight();
         g.setColor(getBackground());
         g.fillRect(0, 0, w, h);
         if (hover) {
            g.setColor(new Color(0x3F, 0x6F, 0xB0, 90));
            g.fill(new RoundRectangle2D.Float(2, 2, w - 4, h - 4, 12, 12));
         }
         Color fg = UIManager.getColor("Label.foreground");
         g.setColor(fg != null ? fg : Color.white);
         // camera body, viewfinder bump and lens
         g.setStroke(new java.awt.BasicStroke(2f));
         float x = 7, y = 12, bw = w - 14, bh = h - 22;
         g.draw(new RoundRectangle2D.Float(x, y, bw, bh, 6, 6));
         g.fill(new RoundRectangle2D.Float(w / 2f - 6, y - 4, 12, 5, 3, 3));
         g.draw(new Ellipse2D.Float(w / 2f - 6, y + bh / 2f - 6, 12, 12));
         g.fill(new Ellipse2D.Float(w - 14, y + 3, 3, 3));
      }

      public void mousePressed(MouseEvent e) { run(aladin); }
      public void mouseEntered(MouseEvent e) { hover = true; repaint(); Aladin.makeCursor(this, Aladin.HANDCURSOR); }
      public void mouseExited(MouseEvent e) { hover = false; repaint(); Aladin.makeCursor(this, Aladin.DEFAULTCURSOR); }
      public void mouseClicked(MouseEvent e) { }
      public void mouseReleased(MouseEvent e) { }
   }

   // ------------------------------------------------------------------------------ user flow

   private static final class Options {
      int scale = 1;
      boolean png = true;
      boolean overlays = false;
      File file;
   }

   /** Entry point (button and menu): asks for the options, then renders and saves in the background. */
   static void run(final Aladin aladin) {
      final ViewSimple cur = aladin.view.getCurrentView();
      if (cur == null || cur.isFree() || cur.rv == null || cur.rv.width < 1 || cur.pref == null) {
         JOptionPane.showMessageDialog(aladin.f, "There is no sky image in the current view to capture.",
               "Save the sky view", JOptionPane.INFORMATION_MESSAGE);
         return;
      }
      final Options o = askOptions(aladin, cur);
      if (o == null) return;

      final AtomicBoolean cancel = new AtomicBoolean(false);
      final JLabel status = new JLabel("Preparing...");
      final JDialog progress = new JDialog(aladin.f, "Saving the sky view", false);
      JProgressBar bar = new JProgressBar();
      bar.setIndeterminate(true);
      JButton stop = new JButton("Cancel");
      stop.addActionListener(e -> { cancel.set(true); status.setText("Cancelling..."); });
      JPanel p = new JPanel(new BorderLayout(8, 8));
      p.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
      p.add(status, BorderLayout.NORTH);
      p.add(bar, BorderLayout.CENTER);
      JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
      south.add(stop);
      p.add(south, BorderLayout.SOUTH);
      progress.setContentPane(p);
      progress.setPreferredSize(new Dimension(420, 150));
      progress.pack();
      progress.setLocationRelativeTo(aladin.f);
      progress.setVisible(true);

      Thread t = new Thread(new Runnable() {
         public void run() {
            String result;
            boolean ok = false;
            try {
               result = capture(aladin, o, cancel, new Status() {
                  public void set(final String s) {
                     SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText(s); } });
                  }
               });
               ok = result != null;
               if (result == null) result = "Cancelled.";
            } catch (OutOfMemoryError e) {
               result = "Not enough memory for that size. Choose a smaller scale.";
            } catch (Exception e) {
               result = "The capture failed: " + e.getMessage();
               if (Aladin.levelTrace >= 3) e.printStackTrace();
            }
            final String msg = result;
            final boolean good = ok;
            SwingUtilities.invokeLater(new Runnable() {
               public void run() {
                  progress.dispose();
                  JOptionPane.showMessageDialog(aladin.f, msg, "Save the sky view",
                        good ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
               }
            });
         }
      }, "Astrovia-capture");
      t.setDaemon(true);
      t.start();
   }

   private interface Status { void set(String s); }

   private static Options askOptions(Aladin aladin, ViewSimple cur) {
      final Options o = new Options();
      int w = cur.rv.width, h = cur.rv.height;
      int maxK = maxScale(w, h);
      Properties prefs = loadPrefs();
      int wantK = 2;
      try { wantK = Integer.parseInt(prefs.getProperty("scale", "2")); } catch (Exception e) { }
      final int defaultK = Math.max(1, Math.min(wantK, maxK));
      final boolean defaultPng = !"false".equals(prefs.getProperty("png"));
      final boolean defaultOverlays = "true".equals(prefs.getProperty("overlays"));

      final JDialog d = new JDialog(aladin.f, "Save the sky view", true);
      JPanel p = new JPanel(new GridLayout(0, 1, 0, 2));
      p.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
      p.add(new JLabel("Saves only the sky image of the current view:"));
      p.add(new JLabel("no panels, toolbars or crosshair, at the finest detail available."));
      p.add(new JLabel(" "));
      p.add(new JLabel("Resolution"));
      ButtonGroup sg = new ButtonGroup();
      final JRadioButton[] sr = new JRadioButton[5];
      for (int k = 1; k <= maxK; k++) {
         int[] sz = outputSize(w, h, k);
         sr[k] = new JRadioButton((k == 1 ? "Screen size" : k + "x") + "  (" + sz[0] + " x " + sz[1] + " px)", k == defaultK);
         sg.add(sr[k]);
         p.add(sr[k]);
      }
      p.add(new JLabel(" "));
      p.add(new JLabel("Format"));
      ButtonGroup fg = new ButtonGroup();
      final JRadioButton png = new JRadioButton("PNG (lossless, keeps the view's WCS in the file)", defaultPng);
      final JRadioButton jpg = new JRadioButton("JPEG (quality 100)", !defaultPng);
      fg.add(png);
      fg.add(jpg);
      p.add(png);
      p.add(jpg);
      final JCheckBox ov = new JCheckBox("Also draw overlays (catalogues, graphics, grid)", defaultOverlays);
      p.add(new JLabel(" "));
      p.add(ov);
      p.add(new JLabel("The folder you choose below is remembered for next time."));

      final JTextField path = new JTextField(defaultFile(cur, defaultPng).getPath(), 34);
      JButton browse = new JButton("Browse...");
      browse.addActionListener(e -> {
         JFileChooser fc = new JFileChooser(new File(path.getText()).getParentFile());
         fc.setSelectedFile(new File(path.getText()));
         if (fc.showSaveDialog(d) == JFileChooser.APPROVE_OPTION) path.setText(fc.getSelectedFile().getPath());
      });
      png.addActionListener(e -> path.setText(swapExt(path.getText(), "png")));
      jpg.addActionListener(e -> path.setText(swapExt(path.getText(), "jpg")));
      path.setCaretPosition(0);
      JPanel fp = new JPanel(new BorderLayout(6, 0));
      fp.add(new JLabel("Save as"), BorderLayout.WEST);
      fp.add(path, BorderLayout.CENTER);
      fp.add(browse, BorderLayout.EAST);

      final boolean[] accepted = new boolean[1];
      JButton go = new JButton("Capture");
      JButton no = new JButton("Cancel");
      go.addActionListener(e -> { accepted[0] = true; d.dispose(); });
      no.addActionListener(e -> d.dispose());
      JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
      south.add(no);
      south.add(go);

      JPanel all = new JPanel(new BorderLayout(0, 6));
      all.add(p, BorderLayout.NORTH);
      JPanel mid = new JPanel(new BorderLayout());
      mid.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
      mid.add(fp, BorderLayout.NORTH);
      all.add(mid, BorderLayout.CENTER);
      all.add(south, BorderLayout.SOUTH);
      d.setContentPane(all);
      d.getRootPane().setDefaultButton(go);
      d.pack();
      d.setLocationRelativeTo(aladin.f);
      d.setVisible(true);
      if (!accepted[0]) return null;

      for (int k = 1; k <= maxK; k++) if (sr[k] != null && sr[k].isSelected()) o.scale = k;
      o.png = png.isSelected();
      o.overlays = ov.isSelected();
      String fn = path.getText().trim();
      if (fn.length() == 0) return null;
      o.file = new File(swapExt(fn, o.png ? "png" : "jpg"));
      return o;
   }

   private static String swapExt(String name, String ext) {
      int i = name.lastIndexOf('.');
      int s = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
      return (i > s ? name.substring(0, i) : name) + "." + ext;
   }

   private static File defaultFile(ViewSimple cur, boolean png) {
      String label = "sky";
      try { if (cur.pref.label != null) label = cur.pref.label; } catch (Exception e) { }
      label = label.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
      if (label.length() > 40) label = label.substring(0, 40);
      if (label.length() == 0) label = "sky";
      String pos = "";
      try {
         Coord c = cur.getCooCentre();
         if (c != null && !Double.isNaN(c.al)) pos = String.format(Locale.US, "_ra%.3f_dec%+.3f", c.al, c.del);
      } catch (Throwable e) { }
      String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
      File dir = captureDir();
      return new File(dir, "astrovia_" + label + pos + "_" + stamp + (png ? ".png" : ".jpg"));
   }

   // --------------------------------------------------------------------------------- render

   /** Renders and writes the picture. Returns the final message, or null if it was cancelled. */
   private static String capture(Aladin aladin, Options o, AtomicBoolean cancel, Status st) throws Exception {
      ViewSimple cur = aladin.view.getCurrentView();
      final int k = o.scale;
      final int W = cur.rv.width * k, H = cur.rv.height * k;
      if ((long)W * H > maxPixels()) throw new Exception("That size does not fit in memory.");

      ViewSimple v = new ViewSimple(aladin, aladin.view, 0, 0, 0);
      Projection oldProj = cur.pref.projd;
      try {
         // Copy the state of the current view straight into the temporary one (ViewMemo.copy() chokes
         // on empty slots when only one view is open).
         new ViewMemoItem(cur).get(v);
         if (v.isFree()) {
            throw new Exception("Could not read the current view.");
         }
         // Same recipe as Save.saveAllViews(): a temporary view of the wanted size...
         v.rv = new Rectangle(0, 0, W, H);
         v.setSize(W, H);
         if (v.pref instanceof PlanBG) v.pref.projd = v.projLocal;
         // ...with the zoom multiplied by k: same field of view, k times more pixels per degree.
         v.setZoomXY(v.zoom * k, v.xzoomView, v.yzoomView);
         v.newView(1);

         st.set("Loading the sky tiles for " + W + " x " + H + " px...");
         BufferedImage img = settle(v, W, H, o.overlays, cancel, st);
         if (img == null) return null;

         st.set("Saving " + o.file.getName() + "...");
         File dir = o.file.getAbsoluteFile().getParentFile();
         if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("Cannot create " + dir);
         aladin.save.writeRendered(img, v, o.file, o.png, 1.0f);
         savePrefs(o); // remember the folder and the options for next time
         return "Saved " + W + " x " + H + " px (" + (o.file.length() / 1024) + " KB):\n" + o.file.getPath();
      } finally {
         cur.pref.projd = oldProj;
         try { v.free(); } catch (Exception e) { }
         aladin.view.repaintAll();
      }
   }

   /**
    * Renders the view until the HiPS tiles have arrived and the picture has stopped changing
    * (two identical renders in a row once the plan reports it is loaded), or a timeout.
    */
   private static BufferedImage settle(ViewSimple v, int W, int H, boolean overlays, AtomicBoolean cancel, Status st)
         throws Exception {
      BufferedImage img = toBuffered(v.getImage(W, H, overlays), W, H);
      if (!(v.pref instanceof PlanBG)) return img; // a plain image needs no tile loading
      PlanBG bg = (PlanBG)v.pref;
      long t0 = System.currentTimeMillis();
      long last = signature(img);
      int stable = 0;
      while (!cancel.get() && System.currentTimeMillis() - t0 < 120000L) {
         Thread.sleep(400);
         img = toBuffered(v.getImage(W, H, overlays), W, H);
         long sig = signature(img);
         boolean loaded = bg.isSync();
         stable = (sig == last && loaded) ? stable + 1 : 0;
         last = sig;
         if (stable >= 3) return img;
         st.set("Loading the sky tiles (" + (loaded ? "refining" : "downloading") + ")... "
               + ((System.currentTimeMillis() - t0) / 1000) + " s");
      }
      return cancel.get() ? null : img; // timeout: save what is there
   }

   private static BufferedImage toBuffered(Image im, int W, int H) {
      if (im instanceof BufferedImage) return (BufferedImage)im;
      BufferedImage b = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
      Graphics g = b.getGraphics();
      g.drawImage(im, 0, 0, null);
      g.dispose();
      return b;
   }

   /** Cheap fingerprint of the picture (a sample of its pixels). */
   private static long signature(BufferedImage b) {
      int w = b.getWidth(), h = b.getHeight();
      long s = 1469598103934665603L;
      int stepX = Math.max(1, w / 97), stepY = Math.max(1, h / 89);
      for (int y = 0; y < h; y += stepY) {
         for (int x = 0; x < w; x += stepX) s = (s ^ b.getRGB(x, y)) * 1099511628211L;
      }
      return s;
   }
}
