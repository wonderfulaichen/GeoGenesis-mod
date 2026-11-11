package com.geogenesis.worldgen.terrain.candidate;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 台地三段隔离诊断：把 {@code valley → valleyBase → terrace} 分别输出，
 * 用于定位"密集窄坎 / 孤立尖峰"到底来自哪一步。
 *
 * <p>判据只能看图：如果 {@code valleyBase} 本身已经是破碎的窄条，
 * 问题在输入；如果它平滑而 {@code terrace} 出现窄坎，问题在 Terrace 的索引口径。</p>
 */
public final class PlateauStageDiagnostic {

    private static final String[] FIELDS = {"valley", "valleyBase", "terrace"};

    private PlateauStageDiagnostic() { }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int size = Integer.parseInt(args[1]);
        int step = Integer.parseInt(args[2]);
        double cx = Double.parseDouble(args[3]);
        double cz = Double.parseDouble(args[4]);
        boolean geo = args.length > 5 && args[5].equals("geo");
        Files.createDirectories(out);

        PlateauCandidate candidate = new PlateauCandidate(geo
                ? PlateauCandidate.Variant.GEO : PlateauCandidate.Variant.REF);
        candidate.seed(9139912035078620160L);

        double[][] values = new double[FIELDS.length][size * size];
        for (int z = 0; z < size; z++) {
            double wz = cz + (z - size / 2.0) * step;
            for (int x = 0; x < size; x++) {
                double wx = cx + (x - size / 2.0) * step;
                int k = z * size + x;
                values[0][k] = candidate.debugValley(wx, wz);
                values[1][k] = candidate.debugBase(wx, wz);
                values[2][k] = candidate.debugTerrace(wx, wz);
            }
        }
        for (int i = 0; i < FIELDS.length; i++) write(out.resolve(FIELDS[i] + ".f64be.raw"), values[i]);
        Files.writeString(out.resolve("geometry.json"), String.format(java.util.Locale.ROOT,
                "{\"size\":%d,\"stepBlocks\":%d,\"fields\":[\"valley\",\"valleyBase\",\"terrace\"]}%n",
                size, step));
        System.out.println("out=" + out.toAbsolutePath());
    }

    private static void write(Path path, double[] values) throws Exception {
        try (DataOutputStream stream = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {
            for (double v : values) {
                if (!Double.isFinite(v)) throw new AssertionError("non-finite in " + path);
                stream.writeDouble(v);
            }
        }
    }
}