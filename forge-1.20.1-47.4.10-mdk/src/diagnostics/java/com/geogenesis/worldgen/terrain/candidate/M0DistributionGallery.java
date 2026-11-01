package com.geogenesis.worldgen.terrain.candidate;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** M0原状分布导出，不调用高度/浅海匹配/边线/逆变换；所有参数沿用S3。 */
public final class M0DistributionGallery {
    static final long SEED = 9139912035078620160L;
    static final int N = 1401;
    static final double X0 = -28140, Z0 = -27863, STEP = 40;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("usage: M0DistributionGallery NEW_OUT [landThreshold]");
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("output must be new: " + out);
        double threshold = args.length == 2 ? Double.parseDouble(args[1]) : 0;
        var sampler = new M0DistributionSampler(SEED, threshold);
        export(sampler, out, X0, Z0, STEP, threshold);
        export(sampler, out.resolve("local"), RegionalMorphGallery.X0, RegionalMorphGallery.Z0, 5, threshold);
        System.out.println("M0 DISTRIBUTION GALLERY PASS threshold=" + threshold + " (no height, no inverse)");
    }

    private static void export(M0DistributionSampler sampler, Path out, double ox, double oz, double step,
                               double threshold) throws Exception {
        Files.createDirectories(out);
        long started = System.nanoTime();
        long sea = 0, candidateTotal = 0;
        int candidateMax = 0;
        try (var types = stream(out, "type.u8"); var sides = stream(out, "side.u8");
             var cells = stream(out, "cells.i64be"); var c = stream(out, "macro-c.f64be");
             var probability = stream(out, "ocean-probability.f64be")) {
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    var p = sampler.sample(ox + i * step, oz + j * step);
                    boolean waterLabel = p.recipe() >= RegionalMorphWorld.LAND_COUNT;
                    types.writeByte(p.recipe()); sides.writeByte(waterLabel ? 1 : 0);
                    cells.writeLong(p.cell().key()); c.writeDouble(p.c());
                    probability.writeDouble(p.probability());
                    sea += waterLabel ? 1 : 0;
                    candidateTotal += p.candidates(); candidateMax = Math.max(candidateMax, p.candidates());
                }
                if (j % 350 == 0) System.out.println("M0 step=" + step + " row=" + j);
            }
        }
        String meta = "{\"seed\":\"" + SEED + "\",\"size\":1401,\"step\":" + step
                + ",\"origin\":[" + ox + "," + oz + "],\"center\":[-140,137],\"spanBlocks\":" + (N - 1) * step
                + ",\"microScale\":600,\"macroScale\":1200,\"bandTotal\":240,\"heightEvaluated\":false,"
                + "\"s3Window\":[-3642.5,-3365.5,3357.5,3634.5],\"shallowMatching\":false,"
                + "\"semantics\":\"base side labels; not final water or S2 ocean subtypes\","
                + "\"jvmArguments\":\"" + java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .getInputArguments().toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        if (threshold != 0) meta = meta.substring(0, meta.length() - 1) + ",\"landThreshold\":" + threshold + "}";
        Files.writeString(out.resolve("meta.json"), meta);
        Files.writeString(out.resolve("sampling.txt"), "samples=" + ((long) N * N) + "\nseaLabels=" + sea
                + "\ncandidateMean=" + (double) candidateTotal / (N * N) + "\ncandidateMax=" + candidateMax
                + "\nexportNanos=" + (System.nanoTime() - started) + "\nnoHeight=true\nnoInverse=true\n");
    }

    private static DataOutputStream stream(Path out, String name) throws Exception {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve(name)), 1 << 16));
    }
}
