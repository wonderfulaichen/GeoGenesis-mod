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
 * <h3>比例核对（与真实 MAR 实测对比；2026-11-18 用户"中间的凹槽有这么宽吗"复核后重算）</h3>
 * <p>水平标定口径：脊带半宽 FOOT=200wu ≈ 真实显著脊带 50~100km（⇒ 1wu ≈ 250~500m）。</p>
 * <table border="1">
 *   <caption>本实现 vs 真实 MAR</caption>
 *   <tr><th>指标</th><th>本实现</th><th>真实 MAR</th></tr>
 *   <tr><td>谷底平底宽/脊带半宽</td><td>20/200 = 0.10</td><td>3~5km/50~100km = 0.03~0.10</td></tr>
 *   <tr><td>谷全宽（壁到壁）/脊带半宽</td><td>50/200 = 0.25</td><td>10~30km/50~100km = 0.10~0.30</td></tr>
 *   <tr><td>谷深(谷底→脊肩)/脊肩→平原</td><td>0.58~1.06（随谷深分段）</td><td>0.33~0.66（本实现偏深，
 *       valleyScale=1 时谷底略低于平原 −0.005e，未动——用户未质疑深度）</td></tr>
 *   <tr><td>脊肩位置/脊带半宽</td><td>80/200 = 0.40</td><td>0.10~0.30（本实现偏外，用户已接受整体形状，未动）</td></tr>
 * </table>
 *
 * <p>⚠ 单位约定：{@code FOOT = 200wu} 是「脊增量归零」的半宽；<b>实际翼部
 * 还会与年龄律叠加</b>（年龄律负责更大的热沉降量程），本剖面只负责局部山形。</p>
 */
public final class RidgeProfile {

    /**
     * 轴部裂谷半宽（wu）。谷底平底占 40% ⇒ 平底 20wu；壁到壁全宽 50wu。
     *
     * <p>⚠ 2026-11-18 二次收窄（用户："中间的凹槽有这么宽吗"）：旧值 60 把
     * 谷全宽做到 120wu，按水平标定（1wu ≈ 250~500m）= 30~60km，而真实 MAR
     * 轴谷壁到壁只有 10~30km（谷底平底 3~5km ⇒ 本实现 20wu ≈ 5~10km）；
     * 谷占脊带半宽比例 0.60 远超真实 0.10~0.30。25 ⇒ 比例 0.25、平底 0.10，
     * 均落入真实区间。谷壁（正断层崖）因此更陡：落差 ~11 格 / 15wu，
     * 对应真实断崖 45°+ 的近直立坡。</p>
     */
    public static final double RIFT_HALF = 25.0;
    /**
     * 轴部裂谷深度（e）：相对脊肩峰 —— 0.045e ≈ 5.7 格。
     *
     * <p>⚠ 2026-11-17 二次定标（用户："这是洋山脊吗？我怎么感觉像海沟？"）：
     * 旧值 0.13 让<b>谷深/总起伏 = 0.68</b>，而真实慢速洋中脊（大西洋中脊型，
     * MAR 23°N/36°N 实测）该比值只有 <b>0.2~0.4</b>：总起伏（脊肩→深海平原）
     * 2500~3000m，轴部谷只深 1000~2000m。0.13 的深谷在俯视图上就是一条
     * 「海沟」——地质方向完全错了（洋中脊是<b>造山</b>、海沟是<b>挖沟</b>，
     * 机制相反）。0.045 ⇒ 比值 0.24 ✔。
     * （⚠ 0.045 那版随脊带加宽被再次调整；现值 0.090 × valleyScale∈[0.55,1]
     * ——谷深比真实偏深一点，用户未质疑深度，2026-11-18 保持。）
     *
     * <p>另外 0.07 那版也不对：实测「年龄律在轴部最高」会吃掉净落差
     * （轴部 eBase≈−0.225 vs 脊肩处 ±300wu 的 −0.24，只有 0.015e 差），
     * 于是脊肩不可见。现在靠<b>加宽脊带</b>（脊肩距轴 80wu 而非 300wu）
     * 让脊肩落在年龄律明显更浅的位置，净落差恢复。</p>
     */
    public static final double RIFT_DEPTH = 0.090;
    /** 脊肩峰离轴距离（wu）= 0.40 × FOOT。 */
    public static final double SHOULDER_AT = 80.0;
    /**
     * 脊肩峰高度（e，相对深海平原）= 0.19e ≈ 24 格。
     *
     * <p>真实 MOR 轴部 vs 深海平原总起伏 2500~3000m；其中<b>年龄律已提供</b>
     * 一部分（CrustField 的 D0=2500m → D∞=5700m），本剖面只补「局部山形」。
     * 0.19e 是沙箱目检定稿值。</p>
     */
    public static final double SHOULDER_H = 0.085;
    /**
     * 脊脚（脊增量归零处，wu）。
     *
     * <p>⚠ 2026-11-17 落地修正：沙箱用的 1500wu 太宽——生产 SUBMARINE_RIDGE
     * 类型格从 5371 暴增到 19823（脊带横向覆盖过大，把大片海床算成脊）。
     * 真实 MOR 的显著脊带（轴部到翼部坡脚）约 50~100km。本项目板块格子
     * 2000wu ⇒ 脊带 420wu 半宽让板块内部保留 1160wu 的平坦深海平原，
     * 脊带之间不会互相叠成「平行亮纹」。</p>
     */
    public static final double FOOT = 200.0;

