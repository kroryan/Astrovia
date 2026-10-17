// Astrovia: image processing window (stretch, background, noise, sharpening, stars, colour, geometry, stacking).
// Uses the processing engine shared with the Android app (cds.astrovia.imgproc). Part of Astrovia, GNU GPL v3.
package cds.aladin;

import cds.astrovia.imgproc.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Window to process the picture you are working on: tools on the right (foldable), undo / redo, save, load back in Astrovia. */
public class ProcessDialog extends JDialog {
   private final Aladin aladin;
   private final List<Img> hist = new ArrayList<Img>();
   private final List<String> log = new ArrayList<String>();
   private int idx = -1;
   private File source;
   private List<Stars.Star> stars = new ArrayList<Stars.Star>();
   private boolean showStars = true;
   private BufferedImage preview;

   private final ViewPanel view = new ViewPanel();
   private final JLabel status = new JLabel(" Open a FITS image to start.");
   private final JProgressBar busy = new JProgressBar();
   private final JComboBox<String> toolBox = new JComboBox<String>();
   private final JPanel cards = new JPanel(new CardLayout());
   private final JComboBox<String> display = new JComboBox<String>(new String[] { "Display: auto stretch", "Display: linear", "Display: asinh" });
   private final JTextArea stepsArea = new JTextArea(3, 20);
   private final JTextArea infoArea = new JTextArea(5, 20);
   private final JButton undo = new JButton("Undo"), redo = new JButton("Redo"), reset = new JButton("Reset");
   private final JPanel side = new JPanel(new BorderLayout(4, 4));
   private JSlider hot, sens;
   private JComboBox<String> bayerBox;

   public ProcessDialog(Window owner, Aladin aladin) {
      super(owner, "Image processing", ModalityType.MODELESS);
      this.aladin = aladin;
      setLayout(new BorderLayout(4, 4));
      // ---- toolbar
      JToolBar tb = new JToolBar();
      tb.setFloatable(false);
      JButton open = new JButton("Open..."), cur = new JButton("Use image of Astrovia"), saveFits = new JButton("Save FITS..."), savePng = new JButton("Save PNG..."), back = new JButton("Load in Astrovia"), tools = new JButton("Tools ▸");
      tb.add(open); tb.add(cur); tb.addSeparator(); tb.add(undo); tb.add(redo); tb.add(reset); tb.addSeparator(); tb.add(saveFits); tb.add(savePng); tb.add(back); tb.addSeparator(); tb.add(display); tb.add(javax.swing.Box.createHorizontalGlue()); tb.add(tools);
      add(tb, BorderLayout.NORTH);
      add(view, BorderLayout.CENTER);
      // ---- side panel with one tool at a time
      buildCards();
      JPanel top = new JPanel(new BorderLayout(4, 4));
      top.add(toolBox, BorderLayout.NORTH);
      top.add(cards, BorderLayout.CENTER);
      side.add(top, BorderLayout.NORTH);
      stepsArea.setEditable(false); stepsArea.setLineWrap(true); stepsArea.setWrapStyleWord(true); infoArea.setEditable(false); infoArea.setLineWrap(true);
      JPanel low = new JPanel(new GridLayout(2, 1, 0, 4));
      low.add(new JScrollPane(infoArea)); low.add(new JScrollPane(stepsArea));
      side.add(low, BorderLayout.CENTER);
      side.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
      side.setPreferredSize(new Dimension(290, 400));
      add(side, BorderLayout.EAST);
      JPanel bottom = new JPanel(new BorderLayout(6, 0));
      bottom.add(status, BorderLayout.CENTER); busy.setIndeterminate(true); busy.setVisible(false); busy.setPreferredSize(new Dimension(120, 14)); bottom.add(busy, BorderLayout.EAST);
      add(bottom, BorderLayout.SOUTH);
      setSize(1100, 720);
      setLocationRelativeTo(owner);
      // ---- actions
      tools.addActionListener(e -> { side.setVisible(!side.isVisible()); tools.setText(side.isVisible() ? "Tools ▸" : "◂ Tools"); revalidate(); });
      toolBox.addActionListener(e -> { if (toolBox.getSelectedItem() != null) ((CardLayout) cards.getLayout()).show(cards, (String) toolBox.getSelectedItem()); });
      display.addActionListener(e -> refreshPreview());
      open.addActionListener(e -> chooseFile());
      cur.addActionListener(e -> useCurrent());
      undo.addActionListener(e -> step(-1)); redo.addActionListener(e -> step(1));
      reset.addActionListener(e -> { if (idx > 0) { Img f = hist.get(0); hist.clear(); log.clear(); hist.add(f); log.add("Original"); idx = 0; stars.clear(); update(true); } });
      saveFits.addActionListener(e -> saveFits());
      savePng.addActionListener(e -> savePng());
      back.addActionListener(e -> loadBack());
      update(false);
   }

