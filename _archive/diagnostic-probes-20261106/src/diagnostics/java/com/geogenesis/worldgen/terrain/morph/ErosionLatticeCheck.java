package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.standalone.FastErosionFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Is the filter's integer lattice transparent to the demo's own negative-coordinate convention?
 *
 * <p>{@code FastErosionOriginalDemo.gridX} maps negative samples to {@code 2^29 + x}. If the filter
 * were shift-invariant by that period, evaluating a negative sample and that same sample plus
 * {@code 2^29} must give the same erosion. Any mismatch means our negative window sits on the
 * documented ambiguous {@code wrap()} path rather than on the demo's convention.</p>
 */
public final class ErosionLatticeCheck {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("refuse overwrite " + out);
        Files.createDirectories(out);
        long seed = MorphCatalog.CANONICAL_SEED;
        MorphCatalog catalog = new MorphCatalog(seed);
        MorphNoise.Warped w = (MorphNoise.Warped) catalog.entry(MorphCatalog.IDX_MOUNTAIN).rawNode();
        MorphNoise.Mul body = (MorphNoise.Mul) w.input();
        MorphNoise.PerlinRidge r0 = (MorphNoise.PerlinRidge) body.a();
        double mainScale = 1.0 / r0.frequency();
        double step = mainScale / (1165.0 / 9.0);
        MorphNoise.Node l1 = MorphNoise.warp(
                new MorphNoise.PerlinRidge(r0.seed(), r0.frequency(), 1, r0.lacunarity(), r0.gain()),
                w.wx(), w.wz(), w.pow());

        double f = 0.3346298021288687;  // L1 offline constant, unchanged
        FastErosionFilter.Fields fields = (px, pz, dst) -> {
            double c = MorphNoiseEval.eval(l1, px * step, pz * step);
            double xp = MorphNoiseEval.eval(l1, (px + 1) * step, pz * step);
            double xm = MorphNoiseEval.eval(l1, (px - 1) * step, pz * step);
            double zp = MorphNoiseEval.eval(l1, px * step, (pz + 1) * step);
            double zm = MorphNoiseEval.eval(l1, px * step, (pz - 1) * step);
            double k = f * 4e-4f;
            dst[0] = (float) (k * (xp - xm) / 2);
            dst[1] = (float) (k * (zp - zm) / 2);
            dst[2] = (float) (k * (xp + xm + zp + zm - 4 * c));
        };
        int period = 1 << 29, diff = 0, total = 0;
        double maxAbs = 0;
        float[] a = new float[3], b = new float[3];
        for (int pz = -1796; pz < -1700; pz++) {
            for (int px = -1745; px < -1649; px++) {
                FastErosionFilter.erosion(px, pz, fields, FastErosionFilter.ORIGINAL, a);
                FastErosionFilter.erosion(px + period, pz + period, fields, FastErosionFilter.ORIGINAL, b);
                total++;
                double d = Math.abs(a[0] - b[0]);
                maxAbs = Math.max(maxAbs, d);
                if (d > 1e-3) diff++;
            }
        }
        String txt = String.format(Locale.ROOT,
                "samples=%d  differing=%d  fraction=%.6f  maxAbsDeltaE=%.9f%n"
                        + "A non-zero result means wrap(negative cell) and the demo's +2^29 convention are%n"
                        + "NOT equivalent for the erosion lattice, so our negative world window is evaluated%n"
                        + "on the ambiguous path rather than the approved one.%n", total, diff,
                diff / (double) total, maxAbs);
        Files.writeString(out.resolve("lattice-check.txt"), txt);
        System.out.print(txt);
        if (diff > 0) System.exit(3);
    }
}
