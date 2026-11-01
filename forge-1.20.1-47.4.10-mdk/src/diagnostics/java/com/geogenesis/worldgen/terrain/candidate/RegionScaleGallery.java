package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 仅区域格距1200/600的同窗口实验；直接采样沙箱世界入口，不加工高度。 */
public final class RegionScaleGallery {
    private static final int N = RegionalMorphGallery.SIZE;
    private static final double X0 = RegionalMorphGallery.X0, Z0 = RegionalMorphGallery.Z0;
    private static final double STEP = RegionalMorphGallery.STEP, END = (N - 1) * STEP;
    private final HeightCurve curve = new HeightCurve(TerrainParams.defaults(), -64, 320);
    private final RegionalMorphWorld a = new RegionalMorphWorld(new RecipeMorphCatalog(RegionalMorphGallery.SEED));
    private final RegionalMorphWorld b = new RegionalMorphWorld(new RecipeMorphCatalog(RegionalMorphGallery.SEED), 600);

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        RegionScaleGallery g = new RegionScaleGallery();
        g.export(g.a, 1200, out.resolve("arm-1200"));
        g.export(g.b, 600, out.resolve("arm-600"));
        g.profiles(out);
        System.out.println("REGION SCALE GALLERY PASS");
    }

    private void export(RegionalMorphWorld w, int scale, Path out) throws Exception {
        Files.createDirectories(out);
        long start = System.nanoTime(), siteSamples = 0, sitePure = 0, siteActive = 0;
        int siteMax = 0;
        try (var height = stream(out, "height.f64be"); var type = stream(out, "type.u8");
             var active = stream(out, "active.u8"); var sea = stream(out, "sea-weight.f64be")) {
            for (int j = 0; j < N; j++) {
                double z = Z0 + j * STEP;
                for (int i = 0; i < N; i++) {
                    double x = X0 + i * STEP;
                    var tile = w.tileAt(x, z);
                    var s = w.sample(tile, x, z);
                    double h = curve.heightFromE(s.e());
                    if (!Double.isFinite(h)) throw new AssertionError("nonfinite " + x + "," + z);
                    height.writeDouble(h); type.writeByte(s.dominant());
                    active.writeByte(s.active()); sea.writeDouble(s.seaWeight());
                    // 同一50块网格测几何候选数，不把同配方合并后的一候选冒充纯区域内部。
                    if (i % 10 == 0 && j % 10 == 0) {
                        int count = FullMorphLand.activeCount(tile.original().weights(x, z));
                        siteSamples++; siteActive += count; siteMax = Math.max(siteMax, count);
                        if (count == 1) sitePure++;
                    }
                }
                if (j % 200 == 0) System.out.println("scale=" + scale + " row=" + j);
            }
        }
        Files.writeString(out.resolve("sampling-stats.txt"), w.stats()
                + "exportNanos=" + (System.nanoTime() - start) + "\nsiteGridStep=50\nsiteGridSamples=" + siteSamples
                + "\nsitePure=" + sitePure + "\nsiteActiveMean=" + (double) siteActive / siteSamples
                + "\nsiteActiveMax=" + siteMax + "\n");
        Files.writeString(out.resolve("meta.json"), "{\"seed\":\"" + RegionalMorphGallery.SEED
                + "\",\"size\":1401,\"step\":5,\"origin\":[-3642.5,-3365.5],\"center\":[-140,137],"
                + "\"scale\":" + scale + ",\"seaLevel\":63,\"bandTotal\":240,\"coastProcessing\":false,"
                + "\"jvmProperties\":[\"file.encoding=UTF-8\"],\"roiPixelBox\":[0,0,620,660]}");
        adjacency(w, out);
    }

    private void adjacency(RegionalMorphWorld w, Path out) throws Exception {
        List<String> csv = new ArrayList<>(List.of("cellAX,cellAZ,cellBX,cellBZ,recipeA,recipeB"));
        Set<String> seen = new HashSet<>();
        for (int tz = FullMorphLand.tileIndex(Z0); tz <= FullMorphLand.tileIndex(Z0 + END); tz++) {
            for (int tx = FullMorphLand.tileIndex(X0); tx <= FullMorphLand.tileIndex(X0 + END); tx++) {
                var t = w.tileAt(FullMorphLand.center(tx), FullMorphLand.center(tz));
                for (var e : t.original().geometry().edges()) {
                    if (!visible(e)) continue;
                    long ka = e.a.key(), kb = e.b.key();
                    String key = Math.min(ka, kb) + ":" + Math.max(ka, kb);
                    if (seen.add(key)) csv.add(e.a.cellX() + "," + e.a.cellZ() + "," + e.b.cellX()
                            + "," + e.b.cellZ() + "," + t.recipes()[e.a.index()] + "," + t.recipes()[e.b.index()]);
                }
            }
        }
        Files.write(out.resolve("adjacency.csv"), csv);
    }

    private static boolean visible(LocalBoundaryGeometry.Edge e) {
        for (int i = 1; i < e.wx.length; i++) {
            // Liang-Barsky裁剪：只计世界窗口中有非零边段的真实邻接。
            double dx = e.wx[i] - e.wx[i - 1], dz = e.wz[i] - e.wz[i - 1];
            double[] p = {-dx, dx, -dz, dz};
            double[] q = {e.wx[i - 1] - X0, X0 + END - e.wx[i - 1],
                    e.wz[i - 1] - Z0, Z0 + END - e.wz[i - 1]};
            double lo = 0, hi = 1;
            boolean valid = true;
            for (int k = 0; k < 4; k++) {
                if (p[k] == 0) { if (q[k] < 0) valid = false; }
                else if (p[k] < 0) lo = Math.max(lo, q[k] / p[k]);
                else hi = Math.min(hi, q[k] / p[k]);
            }
            if (valid && hi > lo) return true;
        }
        return false;
    }

    private void profiles(Path out) throws Exception {
        List<String> csv = new ArrayList<>(List.of("line,x,z,a,b,distance"));
        for (int k = 0; k < 6; k++) {
            int row = (int) ((k + .5) / 6 * N);
            for (int i = 0; i <= (int) END; i++)
                point(csv, "full-" + row, X0 + i, Z0 + row * STEP, i);
        }
        for (int z : new int[]{-2200, -1800, -1300})
            for (int x = -3500; x <= -800; x++) point(csv, "local-" + z, x, z, x + 3500);
        Files.write(out.resolve("profiles.csv"), csv);
    }

    private void point(List<String> csv, String line, double x, double z, double distance) {
        double ah = curve.heightFromE(a.sample(x, z).e()), bh = curve.heightFromE(b.sample(x, z).e());
        if (!Double.isFinite(ah) || !Double.isFinite(bh)) throw new AssertionError("profile nonfinite");
        csv.add(line + "," + x + "," + z + "," + ah + "," + bh + "," + distance);
    }

    private static DataOutputStream stream(Path out, String name) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name)), 1 << 16));
    }
}
