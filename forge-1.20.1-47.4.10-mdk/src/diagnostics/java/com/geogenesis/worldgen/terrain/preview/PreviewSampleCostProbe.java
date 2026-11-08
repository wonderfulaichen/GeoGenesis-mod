package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * 全屏地形预览的<b>采样成本基准</b>（S0）。
 *
 * <h2>要回答的问题</h2>
 * <p>配置面板要改成「全屏地图」，需要知道：一帧之内能采多少格？
 * 现有 {@code LargeAreaSampler} 固定 64×64（4096 点），全屏后是否够密？
 * 加密到 128/256/512 会不会超帧预算？</p>
 *
 * <h2>为什么这是方案的唯一未验证假设</h2>
 * <p>{@code LargeAreaSampler} 的设计是「采样数与视野无关，缩放只改步长」，
 * 所以<b>成本只由网格边长决定</b>。这条约束让上面那个问题变成一个纯数字问题：
 * 量出每档的单次耗时，就能反推全屏时该用哪档。</p>
 *
 * <h2>判据</h2>
 * <ul>
 *   <li>目标：采样一屏 ≤ <b>32 ms</b>（2 帧 @60fps 的时间预算，允许分块渐进补完）；
 *       因为视口变化才重采，不是每帧都跑。</li>
 *   <li>若 256² 达标 ⇒ 全屏默认 256 档；否则降到 128 档。</li>
 *   <li>无论哪档，都必须 ≤ 200 ms 的「一次卡顿」上限，否则拖拽平移时会明显顿。</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runPreviewSampleCostProbe}</p>
 * <p>本探针只读，不写任何文件，不修改生产代码。</p>
 */
public final class PreviewSampleCostProbe {

    /** 防止 JIT 把整个循环优化掉。 */
    private static volatile double sink;

    /** 被测档位（每边格数）。64 = 现有默认。 */
    private static final int[] GRIDS = {64, 128, 256, 512};

    /** 每档重复轮数，取中位数抗噪。 */
    private static final int ROUNDS = 5;

    /** 预热轮数。 */
    private static final int WARMUP = 2;

    /** 单档达标阈值：一屏采样 ≤ 32 ms（2 帧预算）。 */
    private static final double BUDGET_OK_MS = 32.0;

    /** 单档卡顿上限：≤ 200 ms，超过会在拖拽时明显顿。 */
    private static final double BUDGET_MAX_MS = 200.0;

    /** 视口原点：取一块有陆有海的位置，避免全在同一地形里导致缓存偏差。 */
    private static final int ORIGIN_X = -1400;
    private static final int ORIGIN_Z = -1400;

    /** 视口世界尺寸（块）。对应"全屏看一大片"，与参考图 3072×1536 同量级。 */
    private static final int SPAN = 3072;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(12345L);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        System.out.println("=== 全屏预览采样成本（S0）===");
        System.out.printf("视口: origin=(%d,%d) span=%d 块%n", ORIGIN_X, ORIGIN_Z, SPAN);
        System.out.printf("判据: 达标 ≤ %.0f ms / 卡顿上限 ≤ %.0f ms%n%n",
                BUDGET_OK_MS, BUDGET_MAX_MS);

        // ---- 路径 A：Cell 全字段（现 LargeAreaSampler 走这条）----
        System.out.println("--- 路径 A: sampleCellCoarse（Cell 全字段，含气候/类型/群系）---");
        System.out.printf("%-8s %10s %12s %12s %12s %10s%n",
                "grid", "points", "median_ms", "min_ms", "max_ms", "per_point_us");

        int recommended = -1;
        int recommendedFull = -1;
        for (int g : GRIDS) {
            double[] samples = new double[ROUNDS];
            for (int w = 0; w < WARMUP; w++) {
                sampleGrid(terrain, g);
            }
            for (int r = 0; r < ROUNDS; r++) {
                long t0 = System.nanoTime();
                sampleGrid(terrain, g);
                samples[r] = (System.nanoTime() - t0) / 1e6;
            }
            java.util.Arrays.sort(samples);
            double median = samples[ROUNDS / 2];
            long points = (long) g * g;
            System.out.printf("%-8d %10d %12.2f %12.2f %12.2f %10.3f%n",
                    g, points, median, samples[0], samples[ROUNDS - 1],
                    median * 1000.0 / points);
            if (recommendedFull < 0 && median <= BUDGET_OK_MS) {
                recommendedFull = g;
            }
        }

