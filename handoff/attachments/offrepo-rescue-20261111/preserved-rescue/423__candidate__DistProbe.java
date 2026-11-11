package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 诊断 {@link CoastalAssembler#seaDistance} 的取值分布（为什么连续性检查全部 skip）。 */
public final class DistProbe {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(java.util.Locale.ROOT);
        long SEED = 9139912035078620160L;
        var cat = new RecipeMorphCatalog(SEED);
        var land = cat.original();
        var coast = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        var ocean = new OceanRecipeCatalog(SEED + OceanRecipeCatalog.SEED_OFFSET, true);

        // 复刻 CoastalAssembler.main 里的 grid 构建
        int SIZE = 1401, STEP = 5;
        double CX = -140, CZ = 137;
        var src = new CoastalAssembler.CLandSource(land, SEED);
        int gn = (int) Math.ceil(SIZE * STEP / CoastalAssembler.DIST_CELL) + 2;
        double gx0 = CX - SIZE / 2.0 * STEP - CoastalAssembler.DIST_CELL;
        double gz0 = CZ - SIZE / 2.0 * STEP - CoastalAssembler.DIST_CELL;
        var grid = new CoastalAssembler.LandGrid(gn, gx0, gz0, CoastalAssembler.DIST_CELL);
        grid.fill(src);

        var asm = new CoastalAssembler(land, grid, CoastalAssembler.buildDistField(grid), coast, ocean,
                CoastRecipeCatalog.Recipe.SANDY_BEACH, OceanRecipeCatalog.Recipe.SHELF_RIDGE);

        int pos = 0, neg = 0, inf = 0, zero = 0;
        double mn = 1e9, mx = -1e9;
        int n = 0;
        for (double z = CZ - 3000; z <= CZ + 3000; z += 90) {
            for (double x = CX - 3000; x <= CX + 3000; x += 90) {
                double d = asm.seaDistance(x, z);
                n++;
                if (Double.isInfinite(d)) inf++;
                else if (d > 0) pos++;
                else if (d < 0) neg++;
                else zero++;
                if (Double.isFinite(d)) { mn = Math.min(mn, d); mx = Math.max(mx, d); }
            }
        }
        System.out.printf(Locale.ROOT,
                "samples=%d  pos=%d neg=%d zero=%d inf=%d  range=[%.1f, %.1f]%n",
                n, pos, neg, zero, inf, mn, mx);

        // 打印一条横线上的 d 序列
        System.out.println("--- d along z=CZ ---");
        double z = CZ;
        for (double x = CX - 2000; x <= CX + 2000; x += 100) {
            System.out.printf(Locale.ROOT, "  x=%7.0f  d=%9.1f  eLand=%+.4f%n",
                    x, asm.seaDistance(x, z), grid.at(x, z));
        }
    }

    private DistProbe() { }
}
