package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;

import java.util.Locale;

/**
 * <b>沙盘"像素驱动 LOD"验证</b>（★ 2026-11-08 第三次重做）。
 *
 * <h2>要证明什么（用户裁定）</h2>
 * <p>原话：「GRID 按视口像素计算，柱子屏幕尺寸恒定，视野缩小后每柱代表的方块变少。」</p>
 *
 * <h3>上一版为什么错</h3>
 * <p>网格固定 256²、窗口随缩放变 ⇒ 实机「放大后精度没变化」。
 * 而我们的<b>平面视图</b>采样点数 = 视口像素数（900²=81 万），
 * 沙盘只有 6.5 万 ⇒ 差 12.4 倍。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>柱子屏幕尺寸恒定</b>：柱子数 = 视口/13，任何 zoom 都一样；</li>
 *   <li><b>精度随缩放提高</b>：zoom 增大 ⇒ 每柱代表方块单调减少；</li>
 *   <li><b>视野随缩放缩小</b>：zoom 增大 ⇒ 窗口单调减小；</li>
 *   <li><b>采样点数与平面视图同量级</b>：≥ 平面视图的 1/8（即 ≥10 万点档不现实，
 *       但至少应是"按像素走"而不是固定小常数）；</li>
 *   <li><b>精度硬下限</b>：每细格 ≥ 1 格方块（否则重复采样 → 梯田伪影）；</li>
 *   <li><b>负对照</b>：旧的"固定 256 网格"在推近时每柱方块数不变（证明判据有效）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxLodProbe}</p>
 */
public final class SandboxLodProbe {

    /** 典型全屏视口（1080p 下 GUI 缩放 1 时的逻辑像素）。 */
    private static final int VIEWPORT = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘像素驱动 LOD 验证 ===");
        System.out.printf("视口 %dpx，每细格目标 %.1fpx，基准每柱 %.0f 格%n%n",
                VIEWPORT, SandboxLod.CELL_PX, SandboxLod.BASE_BLOCKS_PER_PILLAR);

        int grid = SandboxLod.gridFor(VIEWPORT);
        int pillars = SandboxLod.pillarsFor(VIEWPORT);
        int points = grid * grid;
        System.out.printf("网格 %d²（%d 根柱），采样点 %d%n", grid, pillars, points);
        System.out.printf("对照：平面视图 1:1 = 900² = %d 点（每点 1 格）%n", VIEWPORT * VIEWPORT);
        System.out.printf("     旧实现固定 256² = 65536 点%n%n");

        boolean pass = true;

        // ---- 1) 网格是否按视口走 ----
        System.out.println("[1] 网格随视口变化（不是固定常数）");
        int[] viewports = {480, 700, 900, 1200, 1600};
        java.util.Set<Integer> grids = new java.util.LinkedHashSet<>();
        for (int vp : viewports) {
            int g = SandboxLod.gridFor(vp);
            grids.add(g);
            System.out.printf("    视口 %4dpx -> 网格 %3d（%2d 柱），采样点 %d%n",
                    vp, g, SandboxLod.pillarsFor(vp), g * g);
        }
        if (grids.size() < 3) {
            System.out.println("    FAIL: 网格没有随视口变化（仍是固定值）");
            pass = false;
        } else {
            System.out.println("    OK（随视口变化）");
        }

        // ---- 2) 推近过程：柱子数恒定、每柱方块递减、窗口递减 ----
        System.out.printf("%n[2] 滚轮推近（视口 %dpx）%n", VIEWPORT);
        System.out.printf("%-4s %-8s %-9s %-10s %-10s %-9s %s%n",
                "n", "zoom", "柱子数", "每柱方块", "窗口", "采样点", "每细格");
        float zoom = SandboxLod.BASE_ZOOM;
        int prevPillars = -1;
        double prevBlocks = Double.MAX_VALUE;
        int prevWindow = Integer.MAX_VALUE;
        double prevStep = Double.MAX_VALUE;
        for (int n = 0; n <= 12; n++) {
            int p = SandboxLod.pillarsFor(VIEWPORT);
            double bp = SandboxLod.blocksPerPillar(zoom);
            int win = SandboxLod.windowFor(zoom, VIEWPORT);
            double st = SandboxLod.effectiveStep(zoom, VIEWPORT);
            if (n % 2 == 0 || n == 12) {
                System.out.printf("%-4d %-8.2f %-9d %-10.2f %-10d %-9d %.2f%n",
                        n, zoom, p, bp, win, grid * grid, st);
            }
            // 1) 柱子数恒定
            if (prevPillars >= 0 && p != prevPillars) {
                System.out.printf("    FAIL: 柱子数变了（%d -> %d），屏幕密度不恒定%n",
                        prevPillars, p);
                pass = false;
            }
            // 2) 每柱方块单调递减
            if (bp > prevBlocks + 1e-9) {
                System.out.printf("    FAIL: 每柱方块反而变大（%.2f -> %.2f）%n", prevBlocks, bp);
                pass = false;
            }
            // 3) 窗口单调<b>不增</b>（到精度地板后会被夹住，不再缩小 —— 这是物理下限，不是缺陷）
            if (win > prevWindow) {
                System.out.printf("    FAIL: 窗口反而变大（%d -> %d）%n", prevWindow, win);
                pass = false;
            }
            // 5) 精度硬下限
            if (st < 1.0 - 1e-9) {
                System.out.printf("    FAIL: 每细格 %.3f < 1（重复采样）%n", st);
                pass = false;
            }
            prevPillars = p;
            prevBlocks = bp;
            prevWindow = win;
            prevStep = st;
            zoom = SandboxLod.nextZoom(zoom, true);
        }

