package com.geogenesis.worldgen.erosion;

/**
 * Rune 高级地形侵蚀滤镜（单次骨架塑形核心，纯 Java、零 MC 依赖）。
 *
 * <p><b>许可与溯源</b>：本文件是 Rune Skovbo Johansen "Advanced Terrain Erosion Filter"
 * （2025，版权所有，博客: https://blog.runevision.com/2026/03/fast-and-gorgeous-erosion-filter.html）
 * 的 <b>MPL-2.0 授权代码的 Java 改写（Modifications）</b>，参考了 Luke Mitchell 的 C# 移植
 * （lpmitchell/AdvancedTerrainErosion @57e79cc）。算法仍是 MPL-2.0 覆盖软件的修改版，
 * <b>不是</b>独立重写、不因此免除 MPL 义务；Rune / Luke 的版权声明按 MPL §3.4 保留。
 * 许可证全文与来源说明见 {@code resources/META-INF/licenses/}。</p>
 *
 * <p><b>契约</b>：{@link #delta} 返回归一化高度增量（不是近似梯度冒充坡度）；
 * 输入 x/z 与梯度 gx/gz 必须处于调用方的同一归一化坐标系；本类不读取 HS/VS/Forge/config，
 * 全部参数经不可变 {@link Settings} 传入，且只在 record 构造时校验一次。
 * 位置与中间量全程 double；Phacelle 格点哈希用 long 世界格整数 + <b>SplitMix64</b>
 * （增量 0x9E3779B97F4A7C15 / 混合 0xBF58476D1CE4E5B9 / 0x94D049BB133111EB，变体 30/27/31），
 * 不使用 float 世界坐标或巨大 double 乘积取 frac。</p>
 *
 * <p>保留参考的 stacked fading、partial normalization、straight gullies 内部假设坡度
 * 语义；零向量安全（不固定方向于未掩码噪声）。
 * {@code normalization} 取 [0,1)，<b>不接受 1.0</b>——参考的
 * {@code magnitude = max(1 - normalization, |interpolated|)} 在 1.0 处会把幅度下界压成 0，
 * 得到零除奇点；本类在设置边界直接拒绝。</p>
 */
public final class RuneErosionFilter {

    private RuneErosionFilter() {}

    /** 不可变设置；边界在此一次校验（非每点）。 */
    public record Settings(
            double scale,
            double strength,
            double cellScale,
            int octaves,
            double gain,
            double lacunarity,
            double normalization,
            double gullyWeight,
            double detail,
            double ridgeRounding,
            double creaseRounding,
            double inputRounding,
            double octaveRounding,
            double inputOnset,
            double octaveOnset,
            double assumedSlope,
            double assumedSlopeMix) {

        public Settings {
            if (!isFinite(scale) || scale <= 0.0)
                throw new IllegalArgumentException("scale must be finite and > 0, got " + scale);
            if (!isFinite(strength) || strength < 0.0)
                throw new IllegalArgumentException("strength must be finite and >= 0, got " + strength);
            if (!isFinite(cellScale) || cellScale <= 0.0)
                throw new IllegalArgumentException("cellScale must be finite and > 0, got " + cellScale);
            if (octaves < 0) throw new IllegalArgumentException("octaves must be >= 0, got " + octaves);
            if (!isFinite(gain) || gain < 0.0)
                throw new IllegalArgumentException("gain must be finite and >= 0, got " + gain);
            if (!isFinite(lacunarity) || lacunarity <= 0.0)
                throw new IllegalArgumentException("lacunarity must be finite and > 0, got " + lacunarity);
            if (!isFinite(normalization) || normalization < 0.0 || normalization >= 1.0)
                throw new IllegalArgumentException(
                        "normalization must be in [0,1): 1.0 is rejected because the reference "
                        + "magnitude floor max(1-normalization, |v|) collapses to 0 (zero division), got "
                        + normalization);
            if (!isFinite(gullyWeight) || gullyWeight < 0.0)
                throw new IllegalArgumentException("gullyWeight must be finite and >= 0, got " + gullyWeight);
            if (!isFinite(detail) || detail <= 0.0)
                throw new IllegalArgumentException("detail must be finite and > 0, got " + detail);
            if (!isFinite(ridgeRounding) || ridgeRounding < 0.0)
                throw new IllegalArgumentException("ridgeRounding must be finite and >= 0");
            if (!isFinite(creaseRounding) || creaseRounding < 0.0)
                throw new IllegalArgumentException("creaseRounding must be finite and >= 0");
            if (!isFinite(inputRounding) || inputRounding < 0.0)
                throw new IllegalArgumentException("inputRounding must be finite and >= 0");
            if (!isFinite(octaveRounding) || octaveRounding < 0.0)
                throw new IllegalArgumentException("octaveRounding must be finite and >= 0");
            if (!isFinite(inputOnset) || inputOnset <= 0.0)
                throw new IllegalArgumentException("inputOnset must be finite and > 0, got " + inputOnset);
            if (!isFinite(octaveOnset) || octaveOnset <= 0.0)
                throw new IllegalArgumentException("octaveOnset must be finite and > 0, got " + octaveOnset);
            if (!isFinite(assumedSlope) || assumedSlope < 0.0)
                throw new IllegalArgumentException("assumedSlope must be finite and >= 0");
            if (!isFinite(assumedSlopeMix) || assumedSlopeMix < 0.0 || assumedSlopeMix > 1.0)
                throw new IllegalArgumentException("assumedSlopeMix must be in [0,1], got " + assumedSlopeMix);
        }

        private static boolean isFinite(double v) {
            return Double.isFinite(v);
        }
    }

