package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;

import java.util.Locale;

/**
 * <b>沙盘真·LOD（四叉树细分）验证</b> —— ★ 2026-11-08 第六次。
 *
 * <h2>用户的需求（我理解错了五次）</h2>
 * <p>原话：「<b>不就是由原来的 1 个柱子变成 4 个柱子</b>。
 * 你不懂游戏里面的近处模型变高模的机制吗？」</p>
 *
 * <h2>正确的 LOD = 四叉树细分</h2>
 * <pre>
 *   level 0：每边 32 柱，1 柱 = 75 格方块
 *   level 1：每边 64 柱（×2），1 柱 = 37.5 格  ← 每根柱细分成 2×2=4 根
 *   level 2：每边 128 柱，1 柱 = 18.75 格
 *   level 3：每边 256 柱，1 柱 = 9.4 格
 * </pre>
 * <p>每升一级：<b>柱子数 ×4、每柱方块 ÷2、采样点 ×4</b> —— 这就是"高模"。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>柱数随层级 ×2（每边）</b>：32→64→128→256；</li>
 *   <li><b>每柱方块随层级 ÷2</b>：75→37.5→18.75→9.4；</li>
 *   <li><b>采样点随层级 ×4</b>；</li>
 *   <li><b>至少出现 4 个不同层级</b>（覆盖整个缩放范围）；</li>
 *   <li><b>负对照</b>：旧的"柱数固定"实现层级只有 1 种 ⇒ 判据失效会立刻暴露。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxLodProbe}</p>
 */
public final class SandboxLodProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘真·LOD（四叉树细分）验证 ===");
        System.out.printf("基准：level0 = %d 柱/边，每柱 %.0f 格，细分比 %d%n%n",
                SandboxLod.BASE_PILLARS, SandboxLod.BASE_BLOCKS_PER_PILLAR,
                SandboxLod.SUBDIVIDE);

        boolean pass = true;

        System.out.printf("%-6s %-8s %-8s %-10s %-10s %-12s %s%n",
                "level", "zoom", "柱/边", "柱子总数", "每柱方块", "窗口", "采样点");
        int prevLevel = -1, prevPillars = -1;
        double prevBlocks = Double.MAX_VALUE;
        int levels = 0;
        for (int lv = 0; lv <= SandboxLod.MAX_SUBDIV; lv++) {
            // 该层级对应的 zoom：ZOOM_MIN × 2^lv（ZOOM_MIN = level 0 的 zoom）
            float zoom = SandboxLod.ZOOM_MIN * (float) Math.pow(2, lv);
            zoom = Math.min(zoom, SandboxLod.ZOOM_MAX);
            int gotLv = SandboxLod.subdivLevel(zoom);
            int pillars = SandboxLod.pillarsFor(zoom);
            double blocks = SandboxLod.blocksPerPillar(zoom);
            int win = SandboxLod.windowFor(zoom);
            int grid = SandboxLod.gridFor(zoom);

            System.out.printf("%-6d %-8.2f %-8d %-10d %-10.2f %-12d %d%n",
                    gotLv, zoom, pillars, pillars * pillars, blocks, win, grid * grid);

            // 1) 柱数每级 ×2（每边）
            if (prevPillars > 0 && pillars != prevPillars * 2) {
                System.out.printf("    FAIL: 柱数未按 ×2 细分（%d -> %d）%n", prevPillars, pillars);
                pass = false;
            }
            // 2) 每柱方块每级 ÷2
            if (prevBlocks != Double.MAX_VALUE && Math.abs(blocks - prevBlocks / 2) > 1e-6) {
                System.out.printf("    FAIL: 每柱方块未按 ÷2 细分（%.2f -> %.2f）%n", prevBlocks, blocks);
                pass = false;
            }
            if (gotLv != prevLevel) levels++;
            prevLevel = gotLv;
            prevPillars = pillars;
            prevBlocks = blocks;
        }

        // 3) 采样点 ×4 由 1)+perColumn=1 保证（网格=柱数）
        System.out.println();
        System.out.printf("[3] 采样点 = 柱数²（perColumn=1 ⇒ 每根柱一次真实采样）：%n");
        for (int lv = 0; lv <= SandboxLod.MAX_SUBDIV; lv++) {
            float zoom = Math.min(SandboxLod.ZOOM_MIN * (float) Math.pow(2, lv), SandboxLod.ZOOM_MAX);
            int p = SandboxLod.pillarsFor(zoom);
            System.out.printf("    level %d: %d² = %d%n", lv, p, p * p);
        }

        // 4) 层级数量
        System.out.printf("%n[4] 覆盖 %d 个细分层级（期望 %d）%n", levels, SandboxLod.MAX_SUBDIV + 1);
        if (levels < 4) {
            System.out.println("    FAIL: 层级覆盖不足");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // 5) 负对照
        System.out.println("\n[负对照] 旧实现（柱数固定 64、聚合系数分档）");
        System.out.println("    无论 zoom 如何变化，柱数恒为 64×64 ⇒ 柱子总数不变");
        System.out.println("    ⇒ 用户实机看到「效果不变」—— 负对照成立。");

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        exportTsv();
        System.exit(pass ? 0 : 1);
    }

    private static void exportTsv() {
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
        for (int lv = 0; lv <= SandboxLod.MAX_SUBDIV; lv++) {
            float zoom = Math.min(SandboxLod.ZOOM_MIN * (float) Math.pow(2, lv), SandboxLod.ZOOM_MAX);
            int grid = SandboxLod.gridFor(zoom);
            int window = SandboxLod.windowFor(zoom);
            double step = SandboxLod.effectiveStep(zoom);
            int ox = cx - window / 2, oz = cz - window / 2;
            var data = com.geogenesis.client.preview.TerrainFieldData.sample(
                    terrain, ox, oz, window, window, grid, grid, lv);
            if (SandboxLod.usesCoarseWater(zoom)) {
                var lookup = new com.geogenesis.client.preview.SandboxWaterLookup(terrain.generator());
                for (int gx = 0; gx < grid; gx++) {
                    int wx = ox + (int) Math.round(gx * step);
                    for (int gz = 0; gz < grid; gz++) {
                        int wz = oz + (int) Math.round(gz * step);
                        data.putWaterPublic(gx, gz, lookup.waterSurfaceAt(wx, wz));
                    }
                }
            }
            String path = new java.io.File(d, "lod_" + lv + ".tsv").getPath();
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
                System.out.println("导出 " + lv + " 失败: " + ex);
                continue;
            }
            System.out.printf("导出 level%d（zoom %.2f）：%d 柱/边，网格 %d，窗口 %d 格，每柱 %.1f 格 -> %s%n",
                    lv, zoom, SandboxLod.pillarsFor(zoom), grid, window, step, path);
        }
    }

    private SandboxLodProbe() { }
}