    /**
     * 脊隆起高度（e，相对深海平原）。
     *
     * @param d           离轴距离（wu，已含沿轴蜿蜒偏移；负数视为 0）
     * @param valleyScale 地堑深度沿轴调制 ∈[0,1]（0=无谷段/轴部高地，1=全深谷）
     *                    ——真实 MOR 的轴谷深度沿轴强烈变化（分段性）
     * @return 0~{@link #SHOULDER_H}；{@code d ≥ FOOT} 时为 0
     */
    public static double height(double d, double valleyScale) {
        if (d <= 0) {
            d = 0;
        }
        double riftD = RIFT_DEPTH * Math.max(0.0, Math.min(1.0, valleyScale));
        double valleyFloor = SHOULDER_H - riftD;
        // 谷缘（断层崖顶）高度：介于谷底与脊肩之间。
        //   ⚠ 2026-11-17 修不连续：旧代码谷壁升满到 SHOULDER_H、下一段又从
        //   valleyFloor 起算 ⇒ d=RIFT_HALF 处有 (SHOULDER_H−valleyFloor) 的
        //   垂直跳变，剖面图上轴两侧出现两根尖刺。
        double rim = valleyFloor + (SHOULDER_H - valleyFloor) * 0.78;
        if (d < RIFT_HALF) {
            // U 形谷：平底占 40%，其余 smoothstep 升壁（陡壁 = 正断层崖）
            double t = d / RIFT_HALF;
            double wall = t < 0.40 ? 0.0 : smoothstep01((t - 0.40) / 0.60);
            return valleyFloor + (rim - valleyFloor) * wall;
        }
        if (d < SHOULDER_AT) {
            // 谷缘 → 脊肩峰：从 rim 继续升到脊肩（C0 连续、C1 平滑）
            double t = (d - RIFT_HALF) / (SHOULDER_AT - RIFT_HALF);
            return rim + (SHOULDER_H - rim) * smoothstep01(t);
        }
        if (d < FOOT) {
            // 脊肩 → 脊脚：余弦衰减 + **断层阶地**（2026-11-18）。
            //   真实慢速洋中脊的翼部不是光滑斜坡，是<b>阶梯状断层块</b>——
            //   正断层把翼部切成一级级向外的台地（每级落差 50~150m）。
            //   没有阶地 ⇒ 翼部像「光滑的斜坡滑到平原」⇒ 脊带外缘是一条
            //   干净的折线（用户圈出的「错层折线」）。
            //   4 道阶步、每道落差 ~1.4→0.7 格递减、smoothstep 宽 22wu（C¹）。
            double t = (d - SHOULDER_AT) / (FOOT - SHOULDER_AT);
            double base = SHOULDER_H * (0.5 + 0.5 * Math.cos(Math.PI * t));
            double steps = 0.0;
            for (int k = 1; k <= 4; k++) {
                double dk = SHOULDER_AT + (FOOT - SHOULDER_AT) * k * 0.18;
                steps -= 0.011 * Math.pow(0.8, k - 1) * smoothstep01((d - dk) / 22.0);
            }
            return Math.max(0.0, base + steps * (base / SHOULDER_H));
        }
        return 0.0;
    }

    private static double smoothstep01(double x) {
        double t = x < 0 ? 0 : (x > 1 ? 1 : x);
        return t * t * (3 - 2 * t);
    }

    private RidgeProfile() { }
}