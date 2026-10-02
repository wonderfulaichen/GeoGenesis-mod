package com.geogenesis.worldgen.hydrology.model;

public final class BasinMergeStateProbe {
    public static void main(String[] args) {
        double[] bed = new double[35]; java.util.Arrays.fill(bed, 10);
        bed[11] = 0; bed[16] = 3; bed[21] = 1;
        WaterGrid g = WaterGrid.of(7, 5, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        BasinStorage parent = new BasinStorage(g, 11, 7);
        BasinMergeState merge = new BasinMergeState(parent, 3);
        require(merge.equilibrium(4).status() == BasinMergeState.Status.SEPARATE, "underfilled receiver cannot be merged");
        require(merge.equilibrium(5).status() == BasinMergeState.Status.SEPARATE, "zero-depth saddle is not wet connectivity");
        var result = merge.equilibrium(8);
        require(result.status() == BasinMergeState.Status.CONNECTED_EQUILIBRIUM && Math.abs(result.level() - 4) < 1e-12,
                "independent analytic sum: V=4+1+3 gives common eta=4");
        require(Math.abs(parent.volumeAt(result.level()) - result.volume()) < 1e-12,
                "saddle-platform storage included exactly once");
        require(merge.equilibrium(18).status() == BasinMergeState.Status.NEXT_SADDLE_PENDING,
                "excess volume retained for next hierarchy event");
        var drained = merge.equilibrium(2);
        require(drained.status() == BasinMergeState.Status.SEPARATE && drained.volume() == 2,
                "falling volume requires split with no invented branch allocation");
        dynamicConnection(g, parent);
        System.out.println("ALL PASS: equilibrium eligibility and finite-grid dynamic connection; global integration remains unfinished.");
    }

    private static void dynamicConnection(WaterGrid g, BasinStorage parent) {
        DiffusiveWaveModel low = new DiffusiveWaveModel(g, ModelParams.still().withSteadyTol(1e-8));
        low.setDepth(2, 1, 2);
        low.steadyState();
        require(low.converged() && low.depth(4, 1) == 0 && low.depth(3, 1) == 0,
                "dry saddle blocks sub-crest transfer in actual cell model");
        DiffusiveWaveModel high = new DiffusiveWaveModel(g, ModelParams.still().withSteadyTol(1e-8));
        high.setDepth(2, 1, 8);
        high.steadyState();
        require(high.converged(), "finite-grid connection must actually converge");
        require(Math.abs(high.storage() - 8) < 1e-10 && high.depth(3, 1) > 0,
                "dynamic connection includes saddle water and conserves total volume");
        double level = parent.levelForVolume(8), error = 0;
        for (int k : parent.members()) error = Math.max(error,
                Math.abs(high.surface(g.ix(k), g.iz(k)) - level));
        require(error < 1e-5, "cell simulation agrees with independent connected storage eta=4");
        System.out.printf("dynamicSteps=%d maxLevelError=%.3e volume=%.12f%n", high.steps(), error, high.storage());
    }

    private static void require(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("[PASS] " + name);
    }
}
