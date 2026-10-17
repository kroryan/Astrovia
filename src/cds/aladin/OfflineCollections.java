// Astrovia: offline collections (HiPS folders and catalogue files kept on this computer).
// Part of Astrovia, an independent community project based on Aladin (CDS), GNU GPL v3.
package cds.aladin;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import cds.mocmulti.MocItem;

/**
 * Collections that live on this computer ("offline") and are shown in the Available data tree.
 *
 * <p>Two kinds are supported, each in its own top-level branch of the tree:
 * <ul>
 *   <li><b>HiPS offline</b>: a folder that already holds a HiPS (it has a {@code properties} file),
 *       for instance one saved by the HiPS downloader, or a folder that contains several of them.</li>
 *   <li><b>Catalogs offline</b>: a catalogue file (VOTable, CSV, FITS table...), for instance one saved
 *       by the VizieR / Simbad downloader. Opening it loads the file as a catalogue plane.</li>
 * </ul>
 * The list is remembered in {@code ~/.astrovia/offline.txt} and put back into the tree at every start,
 * so nothing has to be dragged in again. An offline HiPS remembers which online collection it is a copy
 * of, which allows the "prefer offline copies" switch to open the local one instead of the remote one.
 */
final class OfflineCollections {
   private OfflineCollections() { }

   static final String HIPS_BRANCH = "HiPS offline";
   static final String CAT_BRANCH = "Catalogs offline";

   /** Property that marks an offline record: "hips" or "catalog". */
   static final String KEY = "astrovia_offline";
   /** Key (contains "url" so Directory accepts the record as accessible) holding the catalogue file path. */
   static final String KEY_FILE = "astrovia_offline_url";
   /** ID of the online collection an offline HiPS is a copy of (when known). */
   static final String KEY_MIRROR = "astrovia_mirror_of";
   /** Plain folder of an offline HiPS (opened with the engine's native local loader, see TreeObjDir.loadHips). */
   static final String KEY_PATH = "astrovia_offline_path";

   /** How the Available data tree is organised: see Organizer (0 default, 1 by wavelength, 2 by mission). */
   private static int organization = 0;

   /** Which collections the tree shows: everything, online only, or offline only. */
   static final int ALL = 0, ONLINE = 1, OFFLINE = 2;

   static final class Entry {
      final char kind; // 'H' = HiPS folder, 'C' = catalogue file
      final String path;
      final String label;

      Entry(char kind, String path, String label) { this.kind = kind; this.path = path; this.label = label; }

      boolean exists() { return new File(path).exists(); }

      String id() {
         String base = label.replaceAll("[^A-Za-z0-9._-]+", "_");
         // two different folders with the same name must not collide
         String h = Integer.toHexString(path.hashCode() & 0xffff);
         return "Offline/" + (kind == 'H' ? "HiPS/" : "Catalogs/") + base + "_" + h;
      }
   }

   private static final List<Entry> entries = new ArrayList<Entry>();
   private static int sourceMode = ALL;
   private static boolean preferOffline = false;

   static {
      load();
   }

   // ----------------------------------------------------------------------- persistence

   private static File dir() { return new File(System.getProperty("user.home"), Aladin.CACHE); }
   private static File listFile() { return new File(dir(), "offline.txt"); }
   private static File prefsFile() { return new File(dir(), "offline.properties"); }

