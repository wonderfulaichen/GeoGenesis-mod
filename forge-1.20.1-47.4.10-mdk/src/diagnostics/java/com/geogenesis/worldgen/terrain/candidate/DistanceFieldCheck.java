package com.geogenesis.worldgen.terrain.candidate;

import java.util.Arrays;
import java.util.Random;

/** 精确欧氏距离与暴力最近节点对拍；FAIL 通过异常使进程非零退出。 */
public final class DistanceFieldCheck {
    private static long checks;

    public static void main(String[] args) {
        var grid = new CoastalAssembler.LandGrid(101, -32, 17, 8);
        Arrays.fill(grid.e, 1);
        grid.e[0] = -1;
        var field = CoastalAssembler.buildDistField(grid);
        equal(800, field.sea(768, 17));
        equal(Math.hypot(800, 800), field.sea(768, 817));
        equal(Math.hypot(24, 32), field.sea(-8, 49));
        equal(-8, field.dSigned(-32, 17, -1));
        equal(8, field.dSigned(-24, 17, 1));
        // 双线性查询和边缘钳位保持原有契约。
        equal(4, field.sea(-28, 17));
        equal(0, field.sea(-100, -100));
        equal(800, field.sea(900, 17));
        Random random = new Random(271828);
        for (int n : new int[]{1, 2, 3, 7, 13, 23}) {
            for (int trial = 0; trial < 16; trial++) {
                var g = new CoastalAssembler.LandGrid(n, 0, 0, trial % 2 == 0 ? 2 : 8);
                for (int i = 0; i < g.e.length; i++) {
                    g.e[i] = trial == 0 ? 1 : trial == 1 ? -1 : random.nextBoolean() ? 1 : -1;
                }
                if (trial == 2) g.e[random.nextInt(g.e.length)] = 0;
                compare(g);
            }
        }
        System.out.println("DISTANCE FIELD PASS checks=" + checks + " axis800=800 diagonal=1131.370849898476");
    }

    private static void compare(CoastalAssembler.LandGrid g) {
        var df = CoastalAssembler.buildDistField(g);
        for (int y = 0; y < g.n; y++) for (int x = 0; x < g.n; x++) {
            double sea = 1e9, land = 1e9;
            for (int j = 0; j < g.n; j++) for (int i = 0; i < g.n; i++) {
                double distance = Math.hypot(x - i, y - j);
                if (g.e[j * g.n + i] <= 0) sea = Math.min(sea, distance);
                else land = Math.min(land, distance);
            }
            equal(sea, df.toSea[y * g.n + x]);
            equal(land, df.toLand[y * g.n + x]);
            equal(sea * g.cell, df.sea(x * g.cell, y * g.cell));
            equal(land * g.cell, df.land(x * g.cell, y * g.cell));
        }
    }

    private static void equal(double expected, double actual) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-9)
            throw new AssertionError("expected=" + expected + " actual=" + actual);
    }
}