   // ------------------------------------------------------------------ tools (cards)
   private static JSlider slider(final JPanel p, String label, int min, int max, int init, final double scale, final String fmt) {
      final JSlider s = new JSlider(min, max, init);
      final JLabel l = new JLabel(label + ": " + String.format(Locale.US, fmt, init * scale));
      final String lab = label;
      s.addChangeListener(e -> l.setText(lab + ": " + String.format(Locale.US, fmt, s.getValue() * scale)));
      p.add(l); p.add(s);
      return s;
   }

   private JPanel card(String name) {
      JPanel p = new JPanel();
      p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
      p.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
      cards.add(p, name);
      return p;
   }

   private static JTextArea hint(String t) {
      JTextArea a = new JTextArea(t); a.setEditable(false); a.setLineWrap(true); a.setWrapStyleWord(true); a.setOpaque(false); a.setFont(a.getFont().deriveFont(11f));
      return a;
   }

   private void buildCards() {
      // Stretch
      JPanel c = card("Stretch");
      final JSlider target = slider(c, "Background brightness", 10, 50, 25, 0.01, "%.2f"), shadow = slider(c, "Black point (sigmas)", -50, 0, -28, 0.1, "%.1f");
      JButton b = new JButton("Auto stretch"); c.add(b);
      b.addActionListener(e -> run("Auto stretch", new Op() { public Img apply(Img i) { return Ops.autoStretch(i, target.getValue() * 0.01, shadow.getValue() * 0.1, true); } }));
      final JSlider str = slider(c, "Asinh strength", 2, 500, 50, 1, "%.0f");
      JButton b2 = new JButton("Asinh stretch"); c.add(b2);
      b2.addActionListener(e -> run("Asinh stretch", new Op() { public Img apply(Img i) { return Ops.asinh(i, str.getValue(), 0); } }));
      c.add(hint("Makes the faint signal visible. It changes the data: keep the original to stack or measure."));
      // Background
      c = card("Background");
      final JSlider grid = slider(c, "Boxes per side", 4, 16, 8, 1, "%.0f"), order = slider(c, "Gradient complexity", 1, 4, 2, 1, "%.0f");
      b = new JButton("Remove gradient"); c.add(b);
      b.addActionListener(e -> run("Background removal", new Op() { public Img apply(Img i) { return Ops.removeBackground(i, grid.getValue(), order.getValue()); } }));
      c.add(hint("Models the sky background away from the stars and subtracts it (light pollution, vignetting)."));
      // Noise
      c = card("Noise");
      final JComboBox<String> nm = new JComboBox<String>(new String[] { "Median 3x3", "Median 5x5", "Gaussian blur" }); c.add(nm);
      final JSlider ns = slider(c, "Blur radius (pixels)", 5, 40, 12, 0.1, "%.1f");
      b = new JButton("Apply"); c.add(b);
      b.addActionListener(e -> { final int m = nm.getSelectedIndex(); run((String) nm.getSelectedItem(), new Op() { public Img apply(Img i) { return m == 0 ? Ops.median(i, 1) : m == 1 ? Ops.median(i, 2) : Ops.gaussianBlur(i, ns.getValue() * 0.1); } }); });
      // Sharpen
      c = card("Sharpen");
      final JComboBox<String> sm = new JComboBox<String>(new String[] { "Unsharp mask", "Deconvolution (Richardson-Lucy)" }); c.add(sm);
      final JSlider sr = slider(c, "Radius / star sigma (pixels)", 8, 50, 15, 0.1, "%.1f"), sa = slider(c, "Amount", 2, 30, 10, 0.1, "%.1f"), si = slider(c, "Iterations (deconvolution)", 5, 40, 15, 1, "%.0f");
      b = new JButton("Apply"); c.add(b);
      b.addActionListener(e -> { final int m = sm.getSelectedIndex(); run((String) sm.getSelectedItem(), new Op() { public Img apply(Img i) { return m == 0 ? Ops.unsharp(i, sr.getValue() * 0.1, sa.getValue() * 0.1) : Ops.deconvolve(i, sr.getValue() * 0.1, si.getValue()); } }); });
      c.add(hint("Deconvolution works best on linear data. Measure the star FWHM in the Stars tool: sigma = FWHM / 2.35."));
      // Hot pixels
      c = card("Hot pixels");
      hot = slider(c, "Sensitivity (sigmas)", 3, 20, 8, 1, "%.0f");
      final JCheckBox cold = new JCheckBox("Also fix dead (cold) pixels"); c.add(cold);
      b = new JButton("Fix"); c.add(b);
      b.addActionListener(e -> run("Hot pixels", new Op() { public Img apply(Img i) { return Ops.cosmetic(i, hot.getValue(), cold.isSelected() ? hot.getValue() : 0, null); } }));
      // Stars
      c = card("Stars");
      sens = slider(c, "Sensitivity (sigmas)", 3, 15, 6, 1, "%.0f");
      b = new JButton("Measure stars and noise"); c.add(b);
      final JCheckBox mark = new JCheckBox("Mark the stars", true); c.add(mark);
      mark.addActionListener(e -> { showStars = mark.isSelected(); view.repaint(); });
      b.addActionListener(e -> measure());
      // Colour
      c = card("Colour");
      JButton wb = new JButton("White balance"); c.add(wb);
      wb.addActionListener(e -> run("White balance", new Op() { public Img apply(Img i) { return Ops.whiteBalance(i); } }));
      final JSlider sc = slider(c, "Green cast removal", 1, 10, 10, 0.1, "%.1f");
      b = new JButton("Remove green cast"); c.add(b);
      b.addActionListener(e -> run("Green cast removal", new Op() { public Img apply(Img i) { return Ops.scnr(i, sc.getValue() * 0.1); } }));
      final JSlider sat = slider(c, "Saturation", -10, 10, 3, 0.1, "%+.1f");
      b = new JButton("Apply saturation"); c.add(b);
      b.addActionListener(e -> run("Saturation", new Op() { public Img apply(Img i) { return Ops.saturation(i, sat.getValue() * 0.1); } }));
      // Debayer
      c = card("Debayer");
      bayerBox = new JComboBox<String>(new String[] { "RGGB", "BGGR", "GRBG", "GBRG" }); c.add(bayerBox);
      b = new JButton("Debayer"); c.add(b);
      b.addActionListener(e -> { final String pat = (String) bayerBox.getSelectedItem(); run("Debayer " + pat, new Op() { public Img apply(Img i) { return Ops.debayer(i, pat); } }); });
      c.add(hint("For a raw picture from a colour camera: turns the mosaic into a colour picture."));
      // Geometry
      c = card("Geometry");
      JPanel g = new JPanel(new GridLayout(0, 2, 4, 4));
      g.add(op("Rotate left", new Op() { public Img apply(Img i) { return Ops.rotate90(i, false); } }));
      g.add(op("Rotate right", new Op() { public Img apply(Img i) { return Ops.rotate90(i, true); } }));
      g.add(op("Flip horizontally", new Op() { public Img apply(Img i) { return Ops.flip(i, true); } }));
      g.add(op("Flip vertically", new Op() { public Img apply(Img i) { return Ops.flip(i, false); } }));
      g.add(op("Bin 2x2", new Op() { public Img apply(Img i) { return Ops.bin(i, 2); } }));
      g.add(op("Bin 3x3", new Op() { public Img apply(Img i) { return Ops.bin(i, 3); } }));
      g.add(op("Invert", new Op() { public Img apply(Img i) { return Ops.invert(i); } }));
      JButton crop = new JButton("Crop to view"); g.add(crop);
      crop.addActionListener(e -> { final int[] r = view.visibleRect(); if (r != null) run("Crop to view", new Op() { public Img apply(Img i) { return Ops.crop(i, r[0], i.h - r[3], r[2] - r[0], r[3] - r[1]); } }); });
      c.add(g);
      // Stack
      c = card("Stack");
      c.add(hint("Combine several exposures of the same field to reduce noise: lights, with optional darks, flats and bias. Pictures are aligned on the stars."));
      b = new JButton("Stack pictures..."); c.add(b);
      b.addActionListener(e -> new StackWizard().setVisible(true));
      refreshTools();
   }

