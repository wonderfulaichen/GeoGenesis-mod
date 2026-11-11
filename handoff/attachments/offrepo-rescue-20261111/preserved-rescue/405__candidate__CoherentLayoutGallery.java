package com.geogenesis.worldgen.terrain.candidate;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** S0/S1只导出原始赢家标签；不求高度、不接浅海修正或新权重。 */
public final class CoherentLayoutGallery {
    private static final long SEED = RegionalMorphGallery.SEED;
    private static final int N = 1401;
    private static final String[] ARMS = {"arm-a1200", "arm-b600", "arm-c600"};
    private final CoherentRegionDistribution coherent = new CoherentRegionDistribution(SEED);

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        var g = new CoherentLayoutGallery();
        Path root = Path.of(args[0]);
        g.window(root, 5, -3642.5, -3365.5);
        g.window(root.resolve("extended"), 10, -7142.5, -6865.5);
        System.out.println("COHERENT LAYOUT GALLERY PASS");
    }

    private void window(Path root, double step, double ox, double oz) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("meta.json"), "{\"seed\":\"" + SEED + "\",\"size\":1401,\"step\":" + step
                + ",\"origin\":[" + ox + "," + oz + "],\"roi\":[0,0,620,660],\"microC\":600,\"macroC\":1200,"
                + "\"shallowMatching\":false,\"internalEdgeSuppression\":false,\"heightEvaluated\":false,"
                + "\"jvmProperties\":[\"file.encoding=UTF-8\"]}");
        export(root, 0, 1200, step, ox, oz);
        export(root, 1, 600, step, ox, oz);
    }

    private void export(Path root, int arm, int scale, double step, double ox, double oz) throws Exception {
        long start = System.nanoTime();
        RegionalMorphWorld legacy = new RegionalMorphWorld(new RecipeMorphCatalog(SEED), scale);
        byte[] types = new byte[N * N], cTypes = new byte[N * N];
        long[] cells = new long[N * N];
        boolean[] written = new boolean[N * N];
        List<String> sites = new ArrayList<>(List.of("cell,cellX,cellZ,recipe,macroX,macroZ"));
        List<String> cSites = new ArrayList<>(sites), edges = new ArrayList<>(List.of("cellA,cellB,recipeA,recipeB"));
        List<String> cEdges = new ArrayList<>(edges);
        Set<Long> seenSites = new HashSet<>(); Set<String> seenEdges = new HashSet<>();
        double end = (N - 1) * step;
        int tiles = 0;
        for (int tz = FullMorphLand.tileIndex(oz); tz <= FullMorphLand.tileIndex(oz + end); tz++) {
            for (int tx = FullMorphLand.tileIndex(ox); tx <= FullMorphLand.tileIndex(ox + end); tx++) {
                LocalBoundaryGeometry g = legacy.originalLand().tileAt(FullMorphLand.center(tx), FullMorphLand.center(tz)).geometry();
                int[] ids = new int[g.sites().size()], cIds = new int[ids.length];
                for (var site : g.sites()) {
                    ids[site.index()] = legacy.choose(g, site).recipe();
                    var choice = scale == 600 ? coherent.choose(site) : null;
                    cIds[site.index()] = choice == null ? ids[site.index()] : choice.recipe();
                    if (seenSites.add(site.key())) {
                        sites.add(site.key() + "," + site.cellX() + "," + site.cellZ() + "," + ids[site.index()] + ",0,0");
                        if (choice != null) cSites.add(site.key() + "," + site.cellX() + "," + site.cellZ() + ","
                                + choice.recipe() + "," + choice.anchor().cellX() + "," + choice.anchor().cellZ());
                    }
                }
                fill(g, ids, cIds, tx, tz, step, ox, oz, types, cTypes, cells, written);
                for (var e : g.edges()) if (visible(e, ox, oz, end)) {
                    String key = Math.min(e.a.key(), e.b.key()) + ":" + Math.max(e.a.key(), e.b.key());
                    if (seenEdges.add(key)) {
                        edges.add(e.a.key() + "," + e.b.key() + "," + ids[e.a.index()] + "," + ids[e.b.index()]);
                        cEdges.add(e.a.key() + "," + e.b.key() + "," + cIds[e.a.index()] + "," + cIds[e.b.index()]);
                    }
                }
                tiles++;
            }
            System.out.println("layout step=" + step + " scale=" + scale + " tz=" + tz + " tiles=" + tiles);
        }
        for (boolean v : written) if (!v) throw new AssertionError("uncovered sample");
        save(root.resolve(ARMS[arm]), types, cells, sites, edges, System.nanoTime() - start);
        if (scale == 600) save(root.resolve(ARMS[2]), cTypes, cells, cSites, cEdges, System.nanoTime() - start);
    }

    private static void fill(LocalBoundaryGeometry g, int[] ids, int[] cIds, int tx, int tz, double step,
                             double ox, double oz, byte[] types, byte[] cTypes, long[] cells, boolean[] written) {
        int i0 = Math.max(0, (int) Math.ceil((tx * 1024.0 - ox) / step));
        int i1 = Math.min(N, (int) Math.ceil(((tx + 1) * 1024.0 - ox) / step));
        int j0 = Math.max(0, (int) Math.ceil((tz * 1024.0 - oz) / step));
        int j1 = Math.min(N, (int) Math.ceil(((tz + 1) * 1024.0 - oz) / step));
        for (int j = j0; j < j1; j++) for (int i = i0; i < i1; i++) {
            var winner = g.exactWinner(ox + i * step, oz + j * step);
            int k = j * N + i;
            if (written[k]) throw new AssertionError("duplicate sample");
            types[k] = (byte) ids[winner.index()]; cTypes[k] = (byte) cIds[winner.index()];
            cells[k] = winner.key(); written[k] = true;
        }
    }

    private static boolean visible(LocalBoundaryGeometry.Edge e, double ox, double oz, double end) {
        for (int i = 1; i < e.wx.length; i++) {
            double dx = e.wx[i] - e.wx[i - 1], dz = e.wz[i] - e.wz[i - 1];
            double[] p = {-dx, dx, -dz, dz};
            double[] q = {e.wx[i - 1] - ox, ox + end - e.wx[i - 1], e.wz[i - 1] - oz, oz + end - e.wz[i - 1]};
            double lo = 0, hi = 1; boolean valid = true;
            for (int k = 0; k < 4; k++) {
                if (p[k] == 0) { if (q[k] < 0) valid = false; }
                else if (p[k] < 0) lo = Math.max(lo, q[k] / p[k]);
                else hi = Math.min(hi, q[k] / p[k]);
            }
            if (valid && hi > lo) return true;
        }
        return false;
    }

    private static void save(Path out, byte[] types, long[] cells, List<String> sites, List<String> edges, long nanos) throws Exception {
        Files.createDirectories(out); Files.write(out.resolve("type.u8"), types);
        try (var stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("cells.i64be"))))) {
            for (long cell : cells) stream.writeLong(cell);
        }
        Files.write(out.resolve("sites.csv"), sites); Files.write(out.resolve("adjacency.csv"), edges);
        Files.writeString(out.resolve("stats.txt"), "exportNanos=" + nanos + "\nnoHeightEvaluation=true\n");
    }
}
