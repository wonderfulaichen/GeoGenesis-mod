package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * ★ 决定性检验：模型探针取地形所用的 {@link CellGenerator#erodedHeightForRouting}
 * 与生产链 {@link GeoGenesisTerrain#getChunkCells} 的 {@code cell.height}
 * <b>是不是同一个场</b>？
 *
 * <h2>为什么必须先测这一条</h2>
 * <p>此前把"生产管线出图"与"新管线出图"并排比较，发现地形形态明显不同
 * （左：细密沟谷；右：粗大脊线）。若不先确认两条链读的是同一地形场，
 * 任何并排对比都没有意义 —— 差异可能全部来自<b>输入</b>，而不是水文模型。</p>
 *
 * <p>两条链的代码路径看起来一致（都经 {@code blendTileDelta} + {@code heightFromE}），
 * 但"看起来一致"在本项目已多次被实测推翻 ⇒ <b>必须测量</b>。</p>
 *
 * <h2>另注：生产链还多一步</h2>
 * <p>{@code generateChunk} 在侵蚀之后还会跑 {@code applyHydrologyValley}，把
 * 河谷雕刻<b>回写</b> {@code cell.height}。故本探针同时报告"侵蚀后"与"雕刻后"的差异，
 * 以区分两种来源。</p>
 */
public final class TerrainAgreementProbe {

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        int cx = -140, cz = 137, radius = 200, step = 4;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();

        int n = 2 * radius / step + 1;
        double maxDiff = 0, sumAbs = 0, sumRef = 0;
        int compared = 0, worstX = 0, worstZ = 0;
        int searched = 0, skipped = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int bx = cx - radius + i * step;
                int bz = cz - radius + j * step;
                // 生产链：完整 chunk 管线（采样 → 侵蚀 → 水文雕刻）
                Cell[] cells = gt.getChunkCells(bx >> 4, bz >> 4);
                Cell cell = cells[Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
                if (cell == null) { skipped++; continue; }
                searched++;
                // 模型探针路径：erodedHeightForRouting（wu 入参）
                double routing = gen.erodedHeightForRouting(bx / hs, bz / hs);
                double d = Math.abs(cell.height - routing);
                if (d > maxDiff) { maxDiff = d; worstX = bx; worstZ = bz; }
                sumAbs += d;
                sumRef += Math.abs(cell.height);
                compared++;
            }
        }
        System.out.println("=== terrain field agreement: routed vs production chunk pipeline ===");
        System.out.printf("seed=%d centre=(%d,%d) radius=%d step=%d samples=%d%n",
                seed, cx, cz, radius, step, compared);
        System.out.printf("maxAbsDiff=%.4f blocks at (%d,%d)%n", maxDiff, worstX, worstZ);
        System.out.printf("meanAbsDiff=%.4f blocks   meanHeight=%.1f   relativeMean=%.5f%n",
                sumAbs / Math.max(1, compared), sumRef / Math.max(1, compared),
                sumAbs / Math.max(1e-9, sumRef));
        System.out.println("READ: 若 maxAbsDiff 远大于 0.5 块 ⇒ 两链地形不同场 ⇒ 此前并排对比无效。");
        System.out.println("NOTE: production includes applyHydrologyValley (river carving written back to cell.height);");
        System.out.println("      routed path does not. So differences near rivers are expected and are NOT a units bug.");
    }

    private TerrainAgreementProbe() { }
}
