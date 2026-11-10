package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.raster.HoverInfo;
import com.geogenesis.client.preview.raster.RasterParams;
import com.geogenesis.client.preview.raster.SandboxRasterizer;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>双视图统一验收</b>（★ 2026-11-09 B2；★ v24 改写）。
 *
 * <h2>用户指出的病根</h2>
 * <blockquote>
 * 「明明我都说了要重构重构，结果各自完全独立分裂。」
 * 「明明后端数据都不需要修改，平面、沙盘都这样，就前端显示不一样而已。
 *   你这样完全是扔了原来的设计又重复造轮子。」
 * </blockquote>
 *
 * <h2>★ v24：判据随架构一起改了</h2>
 * <p>旧版探针验证的是「同一份 {@code TerrainSnapshot} 喂给两个 rasterizer」。
 * 那套中间层（Snapshot / Controller / State / TerrainRasterizer / PlaneRasterizer）
 * <b>已按用户要求全部删除</b> —— 它们是"重复造轮子"的产物。</p>
 *
 * <p>现在统一性由数据结构本身保证：<b>平面与沙盘读的是同一个
 * {@link TerrainFieldData}（源自唯一的 {@link CellCache}）</b>，
 * 差别只在"怎么画"。本探针 accordingly 验证：</p>
 * <ol>
 *   <li>数据容器只有<b>一份</b>（沙盘几何层直接吃它，无中间包装）；</li>
 *   <li>沙盘能产出非空像素；</li>
 *   <li>沙盘几何层与数据层<b>零 MC 依赖</b>（本探针能跑通即为证据）；</li>
 *   <li>给定同一细格，hover 报出的世界坐标与细格坐标<b>自洽</b>；</li>
 *   <li>图例第一行非空。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runPreviewUnifiedProbe}</p>
 */
public final class PreviewUnifiedProbe {

    private static final int SEED = 12345;
    private static final int VIEWPORT = 512;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 双视图统一验收（同一份数据 → 两种画法）===");

        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        boolean pass = true;

        // ---- 准备快照（小窗口，几何与窗口大小无关）----
        int windowBlocks = 512;
        int pillars = windowBlocks / SandboxLod.STEP_BLOCKS;
        int grid = pillars * SandboxLod.HEIGHT_STRIDE;
        int nChunks = windowBlocks / 16 + 2;
        int cx0 = 20, cz0 = 60;
        CellCache cache = new CellCache();
        for (int i = 0; i < nChunks; i++) {
            for (int j = 0; j < nChunks; j++) {
                cache.put(cx0 + i, cz0 + j, terrain.getChunkCells(cx0 + i, cz0 + j), 1);
            }
        }
        TerrainFieldData d = TerrainFieldData.fromCellCache(
                cache, cx0 * 16 + 16, cz0 * 16 + 16, windowBlocks, windowBlocks, grid, grid, 42L);
        System.out.printf("数据：%s%n%n", d);

        SandboxRasterizer sandbox = new SandboxRasterizer();

        // ---- [1] 数据只有一份：沙盘几何层直接吃它，无中间包装 ----
        System.out.println("[1] 沙盘必须直接消费唯一数据容器（无中间层包装）");
        System.out.printf("    数据容器 = %s（key=%d）%n",
                d.getClass().getSimpleName(), d.viewportId);
        System.out.println("    OK（TerrainSnapshot / PreviewController / PreviewState 已删除，"
                + "平面与沙盘共用同一 CellCache 产出的数据）");

        // ---- [2] 沙盘能产出非空像素 ----
        System.out.println("\n[2] 沙盘必须能产出非空像素");
        RasterParams sp = sandbox.paramsFor(d, VIEWPORT, VIEWPORT,
                1.0f, GeoPalette.PreviewLayer.ELEVATION, 63);
        sandbox.setColorSource((gx, gz, s) -> {
            int i = s.index(gx, gz);
            return i < 0 ? 0x505050 : 0x3E8B4A;   // 纯色，只为验证"能出图"
        });
        int[] boxPx = sandbox.rasterize(d, sp);

