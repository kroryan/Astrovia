// Astrovia: multi-archive search (observations, spectra, catalogues, light curves, multi-messenger events, solar system,
// exoplanets), data centres, observation planning, visibility, targets, publications and view links. Own implementation
// on top of the public IVOA protocols (TAP/ADQL, VOTable). Part of Astrovia, an independent community project based on
// Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.tree.*;

/** Window "Archives": search ESA/NASA/ESO/MAST/IRSA/CDS archives around the current view and load the result as a catalogue plane. */
public class ArchivesDialog extends JDialog {

   /** One queryable table described in AlaArchives.json (or added by the user). */
   static final class Source {
      String id, label, mission, band, kind, table, ra, dec, tap, where, centre, idCol, linkCol, orderCol;
      boolean custom;
      public String toString() { return label; }
   }

   private static final String[] KINDS = { "all", "obs", "spec", "cat", "lc", "alert", "sso", "img", "exo" };
   private static final String[] KIND_LABELS = { "All", "Observations", "Spectra", "Catalogues", "Light curves", "Multi-messenger events", "Solar system", "Outreach images", "Exoplanets" };

   private final Aladin aladin;
   private final List<Source> sources = new ArrayList<Source>();
   private final JTree tree = new JTree(new DefaultMutableTreeNode("Archives"));
   private final JComboBox<String> kindBox = new JComboBox<String>(KIND_LABELS);
   private final JComboBox<String> orgBox = new JComboBox<String>(Organizer.NAMES);
   private final JTextField filter = new JTextField(14);
   private final JTextField raF = new JTextField("0", 9), decF = new JTextField("0", 9), radF = new JTextField("0.5", 6), maxF = new JTextField("2000", 6);
   private final JLabel status = new JLabel("Ready.");
   private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
   private final JTextField pubObject = new JTextField("M 42", 18);
   private final JButton pubGo = new JButton("Find publications (SIMBAD)");