    private static final double TAU = 2.0 * Math.PI;
    private static final double EPS = 1e-10;

    /**
     * 归一化高度增量（不含载体本身）。返回该滤镜在归一化高度域内对载体的塑形增量；
     * 调用方负责换算回世界 e/Y 单位。x/z 与 gx/gz 必须同坐标系。
     *
     * @param x          归一化平面 X
     * @param z          归一化平面 Z
     * @param gx         归一化高度对 X 的偏导（调用方同源差分）
     * @param gz         归一化高度对 Z 的偏导
     * @param fadeTarget [-1,1] 的淡出目标：谷≈-1、峰≈+1（超界会被夹取）
     * @param cfg        不可变设置（构造时已校验）
     * @param seed       世界种子（整数；内部参与格点哈希）
     * @return 归一化高度增量；strength==0 或 octaves==0 时恒为 0.0
     */
    public static double delta(double x, double z, double gx, double gz,
                               double fadeTarget, Settings cfg, long seed) {
        // 无塑形来源（强度 0 / 无 octave）⇒ 精确 0.0，不做任何后续中间量计算。
        if (cfg.strength() == 0.0 || cfg.octaves() == 0) return 0.0;
        return filter(x, z, gx, gz, fadeTarget, cfg, seed);
    }

    // ===== ErosionFilter（octave 循环 + stacked fading）=====
    // 参考的 heightAndSlope 三元组中只有高度分量是本 API 的输出；坡度增量的累加与
    // ridgeMap/ridgeCombiMask 仅服务参考自身的坡度与脊线图输出，本类不返回，
    // 因此不累计。内部 gsx/gsz 是参考的 gullySlope 状态（影响后续 octave 的分叉方向），
    // 属于必需计算，必须保留。
    private static double filter(double px, double pz, double gx, double gz,
                                 double fadeTargetIn, Settings cfg, long seed) {
        double scale = cfg.scale();
        double strength = cfg.strength() * scale;
        double ft = clamp(fadeTargetIn, -1.0, 1.0);
        double freq = 1.0 / (scale * cfg.cellScale());

        double slopeLength = Math.max(Math.hypot(gx, gz), EPS);
        double roundingMult = 1.0;

        double roundingForInput =
                lerp(cfg.creaseRounding(), cfg.ridgeRounding(), clamp01(ft + 0.5)) * cfg.inputRounding();
        // 组合累积掩码：先由初始坡度决定，再由各 octave 的坡度更新。
        double combiMask = easeOut(smoothStart(slopeLength * cfg.inputOnset(),
                roundingForInput * cfg.inputOnset()));

        // 直沟（straight gullies）内部假设坡度：真实坡度方向与假定坡度的混合。
        // 参考 gullySlope = lerp(slope, slope/slopeLen * assumedSlope.x, assumedSlope.y)；
        // 这是塑形方向，不是物理坡度。零坡度时 lerp 退化为零向量（不固定方向）。
        double gsx = lerp(gx, gx / slopeLength * cfg.assumedSlope(), cfg.assumedSlopeMix());
        double gsz = lerp(gz, gz / slopeLength * cfg.assumedSlope(), cfg.assumedSlopeMix());

        double h = 0.0;
        for (int i = 0; i < cfg.octaves(); i++) {
            double[] ph = phacelle(px * freq, pz * freq, gsx, gsz,
                    cfg.cellScale(), cfg.normalization(), seed);
            double phx = ph[0], phy = ph[1];
            double sdx = ph[2] * -freq;
            double sdz = ph[3] * -freq;
            double sloping = Math.abs(phy);

            // 无掩码、按最陡处归一化的累积斜率，供下一 octave 复用。
            gsx += Math.signum(phy) * sdx * strength * cfg.gullyWeight();
            gsz += Math.signum(phy) * sdz * strength * cfg.gullyWeight();

            double fgx = lerp(ft, phx * cfg.gullyWeight(), combiMask);
            h += fgx * strength;
            ft = fgx;

            double roundingOct = lerp(cfg.creaseRounding(), cfg.ridgeRounding(),
                    clamp01(phx + 0.5)) * roundingMult;
            double newMask = easeOut(smoothStart(sloping * cfg.octaveOnset(),
                    roundingOct * cfg.octaveOnset()));
            combiMask = powInv(combiMask, cfg.detail()) * newMask;

            strength *= cfg.gain();
            freq *= cfg.lacunarity();
            roundingMult *= cfg.octaveRounding();
        }
        return h;
    }

