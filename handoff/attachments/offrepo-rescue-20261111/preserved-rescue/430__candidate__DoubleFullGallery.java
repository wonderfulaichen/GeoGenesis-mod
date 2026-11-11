package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Isolated A/B runner: float frozen gallery path vs the double-coordinate candidate. */
public final class DoubleFullGallery {
    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;
    private static final StringBuilder log = new StringBuilder();
    private static int blockers;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        HeightCurve curve;
        ContinentField continent;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(args[0], TomlFormat.instance())) {
            cfg.load();
            if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new IllegalStateException("config needs correction");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            var params = GeoGenesisConfig.INSTANCE.buildParams();
            curve = new HeightCurve(params, -64, 320);
            continent = new ContinentField(params);
            continent.seed(SEED);
        }
        RecipeMorphCatalog catalog = new RecipeMorphCatalog(SEED);
        FullMorphLand reference = new FullMorphLand(catalog);
        DoubleGalleryCandidate candidate = new DoubleGalleryCandidate(catalog, continent::sample, SEED);
        OceanAssemblyCandidate referenceOcean =
                new OceanAssemblyCandidate(reference::e, continent::sample, SEED + 3).enableCoastalDetail(SEED);

        if (args.length > 2 && args[2].equals("seam")) { seamCheck(out, candidate, reference); Files.writeString(out.resolve("seam-gallery.log"), log.toString()); System.out.print(log); System.exit(blockers == 0 ? 0 : 2); }
        render(out, curve, referenceOcean, candidate);
        landAb(out, reference, candidate);
        sha(out);
        farField(out, candidate, reference, referenceOcean, args[0]);
        doubleVerdicts(reference, candidate);
        Files.writeString(out.resolve("double-gallery.log"), log.toString());
        System.out.print(log);
        System.out.println("BLOCKERS=" + blockers);
        System.exit(blockers == 0 ? 0 : 2);
    }

    private static void render(Path out, HeightCurve curve, OceanAssemblyCandidate ref, DoubleGalleryCandidate cand)
            throws Exception {
        int n = SIZE * SIZE, diff = 0, failures = 0;
        double maxAbs = 0, sumAbs = 0;
        try (DataOutputStream fc = stream(out.resolve("float-coastal.f64be"));
             DataOutputStream dc = stream(out.resolve("double-coastal.f64be"));
             DataOutputStream cov = stream(out.resolve("coverage.f64be"));
             DataOutputStream fh = stream(out.resolve("float-heights.f64be"));
             DataOutputStream dh = stream(out.resolve("double-heights.f64be"))) {
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = CX + (i - SIZE / 2.0) * STEP, z = CZ + (j - SIZE / 2.0) * STEP;
                    double fe = Double.NaN, de = Double.NaN;
                    try { fe = ref.e(x, z); } catch (RuntimeException e) { failures++; note("float " + x + "," + z + ": " + e); }
                    try { de = cand.e(x, z); } catch (RuntimeException e) { failures++; note("double " + x + "," + z + ": " + e); }
                    fh.writeDouble(curve.heightFromE(fe));
                    dh.writeDouble(curve.heightFromE(de));
                    if (Double.isFinite(fe) && Double.isFinite(de)) {
                        double d = Math.abs(fe - de);
                        if (d != 0) diff++;
                        maxAbs = Math.max(maxAbs, d);
                        sumAbs += d;
                    }
                    fc.writeDouble(fe);
                    dc.writeDouble(de);
                    cov.writeDouble(cand.land.mask(x, z));
                }
                if (j % 200 == 0) System.out.println("row=" + j + " diff=" + diff + " failures=" + failures);
            }
        }
        log.append(String.format(Locale.ROOT,
                "pixels=%d differing=%d failures=%d maxAbsE=%.9g meanAbsE=%.9g%n",
                n, diff, failures, maxAbs, sumAbs / n));
        if (failures > 0) blockers++;
    }

    /** Far-field export delegated to its own runner so per-arm status is never coerced to a number. */
    private static void farField(Path out, DoubleGalleryCandidate cand, FullMorphLand ref,
                                 OceanAssemblyCandidate refOcean, String config) throws Exception {
        int failures = DoubleFarBlock.run(config, out);
        log.append("far-block doubleFailures=").append(failures).append(" (float arm: see far-block.csv)\n");
        if (failures > 0) blockers++;
    }

    /** Order independence + one-block continuity of the double candidate, with float control. */
    private static void seamCheck(Path out, DoubleGalleryCandidate cand, FullMorphLand ref) throws Exception {
        java.util.Random rnd = new java.util.Random(4242);
        int n = 2000, repeatMismatch = 0, jumps = 0, floatJumps = 0;
        double maxJump = 0, maxFloatJump = 0;
        StringBuilder sb = new StringBuilder("x,z,first,second,delta,stepDelta,floatStepDelta\n");
        for (int i = 0; i < n; i++) {
            double x = -6000 + rnd.nextDouble() * 12000, z = -6000 + rnd.nextDouble() * 12000;
            double a = probe("seam", () -> cand.land.coveredHeight(x, z));
            cand.e(x + 37.5, z - 11.25);
            double b = probe("seam-repeat", () -> cand.land.coveredHeight(x, z));
            double step = probe("seam-step", () -> cand.land.coveredHeight(x + 1.0, z));
            double fstep = probe("seam-float-step", () -> ref.coveredHeight(x + 1.0, z));
            double fhere = probe("seam-float", () -> ref.coveredHeight(x, z));
            double delta = Math.abs(a - b);
            double stepDelta = Math.abs(step - a);
            double floatDelta = Math.abs(fstep - fhere);
            if (!(delta == 0)) repeatMismatch++;
            if (stepDelta > 0.05) jumps++;
            if (floatDelta > 0.05) floatJumps++;
            maxJump = Math.max(maxJump, stepDelta);
            maxFloatJump = Math.max(maxFloatJump, floatDelta);
            sb.append(String.format(Locale.ROOT, "%.3f,%.3f,%.12g,%.12g,%.3g,%.3g,%.3g%n",
                    x, z, a, b, delta, stepDelta, floatDelta));
        }
        Files.writeString(out.resolve("seam.csv"), sb.toString());
        log.append(String.format(Locale.ROOT,
                "seam points=%d repeatMismatch=%d stepsOver0.05E double=%d float=%d"
                        + " maxStepDeltaE double=%.6g float=%.6g%n",
                n, repeatMismatch, jumps, floatJumps, maxJump, maxFloatJump));
        if (repeatMismatch > 0) blockers++;
    }

    private static double probe(String label, java.util.function.DoubleSupplier s) {
        try { return s.getAsDouble(); }
        catch (RuntimeException e) { blockers++; note(label + " " + e); return Double.NaN; }
    }

    private static void doubleVerdicts(FullMorphLand ref, DoubleGalleryCandidate cand) {
        for (int i = 0; i < 18; i++) {
            double[] w = new double[18];
            w[i] = 1;
            try {
                double v = cand.land.heightForMerged(CX, CZ, w);
                log.append(String.format(Locale.ROOT, "entry=%d OK %.9g%n", i, v));
            } catch (RuntimeException e) {
                blockers++;
                log.append("entry=").append(i).append(" FAIL ").append(e).append('\n');
            }
        }
        try {
            log.append(String.format(Locale.ROOT, "coverage float=%.9g double=%.9g%n",
                    ref.coveredHeight(CX, CZ), cand.land.coveredHeight(CX, CZ)));
        } catch (RuntimeException e) {
            blockers++;
            log.append("coverage FAIL ").append(e).append('\n');
        }
    }

    /** Land-only A/B (no ocean assembly) compared against the frozen reference land arm. */
    private static void landAb(Path out, FullMorphLand ref, DoubleGalleryCandidate cand) throws Exception {
        int n = SIZE * SIZE, diff = 0, failures = 0;
        double maxAbs = 0, sumAbs = 0;
        try (DataOutputStream a = stream(out.resolve("float-land-e.f64be"));
             DataOutputStream b = stream(out.resolve("double-land-e.f64be"))) {
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = CX + (i - SIZE / 2.0) * STEP, z = CZ + (j - SIZE / 2.0) * STEP;
                    double fe = probe("land float", () -> ref.e(x, z));
                    double de = probe("land double", () -> cand.land.e(x, z));
                    if (!Double.isFinite(fe) || !Double.isFinite(de)) failures++;
                    else {
                        double d = Math.abs(fe - de);
                        if (d != 0) diff++;
                        maxAbs = Math.max(maxAbs, d);
                        sumAbs += d;
                    }
                    a.writeDouble(fe);
                    b.writeDouble(de);
                }
            }
        }
        log.append(String.format(Locale.ROOT,
                "land-only pixels=%d differing=%d failures=%d maxAbsE=%.9g meanAbsE=%.9g%n",
                n, diff, failures, maxAbs, sumAbs / n));
        if (failures > 0) blockers++;
    }

    /** Compare the float arm against the recorded frozen morph-coastal arm. */
    private static void sha(Path out) throws Exception {
        String expected = "7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238";
        String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(out.resolve("float-heights.f64be")))).toUpperCase(Locale.ROOT);
        String dbl = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(out.resolve("double-heights.f64be")))).toUpperCase(Locale.ROOT);
        log.append("floatArmSha=").append(actual).append(" expected=").append(expected)
           .append(" match=").append(actual.equals(expected)).append('\n');
        log.append("doubleArmSha=").append(dbl).append('\n');
    }

    private static void note(String s) {
        if (blockers < 12) log.append("  ").append(s).append('\n');
    }

    private static DataOutputStream stream(Path file) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
    }

    private DoubleFullGallery() { }
}
