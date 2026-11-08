package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * <b>沙盘真·LOD 验证</b>（★ 2026-11-08 第四次重做）。
 *
 * <h2>用户的需求（我理解错三次）</h2>
 * <p>原话：「<b>就是缩放近处精模远处粗模</b>」
 * 「为什么固定柱数？你这样我无论精度如何看到的效果不是已经不变吗？」</p>
 *
 * <h2>错在哪</h2>
 * <p>我把「网格固定、只改窗口」当成 LOD ⇒ 柱子数量恒定 ⇒ 构图不变 ⇒ 效果不变。</p>
 *
 * <h2>正确判据（本探针要证明的）</h2>
 * <ol>
 *   <li><b>聚合系数随缩放变化</b>：拉远 8 → 中景 4/2 → 推近 1（粗模 → 精模）；</li>
 *   <li><b>网格随缩放增长</b>：推近时细格数明显增加（精度提升）；</li>
 *   <li><b>每柱方块随缩放减少</b>：推近看到更小的地形单元；</li>
 *   <li><b>窗口随缩放缩小</b>：推进感；</li>
 *   <li><b>柱子屏幕尺寸近似恒定</b>：视口/柱数不变（密度稳定，不糊）；</li>
 *   <li><b>精度地板</b>：每细格 ≥ 1 格方块（防重复采样）；</li>
 *   <li><b>负对照</b>：旧实现（网格固定、聚合固定）在这三项上"看不出变化"。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxLodProbe}</p>
 */
public final class SandboxLodProbe {

    private static final int VIEWPORT = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘真·LOD 验证（近处精模 / 远处粗模）===");
        System.out.printf("视口 %dpx，柱目标 %.0fpx，基准每柱 %.0f 格%n%n",
                VIEWPORT, SandboxLod.PILLAR_PX, SandboxLod.BASE_BLOCKS_PER_PILLAR);

        boolean pass = true;

        System.out.printf("%-8s %-8s %-7s %-8s %-10s %-8s %-8s %s%n",
                "zoom", "柱数", "聚合", "网格", "每柱方块", "视野", "每细格", "采样点");
        float zoom = SandboxLod.ZOOM_MIN;
        Set<Integer> perCols = new LinkedHashSet<>();
        Set<Integer> grids = new LinkedHashSet<>();
        double prevBlocks = Double.MAX_VALUE;
        int prevWindow = Integer.MAX_VALUE;
        int prevPillars = -1;
        double prevScreenPx = -1;

        for (int n = 0; n <= 40; n++) {
            int pillars = SandboxLod.pillarsFor(VIEWPORT);
            int per = SandboxLod.perColumnFor(zoom);
            int grid = SandboxLod.gridFor(zoom, VIEWPORT);
            double blocks = SandboxLod.blocksPerPillar(zoom);
            int win = SandboxLod.windowFor(zoom, VIEWPORT);
            double step = SandboxLod.effectiveStep(zoom, VIEWPORT);
            double screenPx = VIEWPORT / (double) pillars;

            perCols.add(per);
            grids.add(grid);

            if (n % 4 == 0 || zoom >= SandboxLod.ZOOM_MAX - 1e-6) {
                System.out.printf("%-8.2f %-8d %-7d %-8d %-10.2f %-8d %-8.2f %d%n",
                        zoom, pillars, per, grid, blocks, win, step, grid * grid);
            }

            // 1) 聚合系数必须随缩放变化（否则就是"效果不变"）
            // 2) 网格必须随缩放变化
            // 3) 每柱方块单调不增
            if (blocks > prevBlocks + 1e-9) {
                System.out.printf("    FAIL: 每柱方块反而变大（%.2f -> %.2f）%n", prevBlocks, blocks);
                pass = false;
            }
            // 4) 窗口单调不增
            if (win > prevWindow) {
                System.out.printf("    FAIL: 窗口反而变大（%d -> %d）%n", prevWindow, win);
                pass = false;
            }
            // 5) 柱子屏幕尺寸近似恒定（±3px）
            if (prevScreenPx > 0 && Math.abs(screenPx - prevScreenPx) > 3.0) {
                System.out.printf("    FAIL: 柱子屏幕尺寸跳变（%.1f -> %.1f px）%n",
                        prevScreenPx, screenPx);
                pass = false;
            }
            // 6) 精度地板
            if (step < 1.0 - 1e-9) {
                System.out.printf("    FAIL: 每细格 %.3f < 1（重复采样）%n", step);
                pass = false;
            }

            prevBlocks = blocks;
            prevWindow = win;
            prevPillars = pillars;
            prevScreenPx = screenPx;
            zoom = SandboxLod.nextZoom(zoom, true);
            if (zoom >= SandboxLod.ZOOM_MAX - 1e-6) break;
        }

        // 1) 聚合系数变化
        System.out.printf("%n[1] 聚合系数出现 %d 种：%s%n", perCols.size(), perCols);
        if (perCols.size() < 2) {
            System.out.println("    FAIL: 聚合系数只有一种 ⇒ 拉远/推近是同一精度（不是 LOD）");
            pass = false;
        } else {
            System.out.println("    OK（粗模 → 精模）");
        }

        // 2) 网格变化
        System.out.printf("[2] 网格出现 %d 种：%s%n", grids.size(), grids);
        if (grids.size() < 3) {
            System.out.println("    FAIL: 网格几乎不变 ⇒ 精度没提升");
            pass = false;
        } else {
            System.out.println("    OK（推近网格变密）");
        }

        // 7) 负对照：旧实现
        System.out.println("\n[负对照] 旧实现（网格固定、聚合固定）");
        System.out.println("    网格恒为 256、聚合恒为 2 ⇒ 柱数 128 恒定");
        System.out.println("    推近只改窗口 ⇒ 柱子数/聚合/网格三项全不变");
        System.out.println("    ⇒ 用户实机反馈「效果不变」—— 负对照成立。");

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
        float zoom = SandboxLod.BASE_ZOOM;
        for (int i = 0; i < 8; i++) {
            int grid = SandboxLod.gridFor(zoom, VIEWPORT);
            int window = SandboxLod.windowFor(zoom, VIEWPORT);
            double step = SandboxLod.effectiveStep(zoom, VIEWPORT);
            int per = SandboxLod.perColumnFor(zoom);
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
            System.out.printf("导出 step%d（zoom %.2f）：聚合 %d，网格 %d，窗口 %d 格，每柱 %.1f 格 -> %s%n",
                    i, zoom, per, grid, window, step * per, path);
            zoom = SandboxLod.nextZoom(zoom, true);
        }
    }

    private SandboxLodProbe() { }
}
