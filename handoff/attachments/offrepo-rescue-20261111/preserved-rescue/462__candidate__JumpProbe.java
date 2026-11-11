package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 诊断岸线跳变位置的局部 d/e 分布。 */
public final class JumpProbe {
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
                CoastRecipeCatalog.Recipe.SEA_CLIFF, OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN);

        // 目标位置
        double TX = 1332, TZ = -2800;
        System.out.printf(Locale.ROOT, "=== 局部 d/e 分布 @ (%.0f, %.0f) ===%n", TX, TZ);
        System.out.printf(Locale.ROOT, "band=%.1f%n", asm.band);
        System.out.println("  x       z      d       e        eLand   coastH(t=0)");
        for (double z = TZ - 80; z <= TZ + 80; z += 8) {
            for (double x = TX - 80; x <= TX + 80; x += 8) {
                double d = asm.seaDistance(x, z);
                double e = asm.e(x, z);
                double eL = grid.at(x, z);
                double c0 = coast.height01(CoastRecipeCatalog.Recipe.SEA_CLIFF, x, z, 0.0, Double.NaN);
                System.out.printf(Locale.ROOT,
                        "  %6.0f %6.0f  %7.1f  %+.4f  %+.4f  %+.4f%n", x, z, d, e, eL, c0);
            }
        }
    }

    private JumpProbe() { }
}
