package com.geogenesis.standalone;

import com.geogenesis.standalone.NoiseFollowingErosion.Cut;
import com.geogenesis.standalone.NoiseFollowingErosion.HeightField;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** S1 diagnostic only: one immutable world-anchored C2 weight, never nearest-route depth. */
public final class SharedDepthField implements HeightField {
    public static final double SPACING = 4.0;
    public record Cell(long cx, long cz) {}
    public record Basis(Cell cell, double value) {}
    /** Open support of modified coefficients; absent when all saved coefficients are one. */
    public record Bounds(double minX, double minZ, double maxX, double maxZ) {}
    private record Axis(long floor, double t) {}
    private final HeightField carrier;
    private final Cut old;
    private final Map<Cell, Double> weights;
    private final Bounds bounds;

    public SharedDepthField(HeightField carrier, Cut old, Map<Cell, Double> weights) {
        this.carrier = Objects.requireNonNull(carrier, "carrier");
        this.old = Objects.requireNonNull(old, "old");
        Map<Cell, Double> copy = new HashMap<>();
        for (var entry : Objects.requireNonNull(weights, "weights").entrySet()) {
            Cell cell = Objects.requireNonNull(entry.getKey(), "cell");
            double value = Objects.requireNonNull(entry.getValue(), "weight");
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Coefficient outside [0,1]: " + cell);
            }
            // Keep basis coordinates exactly representable in binary64, including +/-2 padding.
            if (Math.abs((double) cell.cx()) > 0x1.0p50
                    || Math.abs((double) cell.cz()) > 0x1.0p50) {
                throw new IllegalArgumentException("Coefficient coordinate too large");
            }
            copy.put(cell, value);
        }
        this.weights = Map.copyOf(copy);
        bounds = findBounds(this.weights);
    }

    public Map<Cell, Double> coefficients() { return weights; }
    public Bounds modifiedBounds() { return bounds; }

    /** Header is exact; duplicate cells, nonfinite or out-of-range coefficients are rejected. */
    public static Map<Cell, Double> readWeights(Path csv) throws IOException {
        Map<Cell, Double> result = new HashMap<>();
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            if (!"cx,cz,weight".equals(in.readLine())) throw new IOException("weights header");
            String line;
            while ((line = in.readLine()) != null) {
                String[] v = line.split(",", -1);
                if (v.length != 3) throw new IOException("weights row: " + line);
                Cell cell = new Cell(Long.parseLong(v[0]), Long.parseLong(v[1]));
                double weight = Double.parseDouble(v[2]);
                if (!Double.isFinite(weight) || weight < 0 || weight > 1) {
                    throw new IOException("Coefficient outside [0,1]: " + line);
                }
                if (result.putIfAbsent(cell, weight) != null) throw new IOException("Duplicate " + cell);
            }
        }
        return Map.copyOf(result);
    }

    /** Standard cardinal cubic tensor basis; cx=floor(x/4)-1..floor(x/4)+2 (same for z). */
    public static List<Basis> basis(double x, double z) {
        Axis ax = axis(x), az = axis(z);
        double[] bx = cubicBasis(ax.t()), bz = cubicBasis(az.t());
        List<Basis> result = new ArrayList<>(16);
        for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) {
            result.add(new Basis(new Cell(ax.floor() + i - 1, az.floor() + j - 1), bx[i] * bz[j]));
        }
        return List.copyOf(result);
    }

    private static double[] cubicBasis(double t) {
        double u = 1 - t;
        return new double[] {u*u*u/6, (3*t*t*t - 6*t*t + 4)/6,
                (-3*t*t*t + 3*t*t + 3*t + 1)/6, t*t*t/6};
    }

    /** Equivalent to the 16 basis sum, but de Boor convex mixing keeps 0/1 EXACT.
     * No query normalization and no range clamp (not even ULP clamping) is performed.
     * Polynomial basis dot products may differ by a few ULP from this stable evaluation.
     */
    public double weight(double x, double z) {
        Axis ax = axis(x), az = axis(z);
        double[] rows = new double[4];
        for (int j = 0; j < 4; j++) {
            long cz = az.floor() + j - 1, cx = ax.floor();
            rows[j] = cubic(coefficient(cx-1, cz), coefficient(cx, cz),
                    coefficient(cx+1, cz), coefficient(cx+2, cz), ax.t());
        }
        return cubic(rows[0], rows[1], rows[2], rows[3], az.t());
    }

    private double coefficient(long cx, long cz) {
        return weights.getOrDefault(new Cell(cx, cz), 1.0);
    }

    private static double cubic(double a, double b, double c, double d, double t) {
        double ab = mix(a, b, (t+2)/3), bc = mix(b, c, (t+1)/3), cd = mix(c, d, t/3);
        return mix(mix(ab, bc, (t+1)/2), mix(bc, cd, t/2), t);
    }

    private static double mix(double a, double b, double t) {
        // Compute from the nearer endpoint, preserving constants and the convex interval.
        return t <= .5 ? a + (b-a)*t : b + (a-b)*(1-t);
    }

    private static Axis axis(double coordinate) {
        double q = coordinate / SPACING;
        if (!Double.isFinite(q) || Math.abs(q) > 0x1.0p50) {
            throw new IllegalArgumentException("Invalid world coordinate");
        }
        long floor = (long) Math.floor(q);
        return new Axis(floor, q - floor);
    }

    public double displacement(double x, double z) {
        double d = old.displacement(x, z);
        return d == 0 ? 0.0 : d * weight(x, z);
    }

    @Override public double height(double x, double z) {
        double h0 = carrier.height(x, z);
        if (!Double.isFinite(h0)) throw new IllegalArgumentException("Nonfinite H0");
        double d = displacement(x, z);
        return d == 0 ? h0 : h0 - d; // Preserve carrier bits, including signed zero.
    }

    private static Bounds findBounds(Map<Cell, Double> coefficients) {
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (var entry : coefficients.entrySet()) {
            if (entry.getValue() == 1) continue;
            Cell c = entry.getKey();
            minX = Math.min(minX, (c.cx()-2)*SPACING);
            minZ = Math.min(minZ, (c.cz()-2)*SPACING);
            maxX = Math.max(maxX, (c.cx()+2)*SPACING);
            maxZ = Math.max(maxZ, (c.cz()+2)*SPACING);
        }
        return minX == Double.POSITIVE_INFINITY ? null : new Bounds(minX, minZ, maxX, maxZ);
    }
}