    // ===== Phacelle Noise（4x4 格点钟形权重混合条纹；long 整数格点 + SplitMix64 哈希）=====
    private static double[] phacelle(double px, double pz, double dirX, double dirY,
                                     double stripeFreq, double normalization, long seed) {
        double len = Math.hypot(dirX, dirY);
        double ndx, ndy;
        if (len < EPS) {
            // 零坡度：不固定条纹方向，退化为无掩码相位——安全且有限。
            ndx = 0.0;
            ndy = 0.0;
        } else {
            ndx = dirX / len;
            ndy = dirY / len;
        }
        double sdx = -ndy * stripeFreq * TAU;
        double sdz = ndx * stripeFreq * TAU;
        double off = 0.25 * TAU;

        long xi = floorL(px), zi = floorL(pz);
        double fx = px - xi, fz = pz - zi;
        double phaseX = 0.0, phaseY = 0.0, wSum = 0.0;
        for (long i = -1; i <= 2; i++) {
            for (long j = -1; j <= 2; j++) {
                long gx = xi + i, gz = zi + j;
                double[] rnd = hash2(gx, gz, seed);
                double rx = rnd[0] * 0.5, rz = rnd[1] * 0.5;
                double vx = fx - i - rx;
                double vz = fz - j - rz;
                double sqr = vx * vx + vz * vz;
                double w = Math.exp(-sqr * 2.0);
                w = Math.max(0.0, w - 0.01111);
                wSum += w;
                double wave = vx * sdx + vz * sdz + off;
                phaseX += Math.cos(wave) * w;
                phaseY += Math.sin(wave) * w;
            }
        }
        double inv = 1.0 / Math.max(wSum, EPS);
        double ix = phaseX * inv, iy = phaseY * inv;
        double mag = Math.hypot(ix, iy);
        // 参考的 partial normalization 下界。normalization ∈ [0,1) 由 Settings 保证
        // ⇒ mag ≥ 1 - normalization > 0，除法无零除奇点（normalization = 1.0 被拒绝）。
        mag = Math.max(1.0 - normalization, mag);
        double invMag = 1.0 / mag;
        return new double[]{ix * invMag, iy * invMag, sdx, sdz};
    }

    // ===== 世界格点确定性哈希：long 格点 + SplitMix64（Steele & Lea 2014），返回 [-1,1]^2 =====
    // SplitMix64：63 位黄金比例增量 → xor-shift-multiply(30) → xor-shift-multiply(27) → xor-shift(31)。
    // 注意这不是 MurmurHash3 的 fmix64 终结器（33/33/33 + 0xFF51AFD7ED558CCD/0xC4CEB9FE1A85EC53）。
    private static final long SPLITMIX_GOLDEN = 0x9E3779B97F4A7C15L;

    private static double[] hash2(long gx, long gz, long seed) {
        long h = splitMix64(gx * SPLITMIX_GOLDEN ^ gz);
        h = splitMix64(h ^ seed);
        double u = (h >>> 11) * 0x1.0p-53;                 // [0,1)
        h = splitMix64(h + SPLITMIX_GOLDEN);
        double v = (h >>> 11) * 0x1.0p-53;                 // [0,1)
        return new double[]{u * 2.0 - 1.0, v * 2.0 - 1.0};
    }

    private static long splitMix64(long z) {
        z += SPLITMIX_GOLDEN;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ===== 工具 =====
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static double clamp(double v, double lo, double hi) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }

    private static double clamp01(double v) { return clamp(v, 0.0, 1.0); }

    private static double easeOut(double t) {
        double v = 1.0 - clamp01(t);
        return 1.0 - v * v;
    }

    private static double smoothStart(double t, double s) {
        if (t >= s) return t - 0.5 * s;
        return 0.5 * t * t / s;
    }

    private static double powInv(double t, double p) {
        return 1.0 - Math.pow(1.0 - clamp01(t), p);
    }

    private static long floorL(double v) {
        return (long) Math.floor(v);
    }
}
