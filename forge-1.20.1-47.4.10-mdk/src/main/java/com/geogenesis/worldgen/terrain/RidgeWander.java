package com.geogenesis.worldgen.terrain;

/**
 * <b>洋中脊轴蜿蜒（共享）</b>——阶段七 B（2026-11-17）。
 *
 * <p>Voronoi 板块边界是直线段 ⇒ 洋中脊轴（脊带中心、轴部裂谷）都是「尺子画出来的
 * 直线」，与真实洋中脊的蜿蜒形态差很远。本类提供<b>沿轴连续的低频偏移</b>
 * （±40wu，两频叠加），由两个消费方共享<b>同一个函数、同一个种子</b>：</p>
 *
 * <ul>
 *   <li>{@code OceanFeatures}：脊带体形 {@code shape(dist + wander)}——脊翼/脊肩沿轴摆动；</li>
 *   <li>{@code CrustField}：轴部裂谷 {@code gaussian(distRaw + wander)}——裂谷跟着轴心走。</li>
 * </ul>
 *
 * <p>⚠ <b>为什么必须共享</b>：若只有脊带蜿蜒而裂谷不蜿蜒（或二者噪声不同源），
 * 裂谷会偏离脊带中心——图上表现为「黑色裂谷线不在脊带中间」，比直线更假。
 * 实测教训：B 初版只在 OceanFeatures 加蜿蜒，特写图上裂谷与黄色脊带错开 ~100wu。</p>
 *
 * <p>连续性：{@link #at} 是 {@code alongCoord} 的 smooth value-noise 叠加，
 * 处处连续可微；{@code alongCoord} 本身连续（见 {@code TectonicField.Sample}）。
 * 铁律自查：这是「把连续偏移加到连续距离上」，不是用 dist 当噪声坐标做量化/配对。</p>
 */
public final class RidgeWander {

    /** 蜿蜒幅度（wu）：±90（实测 ±40 在 1.7wu/px 特写图上只有 23px，肉眼看不出）。 */
    public static final double AMP_WU = 90.0;
    /** 主波长（wu）——沿轴约 700wu 起伏一次（板块间距 2000+wu 的 1/3）。 */
    private static final double WAVE_MAIN = 700.0;
    /** 次波长（wu）——叠一点小尺度的不规则。 */
    private static final double WAVE_SUB = 260.0;

    private static final long SALT_MAIN = 0x4F3A9C15D27B8061L;
    private static final long SALT_SUB = 0x7B1D5E93A6C2048FL;

    /**
     * 沿轴偏移量（wu，could 为正/负）。
     *
     * @param worldSeed  世界种子（保证不同世界不同蜿蜒，且与构造场确定性一致）
     * @param alongCoord 沿走向坐标（{@code TectonicField.Sample.alongCoord}）
     */
    public static double at(long worldSeed, double alongCoord) {
        double n1 = vnoise(alongCoord / WAVE_MAIN, worldSeed ^ SALT_MAIN);
        double n2 = vnoise(alongCoord / WAVE_SUB + 13.7, worldSeed ^ SALT_SUB);
        return AMP_WU * (n1 + 0.5 * n2) / 1.5;
    }

    /** 一维 smooth value noise ∈[−1,1]（沿 alongCoord，独立于世界坐标的 x/z 噪声）。 */
    private static double vnoise(double x, long seed) {
        double fx = Math.floor(x);
        long ix = (long) fx;
        double t = x - fx;
        t = t * t * (3 - 2 * t);
        double a = unit(hash(seed, ix)) * 2 - 1;
        double b = unit(hash(seed, ix + 1)) * 2 - 1;
        return a * (1 - t) + b * t;
    }

    private static long hash(long seed, long i) {
        long h = seed ^ (i * 0x9E3779B97F4A7C15L);
        h = Long.rotateLeft(h, 31);
        h *= 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0x2545F4914F6CDD1DL;
        h ^= h >>> 32;
        return h;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private RidgeWander() { }
}