package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>沙盘「看见河流」的采样路径成本实测</b>（★ 2026-11-08）。
 *
 * <h2>为什么要测这个</h2>
 * <p>用户需求：「这样甚至能方便我们显示河流湖泊等内容」。
 * 但沙盘当前用的 {@code sampleCellCoarse} <b>不做河谷雕刻、不生成河网</b>
 * （{@code GeoGenesisTerrain} 第 265-282 行的注释写明：
 * "不保证与最终落块地形一致（相差侵蚀增量 + 河谷雕刻）"）
 * ⇒ <b>无论 LOD 多细，都看不到河流</b>。这不是分辨率问题。
 *
 * <p>能拿到水文的是 {@code sampleCell}（完整管线：生成整个区块）。
 * 但它是否能在沙盘 65536 个采样点上用，取决于单点成本 —— 本探针就是量这个。</p>
 *
 * <h2>判据</h2>
 * <p>目标：沙盘重采（一次 LOD 跨档）控制在 <b>3 秒</b>内（后台线程跑，用户可接受）。
 * 65536 点 ⇒ 单点必须 &lt; 45.8 µs。</p>
 *
 * <p>用法：{@code gradlew runSandboxHydroCostProbe}</p>
 */
public final class SandboxHydroCostProbe {

    private static final int ORIGIN_X = -1200;
    private static final int ORIGIN_Z = -1200;
    /** 采样点数（与沙盘 GRID² 同量级，但这里只需量单点成本）。 */
    private static final int SAMPLES = 400;
    /** 单点成本预算（µs）：65536 点 × 45.8µs ≈ 3 秒。 */
    private static final double BUDGET_US = 45.8;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(12345L);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        System.out.println("=== 沙盘水文采样路径成本 ===");
        System.out.printf("取样 %d 点，窗口 %d 格%n%n", SAMPLES, 1536);
        System.out.printf("%-26s %-14s %-14s %-12s%n",
                "路径", "单点(µs)", "65536点(ms)", "有水文?");

        // 预热（噪声/缓存冷启动）
        for (int i = 0; i < 20; i++) terrain.sampleCellCoarse(ORIGIN_X + i, ORIGIN_Z);

        double coarseUs = timePath(terrain, false);
        double fullUs = timePath(terrain, true);

        System.out.printf("%-26s %-14.2f %-14.0f %-12s%n",
                "sampleCellCoarse", coarseUs, coarseUs * 65536 / 1000.0, "否");
        System.out.printf("%-26s %-14.2f %-14.0f %-12s%n",
                "sampleCell(完整管线)", fullUs, fullUs * 65536 / 1000.0, "是");

        // ---- 判定 ----
        System.out.println();
        boolean pass = true;
        System.out.printf("完整管线单点成本 %.2f µs（预算 %.1f µs）%n", fullUs, BUDGET_US);
        if (fullUs > BUDGET_US) {
            System.out.println("FAIL: 完整管线单点超预算 ⇒ 沙盘不能整体走它（需按档分层）");
            pass = false;
        } else {
            System.out.println("   ⇒ 完整管线可在沙盘整体使用（重采 < 3s）");
        }

        // 至少要让用户知道量级差
        System.out.printf("两条路径差 %.1f 倍%n", fullUs / Math.max(1e-9, coarseUs));

        // ---- 水文数据是否真的不同（证明 coarse 看不到河） ----
        System.out.println();
        int differ = 0;
        for (int i = 0; i < 60; i++) {
            int wx = ORIGIN_X + i * 7, wz = ORIGIN_Z + i * 11;
            Cell a = terrain.sampleCellCoarse(wx, wz);
            Cell b = terrain.sampleCell(wx, wz);
            if (a == null || b == null) continue;
            if (Math.abs(a.height - b.height) > 0.5) differ++;
        }
        System.out.printf("高度不一致的取样点: %d/60（coarse 缺河谷雕刻）%n", differ);
        if (differ == 0) {
            System.out.println("FAIL: 两条路径高度一致 ⇒ 本判据的前提不成立（需复核）");
            pass = false;
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private static double timePath(GeoGenesisTerrain terrain, boolean full) {
        long t0 = System.nanoTime();
        int n = 0;
        for (int i = 0; i < SAMPLES; i++) {
            int wx = ORIGIN_X + (i % 20) * 40;
            int wz = ORIGIN_Z + (i / 20) * 40;
            Cell c = full ? terrain.sampleCell(wx, wz) : terrain.sampleCellCoarse(wx, wz);
            if (c != null) n++;
        }
        long dt = System.nanoTime() - t0;
        return n == 0 ? 0 : dt / 1000.0 / n;
    }

    private SandboxHydroCostProbe() { }
}