   private interface Op { Img apply(Img in); }

   private JButton op(String label, final Op o) { JButton b = new JButton(label); b.addActionListener(e -> run(label, o)); return b; }

   /** Only the tools that make sense for the open picture. */
   private void refreshTools() {
      Img im = current();
      Object keep = toolBox.getSelectedItem();
      toolBox.removeAllItems();
      boolean color = im != null && im.isColor(), bayer = im != null && !color && im.bayerPattern() != null;
      for (String t : new String[] { "Stretch", "Background", "Noise", "Sharpen", "Hot pixels", "Stars", "Color", "Debayer", "Geometry", "Stack" }) {
         String key = t.equals("Color") ? "Colour" : t;
         if (key.equals("Colour") && !color) continue;
         if (key.equals("Debayer") && !bayer) continue;
         toolBox.addItem(key);
      }
      if (bayer && bayerBox != null) bayerBox.setSelectedItem(im.bayerPattern());
      if (keep != null) toolBox.setSelectedItem(keep);
      else if (bayer) toolBox.setSelectedItem("Debayer");
      if (toolBox.getSelectedItem() != null) ((CardLayout) cards.getLayout()).show(cards, (String) toolBox.getSelectedItem());
   }

   // ------------------------------------------------------------------ state
   private Img current() { return idx >= 0 ? hist.get(idx) : null; }

