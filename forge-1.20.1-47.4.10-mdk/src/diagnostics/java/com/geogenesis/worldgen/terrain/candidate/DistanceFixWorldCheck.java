package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 用同一整图入口取样，导出距离/带宽/原始类型高度供独立作用域验证。 */
public final class DistanceFixWorldCheck {
    private static final int N = 1401;
    private static final double X0 = -3642.5, Z0 = -3365.5;
    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        var morph = new FullMorphLand(new RecipeMorphCatalog(SEED));
        var world = new UnifiedWorld(morph,
                new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET),
                new OceanRecipeCatalog(SEED + OceanRecipeCatalog.SEED_OFFSET, true),
                SEED, UnifiedWorld.Arm.BOTH);
        var grid = (CoastalAssembler.LandGrid) field(world, "baseGrid");
        var df = (CoastalAssembler.DistField) field(world, "distField");
        Method band = UnifiedWorld.class.getDeclaredMethod("bandAt", double.class, double.class);
        band.setAccessible(true);
        var curve = new HeightCurve(TerrainParams.defaults(), -64, 320);
        dump(out.resolve("base-grid.f64be"), grid.e);
        dump(out.resolve("to-sea.f64be"), df.toSea);
        dump(out.resolve("to-land.f64be"), df.toLand);
        Files.writeString(out.resolve("grid-meta.json"), String.format(Locale.ROOT,
                "{\"n\":%d,\"x0\":%.1f,\"z0\":%.1f,\"cell\":%.1f}%n",
                grid.n, grid.x0, grid.z0, grid.cell));
        long outside = 0, outsideDiff = 0, coast = 0, water = 0;
        try (var y = stream(out, "Y-world"); var d = stream(out, "distance");
             var b = stream(out, "band"); var base = stream(out, "Y-base")) {
            for (int j = 0; j < N; j++) {
                double z = Z0 + j * 5;
                for (int i = 0; i < N; i++) {
                    double x = X0 + i * 5;
                    double dist = df.dSigned(x, z, grid.at(x, z));
                    double width = (double) band.invoke(world, x, z);
                    double ev = world.e(x, z), original = world.baseEof(x, z);
                    if (Math.abs(dist) >= width) {
                        outside++;
                        if (Double.doubleToLongBits(ev) != Double.doubleToLongBits(original)) outsideDiff++;
                    } else coast++;
                    if (ev < 0) water++;
                    y.writeDouble(curve.heightFromE(ev)); d.writeDouble(dist);
                    b.writeDouble(width); base.writeDouble(curve.heightFromE(original));
                }
                if (j % 200 == 0) System.out.println("row=" + j);
            }
        }
        String summary = String.format(Locale.ROOT,
                "outside=%d%noutsideBaseDiff=%d%ncoast=%d%nwater=%d%nworstDistance=%.12f%nworstY=%.12f%n",
                outside, outsideDiff, coast, water,
                df.dSigned(2522.5, -1305.5, grid.at(2522.5, -1305.5)),
                curve.heightFromE(world.e(2522.5, -1305.5)));
        Files.writeString(out.resolve("scope.txt"), summary);
        System.out.print(summary);
        profile(world, grid, df, band, curve, out);
        if (outside == 0 || coast == 0 || outsideDiff != 0) throw new AssertionError("scope failed");
        System.out.println("WORLD SCOPE PASS");
    }

    private static void profile(UnifiedWorld w, CoastalAssembler.LandGrid g,
            CoastalAssembler.DistField df, Method band, HeightCurve curve, Path out) throws Exception {
        StringBuilder csv = new StringBuilder("x,z,d,band,Y,Y_base\n");
        for (int i = -240; i <= 240; i++) {
            double x = 2522.5 + i, z = -1305.5;
            csv.append(String.format(Locale.ROOT, "%.1f,%.1f,%.12f,%.12f,%.12f,%.12f%n",
                    x, z, df.dSigned(x, z, g.at(x, z)), (double) band.invoke(w, x, z),
                    curve.heightFromE(w.e(x, z)), curve.heightFromE(w.baseEof(x, z))));
        }
        Files.writeString(out.resolve("local-profile.csv"), csv);
    }

    private static Object field(Object o, String key) throws Exception {
        Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o);
    }
    private static DataOutputStream stream(Path dir, String name) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(dir.resolve(name + ".f64be"))));
    }
    private static void dump(Path path, double[] values) throws Exception {
        try (var os = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            for (double value : values) os.writeDouble(value);
        }
    }
}
