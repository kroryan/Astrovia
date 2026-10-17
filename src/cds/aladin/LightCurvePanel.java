// Astrovia: light-curve plot (time series) with wheel zoom and drag pan. Part of Astrovia, GNU GPL v3.
package cds.aladin;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.swing.*;

final class LightCurvePanel extends JPanel {
   private final double[] t, y, e;
   private final String title, xLabel, yLabel;
   private final double t0, t1;
   private double lo, hi;
   private int dragX = -1;
   private static final int L = 64, R = 12, T = 30, B = 34;

   LightCurvePanel(String title, String xLabel, String yLabel, double[] t, double[] y, double[] e) {
      this.title = title; this.xLabel = xLabel; this.yLabel = yLabel; this.t = t; this.y = y; this.e = e;
      double a = Double.MAX_VALUE, b = -Double.MAX_VALUE;
      for (double v : t) { if (v < a) a = v; if (v > b) b = v; }
      t0 = a; t1 = b; lo = a; hi = b;
      setBackground(new Color(0x0B0F1A));
      setPreferredSize(new Dimension(820, 460));
      addMouseWheelListener(new MouseWheelListener() { public void mouseWheelMoved(MouseWheelEvent ev) {
         double w = Math.max(1, getWidth() - L - R), fx = Math.max(0, Math.min(1, (ev.getX() - L) / w));
         double span = hi - lo, ns = Math.max((t1 - t0) / 5000.0, Math.min(t1 - t0, span * (ev.getWheelRotation() < 0 ? 0.8 : 1.25)));
         double nl = lo + (span - ns) * fx; nl = Math.max(t0, Math.min(Math.max(t0, t1 - ns), nl));
         lo = nl; hi = nl + ns; repaint();
      } });
      addMouseListener(new MouseAdapter() {
         public void mousePressed(MouseEvent ev) { dragX = ev.getX(); }
         public void mouseClicked(MouseEvent ev) { if (ev.getClickCount() == 2) { lo = t0; hi = t1; repaint(); } }
      });
      addMouseMotionListener(new MouseMotionAdapter() { public void mouseDragged(MouseEvent ev) {
         if (dragX < 0) return;
         double w = Math.max(1, getWidth() - L - R), span = hi - lo, d = (ev.getX() - dragX) / w * span;
         double nl = Math.max(t0, Math.min(Math.max(t0, t1 - span), lo - d));
         lo = nl; hi = nl + span; dragX = ev.getX(); repaint();
      } });
   }

   /** "Nice" tick values for an axis. */
   static double[] ticks(double a, double b) {
      if (!(b > a)) return new double[] { a };
      double raw = (b - a) / 5, mag = Math.pow(10, Math.floor(Math.log10(raw))), step = mag;
      for (double m : new double[] { 1, 2, 5, 10 }) { step = m * mag; if (step >= raw) break; }
      List<Double> l = new ArrayList<Double>();
      for (double v = Math.ceil(a / step) * step; v <= b + step * 1e-9; v += step) l.add(v);
      double[] r = new double[l.size() + 1];
      for (int i = 0; i < l.size(); i++) r[i] = l.get(i);
      r[l.size()] = step;
      return r;
   }

   private static String fmt(double v, double step) {
      if (Math.abs(v) >= 1e5 || (v != 0 && Math.abs(v) < 1e-3)) return String.format(Locale.US, "%.2e", v);
      int dec = (int) Math.max(0, Math.min(6, -Math.floor(Math.log10(step))));
      return String.format(Locale.US, "%." + dec + "f", v);
   }

