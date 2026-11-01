package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** A/C 原始高度重放与海岸带作用域审计；不改生成器，不覆盖原始数据。 */
public final class CoastDistributionAudit {
    private static final long SEED = 9139912035078620160L;
    private static final int N = 1401;
    private static final double X0 = -3642.5, Z0 = -3365.5, STEP = 5;
    private static final StringBuilder REPORT = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path input = Path.of(args[0]), out = Path.of(args[1]), log = Path.of(args[2]);
        Files.createDirectories(out);
        UnifiedWorld a = world(UnifiedWorld.Arm.BASELINE), c = world(UnifiedWorld.Arm.COAST_DIST);
        var ag = (CoastalAssembler.LandGrid) field(a, "baseGrid");
        var cg = (CoastalAssembler.LandGrid) field(c, "baseGrid");
        var ad = (CoastalAssembler.DistField) field(a, "distField");
        var cd = (CoastalAssembler.DistField) field(c, "distField");
        emit("baseGrid_bit_diffs", differences(ag.e, cg.e));
        emit("toSea_bit_diffs", differences(ad.toSea, cd.toSea));
        emit("toLand_bit_diffs", differences(ad.toLand, cd.toLand));
        exportDistance(out, ag, ad);
        distanceSanity();
        Method band = UnifiedWorld.class.getDeclaredMethod("bandAt", double.class, double.class);
        band.setAccessible(true);
        HeightCurve curve = new HeightCurve(TerrainParams.defaults(), -64, 320);
        double[] worst = scan(input, out, a, c, ag, cg, ad, cd, band, curve);
        describe(a, c, ag, ad, band, curve, worst[0], worst[1]);
        profile(out, a, c, ag, ad, band, curve, worst[0], worst[1]);
        Files.writeString(out.resolve("summary.txt"), REPORT);
        Files.writeString(log, "\n[CoastDistributionAudit H1/H2/H3]\n" + REPORT,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (worst[2] != 1) throw new AssertionError("scope/replay audit failed");
    }

