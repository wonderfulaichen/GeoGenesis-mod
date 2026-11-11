package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * The one Terrace instance the census cannot evaluate is the <b>scalar shaper</b> that
 * {@code RecipeMorphCatalog} builds as {@code new Terrace((v, z) -> v, ...)}: its input is a
 * bare float identity lambda, so no double coordinate ever reaches it — it is fed {@code (raw, 0)}.
 *
 * <p>This check proves that substituting an explicit double identity for that lambda reproduces the
 * float output, so the isolation copy can use a declared identity instead of guessing the lambda body.</p>
 */
public final class DoubleTerraceCheck {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        RefNoise.Node floatShaper = new RefNoise.Terrace((v, z) -> v,
                RecipeMorphCatalog.TERRACE_RAMP, RecipeMorphCatalog.TERRACE_CLIFF,
                RecipeMorphCatalog.TERRACE_RAMP_HEIGHT, RecipeMorphCatalog.TERRACE_BLEND,
                RecipeMorphCatalog.TERRACE_STEPS, 0f, RecipeMorphCatalog.PLATEAU_TERRACE_IN_MAX, false);
        RefNoise.Node doubleShaper = new RefNoise.Terrace(
                new DualCoordinateCall.DualNode((v, z) -> v, (v, z) -> v),
                RecipeMorphCatalog.TERRACE_RAMP, RecipeMorphCatalog.TERRACE_CLIFF,
                RecipeMorphCatalog.TERRACE_RAMP_HEIGHT, RecipeMorphCatalog.TERRACE_BLEND,
                RecipeMorphCatalog.TERRACE_STEPS, 0f, RecipeMorphCatalog.PLATEAU_TERRACE_IN_MAX, false);
        double maxDiff = 0;
        double worst = Double.NaN;
        int points = 2001;
        for (int i = 0; i < points; i++) {
            double v = i / (double) (points - 1);
            double f = floatShaper.compute((float) v, 0f);
            double d = DoubleTreeFixed.eval(doubleShaper, v, 0);
            double diff = Math.abs(f - d);
            if (diff > maxDiff) { maxDiff = diff; worst = v; }
        }
        System.out.printf(Locale.ROOT,
                "terrace-shaper points=%d maxAbsDiff=%.3g at source=%.6f (step spacing %.3f)%n",
                points, maxDiff, worst, RecipeMorphCatalog.PLATEAU_TERRACE_IN_MAX / 3.0);
        // NOT bit-exact and not claimed to be: the float path rounds the source and every
        // intermediate to float, the double path does not. The bound only says the two
        // implementations are the same formula, i.e. the difference stays far below one step.
        boolean ok = maxDiff < 1e-5;
        System.out.println(ok
                ? "TERRACE SHAPER PASS (same formula; residual is float rounding, NOT bit-exact)"
                : "TERRACE SHAPER FAIL");
        System.exit(ok ? 0 : 2);
    }

    private DoubleTerraceCheck() { }
}
