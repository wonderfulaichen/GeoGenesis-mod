package com.geogenesis.worldgen.hydrology.model;

/** Tests structural search completion instead of assuming an adequate margin. */
public final class CertifiedBasinProbe {
    public static void main(String[] args) {
        CertifiedBasinQuery.Terrain terrain = (x, z) -> {
            if (x <= -9) return -1;
            if (x == -8 && z == 0) return 3;
            if (x >= -7 && x <= 7 && z >= -4 && z <= 4) return 1;
            return 10;
        };
        CertifiedBasinQuery query = new CertifiedBasinQuery(terrain, (x, z) -> x <= -9, 0, 1);
        var a = query.resolve(-5, 0, 1000);
        var b = query.resolve(5, 2, 1000);
        require(a.status() == CertifiedBasinQuery.Status.RESOLVED, "physical ocean outlet reached");
        require(a.escapeLevel() == 3 && b.escapeLevel() == 3, "same certified escape level from two roots");
        require(a.owner().equals(b.owner()) && a.members().equals(b.members()), "global basin identity independent of query root");
        require(a.capacity() == 270 && a.capacity() == b.capacity(), "complete connected capacity counted once");
        oracleComparison(terrain, a);
        conservationImpossibility();
        belowSeaInland();
        translationAndCancellation(terrain, a);
        var interrupted = query.resolve(-5, 0, 4);
        require(interrupted.status() == CertifiedBasinQuery.Status.SEARCH_PENDING
                && Double.isNaN(interrupted.escapeLevel()), "budget exhaustion cannot invent a window outlet");
        CertifiedBasinQuery endless = new CertifiedBasinQuery((x, z) -> 1, (x, z) -> false, 0, 1);
        require(endless.resolve(0, 0, 200).status() == CertifiedBasinQuery.Status.SEARCH_PENDING,
                "world with no reachable ocean remains unresolved");
        CertifiedBasinQuery infiniteStorage = new CertifiedBasinQuery(
                (x, z) -> x == 0 && z == 0 ? -1 : (Math.abs(x) + Math.abs(z) == 1 ? 3 : 1),
                (x, z) -> x == 0 && z == 0, 0, 1);
        var partial = infiniteStorage.resolve(2, 0, 200);
        require(partial.status() != CertifiedBasinQuery.Status.RESOLVED
                && Double.isNaN(partial.capacity()) && partial.owner() == null,
                "unbounded storage cannot publish a partial basin capacity");
        System.out.printf("owner=%s cells=%d capacity=%.3f samples=%d/%d%n",
                a.owner(), a.members().size(), a.capacity(), a.sampledCells(), b.sampledCells());
        System.out.println("ALL PASS: certified finite static basin search; no bounded-cost steady-state flux claim.");
    }

    private static void oracleComparison(CertifiedBasinQuery.Terrain terrain, CertifiedBasinQuery.Result result) {
        int size = 25;
        double[] bed = new double[size * size];
        for (int i = 0; i < size; i++) for (int j = 0; j < size; j++) {
            bed[i * size + j] = terrain.height(i - 12, j - 12);
        }
        WaterGrid g = WaterGrid.of(size, size, 1, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, 0);
        double spill = ModelOracle.minimaxSpill(g, g.idx(7, 12), 0);
        require(spill == result.escapeLevel(), "independent finite-domain path oracle agrees");
        // Exact-level ties are not physically connected below the spill, and must not join owners.
        CertifiedBasinQuery separated = new CertifiedBasinQuery((x, z) -> {
            if (z <= -4) return -1;
            if (z == -3) return 3;
            if (z >= -2 && z <= 2 && x >= -4 && x <= 4) return x == 0 ? 3 : 1;
            return 10;
        }, (x, z) -> z <= -4, 0, 1);
        var left = separated.resolve(-2, 0, 1000);
        var right = separated.resolve(2, 0, 1000);
        require(left.status() == CertifiedBasinQuery.Status.RESOLVED
                && right.status() == CertifiedBasinQuery.Status.RESOLVED
                && !left.owner().equals(right.owner()), "exact spill-height ridge preserves separate pre-spill basin owners");
    }

    private static void conservationImpossibility() {
        WaterGrid closed = WaterGrid.flat(5, 5, 1, 0, WaterGrid.Boundary.CLOSED, 0);
        ModelParams p = ModelParams.still().withRain(0.002).withMaxSteps(30);
        DiffusiveWaveModel m = new DiffusiveWaveModel(closed, p);
        m.steadyState();
        double expected = closed.cells() * closed.cellArea() * p.rainRate() * m.time();
        require(!m.converged() && Math.abs(m.storage() - expected) < 1e-12,
                "closed positive-rain domain has no steady state: storage grows exactly by mass conservation");
    }

    private static void translationAndCancellation(CertifiedBasinQuery.Terrain terrain,
                                                   CertifiedBasinQuery.Result reference) {
        int ox = -10000, oz = 7000;
        CertifiedBasinQuery translated = new CertifiedBasinQuery(
                (x, z) -> terrain.height(x - ox, z - oz), (x, z) -> x - ox <= -9, 0, 1);
        var r = translated.resolve(-5 + ox, oz, 1000);
        require(r.status() == CertifiedBasinQuery.Status.RESOLVED
                && r.capacity() == reference.capacity() && r.escapeLevel() == reference.escapeLevel()
                && r.owner().equals(new CertifiedBasinQuery.Cell(reference.owner().x() + ox, reference.owner().z() + oz)),
                "negative global-coordinate translation preserves level/capacity and translates owner");
        Thread.currentThread().interrupt();
        boolean cancelled = false;
        try { translated.resolve(-5 + ox, oz, 1000); }
        catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
        finally { Thread.interrupted(); }
        require(cancelled && translated.resolve(-5 + ox, oz, 1000).equals(r),
                "cancelled query publishes no partial result and retry is identical");
    }

    private static void belowSeaInland() {
        CertifiedBasinQuery query = new CertifiedBasinQuery((x, z) -> {
            if (x <= -5) return -2;
            if (x == -4 && z == 0) return 2;
            if (x >= -3 && x <= 3 && z >= -2 && z <= 2) return -1;
            return 10;
        }, (x, z) -> x <= -5, 0, 1);
        var r = query.resolve(0, 0, 1000);
        require(r.status() == CertifiedBasinQuery.Status.RESOLVED && r.escapeLevel() == 2
                && r.capacity() == 105, "below-sea inland depression is not an ocean boundary");
    }

    private static void require(boolean pass, String name) {
        System.out.println((pass ? "[PASS] " : "[FAIL] ") + name);
        if (!pass) throw new AssertionError(name);
    }
}
