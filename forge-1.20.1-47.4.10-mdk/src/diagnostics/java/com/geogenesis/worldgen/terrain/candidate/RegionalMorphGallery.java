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

/** P3同窗口出图入口。旧完整A、旧无岸基底、新布局硬切与新节点过渡各自列明。 */
public final class RegionalMorphGallery {
    static final long SEED = RecipeMorphCatalog.GALLERY_SEED;
    static final int SIZE = 1401;
    static final double STEP = 5, X0 = -3642.5, Z0 = -3365.5;
    private final RegionalMorphWorld world = new RegionalMorphWorld(new RecipeMorphCatalog(SEED));
    private final HeightCurve curve = new HeightCurve(TerrainParams.defaults(), -64, 320);
    private final RefNoise.Node c = RefNoise.Perlin.ofScale(base(SEED) + 7000, 4000f, 6);
    private final RefNoise.Node selector = RefNoise.Perlin.ofScale(base(SEED) + 7400, 2600f, 3);

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        new RegionalMorphGallery().run(out);
    }

    private void run(Path out) throws Exception {
        long start = System.nanoTime();
        List<String> profiles = new ArrayList<>();
        profiles.add("line,x,z,a,base,hard,morph,distance");
        double[] a = read(out.resolve("baseline-a.f64be"));
        double[] oldBase = read(out.resolve("baseline-base.f64be"));
        Set<Integer> rows = new HashSet<>();
        for (int k = 0; k < 6; k++) rows.add((int) ((k + .5) / 6 * SIZE));
        try (var hard = stream(out, "regional-hard.f64be"); var morph = stream(out, "regional-morph.f64be");
             var ta = stream(out, "type-a.u8"); var tb = stream(out, "type-b.u8");
             var th = stream(out, "type-hard.u8"); var sea = stream(out, "sea-weight.f64be");
             var active = stream(out, "active.u8")) {
            for (int j = 0; j < SIZE; j++) {
                double z = Z0 + j * STEP;
                for (int i = 0; i < SIZE; i++) {
                    double x = X0 + i * STEP;
                    var s = world.sample(x, z);
                    double h = curve.heightFromE(world.hardE(x, z, s.winnerRecipe()));
                    double m = curve.heightFromE(s.e());
                    if (!Double.isFinite(m) || !Double.isFinite(h)) throw new AssertionError("nonfinite");
                    hard.writeDouble(h); morph.writeDouble(m);
                    ta.writeByte(oldType(x, z)); tb.writeByte(s.dominant()); th.writeByte(s.winnerRecipe());
                    sea.writeDouble(s.seaWeight()); active.writeByte(s.active());
                    if (rows.contains(j)) add(profiles, "row-" + j, x, z, a[j * SIZE + i],
                            oldBase[j * SIZE + i], h, m, i * STEP);
                }
                if (j % 100 == 0) System.out.println("P3 row=" + j + " " + world.stats().replace('\n', ' '));
            }
        }
        Files.writeString(out.resolve("sampling-stats.txt"), world.stats() + "galleryNanos=" + (System.nanoTime() - start) + "\n");
        localProfiles(profiles, out);
        Files.write(out.resolve("profiles.csv"), profiles);
        writeSites(out);
        Files.writeString(out.resolve("meta.json"), "{\"seed\":\"" + SEED + "\",\"size\":1401,\"step\":5,"
                + "\"center\":[-140,137],\"origin\":[-3642.5,-3365.5],\"seaLevel\":63,"
                + "\"jvmProperties\":[\"file.encoding=UTF-8\"],\"coastProcessing\":false,\"bandTotal\":240}");
        System.out.println("REGIONAL MORPH GALLERY PASS");
    }

    private int oldType(double x, double z) {
        double cc = c.compute((float) x, (float) z) * 2.0 - 1;
        if (selector.compute((float) x, (float) z) < RegionalMorphWorld.seaProbability(cc)) {
            double t = RefNoise.clamp((float) (-cc / UnifiedWorld.OCEAN_C), 0, 1);
            return 18 + (int) Math.round((1 - t) * 4);
        }
        return world.originalLand().dominantEntry(x, z);
    }

    private void localProfiles(List<String> profiles, Path out) throws Exception {
        // 旧世界只用于对照，不进入P3运行链。此处读原入口，不在Python拼高度。
        UnifiedWorld old = new UnifiedWorld(world.originalLand(),
                new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET),
                new OceanRecipeCatalog(SEED + OceanRecipeCatalog.SEED_OFFSET, true), SEED);
        Set<String> selected = new HashSet<>();
        List<String> boundaries = new ArrayList<>();
        boundaries.add("kind,x,z,nx,nz,recipeA,recipeB");
        for (int tz = -3; tz <= 3; tz++) for (int tx = -3; tx <= 3; tx++) {
            var tile = world.tileAt(FullMorphLand.center(tx), FullMorphLand.center(tz));
            for (var edge : tile.original().geometry().edges()) {
                int ra = tile.recipes()[edge.a.index()], rb = tile.recipes()[edge.b.index()];
                if (ra == rb || edge.wx.length < 5) continue;
                String kind = ra < 18 && rb < 18 ? "land-land" : ra >= 18 && rb >= 18 ? "sea-sea" : "land-sea";
                if (selected.contains(kind)) continue;
                int mid = edge.wx.length / 2;
                double x = edge.wx[mid], z = edge.wz[mid];
                if (x < X0 + 240 || x > X0 + 7000 - 240 || z < Z0 + 240 || z > Z0 + 7000 - 240) continue;
                double nx = -(edge.wz[mid + 1] - edge.wz[mid - 1]), nz = edge.wx[mid + 1] - edge.wx[mid - 1];
                double len = Math.hypot(nx, nz); if (!(len > 0)) continue;
                nx /= len; nz /= len;
                selected.add(kind);
                boundaries.add(kind + "," + x + "," + z + "," + nx + "," + nz + "," + ra + "," + rb);
                for (int k = -240; k <= 240; k++) {
                    double px = x + nx * k, pz = z + nz * k;
                    var s = world.sample(px, pz);
                    add(profiles, "local-" + kind, px, pz, curve.heightFromE(old.e(px, pz)),
                            curve.heightFromE(old.baseEof(px, pz)), curve.heightFromE(world.hardE(px, pz, s.winnerRecipe())),
                            curve.heightFromE(s.e()), k);
                }
            }
        }
        if (selected.size() != 3) throw new AssertionError("missing actual boundary class " + selected);
        Files.write(out.resolve("boundary-locations.csv"), boundaries);
    }

    private void writeSites(Path out) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("cellX,cellZ,x,z,c,probability,draw,landSlot,oceanSlot,recipe");
        Set<Long> seen = new HashSet<>();
        for (int tz = -4; tz <= 3; tz++) for (int tx = -4; tx <= 3; tx++) {
            var tile = world.tileAt(FullMorphLand.center(tx), FullMorphLand.center(tz));
            for (var s : tile.original().geometry().sites()) if (seen.add(s.key())) {
                var v = world.choose(tile.original().geometry(), s);
                lines.add(v.cellX() + "," + v.cellZ() + "," + v.x() + "," + v.z() + "," + v.c()
                        + "," + v.probability() + "," + v.draw() + "," + v.landSlot() + "," + v.oceanSlot() + "," + v.recipe());
            }
        }
        Files.write(out.resolve("sites.csv"), lines);
    }

    private static void add(List<String> csv, String line, double x, double z, double a,
                            double base, double hard, double morph, double distance) {
        csv.add(line + "," + x + "," + z + "," + a + "," + base + "," + hard + "," + morph + "," + distance);
    }
    private static double[] read(Path path) throws Exception {
        var buffer = java.nio.ByteBuffer.wrap(Files.readAllBytes(path));
        if (buffer.remaining() != SIZE * SIZE * 8) throw new AssertionError("baseline size " + path);
        double[] result = new double[SIZE * SIZE];
        for (int i = 0; i < result.length; i++) result[i] = buffer.getDouble();
        return result;
    }
    private static DataOutputStream stream(Path out, String name) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name)), 1 << 16));
    }
    private static int base(long seed) { return (int) (seed ^ (seed >>> 32)); }
}
