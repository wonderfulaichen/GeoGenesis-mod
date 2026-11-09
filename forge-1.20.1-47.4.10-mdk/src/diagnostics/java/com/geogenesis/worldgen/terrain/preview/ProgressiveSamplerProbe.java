package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.ProgressiveSampler;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>后台采样器验证</b>（★ 2026-11-08 重写，S2）。
 *
 * <h2>架构变了，判据跟着变</h2>
 * <p>上一版是「渲染线程每帧补一块」，所以探针验的是「单步 ≤ 16ms」。
 * 读完 yardsite 后改成<b>后台多线程一次跑完</b>（{@code SiteMapSampler.build()} 同架构），
 * 于是判据变成：</p>
 * <ol>
 *   <li><b>{@code reset()} 立即返回</b>（不阻塞渲染线程）—— 这是架构成立的前提。</li>
 *   <li><b>后台能采完</b>，且总耗时在一个可接受量级（不卡死）。</li>
 *   <li><b>结果与同步一次性采样逐位相同</b> —— 多线程只是把工作并行，不能改变结果。</li>
 *   <li><b>同视口重复 reset 不重采</b>。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runProgressiveSamplerProbe}</p>
 */
public final class ProgressiveSamplerProbe {

    /** 本探针用的网格（与生产默认一致）。 */
    private static final int GRID = ProgressiveSampler.DEFAULT_GRID;

    private static final int ORIGIN_X = -1400;
    private static final int ORIGIN_Z = -1400;
    private static final int SPAN = 3072;
    private static final long SEED = 12345L;

    /** reset() 必须在这个时间内返回（渲染线程不能等）。 */
    private static final double RESET_BUDGET_MS = 30.0;
    /** 后台采完的上限：超过说明线程池或算法有问题。 */
    private static final double COMPLETE_BUDGET_MS = 5000.0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        System.out.println("=== 后台采样器验证（S2）===");
        System.out.printf("视口: origin=(%d,%d) span=%d 网格=%d%n%n",
                ORIGIN_X, ORIGIN_Z, SPAN, GRID);

        ProgressiveSampler sampler = new ProgressiveSampler(
                GRID, GRID);
        long viewportId = 0x1A2B3C4DL;

        // 预热（首次会初始化噪声与缓存，属冷启动一次性开销）
        long tw = System.nanoTime();
        sampler.reset(terrain, ORIGIN_X, ORIGIN_Z, SPAN, SPAN, 1L);
        while (!sampler.isComplete()) Thread.sleep(2);
        double warmupMs = (System.nanoTime() - tw) / 1e6;
        System.out.printf("[0] 预热（含冷启动）: %.1f ms%n%n", warmupMs);
        sampler.stop();

        // ---- 1) reset 立即返回 ----
        long t0 = System.nanoTime();
        sampler.reset(terrain, ORIGIN_X, ORIGIN_Z, SPAN, SPAN, viewportId);
        double resetMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("[1] reset() 耗时: %.2f ms（应≈0，不能阻塞渲染线程）%n", resetMs);

        // ---- 2) 等待后台采完 ----
        long t1 = System.nanoTime();
        int polls = 0;
        while (!sampler.isComplete() && (System.nanoTime() - t1) / 1e6 < COMPLETE_BUDGET_MS) {
            Thread.sleep(5);
            polls++;
        }
        double completeMs = (System.nanoTime() - t1) / 1e6;
        boolean finished = sampler.isComplete();
        System.out.printf("[2] 后台采完: %.1f ms（轮询 %d 次），完成=%s，进度=%.0f%%%n",
                completeMs, polls, finished, sampler.progress() * 100);

        TerrainFieldData progressive = sampler.data();
        boolean hasData = progressive != null && progressive.gridW > 0;
        System.out.printf("    数据网格: %s %dx%d%n",
                hasData, hasData ? progressive.gridW : 0, hasData ? progressive.gridH : 0);

        // ---- 3) 与同步一次性采样对拍 ----
        System.out.println("[3] 与一次性采样对拍 …");
        TerrainFieldData direct = TerrainFieldData.sampleDirect(terrain, ORIGIN_X, ORIGIN_Z,
                SPAN, SPAN, GRID, GRID, viewportId);
        int diffs = 0;
        String firstDiff = null;
        int n = progressive.gridW * progressive.gridH;
        for (int i = 0; i < n; i++) {
            if (Double.doubleToRawLongBits(progressive.e[i]) != Double.doubleToRawLongBits(direct.e[i])
                    || Double.doubleToRawLongBits(progressive.height[i])
                            != Double.doubleToRawLongBits(direct.height[i])
                    || progressive.biomeId[i] != direct.biomeId[i]
                    || progressive.terrainId[i] != direct.terrainId[i]) {
                diffs++;
                if (firstDiff == null) {
                    firstDiff = "index=" + i + " e=" + progressive.e[i] + " vs " + direct.e[i];
                }
            }
        }
        System.out.printf("    比较 %d 点，不一致 %d 个%n", n, diffs);
        if (firstDiff != null) System.out.println("    首个差异: " + firstDiff);

        // ---- 4) 同视口重复 reset 不重采 ----
        long t2 = System.nanoTime();
        sampler.reset(terrain, ORIGIN_X, ORIGIN_Z, SPAN, SPAN, viewportId);
        double reResetMs = (System.nanoTime() - t2) / 1e6;
        System.out.printf("[4] 同视口重复 reset: %.2f ms（应≈0）%n", reResetMs);

        sampler.stop();

        // ---- 判定 ----
        System.out.println();
        boolean pass = true;
        if (resetMs > RESET_BUDGET_MS) {
            System.out.printf("FAIL: reset 阻塞 %.1f ms（预算 %.0f）%n", resetMs, RESET_BUDGET_MS);
            pass = false;
        }
        if (!finished) {
            System.out.printf("FAIL: 后台未在 %.0f ms 内采完%n", COMPLETE_BUDGET_MS);
            pass = false;
        }
        if (!hasData) { System.out.println("FAIL: 无数据"); pass = false; }
        if (diffs != 0) {
            System.out.printf("FAIL: 与一次性采样不一致（%d 点）%n", diffs);
            pass = false;
        }
        if (reResetMs > 5.0) {
            System.out.printf("FAIL: 同视口 reset 耗时 %.1f ms，说明重复采样了%n", reResetMs);
            pass = false;
        }
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private ProgressiveSamplerProbe() { }
}
