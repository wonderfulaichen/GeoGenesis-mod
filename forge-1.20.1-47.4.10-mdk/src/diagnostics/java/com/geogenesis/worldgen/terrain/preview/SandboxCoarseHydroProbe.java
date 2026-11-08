package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.worldgen.hydrology.model.HierarchicalHydrology;
import com.geogenesis.worldgen.hydrology.model.LakeWaterBalance;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * <b>粗层水文给沙盘用的可行性实测</b>（★ 2026-11-08）。
 *
 * <h2>背景：细层不可用，粗层可能可用</h2>
 * <p>上一轮实测证明 {@code sampleCell}（完整管线）每触发一个新水文 region 要
 * <b>3.6~10.9 秒</b>（{@code [HYDRO-MODEL] region solved in 10862 ms}），
 * 沙盘 65536 个采样点反复触发 ⇒ 分钟级，不可用。</p>
 *
 * <p>但 {@code HierarchicalHydrology} 有两层：</p>
 * <ul>
 *   <li><b>细层</b>：{@code REGION=512} + 侵蚀 tile，贵；</li>
 *   <li><b>粗层</b>：{@code buildCoarse(gen, params, rx, rz, climate)}，
 *       grid 约 ⟨(512+208)/4⟩² ≈ 180²，<b>不做侵蚀 tile、prewarm=false</b>，
 *       并直接给出 {@code levelAt / lakeAt / waterSurfaceAt / bedAt} 的
 *       <b>按绝对坐标查询接口</b>。</li>
 * </ul>
 *
 * <p>用户要求「河道水面必须」。本探针量三件事：</p>
 * <ol>
 *   <li>粗层单 tract 求解耗时（能否接受）；</li>
 *   <li>沙盘一个 LOD 窗口覆盖几个 tract（决定要解几次）；</li>
 *   <li>粗层水位在真实地形上是否真的给出"河道"（非 NaN 占比、与海平面/陆地的关系）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxCoarseHydroProbe}</p>
 */
public final class SandboxCoarseHydroProbe {

    private static final long SEED = 12345L;
    /** 粗层单 tract 成本预算（毫秒）：沙盘细档窗口最多覆盖 4 个 tract，需 &lt; 1.5s。 */
    private static final double TRACT_BUDGET_MS = 1500.0;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        var hp = HierarchicalHydrology.Params.defaults();
        int tract = hp.tract();
        int step = hp.step();
        System.out.println("=== 粗层水文可用性 ===");
        System.out.printf("tract=%d 粗层step=%d 粗层半径=%d（网格约 %d²）%n%n",
                tract, step, hp.coarseRadius(),
                (2 * hp.coarseRadius()) / step);

        // 与 ModelHydrologyProvider.CLIMATE 同值（那处是 private，见其第 208-209 行：
        // "与全部探针/契约验证一致的固定气候"）
        LakeWaterBalance.Climate climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        // ---- 1) 单 tract 求解耗时（冷 / 热）----
        long t0 = System.nanoTime();
        HierarchicalHydrology.Coarse c0 = HierarchicalHydrology.buildCoarse(
                gen, hp, 0, 0, climate);
        double coldMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("[1] 单 tract 冷启动: %.0f ms（粗层网格 %d²）%n", coldMs, c0.n() * c0.n());

        long t1 = System.nanoTime();
        HierarchicalHydrology.Coarse c1 = HierarchicalHydrology.buildCoarse(
                gen, hp, 0, 1, climate);
        double warmMs = (System.nanoTime() - t1) / 1e6;
        System.out.printf("    相邻 tract（已热）: %.0f ms%n%n", warmMs);

        // ---- 2) 一个沙盘窗口覆盖几个 tract ----
        // 最细档窗口约 1000 格，基准档 3994 格
        for (int window : new int[]{998, 1997, 3994}) {
            int tractsAcross = (int) Math.ceil(window / (double) tract) + 1;
            System.out.printf("[2] 窗口 %d 格 ⇒ 约 %d×%d = %d 个 tract%n",
                    window, tractsAcross, tractsAcross, tractsAcross * tractsAcross);
        }
        System.out.println();

        // ---- 3) 粗层是否真的给出"河道" ----
        // 在一个 tract 内扫，统计：非 NaN 水位占比、湖格数、水面高于地形的格数（= 有水的格）
        int n = c0.n();
        int nonNaN = 0, lake = 0, aboveGround = 0, total = 0;
        double maxDepth = 0;
        for (int bx = c0.originX(); bx < c0.originX() + n * step; bx += step) {
            for (int bz = c0.originZ(); bz < c0.originZ() + n * step; bz += step) {
                total++;
                double ws = c0.waterSurfaceAt(bx, bz);
                if (Double.isNaN(ws)) continue;
                nonNaN++;
                if (c0.lakeAt(bx, bz)) lake++;
                double ground = terrain.sampleHeightNonBlocking(bx, bz);
                double depth = ws - ground;
                if (depth > 0.01) {
                    aboveGround++;
                    if (depth > maxDepth) maxDepth = depth;
                }
            }
        }
        System.out.printf("[3] tract (0,0) 粗层扫描 %d 格：%n", total);
        System.out.printf("    有效水位 %d (%.1f%%)，湖格 %d，水面高于地形 %d (%.2f%%)，最大水深 %.2f%n",
                nonNaN, 100.0 * nonNaN / total, lake, aboveGround,
                100.0 * aboveGround / total, maxDepth);

        // ---- 4) 判定 ----
        System.out.println();
        boolean pass = true;
        if (coldMs > TRACT_BUDGET_MS * 4) {
            System.out.printf("FAIL: 粗层冷启动 %.0f ms 超预算（%.0f ms × 4）%n",
                    coldMs, TRACT_BUDGET_MS);
            pass = false;
        } else {
            System.out.printf("粗层成本 %.0f ms/tract ⇒ 沙盘细档（1~4 tract）需 %.1f~%.0f ms%n",
                    coldMs, coldMs, coldMs * 4);
        }
        if (nonNaN == 0) {
            System.out.println("FAIL: 粗层没有任何有效水位 ⇒ 接了也没用");
            pass = false;
        }
        if (aboveGround == 0) {
            System.out.println("FAIL: 粗层没有任何'水面高于地形'的格 ⇒ 画不出河道水面");
            pass = false;
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxCoarseHydroProbe() { }
}
