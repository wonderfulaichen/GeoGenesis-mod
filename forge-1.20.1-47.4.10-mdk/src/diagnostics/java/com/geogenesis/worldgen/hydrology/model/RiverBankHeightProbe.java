package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/** 岸水高差小合成回归，不解区域、不启动MC；FAIL非零。 */
public final class RiverBankHeightProbe {
    private static int checks, failures;

    public static void main(String[] args) {
        near("flat retains surface", 0, RiverBankHeight.lowering(0, 100));
        near("flat threshold", 0, RiverBankHeight.lowering(0.02, 100));
        near("slope midpoint", 0.5, RiverBankHeight.lowering(0.06, 100));
        near("steep maximum", 1, RiverBankHeight.lowering(0.1, 100));
        near("steeper stays bounded", 1, RiverBankHeight.lowering(8, 100));
        near("lake or sea interface", 0, RiverBankHeight.lowering(1, 0));
        near("water transition midpoint", 0.5, RiverBankHeight.lowering(1, 8));
        near("ordinary river surface", 99, RiverBankHeight.surface(100, 1, 63));
        near("sea floor", 63, RiverBankHeight.surface(63.2, 1, 63));
        near("below sea never raised", 62, RiverBankHeight.surface(62, 1, 63));
        near("zero lowering preserves bits", 100, RiverBankHeight.surface(100, 0, 63));
        for (int step : new int[]{1, 2, 4}) {
            plane(step, 0, 0);
            plane(step, 0.06, 0.5);
            plane(step, 0.1, 1);
            distances(step);
            borrowedWater(step);
        }
        System.out.println("RiverBankHeightProbe checks=" + checks + " FAIL=" + failures);
        if (failures != 0) System.exit(1);
    }

    private static void plane(int step, double slope, double expected) {
        int n = 41, cells = n * n;
        double[] bed = new double[cells], empty = new double[cells];
        boolean[] water = new boolean[cells];
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) bed[x*n+z] = 100 + x*step*slope;
        var pipe = new HydrologyPipeline.Result(n, n, step, bed, water, water, bed, empty,
                water, new int[cells], empty, water, empty, null, null, empty, bed, 0, true, 0);
        double[] oldBed = bed.clone();
        double[] field = RiverBankHeight.build(pipe);
        for (double v : field) near("plane slope=" + slope + " step=" + step, expected, v);
        expect("no terrain writes", Arrays.equals(bed, oldBed));
        expect("no lake writes", !water[n*n/2]);
    }

    private static void distances(int step) {
        int n = 41, mid = n / 2;
        boolean[] lake = new boolean[n*n], sea = new boolean[n*n];
        lake[mid*n+mid] = true;
        sea[0] = true;
        double[] d = RiverBankHeight.waterDistance(lake, sea, n, n, step);
        near("lake seed", 0, d[mid*n+mid]);
        near("sea seed", 0, d[0]);
        near("cardinal distance", 3*step, d[(mid+3)*n+mid]);
        near("diagonal distance", 3*Math.sqrt(2)*step, d[(mid+3)*n+mid+3]);
        double previous = -1;
        for (int i = 0; i <= 16 / step + 1; i++) {
            double lowering = RiverBankHeight.lowering(0.1, d[(mid+i)*n+mid]);
            expect("transition bounded step=" + step, lowering >= 0 && lowering <= 1);
            expect("transition grows away from water", lowering >= previous);
            previous = lowering;
        }
        double epsilon = 1e-4;
        expect("no hard cut at transition reach", Math.abs(RiverBankHeight.lowering(1, 16-epsilon)
                - RiverBankHeight.lowering(1, 16+epsilon)) < 1e-8);
        expect("flat-to-slope is smooth", RiverBankHeight.lowering(0.02+epsilon, 100) < 1e-5);
    }

    private static void borrowedWater(int step) {
        int n = 41, cells = n*n, mid = n/2;
        double[] bed = new double[cells], empty = new double[cells];
        boolean[] dry = new boolean[cells], lake = new boolean[cells];
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) bed[x*n+z] = 100+x*step*0.1;
        lake[(mid-5)*n+mid] = true;
        var target = new HydrologyPipeline.Result(n, n, step, bed, dry, dry, bed, empty,
                dry, new int[cells], empty, dry, empty, null, null, empty, bed, 0, true, 0);
        var source = new HydrologyPipeline.Result(n, n, step, bed, dry, dry, bed, empty,
                lake, new int[cells], empty, dry, empty, null, null, empty, bed, 0, true, 0);
        boolean[] water = new boolean[cells];
        near("unaware local window wants full lowering", 1, RiverBankHeight.build(target)[mid*n+mid]);
        RiverBankHeight.mergeWater(water, target, 0, 0, source, 5*step, 0);
        double[] lower = RiverBankHeight.build(target, water);
        near("borrowed lake prevents new interface drop", 0, lower[mid*n+mid]);
        near("borrowed lake protects adjacent bank", 0, lower[(mid+1)*n+mid]);
        expect("borrowed influence fades rather than removing all incision",
                lower[(mid+12)*n+mid] > 0 && lower[(mid+12)*n+mid] <= 1);
        expect("no writes to neighbor lake mask", lake[(mid-5)*n+mid] && !lake[(mid-4)*n+mid]);
    }

    private static void near(String label, double expected, double actual) {
        expect(label + " expected=" + expected + " actual=" + actual,
                Double.isFinite(actual) && Math.abs(expected - actual) < 1e-10);
    }

    private static void expect(String label, boolean ok) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL " + label); }
    }
}