    private static double[] scan(Path input, Path out, UnifiedWorld a, UnifiedWorld c,
            CoastalAssembler.LandGrid ag, CoastalAssembler.LandGrid cg,
            CoastalAssembler.DistField ad, CoastalAssembler.DistField cd,
            Method band, HeightCurve curve) throws Exception {
        long replayA = 0, replayC = 0, changed = 0, outside = 0, outsideChanged = 0;
        long bandDiff = 0, sameRecipeChanged = 0, landChanged = 0, landOutsideChanged = 0;
        long landToWater = 0, waterToLand = 0, coastPoints = 0;
        long[] coastA = new long[5], coastC = new long[5];
        long[][] pairCount = new long[5][5];
        double[][] pairMax = new double[5][5];
        double maxDelta = -1, maxX = 0, maxZ = 0, farthest = 0, sumLand = 0;
        double[] landDelta = new double[N * N];
        int landN = 0;
        try (DataInputStream ai = data(input.resolve("arm-BASELINE/Y-world.f64be"));
             DataInputStream ci = data(input.resolve("arm-COAST_DIST/Y-world.f64be"))) {
            for (int j = 0; j < N; j++) {
                double z = Z0 + j * STEP;
                for (int i = 0; i < N; i++) {
                    double x = X0 + i * STEP, ya = ai.readDouble(), yc = ci.readDouble();
                    double ea = a.e(x, z), ec = c.e(x, z);
                    if (bits(ya) != bits(curve.heightFromE(ea))) replayA++;
                    if (bits(yc) != bits(curve.heightFromE(ec))) replayC++;
                    double ba = (double) band.invoke(a, x, z), bc = (double) band.invoke(c, x, z);
                    if (bits(ba) != bits(bc)) bandDiff++;
                    double d = ad.dSigned(x, z, ag.at(x, z));
                    boolean in = Math.abs(d) < ba, diff = bits(ya) != bits(yc);
                    int ra = a.pickCoast(x, z).id, rc = c.pickCoast(x, z).id;
                    if (!in) outside++;
                    else { coastPoints++; coastA[ra]++; coastC[rc]++; }
                    if (ya >= 63) {
                        double delta = Math.abs(yc - ya);
                        landDelta[landN++] = delta;
                        sumLand += delta;
                        if (diff) { landChanged++; if (!in) landOutsideChanged++; }
                    }
                    if (ya >= 63 && yc < 63) landToWater++;
                    if (ya < 63 && yc >= 63) waterToLand++;
                    if (diff) {
                        changed++;
                        if (!in) outsideChanged++;
                        if (ra == rc) sameRecipeChanged++;
                        pairCount[ra][rc]++;
                        double delta = Math.abs(yc - ya);
                        pairMax[ra][rc] = Math.max(pairMax[ra][rc], delta);
                        farthest = Math.max(farthest, Math.abs(d));
                        if (delta > maxDelta) { maxDelta = delta; maxX = x; maxZ = z; }
                    }
                }
                if (j % 200 == 0) System.out.println("row=" + j);
            }
            if (ai.read() != -1 || ci.read() != -1) throw new AssertionError("extra input bytes");
        }
        java.util.Arrays.sort(landDelta, 0, landN);
        emit("replay_A_bit_diffs", replayA); emit("replay_C_bit_diffs", replayC);
        emit("band_bit_diffs", bandDiff); emit("changed_pixels", changed);
        emit("outside_band_pixels", outside); emit("outside_band_changed", outsideChanged);
        emit("same_recipe_changed", sameRecipeChanged); emit("A_land_changed", landChanged);
        emit("A_land_outside_changed", landOutsideChanged); emit("A_land_count", landN);
        emit("A_land_mean_abs_delta_Y", sumLand / landN);
        emit("A_land_P99_abs_delta_Y", landDelta[(int) (.99 * (landN - 1))]);
        emit("land_to_water", landToWater); emit("water_to_land", waterToLand);
        emit("coast_band_pixels", coastPoints); emit("farthest_changed_abs_d", farthest);
        emit("max_abs_delta_Y", maxDelta);
        for (int k = 0; k < 5; k++) {
            emit("coast_band_A_" + CoastRecipeCatalog.Recipe.of(k), coastA[k]);
            emit("coast_band_C_" + CoastRecipeCatalog.Recipe.of(k), coastC[k]);
        }
        boolean pass = replayA == 0 && replayC == 0 && bandDiff == 0 && outside > 0
                && changed > 0 && outsideChanged == 0 && sameRecipeChanged == 0
                && differences(ag.e, cg.e) == 0 && differences(ad.toSea, cd.toSea) == 0
                && differences(ad.toLand, cd.toLand) == 0;
        emit("scope_audit_pass", pass);
        pairs(out, pairCount, pairMax);
        return new double[]{maxX, maxZ, pass ? 1 : 0};
    }

