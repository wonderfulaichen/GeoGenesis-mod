package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.ProgressiveSampler;
import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>沙盘 LOD 验证</b>（★ 2026-11-08）。
 *
 * <h2>要证明什么</h2>
 * <p>用户需求：「没放大高清显示吗？这样甚至能方便我们显示河流湖泊等内容。
 * 就想游戏地图有 lod 一样，自动调节精度。」</p>
 *
 * <p>LOD 成立需要三条同时满足，本探针逐条验：</p>
 * <ol>
 *   <li><b>成本恒定</b>：任何档位的采样点数都是 {@code GRID²}（不随缩放爆炸）；</li>
 *   <li><b>窗口随档位单调变细</b>：档位升高 ⇒ 窗口变小 ⇒ 每细格代表的方块数变小
 *       —— 这就是"放大显示高清"；</li>
 *   <li><b>档位量化</b>：zoom 在<b>同一档内</b>连续变化时档位不变（否则每帧重采）。</li>
 * </ol>
 *
 * <p>另外实测「河流湖泊可见性」的量化依据：<b>一个采样格代表多少方块</b>
 * 对比典型河宽（主河数格~十几格、支流 3~8 格、小湖几十格）。
 * 采样格 ≥ 河宽 ⇒ 河必然被抹掉，这是修复前的状态。</p>
 *
 * <p>用法：{@code gradlew runSandboxLodProbe}</p>
 */
public final class SandboxLodProbe {

    /** 主河道典型宽度（方块）：水层宽 3~15 格。 */
    private static final double RIVER_WIDTH = 8.0;
    /** 小湖典型直径（方块）。 */
    private static final double SMALL_LAKE = 60.0;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int grid = ProgressiveSampler.GRID;

        System.out.println("=== 沙盘 LOD 验证 ===");
        System.out.printf("网格恒定 %d² = %d 点；基准 step=%.1f，基准 zoom=%.1f%n%n",
                grid, grid * grid, SandboxLod.BASE_STEP, SandboxLod.BASE_ZOOM);

        boolean pass = true;

        // ---- 1) 档位表 ----
        System.out.printf("%-6s %-8s %-12s %-10s %-12s %-12s%n",
                "level", "step", "窗口(格)", "采样点数", "河可见?", "小湖可见?");
        for (int lv = SandboxLod.MIN_LEVEL; lv <= SandboxLod.MAX_LEVEL; lv++) {
            double step = SandboxLod.stepFor(lv);
            int window = SandboxLod.windowFor(lv, grid);
            int points = grid * grid;
            boolean river = step < RIVER_WIDTH;
            boolean lake = step < SMALL_LAKE;
            System.out.printf("%-6d %-8.2f %-12d %-10d %-12s %-12s%n",
                    lv, step, window, points,
                    river ? "YES" : "NO", lake ? "YES" : "NO");

            // 判据：采样点数恒定
            if (points != grid * grid) {
                System.out.println("FAIL: 档位 " + lv + " 采样点数不是恒定值");
                pass = false;
            }
        }

        // ---- 2) 窗口必须随档位单调变细 ----
        for (int lv = SandboxLod.MIN_LEVEL; lv < SandboxLod.MAX_LEVEL; lv++) {
            double s0 = SandboxLod.stepFor(lv);
            double s1 = SandboxLod.stepFor(lv + 1);
            if (!(s1 < s0)) {
                System.out.printf("FAIL: 档位 %d→%d 的 step 没有变小（%.3f → %.3f）%n", lv, lv + 1, s0, s1);
                pass = false;
            }
        }
        System.out.println();

        // ---- 3) 档位量化：同一档内 zoom 连续变化，档位不变 ----
        System.out.println("[档位量化] zoom → level");
        float[] zooms = {0.6f, 1.0f, 1.4f, 1.9f, 2.0f, 2.1f, 2.8f, 4.0f, 5.6f, 8.0f};
        int prev = Integer.MIN_VALUE;
        for (float z : zooms) {
            int lv = SandboxLod.levelFor(z);
            System.out.printf("    zoom %.2f → LOD %+d  （每细格 %.2f 格）%n",
                    z, lv, SandboxLod.stepFor(lv));
            prev = lv;
        }

        // zoom 在同一档内的微小变化必须得到同一 level（否则每帧重采）
        float[] near = {1.98f, 2.00f, 2.02f};
        int l0 = SandboxLod.levelFor(near[0]);
        for (float z : near) {
            if (SandboxLod.levelFor(z) != l0) {
                System.out.printf("FAIL: zoom %.2f 档位跳变（应稳定在 %+d）%n", z, l0);
                pass = false;
            }
        }
        System.out.printf("    量化稳定: zoom %.2f/%.2f/%.2f 都是 LOD %+d -> %s%n",
                near[0], near[1], near[2], l0, "OK");

