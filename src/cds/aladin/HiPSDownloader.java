package cds.aladin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

/**
 * Downloads a HiPS tree (or the part of it around a region) to a local folder.
 *
 * Rewritten for robustness:
 *  - tiles are written to a ".part" file and renamed only when complete, so a cancelled or broken
 *    transfer never leaves a truncated tile that "skip existing files" would later accept;
 *  - an HTTP 404 means "no tile there" (sparse survey) and prunes the subtree, but a network error
 *    or a 5xx is retried and, if it keeps failing, counted and reported WITHOUT pruning (it used to be
 *    treated as empty sky, silently dropping the tile's whole subtree while saying "Completed!");
 *  - tile numbers are long (an int overflowed beyond order 13);
 *  - the size is estimated and the free disk space checked BEFORE starting;
 *  - progress is published once every 300 ms instead of one event per tile.
 */
public class HiPSDownloader {

    public interface HiPSDownloaderListener {
        void onProgress(int tilesDownloaded, long totalBytesDownloaded, int activeTasks);
        void onComplete();
        void onError(String message);
        /** Final summary (tiles on disk, tiles outside coverage, failures). */
        default void onSummary(String summary, boolean incomplete) { }
    }

    /** Circular region of the sky (ICRS, degrees). */
    public static final class Region {
        final double ra, dec, radius;
        public Region(double ra, double dec, double radius) { this.ra = ra; this.dec = dec; this.radius = radius; }
    }

    private enum Outcome { OK, NOT_FOUND, FAILED }

    private static final int ATTEMPTS = 3;
    public static final long MAX_TILES = 6000000L;
    private static final double HEALPIX_SIDE = Math.sqrt(Math.PI / 3.0);

    private final String baseUrl;
    private final File targetDir;
    private final int maxOrder;
    private final boolean fastCheck;
    private final int maxThreads;
    private final Region region;
    private final HiPSDownloaderListener listener;
    private ExecutorService executor;

    private String ext = "jpg";
    private boolean galactic = false;
    private final AtomicInteger downloaded = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger notFound = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();
    private volatile boolean isCancelled = false;

    public HiPSDownloader(String baseUrl, File targetDir, int maxOrder, boolean fastCheck, int maxThreads,
                          HiPSDownloaderListener listener) {
        this(baseUrl, targetDir, maxOrder, fastCheck, maxThreads, null, listener);
    }

    public HiPSDownloader(String baseUrl, File targetDir, int maxOrder, boolean fastCheck, int maxThreads,
                          Region region, HiPSDownloaderListener listener) {
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        // Plain HTTP is blocked or rewritten by many networks and the CDS serves everything over HTTPS.
        if (baseUrl.startsWith("http://")) baseUrl = "https://" + baseUrl.substring(7);
        this.baseUrl = baseUrl;
        this.targetDir = targetDir;
        this.maxOrder = maxOrder;
        this.fastCheck = fastCheck;
        this.maxThreads = Math.max(1, Math.min(32, maxThreads));
        this.region = region;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ estimate and free space

    /** Rough bytes of one 512 px tile per format (only used for the warning shown before starting). */
    private static long tileBytes(String ext) {
        if ("png".equals(ext)) return 260000L;
        if ("fits".equals(ext)) return 530000L;
        if ("webp".equals(ext)) return 40000L;
        return 45000L;
    }

    /** {tiles, bytes} needed for [maxOrder], the whole sky or a circular region. */
    public static long[] estimate(int maxOrder, Region region, String ext) {
        long tiles = 0;
        for (int o = 0; o <= maxOrder; o++) {
            long all = 12L * (1L << (2 * o));
            long n;
            if (region == null) n = all;
            else {
                double side = HEALPIX_SIDE / (double)(1L << o);
                double r = Math.min(Math.toRadians(region.radius) + side, Math.PI);
                double solid = 2 * Math.PI * (1 - Math.cos(r));
                n = Math.min(all, Math.max(1L, (long)(solid / (side * side)) + 1));
            }
            tiles += n;
        }
        return new long[] { tiles, tiles * tileBytes(ext) };
    }

    public static long freeBytes(File dir) {
        File d = dir;
        while (d != null && !d.exists()) d = d.getParentFile();
        return d == null ? Long.MAX_VALUE : d.getUsableSpace();
    }

    public static String human(long b) {
        if (b >= (1L << 30)) return String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0);
        if (b >= (1L << 20)) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        if (b >= (1L << 10)) return String.format(java.util.Locale.US, "%.0f KB", b / 1024.0);
        return b + " B";
    }

    // ------------------------------------------------------------------------------------ run

    public void start() {
        Thread t = new Thread(new Runnable() { public void run() { runAll(); } }, "Astrovia-hips-download");
        t.setDaemon(true);
        t.start();
    }

    public void cancel() {
        isCancelled = true;
        if (executor != null) executor.shutdownNow();
    }

