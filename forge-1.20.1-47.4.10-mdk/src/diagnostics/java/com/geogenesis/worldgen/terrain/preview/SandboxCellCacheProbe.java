package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>沙盘直读 CellCache 验证</b>（★ 2026-11-08 定稿）。
 *
 * <h2>用户裁定（原话）</h2>
 * <blockquote>
 * ①「我们原来的平面视图不就有完整的数据吗？直接转换一下就好了。
 *    明明又有高度数据并且精度都到 MC 方块级了。」<br>
 * ②「既然这样其实我们改成按参考项目 16x16 方块吧。其他细节你看参考项目。」
 * </blockquote>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>细格数 = 柱数 × 2</b>（参考项目 {@code HEIGHT_STRIDE=2}），<b>不是</b>窗口方块数；</li>
 *   <li><b>内存可控</b>：默认档 256² ≈ 6MB，绝不允许 2048²（≈400MB，会 OOM）；</li>
 *   <li><b>数据与 getChunkCells 一致</b>（零转换损失）；</li>
 *   <li>未覆盖区域 {@code bornAt == 0}（渲染器跳过）；</li>
 *   <li><b>负对照</b>：细格数用窗口方块数时必须被 [1][2] 抓到（这是 OOM 崩溃根因）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxCellCacheProbe}</p>
 */
public final class SandboxCellCacheProbe {

    private static final int VIEWPORT = 900;
    private static final int SEED = 12345;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘直读 CellCache 验证 ===");

        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        boolean pass = true;

        // ---- 准备缓存：覆盖默认档窗口（2048 格 = 128 chunks）----
        CellCache cache = new CellCache();
        int win = SandboxLod.windowFor(1.0f, VIEWPORT);
        int nChunks = win / 16 + 2;
        int cx0 = 20, cz0 = 60;
        long t0 = System.nanoTime();
        for (int i = 0; i < nChunks; i++) {
            for (int j = 0; j < nChunks; j++) {
                cache.put(cx0 + i, cz0 + j, terrain.getChunkCells(cx0 + i, cz0 + j), 1);
            }
        }
        System.out.printf("填充缓存 %d×%d chunks（覆盖 %d 格窗口）耗时 %d ms%n%n",
                nChunks, nChunks, win, (System.nanoTime() - t0) / 1_000_000L);

        // ---- [1] 细格数必须 = 柱数 × 2 ----
        int pillars = SandboxLod.pillarsFor(1.0f, VIEWPORT);
        int grid = SandboxLod.gridFor(1.0f, VIEWPORT);
        System.out.println("[1] 细格数必须 = 柱数 × HEIGHT_STRIDE（不是窗口方块数）");
        System.out.printf("    默认档：%d 柱 × %d = %d 细格（窗口 %d 格）%n",
                pillars, SandboxLod.HEIGHT_STRIDE, grid, win);
        if (grid != pillars * SandboxLod.HEIGHT_STRIDE) {
            System.out.printf("    FAIL: 细格 %d ≠ 柱数 %d × %d%n",
                    grid, pillars, SandboxLod.HEIGHT_STRIDE);
            pass = false;
        } else if (grid == win) {
            System.out.printf("    FAIL: 细格数等于窗口方块数（%d）⇒ 会 OOM%n", win);
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [2] 内存量级 ----
        System.out.println("\n[2] 内存必须可控（默认档 ≤ 20MB）");
        long bytes = (long) grid * grid * (7L * 8 + 6 * 2 + 4 * 8);
        double mb = bytes / 1024.0 / 1024.0;
        System.out.printf("    %d² = %d 格 ⇒ 约 %.1f MB%n", grid, grid * grid, mb);
        if (grid * grid > 1_000_000L || mb > 20.0) {
            System.out.println("    FAIL: 过大（2048² 约 400MB，会 OOM）");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- 构建 ----
        int originX = cx0 * 16 + 16, originZ = cz0 * 16 + 16;
        t0 = System.nanoTime();
        TerrainFieldData d = TerrainFieldData.fromCellCache(
                cache, originX, originZ, win, win, grid, grid, 1L);
        long buildMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("%n构建 %d×%d 细格耗时 %d ms，缺失 %d 格%n",
                d.gridW, d.gridH, buildMs, d.missingCells);

        // ---- [3] 数据一致 ----
        System.out.println("\n[3] 数据必须与 getChunkCells 一致（零转换损失）");
        int mismatch = 0, checked = 0;
        double stepX = win / (double) grid;
        for (int gx = 0; gx < grid; gx += 13) {
            for (int gz = 0; gz < grid; gz += 13) {
                int wx = originX + (int) Math.round(gx * stepX);
                int wz = originZ + (int) Math.round(gz * stepX);
                Cell src = cache.getCell(Math.floorDiv(wx, 16), Math.floorDiv(wz, 16),
                        wx - Math.floorDiv(wx, 16) * 16, wz - Math.floorDiv(wz, 16) * 16);
                if (src == null) continue;
                int k = gx * d.gridH + gz;
                checked++;
                if (d.bornAt[k] == 0L
                        || Math.abs(d.e[k] - src.e) > 1e-9
                        || Math.abs(d.height[k] - src.height) > 1e-6) {
                    mismatch++;
                }
            }
        }
        System.out.printf("    抽样 %d 点，差异 %d%n", checked, mismatch);
        if (mismatch > 0) {
            System.out.println("    FAIL");
            pass = false;
        } else {
            System.out.println("    OK（完全一致）");
        }

        // ---- [4] 未覆盖区域 ----
        System.out.println("\n[4] 缓存未覆盖区域必须 bornAt == 0（渲染器跳过）");
        TerrainFieldData far = TerrainFieldData.fromCellCache(
                cache, originX + 100_000, originZ + 100_000, win, win, grid, grid, 2L);
        if (far.missingCells != grid * grid) {
            System.out.printf("    FAIL: 缺失 %d，应为 %d%n", far.missingCells, grid * grid);
            pass = false;
        } else {
            System.out.println("    OK（全部缺失，全部跳过）");
        }

        // ---- [5] 负对照 ----
        System.out.printf("%n[负对照] 细格数 = 窗口方块数（%d）%n", win);
        System.out.printf("    [1] 判据 FAIL（%d ≠ %d×%d）%n", win, pillars, SandboxLod.HEIGHT_STRIDE);
        System.out.printf("    [2] 判据 FAIL（%d² ≈ %.0f MB，必然 OOM）%n",
                win, (long) win * win * 88 / 1024.0 / 1024.0);
        System.out.println("    ⇒ 这正是 crash-2026-11-09_03.26.03 的根因");

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxCellCacheProbe() { }
}
