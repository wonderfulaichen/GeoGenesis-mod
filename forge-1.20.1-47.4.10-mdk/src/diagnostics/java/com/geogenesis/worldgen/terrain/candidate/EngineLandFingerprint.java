package com.geogenesis.worldgen.terrain.candidate;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** 可用冻结旧源码独立编译的逐位指纹，不依赖新内核。 */
public final class EngineLandFingerprint {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        long[] seeds = {9139912035078620160L, 0, -419, 12345};
        int samples = 0;
        try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(out))) {
            for (long seed : seeds) {
                RecipeJunctionMorph engine = new RecipeJunctionMorph(new RecipeMorphCatalog(seed), seed);
                Random random = new Random(197);
                for (int p = 0; p < 120; p++) {
                    double x = random.nextDouble() * 14000 - 7000;
                    double z = random.nextDouble() * 14000 - 7000;
                    for (int i = 0; i < 18; i++) {
                        double[] w = new double[18];
                        w[i] = 1;
                        data.writeDouble(engine.sample(x, z, w));
                        samples++;
                    }
                    for (int k = 0; k < 12; k++) {
                        double[] w = new double[18];
                        double sum = 0;
                        for (int i = 0; i < 18; i++) {
                            w[i] = k < 6 && i > 2 ? 0 : random.nextDouble();
                            sum += w[i];
                        }
                        for (int i = 0; i < 18; i++) w[i] /= sum;
                        data.writeDouble(engine.sample(x, z, w));
                        samples++;
                    }
                }
            }
        }
        System.out.println("LAND FINGERPRINT samples=" + samples + " out=" + out);
    }
}