    private void runAll() {
        try {
            if (!targetDir.isDirectory() && !targetDir.mkdirs()) throw new Exception("Cannot create " + targetDir);
            if (!targetDir.canWrite()) throw new Exception("Cannot write to " + targetDir);
            downloadProperties();

            long[] est = estimate(maxOrder, region, ext);
            if (est[0] > MAX_TILES) {
                throw new Exception("About " + est[0] + " tiles: too many for one run. Choose a lower order or only the area in view.");
            }
            long free = freeBytes(targetDir);
            if (est[1] > free * 0.9) {
                throw new Exception("Needs about " + human(est[1]) + " but only " + human(free)
                        + " are free. Choose a lower order or only the area in view.");
            }

            executor = Executors.newFixedThreadPool(maxThreads);
            for (long i = 0; i < 12; i++) queueTile(0, i);

            while (!isCancelled && pending.get() > 0) {
                Thread.sleep(300);
                updateProgress();
            }
            executor.shutdownNow();
            updateProgress();

            final String summary = (downloaded.get() + skipped.get()) + " tiles on disk (" + human(bytes.get()) + " downloaded)"
                    + (notFound.get() > 0 ? ", " + notFound.get() + " outside the survey coverage" : "")
                    + (failed.get() > 0 ? ", " + failed.get() + " FAILED - run it again to fetch the missing ones" : "");
            final boolean incomplete = failed.get() > 0;
            if (!isCancelled) {
                SwingUtilities.invokeLater(new Runnable() { public void run() {
                    listener.onSummary(summary, incomplete);
                    listener.onComplete();
                } });
            }
        } catch (final Exception e) {
            if (executor != null) executor.shutdownNow();
            if (!isCancelled) {
                SwingUtilities.invokeLater(new Runnable() { public void run() { listener.onError(e.getMessage()); } });
            }
        }
    }

    private void updateProgress() {
        final int n = downloaded.get() + skipped.get();
        final long b = bytes.get();
        final int a = active.get();
        SwingUtilities.invokeLater(new Runnable() { public void run() {
            if (listener != null) listener.onProgress(n, b, a);
        } });
    }

