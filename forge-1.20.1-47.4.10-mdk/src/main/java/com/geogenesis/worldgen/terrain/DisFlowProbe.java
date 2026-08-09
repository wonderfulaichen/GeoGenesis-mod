package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.geogenesis.config.GeoGenesisConfig;

import java.io.File;

/**
 * dis（稳态放电量场）分布探针。
 *
 * <p>目的：量化液滴侵蚀汇聚场 dis 的实际量级分布，为"源头弱、汇聚强"门控
 * （flowGate = ld/(ld+FLOW_SCALE)）定 FLOW_SCALE。
 *
 * <p>与独立预览不同：本探针通过 {@code GeoGenesisConfig.SPEC.setConfig(FileConfig)}
 * 加载 {@code run/config/geogenesis-common.toml} 真实配置，使
 * {@code GeoGenesisConfig.INSTANCE.xxx.get()} 返回游戏内实际值（erosionIterations、
 * erosionLrate、erosionDropsMul、erosionRidgeEnabled 等），完全对齐游戏窗口。
 */
// TODO(2026-08-10 wu 化)：探针以旧"块坐标触发 tile"调用 getErosionTile，HS≠1 时 tile 定位漂移
// （诊断工具，HS=1 仍逐位等价）。
public final class DisFlowProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        runProbe(seed);
    }

    public static void runProbe(long seed) {
        System.out.println("=== DisFlowProbe: dis field distribution ===");
        System.out.println("Seed: " + seed);

        // 1) 加载真实 toml 配置（对齐游戏窗口）
        File toml = new File("run/config/geogenesis-common.toml");
        if (!toml.exists()) {
            System.out.println("!! toml not found: " + toml.getAbsolutePath());
            System.out.println("   (从 forge-1.20.1-47.4.10-mdk 目录运行 gradlew runDisFlowProbe)");
            return;
        }
        CommentedFileConfig fc = CommentedFileConfig.of(toml);
        fc.load();
        GeoGenesisConfig.SPEC.setConfig(fc);
        System.out.println("Loaded toml: " + toml.getAbsolutePath());
        System.out.println("  erosionEnabled    = " + GeoGenesisConfig.INSTANCE.erosionEnabled.get());
        System.out.println("  erosionRidgeEnabled = " + GeoGenesisConfig.INSTANCE.erosionRidgeEnabled.get());
        System.out.println("  erosionDropsMul   = " + GeoGenesisConfig.INSTANCE.erosionDropsMul.get());
        System.out.println("  erosionErodeMul   = " + GeoGenesisConfig.INSTANCE.erosionErodeMul.get());
        System.out.println("  erosionIterations = " + GeoGenesisConfig.INSTANCE.erosionIterations.get());
        System.out.println("  erosionLrate      = " + GeoGenesisConfig.INSTANCE.erosionLrate.get());
        System.out.println("  riversEnabled     = " + GeoGenesisConfig.INSTANCE.riversEnabled.get());
        System.out.println();

        // 2) 构造 CellGenerator（与游戏内同路径，config 已加载 → 用 toml 真实值）
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        // 3) 触发几个 tile（覆盖陆地区域；disProbe 统计在 ErosionEngine 内每 64 tile 打印一次）
        int tilesPerRow = 8;
        for (int tz = 0; tz < tilesPerRow; tz++) {
            for (int tx = 0; tx < tilesPerRow; tx++) {
                // 以 chunk 坐标触发 tile（ERODE_TILE_CHUNKS=3 → tile 坐标 = chunk/3）
                gen.getErosionTile(tx * 3 + 1, tz * 3 + 1);
            }
        }
        System.out.println("=== DisFlowProbe done (" + tilesPerRow * tilesPerRow + " tiles requested) ===");
        System.out.println("dis 分布见上方 [disProbe] 日志（每 64 tile 一次，覆盖陆地/海洋混合区）");
    }
}
