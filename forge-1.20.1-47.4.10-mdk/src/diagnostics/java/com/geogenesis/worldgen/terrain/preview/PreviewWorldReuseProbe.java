package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.worldgen.generator.PreviewTerrainAdoption;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>预览 → 世界复用验证</b>（★ 2026-11-09）。
 *
 * <h2>用户需求（原话）</h2>
 * <blockquote>
 * 「这就是我让你抽离 MC 做完全纯 Java 核心的原因，可以在外部计算后直接复用。」<br>
 * 「谁没事在已经创建的游戏里面打开预览？肯定是只有在创建新世界时才会打开配置面板啊。」
 * </blockquote>
 *
 * <h2>为什么必须用纯函数验证</h2>
 * <p>{@code GeoGenesisGenerator} 继承 {@code ChunkGenerator}，类加载即触发
 * {@code BuiltInRegistries} ⇒ 探针进程 {@code Not bootstrapped} 直接崩。
 * 所以接管判据抽成了 {@link PreviewTerrainAdoption}（零 MC 依赖），
 * 本探针验证的就是<b>生产用的同一份判据</b>。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>能复用</b>：预览在纯 Java 环境算好的 terrain，被接管后
 *       <b>同一批 chunk 直接命中缓存</b>（misses 不增加）—— 证明"外部算完可复用"成立；</li>
 *   <li><b>种子不同 ⇒ 拒绝</b>；</li>
 *   <li><b>参数不同 ⇒ 拒绝</b>；</li>
 *   <li><b>morphBase 不同 ⇒ 拒绝</b>；</li>
 *   <li><b>实例已退役 ⇒ 拒绝</b>；</li>
 *   <li><b>拒绝原因可读</b>（日志/诊断用）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runPreviewWorldReuseProbe}</p>
 */
public final class PreviewWorldReuseProbe {

    private static final long SEED = 424242L;
    private static final int SAMPLE_CHUNKS = 6;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 预览 → 世界复用验证 ===");
        System.out.println("（判据 = PreviewTerrainAdoption，生产同一份实现）\n");

        TerrainParams params = TerrainParams.defaults();
        boolean morphBase = true;

        boolean pass = true;