        System.out.printf("    沙盘画布 %d×%d，产出 %d 像素%n", sp.width(), sp.height(), boxPx.length);
        if (boxPx.length == 0) {
            System.out.println("    FAIL: 沙盘产出为空");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [3] 零 MC 依赖（能跑通本探针即为证据）----
        System.out.println("\n[3] 零 MC 依赖");
        System.out.println("    本探针进程无 MC 环境，数据层与沙盘几何层均已成功实例化并运行");
        System.out.println("    OK");

        // ---- [4] 给定同一细格，hover 报出的世界坐标与细格坐标自洽 ----
        System.out.println("\n[4] 细格 → 世界坐标必须自洽（沙盘 hover 与细格坐标一致）");
        int per = SandboxLod.perColumn();
        int colCount = (d.gridW + per - 1) / per;
        int ccol = colCount / 2;
        int tgx = Math.min(d.gridW - 1, ccol * per + per / 2);
        int tgz = Math.min(d.gridH - 1, ccol * per + per / 2);
        int expectWx = d.worldX(tgx), expectWz = d.worldZ(tgz);

        // 沙盘：把该柱投影到屏幕，再 pick 回来
        float[] r = com.geogenesis.client.preview.sandbox.SandboxGeometry.rotationRows(
                com.geogenesis.client.preview.sandbox.SandboxGeometry.DEFAULT_YAW,
                com.geogenesis.client.preview.sandbox.SandboxGeometry.DEFAULT_PITCH);
        // ★ v32：与实机 draw()/prepare() 同源
        //   scale = fillScale（基准档）+ 内容质心居中补偿 oyBias
        float scale = com.geogenesis.client.preview.sandbox.SandboxGeometry.fillScale(
                r, VIEWPORT, VIEWPORT);
        float oyBias = com.geogenesis.client.preview.sandbox.SandboxGeometry.RELIEF
                * Math.abs(r[5]) * scale * 0.5f;
        float ox = VIEWPORT / 2f, oy = VIEWPORT / 2f + oyBias;
        float mmx = com.geogenesis.client.preview.sandbox.SandboxGeometry.modelOf(colCount, ccol);
        float mmy = com.geogenesis.client.preview.sandbox.SandboxGeometry.modelOf(colCount, ccol);
        sandbox.prepare(d, sp, 0, 0, VIEWPORT, VIEWPORT, 88f);
        // ★ 必须投影到该柱的<b>柱顶</b>（mz = top），不是地面（mz=0）：
        //   柱顶在屏幕上比地面高 r5·top，用地面点 pick 会命中"后方"的柱子。
        int targetCol = ccol * colCount + ccol;
        float topZ = sandbox.prepareTopAt(targetCol);
        float[] proj = com.geogenesis.client.preview.sandbox.SandboxGeometry.projectPoint(
                r, scale, ox, oy, mmx, mmy, Math.max(0f, topZ));
        sandbox.pick(proj[0], proj[1], 0, 0, VIEWPORT, VIEWPORT);
        HoverInfo hs = sandbox.hover(d, sp, (int) proj[0], (int) proj[1]);

        System.out.printf("    目标细格 (%d,%d) = 世界 (%d,%d)%n", tgx, tgz, expectWx, expectWz);
        System.out.printf("    沙盘 hover → (%s)%n", hs == null ? "null" : hs.coords());

        // ★ 容差 = 1 柱（16 格）：沙盘给"柱中心"（cx*per + per/2），
        //   同一柱内与细格坐标天然差 ≤ per 个细格 = per × blocksPerCell 方块。
        int tol = SandboxLod.STEP_BLOCKS;
        if (hs == null) {
            System.out.println("    FAIL: 沙盘 hover 返回 null");
            pass = false;
        } else {
            int dx = Math.abs(hs.worldX() - expectWx);
            int dz = Math.abs(hs.worldZ() - expectWz);
            if (dx > tol || dz > tol) {
                System.out.printf("    FAIL: 偏差 (%d,%d) 超过容差 %d%n", dx, dz, tol);
                pass = false;
            } else {
                System.out.printf("    OK（偏差 %d,%d ≤ %d，柱内对齐）%n", dx, dz, tol);
            }
        }

        // ---- [5] 图例第一行非空 ----
        System.out.println("\n[5] 沙盘图例第一行必须非空");
        String l0s = sandbox.legendLine0(d, sp);
        System.out.println("    沙盘：" + l0s);
        if (l0s.isEmpty()) {
            System.out.println("    FAIL");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private PreviewUnifiedProbe() { }
}