   private void run(final String label, final Op op) {
      final Img in = current();
      if (in == null) { status.setText(" Open an image first."); return; }
      setBusy(true, label + " ...");
      new SwingWorker<Img, Void>() {
         protected Img doInBackground() { return op.apply(in); }
         protected void done() {
            try {
               Img out = get();
               while (hist.size() > idx + 1) { hist.remove(hist.size() - 1); log.remove(log.size() - 1); }
               hist.add(out); log.add(label); idx++;
               while (hist.size() > 6) { hist.remove(0); log.remove(0); idx--; }
               stars.clear();
               update(label.equals("Auto stretch") || label.equals("Asinh stretch") || label.startsWith("Debayer") || out.w != in.w || out.h != in.h);
               status.setText(" " + label + " done");
            } catch (Exception ex) { Throwable t = ex.getCause() != null ? ex.getCause() : ex; status.setText(" " + label + " failed: " + (t instanceof OutOfMemoryError ? "not enough memory, try binning or cropping first" : t.getMessage())); }
            finally { setBusy(false, null); }
         }
      }.execute();
   }

   private void step(int d) {
      int i = idx + d;
      if (i < 0 || i >= hist.size()) return;
      idx = i; stars.clear(); update(false);
   }

   private void setBusy(boolean b, String msg) { busy.setVisible(b); if (msg != null) status.setText(" " + msg); }

