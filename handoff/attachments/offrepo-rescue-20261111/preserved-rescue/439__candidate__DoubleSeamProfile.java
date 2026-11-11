package com.geogenesis.worldgen.terrain.candidate;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.ContinentField;
import java.util.Locale;
/** Profiles the largest 1-block step of the double candidate and the float reference at the same spot. */
public final class DoubleSeamProfile {
    public static void main(String[] a) throws Exception {
        CommentedFileConfig cfg = CommentedFileConfig.of(a[0], TomlFormat.instance());
        cfg.load();
        GeoGenesisConfig.SPEC.setConfig(cfg);
        ContinentField c = new ContinentField(GeoGenesisConfig.INSTANCE.buildParams());
        c.seed(9139912035078620160L);
        RecipeMorphCatalog cat = new RecipeMorphCatalog(9139912035078620160L);
        FullMorphLand ref = new FullMorphLand(cat);
        DoubleGalleryCandidate dbl = new DoubleGalleryCandidate(cat, c::sample, 9139912035078620160L);
        double x0 = 4590.673, z0 = -2452.254;
        System.out.println("i,dx,floatCovered,doubleCovered,floatE,doubleE");
        for (int i = -4; i <= 4; i++) {
            double x = x0 + i;
            System.out.printf(Locale.ROOT, "%d,%.0f,%.9f,%.9f,%.9f,%.9f%n", i, x,
                    ref.coveredHeight(x, z0), dbl.land.coveredHeight(x, z0),
                    ref.e(x, z0), dbl.land.e(x, z0));
        }
    }
}
