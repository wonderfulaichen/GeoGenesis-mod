package com.geogenesis.worldgen.terrain.morph;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Independent read-only check of Python's decomposed-carve arrays; no terrain sampling. */
public final class DecomposedCarveProbe {
    private static final double EPS = 1e-12;
    private static final double CAP = Math.min(.08, 16.0 / 354.66);
    private static final String[] FIELDS = {
        "H0", "base", "detail", "g", "D", "candidate", "f_height", "relief_scale"
    };

    private DecomposedCarveProbe() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static DataInputStream open(Path path) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
    }

    private static void check(Path directory) throws IOException {
        DataInputStream[] streams = new DataInputStream[FIELDS.length];
        long bytes = Files.size(directory.resolve("H0.f64be"));
        require(bytes > 0 && bytes % 8 == 0, "invalid H0 file length");
        long count = bytes / 8, zeros = 0;
        double reconstructionError = 0, formulaError = 0, maxDepth = 0;
        try {
            for (int k = 0; k < streams.length; k++) {
                Path file = directory.resolve(FIELDS[k] + ".f64be");
                require(Files.size(file) == bytes, "shape mismatch: " + file);
                streams[k] = open(file);
            }
            double[] v = new double[streams.length];
            for (long i = 0; i < count; i++) {
                for (int k = 0; k < streams.length; k++) {
                    v[k] = streams[k].readDouble();
                    require(Double.isFinite(v[k]), "nonfinite " + FIELDS[k] + " at " + i);
                }
                double h0 = v[0], base = v[1], detail = v[2], g = v[3], d = v[4];
                require(g >= 0 && g <= 1 && d >= 0 && d <= CAP + EPS, "g/D bounds");
                require(v[6] >= 0 && v[6] <= 1 && v[7] >= .45 && v[7] <= 1, "factors");
                reconstructionError = Math.max(reconstructionError, Math.abs(base + detail - h0));
                formulaError = Math.max(formulaError,
                        Math.abs(base - d + detail * (1 - .70 * g) - v[5]));
                require(Math.abs(d - CAP * v[6] * v[7] * g) < EPS, "depth formula");
                if (g == 0) {
                    zeros++;
                    require(Double.doubleToRawLongBits(h0) == Double.doubleToRawLongBits(v[5]),
                            "g=0 must preserve H0 bits");
                }
                maxDepth = Math.max(maxDepth, d);
            }
            require(reconstructionError < EPS && formulaError < EPS, "reconstruction/formula error");
            System.out.printf(java.util.Locale.ROOT,
                    "PASS %s points=%d zeroG=%d reconstructionErr=%.17g formulaErr=%.17g maxD=%.17g%n",
                    directory.getFileName(), count, zeros, reconstructionError, formulaError, maxDepth);
        } finally {
            for (DataInputStream stream : streams) if (stream != null) stream.close();
        }
    }

    public static void main(String[] args) {
        try {
            require(args.length > 0, "usage: DecomposedCarveProbe <array-directory> [...]");
            for (String arg : args) check(Path.of(arg));
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
