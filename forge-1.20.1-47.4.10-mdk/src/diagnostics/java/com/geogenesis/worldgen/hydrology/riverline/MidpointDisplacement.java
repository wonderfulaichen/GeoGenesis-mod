package com.geogenesis.worldgen.hydrology.riverline;

/**
 * 折线节点类型 + 地形 e 采样抽象。
 *
 * <p><b>★ 2026-09-19 清理（复刻前收敛，见 PLAN 的 P1）：</b>
 * 本类原有 {@code generate()}（确定性中点位移分形折线）与 {@code biasToValley()}（节点贴谷偏置）
 * <b>已删除</b> —— 全仓 {@code search_content} 搜索<b>零调用</b>：它们是审计出的
 * "3 套河线生成"里<b>已死的那两套</b>之一（另一套是已删的 {@code flowaccum/RiverTrace.java}）。
 * 生产河线由 {@link RiverLineNetwork#traceRiver} 内联生成（D8 路径 → 粒子化节点 → smoothPath → meander）。</p>
 *
 * <p>本类现只保留<b>仍被使用</b>的两项：
 * <ul>
 *   <li>{@link Node} —— 折线节点，被 {@code RiverLineNetwork} / {@code RiverPolyline} 使用；</li>
 *   <li>{@link ElevationSampler} —— 地形 e 采样抽象，被 {@code RiverLineNetwork} 与多个探针注入。</li>
 * </ul>
 * ⚠ 后继者注意：本类名保留仅为兼容既有引用；<b>不要</b>因为名字叫 "MidpointDisplacement"
 * 就以为里面还有中点位移实现。</p>
 */
public final class MidpointDisplacement {
    private MidpointDisplacement() { }

    /** 折线节点。 */
    public record Node(double x, double z) { }

    /** 地形 e 采样抽象（{@code CellGenerator::terrainEQuick} 注入）。 */
    public interface ElevationSampler {
        double eAt(double wx, double wz);
    }
}