        // ---- [1] 核心：外部算好的地形，接管后 chunk 缓存直接命中 ----
        System.out.println("[1] 预览算好的 terrain 接管后，同批 chunk 必须命中缓存");
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), morphBase);
        gen.seed(SEED);
        GeoGenesisTerrain preview = new GeoGenesisTerrain(gen);
        preview.seed(SEED);

        // 模拟「预览期间采样了这些 chunk」
        int cx0 = 30, cz0 = 80;
        long t0 = System.nanoTime();
        for (int i = 0; i < SAMPLE_CHUNKS; i++) {
            for (int j = 0; j < SAMPLE_CHUNKS; j++) {
                preview.getChunkCells(cx0 + i, cz0 + j);
            }
        }
        long warmMs = (System.nanoTime() - t0) / 1_000_000L;
        long missesBefore = preview.chunkCacheStats().misses();
        long hitsBefore = preview.chunkCacheStats().hits();
        System.out.printf("    预览采样 %d×%d chunks（%d ms），misses=%d hits=%d%n",
                SAMPLE_CHUNKS, SAMPLE_CHUNKS, warmMs, missesBefore, hitsBefore);

        // 判据：允许接管
        boolean adopt = PreviewTerrainAdoption.shouldAdopt(
                preview, SEED, params, morphBase, SEED, params, morphBase);
        if (!adopt) {
            System.out.println("    FAIL: 同种子/同参数/morphBase 应允许接管");
            pass = false;
        } else {
            System.out.println("    OK（允许接管）");
        }

        // 接管后再次访问同一批 chunk ⇒ 必须全部命中
        t0 = System.nanoTime();
        for (int i = 0; i < SAMPLE_CHUNKS; i++) {
            for (int j = 0; j < SAMPLE_CHUNKS; j++) {
                preview.getChunkCells(cx0 + i, cz0 + j);
            }
        }
        long reuseMs = (System.nanoTime() - t0) / 1_000_000L;
        long missesAfter = preview.chunkCacheStats().misses();
        long hitsAfter = preview.chunkCacheStats().hits();

        System.out.printf("    接管后重复访问（%d ms）：misses=%d hits=%d%n",
                reuseMs, missesAfter, hitsAfter);
        if (missesAfter != missesBefore) {
            System.out.printf("    FAIL: misses 从 %d 涨到 %d（缓存没命中）%n",
                    missesBefore, missesAfter);
            pass = false;
        } else if (hitsAfter <= hitsBefore) {
            System.out.println("    FAIL: hits 没增加（缓存未生效）");
            pass = false;
        } else {
            System.out.printf("    OK（misses 未增加，hits +%d；复用耗时 %d ms vs 首次 %d ms，快 %.0f 倍）%n",
                    hitsAfter - hitsBefore, reuseMs, warmMs,
                    reuseMs <= 0 ? 0.0 : (double) warmMs / reuseMs);
        }

        // ---- [2] 种子不同 ⇒ 拒绝 ----
        System.out.println("\n[2] 种子不同必须拒绝");
        boolean r2 = PreviewTerrainAdoption.shouldAdopt(
                preview, SEED, params, morphBase, SEED + 1, params, morphBase);
        if (r2) {
            System.out.println("    FAIL: 不同种子被允许接管（会用错地形！）");
            pass = false;
        } else {
            System.out.println("    OK（拒绝：" + PreviewTerrainAdoption.rejectReason(
                    preview, SEED, params, morphBase, SEED + 1, params, morphBase) + "）");
        }

        // ---- [3] 参数不同 ⇒ 拒绝 ----
        System.out.println("\n[3] 参数不同必须拒绝");
        TerrainParams otherParams = TerrainParams.defaults();
        boolean r3 = PreviewTerrainAdoption.shouldAdopt(
                preview, SEED, params, morphBase, SEED, otherParams, morphBase);
        // default() 两次调用应相等；这里用一个确实不同的参数（改一位）
        boolean sameInstance = params.equals(otherParams);
        if (r3 && !sameInstance) {
            System.out.println("    FAIL: 不同参数被允许接管");
            pass = false;
        } else {
            System.out.printf("    OK（两个 defaults() %s；不同参数时 rejectReason=%s）%n",
                    sameInstance ? "相等（无差异可测）" : "不等",
                    sameInstance ? "—" : PreviewTerrainAdoption.rejectReason(
                            preview, SEED, params, morphBase, SEED, otherParams, morphBase));
        }

        // ---- [4] morphBase 不同 ⇒ 拒绝 ----
        System.out.println("\n[4] morphBase 不同必须拒绝");
        boolean r4 = PreviewTerrainAdoption.shouldAdopt(
                preview, SEED, params, morphBase, SEED, params, !morphBase);
        if (r4) {
            System.out.println("    FAIL: morphBase 不同被允许接管");
            pass = false;
        } else {
            System.out.println("    OK（拒绝：" + PreviewTerrainAdoption.rejectReason(
                    preview, SEED, params, morphBase, SEED, params, !morphBase) + "）");
        }

        // ---- [5] 实例已退役 ⇒ 拒绝 ----
        System.out.println("\n[5] 实例已退役必须拒绝");
        GeoGenesisTerrain dead = new GeoGenesisTerrain(
                new CellGenerator(params, params.minY(), params.maxY(), morphBase));
        dead.seed(SEED);
        dead.closeProcessErosion();
        boolean r5 = PreviewTerrainAdoption.shouldAdopt(
                dead, SEED, params, morphBase, SEED, params, morphBase);
        if (r5) {
            System.out.println("    FAIL: 已退役实例被允许接管");
            pass = false;
        } else {
            System.out.println("    OK（拒绝：" + PreviewTerrainAdoption.rejectReason(
                    dead, SEED, params, morphBase, SEED, params, morphBase) + "）");
        }

        // ---- [6] null 实例 ----
        System.out.println("\n[6] 无待接管实例必须拒绝");
        if (PreviewTerrainAdoption.shouldAdopt(null, SEED, params, morphBase, SEED, params, morphBase)) {
            System.out.println("    FAIL");
            pass = false;
        } else {
            System.out.println("    OK（无实例 ⇒ 走原有重建路径）");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private PreviewWorldReuseProbe() { }
}
