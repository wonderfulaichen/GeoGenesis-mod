package com.geogenesis.worldgen.river;

/**
 * 河段点链节点（确定性几何河网 · Phase 2）。
 *
 * <p>平面几何（wu 坐标语义）+ 节点水面/河床 Y（块语义，= world Y，可直接用于方块层）。
 * 主河段节点水面 = 全局海平面（{@code seaLevelY}）；支流段节点水面 = 逐节点抬升值
 * （Farseek 公式，构建期确定）。采样时沿相邻节点线性插值 → 河道内任意点水面连续。</p>
 *
 * @param x             世界 X（wu）
 * @param z             世界 Z（wu）
 * @param waterSurfaceY 节点水面 Y（块）
 * @param bedY          节点河床 Y（块）
 */
public record RiverNode(double x, double z, double waterSurfaceY, double bedY) {

    /** 占位构造（水面/河床后填；仅中间阶段使用） */
    public RiverNode(double x, double z) {
        this(x, z, 0, 0);
    }
}