    /** `properties`, downloaded atomically; also tells us the tile format and the frame. */
    private void downloadProperties() throws Exception {
        File propFile = new File(targetDir, "properties");
        if (!(propFile.isFile() && propFile.length() > 0 && readFormat(propFile))) {
            URL url = new URL(baseUrl + "properties");
            HttpURLConnection conn = open(url);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("Could not download the properties file (HTTP " + code + ")");
            File part = new File(targetDir, "properties.part");
            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(part)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = is.read(buffer)) != -1) fos.write(buffer, 0, n);
            }
            if (propFile.exists()) propFile.delete();
            if (!part.renameTo(propFile)) throw new Exception("Could not save the properties file");
            if (!readFormat(propFile)) throw new Exception("That does not look like a HiPS (no hips_ properties)");
        }
    }

    /** Reads hips_tile_format (best of jpeg > png > webp > fits) and hips_frame. False if not a HiPS file. */
    private boolean readFormat(File propFile) {
        boolean isHips = false;
        String formats = "jpeg";
        try (BufferedReader reader = new BufferedReader(new FileReader(propFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf('=');
                if (idx <= 0) continue;
                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim().toLowerCase();
                if (key.startsWith("hips_")) isHips = true;
                if ("hips_tile_format".equalsIgnoreCase(key) || "format".equalsIgnoreCase(key)) formats = val;
                if ("hips_frame".equalsIgnoreCase(key) || "coordsys".equalsIgnoreCase(key)) galactic = val.startsWith("g");
            }
        } catch (IOException e) {
            return false;
        }
        String[] offered = formats.split("[,; ]+");
        String[] prio = { "jpeg", "jpg", "png", "webp", "fits" };
        String best = offered.length > 0 && offered[0].length() > 0 ? offered[0] : "jpeg";
        outer:
        for (String p : prio) for (String o : offered) if (o.equals(p)) { best = p; break outer; }
        ext = "jpeg".equals(best) ? "jpg" : best;
        return isHips;
    }

    private HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000); // a stalled server must not hang a worker forever
        return conn;
    }

    // ------------------------------------------------------------------------------- the tree

    private void queueTile(final int order, final long npix) {
        if (isCancelled || !intersects(order, npix)) return;
        pending.incrementAndGet();
        executor.submit(new Runnable() { public void run() {
            active.incrementAndGet();
            try {
                if (!isCancelled) processTile(order, npix);
            } catch (Exception e) {
                failed.incrementAndGet(); // unexpected: never lose it silently
            } finally {
                active.decrementAndGet();
                pending.decrementAndGet();
            }
        } });
    }

    private void processTile(int order, long npix) throws Exception {
        long dirY = (npix / 10000L) * 10000L;
        String relPath = "Norder" + order + "/Dir" + dirY + "/Npix" + npix + "." + ext;
        File tileFile = new File(targetDir, relPath);

        boolean present = false;
        if (fastCheck && tileFile.isFile() && tileFile.length() > 0 && !new File(tileFile.getPath() + ".part").exists()) {
            present = true;
            skipped.incrementAndGet();
        } else {
            switch (fetchTile(new URL(baseUrl + relPath), tileFile)) {
                case OK: present = true; downloaded.incrementAndGet(); break;
                case NOT_FOUND: notFound.incrementAndGet(); break;
                default: failed.incrementAndGet(); present = true; break; // do not prune on a transient error
            }
        }
        if (present && order < maxOrder && !isCancelled) {
            for (int i = 0; i < 4; i++) queueTile(order + 1, 4L * npix + i);
        }
    }

    private Outcome fetchTile(URL url, File dest) {
        for (int attempt = 0; attempt < ATTEMPTS && !isCancelled; attempt++) {
            File part = new File(dest.getPath() + ".part");
            HttpURLConnection conn = null;
            try {
                conn = open(url);
                int code = conn.getResponseCode();
                if (code == 404) return Outcome.NOT_FOUND;
                if (code != 200) throw new IOException("HTTP " + code);
                dest.getParentFile().mkdirs();
                long len = 0;
                try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(part)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = is.read(buffer)) != -1) {
                        if (isCancelled) { fos.close(); part.delete(); return Outcome.FAILED; }
                        fos.write(buffer, 0, n);
                        len += n;
                    }
                }
                long expected = conn.getContentLengthLong();
                if (len == 0 || (expected > 0 && len != expected)) throw new IOException("incomplete tile");
                if (dest.exists()) dest.delete();
                if (!part.renameTo(dest)) throw new IOException("could not move the tile into place");
                bytes.addAndGet(len);
                return Outcome.OK;
            } catch (IOException e) {
                part.delete();
                try { Thread.sleep(400L * (attempt + 1)); } catch (InterruptedException ie) { return Outcome.FAILED; }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return Outcome.FAILED;
    }

    // ---------------------------------------------------------------------------- region test

    private boolean intersects(int order, long npix) {
        if (region == null) return true;
        double[] c = center(order, npix);
        double[] v = toVec(c[0], c[1]);
        if (galactic) v = galToIcrs(v);
        double[] r = toVec(Math.toDegrees(Math.atan2(v[1], v[0])), Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, v[2])))));
        double[] rc = toVec(region.ra, region.dec);
        double d = Math.acos(Math.max(-1, Math.min(1, r[0] * rc[0] + r[1] * rc[1] + r[2] * rc[2])));
        return d <= Math.toRadians(region.radius) + HEALPIX_SIDE / (double)(1L << order);
    }

    private static double[] toVec(double raDeg, double decDeg) {
        double a = Math.toRadians(raDeg), d = Math.toRadians(decDeg), cd = Math.cos(d);
        return new double[] { cd * Math.cos(a), cd * Math.sin(a), Math.sin(d) };
    }

    /** Galactic -> ICRS (transpose of the standard ICRS -> Galactic matrix). */
    private static double[] galToIcrs(double[] g) {
        return new double[] {
            -0.0548755604162154 * g[0] + 0.4941094278755837 * g[1] - 0.8676661490190047 * g[2],
            -0.8734370902348850 * g[0] - 0.4448296299600112 * g[1] - 0.1980763734312015 * g[2],
            -0.4838350155487132 * g[0] + 0.7469822444972189 * g[1] + 0.4559837761750669 * g[2] };
    }

    private static final int[] JRLL = { 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4 };
    private static final int[] JPLL = { 1, 3, 5, 7, 0, 2, 4, 6, 1, 3, 5, 7 };

    private static long compress(long x) {
        long v = 0; int i = 0;
        while (x != 0) { if ((x & 1) != 0) v |= 1L << i; x >>>= 2; i++; }
        return v;
    }

    /** Centre (lon, lat in degrees, in the survey's own frame) of a NESTED HEALPix pixel. */
    static double[] center(int order, long ipix) {
        long nside = 1L << order, npface = nside * nside;
        int face = (int)(ipix / npface);
        long ipf = ipix % npface;
        double fx = (compress(ipf) + 0.5) / nside, fy = (compress(ipf >>> 1) + 0.5) / nside;
        double jr = JRLL[face] - fx - fy, nr, z;
        if (jr < 1.0) { nr = jr; z = 1.0 - nr * nr / 3.0; }
        else if (jr > 3.0) { nr = 4.0 - jr; z = nr * nr / 3.0 - 1.0; }
        else { nr = 1.0; z = (2.0 - jr) * 2.0 / 3.0; }
        double t = JPLL[face] * nr + fx - fy;
        if (t < 0) t += 8;
        if (t >= 8) t -= 8;
        double phi = nr < 1e-15 ? 0.0 : (Math.PI / 4.0 * t) / nr;
        return new double[] { Math.toDegrees(phi), Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, z)))) };
    }
}