    private static void pairs(Path out, long[][] pairCount, double[][] pairMax) throws Exception {
        StringBuilder csv = new StringBuilder("from,to,count,max_abs_delta_Y\n");
        for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) if (pairCount[i][j] > 0)
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%.12f%n",
                    CoastRecipeCatalog.Recipe.of(i), CoastRecipeCatalog.Recipe.of(j),
                    pairCount[i][j], pairMax[i][j]));
        Files.writeString(out.resolve("pairs.csv"), csv);
    }

    private static void profile(Path out, UnifiedWorld a, UnifiedWorld c,
            CoastalAssembler.LandGrid ag, CoastalAssembler.DistField ad, Method band,
            HeightCurve curve, double maxX, double maxZ) throws Exception {
        StringBuilder csv = new StringBuilder("x,z,d,band,t,recipe_A,recipe_C,Y_A,Y_C,Y_land\n");
        for (double x = maxX - 160; x <= maxX + 160; x += 1) {
            double d = ad.dSigned(x, maxZ, ag.at(x, maxZ));
            double b = (double) band.invoke(a, x, maxZ);
            csv.append(String.format(Locale.ROOT, "%.3f,%.3f,%.9f,%.9f,%.9f,%s,%s,%.9f,%.9f,%.9f%n",
                    x, maxZ, d, b, Math.abs(d) / b, a.pickCoast(x, maxZ), c.pickCoast(x, maxZ),
                    curve.heightFromE(a.e(x, maxZ)), curve.heightFromE(c.e(x, maxZ)),
                    curve.heightFromE(land().e(x, maxZ))));
        }
        Files.writeString(out.resolve("worst-profile.csv"), csv);
    }

    private static void describe(UnifiedWorld a, UnifiedWorld c, CoastalAssembler.LandGrid g,
                                 CoastalAssembler.DistField df, Method band, HeightCurve curve,
                                 double x, double z) throws Exception {
        double d = df.dSigned(x, z, g.at(x, z)), b = (double) band.invoke(a, x, z);
        double lh = land().e(x, z), t = Math.abs(d) / b;
        var cat = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        emit("worst_x", x); emit("worst_z", z); emit("worst_d", d); emit("worst_band", b);
        emit("worst_t", t); emit("worst_recipe_A", a.pickCoast(x, z));
        emit("worst_recipe_C", c.pickCoast(x, z)); emit("worst_land_e", lh);
        emit("worst_land_Y", curve.heightFromE(lh));
        emit("worst_A_profile_e", cat.height01(a.pickCoast(x, z), x, z, t, lh));
        emit("worst_C_profile_e", cat.height01(c.pickCoast(x, z), x, z, t, lh));
        emit("worst_Y_A", curve.heightFromE(a.e(x, z)));
        emit("worst_Y_C", curve.heightFromE(c.e(x, z)));
    }

    private static void exportDistance(Path out, CoastalAssembler.LandGrid g,
                                       CoastalAssembler.DistField df) throws Exception {
        double[][] arrays = {g.e, df.toSea, df.toLand};
        String[] names = {"base-grid", "to-sea", "to-land"};
        for (int k = 0; k < arrays.length; k++) {
            try (var os = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                    Files.newOutputStream(out.resolve(names[k] + ".f64be"))))) {
                for (double value : arrays[k]) os.writeDouble(value);
            }
        }
        Files.writeString(out.resolve("grid-meta.json"), String.format(Locale.ROOT,
                "{\"n\":%d,\"x0\":%.9f,\"z0\":%.9f,\"cell\":%.9f}%n",
                g.n, g.x0, g.z0, g.cell));
    }

    private static void distanceSanity() {
        var g = new CoastalAssembler.LandGrid(101, 0, 0, 8);
        java.util.Arrays.fill(g.e, 1);
        g.e[0] = -1;
        var df = CoastalAssembler.buildDistField(g);
        emit("synthetic_100_cells_expected_blocks", 800);
        emit("synthetic_100_cells_actual_blocks", df.sea(800, 0));
        // 这是诊断反例，不是作用域门禁；必须如实记录旧算法的错误，不将其包装为 PASS。
        emit("distance_is_euclidean", Math.abs(df.sea(800, 0) - 800) < 1e-9);
    }

    private static FullMorphLand land;
    private static FullMorphLand land() {
        if (land == null) land = new FullMorphLand(new RecipeMorphCatalog(SEED));
        return land;
    }
    private static UnifiedWorld world(UnifiedWorld.Arm arm) {
        return new UnifiedWorld(new FullMorphLand(new RecipeMorphCatalog(SEED)),
                new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET),
                new OceanRecipeCatalog(SEED + OceanRecipeCatalog.SEED_OFFSET, true), SEED, arm);
    }
    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private static DataInputStream data(Path p) throws Exception {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(p), 1 << 16));
    }
    private static long bits(double v) { return Double.doubleToLongBits(v); }
    private static long differences(double[] a, double[] b) {
        if (a.length != b.length) throw new AssertionError("length");
        long n = 0; for (int i = 0; i < a.length; i++) if (bits(a[i]) != bits(b[i])) n++; return n;
    }
    private static void emit(String key, Object value) {
        String line = key + "=" + value + "\n"; REPORT.append(line); System.out.print(line);
    }
}
