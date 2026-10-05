package com.geogenesis.worldgen.hydrology.model;

/** Exact dependency-cone test for the explicit prototype, not a steady-state solver. */
public final class CausalDomainProbe {
    public static void main(String[] args) {
        int steps = 24;
        // Flux reads one-hop depths; its donor limiter reads one-hop fluxes.
        // Therefore one accepted explicit step has at most a two-cell dependency radius.
        int radius = 2 * steps;
        int centerX = -23, centerZ = 19;
        PartitionedWaterModel local = domain(centerX, centerZ, radius, 5);
        PartitionedWaterModel large = domain(centerX, centerZ, radius + 8, 7);
        for (int step = 0; step < steps; step++) {
            local.step(0.0001, step % 2 == 0);
            large.step(0.0001, step % 2 != 0);
        }
        double a = local.depth(radius, radius);
        double b = large.depth(radius + 8, radius + 8);
        boolean equal = Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
        System.out.printf("[%s] negative world origin, enlarged domain and reverse partition order: %.12e%n",
                equal ? "PASS" : "FAIL", Math.abs(a - b));
        if (!equal) throw new AssertionError("dependency cone mismatch");
        System.out.printf("steps=%d exact stencil radius=%d cells physicalTime=%.6f%n", steps, radius, steps * 0.0001);
        System.out.println("Finite-time closure is exact for this stencil; steady-state closure remains unproven.");
    }

    private static PartitionedWaterModel domain(int centerX, int centerZ, int radius, int tileSize) {
        int n = radius * 2 + 1;
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            int x = centerX + i - radius, z = centerZ + j - radius;
            bed[i * n + j] = 0.12 * Math.sin(x * 0.17) * Math.cos(z * 0.13) - x * 0.005;
        }
        WaterGrid g = WaterGrid.of(n, n, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        PartitionedWaterModel model = new PartitionedWaterModel(g, ModelParams.defaults(), tileSize);
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            int x = centerX + i - radius, z = centerZ + j - radius;
            model.setDepth(i, j, 0.2 + 0.03 * Math.sin(x * 0.21 + z * 0.19));
        }
        return model;
    }
}
