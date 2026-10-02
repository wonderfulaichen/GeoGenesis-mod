package com.geogenesis.worldgen.hydrology.model;

/** Same bed/source/time comparison: local-inertial vs diffusive wave. Not a production gate. */
public final class LocalInertialComparisonProbe {
    private static final double G = 9.81;

    public static void main(String[] args) {
        damBreakComparison();
        rainSlopeComparison();
        System.out.println("NOTE: local-inertial is a candidate arm only; no production selection.");
    }

    private static void damBreakComparison() {
        int n = 400; double dx = 1, duration = 1, h0 = 1, nMan = 0.03;
        double[] bed = new double[n];
        LocalInertial1D li = new LocalInertial1D(bed, dx, nMan, 0, 0, 0.02);
        for (int i = 0; i < n / 2; i++) li.setDepth(i, h0);
        li.run(duration);
        double liL1 = 0, ref = 0, front = 0;
        for (int i = 0; i < n; i++) {
            double x = (i - n / 2.0) * dx, exact = ritter(x, duration, h0);
            liL1 += Math.abs(li.depth(i) - exact) * dx; ref += exact * dx;
            if (li.depth(i) > .01) front = x;
        }
        report("inertial dam-break finite-volume comparison", liL1 / ref < .15,
                String.format("relativeL1=%.3f front=%.1f", liL1 / ref, front));
    }

    private static void rainSlopeComparison() {
        double length = 120, slope = .01, rain = 1e-3, nMan = .03;
        for (double dx : new double[]{2, 1}) {
            int n = (int) Math.round(length / dx);
            double[] bed = new double[n];
            for (int i = 0; i < n; i++) bed[i] = -slope * i * dx;
            double hOut = 5 * Math.pow(nMan * rain * length / Math.sqrt(slope), .6);
            LocalInertial1D li = new LocalInertial1D(bed, dx, nMan, rain, bed[n - 1] + hOut, 0.02);
            li.run(200);
            int monotonic = 0;
            for (int i = 1; i < n; i++) if (li.depth(i) + 1e-6 < li.depth(i - 1)) monotonic++;
            double balance = li.sourceVolume() - li.boundaryOutflow() - li.storage();
            DiffusiveWaveModel diffusive = new DiffusiveWaveModel(
                    WaterGrid.of(n, 1, dx, bed, WaterGrid.Boundary.EAST_OPEN_AT_LEVEL, bed[n - 1] + hOut),
                    new ModelParams(dx, nMan, rain, 0, 0, 0, 0.002 * dx * dx, 1e-6, 4_000_000));
            diffusive.steadyState();
            double l1 = 0, max = 0;
            for (int i = 0; i < n; i++) {
                double delta = Math.abs(li.depth(i) - diffusive.depth(i, 0));
                l1 += delta * dx; max = Math.max(max, delta);
            }
            double normalized = l1 / Math.max(1e-12, diffusive.storage());
            report("inertial rain/backwater dx=" + dx,
                    monotonic == 0 && Math.abs(balance) < 1e-6 * Math.max(1, li.sourceVolume()),
                    String.format("monotonicViolations=%d ledger=%.3e steps=%d diffSteps=%d time=%.2f"
                                    + " L1/diffStorage=%.3f maxDepthDiff=%.4f",
                            monotonic, balance, li.steps(), diffusive.steps(), li.time(), normalized, max));
        }
    }

    private static double ritter(double x, double t, double h0) {
        double c = Math.sqrt(G * h0);
        if (x <= -c * t) return h0;
        if (x >= 2 * c * t) return 0;
        double fan = 2 * c - x / t;
        return fan * fan / (9 * G);
    }

    private static void report(String name, boolean pass, String details) {
        System.out.printf("%s %s  %s%n", pass ? "[PASS]" : "[FAIL]", name, details);
    }
}
