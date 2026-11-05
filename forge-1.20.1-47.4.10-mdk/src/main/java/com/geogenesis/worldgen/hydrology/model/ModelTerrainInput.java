package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * Explicit model height boundary. Coordinates are blocks, heights are continuous Y.
 * Legacy adapters preserve historical sampling expressions and shared cache semantics.
 * Isolated B/P inputs provide full semantic identity and use session-owned model stores.
 * Model JVM strategy remains fixed per process; property hot switching is unsupported.
 */
public interface ModelTerrainInput {
    /** Null preserves the legacy cache key; isolated inputs supply full semantic identity. */
    default String cacheNamespace() { return null; }

    TerrainParams params();
    double seaLevel();
    double mappedSeaLevel();

    /** Original column snapshot B (legacy sample, not the coarse quick field). */
    double baseHeightAtBlock(double bx, double bz);

    /** Coarse B, explicitly zero process tiles and zero prewarm. */
    double coarseBaseHeightAtBlock(double bx, double bz);

    /** P with the old provider's reciprocal-multiply coordinate mapping. */
    double processHeightAtBlock(double bx, double bz);

    /** Pipeline uses division by its supplied TP scale, not reciprocal multiplication. */
    double pipelineHeightAtBlock(double bx, double bz, double hs, boolean eroded);

    /** Margin's historically clamped-scale division path. */
    double marginHeightAtBlock(double bx, double bz, double hs);

    /**
     * 骨架<b>塑形规则版本</b>，由会话自己携带（2026-11-05 R2）。
     *
     * <p>默认 {@link DeterministicSkeleton.Shaping#LEGACY}：旧 {@code CellGenerator} 生产路径
     * 与一切既有适配器<b>不改一行</b>就是旧规则 ⇒ 产出逐位不变。只有新的 B/P 会话
     * （{@link ProcessModelTerrainInput}）显式覆盖为 {@code CANONICAL}。</p>
     *
     * <p>⚠ 规则名会进入 {@link ModelPolicyIdentity#namespace} ⇒ 两种规则不共用缓存命名空间。</p>
     */
    default DeterministicSkeleton.Shaping shaping() { return DeterministicSkeleton.Shaping.LEGACY; }

    /**
     * ★ R3：<b>整数水柱四邻接连接计划</b>规则版本，由会话自己携带（2026-11-05 R3）。
     *
     * <p>默认 {@code false}：旧 {@code CellGenerator} 生产路径与一切既有适配器
     * <b>不改一行</b>就是「不做连接」⇒ 列计划逐位不变。只有新的 B/P 会话
     * （{@link ProcessModelTerrainInput}）显式覆盖为 {@code true}。</p>
     *
     * <p>规则语义见 {@code ModelHydrologyProvider#applyIntegerWaterConnector}：只对
     * <b>已有水柱、非湖、非海床</b>的河列，按四邻接补"仅下切"的必要量。
     * ⚠ 该规则名进入 {@link ModelPolicyIdentity#namespace} ⇒ 两种规则<b>不共用缓存命名空间</b>。</p>
     */
    default boolean integerWaterConnector() { return false; }

    /** Application/config contribution only; model adds unchanged policy/version terms. */
    long strategyContribution(long hash);
    void prewarm(double originX, double originZ, double span, double hs);
    int processCacheSize();
    long processCacheEvictions();
}