   private static synchronized void load() {
      entries.clear();
      File f = listFile();
      if (f.isFile()) {
         try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
               String[] p = line.split("\t");
               if (p.length >= 3 && (p[0].equals("H") || p[0].equals("C"))) entries.add(new Entry(p[0].charAt(0), p[1], p[2]));
            }
         } catch (IOException e) { /* start empty */ }
      }
      Properties pr = new Properties();
      try (FileInputStream in = new FileInputStream(prefsFile())) { pr.load(in); } catch (IOException e) { /* defaults */ }
      preferOffline = "true".equals(pr.getProperty("prefer"));
      try { sourceMode = Math.max(0, Math.min(2, Integer.parseInt(pr.getProperty("source", "0")))); } catch (Exception e) { sourceMode = ALL; }
      try { organization = Math.max(0, Math.min(2, Integer.parseInt(pr.getProperty("organization", "0")))); } catch (Exception e) { organization = 0; }
   }

   private static synchronized void save() {
      try {
         dir().mkdirs();
         try (PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(new FileOutputStream(listFile()), "UTF-8"))) {
            for (Entry e : entries) w.println(e.kind + "\t" + e.path + "\t" + e.label);
         }
         Properties pr = new Properties();
         pr.setProperty("prefer", String.valueOf(preferOffline));
         pr.setProperty("source", String.valueOf(sourceMode));
         pr.setProperty("organization", String.valueOf(organization));
         try (FileOutputStream out = new FileOutputStream(prefsFile())) { pr.store(out, "Astrovia offline collections"); }
      } catch (IOException e) { /* preferences are optional */ }
   }

   static synchronized List<Entry> list() { return new ArrayList<Entry>(entries); }

   static synchronized boolean has(char kind, String path) {
      for (Entry e : entries) if (e.kind == kind && e.path.equals(path)) return true;
      return false;
   }

   static synchronized int getSourceMode() { return sourceMode; }
   static synchronized void setSourceMode(int m) { sourceMode = m; save(); }
   static synchronized int getOrganization() { return organization; }
   static synchronized void setOrganization(int m) { organization = m; save(); }
   static synchronized boolean isPreferOffline() { return preferOffline; }
   static synchronized void setPreferOffline(boolean b) { preferOffline = b; save(); }

   // --------------------------------------------------------------------------- finding

   /**
    * HiPS folders in [folder]: the folder itself when it has a HiPS {@code properties} file, otherwise the
    * folders below it (up to 3 levels) that have one. Tile folders are never entered.
    */
   static List<File> findHips(File folder) {
      List<File> out = new ArrayList<File>();
      scan(folder, 0, out);
      return out;
   }

   private static void scan(File d, int depth, List<File> out) {
      if (d == null || !d.isDirectory() || depth > 3) return;
      if (isHipsFolder(d)) { out.add(d); return; }
      File[] kids = d.listFiles();
      if (kids == null) return;
      java.util.Arrays.sort(kids);
      for (File k : kids) {
         String n = k.getName();
         if (k.isDirectory() && !n.startsWith(".") && !n.startsWith("Norder") && !n.startsWith("Dir")) scan(k, depth + 1, out);
      }
   }

   static boolean isHipsFolder(File d) {
      File p = new File(d, "properties");
      if (!p.isFile() || p.length() == 0) return false;
      try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(p), "UTF-8"))) {
         String line;
         while ((line = r.readLine()) != null) if (line.startsWith("hips_")) return true;
      } catch (IOException e) { /* not readable */ }
      return false;
   }

   // --------------------------------------------------------------------------- records

   /** The text of the properties record that describes [e] to the Available data tree, or null if it is gone. */
   static String record(Entry e) {
      if (!e.exists()) return null;
      StringBuilder s = new StringBuilder();
      if (e.kind == 'H') {
         File props = new File(e.path, "properties");
         String title = e.label, mirror = null;
         StringBuilder rest = new StringBuilder();
         try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(props), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
               int i = line.indexOf('=');
               String k = i > 0 ? line.substring(0, i).trim() : "";
               String v = i > 0 ? line.substring(i + 1).trim() : "";
               if (k.equals("obs_title") && v.length() > 0) { title = v; continue; }
               if (k.equals("ID") || k.equals("creator_did") || k.equals("publisher_did") || k.equals("obs_id")
                     || k.equals("creator_id") || k.equals("publisher_id")) {
                  if (mirror == null && v.length() > 0) mirror = v.replace("ivo://", "");
                  continue;
               }
               if (k.equals("client_category") || k.equals("hips_service_url") || k.startsWith("hips_service_url_")
                     || k.equals("obs_collection") || k.equals("hips_status") || k.equals("TIMESTAMP")
                     || k.equals("moc_access_url") || k.equals("hips_progenitor_url")) continue;
               if (line.trim().length() > 0) rest.append(line).append('\n');
            }
         } catch (IOException ex) {
            return null;
         }
         s.append("ID = ").append(e.id()).append('\n');
         s.append("obs_title = ").append(title).append('\n');
         s.append("client_category = ").append(HIPS_BRANCH).append('\n');
         s.append("client_sort_key = 00-00\n"); // first branches of the tree
         s.append("hips_service_url = ").append(fileUrl(e.path)).append('\n');
         s.append(KEY).append(" = hips\n");
         s.append(KEY_PATH).append(" = ").append(e.path).append('\n');
         if (mirror != null) s.append(KEY_MIRROR).append(" = ").append(mirror).append('\n');
         s.append(rest);
      } else {
         s.append("ID = ").append(e.id()).append('\n');
         s.append("obs_title = ").append(e.label).append('\n');
         s.append("obs_description = Catalogue file ").append(new File(e.path).getName()).append('\n');
         s.append("dataproduct_type = catalog\n");
         s.append("client_category = ").append(CAT_BRANCH).append('\n');
         s.append("client_sort_key = 00-01\n");
         s.append(KEY).append(" = catalog\n");
         s.append(KEY_FILE).append(" = ").append(e.path).append('\n');
      }
      s.append('\n');
      return s.toString();
   }

   /** The GLU layer only accepts URLs (a bare path gives "no protocol"): file:///... with no trailing slash. */
   static String fileUrl(String path) {
      String u = new File(path).toURI().toString();
      if (u.startsWith("file:/") && !u.startsWith("file://")) u = "file://" + u.substring(5);
      while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
      return u;
   }

   /** All the records, as one stream the tree can read. Entries whose folder or file is gone are skipped. */
   static synchronized String allRecords() {
      StringBuilder s = new StringBuilder();
      for (Entry e : entries) {
         String r = record(e);
         if (r != null) s.append(r);
      }
      return s.toString();
   }

   static boolean isOffline(MyProperties p) {
      return p != null && p.getProperty(KEY) != null;
   }

   // ---------------------------------------------------------------------------- actions

   /** Remembers the HiPS found in [folder] and shows them in the tree now. @return how many were added. */
   static int addHipsFolder(final Aladin aladin, File folder) {
      List<File> found = findHips(folder);
      StringBuilder recs = new StringBuilder();
      int n = 0;
      synchronized (OfflineCollections.class) {
         for (File f : found) {
            String path = f.getAbsolutePath();
            if (!has('H', path)) entries.add(new Entry('H', path, f.getName()));
            Entry e = find('H', path);
            String r = record(e);
            if (r != null) { recs.append(r); n++; }
         }
         save();
      }
      if (n > 0) aladin.directory.addOfflineRecords(recs.toString());
      return n;
   }

   /** Remembers a catalogue file and shows it in the tree now. */
   static boolean addCatalog(Aladin aladin, File file, String label) {
      if (file == null || !file.isFile()) return false;
      String path = file.getAbsolutePath();
      if (label == null || label.trim().length() == 0) {
         label = file.getName();
         int i = label.lastIndexOf('.');
         if (i > 0) label = label.substring(0, i);
      }
      String r;
      synchronized (OfflineCollections.class) {
         if (!has('C', path)) entries.add(new Entry('C', path, label));
         save();
         r = record(find('C', path));
      }
      if (r == null) return false;
      aladin.directory.addOfflineRecords(r);
      return true;
   }

   private static Entry find(char kind, String path) {
      for (Entry e : entries) if (e.kind == kind && e.path.equals(path)) return e;
      return null;
   }

   /** Forgets [e] (the files stay on disk) and removes it from the tree. */
   static void remove(Aladin aladin, Entry e) {
      synchronized (OfflineCollections.class) {
         for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
            Entry x = it.next();
            if (x.kind == e.kind && x.path.equals(e.path)) it.remove();
         }
         save();
      }
      aladin.directory.removeOfflineRecord(e.id());
   }

   /** Removes from a MultiMoc iteration the offline ones (used before they are put back at start-up). */
   static List<String> offlineIds(Iterator<MocItem> it) {
      List<String> ids = new ArrayList<String>();
      while (it.hasNext()) {
         MocItem mi = it.next();
         if (mi.prop != null && mi.prop.getProperty(KEY) != null) ids.add(mi.mocId);
      }
      return ids;
   }

   // -------------------------------------------------------------------- online -> offline

   /**
    * The id to open for the online collection [id]: its offline copy when "prefer offline" is on and a
    * copy is known (the properties of a downloaded HiPS remember the collection they came from).
    */
   static synchronized String redirect(String id) {
      if (!preferOffline || id == null) return id;
      String key = normalise(id);
      for (Entry e : entries) {
         if (e.kind != 'H' || !e.exists()) continue;
         String m = mirrorOf(e);
         if (m != null && normalise(m).equals(key)) return e.id();
      }
      return id;
   }

   /** Folder of the offline copy to open instead of the online collection [id], or null (no copy / switch off). */
   static synchronized String redirectPath(String id) {
      String r = redirect(id);
      if (r == null || r.equals(id)) return null;
      for (Entry e : entries) if (e.kind == 'H' && e.id().equals(r)) return e.path;
      return null;
   }

   private static String normalise(String s) {
      return s.replace("ivo://", "").trim().toLowerCase(Locale.ROOT);
   }

   private static String mirrorOf(Entry e) {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(new File(e.path, "properties")), "UTF-8"))) {
         String line;
         while ((line = r.readLine()) != null) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            if (k.equals("ID") || k.equals("creator_did") || k.equals("publisher_did")) return line.substring(i + 1).trim();
         }
      } catch (IOException ex) { /* no mirror info */ }
      return null;
   }

   /** Quick sanity check used by tests and the manager: a stream of records can be parsed. */
   static ByteArrayInputStream asStream(String records) throws IOException {
      return new ByteArrayInputStream(records.getBytes("UTF-8"));
   }
}