   protected void paintComponent(Graphics g0) {
      super.paintComponent(g0);
      Graphics2D g = (Graphics2D) g0;
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int w = getWidth() - L - R, h = getHeight() - T - B;
      g.setColor(Color.WHITE);
      g.drawString(title + "   (" + t.length + " points; wheel = zoom, drag = pan, double-click = reset)", L, 18);
      double fl = Double.MAX_VALUE, fh = -Double.MAX_VALUE;
      for (int i = 0; i < t.length; i++) if (t[i] >= lo && t[i] <= hi) { fl = Math.min(fl, y[i]); fh = Math.max(fh, y[i]); }
      if (fl > fh) return;
      if (fh <= fl) { fl -= 1; fh += 1; }
      double pad = (fh - fl) * 0.06; fl -= pad; fh += pad;
      double[] yt = ticks(fl, fh), xt = ticks(lo, hi);
      double ys = yt[yt.length - 1], xs = xt[xt.length - 1];
      g.setFont(g.getFont().deriveFont(11f));
      for (int i = 0; i < yt.length - 1; i++) {
         int py = (int) (T + h - (yt[i] - fl) / (fh - fl) * h);
         g.setColor(new Color(255, 255, 255, 34)); g.draw(new Line2D.Double(L, py, L + w, py));
         g.setColor(Color.LIGHT_GRAY); String s = fmt(yt[i], ys); g.drawString(s, L - 6 - g.getFontMetrics().stringWidth(s), py + 4);
      }
      for (int i = 0; i < xt.length - 1; i++) {
         int px = (int) (L + (xt[i] - lo) / (hi - lo) * w);
         g.setColor(new Color(255, 255, 255, 34)); g.draw(new Line2D.Double(px, T, px, T + h));
         g.setColor(Color.LIGHT_GRAY); String s = fmt(xt[i], xs); g.drawString(s, px - g.getFontMetrics().stringWidth(s) / 2, T + h + 15);
      }
      g.setColor(Color.GRAY); g.drawRect(L, T, w, h);
      g.setColor(Color.LIGHT_GRAY);
      g.drawString(xLabel, L + w / 2 - g.getFontMetrics().stringWidth(xLabel) / 2, getHeight() - 6);
      g.drawString(yLabel, 6, T + 12);
      int n = 0; for (double v : t) if (v >= lo && v <= hi) n++;
      int stride = Math.max(1, n / 8000), k = 0;
      for (int i = 0; i < t.length; i++) {
         if (t[i] < lo || t[i] > hi || (k++ % stride) != 0) continue;
         int px = (int) (L + (t[i] - lo) / (hi - lo) * w), py = (int) (T + h - (y[i] - fl) / (fh - fl) * h);
         if (e != null && n < 1500) { g.setColor(new Color(56, 189, 248, 90)); int a = (int) (T + h - (y[i] - e[i] - fl) / (fh - fl) * h), b = (int) (T + h - (y[i] + e[i] - fl) / (fh - fl) * h); g.drawLine(px, a, px, b); }
         g.setColor(new Color(0x38BDF8)); g.fillOval(px - 1, py - 1, 3, 3);
      }
   }

   private static final String[] TIME = { "TIME", "BTJD", "BJD", "HJD", "MJD", "JD", "TSTART" };
   private static final String[] FLUX = { "PDCSAP_FLUX", "SAP_FLUX", "FLUX", "KSPSAP_FLUX", "FLUX_CORR", "MAG", "RATE", "COUNTS" };

   private static String find(Map<String, double[]> d, String[] names) {
      for (String n : names) for (String c : d.keySet()) if (c.equalsIgnoreCase(n)) return c;
      return null;
   }

   /** Opens a window with the light curve of a FITS file (first table with time and flux columns). */
   static void show(Window owner, File f, String name) throws java.io.IOException {
      for (MiniFits.Table tb : MiniFits.read(f, 400000)) {
         String tc = find(tb.data, TIME), fc = find(tb.data, FLUX);
         if (tc == null || fc == null || tc.equalsIgnoreCase(fc)) continue;
         String ec = null, qc = null;
         for (String c : tb.data.keySet()) { if (c.equalsIgnoreCase(fc + "_ERR") || c.equalsIgnoreCase("FLUX_ERR")) ec = c; if (c.equalsIgnoreCase("QUALITY")) qc = c; }
         double[] t = tb.data.get(tc), y = tb.data.get(fc), e = ec == null ? null : tb.data.get(ec), q = qc == null ? null : tb.data.get(qc);
         int n = 0; boolean[] keep = new boolean[t.length];
         for (int i = 0; i < t.length; i++) { keep[i] = !Double.isNaN(t[i]) && !Double.isNaN(y[i]) && !Double.isInfinite(y[i]) && (q == null || q[i] == 0); if (keep[i]) n++; }
         if (n < 2) throw new java.io.IOException("The light curve has fewer than 2 valid points");
         double[] tt = new double[n], yy = new double[n], ee = e == null ? null : new double[n];
         int k = 0;
         for (int i = 0; i < t.length; i++) if (keep[i]) { tt[k] = t[i]; yy[k] = y[i]; if (ee != null) ee[k] = e[i]; k++; }
         String xl = tc.equalsIgnoreCase("TIME") && !Double.isNaN(tb.bjdRef) ? String.format(Locale.US, "BJD - %.1f (days)", tb.bjdRef) : tc;
         JDialog d = new JDialog(owner, "Light curve - " + (tb.object != null ? tb.object : name), Dialog.ModalityType.MODELESS);
         d.add(new LightCurvePanel(tb.object != null ? tb.object : name, xl, fc, tt, yy, ee));
         d.pack(); d.setLocationRelativeTo(owner); d.setVisible(true);
         return;
      }
      throw new java.io.IOException("This file has no light curve (a table with time and flux columns)");
   }
}
