package com.geogenesis.worldgen.terrain.candidate;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** 与冻结海洋源码分别编译，保留旧API的多种子逐位输出。 */
public final class EngineOceanFingerprint {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        int samples = 0;
        try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(out))) {
            for (long seed : new long[]{9139912035078620160L, 0, -419, 12345}) {
                for (boolean concept : new boolean[]{false, true}) {
                    OceanRecipeCatalog catalog = new OceanRecipeCatalog(seed + OceanRecipeCatalog.SEED_OFFSET, concept);
                    Random random = new Random(197);
                    for (int p = 0; p < 3000; p++) {
                        double x = random.nextDouble() * 14000 - 7000;
                        double z = random.nextDouble() * 14000 - 7000;
                        for (int i = 0; i < 5; i++) {
                            data.writeDouble(catalog.height01(i, x, z));
                            samples++;
                        }
                    }
                }
            }
        }
        System.out.println("OCEAN FINGERPRINT samples=" + samples + " out=" + out);
    }
}
