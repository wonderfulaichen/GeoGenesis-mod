package com.geogenesis.worldgen.hydrology.model;

/** Two fixed storage footprints coupled by a crest. Diagnostic level-pool approximation only. */
public final class LakeSaddleExchange {
    public record Step(double volumeA, double volumeB, double transfer, double rate,
                       double levelA, double levelB) { }
    private final double[] bedA, bedB;
    private final double area, crest, conductance;

    public LakeSaddleExchange(double[] bedA, double[] bedB, double area,
                              double crest, double conductance) {
        if (bedA.length == 0 || bedB.length == 0 || !Double.isFinite(area) || area <= 0
                || !Double.isFinite(crest) || !Double.isFinite(conductance) || conductance <= 0) {
            throw new IllegalArgumentException("exchange geometry");
        }
        for (double h : bedA) if (!Double.isFinite(h) || h >= crest) throw new IllegalArgumentException("bed A");
        for (double h : bedB) if (!Double.isFinite(h) || h >= crest) throw new IllegalArgumentException("bed B");
        this.bedA = bedA.clone(); this.bedB = bedB.clone();
        this.area = area; this.crest = crest; this.conductance = conductance;
    }

    /** Signed rating approximation: two crest-relative heads; downstream head suppresses exchange. */
    public double rate(double levelA, double levelB) {
        return conductance * (Math.pow(Math.max(0, levelA - crest), 1.5)
                - Math.pow(Math.max(0, levelB - crest), 1.5));
    }

    /** Implicit conservative transfer, bounded by donor storage. No source or sink is applied here. */
    public Step step(double volumeA, double volumeB, double dt) {
        if (!Double.isFinite(volumeA) || !Double.isFinite(volumeB) || volumeA < 0 || volumeB < 0
                || !Double.isFinite(dt) || dt <= 0 || !Double.isFinite(volumeA + volumeB)) {
            throw new IllegalArgumentException("exchange state");
        }
        double lo = -volumeB, hi = volumeA;
        for (int it = 0; it < 100; it++) {
            double transfer = lo + (hi - lo) * 0.5;
            double a = level(bedA, volumeA - transfer), b = level(bedB, volumeB + transfer);
            if (transfer - dt * rate(a, b) < 0) lo = transfer; else hi = transfer;
        }
        double transfer = lo + (hi - lo) * 0.5;
        double a = volumeA - transfer, b = volumeB + transfer;
        return new Step(a, b, transfer, transfer / dt, level(bedA, a), level(bedB, b));
    }

    public double levelA(double volume) { return level(bedA, volume); }
    public double levelB(double volume) { return level(bedB, volume); }

    // Fixed-footprint continuation above crest is for this exchange test, not a merged-basin shoreline.
    private double level(double[] bed, double volume) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double h : bed) { min = Math.min(min, h); max = Math.max(max, h); }
        if (volume == 0) return min;
        double lo = min, hi = max + volume / (area * bed.length);
        for (int it = 0; it < 80; it++) {
            double mid = lo + (hi - lo) * 0.5, v = 0;
            for (double h : bed) v += Math.max(0, mid - h) * area;
            if (v < volume) lo = mid; else hi = mid;
        }
        return lo + (hi - lo) * 0.5;
    }
}
