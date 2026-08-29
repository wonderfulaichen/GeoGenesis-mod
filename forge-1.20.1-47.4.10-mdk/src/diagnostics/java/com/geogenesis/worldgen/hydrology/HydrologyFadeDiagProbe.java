package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 打印河道中心列的 fade/carved/surface 数值，定位 fade>=0.95 门控过严问题。 */
public final class HydrologyFadeDiagProbe {
    private HydrologyFadeDiagProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double horizontalScale = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int printed = 0;
        double maxFade = 0.0;
        for (int z = -256; z <= 256 && printed < 20; z += 8) {
            for (int x = -256; x <= 256 && printed < 20; x += 8) {
                HydrologyBlockSample sample = engine.sampleBlock(x, z, horizontalScale);
                if (sample == null) continue;
                double fade = sample.width() <= 0.0 ? 1.0
                        : Math.min(1.0, sample.width() / Math.max(sample.valleyWidth(), 1.0));
                maxFade = Math.max(maxFade, fade);
                if (fade < 0.5) continue;
                System.out.printf("block=(%d,%d) width=%.2f valley=%.2f fade=%.3f surface=%.1f%n",
                        x, z, sample.width(), sample.valleyWidth(), fade, sample.surfaceY());
                printed++;
            }
        }
        System.out.println("maxFade=" + maxFade);
    }
}