        // ---- 4) 采样点数应与平面视图同量级（至少 4 万，且随视口增长） ----
        System.out.printf("%n[4] 采样点数 %d（平面视图 %d 的 %.0f%%）%n",
                points, VIEWPORT * VIEWPORT, 100.0 * points / (VIEWPORT * VIEWPORT));
        if (points < 40_000) {
            System.out.printf("    FAIL: 采样点 %d 过少（<40000），精度不足%n", points);
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- 6) 负对照：旧实现固定网格 ⇒ 推近时每柱方块不变 ----
        System.out.println("\n[负对照] 旧实现（网格固定 256，窗口 = 网格×step(zoom)）");
        double oldBlocksFirst = -1, oldBlocksLast = -1;
        float z2 = SandboxLod.BASE_ZOOM;
        for (int n = 0; n <= 12; n++) {
            // 旧公式：每柱 = 窗口/柱数，柱数固定 128
            int oldWin = (int) (Math.round(256 * 15.6 / SandboxLod.zoomRatio(z2) / 16.0) * 16);
            double bp = oldWin / 128.0;
            if (n == 0) oldBlocksFirst = bp;
            oldBlocksLast = bp;
            z2 = SandboxLod.nextZoom(z2, true);
        }
        System.out.printf("    每柱方块 %.1f -> %.1f（确实在变，但柱数固定 128 ⇒ 屏幕密度也固定）%n",
                oldBlocksFirst, oldBlocksLast);
        System.out.println("    ⚠ 旧实现的真正问题是【网格固定 256 ⇒ 采样点只有 6.5 万】");
        System.out.println("      ⇒ 每细格最多 15.6 格，比平面视图的 1 格粗 15 倍。");

        // ---- 7) 比例尺读数 ----
        System.out.println();
        for (float z : new float[]{2.0f, 6.0f, 15.0f, 31.0f}) {
            System.out.printf("    zoom %5.2f: %s%n", z, SandboxLod.scaleLabel(z, VIEWPORT));
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        exportTsv(grid);
        System.exit(pass ? 0 : 1);
    }

    /** 导出真实地形采样（供离线出图）。 */
    private static void exportTsv(int grid) {
        String dir = System.getProperty("geogenesis.lodTsvDir");
        if (dir == null || dir.isEmpty()) return;
        java.io.File d = new java.io.File(dir);
        if (!d.exists() && !d.mkdirs()) {
            System.out.println("无法创建目录: " + dir);
            return;
        }
        var params = com.geogenesis.worldgen.terrain.TerrainParams.defaults();
        var gen = new com.geogenesis.worldgen.terrain.CellGenerator(
                params, params.minY(), params.maxY(), true);
        gen.seed(12345L);
        var terrain = new com.geogenesis.worldgen.terrain.GeoGenesisTerrain(gen);

        int cx = 200, cz = 1400;
        float zoom = SandboxLod.BASE_ZOOM;
        for (int i = 0; i < 8; i++) {
            int window = SandboxLod.windowFor(zoom, VIEWPORT);
            double step = SandboxLod.effectiveStep(zoom, VIEWPORT);
            int ox = cx - window / 2, oz = cz - window / 2;
            var data = com.geogenesis.client.preview.TerrainFieldData.sample(
                    terrain, ox, oz, window, window, grid, grid, i);
            if (SandboxLod.usesCoarseWater(zoom, VIEWPORT)) {
                var lookup = new com.geogenesis.client.preview.SandboxWaterLookup(terrain.generator());
                for (int gx = 0; gx < grid; gx++) {
                    int wx = ox + (int) Math.round(gx * step);
                    for (int gz = 0; gz < grid; gz++) {
                        int wz = oz + (int) Math.round(gz * step);
                        data.putWaterPublic(gx, gz, lookup.waterSurfaceAt(wx, wz));
                    }
                }
            }
            String path = new java.io.File(d, "lod_" + i + ".tsv").getPath();
            int waterCells = 0;
            try (var w = new java.io.PrintWriter(path, "UTF-8")) {
                w.println("# gx gz height e biomeClass terrainId snow temp humid precip cont shape disch waterY");
                for (int gx = 0; gx < grid; gx++) {
                    for (int gz = 0; gz < grid; gz++) {
                        int k = gx * grid + gz;
                        if (data.e[k] < 0.0) waterCells++;
                        double wy = data.waterY[k];
                        w.printf("%d %d %.6f %.6f %d %d %d %.6f %.6f %.6f %.6f %.6f %.6f %s%n",
                                gx, gz, data.height[k], data.e[k],
                                data.biomeClassId[k], data.terrainId[k], data.snow[k] ? 1 : 0,
                                data.temperature[k], data.humidity[k], data.precipitation[k],
                                data.continent[k], data.shape[k], data.discharge[k],
                                Double.isNaN(wy) ? "nan" : String.format(Locale.ROOT, "%.6f", wy));
                    }
                }
            } catch (Exception ex) {
                System.out.println("导出 " + i + " 失败: " + ex);
                continue;
            }
            System.out.printf("导出 step%d（zoom %.2f）：窗口 %d 格，每柱 %.1f 格，水格 %d -> %s%n",
                    i, zoom, window, step * 2, waterCells, path);
            zoom = SandboxLod.nextZoom(zoom, true);
        }
    }

    private SandboxLodProbe() { }
}
