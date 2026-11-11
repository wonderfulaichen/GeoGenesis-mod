package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 64-block far-field export with an explicit status per arm and per call.
 * Never coerces a failure into a number: failures are written as the exception type plus message.
 */
public final class DoubleFarBlock {
    private static final long SEED = 9139912035078620160L;

    public static void main(String[] a) throws Exception {
        System.exit(run(a[0], Path.of(a[1])));
    }

    /** @return number of failing double-arm samples (0 = all 64 points produced finite values). */
    static int run(String configPath, Path out) throws Exception {
        Files.createDirectories(out);
        ContinentField c;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(configPath, TomlFormat.instance())) {
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            c = new ContinentField(GeoGenesisConfig.INSTANCE.buildParams());
            c.seed(SEED);
        }
        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        FullMorphLand ref = new FullMorphLand(cat);
        DoubleGalleryCandidate cand = new DoubleGalleryCandidate(cat, c::sample, SEED);
        OceanAssemblyCandidate refOcean =
                new OceanAssemblyCandidate(ref::e, c::sample, SEED + 3).enableCoastalDetail(SEED);

        StringBuilder sb = new StringBuilder("x,z,"
                + "floatLandE,floatLandStatus,doubleLandE,doubleLandStatus,"
                + "floatWorldE,floatWorldStatus,doubleWorldE,doubleWorldStatus,"
                + "doubleRepeatExact,doubleWarpInverseResidualBlocks\n");
        int[] ok = new int[5];
        int[] bad = new int[5];
        double maxResidual = 0;
        for (long bx = 1_875_000L; bx < 1_875_000L + 64; bx++) {
            double x = bx + 0.5, z = 1_875_000.5;
            Probe fl = val(() -> ref.e(x, z));
            Probe dl = val(() -> cand.land.e(x, z));
            Probe fw = val(() -> refOcean.e(x, z));
            Probe dw = val(() -> cand.e(x, z));
            Probe dw2 = val(() -> cand.e(x, z));
            boolean repeat = dw.ok() && dw2.ok() && dw.value == dw2.value;
            double residual = Double.NaN;
            if (bx == 1_875_000L) {
                DoubleBoundaryGeometry g = cand.land.tileAt(x, z).geometry();
                double[] u = g.warpForward(x, z);
                residual = g.warpInverse(u[0], u[1], x, z)[2];
                maxResidual = residual;
            }
            Probe[] all = {fl, dl, fw, dw};
            for (int i = 0; i < all.length; i++) {
                if (all[i].ok()) ok[i]++; else bad[i]++;
            }
            sb.append(String.format(Locale.ROOT, "%.1f,%.1f,%s,%s,%s,%s,%s,%s,%s,%s,%b,%s%n",
                    x, z, fl.num(), fl.status(), dl.num(), dl.status(), fw.num(), fw.status(),
                    dw.num(), dw.status(), repeat,
                    Double.isFinite(residual) ? String.format(Locale.ROOT, "%.6g", residual) : "n/a"));
        }
        Files.writeString(out.resolve("far-block.csv"), sb.toString());
        String summary = String.format(Locale.ROOT,
                "far-block points=64%n  float land  ok=%d fail=%d%n  double land ok=%d fail=%d%n"
                        + "  float world ok=%d fail=%d%n  double world ok=%d fail=%d%n"
                        + "  double warp-inverse residual at first point = %.6g blocks%n",
                ok[0], bad[0], ok[1], bad[1], ok[2], bad[2], ok[3], bad[3], maxResidual);
        Files.writeString(out.resolve("far-block.txt"), summary);
        System.out.print(summary);
        return bad[1] + bad[3];
    }

    /** Registers this runner's own failures with the A/B log. */
    static void contribute(String configPath, Path out, java.util.List<String> log) throws Exception {
        int failures = run(configPath, out);
        log.add(String.format(Locale.ROOT, "far-block = %s%n", out.resolve("far-block.txt")));
        if (failures > 0) log.add("far-block doubleFailures=" + failures + '\n');
    }

    private record Probe(boolean ok, double value, String error) {
        String num() { return ok ? String.format(Locale.ROOT, "%.12g", value) : ""; }
        String status() { return ok ? "ok" : error; }
    }

    private static Probe val(java.util.function.DoubleSupplier s) {
        try {
            double v = s.getAsDouble();
            return Double.isFinite(v) ? new Probe(true, v, "")
                    : new Probe(false, v, "non-finite");
        } catch (RuntimeException e) {
            return new Probe(false, Double.NaN, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private DoubleFarBlock() { }
}
