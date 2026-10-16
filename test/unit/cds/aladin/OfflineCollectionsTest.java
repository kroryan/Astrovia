package cds.aladin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

/** Offline collections: finding HiPS folders, the tree records and the "prefer offline copies" redirection. */
public class OfflineCollectionsTest {
   private static File home;

   @BeforeClass
   public static void isolatedHome() throws Exception {
      // the registry lives in ~/.astrovia: use a temporary home, before the class is first loaded
      home = Files.createTempDirectory("astrovia-test").toFile();
      System.setProperty("user.home", home.getAbsolutePath());
   }

   private static File hips(String name, String extra) throws Exception {
      File d = new File(home, name);
      d.mkdirs();
      Files.write(new File(d, "properties").toPath(),
            ("obs_title = " + name + "\nhips_order = 5\nhips_frame = equatorial\nhips_tile_format = jpeg\n" + extra).getBytes("UTF-8"));
      return d;
   }

   @Test
   public void aFolderWithAHipsPropertiesFileIsAHips() throws Exception {
      assertTrue(OfflineCollections.isHipsFolder(hips("one", "")));
      assertFalse(OfflineCollections.isHipsFolder(home));
   }

   @Test
   public void aFolderOfFoldersYieldsEveryHipsAndNeverEntersTileFolders() throws Exception {
      File root = new File(home, "many");
      new File(root, "a").mkdirs();
      new File(root, "b/deep").mkdirs();
      Files.write(new File(root, "a/properties").toPath(), "hips_order = 3\n".getBytes());
      Files.write(new File(root, "b/deep/properties").toPath(), "hips_order = 3\n".getBytes());
      new File(root, "a/Norder3/Dir0").mkdirs();
      List<File> found = OfflineCollections.findHips(root);
      assertEquals(2, found.size());
   }

   @Test
   public void theRecordHasItsOwnIdentityAndAFileUrl() throws Exception {
      File d = hips("dss", "creator_did = ivo://CDS/P/DSS2/color\nhips_service_url = http://alasky/DSS\n");
      String rec = OfflineCollections.record(new OfflineCollections.Entry('H', d.getAbsolutePath(), "dss"));
      assertNotNull(rec);
      assertTrue(rec.contains("ID = Offline/HiPS/dss_"));
      assertFalse(rec.contains("creator_did"));
      assertFalse(rec.contains("http://alasky"));
      assertTrue(rec.contains("astrovia_mirror_of = CDS/P/DSS2/color"));
      assertTrue(rec.contains("hips_service_url = file:///"));
      assertTrue(rec.contains("client_category = HiPS offline"));
   }

   @Test
   @SuppressWarnings("unchecked")
   public void preferOfflineOpensTheLocalCopyOfAnOnlineCollection() throws Exception {
      File d = hips("mirror", "creator_did = ivo://CDS/P/Test/color\n");
      Field f = OfflineCollections.class.getDeclaredField("entries");
      f.setAccessible(true);
      ((List<OfflineCollections.Entry>) f.get(null)).add(new OfflineCollections.Entry('H', d.getAbsolutePath(), "mirror"));
      OfflineCollections.setPreferOffline(false);
      assertNull(OfflineCollections.redirectPath("CDS/P/Test/color"));
      OfflineCollections.setPreferOffline(true);
      assertEquals(d.getAbsolutePath(), OfflineCollections.redirectPath("CDS/P/Test/color"));
      assertEquals(d.getAbsolutePath(), OfflineCollections.redirectPath("ivo://cds/p/test/COLOR"));
      assertNull(OfflineCollections.redirectPath("CDS/P/Other/color"));
      OfflineCollections.setPreferOffline(false);
   }

   @Test
   public void aCatalogueRecordIsReachableAndKnowsItsFile() throws Exception {
      File f = new File(home, "stars.vot");
      Files.write(f.toPath(), "<VOTABLE/>".getBytes());
      String rec = OfflineCollections.record(new OfflineCollections.Entry('C', f.getAbsolutePath(), "stars"));
      assertTrue(rec.contains("client_category = Catalogs offline"));
      assertTrue(rec.contains("astrovia_offline_url = " + f.getAbsolutePath()));
      // missing file -> no record (it is skipped when the tree is built)
      assertNull(OfflineCollections.record(new OfflineCollections.Entry('C', new File(home, "gone.vot").getAbsolutePath(), "gone")));
   }
}
