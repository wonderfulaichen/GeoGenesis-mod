package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.noise.NoiseUtil;

/**
 * 气候区（抖动 Voronoi 网格）—— 消除群系椒盐噪点的根本手段。
 *
 * <p><b>为什么需要它</b>：若温度/湿度逐格求值，相邻格可因噪声抖动而跨过群系阈值，
 * 表现为「丛林紧挨热带草原」这类生态上不可能的碎斑。真实气候是大尺度连续的。
 *
 * <p><b>做法（参考 FreeTerraForged 的 ClimateModule）</b>：把世界划为抖动 Voronoi 网格，
 * 每个格取<b>最近站点中心</b>去采样温度/湿度 → 同一区内气候恒定 → 群系成片；
 * 再在区外叠回逐格的地形响应（海拔递减、大陆性、雨影），山体因此仍有垂直分异。
 *
 * <p>另输出 {@code edge}（到区边界的归一化距离，0=区中心、1=区边界），
 * 供后续做边界软化/二次采样，避免直线状硬边界。
 */
public final class ClimateRegion {

    /** 默认区尺寸（wu）。256 wu ≈ 原版群系斑块观感。 */
    public static final double DEFAULT_SIZE = 256.0;

    /**
     * 站点抖动范围（占格距的比例）：站点落在格内 [0.15, 0.85] 区间。
     *
     * <p>【2026-09-10】原实现站点固定在格心 ±0.375（即 [0.125,0.875] 区间）但以 0.5 为中心，
     * 相邻站点几乎等高/等宽 → 垂直平分线近乎<b>轴对齐直线</b> → 群系边界呈大块矩形（用户实测）。
     * 改为整格均匀抖动后多边形形状才不规则。
     *
     * <p>上限取 0.85 而非 1.0：保证最近站点必在 3×3 邻域内（格内查询点距本格站点 ≤0.707+0.35≈1.06，
     * 而隔一格的站点距离 ≥1.15）——既有足够不规则度，又无需扩大搜索半径。
     */
    private static final double JITTER_LO = 0.15;
    private static final double JITTER_SPAN = 0.70;

    private final double size;
    private int seedX;
    private int seedZ;

    public ClimateRegion(double size) {
        this.size = Math.max(16.0, size);
        this.seedX = 0;
        this.seedZ = 0;
    }

    /** 播种（与 {@link com.geogenesis.worldgen.terrain.CellGenerator#seed} 同为一次性写入）。 */
    public void seed(long worldSeed) {
        this.seedX = (int) (worldSeed & 0xffff);
        this.seedZ = (int) ((worldSeed >>> 16) & 0xffff);
    }

    /**
     * 采样结果。
     *
     * @param centerX  最近站点中心 X（wu）
     * @param centerZ  最近站点中心 Z（wu）
     * @param centerX2 次近站点中心 X（wu，用于边界混合）
     * @param centerZ2 次近站点中心 Z（wu，用于边界混合）
     * @param blend    次近站点的混合权重 [0,0.5]：区中心 0，区边界恰好 0.5
     */
    public record Sample(double centerX, double centerZ,
                         double centerX2, double centerZ2, double blend) {}

    /**
     * 查询坐标所属气候区。3×3 邻域搜最近站点（格距 ≥16 wu 且抖动 ≤0.375 格，
     * 最近站点必在 3×3 内，无需更大搜索半径）。
     *
     * <p><b>为什么还要返回次近站点</b>：若只取最近站点，气候在区界处会<b>硬跳变</b>——
     * 两侧格子的温湿来自相距很远的两个站点，可能一步跨过整个 Whittaker 分区
     * （实测出现「稀树草原 ↔ 热带雨林」这类生态上不可能的相邻）。
     * 在边界带按 {@code blend} 与次近站点混合，使气候场在区界处 C0 连续：
     * 分界线两侧的格子算出的混合结果完全相同 → 跨区必然经过中间过渡类型。
     */
    public Sample sample(double wx, double wz) {
        int gx = (int) Math.floor(wx / size);
        int gz = (int) Math.floor(wz / size);

        double best = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        double cx = wx, cz = wz, cx2 = wx, cz2 = wz;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int ix = gx + dx;
                int iz = gz + dz;
                double u = JITTER_LO + JITTER_SPAN * NoiseUtil.hash2_01(ix + seedX, iz + seedZ);
                double v = JITTER_LO + JITTER_SPAN * NoiseUtil.hash2_01(ix + seedZ + 7919, iz + seedX + 104729);
                double sx = (ix + u) * size;
                double sz = (iz + v) * size;

                double ddx = sx - wx;
                double ddz = sz - wz;
                double d = ddx * ddx + ddz * ddz;
                if (d < best) {
                    second = best;
                    cx2 = cx;
                    cz2 = cz;
                    best = d;
                    cx = sx;
                    cz = sz;
                } else if (d < second) {
                    second = d;
                    cx2 = sx;
                    cz2 = sz;
                }
            }
        }

        // F1/F2 对称权重：blend = d1/(d1+d2)
        //   · 区中心 d1→0 → blend=0（纯本区，保住"成片"）
        //   · 区边界 d1=d2 → blend=0.5
        //   · 关键性质：分界线两侧分别以各自最近站点计算，代入后代数式完全相同
        //     → 跨界处左右极限相等，气候场 C0 连续 → 跨区必然经过中间过渡类型。
        // 【2026-09-10】曾用 smoothstep(0.5,1,edge) 只在很窄的边界带混合，
        //   实测混合权重仅 ~0.1，相邻区湿度仍整幅跳变（Δm 达 0.69）→ 违例不降。
        double d1 = Math.sqrt(best);
        double d2 = Math.sqrt(second);
        double sum = d1 + d2;
        double blend = sum > 1e-9 ? d1 / sum : 0.0;
        if (blend < 0.0) blend = 0.0;
        else if (blend > 0.5) blend = 0.5;
        return new Sample(cx, cz, cx2, cz2, blend);
    }
}
