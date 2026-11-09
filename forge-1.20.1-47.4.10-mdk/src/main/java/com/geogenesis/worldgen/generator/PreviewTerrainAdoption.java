package com.geogenesis.worldgen.generator;

import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <b>预览 → 世界接管的判据（纯函数）</b> ★ 2026-11-09。
 *
 * <h2>为什么单独抽出来</h2>
 * <p>{@code GeoGenesisGenerator} 继承 {@code ChunkGenerator}，类加载即触发
 * {@code BuiltInRegistries} ⇒ 探针进程里 {@code Not bootstrapped} 直接崩，
 * <b>无法测试</b>。</p>
 *
 * <p>而「能不能接管」是本次架构最关键的安全判据（接管错了 =
 * 用别的世界的地形生成），必须可测 ⇒ 抽成零 MC 依赖的纯函数。</p>
 *
 * <h2>判据（四项全等才接管）</h2>
 * <ol>
 *   <li>实例非 null 且 {@code isUsable()}（未被 retire）</li>
 *   <li>种子相同</li>
 *   <li>参数 {@code equals}（record 结构相等）</li>
 *   <li>{@code morphBase} 相同</li>
 * </ol>
 *
 * <p>任一不等 ⇒ <b>丢弃并走原有重建路径</b>（语义与旧行为完全一致）。</p>
 */
public final class PreviewTerrainAdoption {

    private PreviewTerrainAdoption() { }

    /**
     * 是否应当接管预览实例。
     *
     * @param offered      待接管实例（可 null）
     * @param offeredSeed  该实例归属的种子
     * @param offeredParams 该实例归属的参数
     * @param offeredMorphBase 该实例归属的 morphBase
     * @param wantSeed     进世界要用的种子
     * @param wantParams   进世界要用的参数
     * @param wantMorphBase 进世界要用的 morphBase
     */
    public static boolean shouldAdopt(GeoGenesisTerrain offered,
                                      long offeredSeed, TerrainParams offeredParams,
                                      boolean offeredMorphBase,
                                      long wantSeed, TerrainParams wantParams,
                                      boolean wantMorphBase) {
        if (offered == null || !offered.isUsable()) return false;
        if (offeredParams == null || wantParams == null) return false;
        if (offeredSeed != wantSeed) return false;
        if (offeredMorphBase != wantMorphBase) return false;
        return offeredParams.equals(wantParams);
    }

    /** 不可接管的原因（诊断日志/探针用）。 */
    public static String rejectReason(GeoGenesisTerrain offered,
                                      long offeredSeed, TerrainParams offeredParams,
                                      boolean offeredMorphBase,
                                      long wantSeed, TerrainParams wantParams,
                                      boolean wantMorphBase) {
        if (offered == null) return "无待接管实例";
        if (!offered.isUsable()) return "实例已退役（isUsable=false）";
        if (offeredParams == null || wantParams == null) return "参数为 null";
        if (offeredSeed != wantSeed) {
            return "种子不同（预览 " + offeredSeed + " / 世界 " + wantSeed + "）";
        }
        if (offeredMorphBase != wantMorphBase) {
            return "morphBase 不同（预览 " + offeredMorphBase + " / 世界 " + wantMorphBase + "）";
        }
        if (!offeredParams.equals(wantParams)) return "参数不同（用户改过旋钮）";
        return "";
    }
}
