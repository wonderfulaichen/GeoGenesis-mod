package com.geogenesis.client;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内 chunk 边界断裂扫描器（2026-08-12）。
 *
 * <p>用法：按 B 键（GeoGenesisForgeEvents 接线），以玩家为中心扫描 ±3 chunk 内所有
 * chunk 边界线（x=16k / z=16k），采样相邻块高度差 &gt; 4 块的位置。</p>
 *
 * <p>输出：完整结果写 latest.log（LOGGER，dev 环境 System.out 不可见），
 * 聊天框显示摘要（绿=无断裂 / 红=发现 N 处）。</p>
 *
 * <p>异步线程执行（主线程不卡）；重复按键忽略进行中的扫描。
 * 引擎按世界种子 + 当前配置本地重建，与服务器生成算法一致。</p>
 */
public final class InGameSeamScanner {
    private static final Logger LOGGER = LogManager.getLogger("geogenesis");
    private static final int RADIUS = 3 * 16;      // ±3 chunk（48 块半径，覆盖视野附近）
    private static final int STEP = 1;             // 边界线采样步长（块）——逐块扫描不漏
    private static final double THRESHOLD = 2.5;   // 高度差阈值（块），> 此值判定断裂（2026-08-13：4→2.5，捕获用户截图的 ~3 块断裂）
    private static volatile boolean running = false;

    private InGameSeamScanner() {}

    public static void scan(Minecraft mc) {
        if (running) {
            LOGGER.info("[SeamScan] 已有扫描进行中，忽略本次按键");
            return;
        }
        running = true;
        int px = (int) Math.floor(mc.player.getX());
        int pz = (int) Math.floor(mc.player.getZ());
        // 种子：单机取集成服务器 overworld（与 G 键配置屏同法）；联机时客户端无法获取
        // 服务器种子，扫描结果不匹配服务器地形 → 降级 seed=0 并告警。
        long seed;
        if (mc.getSingleplayerServer() != null) {
            seed = mc.getSingleplayerServer().overworld().getSeed();
        } else {
            seed = 0L;
            LOGGER.warn("[SeamScan] 非单机模式无法获取世界种子，扫描基于 seed=0，结果与服务器地形不符");
        }
        new Thread(() -> {
            try {
                TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
                // 参数取证：与独立探针 defaults 对比定位引擎差异
                LOGGER.info("[SeamScan] 引擎参数: hs={} erosionStrength={} dropsMul={} riversEnabled={}",
                    p.horizontalScale(),
                    GeoGenesisConfig.INSTANCE.erosionStrength.get(),
                    GeoGenesisConfig.INSTANCE.erosionDropsMul.get(),
                    GeoGenesisConfig.INSTANCE.riversEnabled.get());
                CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
                gen.seed(seed);
                GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

                List<String> hits = new ArrayList<>();
                long t0 = System.currentTimeMillis();

                // X 边界线：x=16k，沿 z 采样（k 用 floorDiv，负坐标不截断错线）
                int kStart = Math.floorDiv(px - RADIUS, 16);
                int kEnd = Math.floorDiv(px + RADIUS, 16);
                for (int k = kStart; k <= kEnd; k++) {
                    int bx = k * 16;
                    for (int z = pz - RADIUS; z <= pz + RADIUS; z += STEP) {
                        double hL = terrain.sampleHeight(bx - 1, z);
                        double hR = terrain.sampleHeight(bx, z);
                        double d = Math.abs(hR - hL);
                        if (d > THRESHOLD) {
                            hits.add(String.format("X边界 x=%d z=%d: 左%.1f 右%.1f 差%.1f", bx, z, hL, hR, d));
                            // 剖面：断裂处两侧各 8 块（含侵蚀总高度），定位断裂形态
                            StringBuilder sb = new StringBuilder("  X剖面[");
                            for (int dx = -8; dx <= 8; dx++)
                                sb.append(String.format("%.1f ", terrain.sampleHeight(bx + dx, z)));
                            LOGGER.info("[SeamScan] X边界x={} z={} {}", bx, z, sb.append(']'));
                            // 基础场剖面（无侵蚀）：判断断裂在基础场还是侵蚀层
                            StringBuilder sbB = new StringBuilder("  X基础剖面[");
                            for (int dx = -8; dx <= 8; dx++) {
                                Cell bc = terrain.sampleCellLight(bx + dx, z);
                                sbB.append(String.format("%.1f ", bc != null ? bc.height : -1));
                            }
                            LOGGER.info("[SeamScan] X基础 x={} z={} {}", bx, z, sbB.append(']'));
                        }
                    }
                }
                // Z 边界线：z=16k，沿 x 采样
                for (int k = kStart; k <= kEnd; k++) {
                    int bz = k * 16;
                    for (int x = px - RADIUS; x <= px + RADIUS; x += STEP) {
                        double hT = terrain.sampleHeight(x, bz - 1);
                        double hB = terrain.sampleHeight(x, bz);
                        double d = Math.abs(hB - hT);
                        if (d > THRESHOLD) {
                            hits.add(String.format("Z边界 z=%d x=%d: 上%.1f 下%.1f 差%.1f", bz, x, hT, hB, d));
                            StringBuilder sb = new StringBuilder("  Z剖面[");
                            for (int dz = -8; dz <= 8; dz++)
                                sb.append(String.format("%.1f ", terrain.sampleHeight(x, bz + dz)));
                            LOGGER.info("[SeamScan] Z边界z={} x={} {}", bz, x, sb.append(']'));
                        }
                    }
                }

                long dt = System.currentTimeMillis() - t0;
                LOGGER.info("[SeamScan] seed={} center=({},{}) radius={} 耗时{}ms 发现{}处断裂",
                        seed, px, pz, RADIUS, dt, hits.size());
                for (String h : hits) LOGGER.info("[SeamScan] {}", h);

                int count = hits.size();
                String summary = count == 0
                        ? "§a[地形] 周围±3chunk 无>2.5块断裂 ✓"
                        : "§c[地形] 发现 " + count + " 处断裂(>2.5块)，详情见 latest.log";
                mc.execute(() -> {
                    if (mc.player != null)
                        mc.player.displayClientMessage(Component.literal(summary), false);
                });
            } catch (Exception e) {
                LOGGER.error("[SeamScan] 扫描失败", e);
            } finally {
                running = false;
            }
        }, "geogenesis-seam-scan").start();
    }
}