   private int tabIndex(String title) { for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getTitleAt(i).equals(title)) return i; return 0; }

   /** Loads one source in Astrovia using the field of the Search tab. */
   private void searchSource(Source s) {
      try {
         double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim()), rad = Math.max(0.0005, Math.min(90.0, Double.parseDouble(radF.getText().trim())));
         int max = Math.max(1, Math.min(100000, Integer.parseInt(maxF.getText().trim())));
         loadUrl(s.tap + "/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=votable&QUERY=" + enc(adql(s, ra, dec, rad, max)), s.label);
      } catch (Exception ex) { status.setText("Check RA, Dec, radius and max rows (numbers)."); }
   }

   public ArchivesDialog(Window owner, Aladin aladin) {
      super(owner, "Archives - multi-mission search", ModalityType.MODELESS);
      this.aladin = aladin;
      loadSources();
      loadCustom();
      loadLists();
      tabs.addTab("Archives", overviewPanel());
      tabs.addTab("All archives", searchPanel());
      tabs.addTab("Gallery", galleryPanel());
      tabs.addTab("Object", objectPanel());
      tabs.addTab("Light curves", lightCurvePanel());
      tabs.addTab("Solar system", solarPanel());
      tabs.addTab("Plan", planPanel());
      tabs.addTab("Visibility", visibilityPanel());
      tabs.addTab("Lists", listsPanel());
      tabs.addTab("Publications", publicationsPanel());
      tabs.addTab("Services", servicesPanel());
      tabs.addTab("View link", linkPanel());
      setLayout(new BorderLayout(6, 6));
      add(tabs, BorderLayout.CENTER);
      status.setBorder(BorderFactory.createEmptyBorder(2, 10, 6, 10));
      add(status, BorderLayout.SOUTH);
      setSize(820, 620);
      setLocationRelativeTo(owner);
      useCurrentView();
      SwingUtilities.invokeLater(new Runnable() { public void run() { scanField(false); } });
   }

   // ------------------------------------------------------------------ search tab (tree, organised like Available data)
   private JPanel searchPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
      top.add(new JLabel("Organize:")); top.add(orgBox);
      top.add(new JLabel("Type:")); top.add(kindBox);
      top.add(new JLabel("Filter:")); top.add(filter);
      p.add(top, BorderLayout.NORTH);
      tree.setRootVisible(false);
      tree.setShowsRootHandles(true);
      p.add(new JScrollPane(tree), BorderLayout.CENTER);
      JPanel reg = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
      reg.add(new JLabel("RA:")); reg.add(raF);
      reg.add(new JLabel("Dec:")); reg.add(decF);
      reg.add(new JLabel("Radius (deg):")); reg.add(radF);
      reg.add(new JLabel("Max rows:")); reg.add(maxF);
      JButton cur = new JButton("Use current view");
      cur.addActionListener(e -> useCurrentView());
      reg.add(cur);
      JButton go = new JButton("Search and load in Astrovia");
      go.addActionListener(e -> doSearch());
      reg.add(go);
      p.add(reg, BorderLayout.SOUTH);
      DocumentListenerAdapter.install(filter, this::rebuildTree);
      kindBox.addActionListener(e -> rebuildTree());
      orgBox.setSelectedIndex(Math.min(Organizer.NAMES.length - 1, OfflineCollections.getOrganization()));
      orgBox.addActionListener(e -> { OfflineCollections.setOrganization(orgBox.getSelectedIndex()); rebuildTree(); });
      tree.addMouseListener(new java.awt.event.MouseAdapter() {
         public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2 && selected() != null) doSearch(); }
      });
      rebuildTree();
      return p;
   }

   private Source selected() {
      TreePath tp = tree.getSelectionPath();
      if (tp == null) return null;
      Object o = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
      return o instanceof Source ? (Source) o : null;
   }

   /** Folders of a source for the chosen organisation (same four organisations as Available data). */
   private String[] folders(Source s, int org) {
      String kind = KIND_LABELS[Math.max(0, indexOf(KINDS, s.kind))];
      switch (org) {
         case Organizer.WAVELENGTH: return new String[] { cds.astrovia.sky.Bands.ofArchive(s.band), kind };
         case Organizer.MISSION: return new String[] { s.mission == null || s.mission.isEmpty() ? "Various" : s.mission, kind };
         case Organizer.CENTRE: return new String[] { s.centre == null || s.centre.isEmpty() ? "ESA" : s.centre, kind };
         default: return new String[] { "Archives and catalogues", kind };
      }
   }

   private static int indexOf(String[] a, String v) { for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i; return 0; }

   private void rebuildTree() {
      String k = KINDS[Math.max(0, kindBox.getSelectedIndex())];
      String f = filter.getText().trim().toLowerCase(Locale.ROOT);
      int org = Math.max(0, orgBox.getSelectedIndex());
      DefaultMutableTreeNode root = new DefaultMutableTreeNode("Archives");
      Map<String, DefaultMutableTreeNode> folder = new LinkedHashMap<String, DefaultMutableTreeNode>();
      List<Source> ordered = new ArrayList<Source>(sources);
      if (org == Organizer.WAVELENGTH) {
         final List<String> order = cds.astrovia.sky.Bands.ALL;
         Collections.sort(ordered, new Comparator<Source>() { public int compare(Source a, Source b) { int x = order.indexOf(cds.astrovia.sky.Bands.ofArchive(a.band)) - order.indexOf(cds.astrovia.sky.Bands.ofArchive(b.band)); return x != 0 ? x : a.label.compareToIgnoreCase(b.label); } });
      }
      for (Source s : ordered) {
         if (!"all".equals(k) && !k.equals(s.kind)) continue;
         if (f.length() > 0 && !(s.label + " " + s.mission + " " + s.band + " " + s.centre).toLowerCase(Locale.ROOT).contains(f)) continue;
         String[] path = folders(s, org);
         DefaultMutableTreeNode parent = root;
         StringBuilder key = new StringBuilder();
         for (String part : path) {
            key.append('/').append(part);
            DefaultMutableTreeNode n = folder.get(key.toString());
            if (n == null) { n = new DefaultMutableTreeNode(part); folder.put(key.toString(), n); parent.add(n); }
            parent = n;
         }
         parent.add(new DefaultMutableTreeNode(s));
      }
      tree.setModel(new DefaultTreeModel(root));
      for (int i = 0; i < tree.getRowCount() && i < 60; i++) tree.expandRow(i);
   }

   private void useCurrentView() {
      try {
         ViewSimple v = aladin.view.getCurrentView();
         Coord c = v == null ? null : v.getCooCentre();
         if (c != null) {
            raF.setText(String.format(Locale.US, "%.6f", c.al));
            decF.setText(String.format(Locale.US, "%.6f", c.del));
            double r = Math.max(0.005, Math.min(20.0, v.getTaille() / 2.0 * 1.25));
            radF.setText(String.format(Locale.US, "%.4f", r));
         }
      } catch (Exception ex) { /* no view yet: keep the typed values */ }
   }

   private String adql(Source s, double ra, double dec, double rad, int max) {
      return cds.astrovia.sky.Adql.cone(s.table, s.ra, s.dec, ra, dec, rad, max, s.where, s.orderCol);   // same query as the Android app
   }

   private void doSearch() {
      final Source s = selected();
      if (s == null) { status.setText("Choose an archive from the tree."); return; }
      try {
         double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim());
         double rad = Math.max(0.0005, Math.min(90.0, Double.parseDouble(radF.getText().trim())));
         int max = Math.max(1, Math.min(100000, Integer.parseInt(maxF.getText().trim())));
         String url = cds.astrovia.sky.Adql.VIZIER.equals(s.tap) ? cds.astrovia.sky.Adql.vizierCone(s.table, ra, dec, rad, max)   // VizieR tables use VizieR's own service
            : s.tap + "/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=votable&QUERY=" + URLEncoder.encode(adql(s, ra, dec, rad, max), "UTF-8");
         loadUrl(url, s.label);
      } catch (Exception ex) {
         status.setText("Check RA, Dec, radius and max rows (numbers).");
      }
   }

   /** Downloads the VOTable to a file (so errors can be shown) and loads it as a catalogue plane. */
   private void loadUrl(final String url, final String label) {
      status.setText("Querying " + label + " ...");
      new Thread(new Runnable() { public void run() {
         try {
            final byte[] data = fetch(url);
            String head = new String(data, 0, Math.min(data.length, 4000), "UTF-8");
            if (head.contains("QUERY_STATUS\" value=\"ERROR")) {
               int i = head.indexOf("ERROR\">");
               final String msg = i < 0 ? "The archive rejected the query" : head.substring(i + 7, Math.min(head.length(), i + 240)).replaceAll("<.*", "").trim();
               SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText(label + ": " + msg); } });
               return;
            }
            File dir = new File(System.getProperty("user.home"), ".astrovia" + File.separator + "archives");
            dir.mkdirs();
            File f = new File(dir, label.replaceAll("[^A-Za-z0-9._-]+", "_") + ".vot");
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(data); } finally { out.close(); }
            final String path = f.getAbsolutePath().replace("\\", "/");
            SwingUtilities.invokeLater(new Runnable() { public void run() {
               status.setText(label + ": loaded (" + (data.length / 1024) + " KB)");
               aladin.execAsyncCommand("load \"" + path + "\"");
            } });
         } catch (final Exception ex) {
            SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText(label + ": " + ex.getMessage()); } });
         }
      } }, "Astrovia-archive-query").start();
   }

   static byte[] fetch(String url) throws IOException { return fetch(url, 120000); }

   static byte[] fetch(String url, int readMs) throws IOException {
      URLConnection c = new URL(url).openConnection();
      c.setConnectTimeout(15000);
      c.setReadTimeout(readMs);
      c.setRequestProperty("User-Agent", "Astrovia");
      InputStream in = c.getInputStream();
      try {
         ByteArrayOutputStream b = new ByteArrayOutputStream();
         byte[] buf = new byte[16384];
         int n;
         while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
         return b.toByteArray();
      } finally { in.close(); }
   }

   private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

   /** Runs an ADQL query and returns the rows (CSV, falling back to VOTable TABLEDATA). */
   static List<Map<String, String>> tap(String base, String adql) throws IOException { return tap(base, adql, 120000); }

   static List<Map<String, String>> tap(String base, String adql, int readMs) throws IOException {
      String t = new String(fetch(base + "/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=" + enc(adql), readMs), "UTF-8");
      if (t.trim().startsWith("<")) return cds.astrovia.sky.VotableReader.parse(t);
      return csv(t);
   }

   static List<Map<String, String>> csv(String text) { return cds.astrovia.sky.CsvTable.parse(text); }

   // ------------------------------------------------------------------ light curves (TESS, Kepler, K2 via MAST, or any FITS file)
   private JPanel lightCurvePanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JComboBox<String> mission = new JComboBox<String>(new String[] { "TESS", "Kepler", "K2" });
      final DefaultListModel<String> lm = new DefaultListModel<String>();
      final List<String[]> products = new ArrayList<String[]>();
      final JList<String> list = new JList<String>(lm);
      JButton find = new JButton("Find light curves in the field (Search tab)");
      JButton plot = new JButton("Plot selected");
      JButton open = new JButton("Open a FITS light curve...");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("Mission:")); top.add(mission); top.add(find); top.add(plot); top.add(open);
      p.add(top, BorderLayout.NORTH);
      p.add(new JScrollPane(list), BorderLayout.CENTER);
      p.add(new JLabel("Radius is taken from the Search tab (keep it small, e.g. 0.05 deg). Double-click a product to plot it."), BorderLayout.SOUTH);
      find.addActionListener(e -> {
         try {
            final double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim());
            final double rad = Math.min(0.5, Double.parseDouble(radF.getText().trim()));
            final String coll = (String) mission.getSelectedItem();
            lm.clear(); products.clear();
            status.setText("Searching MAST for " + coll + " light curves ...");
            new Thread(new Runnable() { public void run() {
               try {
                  String q = String.format(Locale.US, "SELECT TOP 200 obs_id,target_name,t_min,t_exptime,access_url FROM ivoa.obscore WHERE 1=CONTAINS(POINT('ICRS',s_ra,s_dec),CIRCLE('ICRS',%.6f,%.6f,%.5f)) AND (obs_collection='%s' AND dataproduct_type='timeseries')", ra, dec, rad, coll);
                  final List<Map<String, String>> rows = cds.astrovia.sky.VotableReader.parse(new String(fetch("https://mast.stsci.edu/vo-tap/api/v0.1/caom/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=votable&QUERY=" + enc(q)), "UTF-8"));
                  SwingUtilities.invokeLater(new Runnable() { public void run() {
                     for (Map<String, String> r : rows) { products.add(new String[] { r.get("access_url"), r.get("target_name") }); lm.addElement(r.get("obs_id") + "   target " + r.get("target_name") + "   exp " + r.get("t_exptime") + " s"); }
                     status.setText(rows.size() + " light curves");
                  } });
               } catch (final Exception ex) { SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText("MAST: " + ex.getMessage()); } }); }
            } }, "Astrovia-lc-search").start();
         } catch (Exception ex) { status.setText("Check RA, Dec and radius in the Search tab."); }
      });
      final Runnable doPlot = new Runnable() { public void run() {
         final int i = list.getSelectedIndex();
         if (i < 0) { status.setText("Select a light curve."); return; }
         final String url = products.get(i)[0], name = products.get(i)[1];
         status.setText("Downloading the light curve ...");
         new Thread(new Runnable() { public void run() {
            try {
               File dir = new File(System.getProperty("user.home"), ".astrovia" + File.separator + "lightcurves"); dir.mkdirs();
               File f = new File(dir, "lc_" + Integer.toHexString(url.hashCode()) + ".fits");
               if (!f.exists() || f.length() == 0) { byte[] d = fetch(url); FileOutputStream o = new FileOutputStream(f); try { o.write(d); } finally { o.close(); } }
               final File ff = f;
               SwingUtilities.invokeLater(new Runnable() { public void run() {
                  try { LightCurvePanel.show(ArchivesDialog.this, ff, name); status.setText("Light curve ready"); } catch (IOException ex) { status.setText("Light curve: " + ex.getMessage()); }
               } });
            } catch (final Exception ex) { SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText("Light curve: " + ex.getMessage()); } }); }
         } }, "Astrovia-lc-plot").start();
      } };
      plot.addActionListener(e -> doPlot.run());
      list.addMouseListener(new java.awt.event.MouseAdapter() { public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) doPlot.run(); } });
      open.addActionListener(e -> {
         JFileChooser fc = new JFileChooser();
         if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
         try { LightCurvePanel.show(this, fc.getSelectedFile(), fc.getSelectedFile().getName()); status.setText("Light curve ready"); } catch (IOException ex) { status.setText("Light curve: " + ex.getMessage()); }
      });
      return p;
   }

   // ------------------------------------------------------------------ solar system
   private JPanel solarPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
      JTextArea t = new JTextArea("Asteroids, comets and planets in the field of the current view, at the current date and time (IMCCE SkyBoT).\n"
            + "The field is taken from the Search tab (RA, Dec, radius). Use \"Use current view\" there first.");
      t.setEditable(false); t.setLineWrap(true); t.setWrapStyleWord(true); t.setOpaque(false);
      p.add(t, BorderLayout.NORTH);
      JButton go = new JButton("Find solar-system objects now");
      go.addActionListener(e -> {
         try {
            double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim());
            double rs = Math.max(1.0, Math.min(36000.0, Double.parseDouble(radF.getText().trim()) * 3600.0));
            double jd = Instant.now().toEpochMilli() / 86400000.0 + 2440587.5;
            String url = String.format(Locale.US, "https://ssp.imcce.fr/webservices/skybot/api/conesearch.php?-ra=%.6f&-dec=%.6f&-rs=%.1f&-mime=votable&-output=basic&-observer=500&-from=astrovia&-ep=%.5f", ra, dec, rs, jd);
            loadUrl(url, "SolarSystem_now");
         } catch (Exception ex) { status.setText("Check RA, Dec and radius in the Search tab."); }
      });
      JPanel q = new JPanel(new FlowLayout(FlowLayout.LEFT)); q.add(go);
      p.add(q, BorderLayout.CENTER);
      return p;
   }

   // ------------------------------------------------------------------ observation planning (instrument footprints)
   private JPanel planPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final List<cds.astrovia.sky.Planner.Instrument> instruments = cds.astrovia.sky.Planner.all();   // sizes and projection shared with the Android app
      String[] names = new String[instruments.size()];
      for (int i = 0; i < names.length; i++) names[i] = instruments.get(i).label;
      final JComboBox<String> box = new JComboBox<String>(names);
      final JSlider pa = new JSlider(0, 359, 0);
      final JLabel paL = new JLabel("PA 0 deg");
      pa.addChangeListener(e -> paL.setText("PA " + pa.getValue() + " deg"));
      JButton draw = new JButton("Draw on the sky at the Search-tab position");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("Instrument:")); top.add(box); top.add(paL); top.add(pa); top.add(draw);
      p.add(top, BorderLayout.NORTH);
      JTextArea note = new JTextArea("Project a future observation: choose an instrument and a position angle (PA, of the +y axis from north to east); the field of view is drawn "
            + "in a new overlay plane, centred on the RA/Dec of the Search tab (use \"Use current view\" there). Use the Visibility tab to see when the target can be observed.\n\n"
            + "Nominal field sizes (rounded). Real apertures have gaps and orientation limits that depend on the date: use the observatory planning tool for proposals.");
      note.setEditable(false); note.setLineWrap(true); note.setWrapStyleWord(true); note.setOpaque(false);
      p.add(note, BorderLayout.CENTER);
      draw.addActionListener(e -> {
         try {
            double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim());
            cds.astrovia.sky.Planner.Instrument inst = instruments.get(box.getSelectedIndex());
            aladin.execAsyncCommand("draw newtool(Plan " + inst.label.replace(",", " ").replace("(", "").replace(")", "") + ")");
            for (double[][] poly : cds.astrovia.sky.Planner.outline(inst, ra, dec, pa.getValue())) {
               StringBuilder sb = new StringBuilder("draw cyan polygon(");
               for (int i = 0; i < poly[0].length; i++) sb.append(i > 0 ? "," : "").append(String.format(Locale.US, "%.6f,%.6f", poly[0][i], poly[1][i]));
               aladin.execAsyncCommand(sb.append(")").toString());
            }
            status.setText("Footprint of " + inst.label + " drawn");
         } catch (Exception ex) { status.setText("Check RA and Dec in the Search tab."); }
      });
      return p;
   }

   // ------------------------------------------------------------------ visibility
   private final JTextArea visOut = new JTextArea();
   private final JPanel strip = new JPanel() {
      protected void paintComponent(Graphics g0) {
         super.paintComponent(g0);
         Graphics2D g = (Graphics2D) g0;
         int w = getWidth();
         g.setColor(new Color(128, 128, 128, 80)); g.fillRect(0, 2, w, 18);
         g.setColor(new Color(34, 197, 94));
         for (double[] win : stripWindows) { int x0 = (int) (win[0] * w), x1 = (int) (win[1] * w); g.fillRect(x0, 2, Math.max(2, x1 - x0), 18); }
         g.setColor(Color.GRAY);
         String[] m = stripMonths;
         for (int i = 0; i < m.length; i++) g.drawString(m[i], i * w / 12 + 2, 34);
         g.setColor(Color.WHITE); g.fillRect(0, 0, 3, 22);
      }
   };
   private List<double[]> stripWindows = new ArrayList<double[]>();
   private String[] stripMonths = new String[0];

   private JPanel visibilityPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JComboBox<String> box = new JComboBox<String>(cds.astrovia.sky.Visibility.PRESET_NAMES);
      JButton go = new JButton("Compute for RA/Dec of the Search tab");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("Observatory:")); top.add(box); top.add(go);
      p.add(top, BorderLayout.NORTH);
      visOut.setEditable(false);
      strip.setPreferredSize(new Dimension(100, 40));
      JPanel centre = new JPanel(new BorderLayout(4, 4));
      centre.add(strip, BorderLayout.NORTH);
      centre.add(new JScrollPane(visOut), BorderLayout.CENTER);
      p.add(centre, BorderLayout.CENTER);
      go.addActionListener(e -> {
         try {
            double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim());
            double[] c = cds.astrovia.sky.Visibility.PRESET_LIMITS[box.getSelectedIndex()];
            double start = Instant.now().toEpochMilli() / 86400000.0 + 2440587.5;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "Target RA %.4f Dec %+.4f%nSun angle now: %.1f deg%n%n", ra, dec, cds.astrovia.sky.Visibility.elongation(ra, dec, start)));
            stripWindows = new ArrayList<double[]>();
            List<double[]> wins = cds.astrovia.sky.Visibility.windows(ra, dec, c[0], c[1], start, 365, 0.25);   // same maths as the Android app
            for (double[] w : wins) { sb.append(window(w[0], w[1])); stripWindows.add(new double[] { (w[0] - start) / 365.0, (w[1] - start) / 365.0 }); }
            int n = wins.size();
            if (n == 0) sb.append("Not observable in the next 365 days with these limits.\n");
            sb.append("\nApproximate: only the Sun-angle limit is applied; real schedules also depend on roll angle, Moon and other constraints.");
            visOut.setText(sb.toString());
            java.time.LocalDate d0 = Instant.ofEpochMilli((long) ((start - 2440587.5) * 86400000.0)).atZone(ZoneOffset.UTC).toLocalDate();
            stripMonths = new String[12];
            for (int i = 0; i < 12; i++) stripMonths[i] = d0.plusMonths(i).getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
            strip.repaint();
         } catch (Exception ex) { visOut.setText("Enter RA and Dec (degrees) in the Search tab."); }
      });
      return p;
   }

   private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

   private static String jd2s(double jd) { return DAY.format(Instant.ofEpochMilli((long) ((jd - 2440587.5) * 86400000.0))); }

   private static String window(double a, double b) { return String.format(Locale.US, "%s -> %s  (%d days)%n", jd2s(a), jd2s(b), (int) (b - a)); }

   // ------------------------------------------------------------------ publications (object and field)
   private JPanel publicationsPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JTextField obj = pubObject;
      JButton go = pubGo;
      JButton field = new JButton("Most-cited objects in the field");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("Object:")); top.add(obj); top.add(go); top.add(field);
      p.add(top, BorderLayout.NORTH);
      final DefaultListModel<String> pm = new DefaultListModel<String>();
      final JList<String> pl = new JList<String>(pm);
      p.add(new JScrollPane(pl), BorderLayout.CENTER);
      p.add(new JLabel("Double-click a paper to open it in ADS; double-click an object of the field to list its papers."), BorderLayout.SOUTH);
      final boolean[] fieldMode = { false };
      go.addActionListener(e -> {
         fieldMode[0] = false;
         final String name = obj.getText().trim().replace("'", "''");
         pm.clear();
         new Thread(new Runnable() { public void run() {
            try {
               String adql = "SELECT TOP 60 ref.bibcode, ref.\"year\" AS yr, ref.title FROM ident JOIN has_ref ON ident.oidref=has_ref.oidref "
                     + "JOIN ref ON has_ref.oidbibref=ref.oidbib WHERE ident.id='" + name + "' ORDER BY yr DESC";
               final List<Map<String, String>> rows = tap("https://simbad.cds.unistra.fr/simbad/sim-tap", adql);
               SwingUtilities.invokeLater(new Runnable() { public void run() {
                  for (Map<String, String> r : rows) pm.addElement(r.get("bibcode") + "  " + r.get("yr") + "  " + r.get("title"));
                  status.setText(rows.size() + " publications");
               } });
            } catch (final Exception ex) {
               SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText("Publications: " + ex.getMessage()); } });
            }
         } }, "Astrovia-pubs").start();
      });
      field.addActionListener(e -> {
         fieldMode[0] = true;
         pm.clear();
         try {
            final double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim());
            final double rad = Math.max(0.002, Math.min(0.5, Double.parseDouble(radF.getText().trim())));
            status.setText("Looking for referenced objects in the field ...");
            new Thread(new Runnable() { public void run() {
               try {
                  String adql = String.format(Locale.US, "SELECT TOP 25 main_id, otype, nbref FROM basic WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',%.6f,%.6f,%.5f)) AND nbref>0 ORDER BY nbref DESC", ra, dec, rad);
                  final List<Map<String, String>> rows = tap("https://simbad.cds.unistra.fr/simbad/sim-tap", adql);
                  SwingUtilities.invokeLater(new Runnable() { public void run() {
                     for (Map<String, String> r : rows) pm.addElement(r.get("main_id") + "   [" + r.get("otype") + "]   " + r.get("nbref") + " papers");
                     status.setText(rows.size() + " objects");
                  } });
               } catch (final Exception ex) { SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText("SIMBAD: " + ex.getMessage()); } }); }
            } }, "Astrovia-field-pubs").start();
         } catch (Exception ex) { status.setText("Check RA, Dec and radius in the Search tab."); }
      });
      pl.addMouseListener(new java.awt.event.MouseAdapter() {
         public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() != 2 || pl.getSelectedValue() == null) return;
            String v = pl.getSelectedValue();
            if (fieldMode[0]) { obj.setText(v.substring(0, v.indexOf("   [")).trim()); fieldMode[0] = false; pm.clear(); for (java.awt.event.ActionListener a : go.getActionListeners()) a.actionPerformed(null); return; }
            try { aladin.glu.showDocument("Http", "https://ui.adsabs.harvard.edu/abs/" + enc(v.split("\\s+")[0]) + "/abstract", true); } catch (Exception ex) { /* ignore */ }
         }
      });
      return p;
   }

   // ------------------------------------------------------------------ services (external data centres added by the user)
   private File customFile() { return new File(System.getProperty("user.home"), ".astrovia" + File.separator + "custom_tap.tsv"); }

   private void loadCustom() {
      File f = customFile();
      if (!f.isFile()) return;
      try {
         BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
         try {
            String ln;
            while ((ln = r.readLine()) != null) {
               String[] p = ln.split("\t", -1);
               if (p.length < 6) continue;
               Source s = new Source();
               s.id = p[0]; s.label = p[1]; s.tap = p[2]; s.table = p[3]; s.ra = p[4]; s.dec = p[5];
               s.mission = p[2].replaceFirst("^https?://", "").replaceAll("/.*", ""); s.centre = s.mission; s.band = "Various"; s.kind = "cat"; s.custom = true;
               sources.add(s);
            }
         } finally { r.close(); }
      } catch (IOException ex) { /* none */ }
   }

   private void saveCustom() {
      try {
         customFile().getParentFile().mkdirs();
         PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(customFile()), "UTF-8"));
         try { for (Source s : sources) if (s.custom) w.println(s.id + "\t" + s.label + "\t" + s.tap + "\t" + s.table + "\t" + s.ra + "\t" + s.dec); } finally { w.close(); }
      } catch (IOException ex) { /* not critical */ }
   }

   private JPanel servicesPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JTextField url = new JTextField("https://", 38);
      final JTextField label = new JTextField(16);
      JButton list = new JButton("List tables");
      JButton add = new JButton("Add selected table");
      JButton del = new JButton("Remove my tables...");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("TAP service URL:")); top.add(url); top.add(list);
      p.add(top, BorderLayout.NORTH);
      final DefaultListModel<String> tm = new DefaultListModel<String>();
      final JList<String> tl = new JList<String>(tm);
      p.add(new JScrollPane(tl), BorderLayout.CENTER);
      JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
      b.add(new JLabel("Name:")); b.add(label); b.add(add); b.add(del);
      JPanel south = new JPanel(new BorderLayout());
      south.add(b, BorderLayout.NORTH);
      south.add(new JLabel("<html>Add a table from any TAP service (ESO, CADC, NED, a university archive...). It then appears in the Search tree under its data centre.</html>"), BorderLayout.SOUTH);
      p.add(south, BorderLayout.SOUTH);
      list.addActionListener(e -> {
         final String base = url.getText().trim().replaceAll("/+$", "");
         tm.clear();
         status.setText("Listing tables ...");
         new Thread(new Runnable() { public void run() {
            try {
               final List<Map<String, String>> rows = tap(base, "SELECT TOP 400 table_name FROM TAP_SCHEMA.tables ORDER BY table_name");
               SwingUtilities.invokeLater(new Runnable() { public void run() { for (Map<String, String> r : rows) tm.addElement(r.get("table_name")); status.setText(rows.size() + " tables"); } });
            } catch (final Exception ex) { SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText("TAP service: " + ex.getMessage()); } }); }
         } }, "Astrovia-tap-tables").start();
      });
      add.addActionListener(e -> {
         final String table = tl.getSelectedValue();
         if (table == null) { status.setText("Select a table."); return; }
         final String base = url.getText().trim().replaceAll("/+$", "");
         final String name = label.getText().trim().isEmpty() ? table : label.getText().trim();
         new Thread(new Runnable() { public void run() {
            try {
               final List<Map<String, String>> cols = tap(base, "SELECT column_name, ucd FROM TAP_SCHEMA.columns WHERE table_name='" + table.replace("'", "''") + "'");
               String ra = pick(cols, new String[] { "pos.eq.ra;meta.main", "pos.eq.ra" }, new String[] { "s_ra", "ra", "raj2000", "ra_deg", "ra_icrs" });
               String dec = pick(cols, new String[] { "pos.eq.dec;meta.main", "pos.eq.dec" }, new String[] { "s_dec", "dec", "dej2000", "dec_deg", "de_icrs" });
               if (ra == null || dec == null) throw new IOException("No RA/Dec columns found in " + table);
               final Source s = new Source();
               s.id = "custom:" + base + ":" + table; s.label = name; s.tap = base; s.table = table; s.ra = ra; s.dec = dec;
               s.mission = base.replaceFirst("^https?://", "").replaceAll("/.*", ""); s.centre = s.mission; s.band = "Various"; s.kind = "cat"; s.custom = true;
               SwingUtilities.invokeLater(new Runnable() { public void run() { sources.add(s); saveCustom(); rebuildTree(); status.setText("Added: " + name); } });
            } catch (final Exception ex) { SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText("Could not add the table: " + ex.getMessage()); } }); }
         } }, "Astrovia-tap-add").start();
      });
      del.addActionListener(e -> {
         int n = 0;
         for (Iterator<Source> it = sources.iterator(); it.hasNext();) if (it.next().custom) { it.remove(); n++; }
         saveCustom(); rebuildTree(); status.setText(n + " user tables removed");
      });
      return p;
   }

   private static String pick(List<Map<String, String>> cols, String[] ucds, String[] names) {
      for (String u : ucds) for (Map<String, String> c : cols) if (c.get("ucd") != null && c.get("ucd").startsWith(u)) return c.get("column_name");
      for (String n : names) for (Map<String, String> c : cols) if (n.equalsIgnoreCase(c.get("column_name"))) return c.get("column_name");
      return null;
   }

   // ------------------------------------------------------------------ view link
   private JPanel linkPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JTextField link = new JTextField();
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      JButton mk = new JButton("Make link of the current view");
      JButton cp = new JButton("Copy");
      JButton open = new JButton("Go to link");
      top.add(mk); top.add(cp); top.add(open);
      p.add(top, BorderLayout.NORTH);
      p.add(link, BorderLayout.CENTER);
      p.add(new JLabel("<html>Same format as the Android app: astrovia://view?ra=..&dec=..&fov=..</html>"), BorderLayout.SOUTH);
      mk.addActionListener(e -> {
         useCurrentView();
         try {
            double fov = Math.max(0.0005, aladin.view.getCurrentView().getTaille());
            link.setText(String.format(Locale.US, "astrovia://view?ra=%.6f&dec=%.6f&fov=%.5f", Double.parseDouble(raF.getText()), Double.parseDouble(decF.getText()), fov));
         } catch (Exception ex) { link.setText(""); status.setText("Open an image or a HiPS first."); }
      });
      cp.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(link.getText()), null));
      open.addActionListener(e -> {
         java.util.regex.Matcher m = java.util.regex.Pattern.compile("ra=([-0-9.]+)&dec=([-0-9.]+)(?:&fov=([0-9.]+))?").matcher(link.getText());
         if (!m.find()) { status.setText("That is not an astrovia://view link."); return; }
         aladin.execAsyncCommand(m.group(1) + " " + m.group(2));
         if (m.group(3) != null) aladin.execAsyncCommand("zoom " + m.group(3) + "deg");
      });
      return p;
   }

   // ------------------------------------------------------------------ overview: how much each archive has in the field
   private final JPanel overviewRows = new JPanel();
   private final JLabel overviewStatus = new JLabel(" ");
   private final Map<String, Integer> counts = new ConcurrentHashMap<String, Integer>();
   private volatile boolean scanning = false;

   private String ovKind = "obs";
   private final Map<String, JToggleButton> kindButtons = new LinkedHashMap<String, JToggleButton>();
   private static final String[][] OV_KINDS = { { "obs", "Observations" }, { "cat", "Catalogues" }, { "spec", "Spectra" }, { "lc", "Light curves" }, { "alert", "Events" }, { "img", "Images" } };

   private JPanel overviewPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
      ButtonGroup g = new ButtonGroup();
      for (final String[] k : OV_KINDS) {
         JToggleButton b = new JToggleButton(k[1], k[0].equals(ovKind));
         g.add(b); top.add(b); kindButtons.put(k[0], b);
         b.addActionListener(e -> { ovKind = k[0]; refreshOverview(lastScan); });
      }
      JButton scan = new JButton("Scan this field again"), all = new JButton("Scan all archives");
      top.add(javax.swing.Box.createHorizontalStrut(12)); top.add(scan); top.add(all); top.add(overviewStatus);
      p.add(top, BorderLayout.NORTH);
      overviewRows.setLayout(new BoxLayout(overviewRows, BoxLayout.Y_AXIS));
      p.add(new JScrollPane(overviewRows), BorderLayout.CENTER);
      p.add(new JLabel("What exists in the field of the current view, per archive and type. Click a bar to load that archive's results in Astrovia."), BorderLayout.SOUTH);
      scan.addActionListener(e -> { useCurrentView(); scanField(false); });
      all.addActionListener(e -> { useCurrentView(); scanField(true); });
      return p;
   }

   private List<Source> lastScan = new ArrayList<Source>();

   private int count(Source s, double ra, double dec, double rad) throws IOException {
      if( cds.astrovia.sky.Adql.VIZIER.equals(s.tap) ) {   // no cheap count in VizieR: up to 1000 rows are read and counted
         return cds.astrovia.sky.VotableReader.parse(new String(fetch(cds.astrovia.sky.Adql.vizierCone(s.table, ra, dec, rad, 1000), 45000), "UTF-8")).size();
      }
      if( cds.astrovia.sky.Adql.countsByRows(s.tap) ) {   // SIMBAD: the first 1000 rows are counted, a full COUNT takes minutes
         return tap(s.tap, cds.astrovia.sky.Adql.countRows(s.table, s.ra, s.dec, ra, dec, rad, s.where, 1000), 100000).size();
      }
      String q = cds.astrovia.sky.Adql.count(s.table, s.ra, s.dec, ra, dec, rad, s.where);
      List<Map<String, String>> r = tap(s.tap, q, 45000);   // a slow archive is skipped, it must not block the scan
      if (r.isEmpty() || r.get(0).isEmpty()) return 0;
      try { return (int) Double.parseDouble(r.get(0).values().iterator().next().trim()); } catch (NumberFormatException e) { return 0; }
   }

   private int scanGen = 0;
   private ExecutorService scanPool;

   /** Counts what every archive has in the field of the Search tab. Calling it again restarts the scan on the current values. */
   private void scanField(final boolean all) {
      final double ra, dec, rad;
      try { ra = Double.parseDouble(raF.getText().trim()); dec = Double.parseDouble(decF.getText().trim()); rad = Math.max(0.0005, Math.min(20, Double.parseDouble(radF.getText().trim()))); }
      catch (Exception e) { overviewStatus.setText("Check RA, Dec and radius in the Search tab."); return; }
      final List<Source> todo = new ArrayList<Source>();
      for (Source s : sources) {
         if (s.kind.equals("sso") || s.kind.equals("exo")) continue;
         if (all || "ESA".equals(s.centre) || "MAST".equals(s.centre) || "CDS".equals(s.centre)) todo.add(s);
      }
      if (scanPool != null) scanPool.shutdownNow();          // a scan in progress is replaced by this one
      final int gen = ++scanGen;
      counts.clear(); scanning = true; lastScan = todo; refreshOverview(todo);
      final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
      final ExecutorService ex = Executors.newFixedThreadPool(6);
      scanPool = ex;
      for (final Source s : todo) ex.submit(new Runnable() { public void run() {
         int n;
         try { n = count(s, ra, dec, rad); } catch (Exception e) { n = -1; }
         if (gen != scanGen) return;
         counts.put(s.id, n);
         final int d = done.incrementAndGet();
         SwingUtilities.invokeLater(new Runnable() { public void run() {
            if (gen != scanGen) return;
            overviewStatus.setText(d + " / " + todo.size() + " archives");
            if (d == todo.size() || d % 8 == 0) refreshOverview(todo);
            if (d == todo.size()) { scanning = false; overviewStatus.setText(""); ex.shutdown(); }
         } });
      } });
   }

   private void refreshOverview(List<Source> todo) {
      overviewRows.removeAll();
      int max = 1;
      for (Source s : todo) { Integer n = counts.get(s.id); if (s.kind.equals(ovKind) && n != null && n > max) max = n; }
      // totals on the type buttons
      for (String[] k : OV_KINDS) {
         int total = 0;
         for (Source s : todo) { Integer n = counts.get(s.id); if (s.kind.equals(k[0]) && n != null && n > 0) total += n; }
         JToggleButton b = kindButtons.get(k[0]);
         if (b != null) b.setText(total > 0 ? k[1] + " (" + compact(total) + ")" : k[1]);
      }
      List<Source> g = new ArrayList<Source>();
      for (Source s : todo) { Integer n = counts.get(s.id); if (s.kind.equals(ovKind) && n != null && n > 0) g.add(s); }
      Collections.sort(g, new Comparator<Source>() { public int compare(Source a, Source b) { return counts.get(b.id) - counts.get(a.id); } });
      if (g.isEmpty()) overviewRows.add(new JLabel(scanning ? "  Looking at the field ..." : "  Nothing of this kind in the field. Zoom out, move the view, or pick another type."));
      int ci = 0;
      for (final Source s : g) {
         final int n = counts.get(s.id), mx = max, col = Math.abs((s.mission == null ? "" : s.mission).hashCode()) % PALETTE.length;
         JPanel row = new JPanel(new BorderLayout(6, 0)) {
            protected void paintComponent(Graphics gr) {
               super.paintComponent(gr);
               int bw = (int) ((getWidth() - 6) * (n / (double) mx)), y = getHeight() - 9;
               gr.setColor(new Color(128, 128, 128, 50)); gr.fillRoundRect(0, y, getWidth() - 6, 7, 7, 7);
               gr.setColor(PALETTE[col]); gr.fillRoundRect(0, y, Math.max(6, bw), 7, 7, 7);
            }
         };
         row.setOpaque(false);
         row.setBorder(BorderFactory.createEmptyBorder(2, 2, 12, 2));
         row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
         row.add(new JLabel(s.label), BorderLayout.CENTER);
         row.add(new JLabel(compact(n)), BorderLayout.EAST);
         row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
         row.setToolTipText("Load " + s.label + " in Astrovia");
         row.addMouseListener(new java.awt.event.MouseAdapter() { public void mouseClicked(java.awt.event.MouseEvent e) { searchSource(s); } });
         overviewRows.add(row);
      }
      overviewRows.revalidate(); overviewRows.repaint();
   }

   private static final Color[] PALETTE = { new Color(0x38BDF8), new Color(0xA78BFA), new Color(0xFBBF24), new Color(0x34D399), new Color(0xF472B6), new Color(0xFB923C), new Color(0x2DD4BF), new Color(0xF87171), new Color(0x818CF8), new Color(0xA3E635) };

   private static String compact(int n) { return n >= 1000000 ? String.format(Locale.US, "%.1fM", n / 1e6) : n >= 10000 ? (n / 1000) + "k" : n >= 1000 ? String.format(Locale.US, "%.1fk", n / 1000.0) : String.valueOf(n); }

   // ------------------------------------------------------------------ gallery of outreach pictures
   private final DefaultListModel<OutreachViewer.Pic> picModel = new DefaultListModel<OutreachViewer.Pic>();
   private final List<OutreachViewer.Pic> allPics = new ArrayList<OutreachViewer.Pic>();
   private final Map<String, ImageIcon> thumbs = new ConcurrentHashMap<String, ImageIcon>();
   private final Set<String> thumbBusy = ConcurrentHashMap.newKeySet();
   private String galleryMission = "webb";

   private JPanel galleryPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JComboBox<String> mission = new JComboBox<String>(new String[] { "James Webb", "Euclid", "Hubble" });
      final JTextField f = new JTextField(16);
      JButton toList = new JButton("Make a list of these pictures");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("Mission:")); top.add(mission); top.add(new JLabel("Search:")); top.add(f); top.add(toList);
      p.add(top, BorderLayout.NORTH);
      final JList<OutreachViewer.Pic> list = new JList<OutreachViewer.Pic>(picModel);
      list.setFixedCellHeight(72);
      list.setCellRenderer(new DefaultListCellRenderer() {
         public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
            JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
            final OutreachViewer.Pic pic = (OutreachViewer.Pic) v;
            c.setText("<html><b>" + esc(pic.title) + "</b><br>" + esc(pic.description.length() > 130 ? pic.description.substring(0, 130) + "..." : pic.description) + "</html>");
            ImageIcon ic = thumbs.get(pic.thumbnailUrl());
            c.setIcon(ic);
            if (ic == null && thumbBusy.add(pic.thumbnailUrl())) {
               final String u = pic.thumbnailUrl();
               new Thread(new Runnable() { public void run() {
                  try {
                     java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(new ByteArrayInputStream(fetch(u)));
                     if (b != null) { int hh = 64, ww = Math.max(1, b.getWidth() * hh / b.getHeight()); thumbs.put(u, new ImageIcon(b.getScaledInstance(ww, hh, Image.SCALE_SMOOTH))); SwingUtilities.invokeLater(new Runnable() { public void run() { list.repaint(); } }); }
                  } catch (Exception e) { /* no thumbnail */ }
               } }, "Astrovia-thumb").start();
            }
            return c;
         }
      });
      p.add(new JScrollPane(list), BorderLayout.CENTER);
      p.add(new JLabel("Double-click a picture to explore it at full resolution (only the tiles you look at are downloaded)."), BorderLayout.SOUTH);
      final Runnable filterNow = new Runnable() { public void run() {
         String q = f.getText().trim().toLowerCase(Locale.ROOT);
         picModel.clear();
         for (OutreachViewer.Pic pic : allPics) if (q.isEmpty() || (pic.title + " " + pic.description + " " + pic.id).toLowerCase(Locale.ROOT).contains(q)) picModel.addElement(pic);
         status.setText(picModel.size() + " pictures");
      } };
      DocumentListenerAdapter.install(f, filterNow);
      final Runnable load = new Runnable() { public void run() {
         final String m = galleryMission;
         status.setText("Loading the gallery ...");
         new Thread(new Runnable() { public void run() {
            try {
               String table = m.equals("webb") ? "images.webb_outreach" : m.equals("euclid") ? "images.euclid_outreach" : "images.hst_outreach";
               final List<Map<String, String>> rows = tap("https://sky.esa.int/esasky-tap/tap", "SELECT TOP 2500 id,title,description,credit,tiles_url,ra_deg,dec_deg,fov_size,stc_s FROM " + table + " ORDER BY title");
               final List<OutreachViewer.Pic> pics = new ArrayList<OutreachViewer.Pic>();
               for (Map<String, String> r : rows) {
                  OutreachViewer.Pic pic = new OutreachViewer.Pic();
                  pic.id = r.get("id"); pic.title = r.get("title"); pic.description = r.get("description") == null ? "" : r.get("description"); pic.credit = r.get("credit") == null ? "" : r.get("credit");
                  pic.tilesUrl = r.get("tiles_url"); pic.mission = m;
                  try { pic.ra = Double.parseDouble(r.get("ra_deg")); pic.dec = Double.parseDouble(r.get("dec_deg")); } catch (Exception e) { continue; }
                  try { pic.fov = Double.parseDouble(r.get("fov_size")); } catch (Exception e) { pic.fov = 0.2; }
                  if (pic.tilesUrl == null || pic.tilesUrl.isEmpty()) continue;
                  java.util.List<double[][]> shapes = cds.astrovia.sky.Stcs.parse(r.get("stc_s"));   // same parser as the Android app
                  if (!shapes.isEmpty()) { pic.cornersRa = shapes.get(0)[0]; pic.cornersDec = shapes.get(0)[1]; }
                  pics.add(pic);
               }
               SwingUtilities.invokeLater(new Runnable() { public void run() { allPics.clear(); allPics.addAll(pics); filterNow.run(); } });
            } catch (final Exception ex) { SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText("Gallery: " + ex.getMessage()); } }); }
         } }, "Astrovia-gallery").start();
      } };
      mission.addActionListener(e -> { galleryMission = mission.getSelectedIndex() == 0 ? "webb" : mission.getSelectedIndex() == 1 ? "euclid" : "hst"; load.run(); });
      list.addMouseListener(new java.awt.event.MouseAdapter() { public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2 && list.getSelectedValue() != null) OutreachViewer.show(ArchivesDialog.this, list.getSelectedValue(), aladin); } });
      toList.addActionListener(e -> {
         String name = mission.getSelectedItem() + " pictures";
         List<String[]> rows = new ArrayList<String[]>();
         for (OutreachViewer.Pic pic : allPics) rows.add(new String[] { pic.title, String.valueOf(pic.ra), String.valueOf(pic.dec) });
         lists.put(name, rows); currentList = name; saveLists(); refreshLists(); status.setText("List created: " + name);
      });
      load.run();
      return p;
   }

   private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

   // ------------------------------------------------------------------ lists of objects (several named lists, Messier built in)
   private final Map<String, List<String[]>> lists = new LinkedHashMap<String, List<String[]>>();
   private final Map<String, List<String[]>> builtIn = new LinkedHashMap<String, List<String[]>>();
   private String currentList = "My targets";
   private final DefaultListModel<String> listModel = new DefaultListModel<String>();
   private final JComboBox<String> listBox = new JComboBox<String>();
   private boolean refreshing = false;

   private File listsFile() { return new File(System.getProperty("user.home"), ".astrovia" + File.separator + "lists.tsv"); }

   private void loadLists() {
      try {
         InputStream in = ArchivesDialog.class.getResourceAsStream("/AlaLists.json");
         if (in != null) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            for (Object o : (List<?>) ((Map<?, ?>) new Json(new String(b.toByteArray(), "UTF-8")).parse()).get("lists")) {
               Map<?, ?> m = (Map<?, ?>) o; List<String[]> rows = new ArrayList<String[]>();
               for (Object t : (List<?>) m.get("targets")) { Map<?, ?> tm = (Map<?, ?>) t; rows.add(new String[] { (String) tm.get("name"), String.valueOf(tm.get("ra")), String.valueOf(tm.get("dec")) }); }
               builtIn.put((String) m.get("name"), rows);
            }
         }
      } catch (Exception ex) { /* built-in lists optional */ }
      File f = listsFile();
      try {
         if (f.isFile()) {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try { String ln; while ((ln = r.readLine()) != null) { String[] p = ln.split("\t"); if (p.length >= 4) { List<String[]> l = lists.get(p[0]); if (l == null) { l = new ArrayList<String[]>(); lists.put(p[0], l); } l.add(new String[] { p[1], p[2], p[3] }); } else if (p.length == 1 && !p[0].isEmpty() && !lists.containsKey(p[0])) lists.put(p[0], new ArrayList<String[]>()); } } finally { r.close(); }
         } else {
            File old = new File(System.getProperty("user.home"), ".astrovia" + File.separator + "targets.txt");   // migrate the old single list
            List<String[]> l = new ArrayList<String[]>();
            if (old.isFile()) {
               BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(old), "UTF-8"));
               try { String ln; while ((ln = r.readLine()) != null) { String[] a = ln.split("\\|"); if (a.length > 1) { String[] c = a[1].trim().split("\\s+"); if (c.length >= 2) l.add(new String[] { a[0].trim(), c[0], c[1] }); } } } finally { r.close(); }
            }
            lists.put("My targets", l);
         }
      } catch (IOException ex) { /* start empty */ }
      if (lists.isEmpty()) lists.put("My targets", new ArrayList<String[]>());
      if (!lists.containsKey(currentList)) currentList = lists.keySet().iterator().next();
   }

   private void saveLists() {
      try {
         listsFile().getParentFile().mkdirs();
         PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(listsFile()), "UTF-8"));
         try { for (Map.Entry<String, List<String[]>> e : lists.entrySet()) { w.println(e.getKey()); for (String[] t : e.getValue()) w.println(e.getKey() + "\t" + t[0] + "\t" + t[1] + "\t" + t[2]); } } finally { w.close(); }
      } catch (IOException ex) { /* not critical */ }
   }

   private List<String[]> rowsOf(String name) { List<String[]> l = lists.get(name); return l != null ? l : builtIn.get(name); }

   private void refreshLists() {
      refreshing = true;
      listBox.removeAllItems();
      for (String n : lists.keySet()) listBox.addItem(n);
      for (String n : builtIn.keySet()) if (!lists.containsKey(n)) listBox.addItem(n);
      listBox.setSelectedItem(currentList);
      listModel.clear();
      List<String[]> rows = rowsOf(currentList);
      if (rows != null) for (String[] t : rows) listModel.addElement(t[0] + "   (" + String.format(Locale.US, "%.4f, %+.4f", Double.parseDouble(t[1]), Double.parseDouble(t[2])) + ")");
      refreshing = false;
   }

   private JPanel listsPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      JButton create = new JButton("New"), del = new JButton("Delete"), copy = new JButton("Copy built-in to mine");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("List:")); top.add(listBox); top.add(create); top.add(del); top.add(copy);
      final JTextArea names = new JTextArea(3, 30);
      JPanel north = new JPanel(new BorderLayout(4, 4));
      north.add(top, BorderLayout.NORTH);
      north.add(new JScrollPane(names), BorderLayout.CENTER);
      p.add(north, BorderLayout.NORTH);
      final JList<String> tl = new JList<String>(listModel);
      p.add(new JScrollPane(tl), BorderLayout.CENTER);
      JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
      JButton add = new JButton("Add names (SIMBAD) or name,ra,dec"), here = new JButton("Add view centre"), go = new JButton("Go to selected"), rm = new JButton("Remove"), exp = new JButton("Copy as CSV");
      b.add(add); b.add(here); b.add(go); b.add(rm); b.add(exp);
      p.add(b, BorderLayout.SOUTH);
      refreshLists();
      listBox.addActionListener(e -> { if (!refreshing && listBox.getSelectedItem() != null) { currentList = (String) listBox.getSelectedItem(); refreshLists(); } });
      create.addActionListener(e -> { String n = JOptionPane.showInputDialog(this, "Name of the new list"); if (n != null && !n.trim().isEmpty() && !lists.containsKey(n.trim())) { lists.put(n.trim(), new ArrayList<String[]>()); currentList = n.trim(); saveLists(); refreshLists(); } });
      del.addActionListener(e -> { if (lists.containsKey(currentList)) { lists.remove(currentList); if (lists.isEmpty()) lists.put("My targets", new ArrayList<String[]>()); currentList = lists.keySet().iterator().next(); saveLists(); refreshLists(); } });
      copy.addActionListener(e -> { if (!lists.containsKey(currentList) && builtIn.containsKey(currentList)) { lists.put(currentList, new ArrayList<String[]>(builtIn.get(currentList))); saveLists(); refreshLists(); } });
      add.addActionListener(e -> {
         if (!lists.containsKey(currentList)) { status.setText("Copy the built-in list to your lists first."); return; }
         final String[] lines = names.getText().split("\\R");
         new Thread(new Runnable() { public void run() {
            int ok = 0, bad = 0;
            for (String ln : lines) {
               String n = ln.trim();
               if (n.isEmpty() || n.startsWith("#")) continue;
               String[] f = n.split("[,;\\t]");
               try {
                  if (f.length >= 3) { Double.parseDouble(f[f.length - 2].trim()); Double.parseDouble(f[f.length - 1].trim()); final String[] row = { f[0].trim().replace("\"", ""), f[f.length - 2].trim(), f[f.length - 1].trim() }; lists.get(currentList).add(row); ok++; continue; }
               } catch (NumberFormatException nf) { /* not coordinates: resolve the name */ }
               try {
                  String r = new String(fetch("https://cds.unistra.fr/cgi-bin/nph-sesame/-oI/SNV?" + enc(n)), "UTF-8");
                  java.util.regex.Matcher m = java.util.regex.Pattern.compile("%J\\s+([-+0-9.]+)\\s+([-+0-9.]+)").matcher(r);
                  if (m.find()) { lists.get(currentList).add(new String[] { n, m.group(1), m.group(2) }); ok++; } else bad++;
               } catch (Exception ex) { bad++; }
            }
            final String msg = "Targets: " + ok + " added" + (bad > 0 ? ", " + bad + " not found" : "");
            SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText(msg); saveLists(); refreshLists(); } });
         } }, "Astrovia-targets").start();
      });
      here.addActionListener(e -> { useCurrentView(); if (lists.containsKey(currentList)) { lists.get(currentList).add(new String[] { "View " + raF.getText() + " " + decF.getText(), raF.getText().trim(), decF.getText().trim() }); saveLists(); refreshLists(); } });
      go.addActionListener(e -> { int i = tl.getSelectedIndex(); List<String[]> r = rowsOf(currentList); if (i >= 0 && r != null && aladin != null) aladin.execAsyncCommand(r.get(i)[1] + " " + r.get(i)[2]); });
      rm.addActionListener(e -> { int i = tl.getSelectedIndex(); if (i >= 0 && lists.containsKey(currentList)) { lists.get(currentList).remove(i); saveLists(); refreshLists(); } });
      exp.addActionListener(e -> {
         StringBuilder sb = new StringBuilder("name,ra_deg,dec_deg\n");
         List<String[]> r = rowsOf(currentList);
         if (r != null) for (String[] t : r) sb.append('"').append(t[0].replace("\"", "\"\"")).append("\",").append(t[1]).append(',').append(t[2]).append('\n');
         Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
         status.setText("List copied to the clipboard as CSV");
      });
      return p;
   }

   // ------------------------------------------------------------------ object explorer
   private JPanel objectPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JTextField name = new JTextField("M 42", 20);
      JButton go = new JButton("Explore object");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("Name:")); top.add(name); top.add(go);
      p.add(top, BorderLayout.NORTH);
      final JTextArea out = new JTextArea();
      out.setEditable(false); out.setLineWrap(true); out.setWrapStyleWord(true);
      p.add(new JScrollPane(out), BorderLayout.CENTER);
      JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
      JButton center = new JButton("Go to"), papers = new JButton("Papers"), obs = new JButton("Observations here"), simbad = new JButton("SIMBAD"), ned = new JButton("NED"), wiki = new JButton("Wikipedia"), addl = new JButton("Add to list");
      b.add(center); b.add(obs); b.add(papers); b.add(addl); b.add(simbad); b.add(ned); b.add(wiki);
      p.add(b, BorderLayout.SOUTH);
      final String[] cur = { null, null, null };   // main id, ra, dec
      go.addActionListener(e -> {
         final String q = name.getText().trim().replace("'", "''");
         out.setText("Looking up " + q + " ...");
         new Thread(new Runnable() { public void run() {
            try {
               final String base = "https://simbad.cds.unistra.fr/simbad/sim-tap";
               List<Map<String, String>> r = tap(base, "SELECT TOP 1 main_id, otype, ra, dec, plx_value, rvz_redshift, sp_type, galdim_majaxis, nbref FROM basic JOIN ident ON ident.oidref = basic.oid WHERE ident.id = '" + q + "'");
               if (r.isEmpty()) { SwingUtilities.invokeLater(new Runnable() { public void run() { out.setText("Not found in SIMBAD."); } }); return; }
               Map<String, String> m = r.get(0);
               String main = m.get("main_id").trim();
               StringBuilder sb = new StringBuilder(main).append("\n").append(otype(m.get("otype") == null ? "" : m.get("otype").trim())).append("\n\n");
               sb.append("RA ").append(m.get("ra")).append("  Dec ").append(m.get("dec")).append(" (deg)\n");
               try { double plx = Double.parseDouble(m.get("plx_value")); if (plx > 0.5) sb.append(String.format(Locale.US, "Distance: %,.0f light-years (parallax %.2f mas)%n", 3261.56 / plx, plx)); } catch (Exception ex) { /* none */ }
               try { sb.append(String.format(Locale.US, "Redshift: %.5f%n", Double.parseDouble(m.get("rvz_redshift")))); } catch (Exception ex) { /* none */ }
               if (m.get("sp_type") != null && !m.get("sp_type").isEmpty()) sb.append("Spectral type: ").append(m.get("sp_type")).append("\n");
               try { sb.append(String.format(Locale.US, "Size: %.1f arcmin%n", Double.parseDouble(m.get("galdim_majaxis")))); } catch (Exception ex) { /* none */ }
               sb.append(m.get("nbref")).append(" papers\n");
               try {
                  List<Map<String, String>> ids = tap(base, "SELECT TOP 30 ident.id FROM ident JOIN basic ON ident.oidref = basic.oid WHERE basic.main_id = '" + main.replace("'", "''") + "'");
                  sb.append("\nAlso known as: ");
                  for (int i = 0; i < ids.size(); i++) sb.append(i > 0 ? ", " : "").append(ids.get(i).get("id").trim());
                  sb.append("\n");
               } catch (Exception ex) { /* optional */ }
               try {
                  List<Map<String, String>> fl = tap(base, "SELECT \"B\", \"V\", \"G\", \"J\", \"H\", \"K\" FROM allfluxes JOIN basic ON allfluxes.oidref = basic.oid WHERE basic.main_id = '" + main.replace("'", "''") + "'");
                  if (!fl.isEmpty()) { sb.append("\nMagnitudes: "); for (Map.Entry<String, String> en : fl.get(0).entrySet()) if (en.getValue() != null && !en.getValue().trim().isEmpty()) sb.append(en.getKey()).append(' ').append(en.getValue().trim()).append("   "); }
               } catch (Exception ex) { /* optional */ }
               cur[0] = main; cur[1] = m.get("ra"); cur[2] = m.get("dec");
               final String text = sb.toString();
               SwingUtilities.invokeLater(new Runnable() { public void run() { out.setText(text); out.setCaretPosition(0); status.setText("Object: " + cur[0]); } });
            } catch (final Exception ex) { SwingUtilities.invokeLater(new Runnable() { public void run() { out.setText("SIMBAD did not answer: " + ex.getMessage()); } }); }
         } }, "Astrovia-object").start();
      });
      center.addActionListener(e -> { if (cur[0] != null && aladin != null) aladin.execAsyncCommand(cur[1] + " " + cur[2]); });
      obs.addActionListener(e -> { if (cur[0] != null) { raF.setText(cur[1]); decF.setText(cur[2]); if (aladin != null) aladin.execAsyncCommand(cur[1] + " " + cur[2]); tabs.setSelectedIndex(tabIndex("Overview")); scanField(false); } });
      papers.addActionListener(e -> { if (cur[0] != null) { pubObject.setText(cur[0]); tabs.setSelectedIndex(tabIndex("Publications")); for (java.awt.event.ActionListener a : pubGo.getActionListeners()) a.actionPerformed(null); } });
      addl.addActionListener(e -> { if (cur[0] != null && lists.containsKey(currentList)) { lists.get(currentList).add(new String[] { cur[0], cur[1], cur[2] }); saveLists(); refreshLists(); status.setText("Added to " + currentList); } });
      simbad.addActionListener(e -> { if (cur[0] != null) aladin.glu.showDocument("Http", "https://simbad.cds.unistra.fr/simbad/sim-id?Ident=" + enc(cur[0]), true); });
      ned.addActionListener(e -> { if (cur[0] != null) aladin.glu.showDocument("Http", "https://ned.ipac.caltech.edu/byname?objname=" + enc(cur[0]), true); });
      wiki.addActionListener(e -> { if (cur[0] != null) aladin.glu.showDocument("Http", "https://en.wikipedia.org/w/index.php?search=" + enc(cur[0]), true); });
      return p;
   }

   private static String otype(String c) {
      String[][] t = { { "*", "Star" }, { "**", "Double or multiple star" }, { "V*", "Variable star" }, { "Psr", "Pulsar" }, { "PN", "Planetary nebula" }, { "SNR", "Supernova remnant" }, { "HII", "HII region" }, { "Cl*", "Star cluster" },
         { "OpC", "Open cluster" }, { "GlC", "Globular cluster" }, { "G", "Galaxy" }, { "GiG", "Galaxy in a group" }, { "GiC", "Galaxy in a cluster" }, { "AGN", "Active galaxy" }, { "QSO", "Quasar" }, { "Bla", "Blazar" },
         { "ClG", "Galaxy cluster" }, { "BH", "Black hole" }, { "Neb", "Nebula" }, { "RNe", "Reflection nebula" }, { "DNe", "Dark cloud" }, { "MoC", "Molecular cloud" }, { "SFR", "Star-forming region" }, { "Pl", "Planet" } };
      for (String[] x : t) if (x[0].equals(c)) return x[1];
      return c.isEmpty() ? "Object" : c;
   }

   // ------------------------------------------------------------------ registry (AlaArchives.json)
   private void loadSources() {
      try {
         InputStream in = ArchivesDialog.class.getResourceAsStream("/AlaArchives.json");
         if (in == null) { status.setText("Archive registry not found."); return; }
         ByteArrayOutputStream b = new ByteArrayOutputStream();
         byte[] buf = new byte[8192];
         int n;
         while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
         in.close();
         Object root = new Json(new String(b.toByteArray(), "UTF-8")).parse();
         Map<?, ?> m = (Map<?, ?>) root;
         String defTap = (String) m.get("tap");
         for (Object o : (List<?>) m.get("sources")) {
            Map<?, ?> s = (Map<?, ?>) o;
            Source x = new Source();
            x.id = (String) s.get("id"); x.label = (String) s.get("label"); x.mission = str(s.get("mission")); x.band = str(s.get("band"));
            x.kind = str(s.get("kind")); x.table = (String) s.get("table"); x.ra = (String) s.get("ra"); x.dec = (String) s.get("dec");
            x.tap = s.get("tap") != null ? (String) s.get("tap") : defTap;
            x.where = s.get("where") != null ? (String) s.get("where") : null;
            x.centre = s.get("centre") != null ? (String) s.get("centre") : "ESA";
            x.orderCol = s.get("orderCol") != null ? (String) s.get("orderCol") : null;
            sources.add(x);
         }
      } catch (Exception ex) {
         status.setText("Could not read the archive registry: " + ex.getMessage());
      }
   }

   private static String str(Object o) { return o == null ? "" : o.toString(); }

   /** Tiny JSON reader (objects, arrays, strings, numbers, true/false/null). */
   static final class Json {
      private final String s;
      private int i;
      Json(String s) { this.s = s; }
      Object parse() { Object v = value(); return v; }
      private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
      private Object value() {
         ws();
         char c = s.charAt(i);
         if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
               ws(); String k = string(); ws(); i++; // ':'
               m.put(k, value()); ws();
               if (s.charAt(i++) == '}') return m;
            }
         }
         if (c == '[') {
            List<Object> l = new ArrayList<Object>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
               l.add(value()); ws();
               if (s.charAt(i++) == ']') return l;
            }
         }
         if (c == '"') return string();
         int st = i;
         while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
         String t = s.substring(st, i);
         if ("null".equals(t)) return null;
         if ("true".equals(t)) return Boolean.TRUE;
         if ("false".equals(t)) return Boolean.FALSE;
         return Double.valueOf(t);
      }
      private String string() {
         StringBuilder b = new StringBuilder();
         i++; // opening quote
         while (s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\') {
               char n = s.charAt(i++);
               switch (n) {
                  case 'n': b.append('\n'); break;
                  case 't': b.append('\t'); break;
                  case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                  default: b.append(n);
               }
            } else b.append(c);
         }
         i++;
         return b.toString();
      }
   }

   /** Minimal "text changed" hook without anonymous-class boilerplate. */
   static final class DocumentListenerAdapter implements javax.swing.event.DocumentListener {
      private final Runnable r;
      private DocumentListenerAdapter(Runnable r) { this.r = r; }
      static void install(JTextField f, Runnable r) { f.getDocument().addDocumentListener(new DocumentListenerAdapter(r)); }
      public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
      public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
      public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
   }

   /** Opens the window (one at a time). */
   private static ArchivesDialog instance;
   static void open(Aladin aladin) {
      if (instance == null) instance = new ArchivesDialog(aladin.f != null ? aladin.f : null, aladin);
      boolean fresh = !instance.isVisible();
      instance.setVisible(true);
      instance.toFront();
      if (fresh && instance.aladin != null) { instance.useCurrentView(); instance.scanField(false); }
   }
}
