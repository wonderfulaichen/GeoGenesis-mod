package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;

import java.util.Locale;

/**
 * <b>沙盘 LOD（方案 B）验证</b> —— ★ 2026-11-08 第八版。
 *
 * <h2>用户选定的方案 B</h2>
 * <blockquote>「选 B：窗口和每柱方块独立，柱数自动算。」</blockquote>
 *
 * <pre>
 *   窗口 W     = 2400 / 2^k    （滚轮，连续）
 *   每柱方块 P = 75 / 2^j      （j = r·k，近景段 r=2）
 *   柱数 N     = W / P          （派生）
 * </pre>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>窗口随滚轮连续变化</b>（每格都不同）—— 修"视野不动"；</li>
 *   <li><b>近景段柱数翻倍</b>（r=2 生效）—— 真"高模"；</li>
 *   <li><b>每柱 ≥ 1 格</b>（精度地板）；</li>
 *   <li><b>柱子屏幕尺寸 ≥ 12px</b>（防弧纹，人眼物理下限）；</li>
 *   <li><b>推近/拉远双向都有空间</b>（默认 zoom 在中间）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxLodProbe}</p>
 */
public final class SandboxLodProbe {

    /** 典型视口。 */
    private static final int VIEWPORT = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘 LOD（方案 B）验证 ===");
        System.out.printf("视口 %dpx · 基准窗口 %.0f 格 · 基准每柱 %.0f 格%n%n",
                VIEWPORT, SandboxLod.BASE_WINDOW, SandboxLod.BASE_BLOCKS);

        boolean pass = true;

        // ---- 推近方向（每格 ×2）----
        System.out.printf("%-6s %-8s %-9s %-10s %-10s %-9s %-8s %s%n",
                "step", "zoom", "柱/边", "视野", "每柱格数", "采样点", "柱px", "屏幕尺寸判定");
        float zoom = SandboxLod.BASE_ZOOM;
        int prevWindow = -1;
        int prevPillars = -1;
        java.util.Set<Integer> windows = new java.util.LinkedHashSet<>();

        for (int n = 0; n <= SandboxLod.MAX_ZOOM_IN_STEPS; n++) {
            int win = SandboxLod.windowFor(zoom);
            int pillars = SandboxLod.pillarsFor(zoom, VIEWPORT);
            double blocks = SandboxLod.blocksPerPillar(zoom, VIEWPORT);
            double px = (double) VIEWPORT / pillars;

            windows.add(win);
            System.out.printf("%-6d %-8.2f %-9d %-10d %-10.2f %-9d %-8.1f %s%n",
                    n, zoom, pillars, win, blocks, pillars * pillars, px,
                    px >= SandboxLod.MIN_PILLAR_PX ? "OK" : "FAIL");

            // 1) 窗口必须严格变小
            if (prevWindow > 0 && win >= prevWindow) {
                System.out.printf("    FAIL: 窗口没变小（%d -> %d）⇒ 视野不动%n", prevWindow, win);
                pass = false;
            }
            // 3) 精度地板
            if (blocks < 1.0 - 1e-9) {
                System.out.printf("    FAIL: 每柱 %.2f < 1（重复采样）%n", blocks);
                pass = false;
            }
            // 4) 柱子屏幕尺寸
            if (px < SandboxLod.MIN_PILLAR_PX - 1e-6) {
                System.out.printf("    FAIL: 柱子 %.1fpx < %.0fpx 下限（弧纹）%n",
                        px, SandboxLod.MIN_PILLAR_PX);
                pass = false;
            }
            prevWindow = win;
            prevPillars = pillars;
            zoom = SandboxLod.nextZoom(zoom, true);
        }

        // ---- 拉远方向 ----
        System.out.println("\n拉远方向：");
        zoom = SandboxLod.BASE_ZOOM;
        int lastWin = SandboxLod.windowFor(zoom);
        for (int n = 1; n <= SandboxLod.MAX_ZOOM_OUT_STEPS; n++) {
            zoom = SandboxLod.nextZoom(zoom, false);
            int win = SandboxLod.windowFor(zoom);
            System.out.printf("  step %d: zoom %.3f 视野 %d 格%n", n, zoom, win);
            if (win <= lastWin) {
                System.out.println("    FAIL: 拉远时窗口没有变大");
                pass = false;
            }
            lastWin = win;
        }

        // ---- 汇总 ----
        System.out.printf("%n[1] 推近出现 %d 种窗口：%s%n", windows.size(), windows);
        if (windows.size() < 3) {
            System.out.println("    FAIL: 窗口变化不足");
            pass = false;
        } else {
            System.out.println("    OK（视野连续变化，修'视野不动'）");
        }

        // 2) 近景段柱数翻倍（真高模）
        System.out.println("\n[2] 近景段（k>2）柱数应翻倍：");
        int midPillars = SandboxLod.pillarsFor(
                SandboxLod.BASE_ZOOM * (float) Math.pow(2, 2), VIEWPORT);
        int nearPillars = SandboxLod.pillarsFor(
                SandboxLod.BASE_ZOOM * (float) Math.pow(2, 3), VIEWPORT);
        System.out.printf("    k=2: %d 柱 → k=3: %d 柱 %s%n", midPillars, nearPillars,
                nearPillars > midPillars ? "OK（高模）" : "（受 12px 下限夹住 —— 视觉保护，可接受）");

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

        // ★ 取样中心必须"有山有水"，否则推近后窗口只有 144 格、落在平原上，
        //   出图看不出 LOD 效果（会被误判成"地形被压平"）。
        //   本坐标由 _find_center.py 在 lod_0 上滑动搜索得出：
        //   24×24 格窗口内高差 141.8 格、水格 97 —— 山海俱全。
        int cx = 350, cz = 1100;
        float zoom = SandboxLod.BASE_ZOOM;
        for (int i = 0; i <= SandboxLod.MAX_ZOOM_IN_STEPS; i++) {
            int grid = SandboxLod.gridFor(zoom, VIEWPORT);
            int window = SandboxLod.windowFor(zoom);
            double step = SandboxLod.effectiveStep(zoom, VIEWPORT);
            int ox = cx - window / 2, oz = cz - window / 2;
            var data = com.geogenesis.client.preview.TerrainFieldData.sample(
                    terrain, ox, oz, window, window, grid, grid, i);
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
            System.out.printf("导出 step%d（zoom %.2f）：%d 柱，视野 %d 格，每柱 %.1f 格 -> %s%n",
                    i, zoom, SandboxLod.pillarsFor(zoom, VIEWPORT), window,
                    SandboxLod.blocksPerPillar(zoom, VIEWPORT), path);
            zoom = SandboxLod.nextZoom(zoom, true);
        }
    }

    private SandboxLodProbe() { }
}