        // ---- 路径 B：纯高度（只够画高程/坡度，不够画群系）----
        System.out.println();
        System.out.println("--- 路径 B: sampleHeightNonBlocking（纯高度，无气候/类型）---");
        System.out.printf("%-8s %10s %12s %12s %12s %10s%n",
                "grid", "points", "median_ms", "min_ms", "max_ms", "per_point_us");
        for (int g : GRIDS) {
            double[] samples = new double[ROUNDS];
            for (int w = 0; w < WARMUP; w++) {
                sampleHeightGrid(terrain, g);
            }
            for (int r = 0; r < ROUNDS; r++) {
                long t0 = System.nanoTime();
                sampleHeightGrid(terrain, g);
                samples[r] = (System.nanoTime() - t0) / 1e6;
            }
            java.util.Arrays.sort(samples);
            double median = samples[ROUNDS / 2];
            long points = (long) g * g;
            System.out.printf("%-8d %10d %12.2f %12.2f %12.2f %10.3f%n",
                    g, points, median, samples[0], samples[ROUNDS - 1],
                    median * 1000.0 / points);
            if (recommended < 0 && median <= BUDGET_OK_MS) {
                recommended = g;
            }
        }

        System.out.println();
        System.out.println("--- 结论 ---");
        System.out.printf("路径 A（全字段）达标档位: %s%n",
                recommendedFull < 0 ? "无（全部超预算）" : (recommendedFull + " 档"));
        System.out.printf("路径 B（纯高度）达标档位: %s%n",
                recommended < 0 ? "无（全部超预算）" : (recommended + " 档"));
        System.out.println();
        System.out.println("⇒ 解读：");
        System.out.println("  · 高程/坡度图层可用路径 B，能撑更高的格子密度；");
        System.out.println("  · 群系/温度/湿度图层必须用路径 A，密度受限；");
        System.out.println("  · 若路径 A 连 64 档都不达标 ⇒ 全屏必须分块渐进（先画中心）。");
        System.out.println("PASS");
    }

    /** 纯高度采样（无气候/类型），对应高程图层。 */
    private static void sampleHeightGrid(GeoGenesisTerrain terrain, int grid) {
        double step = SPAN / (double) grid;
        double sum = 0;
        for (int gx = 0; gx < grid; gx++) {
            int wx = ORIGIN_X + (int) Math.round(gx * step);
            for (int gz = 0; gz < grid; gz++) {
                int wz = ORIGIN_Z + (int) Math.round(gz * step);
                sum += terrain.sampleHeightNonBlocking(wx, wz);
            }
        }
        sink = sum;
    }

    /**
     * 按指定网格边长采一遍，覆盖 {@link #SPAN} 方块。
     *
     * <p>模拟 {@code LargeAreaSampler.sample} 的调用方式与顺序（X 主序、步长 = span/grid），
     * 保证测出的数字能直接套用到生产路径。</p>
     */
    private static void sampleGrid(GeoGenesisTerrain terrain, int grid) {
        double step = SPAN / (double) grid;
        double sum = 0;
        for (int gx = 0; gx < grid; gx++) {
            int wx = ORIGIN_X + (int) Math.round(gx * step);
            for (int gz = 0; gz < grid; gz++) {
                int wz = ORIGIN_Z + (int) Math.round(gz * step);
                Cell c = terrain.sampleCellCoarse(wx, wz);
                // 消费全部关键字段，防止 JIT 判定"算了没用"而省掉部分计算。
                sum += c.e + c.height + c.temperature + c.humidity
                        + c.precipitation + (c.terrainType == null ? 0 : c.terrainType.ordinal())
                        + (c.biomeType == null ? 0 : c.biomeType.ordinal());
            }
        }
        sink = sum;
    }

    private PreviewSampleCostProbe() { }
}
