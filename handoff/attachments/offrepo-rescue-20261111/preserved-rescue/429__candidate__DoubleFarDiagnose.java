package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import java.util.Locale;

/** Prints the exact far-field failure of both arms instead of collapsing it into NaN. */
public final class DoubleFarDiagnose {
    public static void main(String[] a) throws Exception {
        CommentedFileConfig cfg = CommentedFileConfig.of(a[0], TomlFormat.instance());
        cfg.load();
        GeoGenesisConfig.SPEC.setConfig(cfg);
        ContinentField c = new ContinentField(GeoGenesisConfig.INSTANCE.buildParams());
        c.seed(9139912035078620160L);
        RecipeMorphCatalog cat = new RecipeMorphCatalog(9139912035078620160L);
        FullMorphLand ref = new FullMorphLand(cat);
        DoubleGalleryCandidate cand = new DoubleGalleryCandidate(cat, c::sample, 9139912035078620160L);
        double[][] pts = {{1875000.5, 1875000.5}, {65536.5, 65536.5}, {262144.5, 262144.5},
                {1048576.5, 1048576.5}, {300.5, -700.5}};
        for (double[] p : pts) {
            System.out.printf(Locale.ROOT, "x=%.1f z=%.1f%n", p[0], p[1]);
            report("  float land ", () -> ref.e(p[0], p[1]));
            report("  float world", () -> ref.coveredHeight(p[0], p[1]));
            report("  double land", () -> cand.land.e(p[0], p[1]));
            report("  double world", () -> cand.e(p[0], p[1]));
        }
    }

    private static void report(String label, java.util.function.DoubleSupplier s) {
        try {
            double v = s.getAsDouble();
            System.out.printf(Locale.ROOT, "%s = %.12g%s%n", label, v, Double.isFinite(v) ? "" : "  <-- NON-FINITE");
        } catch (RuntimeException e) {
            System.out.println(label + " THROWS " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private DoubleFarDiagnose() { }
}
