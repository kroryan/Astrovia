// Astrovia: multi-archive search (observations, spectra, catalogues, alerts, solar system, exoplanets),
// visibility windows, target lists, publications and view links. Own implementation on top of the public
// IVOA protocols (TAP/ADQL, VOTable). Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
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
import javax.swing.*;

/** Window "Archives": search ESA/NASA/CDS archives around the current view and load the result as a catalogue plane. */
public class ArchivesDialog extends JDialog {

   /** One queryable table described in AlaArchives.json. */
   static final class Source {
      String id, label, mission, band, kind, table, ra, dec, tap;
      public String toString() { return label + "   [" + mission + " · " + band + "]"; }
   }

   private static final String[] KINDS = { "all", "obs", "spec", "cat", "alert", "sso", "img", "exo" };
   private static final String[] KIND_LABELS = { "All", "Observations", "Spectra", "Catalogues", "Alerts", "Solar system", "Outreach images", "Exoplanets" };

   private final Aladin aladin;
   private final List<Source> sources = new ArrayList<Source>();
   private final DefaultListModel<Source> model = new DefaultListModel<Source>();
   private final JList<Source> list = new JList<Source>(model);
   private final JComboBox<String> kindBox = new JComboBox<String>(KIND_LABELS);
   private final JTextField filter = new JTextField(14);
   private final JTextField raF = new JTextField("0", 9), decF = new JTextField("0", 9), radF = new JTextField("0.5", 6), maxF = new JTextField("2000", 6);
   private final JLabel status = new JLabel("Ready.");

   public ArchivesDialog(Window owner, Aladin aladin) {
      super(owner, "Archives - multi-mission search", ModalityType.MODELESS);
      this.aladin = aladin;
      loadSources();
      JTabbedPane tabs = new JTabbedPane();
      tabs.addTab("Search", searchPanel());
      tabs.addTab("Solar system", solarPanel());
      tabs.addTab("Visibility", visibilityPanel());
      tabs.addTab("Targets", targetsPanel());
      tabs.addTab("Publications", publicationsPanel());
      tabs.addTab("View link", linkPanel());
      setLayout(new BorderLayout(6, 6));
      add(tabs, BorderLayout.CENTER);
      status.setBorder(BorderFactory.createEmptyBorder(2, 10, 6, 10));
      add(status, BorderLayout.SOUTH);
      setSize(760, 560);
      setLocationRelativeTo(owner);
      useCurrentView();
   }

