package com.geogenesis.worldgen.terrain.candidate;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 分场诊断：把复刻链的每个中间节点单独输出，用于逐段核对复刻是否正确。
 *
 * <p>输出（同一坐标范围）：{@code mask / hills / detail / shape / height}。</p>
 */
public final class FieldDiagnostic {

    private static final String[] FIELDS = {"mask", "hills", "detail", "shape", "height"};

    private FieldDiagnostic() { }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int size = Integer.parseInt(args[1]);
        int step = Integer.parseInt(args[2]);
        double cx = Double.parseDouble(args[3]);
        double cz = Double.parseDouble(args[4]);
        double hs = Double.parseDouble(args[5]);
        Files.createDirectories(out);

        BadlandsCandidate candidate = new BadlandsCandidate();
        candidate.seed(9139912035078620160L);

        // 参考算子的 scale 以方块为单位，故这里直接用块坐标（不除 horizontalScale）。
        double[][] values = new double[FIELDS.length][size * size];
        for (int z = 0; z < size; z++) {
            double wz = cz + (z - size / 2) * (double) step;
            for (int x = 0; x < size; x++) {
                double wx = cx + (x - size / 2) * (double) step;
                int k = z * size + x;
                values[0][k] = candidate.debugMask(wx, wz);
                values[1][k] = candidate.debugHills(wx, wz);
                values[2][k] = candidate.debugDetail(wx, wz);
                values[3][k] = candidate.debugShape(wx, wz);
                values[4][k] = candidate.height01(wx, wz);
            }
        }
        for (int i = 0; i < FIELDS.length; i++) write(out.resolve(FIELDS[i] + ".f64be.raw"), values[i]);
        Files.writeString(out.resolve("geometry.json"), String.format(java.util.Locale.ROOT,
                "{\"size\":%d,\"stepBlocks\":%d,\"fields\":[\"mask\",\"hills\",\"detail\",\"shape\",\"height\"]}%n",
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