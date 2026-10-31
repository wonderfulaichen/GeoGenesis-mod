package com.geogenesis.worldgen.terrain.candidate;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** 原样比较三版未缓存逐点调用，不改变实现或进行性能优化。 */
public final class MorphChoicePerfProbe {
    private static volatile double sink;
    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        HeightCurve curve;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(Path.of(args[0]), TomlFormat.instance())) {
            cfg.load();
            if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new AssertionError("config correction");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            curve = new HeightCurve(GeoGenesisConfig.INSTANCE.buildParams(), -64, 320);
        }
        StringBuilder csv = new StringBuilder("pair,scope,points,rounds,A_median_ms,B_median_ms,C_median_ms,B_over_A,C_over_A,C_over_B\n");
        for (var pair : new FourPairMorphCandidate.Pair[]{FourPairMorphCandidate.Pair.PLAIN_MOUNTAINS,
                FourPairMorphCandidate.Pair.MOUNTAINS_BADLANDS}) {
            FourPairMorphCandidate a = new FourPairMorphCandidate(SEED, pair);
            AdaptiveWidthCandidate b = new AdaptiveWidthCandidate(SEED, pair, curve, -140);
            OffsetTransitionCandidate c = new OffsetTransitionCandidate(SEED, pair, curve, -140);
            measure(pair, "common_transition", 110, a, b, c, csv);
            measure(pair, "whole_window", 600, a, b, c, csv);
        }
        Files.writeString(Path.of(args[1]), csv);
        System.out.print(csv);
        if (!Double.isFinite(sink)) throw new AssertionError("nonfinite benchmark");
    }

    private static void measure(FourPairMorphCandidate.Pair pair, String scope, double halfSpan,
                                FourPairMorphCandidate a, AdaptiveWidthCandidate b,
                                OffsetTransitionCandidate c, StringBuilder csv) {
        int count = 400, rounds = 5;
        double[] xs = new double[count], zs = new double[count];
        Random random = new Random(18471);
        for (int i = 0; i < count; i++) {
            xs[i] = -140 + (random.nextDouble() * 2 - 1) * halfSpan;
            zs[i] = 137 + random.nextDouble() * 600 - 300;
        }
        for (int i = 0; i < 150; i++) {
            for (int mode = 0; mode < 3; mode++) sink = sample(mode, xs[i], zs[i], a, b, c);
        }
        double[][] elapsed = new double[3][rounds];
        for (int trial = 0; trial < rounds; trial++) {
            for (int turn = 0; turn < 3; turn++) {
                int mode = (trial + turn) % 3;
                long start = System.nanoTime(); double sum = 0;
                for (int i = 0; i < count; i++) sum += sample(mode, xs[i], zs[i], a, b, c);
                elapsed[mode][trial] = (System.nanoTime() - start) / 1e6;
                sink = sum;
            }
        }
        for (double[] row : elapsed) Arrays.sort(row);
        double ta = elapsed[0][2], tb = elapsed[1][2], tc = elapsed[2][2];
        csv.append(String.format("%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                pair, scope, count, rounds, ta, tb, tc, tb / ta, tc / ta, tc / tb));
        System.out.println("measured " + pair + " " + scope);
    }

    private static double sample(int mode, double x, double z, FourPairMorphCandidate a,
                                 AdaptiveWidthCandidate b, OffsetTransitionCandidate c) {
        return switch (mode) {
            case 0 -> a.spatial(x, z, -140);
            case 1 -> b.sample(x, z);
            default -> c.sample(x, z);
        };
    }
}