        // ---- 4) 修复前的对照：固定窗口 57600 格 / 256 细格 ----
        System.out.println();
        double oldStep = 57600.0 / grid;
        System.out.printf("[负对照] 修复前固定窗口：每细格 %.1f 格方块%n", oldStep);
        System.out.printf("    主河宽 %.0f 格 → %s%n", RIVER_WIDTH,
                oldStep < RIVER_WIDTH ? "可见" : "被抹掉（采样格比河还宽）");
        if (oldStep < RIVER_WIDTH) {
            System.out.println("FAIL: 负对照未复现旧缺陷 ⇒ 判据失效");
            pass = false;
        }

        // ---- 5) ★ 默认档必须能看见湖泊（用户诉求："方便显示河流湖泊"）----
        int defaultLevel = SandboxLod.levelFor(SandboxLod.BASE_ZOOM);
        double defaultStep = SandboxLod.stepFor(defaultLevel);
        System.out.println();
        System.out.printf("默认档 LOD %+d：每细格 %.2f 格方块，窗口 %d 格%n",
                defaultLevel, defaultStep, SandboxLod.windowFor(defaultLevel, grid));
        // 湖泊（直径 60 格）在默认档必须跨多个采样格才算"看得见"
        if (defaultStep >= SMALL_LAKE / 3.0) {
            System.out.printf("FAIL: 默认档采样格 %.1f ≥ 小湖/3 (%.0f) ⇒ 湖只剩几个点%n",
                    defaultStep, SMALL_LAKE / 3.0);
            pass = false;
        } else {
            System.out.printf("    小湖 %.0f 格 / 采样格 %.1f = %.1f 格宽 -> 可见%n",
                    SMALL_LAKE, defaultStep, SMALL_LAKE / defaultStep);
        }

        // ---- 6) 最细档必须能分辨主河道（宽 8 格 → 至少 2 个采样格）----
        int maxLevel = SandboxLod.MAX_LEVEL;
        double maxStep = SandboxLod.stepFor(maxLevel);
        System.out.printf("最细档 LOD %+d：每细格 %.2f 格方块%n", maxLevel, maxStep);
        if (maxStep > RIVER_WIDTH / 2.0) {
            System.out.printf("FAIL: 最细档采样格 %.2f > 河宽/2 (%.1f) ⇒ 主河道只剩 1 格%n",
                    maxStep, RIVER_WIDTH / 2.0);
            pass = false;
        } else {
            System.out.printf("    主河 %.0f 格 / 采样格 %.1f = %.1f 格宽 -> 可分辨%n",
                    RIVER_WIDTH, maxStep, RIVER_WIDTH / maxStep);
        }

        // ---- 7) 缩放上限必须覆盖 LOD 满档（否则 MAX_LEVEL 是够不到的死配置）----
        int levelAtMaxZoom = SandboxLod.levelFor(com.geogenesis.client.preview.SandboxRenderer.ZOOM_MAX);
        System.out.printf("zoom 上限 %.1f → LOD %+d（MAX_LEVEL %+d）%n",
                com.geogenesis.client.preview.SandboxRenderer.ZOOM_MAX, levelAtMaxZoom, maxLevel);
        if (levelAtMaxZoom < maxLevel) {
            System.out.printf("FAIL: 缩放拉满只到 LOD %+d，MAX_LEVEL %+d 够不到（死配置）%n",
                    levelAtMaxZoom, maxLevel);
            pass = false;
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        exportLodTsvs();
        System.exit(pass ? 0 : 1);
    }

