// Astrovia: controls of the "Available data" panel for offline collections.
// Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.File;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import cds.tools.Util;

/**
 * Two controls under the collections tree:
 * <ul>
 *   <li><b>Source</b>: All collections / Online only / Offline only, to choose between the CDS servers and
 *       the collections kept on this computer with one click;</li>
 *   <li><b>Offline collections...</b>: add a HiPS folder or a catalogue file, download new ones, manage
 *       the library and switch "prefer offline copies".</li>
 * </ul>
 */
final class OfflineBar extends JPanel {
   private final Aladin aladin;

   OfflineBar(final Aladin aladin, Color bg) {
      this.aladin = aladin;
      setLayout(new BorderLayout(0, 3));
      setBackground(bg);
      setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 6));

      // ---- Source: All / Online / Offline
      JLabel label = new JLabel(aladin.chaine.getString("OFFSRC"));
      label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
      label.setForeground(Aladin.COLOR_LABEL);
      final JComboBox<String> box = new JComboBox<String>(new String[] { "All collections", "Online only", "Offline only" });
      box.setSelectedIndex(OfflineCollections.getSourceMode());
      Util.toolTip(box, aladin.chaine.getString("OFFSRCTIP"), true);
      Util.toolTip(label, aladin.chaine.getString("OFFSRCTIP"), true);
      box.addActionListener(e -> {
         final int m = box.getSelectedIndex();
         SwingUtilities.invokeLater(new Runnable() { public void run() { aladin.directory.applySourceMode(m); } });
      });
      JPanel row1 = new JPanel(new BorderLayout(6, 0));
      row1.setBackground(bg);
      row1.add(label, BorderLayout.WEST);
      row1.add(box, BorderLayout.CENTER);

      // ---- Offline collections menu
      final JButton plus = new JButton(aladin.chaine.getString("OFFPLUS") );
      Util.toolTip(plus, aladin.chaine.getString("OFFPLUSTIP"), true);
      plus.addActionListener(e -> menu().show(plus, 0, plus.getHeight()));

      add(row1, BorderLayout.NORTH);
      add(plus, BorderLayout.CENTER);
   }

   private Window window() { return SwingUtilities.getWindowAncestor(this); }

   private JPopupMenu menu() {
      JPopupMenu m = new JPopupMenu();
      JMenuItem addHips = new JMenuItem("Add a HiPS folder...");
      addHips.setToolTipText("A folder that holds a HiPS (it has a 'properties' file), or a folder containing several of them");
      addHips.addActionListener(e -> chooseHipsFolder());
      JMenuItem addCat = new JMenuItem("Add a catalogue file (VOTable, CSV, FITS table)...");
      addCat.addActionListener(e -> chooseCatalog());
      JMenuItem dlHips = new JMenuItem("Download a HiPS for offline use...");
      dlHips.addActionListener(e -> new HiPSDownloaderDialog(window(), aladin).setVisible(true));
      JMenuItem dlCat = new JMenuItem("Download a catalogue (VizieR / Simbad)...");
      dlCat.addActionListener(e -> new CatalogDownloaderDialog(window(), aladin).setVisible(true));
      JMenuItem manage = new JMenuItem("Manage offline collections...");
      manage.addActionListener(e -> manage());
      final JCheckBoxMenuItem prefer = new JCheckBoxMenuItem("Prefer offline copies of online collections", OfflineCollections.isPreferOffline());
      prefer.setToolTipText("When a collection you open also has an offline copy here, open the copy (no network needed)");
      prefer.addActionListener(e -> OfflineCollections.setPreferOffline(prefer.isSelected()));
      m.add(addHips);
      m.add(addCat);
      m.addSeparator();
      m.add(dlHips);
      m.add(dlCat);
      m.addSeparator();
      m.add(manage);
      m.add(prefer);
      return m;
   }

   private void chooseHipsFolder() {
      JFileChooser fc = new JFileChooser(lastDir());
      fc.setDialogTitle("Choose a HiPS folder (or a folder that contains HiPS folders)");
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      if (fc.showOpenDialog(window()) != JFileChooser.APPROVE_OPTION) return;
      final File f = fc.getSelectedFile();
      new Thread(new Runnable() { public void run() {
         final int n = OfflineCollections.addHipsFolder(aladin, f);
         SwingUtilities.invokeLater(new Runnable() { public void run() {
            if (n > 0) {
               OfflineCollections.setSourceMode(OfflineCollections.getSourceMode()); // keep the choice persisted
               JOptionPane.showMessageDialog(window(), n + (n == 1 ? " HiPS" : " HiPS") + " added to \"HiPS offline\".\nThey will be there every time you start Astrovia.",
                     "Offline collections", JOptionPane.INFORMATION_MESSAGE);
            } else {
               JOptionPane.showMessageDialog(window(), "No HiPS found in that folder.\nA HiPS folder contains a file called \"properties\" and the Norder... folders.",
                     "Offline collections", JOptionPane.WARNING_MESSAGE);
            }
         } });
      } }, "Astrovia-add-hips").start();
   }

   private void chooseCatalog() {
      JFileChooser fc = new JFileChooser(lastDir());
      fc.setDialogTitle("Choose a catalogue file");
      fc.setFileFilter(new FileNameExtensionFilter("Catalogues (VOTable, CSV, TSV, FITS)", "vot", "votable", "xml", "csv", "tsv", "fits", "fit", "txt"));
      if (fc.showOpenDialog(window()) != JFileChooser.APPROVE_OPTION) return;
      File f = fc.getSelectedFile();
      boolean ok = OfflineCollections.addCatalog(aladin, f, null);
      JOptionPane.showMessageDialog(window(),
            ok ? f.getName() + " added to \"Catalogs offline\".\nOpen it from the collections list whenever you want."
               : "That file could not be added.",
            "Offline collections", ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
   }

   private File lastDir() {
      List<OfflineCollections.Entry> l = OfflineCollections.list();
      if (!l.isEmpty()) {
         File p = new File(l.get(l.size() - 1).path).getParentFile();
         if (p != null && p.isDirectory()) return p;
      }
      return new File(System.getProperty("user.home"));
   }

   /** Lists the library and lets the user forget collections (the files stay on disk). */
   private void manage() {
      final JDialog d = new JDialog(window(), "Offline collections", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
      final DefaultListModel<OfflineCollections.Entry> model = new DefaultListModel<OfflineCollections.Entry>();
      for (OfflineCollections.Entry e : OfflineCollections.list()) model.addElement(e);
      final JList<OfflineCollections.Entry> list = new JList<OfflineCollections.Entry>(model);
      list.setCellRenderer(new DefaultListCellRenderer() {
         public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
            OfflineCollections.Entry e = (OfflineCollections.Entry) v;
            String s = (e.kind == 'H' ? "[HiPS]  " : "[Catalogue]  ") + e.label + "   -   " + e.path + (e.exists() ? "" : "   (MISSING)");
            JLabel c = (JLabel) super.getListCellRendererComponent(l, s, i, sel, foc);
            if (!e.exists()) c.setForeground(new Color(0xEF, 0x44, 0x44));
            return c;
         }
      });
      JScrollPane sp = new JScrollPane(list);
      sp.setPreferredSize(new Dimension(640, 260));

      JButton remove = new JButton("Remove from the list");
      remove.setToolTipText("Forget the selected collections. The files stay on your disk.");
      remove.addActionListener(e -> {
         for (OfflineCollections.Entry en : list.getSelectedValuesList()) {
            OfflineCollections.remove(aladin, en);
            model.removeElement(en);
         }
      });
      JButton close = new JButton("Close");
      close.addActionListener(e -> d.dispose());
      JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
      south.add(remove);
      south.add(close);

      JPanel p = new JPanel(new BorderLayout(0, 8));
      p.setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));
      p.add(new JLabel(model.isEmpty() ? "No offline collections yet. Use \"Add a HiPS folder...\" or download one."
            : "Offline library (" + model.size() + "). Select entries and remove them from the list if you do not need them."), BorderLayout.NORTH);
      p.add(sp, BorderLayout.CENTER);
      p.add(south, BorderLayout.SOUTH);
      d.setContentPane(p);
      d.pack();
      d.setLocationRelativeTo(window());
      d.setVisible(true);
   }
}
