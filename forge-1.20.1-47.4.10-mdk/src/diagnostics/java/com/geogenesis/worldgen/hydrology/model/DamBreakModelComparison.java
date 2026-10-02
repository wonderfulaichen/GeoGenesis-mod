package com.geogenesis.worldgen.hydrology.model;

/** Same mesh/terrain/initial water/friction/time: diffusive-wave vs local-inertial. */
public final class DamBreakModelComparison {
    private static final double G = 9.81;

    public static void main(String[] args) {
        for (double dx : new double[]{1.0, 0.5}) compare(dx);
    }

    private static void compare(double dx) {
        int n = (int) (200 / dx); double duration = 1.0, h0 = 1.0, manning = 0.03;
        double[] bed = new double[n];
        var params = new ModelParams(dx, manning, 0, 0, 0, 0,
                0.001 * dx * dx, 1e-8, 4000000);
        var diff = new DiffusiveWaveModel(
                WaterGrid.flat(n, 1, dx, 0, WaterGrid.Boundary.CLOSED, 0), params);
        var inertial = new LocalInertial1D(bed, dx, manning, 0, 0, 0.02 * dx, true);
        for (int i = 0; i < n / 2; i++) { diff.setDepth(i, 0, h0); inertial.setDepth(i, h0); }
        diff.runFor(duration);
        inertial.run(duration);
        double l1 = 0, refVolume = 0, li = 0, maxDiff = 0, maxToRitter = 0;
        double frontD = 0, frontI = 0, frontR = 0;
        for (int i = 0; i < n; i++) {
            double x = (i - n / 2.0) * dx;
            double a = diff.depth(i, 0), b = inertial.depth(i), r = ritter(x, duration, h0);
            l1 += Math.abs(a - b) * dx; li += Math.abs(b - r) * dx; refVolume += r * dx;
            maxDiff = Math.max(maxDiff, Math.abs(a - b));
            maxToRitter = Math.max(maxToRitter, Math.abs(b - r));
            if (a > .01) frontD = x;
            if (b > .01) frontI = x;
            if (r > .01) frontR = x;
        }
        // 1D per-unit-width volume uses h*dx, not the 2D WaterGrid.storage() convention h*dx².
        double dVolume = 0, iVolume = 0;
        for (int i = 0; i < n; i++) { dVolume += diff.depth(i, 0) * dx; iVolume += inertial.depth(i) * dx; }
        double initialVolume = h0 * (n / 2.0) * dx;
        double dLedger = dVolume - initialVolume;
        double iLedger = iVolume + inertial.boundaryOutflow() + inertial.westOutflow() - initialVolume;
        System.out.printf("dx=%.2f t=%.1f dtD=%.4g dtI<=%.4g stepsD=%d stepsI=%d%n",
                dx, duration, params.dtMax(), .02*dx, diff.steps(), inertial.steps());
        System.out.printf("  diff-vs-inertial: L1/initialV=%.4f maxAbs=%.4f fronts D/I=%.1f/%.1f%n",
                l1 / (h0 * (n / 2.0) * dx), maxDiff, frontD, frontI);
        System.out.printf("  inertial-vs-Ritter: L1/refV=%.4f maxAbs=%.4f front I/R=%.1f/%.1f%n",
                li / refVolume, maxToRitter, frontI, frontR);
        System.out.printf("  volume residual diff=%.3e inertial=%.3e%n", dLedger, iLedger);
    }

    private static double ritter(double x, double t, double h0) {
        double c = Math.sqrt(G*h0);
        if (x <= -c*t) return h0;
        if (x >= 2*c*t) return 0;
        double f = 2*c - x/t;
        return f*f/(9*G);
    }

    private DamBreakModelComparison() { }
}
