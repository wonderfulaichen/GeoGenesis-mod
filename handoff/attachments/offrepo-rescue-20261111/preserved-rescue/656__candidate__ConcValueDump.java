package com.geogenesis.worldgen.terrain.candidate;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Bit-exact value corpus of the full land+sea chain, written as raw doubles.
 *
 * <p>Purpose: a before/after diff of the PS cache lifecycle must show ZERO changed bits.
 * The corpus deliberately spans several tiles, crosses tile boundaries, includes negative
 * coordinates and far-field points, and runs an aggressive {@code System.gc()} pass in the
 * middle so a weak-entry cache would be forced to recompute.</p>
 */
public final class ConcValueDump {

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "values.f64be");
        boolean gcPressure = args.length > 1 && args[1].equals("gc");
        int n = 240;

        RecipeMorphCatalog catalog = new RecipeMorphCatalog(RecipeMorphCatalog.GALLERY_SEED);
        DoubleFullMorphLand land = new DoubleFullMorphLand(catalog);
        DoubleOceanAssemblyCandidate ocean =
                new DoubleOceanAssemblyCandidate(land::e, ConcHarness::continent,
                        RecipeMorphCatalog.GALLERY_SEED + 3).enableCoastalDetail(RecipeMorphCatalog.GALLERY_SEED);

        double[][] pts = new double[n][];
        long s = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < n; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            double ux = ((s >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
            s = s * 6364136223846793005L + 1442695040888963407L;
            double uz = ((s >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
            // Half within the canonical window, half far-field (still inside int lattice range).
            pts[i] = i % 2 == 0
                    ? new double[]{-2600 + ux * 5200, -2600 + uz * 5200}
                    : new double[]{-900000 + ux * 1800000, -900000 + uz * 1800000};
        }
        // Hard tile-boundary probes.
        for (int i = 0; i < 8; i++) {
            pts[i] = new double[]{1024.0 * (i - 3) + 0.5, 1024.0 * (i % 3 - 1) - 0.5};
        }

        try (DataOutputStream o = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(out)))) {
            for (int i = 0; i < n; i++) {
                double x = pts[i][0], z = pts[i][1];
                o.writeLong(Double.doubleToRawLongBits(land.regionHeight(x, z)));
                o.writeLong(Double.doubleToRawLongBits(land.coveredHeight(x, z)));
                o.writeLong(Double.doubleToRawLongBits(land.e(x, z)));
                o.writeLong(Double.doubleToRawLongBits(land.mask(x, z)));
                double[] w = land.weights(x, z);
                for (double v : w) o.writeLong(Double.doubleToRawLongBits(v));
                o.writeLong(Double.doubleToRawLongBits(ocean.e(x, z)));
                o.writeLong(Double.doubleToRawLongBits(ocean.heightBlocks(x, z)));
                o.writeInt(ocean.sea(x, z).ordinal());
                // Pressure point: a GC here must not change any subsequent value.
                if (i == n / 2 && gcPressure) {
                    for (int g = 0; g < 3; g++) { System.gc(); Thread.sleep(40); }
                }
            }
        }
        DoubleFullMorphLand.Stats st = land.stats();
        System.out.println("dumped=" + n + " gcPressure=" + gcPressure
                + " tilesBuilt=" + st.tilesBuilt() + " evictions=" + st.tileEvictions()
                + " tilesCached=" + st.tilesCached() + " bytes=" + Files.size(out));
    }

    private ConcValueDump() { }
}
