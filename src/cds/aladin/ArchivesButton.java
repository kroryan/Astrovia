// Astrovia: top-right button that opens the Archives window (multi-mission search).
// Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;
import cds.tools.Util;

/** Telescope-like "search the sky archives" button, same look as the capture button. */
final class ArchivesButton extends JComponent implements MouseListener {
   private final Aladin aladin;
   private boolean hover = false;

   ArchivesButton(Aladin aladin) {
      this.aladin = aladin;
      addMouseListener(this);
      Util.toolTip(this, aladin.chaine.getString("TIPARCHIVES"));
      setPreferredSize(new Dimension(40, 40));
      setMinimumSize(new Dimension(40, 40));
   }

   public void paintComponent(Graphics g0) {
      Graphics2D g = (Graphics2D) g0;
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
      g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      // magnifier over a small globe
      g.draw(new Ellipse2D.Float(7, 7, 20, 20));
      g.draw(new Ellipse2D.Float(12, 7, 10, 20));
      g.draw(new Line2D.Float(7, 17, 27, 17));
      g.draw(new Line2D.Float(23, 23, w - 7, h - 7));
   }

   public void mouseClicked(MouseEvent e) { ArchivesDialog.open(aladin); }
   public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
   public void mouseExited(MouseEvent e) { hover = false; repaint(); }
   public void mousePressed(MouseEvent e) { }
   public void mouseReleased(MouseEvent e) { }
}