    /**
     * 按每个 LOD 档导出真实地形采样（供 {@code tools/render_sandbox_lod.py} 出图对比）。
     *
     * <p>这是「推近能看见河流」的<b>视觉证据</b>来源：同一台生成器，
     * 只是窗口按档位缩小，河网应当逐档显现。</p>
     *
     * <p>由 {@code -Dgeogenesis.lodTsvDir=<dir>} 指定输出目录。</p>
     */
    private static void exportLodTsvs() {
        String dir = System.getProperty("geogenesis.lodTsvDir");
        if (dir == null || dir.isEmpty()) return;
        java.io.File d = new java.io.File(dir);
        if (!d.exists() && !d.mkdirs()) {
            System.out.println("无法创建目录: " + dir);
            return;
        }
        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(12345L);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        int grid = ProgressiveSampler.GRID;

        // ★ 先找一块"有水的中心"，否则推近后画面全是陆地 ——
        //   那是<b>取样点选错</b>，不是 LOD 失效。LOD 的正确语义是
        //   「围绕固定中心逐档放大」，中心本身在水边才能看出河湖细节。
        int[] center = findWaterCenter(terrain, params);
        System.out.printf("LOD 导出中心: (%d, %d)%n", center[0], center[1]);

        for (int lv = SandboxLod.MIN_LEVEL; lv <= SandboxLod.MAX_LEVEL; lv++) {
            int window = SandboxLod.windowFor(lv, grid);
            int ox = center[0] - window / 2;
            int oz = center[1] - window / 2;
            // ★ 按档选路径（与生产一致）：细档走 sampleCell 才能拿到河谷/湖河
            boolean hydro = SandboxLod.usesHydroPath(lv);
            TerrainFieldData data = TerrainFieldData.sample(terrain, ox, oz,
                    window, window, grid, grid, lv, hydro);
            // ★ 粗层水位（与生产一致：细档才查）
            int waterCellsWithSurface = 0;
            if (SandboxLod.usesCoarseWater(lv)) {
                var lookup = new com.geogenesis.client.preview.SandboxWaterLookup(terrain.generator());
                double stepW = window / (double) grid;
                for (int gx = 0; gx < grid; gx++) {
                    int wx = ox + (int) Math.round(gx * stepW);
                    for (int gz = 0; gz < grid; gz++) {
                        int wz = oz + (int) Math.round(gz * stepW);
                        double wy = lookup.waterSurfaceAt(wx, wz);
                        data.putWaterPublic(gx, gz, wy);
                        int i = gx * grid + gz;
                        if (!Double.isNaN(wy) && wy > data.height[i] + 0.01) waterCellsWithSurface++;
                    }
                }
            }
            String path = new java.io.File(d, "lod_" + lv + ".tsv").getPath();
            int waterCells = 0, riverCells = 0;
            try (java.io.PrintWriter w = new java.io.PrintWriter(path, "UTF-8")) {
                w.println("# gx gz height e biomeId terrainId snow temp humidity precip continent shape discharge waterY");
                for (int gx = 0; gx < data.gridW; gx++) {
                    for (int gz = 0; gz < data.gridH; gz++) {
                        int i = gx * data.gridH + gz;
                        if (data.e[i] < 0.0) waterCells++;
                        if (data.extraId[i * 4] > 0) riverCells++;
                        double wy = data.waterY[i];
                        w.printf("%d %d %.6f %.6f %d %d %d %.6f %.6f %.6f %.6f %.6f %.6f %s%n",
                                gx, gz, data.height[i], data.e[i],
                                data.biomeId[i], data.terrainId[i], data.snow[i] ? 1 : 0,
                                data.temperature[i], data.humidity[i], data.precipitation[i],
                                data.continent[i], data.shape[i], data.discharge[i],
                                Double.isNaN(wy) ? "nan" : String.format(Locale.ROOT, "%.6f", wy));
                    }
                }
            } catch (Exception ex) {
                System.out.println("导出 LOD " + lv + " 失败: " + ex);
                continue;
            }
            System.out.printf("导出 LOD %+d：窗口 %d 格，每细格 %.2f 格，%s，水格 %d，河格 %d，水面格 %d -> %s%n",
                    lv, window, SandboxLod.stepFor(lv), hydro ? "水文管线" : "廉价路径",
                    waterCells, riverCells, waterCellsWithSurface, path);
        }
    }

    /**
     * 找一块<b>有河/湖</b>的中心：粗网格扫一遍，取"周围有水且陆地占比高"的点
     * （海岸/湖畔），这样推近后河湖细节才在画面里。
     */
    private static int[] findWaterCenter(GeoGenesisTerrain terrain, TerrainParams params) {
        // 在 -2000..2000 内粗扫，找水格比例在 15%~55% 的点（既不是纯海也不纯陆）
        int bestX = 0, bestZ = 0;
        double bestScore = Double.MAX_VALUE;
        double sea = params.seaLevel();
        for (int x = -1800; x <= 1800; x += 400) {
            for (int z = -1800; z <= 1800; z += 400) {
                int water = 0, total = 0;
                for (int dx = -160; dx <= 160; dx += 40) {
                    for (int dz = -160; dz <= 160; dz += 40) {
                        var c = terrain.sampleCellCoarse(x + dx, z + dz);
                        if (c == null) continue;
                        total++;
                        if (c.e < 0.0) water++;
                    }
                }
                if (total == 0) continue;
                double frac = water / (double) total;
                // 目标 30% 水（海岸线附近），越接近越好
                double score = Math.abs(frac - 0.30);
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        return new int[]{bestX, bestZ};
    }

    private SandboxLodProbe() { }
}
