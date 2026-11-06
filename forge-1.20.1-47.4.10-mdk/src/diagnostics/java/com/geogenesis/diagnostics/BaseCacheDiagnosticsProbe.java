package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.core.*;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.concurrent.*;

/** Small fixed witnesses; assertion/timeout failure propagates as a nonzero JavaExec exit. */
public final class BaseCacheDiagnosticsProbe {
    public static void main(String[] args) throws Exception {
        outcomes();
        overlap();
        occupancy();
        System.out.println("PASS: BASECACHE exact outcomes, scope isolation, retained repeat/overlap");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void has(String text, String expected) {
        require(text.contains(expected), expected + " missing: " + text);
    }
    private static void outcomes() {
        var diag = new BaseCacheDiagnostics();
        var input = diag.input();
        var sampler = new BaseWorldSampler(9139, TerrainParams.defaults(), -64, 320, BaseWorldSettings.defaults());
        double first = sampler.sampleE(0, 0, diag, input);
        require(Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(sampler.sampleE(0, 0, diag, input)),
                "observed sample changed");
        sampler.sampleE(0, -2, diag, input); // known production-slot conflict
        sampler.sampleE(0, 0, diag, input);
        sampler.sampleE(0, 0, diag, null); // explicitly not tile input
        String summary = diag.inputSummary(input);
        boolean off = "false".equals(System.getProperty("geogenesis.baseSampleCache"));
        has(summary, "total=4 ");
        has(summary, off ? "bypass=4" : "hit=1 missEmpty=1 missConflict=2");
        has(diag.otherSummary(), "total=1 ");
        String finished = diag.finish(input);
        has(finished, "tiles=1");
        has(finished, "contextTile={total=4 ");
        has(finished, "contextOther={total=1 ");
        has(new BaseCacheDiagnostics().otherSummary(), "total=0 ");
    }
    /** Exact address occupancy, NOT cache hit rate; actual Owner input coordinates, seed independent. */
    private static void occupancy() {
        int[][] owners = {{-24, -24}, {0, 0}, {48, 0}, {-48, 48}, {960, -1008}, {100032, -99984}};
        for (int[] pair : owners) {
            // First row reproduces the explicitly synthetic [-73,72]^2 witness only.
            int ix = pair[0] - 49, iz = pair[1] - 49;
            if (pair[0] != -24) {
                var owner = new com.geogenesis.worldgen.process.TileGeometry.Owner(pair[0], pair[1]);
                ix = owner.inputX(); iz = owner.inputZ();
            }
            int[] bins = new int[1 << 18];
            for (int z = 0; z < 146; z++) for (int x = 0; x < 146; x++) {
                long a = Double.doubleToRawLongBits((double) ix + x);
                long b = Double.doubleToRawLongBits((double) iz + z);
                int slot = (int) (((a * 0x9E3779B97F4A7C15L)
                        ^ (b * 0x632BE59BD9B4E019L)) >>> 40) & (bins.length - 1);
                bins[slot]++;
            }
            int distinct = 0, max = 0;
            for (int n : bins) { if (n != 0) distinct++; max = Math.max(max, n); }
            if (pair[0] == -24) require(distinct == 2484 && max == 30, "synthetic witness changed");
            System.out.println("slotOccupancy kind=" + (pair[0] == -24 ? "synthetic" : "production-input")
                    + " owner=" + pair[0] + "," + pair[1] + " input=" + ix + "," + iz
                    + " points=21316 distinct=" + distinct + " max=" + max + " seedIndependent=true hitRate=NOT_MEASURED");
        }
    }
    private static void overlap() throws Exception {
        var diag = new BaseCacheDiagnostics();
        var one = diag.input();
        // hash(0,0)==0 is selected; explicit lifecycle calls avoid scheduler-dependent terrain races.
        int first = diag.begin(one, 0, 0);
        require(first != 0, "fixed coordinate must be sampled");
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                int second = diag.begin(null, 0, 0);
                require(second == first, "same raw coordinate token");
                diag.end(null, second, true);
            }).get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        diag.end(one, first, true);
        int third = diag.begin(one, 0, 0);
        diag.end(one, third, false);
        has(diag.inputSummary(one), "sampledCompute=2 retainedRepeat=1 overlapStart=0 trackerDrop=0 computeFailed=1");
        has(diag.otherSummary(), "sampledCompute=1 retainedRepeat=1 overlapStart=1");
        // Fixed raw-bit pair hashes to the same selected tracker bucket as (0,0).
        int collision = 25842;
        var candidate = new BaseCacheDiagnostics();
        int witness = candidate.begin(null, collision, 0);
        require(witness == first, "tracker collision witness");
        candidate.end(null, witness, true);
        int held = diag.begin(one, 0, 0);
        require(diag.begin(null, collision, 0) == 0, "active bucket must not be evicted");
        has(diag.otherSummary(), "trackerDrop=1");
        diag.end(one, held, true);
    }
}
