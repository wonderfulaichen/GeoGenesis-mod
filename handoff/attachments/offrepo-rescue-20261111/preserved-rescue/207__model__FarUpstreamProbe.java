package com.geogenesis.worldgen.hydrology.model;

/** Dynamic no-loss channel test: upstream rainfall must reach the sole downstream outlet. */
public final class FarUpstreamProbe {
    public static void main(String[] args) {
        int n = 32;
        double[] bed = new double[n];
        for (int i = 0; i < n; i++) bed[i] = 2 - i * 0.05;
        WaterGrid g = WaterGrid.of(n, 1, 1, bed, WaterGrid.Boundary.EAST_OPEN_AT_LEVEL, 0);
        ModelParams p = ModelParams.still().withRain(0.002).withDtMax(0.01).withSteadyTol(1e-6);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, p);
        m.steadyState();
        if (!m.converged()) throw new AssertionError("channel unconverged");
        double initial = m.storage(), loss = m.boundaryLossVolume();
        double time = 0;
        for (int i = 0; i < 1000; i++) time += m.step();
        double actual = (m.boundaryLossVolume() - loss) / time;
        double expected = n * p.rainRate() * g.cellArea();
        double relativeError = Math.abs(actual - expected) / expected;
        double storageDrift = m.storage() - initial;
        System.out.printf("[%s] no-loss upstream rainfall reaches only outlet: Q=%.9f expected=%.9f error=%.3e drift=%.3e%n",
                relativeError < 0.01 ? "PASS" : "FAIL", actual, expected, relativeError, storageDrift);
        if (relativeError >= 0.01) throw new AssertionError("upstream contribution missing");
        double nearOnly = 3 * 4 * p.rainRate() * g.cellArea();
        System.out.printf("full upstream=8 tiles Q=%.9f versus current+two upstream tiles Q=%.9f%n", expected, nearOnly);
        System.out.println("Finite channel dynamic test only; no arbitrary infinite upstream closure proof.");
    }
}