   // ------------------------------------------------------------------ search tab
   private JPanel searchPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
      top.add(new JLabel("Type:")); top.add(kindBox);
      top.add(new JLabel("Filter:")); top.add(filter);
      p.add(top, BorderLayout.NORTH);
      list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      p.add(new JScrollPane(list), BorderLayout.CENTER);
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
      DocumentListenerAdapter.install(filter, this::refilter);
      kindBox.addActionListener(e -> refilter());
      list.addMouseListener(new java.awt.event.MouseAdapter() {
         public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) doSearch(); }
      });
      refilter();
      return p;
   }

   private void refilter() {
      String k = KINDS[Math.max(0, kindBox.getSelectedIndex())];
      String f = filter.getText().trim().toLowerCase(Locale.ROOT);
      model.clear();
      for (Source s : sources) {
         if (!"all".equals(k) && !k.equals(s.kind)) continue;
         if (f.length() > 0 && !(s.label + " " + s.mission + " " + s.band).toLowerCase(Locale.ROOT).contains(f)) continue;
         model.addElement(s);
      }
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

   private void doSearch() {
      final Source s = list.getSelectedValue();
      if (s == null) { status.setText("Choose an archive from the list."); return; }
      try {
         double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim());
         double rad = Math.max(0.0005, Math.min(90.0, Double.parseDouble(radF.getText().trim())));
         int max = Math.max(1, Math.min(100000, Integer.parseInt(maxF.getText().trim())));
         String adql = String.format(Locale.US, "SELECT TOP %d * FROM %s WHERE 1=CONTAINS(POINT('ICRS',%s,%s),CIRCLE('ICRS',%.6f,%.6f,%.6f))",
               max, s.table, s.ra, s.dec, ra, dec, rad);
         String url = s.tap + "/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=votable&QUERY=" + URLEncoder.encode(adql, "UTF-8");
         loadUrl(url, s.label);
      } catch (Exception ex) {
         status.setText("Check RA, Dec, radius and max rows (numbers).");
      }
   }

   /** Downloads the VOTable to a temporary file (so errors can be shown) and loads it as a catalogue plane. */
   private void loadUrl(final String url, final String label) {
      status.setText("Querying " + label + " ...");
      new Thread(new Runnable() { public void run() {
         try {
            byte[] data = fetch(url);
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

   static byte[] fetch(String url) throws IOException {
      URLConnection c = new URL(url).openConnection();
      c.setConnectTimeout(20000);
      c.setReadTimeout(120000);
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

   // ------------------------------------------------------------------ visibility
   static final String[] CONSTRAINT_NAMES = { "JWST (Sun 85°-135°)", "Euclid (Sun ~87°-121°)", "Generic (Sun > 45°)" };
   static final double[][] CONSTRAINTS = { { 85, 135 }, { 87, 121 }, { 45, 180 } };
   private final JTextArea visOut = new JTextArea();

   private JPanel visibilityPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JComboBox<String> box = new JComboBox<String>(CONSTRAINT_NAMES);
      JButton go = new JButton("Compute for RA/Dec of the Search tab");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("Observatory:")); top.add(box); top.add(go);
      p.add(top, BorderLayout.NORTH);
      visOut.setEditable(false);
      p.add(new JScrollPane(visOut), BorderLayout.CENTER);
      go.addActionListener(e -> {
         try {
            double ra = Double.parseDouble(raF.getText().trim()), dec = Double.parseDouble(decF.getText().trim());
            double[] c = CONSTRAINTS[box.getSelectedIndex()];
            double start = Instant.now().toEpochMilli() / 86400000.0 + 2440587.5;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "Target RA %.4f Dec %+.4f%nSun angle now: %.1f deg%n%n", ra, dec, elongation(ra, dec, start)));
            double open = -1, jd = start, end = start + 365;
            int n = 0;
            for (; jd <= end; jd += 0.25) {
               double el = elongation(ra, dec, jd);
               boolean ok = el >= c[0] && el <= c[1];
               if (ok && open < 0) open = jd;
               if (!ok && open >= 0) { sb.append(window(open, jd)); open = -1; n++; }
            }
            if (open >= 0) { sb.append(window(open, end)); n++; }
            if (n == 0) sb.append("Not observable in the next 365 days with these limits.\n");
            sb.append("\nApproximate: only the Sun-angle limit is applied; real schedules also depend on roll angle, Moon and other constraints.");
            visOut.setText(sb.toString());
         } catch (Exception ex) { visOut.setText("Enter RA and Dec (degrees) in the Search tab."); }
      });
      return p;
   }

   private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

   private static String jd2s(double jd) { return DAY.format(Instant.ofEpochMilli((long) ((jd - 2440587.5) * 86400000.0))); }

   private static String window(double a, double b) { return String.format(Locale.US, "%s -> %s  (%d days)%n", jd2s(a), jd2s(b), (int) (b - a)); }

   /** Sun position (low precision, ~0.01 deg) and Sun-target angle in degrees. */
   static double elongation(double raDeg, double decDeg, double jd) {
      double n = jd - 2451545.0;
      double l = Math.toRadians((280.460 + 0.9856474 * n) % 360.0);
      double g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0);
      double lam = l + Math.toRadians(1.915) * Math.sin(g) + Math.toRadians(0.020) * Math.sin(2 * g);
      double eps = Math.toRadians(23.439 - 0.0000004 * n);
      double sra = Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam));
      double sdec = Math.asin(Math.sin(eps) * Math.sin(lam));
      double a = Math.toRadians(raDeg), d = Math.toRadians(decDeg);
      double c = Math.sin(d) * Math.sin(sdec) + Math.cos(d) * Math.cos(sdec) * Math.cos(a - sra);
      return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
   }

   // ------------------------------------------------------------------ targets
   private final DefaultListModel<String> targetModel = new DefaultListModel<String>();

   private File targetFile() { return new File(System.getProperty("user.home"), ".astrovia" + File.separator + "targets.txt"); }

   private JPanel targetsPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JTextArea names = new JTextArea(4, 30);
      p.add(new JScrollPane(names), BorderLayout.NORTH);
      final JList<String> tl = new JList<String>(targetModel);
      p.add(new JScrollPane(tl), BorderLayout.CENTER);
      JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
      JButton add = new JButton("Resolve names (SIMBAD) and add");
      JButton go = new JButton("Go to selected");
      JButton del = new JButton("Remove");
      b.add(add); b.add(go); b.add(del);
      p.add(b, BorderLayout.SOUTH);
      try {
         if (targetFile().exists()) {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(targetFile()), "UTF-8"));
            try { String ln; while ((ln = r.readLine()) != null) if (ln.trim().length() > 0) targetModel.addElement(ln.trim()); } finally { r.close(); }
         }
      } catch (IOException ex) { /* empty list */ }
      add.addActionListener(e -> {
         final String[] lines = names.getText().split("\\R");
         new Thread(new Runnable() { public void run() {
            int ok = 0, bad = 0;
            for (String n : lines) {
               if (n.trim().isEmpty()) continue;
               try {
                  String r = new String(fetch("https://cds.unistra.fr/cgi-bin/nph-sesame/-oI/SNV?" + URLEncoder.encode(n.trim(), "UTF-8")), "UTF-8");
                  java.util.regex.Matcher m = java.util.regex.Pattern.compile("%J\\s+([-+0-9.]+)\\s+([-+0-9.]+)").matcher(r);
                  if (m.find()) { final String row = n.trim() + " | " + m.group(1) + " " + m.group(2); ok++;
                     SwingUtilities.invokeLater(new Runnable() { public void run() { targetModel.addElement(row); } }); } else bad++;
               } catch (Exception ex) { bad++; }
            }
            final String msg = "Targets: " + ok + " added" + (bad > 0 ? ", " + bad + " not found" : "");
            SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText(msg); saveTargets(); } });
         } }, "Astrovia-targets").start();
      });
      go.addActionListener(e -> {
         String v = tl.getSelectedValue();
         if (v == null) return;
         String[] part = v.split("\\|");
         if (part.length > 1) aladin.execAsyncCommand(part[1].trim());
      });
      del.addActionListener(e -> { int i = tl.getSelectedIndex(); if (i >= 0) { targetModel.remove(i); saveTargets(); } });
      return p;
   }

   private void saveTargets() {
      try {
         targetFile().getParentFile().mkdirs();
         PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(targetFile()), "UTF-8"));
         try { for (int i = 0; i < targetModel.size(); i++) w.println(targetModel.get(i)); } finally { w.close(); }
      } catch (IOException ex) { /* not critical */ }
   }

   // ------------------------------------------------------------------ publications
   private JPanel publicationsPanel() {
      JPanel p = new JPanel(new BorderLayout(6, 6));
      p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      final JTextField obj = new JTextField("M 42", 18);
      JButton go = new JButton("Find publications (SIMBAD)");
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("Object:")); top.add(obj); top.add(go);
      p.add(top, BorderLayout.NORTH);
      final DefaultListModel<String> pm = new DefaultListModel<String>();
      final JList<String> pl = new JList<String>(pm);
      p.add(new JScrollPane(pl), BorderLayout.CENTER);
      p.add(new JLabel("Double-click a paper to open it in ADS."), BorderLayout.SOUTH);
      go.addActionListener(e -> {
         final String name = obj.getText().trim().replace("'", "''");
         pm.clear();
         new Thread(new Runnable() { public void run() {
            try {
               String adql = "SELECT TOP 60 ref.bibcode, ref.\"year\" AS yr, ref.title FROM ident JOIN has_ref ON ident.oidref=has_ref.oidref "
                     + "JOIN ref ON has_ref.oidbibref=ref.oidbib WHERE ident.id='" + name + "' ORDER BY yr DESC";
               String url = "https://simbad.cds.unistra.fr/simbad/sim-tap/sync?request=doQuery&lang=adql&format=tsv&query=" + URLEncoder.encode(adql, "UTF-8");
               String[] rows = new String(fetch(url), "UTF-8").split("\\R");
               for (int i = 1; i < rows.length; i++) {
                  final String row = rows[i].replace("\"", "");
                  if (row.trim().length() > 0) SwingUtilities.invokeLater(new Runnable() { public void run() { pm.addElement(row.replace('\t', ' ')); } });
               }
               final int n = Math.max(0, rows.length - 1);
               SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText(n + " publications"); } });
            } catch (final Exception ex) {
               SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText("Publications: " + ex.getMessage()); } });
            }
         } }, "Astrovia-pubs").start();
      });
      pl.addMouseListener(new java.awt.event.MouseAdapter() {
         public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() != 2 || pl.getSelectedValue() == null) return;
            String bib = pl.getSelectedValue().split("\\s+")[0];
            try { aladin.glu.showDocument("Http", "https://ui.adsabs.harvard.edu/abs/" + URLEncoder.encode(bib, "UTF-8") + "/abstract", true); } catch (Exception ex) { /* ignore */ }
         }
      });
      return p;
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

   // ------------------------------------------------------------------ registry (assets/AlaArchives.json)
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
      instance.setVisible(true);
      instance.toFront();
   }
}
