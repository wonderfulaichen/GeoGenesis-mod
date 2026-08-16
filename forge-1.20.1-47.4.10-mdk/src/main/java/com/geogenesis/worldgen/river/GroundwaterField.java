package com.geogenesis.worldgen.river;

/**
 * 地下水位场（汇水分析驱动 · 阶段 C：泉眼 / 暗河下潜）。
 *
 * <p><b>模型</b>（水文简化）：地下水位 GWT(x,z) ≈ 地形低通（32wu 8 邻平均）
 * − 常偏置 + 低频气候噪声。物理语义：</p>
 * <ul>
 *   <li>GWT 低于地表 = 潜水面在地层中（正常）；</li>
 *   <li>GWT <b>高于地表</b> = 地下水出露（洼地/谷底低于区域潜水面）→
 *       <b>泉眼</b>（水压冒出地表，喀斯特泉/谷底泉）；</li>
 *   <li>河道上 isSubsurface（地表覆盖厚 = 当地面高于潜水面较多）→
 *       <b>暗河段</b>：水面埋在地层下，视觉"河消失"；下游地势低洼处重新
 *       出露成泉 → "河消失又出现"（喀斯特暗河视觉，plan 阶段 C）。</li>
 * </ul>
 *
 * <p>纯函数（世界坐标 + e 场 + 确定性噪声），无缓存、无状态 → 任意时序
 * 同结果。数值：偏置 −0.015（e ≈ 1.5 块，多数区域潜水面在表土下），
 * 噪声 ±0.03（湿润区潜水面抬升）。</p>
 */
final class GroundwaterField {

    /** 地形低通半径（wu）：32wu 邻（与 LakeBuilder.DEPTH_SCAN 同量级） */
    static final int SMOOTH = 32;
    /** 潜水面相对地形低通的常偏置（e ≈ 1.5 块：多数区域地表下有潜水面） */
    static final double BIAS = 0.015;
    /** 出露门槛（e ≈ 1 块）：GWT − 地表 e &gt; 此值 = 泉/暗河 */
    static final double SPRING_EPS = 0.01;

    private final RiverNetwork net;

    GroundwaterField(RiverNetwork net) {
        this.net = net;
    }

    /** 地下水位 GWT（e 语义；纯函数） */
    double gwtAt(double wx, double wz) {
        double avg = 0;
        for (int k = 0; k < 8; k++) {
            avg += net.eAt(wx + FlowField.DX8[k] * SMOOTH, wz + FlowField.DZ8[k] * SMOOTH);
        }
        avg /= 8.0;
        // 低频气候噪声（确定性 sin 混合：湿润区潜水面抬升）
        double n = Math.sin(wx * 0.011 + wz * 0.007) * 0.02
                 + Math.sin(wx * 0.0032 - wz * 0.0021) * 0.01;
        return avg + n - BIAS;
    }

    /** 泉眼判定：GWT 高于地表 ≥ SPRING_EPS（地下水出露） */
    boolean springAt(double wx, double wz) {
        return gwtAt(wx, wz) > net.eAt(wx, wz) + SPRING_EPS;
    }

    /** 暗河判定：地表覆盖 ≥5 块（GWT 低于地表 0.05e）→ 水面埋地层下。
     *  ★ 0.03 实锤：暗河节点 19.4% = 河段频繁断裂（"消失又出现"过密）；
     *  0.05 = 仅深山地段（覆盖厚）隐藏，喀斯特效果适度 */
    boolean isSubsurface(double wx, double wz) {
        return gwtAt(wx, wz) < net.eAt(wx, wz) - SPRING_EPS * 5.0;
    }
}
