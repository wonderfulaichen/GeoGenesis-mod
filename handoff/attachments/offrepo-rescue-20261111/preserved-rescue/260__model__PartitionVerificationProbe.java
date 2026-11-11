package com.geogenesis.worldgen.hydrology.model;

/** Finite-domain partition tests; deliberately not an infinite-world feasibility claim. */
public final class PartitionVerificationProbe {
    public static void main(String[] args) {
        int nx = 24, nz = 17, steps = 200;
        double[] bed = new double[nx * nz];
        for (int i = 0; i < nx; i++) for (int j = 0; j < nz; j++) {
            double x = i - 10, z = j - 8;
            bed[i * nz + j] = 0.02 * (x * x + z * z) - 0.05 * i;
        }
        WaterGrid g = WaterGrid.of(nx, nz, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        ModelParams p = ModelParams.defaults();
        PartitionedWaterModel whole = initial(g, p, 100);
        PartitionedWaterModel split = initial(g, p, 5);
        PartitionedWaterModel reversed = initial(g, p, 7);
        double initial = whole.storage();
        for (int step = 0; step < steps; step++) {
            whole.step(0.0001, false);
            split.step(0.0001, false);
            reversed.step(0.0001, true);
        }
        double error = Math.max(difference(whole, split, nx, nz), difference(whole, reversed, nx, nz));
        long expectedFaces = (long) steps * ((nx - 1) * nz + nx * (nz - 1));
        long expectedSources = (long) steps * nx * nz;
        double residual = split.storage() - initial - split.rainVolume() + split.lossVolume();
        check(error == 0, "partition sizes and tile visitation order", error);
        check(split.faceUpdates() == expectedFaces, "one owner per interior face", split.faceUpdates());
        check(split.sourceUpdates() == expectedSources, "one source update per owned cell", split.sourceUpdates());
        check(split.crossFaces() > 0, "cross-partition faces actually exercised", split.crossFaces());
        check(Math.abs(residual) < 1e-8, "external conservation ledger", residual);
        double duplicatedRainResidual = residual - split.rainVolume();
        check(Math.abs(duplicatedRainResidual) > 1e-8,
                "reverse validation: duplicated source accounting is rejected", duplicatedRainResidual);
        temporalComparison();
        basinAcrossPartitions();
        System.out.println("ALL PASS: finite-domain ownership only; infinite-world gate remains unproven");
    }

    private static PartitionedWaterModel initial(WaterGrid g, ModelParams p, int tileSize) {
        PartitionedWaterModel model = new PartitionedWaterModel(g, p, tileSize);
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            model.setDepth(i, j, Math.max(0, 2 - g.bed[g.idx(i, j)]));
        }
        model.setDepth(10, 8, model.depth(10, 8) + 0.1);
        return model;
    }

    private static double difference(PartitionedWaterModel a, PartitionedWaterModel b, int nx, int nz) {
        double error = 0;
        for (int i = 0; i < nx; i++) for (int j = 0; j < nz; j++) {
            error = Math.max(error, Math.abs(a.depth(i, j) - b.depth(i, j)));
        }
        return error;
    }

    private static void temporalComparison() {
        WaterGrid g = WaterGrid.flat(8, 6, 1, 0, WaterGrid.Boundary.CLOSED, 0);
        ModelParams p = ModelParams.still();
        DiffusiveWaveModel reference = new DiffusiveWaveModel(g, p.withDtMax(0.00001));
        PartitionedWaterModel candidate = new PartitionedWaterModel(g, p, 3);
        for (int i = 0; i < 8; i++) for (int j = 0; j < 6; j++) {
            double d = 0.1 + 0.01 * Math.sin(i * 0.5) * Math.cos(j * 0.4);
            reference.setDepth(i, j, d);
            candidate.setDepth(i, j, d);
        }
        for (int step = 0; step < 100; step++) reference.step();
        for (int step = 0; step < 1000; step++) candidate.step(0.000001, step % 2 == 0);
        double error = 0;
        for (int i = 0; i < 8; i++) for (int j = 0; j < 6; j++) {
            error = Math.max(error, Math.abs(reference.depth(i, j) - candidate.depth(i, j)));
        }
        check(error < 1e-5, "independent explicit vs split-implicit at the same model time", error);
    }

    private static void basinAcrossPartitions() {
        int nx = 24, nz = 16;
        double[] bed = new double[nx * nz];
        for (int i = 0; i < nx; i++) for (int j = 0; j < nz; j++) {
            double left = 0.003 * ((i - 6.0) * (i - 6.0) + (j - 8.0) * (j - 8.0));
            double right = 0.003 * ((i - 18.0) * (i - 18.0) + (j - 8.0) * (j - 8.0));
            bed[i * nz + j] = Math.min(left, right);
        }
        WaterGrid g = WaterGrid.of(nx, nz, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        ModelParams p = ModelParams.still();
        PartitionedWaterModel whole = new PartitionedWaterModel(g, p, 100);
        PartitionedWaterModel split = new PartitionedWaterModel(g, p, 4);
        for (int i = 0; i < 12; i++) for (int j = 0; j < nz; j++) {
            double water = Math.max(0, 0.22 - bed[g.idx(i, j)]);
            whole.setDepth(i, j, water);
            split.setDepth(i, j, water);
        }
        double initial = split.storage();
        for (int step = 0; step < 12000; step++) {
            whole.step(0.001, false);
            split.step(0.001, step % 2 == 0);
        }
        double rightWater = 0;
        for (int i = 12; i < nx; i++) for (int j = 0; j < nz; j++) rightWater += split.depth(i, j);
        check(rightWater > 0.01, "water actually crosses the saddle into the other basin", rightWater);
        check(difference(whole, split, nx, nz) == 0,
                "cross-partition basin matches single-domain at the same time", difference(whole, split, nx, nz));
        check(Math.abs(split.storage() - initial) < 1e-8,
                "cross-basin transfer conserves external volume", split.storage() - initial);
    }

    private static void check(boolean pass, String name, double value) {
        System.out.printf("[%s] %s: %.12e%n", pass ? "PASS" : "FAIL", name, value);
        if (!pass) throw new AssertionError(name);
    }
}
