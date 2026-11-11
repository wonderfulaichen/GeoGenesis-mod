package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 诊断 DistField 的 d 分布（为什么 inland 全 0）。 */
public final class DistProbe2 {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(java.util.Locale.ROOT);
        long SEED = 9139912035078620160L;
        var cat = new RecipeMorphCatalog(SEED);
        var land = cat.original();
        var coast = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        var ocean = new OceanRecipeCatalog(SEED + OceanRecipeCatalog.SEED_OFFSET, true);

        int SIZE = 1401, STEP = 5;
        double CX = -140, CZ = 137;
        var src = new CoastalAssembler.CLandSource(land, SEED);
        int gn = (int) Math.ceil(SIZE * STEP / CoastalAssembler.DIST_CELL) + 2;
        double gx0 = CX - SIZE / 2.0 * STEP - CoastalAssembler.DIST_CELL;
        double gz0 = CZ - SIZE / 2.0 * STEP - CoastalAssembler.DIST_CELL;
        var grid = new CoastalAssembler.LandGrid(gn, gx0, gz0, CoastalAssembler.DIST_CELL);
        grid.fill(src);
        var df = CoastalAssembler.buildDistField(grid);

        var asm = new CoastalAssembler(land, grid, df, coast, ocean,
                CoastRecipeCatalog.Recipe.SANDY_BEACH, OceanRecipeCatalog.Recipe.SHELF_RIDGE);

        int over = 0, under = 0, neg = 0;
        double mn = 1e9, mx = -1e9;
        int n = 0;
        for (double z = CZ - 3000; z <= CZ + 3000; z += 90) {
            for (double x = CX - 3000; x <= CX + 3000; x += 90) {
                double d = asm.seaDistance(x, z);
                n++;
                if (d < 0) neg++;
                else if (d >= CoastalAssembler.BAND) over++;
                else under++;
                mn = Math.min(mn, d); mx = Math.max(mx, d);
            }
        }
        System.out.printf(Locale.ROOT,
                "samples=%d  d>=BAND(%.0f)=%d  0<=d<BAND=%d  d<0=%d  range=[%.1f, %.1f]%n",
                n, CoastalAssembler.BAND, over, under, neg, mn, mx);

        System.out.println("--- d along z=CZ ---");
        for (double x = CX - 2000; x <= CX + 2000; x += 200) {
            double d = asm.seaDistance(x, CZ);
            double e = asm.e(x, CZ);
            System.out.printf(Locale.ROOT,
                    "  x=%7.0f  d=%9.1f  u=%+5.2f  e=%+.4f  eLand=%+.4f%n",
                    x, d, d / CoastalAssembler.BAND, e, grid.at(x, CZ));
        }
    }

    private DistProbe2() { }
}