   private void measure() {
      final Img im = current();
      if (im == null) return;
      final double s = sens.getValue();
      setBusy(true, "Measuring ...");
      new SwingWorker<Object[], Void>() {
         protected Object[] doInBackground() {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < im.c; k++) sb.append(im.c == 3 ? new String[] { "R: ", "G: ", "B: " }[k] : "Image: ").append(Img.stats(im.p[k], im.w, im.h)).append('\n');
            Stars.Report r = Stars.detect(im.luma(), im.w, im.h, s, 400);
            sb.append(r);
            return new Object[] { sb.toString(), r.stars };
         }
         @SuppressWarnings("unchecked")
         protected void done() {
            try { Object[] o = get(); infoArea.setText((String) o[0]); stars = (List<Stars.Star>) o[1]; view.repaint(); status.setText(" " + stars.size() + " stars"); }
            catch (Exception ex) { status.setText(" Measure failed: " + ex.getMessage()); }
            finally { setBusy(false, null); }
         }
      }.execute();
   }

   private void update(boolean fit) {
      undo.setEnabled(idx > 0); redo.setEnabled(idx >= 0 && idx < hist.size() - 1); reset.setEnabled(idx > 0);
      StringBuilder sb = new StringBuilder();
      for (int i = 1; i <= idx; i++) sb.append(i > 1 ? " → " : "").append(log.get(i));
      stepsArea.setText(idx > 0 ? "Steps: " + sb : "No processing yet");
      refreshTools();
      refreshPreview();
      if (fit) view.fit();
      Img im = current();
      if (im != null) setTitle("Image processing - " + (source != null ? source.getName() : "stack") + "  (" + im.w + " x " + im.h + (im.isColor() ? ", colour" : "") + ")");
   }

   private void refreshPreview() {
      Img im = current();
      if (im == null) { preview = null; view.repaint(); return; }
      Img d;
      int mode = display.getSelectedIndex();
      if (mode == 0) d = Ops.autoStretch(im, 0.25, -2.8, true);
      else if (mode == 2) d = Ops.asinh(im, 50, 0);
      else {
         d = im.like(im.c);
         for (int k = 0; k < im.c; k++) {
            float[] sample = Img.sampleOf(im.p[k], 50000); Arrays.sort(sample);
            double lo = sample.length == 0 ? 0 : sample[(int) (sample.length * 0.01)], hi = sample.length == 0 ? 1 : sample[Math.min(sample.length - 1, (int) (sample.length * 0.995))];
            double rg = Math.max(1e-30, hi - lo);
            for (int i = 0; i < im.p[k].length; i++) d.p[k][i] = (float) Math.max(0, Math.min(1, (im.p[k][i] - lo) / rg));
         }
      }
      BufferedImage b = new BufferedImage(im.w, im.h, BufferedImage.TYPE_INT_RGB);
      int[] row = new int[im.w];
      for (int y = 0; y < im.h; y++) {
         int src = (im.h - 1 - y) * im.w;
         for (int x = 0; x < im.w; x++) {
            int r = (int) (d.p[0][src + x] * 255 + 0.5f), g = d.c == 3 ? (int) (d.p[1][src + x] * 255 + 0.5f) : r, bl = d.c == 3 ? (int) (d.p[2][src + x] * 255 + 0.5f) : r;
            row[x] = (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
         }
         b.setRGB(0, y, im.w, 1, row, 0, im.w);
      }
      preview = b;
      view.repaint();
   }

   private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

   // ------------------------------------------------------------------ files
   private void load(File f) {
      setBusy(true, "Reading " + f.getName() + " ...");
      new SwingWorker<Img, Void>() {
         protected Img doInBackground() throws Exception {
            Img im = FitsIO.read(f);
            if ((long) im.w * im.h > 40000000L) im = Ops.bin(im, 2);
            return im;
         }
         protected void done() {
            try {
               Img im = get();
               hist.clear(); log.clear(); hist.add(im); log.add("Original"); idx = 0; source = f; stars.clear();
               update(true);
               status.setText(" " + f.getName() + ": " + im.w + " x " + im.h + (im.isColor() ? " colour" : " mono"));
            } catch (Exception ex) { Throwable t = ex.getCause() != null ? ex.getCause() : ex; status.setText(" Could not open: " + t.getMessage()); }
            finally { setBusy(false, null); }
         }
      }.execute();
   }

   private void chooseFile() {
      JFileChooser fc = new JFileChooser(source != null ? source.getParentFile() : null);
      fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("FITS images", "fits", "fit", "fts"));
      if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) load(fc.getSelectedFile());
   }

   private void useCurrent() {
      try {
         Plan p = aladin.calque.getFirstSelectedPlan();
         String u = p == null ? null : p.getUrl();
         if (u != null) {
            File f = u.startsWith("file:") ? new File(new java.net.URI(u.replace(" ", "%20"))) : new File(u);
            if (f.isFile()) { load(f); return; }
         }
      } catch (Exception ex) { /* fall through */ }
      status.setText(" The selected image is not a local FITS file: use Open...");
   }

   private File outDir() { File d = new File(System.getProperty("user.home"), ".astrovia" + File.separator + "processed"); d.mkdirs(); return d; }

   private void saveFits() {
      Img im = current();
      if (im == null) return;
      JFileChooser fc = new JFileChooser(source != null ? source.getParentFile() : null);
      fc.setSelectedFile(new File(source != null ? source.getName().replaceAll("\\.[^.]*$", "") + "_processed.fits" : "stack.fits"));
      if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
      try { FitsIO.write(im, fc.getSelectedFile(), steps()); status.setText(" Saved " + fc.getSelectedFile().getName()); } catch (Exception ex) { status.setText(" Could not save: " + ex.getMessage()); }
   }

   private String[] steps() { List<String> s = new ArrayList<String>(); for (int i = 1; i <= idx; i++) s.add(log.get(i)); return s.toArray(new String[0]); }

   private void savePng() {
      if (preview == null) return;
      JFileChooser fc = new JFileChooser(source != null ? source.getParentFile() : null);
      fc.setSelectedFile(new File("picture.png"));
      if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
      try { ImageIO.write(preview, "png", fc.getSelectedFile()); status.setText(" Saved " + fc.getSelectedFile().getName()); } catch (Exception ex) { status.setText(" Could not save: " + ex.getMessage()); }
   }

   private void loadBack() {
      Img im = current();
      if (im == null) return;
      try {
         File f = new File(outDir(), (source != null ? source.getName().replaceAll("\\.[^.]*$", "") : "stack") + "_processed.fits");
         FitsIO.write(im, f, steps());
         aladin.execAsyncCommand("load \"" + f.getAbsolutePath().replace("\\", "/") + "\"");
         status.setText(" Loaded in Astrovia: " + f.getName());
      } catch (Exception ex) { status.setText(" Could not load: " + ex.getMessage()); }
   }

   // ------------------------------------------------------------------ view
   private final class ViewPanel extends JPanel {
      double scale = 1, ox, oy; boolean placed; int dragX, dragY;
      ViewPanel() {
         setBackground(Color.BLACK);
         addMouseWheelListener(new MouseWheelListener() { public void mouseWheelMoved(MouseWheelEvent e) {
            double ns = Math.max(0.05, Math.min(60, scale * (e.getWheelRotation() < 0 ? 1.25 : 0.8))), k = ns / scale;
            ox = e.getX() - (e.getX() - ox) * k; oy = e.getY() - (e.getY() - oy) * k; scale = ns; repaint();
         } });
         addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragX = e.getX(); dragY = e.getY(); }
            public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) fit(); }
         });
         addMouseMotionListener(new MouseMotionAdapter() { public void mouseDragged(MouseEvent e) { ox += e.getX() - dragX; oy += e.getY() - dragY; dragX = e.getX(); dragY = e.getY(); repaint(); } });
      }
      void fit() {
         if (preview == null || getWidth() == 0) { placed = false; return; }
         scale = Math.min(getWidth() / (double) preview.getWidth(), getHeight() / (double) preview.getHeight());
         ox = (getWidth() - preview.getWidth() * scale) / 2; oy = (getHeight() - preview.getHeight() * scale) / 2; placed = true; repaint();
      }
      /** The part of the picture on screen, in FITS pixels: {x0, y0(top), x1, y1(bottom)} measured from the top. */
      int[] visibleRect() {
         if (preview == null) return null;
         int x0 = (int) Math.max(0, -ox / scale), y0 = (int) Math.max(0, -oy / scale), x1 = (int) Math.min(preview.getWidth(), (getWidth() - ox) / scale), y1 = (int) Math.min(preview.getHeight(), (getHeight() - oy) / scale);
         return x1 > x0 && y1 > y0 ? new int[] { x0, y0, x1, y1 } : null;
      }
      protected void paintComponent(Graphics g0) {
         super.paintComponent(g0);
         Graphics2D g = (Graphics2D) g0;
         if (preview == null) { g.setColor(Color.GRAY); g.drawString("Open a FITS image (Open... or Use image of Astrovia)", 20, 30); return; }
         if (!placed) fit();
         g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale > 3 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
         g.drawImage(preview, (int) ox, (int) oy, (int) (preview.getWidth() * scale), (int) (preview.getHeight() * scale), null);
         if (showStars) {
            g.setColor(new Color(34, 211, 238)); g.setStroke(new BasicStroke(1.5f));
            for (Stars.Star s : stars) { double cx = ox + (s.x + 0.5) * scale, cy = oy + (preview.getHeight() - 1 - s.y + 0.5) * scale, r = Math.max(5, Math.min(40, s.fwhm * 1.6 * scale)); g.drawOval((int) (cx - r), (int) (cy - r), (int) (2 * r), (int) (2 * r)); }
         }
      }
   }

   // ------------------------------------------------------------------ stacking wizard
   private final class StackWizard extends JDialog {
      private final DefaultListModel<File> lights = new DefaultListModel<File>(), darks = new DefaultListModel<File>(), flats = new DefaultListModel<File>(), bias = new DefaultListModel<File>();
      private volatile boolean cancel;

      StackWizard() {
         super(ProcessDialog.this, "Stack pictures", ModalityType.MODELESS);
         if (source != null) lights.addElement(source);
         setLayout(new BorderLayout(6, 6));
         JPanel lists = new JPanel(new GridLayout(2, 2, 6, 6));
         lists.add(group("Lights (at least 2)", lights)); lists.add(group("Darks (optional)", darks)); lists.add(group("Flats (optional)", flats)); lists.add(group("Bias (optional)", bias));
         add(lists, BorderLayout.CENTER);
         final JComboBox<String> mode = new JComboBox<String>(new String[] { "Sigma clipping", "Median", "Average" });
         final JCheckBox reg = new JCheckBox("Align on the stars", true), deb = new JCheckBox("Debayer (colour camera)", current() != null && current().bayerPattern() != null);
         JButton go = new JButton("Stack"), close = new JButton("Close");
         JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
         bar.add(new JLabel("Combine with:")); bar.add(mode); bar.add(reg); bar.add(deb); bar.add(go); bar.add(close);
         add(bar, BorderLayout.SOUTH);
         close.addActionListener(e -> dispose());
         go.addActionListener(e -> {
            if (lights.size() < 2) { JOptionPane.showMessageDialog(this, "Add at least 2 light frames."); return; }
            run(mode.getSelectedIndex() == 0 ? Stacker.SIGMA_CLIP : mode.getSelectedIndex() == 1 ? Stacker.MEDIAN : Stacker.AVERAGE, reg.isSelected(), deb.isSelected());
         });
         setSize(760, 480); setLocationRelativeTo(ProcessDialog.this);
      }

      private JPanel group(String title, final DefaultListModel<File> m) {
         JPanel p = new JPanel(new BorderLayout(4, 4));
         p.setBorder(BorderFactory.createTitledBorder(title));
         final JList<File> l = new JList<File>(m);
         p.add(new JScrollPane(l), BorderLayout.CENTER);
         JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
         JButton add = new JButton("Add..."), rm = new JButton("Remove");
         b.add(add); b.add(rm); p.add(b, BorderLayout.SOUTH);
         add.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(source != null ? source.getParentFile() : null); fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("FITS images", "fits", "fit", "fts"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) for (File f : fc.getSelectedFiles()) if (!m.contains(f)) m.addElement(f);
         });
         rm.addActionListener(e -> { for (File f : l.getSelectedValuesList()) m.removeElement(f); });
         return p;
      }

      private List<File> list(DefaultListModel<File> m) { List<File> r = new ArrayList<File>(); for (int i = 0; i < m.size(); i++) r.add(m.get(i)); return r; }

      private void run(final int mode, final boolean register, final boolean debayer) {
         final JDialog d = new JDialog(this, "Stacking", ModalityType.MODELESS);
         final JProgressBar pb = new JProgressBar(0, 1000); pb.setStringPainted(true);
         final JButton stop = new JButton("Cancel");
         d.setLayout(new BorderLayout(6, 6)); d.add(pb, BorderLayout.CENTER); d.add(stop, BorderLayout.EAST); d.setSize(420, 80); d.setLocationRelativeTo(this); d.setVisible(true);
         stop.addActionListener(e -> cancel = true);
         cancel = false;
         final Stacker.Progress prog = new Stacker.Progress() {
            public void step(final String m, final double f) { SwingUtilities.invokeLater(new Runnable() { public void run() { pb.setValue((int) (f * 1000)); pb.setString(m); } }); }
            public boolean cancelled() { return cancel; }
         };
         new SwingWorker<Stacker.Result, Void>() {
            protected Stacker.Result doInBackground() throws Exception {
               Stacker.Params p = new Stacker.Params();
               p.mode = mode; p.register = register; p.debayer = debayer;
               File tmp = java.nio.file.Files.createTempDirectory("astrovia_stack").toFile(); p.tempDir = tmp;
               try {
                  if (bias.size() > 0) { File f = new File(tmp, "bias.fits"); FitsIO.write(Stacker.master(list(bias), p, prog), f); p.bias = f; }
                  if (darks.size() > 0) { File f = new File(tmp, "dark.fits"); FitsIO.write(Stacker.master(list(darks), p, prog), f); p.dark = f; }
                  if (flats.size() > 0) { File f = new File(tmp, "flat.fits"); FitsIO.write(Stacker.master(list(flats), p, prog), f); p.flat = f; }
                  return Stacker.stack(list(lights), p, prog);
               } finally { File[] fs = tmp.listFiles(); if (fs != null) for (File f : fs) f.delete(); tmp.delete(); }
            }
            protected void done() {
               d.dispose();
               try {
                  Stacker.Result r = get();
                  hist.clear(); log.clear(); hist.add(r.image); log.add("Stack of " + r.used + " frames"); idx = 0; stars.clear(); source = lights.get(0);
                  ProcessDialog.this.update(true);
                  status.setText(" Stacked " + r.used + " frames" + (r.rejected > 0 ? ", " + r.rejected + " skipped" : ""));
                  StackWizard.this.dispose();
               } catch (Exception ex) { Throwable t = ex.getCause() != null ? ex.getCause() : ex; JOptionPane.showMessageDialog(StackWizard.this, "Stacking: " + t.getMessage()); }
            }
         }.execute();
      }
   }

   private static ProcessDialog instance;
   static void open(Aladin aladin) {
      if (instance == null) instance = new ProcessDialog(aladin.f != null ? aladin.f : null, aladin);
      instance.setVisible(true);
      instance.toFront();
   }
}
