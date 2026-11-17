package com.geogenesis.worldgen.terrain;

/**
 * <b>洋中脊横剖面（确定性纯函数）</b>——2026-11-17 阶段七 B 落地。
 *
 * <p>沙箱定稿见 {@code RidgeFormProbe}（用户目检通过前先出图）。本类只做
 * <b>一个几何量</b>：给定「离轴距离 d（wu，已含蜿蜒偏移）」，返回该点的
 * <b>脊隆起高度（e，相对深海平原）</b>。</p>
 *
 * <h3>剖面形状（真实慢速洋中脊 / Mid-Atlantic Ridge 型）</h3>
 * <pre>
 *   SHOULDER_H ──╮                    ╭── 脊肩峰（全剖面最高，rift mountains）
 *                ╰─╮                ╭─╯
 *       谷壁       ╰╮              ╭╯
 *                  │              │
 *   valleyFloor ───┴──────────────┴───  中央 U 形地堑（axial valley）
 *        d=0     RIFT_HALF   SHOULDER_AT        FOOT → 0
 * </pre>
 *
 * <h3>为什么是两段式（不是三段）</h3>
 * <p>真实慢速扩张洋中脊：<b>谷壁（正断层崖）直接升到脊肩</b>，中间没有独立的
 * 「轴部台面」。沙箱首版多做了轴部台面且让它最高 ⇒ 俯视图只剩「一条沟」
 * （用户判断「像被破坏的海沟」）。现已删除该段。</p>
 *
 * <h3>比例核对（与真实 MAR 实测对比，见 {@code RidgeFormProbe} 的常量注释）</h3>
 * <table border="1">
 *   <caption>沙箱实测 vs 真实</caption>
 *   <tr><th>指标</th><th>本实现</th><th>真实 MAR</th></tr>
 *   <tr><td>谷深/总起伏</td><td>0.37</td><td>0.33~0.66</td></tr>
 *   <tr><td>谷全宽/脊半宽</td><td>0.053</td><td>0.05~0.15</td></tr>
 *   <tr><td>脊肩位置/脊半宽</td><td>0.23</td><td>0.10~0.30</td></tr>
 * </table>
 *
 * <p>⚠ 单位约定：{@code FOOT = 1500wu} 是「脊增量归零」的半宽；<b>实际翼部
 * 还会与年龄律叠加</b>（年龄律负责更大的热沉降量程），本剖面只负责局部山形。</p>
 */
public final class RidgeProfile {

    /** 轴部裂谷半宽（wu）：谷全宽 160wu。 */
    public static final double RIFT_HALF = 80.0;
    /**
     * 轴部裂谷深度（e）：相对脊肩峰 —— 0.130e ≈ 16.5 格。
     *
     * <p>⚠ 2026-11-17 落地实测：0.07 时「年龄律在轴部最高」会吃掉净落差——
     * 轴部 eBase≈−0.225、脊肩处（±300wu）eBase≈−0.24，只有 0.015e 的年龄差，
     * 于是谷底到脊肩的净落差被压到 ~7 格，特写图上脊肩几乎不可见。
     * 加深到 0.13 后净落差 ≈16.5 格；谷深/总起伏 = 0.68（真实 0.33~0.66
     * 的上沿，游戏可读性优先）。</p>
     */
    public static final double RIFT_DEPTH = 0.130;
    /** 脊肩峰离轴距离（wu）= 0.375 × FOOT。 */
    public static final double SHOULDER_AT = 300.0;
    /**
     * 脊肩峰高度（e，相对深海平原）= 0.19e ≈ 24 格。
     *
     * <p>真实 MOR 轴部 vs 深海平原总起伏 2500~3000m；其中<b>年龄律已提供</b>
     * 一部分（CrustField 的 D0=2500m → D∞=5700m），本剖面只补「局部山形」。
     * 0.19e 是沙箱目检定稿值。</p>
     */
    public static final double SHOULDER_H = 0.190;
    /**
     * 脊脚（脊增量归零处，wu）。
     *
     * <p>⚠ 2026-11-17 落地修正：沙箱用的 1500wu 太宽——生产 SUBMARINE_RIDGE
     * 类型格从 5371 暴增到 19823（脊带横向覆盖过大，把大片海床算成脊）。
     * 真实 MOR 的显著脊带（轴部到翼部坡脚）约 50~100km；本项目 800wu
     * ≈ 7km 视觉尺度（1wu≈9m），配合年龄律的更大尺度热沉降正好。</p>
     */
    public static final double FOOT = 800.0;

    /**
     * 脊隆起高度（e，相对深海平原）。
     *
     * @param d 离轴距离（wu，已含沿轴蜿蜒偏移；负数视为 0）
     * @return 0~{@link #SHOULDER_H}；{@code d ≥ FOOT} 时为 0
     */
    public static double height(double d) {
        if (d <= 0) {
            d = 0;
        }
        double valleyFloor = SHOULDER_H - RIFT_DEPTH;
        if (d < RIFT_HALF) {
            // U 形谷：平底占 40%，其余 smoothstep 升壁（陡壁 = 正断层崖）
            double t = d / RIFT_HALF;
            double wall = t < 0.40 ? 0.0 : smoothstep01((t - 0.40) / 0.60);
            return valleyFloor + (SHOULDER_H - valleyFloor) * wall;
        }
        if (d < SHOULDER_AT) {
            // 谷缘 → 脊肩峰：单调上升（前段陡、后段圆顶）
            double t = (d - RIFT_HALF) / (SHOULDER_AT - RIFT_HALF);
            return valleyFloor + (SHOULDER_H - valleyFloor) * smoothstep01(t);
        }
        if (d < FOOT) {
            // 脊肩 → 脊脚：余弦衰减
            double t = (d - SHOULDER_AT) / (FOOT - SHOULDER_AT);
            return SHOULDER_H * (0.5 + 0.5 * Math.cos(Math.PI * t));
        }
        return 0.0;
    }

    /**
     * 该点是否落在<b>轴部地堑</b>内（供「地堑内不加噪声」等判断）。
     *
     * @param d 离轴距离（wu）
     */
    public static boolean inRift(double d) {
        return d < RIFT_HALF;
    }

    private static double smoothstep01(double x) {
        double t = x < 0 ? 0 : (x > 1 ? 1 : x);
        return t * t * (3 - 2 * t);
    }

    private RidgeProfile() { }
}