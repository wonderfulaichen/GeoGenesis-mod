package com.geogenesis.worldgen.erosion;

import java.util.Random;
import java.util.function.Supplier;

/**
 * RuneErosionFilter 合成核验（纯 JDK，main 工具）。任一断言失败 → {@code System.exit(1)}。
 *
 * <p>使用的 {@link #base()} 即候选归一化 settings（scale=0.15 / strength=0.22 / cellScale=1.1 …），
 * 不是旧 Ridge 的块尺度配置。阈值均由候选参数下的实测值标定并写明依据，不做无依据放宽：
 * <ul>
 *   <li>尺度不变性 D(kp,kS)=k·D(p,S)：实测相对误差 0（逐位相等），断言 ≤1e-12。</li>
 *   <li>零坡度平坡：combiMask=0 ⇒ delta 恒为 fadeTarget·Σstrength，实测逐步差恰为 0，断言精确相等。</li>
 *   <li>±30e6/610 远点：实测 spread≈4.8e-3、邻居差 1e-5~2e-4，断言 spread&gt;1e-4 且邻居差&gt;1e-9。</li>
 *   <li>圆丘/盆地近顶点：差按 O(ε²) 衰减（1e-4→5.8e-7，1e-6→5.9e-11），断言单调不增且 1e-6 处 ≤1e-8。</li>
 * </ul>
 * 注意：零坡度近顶点检查只是该合成剖面上的局部经验连续性，<b>不构成全域连续性定理</b>。</p>
 */
public final class RuneErosionCheck {

    private RuneErosionCheck() {}

    // ===== 候选归一化 settings（与生产候选一致）=====
    private static final double SCALE = 0.15;
    private static final double STRENGTH = 0.22;
    private static final double CELL_SCALE = 1.1;
    private static final double GAIN = 0.5;
    private static final double LACUNARITY = 2.0;
    private static final double NORMALIZATION = 0.5;
    private static final double GULLY_WEIGHT = 0.65;
    private static final double DETAIL = 1.5;
    private static final double RIDGE_ROUNDING = 0.1;
    private static final double CREASE_ROUNDING = 0.12;
    private static final double INPUT_ROUNDING = 0.1;
    private static final double OCTAVE_ROUNDING = 2.0;
    private static final double INPUT_ONSET = 1.25;
    private static final double OCTAVE_ONSET = 1.25;
    private static final double ASSUMED_SLOPE = 0.7;
    private static final double ASSUMED_SLOPE_MIX = 1.0;

    private static RuneErosionFilter.Settings base() {
        return new RuneErosionFilter.Settings(SCALE, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING,
                INPUT_ROUNDING, OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET,
                ASSUMED_SLOPE, ASSUMED_SLOPE_MIX);
    }

    /** 同一候选参数，仅替换 strength（掩码/条纹其余不变），供零强度用例。 */
    private static RuneErosionFilter.Settings withStrength(double strength) {
        return new RuneErosionFilter.Settings(SCALE, strength, CELL_SCALE, 4, GAIN, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING,
                INPUT_ROUNDING, OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET,
                ASSUMED_SLOPE, ASSUMED_SLOPE_MIX);
    }

    /** 同一候选参数，仅替换 octaves，供零 octave 用例。 */
    private static RuneErosionFilter.Settings withOctaves(int octaves) {
        return new RuneErosionFilter.Settings(SCALE, STRENGTH, CELL_SCALE, octaves, GAIN, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING,
                INPUT_ROUNDING, OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET,
                ASSUMED_SLOPE, ASSUMED_SLOPE_MIX);
    }

    /** 同一候选参数，仅替换 scale（尺度不变性用例）。 */
    private static RuneErosionFilter.Settings withScale(double scale) {
        return new RuneErosionFilter.Settings(scale, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING,
                INPUT_ROUNDING, OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET,
                ASSUMED_SLOPE, ASSUMED_SLOPE_MIX);
    }

    /** 全参数显式构造（边界与近单位归一化用例）。 */
    private static RuneErosionFilter.Settings settings(double scale, double strength, double cellScale,
                                                       int octaves, double gain, double lacunarity,
                                                       double normalization, double gullyWeight,
                                                       double detail, double ridgeRounding,
                                                       double creaseRounding, double inputRounding,
                                                       double octaveRounding, double inputOnset,
                                                       double octaveOnset, double assumedSlope,
                                                       double assumedSlopeMix) {
        return new RuneErosionFilter.Settings(scale, strength, cellScale, octaves, gain, lacunarity,
                normalization, gullyWeight, detail, ridgeRounding, creaseRounding, inputRounding,
                octaveRounding, inputOnset, octaveOnset, assumedSlope, assumedSlopeMix);
    }

    /** 各 octave 强度之和（|delta| 的解析上界：|fgx| ≤ 1）。 */
    private static double sumStrength(RuneErosionFilter.Settings s) {
        double st = s.strength() * s.scale();
        double sum = 0.0;
        for (int i = 0; i < s.octaves(); i++) { sum += st; st *= s.gain(); }
        return sum;
    }

    public static void main(String[] args) {
        checkSettingsBoundaries();
        checkStrengthZeroIsZero();
        checkZeroOctavesIsZero();
        checkFlatZeroSlopeInvariant();
        checkPeakValleyFinite();
        checkFlatOrderInvariance();
        checkScaleInvariance();
        checkFarCoordinates();
        checkApexApproach();
        checkNearbyNeighbours();
        System.out.println("RUNE-EROSION-CHECK PASS");
        System.exit(0);
    }

    // 设置边界：normalization=1.0 必须被拒绝（参考的幅度下界 max(1-normalization,|v|) 会归零 → 零除）。
    private static void checkSettingsBoundaries() {
        expectReject("normalization=1.0", () -> settings(SCALE, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                1.0, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX));
        expectReject("normalization>1", () -> settings(SCALE, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                1.5, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX));
        expectReject("normalization<0", () -> settings(SCALE, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                -0.01, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX));
        expectReject("NaN normalization", () -> settings(SCALE, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                Double.NaN, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX));
        expectReject("scale=0", () -> settings(0.0, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX));
        expectReject("cellScale<=0", () -> settings(SCALE, STRENGTH, 0.0, 4, GAIN, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX));
        expectReject("octaves<0", () -> settings(SCALE, STRENGTH, CELL_SCALE, -1, GAIN, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX));
        expectReject("assumedSlopeMix>1", () -> settings(SCALE, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, 1.5));
        expectReject("gain<0", () -> settings(SCALE, STRENGTH, CELL_SCALE, 4, -0.1, LACUNARITY,
                NORMALIZATION, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX));

        // 合法上界附近仍有限：normalization=0.999 的幅度下界是 0.001（不是 0）。
        RuneErosionFilter.Settings nearUnit = settings(SCALE, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                0.999, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX);
        for (double[] p : SAMPLES) {
            if (!Double.isFinite(RuneErosionFilter.delta(p[0], p[1], p[2], p[3], p[4], nearUnit, 1L)))
                fail("normalization=0.999 non-finite at " + p[0] + "," + p[1]);
        }
        // normalization=0 表示不做归一化（不设幅度下界），不是“完全归一化”；零坡度处仍须有限。
        RuneErosionFilter.Settings noNorm = settings(SCALE, STRENGTH, CELL_SCALE, 4, GAIN, LACUNARITY,
                0.0, GULLY_WEIGHT, DETAIL, RIDGE_ROUNDING, CREASE_ROUNDING, INPUT_ROUNDING,
                OCTAVE_ROUNDING, INPUT_ONSET, OCTAVE_ONSET, ASSUMED_SLOPE, ASSUMED_SLOPE_MIX);
        if (!Double.isFinite(RuneErosionFilter.delta(0.0, 0.0, 0.0, 0.0, 0.0, noNorm, 1L)))
            fail("normalization=0 zero-slope non-finite");
        for (double[] p : SAMPLES) {
            if (!Double.isFinite(RuneErosionFilter.delta(p[0], p[1], p[2], p[3], p[4], noNorm, 1L)))
                fail("normalization=0 non-finite at " + p[0] + "," + p[1]);
        }
    }

    private static void expectReject(String label, Supplier<RuneErosionFilter.Settings> factory) {
        try {
            factory.get();
        } catch (IllegalArgumentException expected) {
            return;
        }
        fail("Settings should reject " + label);
    }

    private static void checkStrengthZeroIsZero() {
        RuneErosionFilter.Settings s = withStrength(0.0);
        for (double[] p : SAMPLES) {
            if (RuneErosionFilter.delta(p[0], p[1], p[2], p[3], p[4], s, 1337L) != 0.0)
                fail("strength=0 must give delta exactly 0 at " + p[0] + "," + p[1]);
        }
    }

    private static void checkZeroOctavesIsZero() {
        RuneErosionFilter.Settings s = withOctaves(0);
        for (double[] p : SAMPLES) {
            if (RuneErosionFilter.delta(p[0], p[1], p[2], p[3], p[4], s, 3L) != 0.0)
                fail("octaves=0 must give delta exactly 0 at " + p[0] + "," + p[1]);
        }
    }

    /**
     * 零坡度平坡解析不变量：slopeLength=0 ⇒ combiMask=0 ⇒ fgx 恒等于 fadeTarget
     * ⇒ delta = fadeTarget·Σstrength，与 x 无关。实测逐步差恰为 0，故断言精确相等。
     */
    private static void checkFlatZeroSlopeInvariant() {
        RuneErosionFilter.Settings s = base();
        double sum = sumStrength(s);
        for (double ft : new double[]{-1.0, -0.25, 0.0, 0.6, 1.0}) {
            double expect = ft * sum;
            for (int i = 0; i <= 64; i++) {
                double d = RuneErosionFilter.delta(i * 0.125, -i * 0.0625, 0.0, 0.0, ft, s, 42L);
                if (!Double.isFinite(d)) fail("flat row non-finite at step " + i + " ft=" + ft);
                if (Math.abs(d - expect) > 1e-12)
                    fail("flat zero-slope invariant broken: d=" + d + " expected=" + expect + " ft=" + ft);
            }
        }
    }

    private static void checkPeakValleyFinite() {
        RuneErosionFilter.Settings s = base();
        for (double ft : new double[]{-1.0, -0.5, 0.5, 1.0}) {
            for (double[] p : SAMPLES) {
                double d = RuneErosionFilter.delta(p[0], p[1], p[2], p[3], ft, s, 99L);
                if (!Double.isFinite(d)) fail("non-finite delta ft=" + ft + " at " + p[0] + "," + p[1]);
                if (Math.abs(d) > sumStrength(s) * (1.0 + 1e-12))
                    fail("|delta| above analytic bound at ft=" + ft + " p=" + p[0] + "," + p[1]);
            }
        }
    }

    private static void checkFlatOrderInvariance() {
        RuneErosionFilter.Settings s = base();
        double[] ref = new double[32];
        for (int i = 0; i < 32; i++) {
            ref[i] = RuneErosionFilter.delta(i * 0.5, i * 0.25, 0.0, 0.0, 0.3, s, 7L);
        }
        Random rnd = new Random(123);
        for (int k = 0; k < 200; k++) {
            int i = rnd.nextInt(32);
            double d = RuneErosionFilter.delta(i * 0.5, i * 0.25, 0.0, 0.0, 0.3, s, 7L);
            if (d != ref[i]) fail("order invariance broken at idx " + i + ": " + d + " vs " + ref[i]);
        }
    }

    /**
     * 真尺度不变性：坐标与 scale 同时 ×k、strength 保持 ⇒
     * freq=1/(scale·cellScale) 与 p 的乘积不变（条纹/格点不变），
     * 单 octave 强度 ×k 与内部 gullySlope 的 (strength×freq) 相消（方向状态不变），
     * slope 与掩码不变 ⇒ D(kp,kS) 应等于 k·D(p,S)。
     * 实测 k=2/4/8 相对误差为 0，断言 ≤1e-12（不放宽）。
     */
    private static void checkScaleInvariance() {
        RuneErosionFilter.Settings s = base();
        int nonTrivial = 0;
        for (double k : new double[]{2.0, 4.0, 8.0}) {
            RuneErosionFilter.Settings sk = withScale(SCALE * k);
            for (double[] p : SAMPLES) {
                for (double ft : new double[]{-1.0, -0.3, 0.0, 0.55, 1.0}) {
                    double d0 = RuneErosionFilter.delta(p[0], p[1], p[2], p[3], ft, s, 1337L);
                    double dk = RuneErosionFilter.delta(p[0] * k, p[1] * k, p[2], p[3], ft, sk, 1337L);
                    double target = k * d0;
                    if (!Double.isFinite(dk) || !Double.isFinite(d0))
                        fail("scale invariance non-finite k=" + k + " p=" + p[0] + "," + p[1]);
                    double scaleRef = Math.abs(target);
                    double err = Math.abs(dk - target);
                    double rel = scaleRef > 1e-12 ? err / scaleRef : err;
                    if (rel > 1e-12)
                        fail("D(kp,kS) != k*D(p,S): k=" + k + " p=" + p[0] + "," + p[1]
                                + " rel=" + rel + " dk=" + dk + " k*d=" + target);
                    if (scaleRef > 1e-3) nonTrivial++;
                }
            }
        }
        // 防止测试空洞化：至少一部分样本的幅度足够大，比较才有意义。
        if (nonTrivial < 15) fail("scale invariance samples too trivial: " + nonTrivial);
    }

    /**
     * 远坐标：±30e6/610 的归一化位置及其 ±0.5/610 邻点。
     * 要求全部有限、彼此不全相同（spread 实测 ≈4.8e-3），且邻点与中心有别（实测 1e-5~2e-4）。
     */
    private static void checkFarCoordinates() {
        RuneErosionFilter.Settings s = base();
        double far = 30_000_000.0 / 610.0;
        double nb = 0.5 / 610.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double sx : new double[]{1.0, -1.0}) {
            for (double sz : new double[]{1.0, -1.0}) {
                double cx = sx * far, cz = sz * far;
                double center = RuneErosionFilter.delta(cx, cz, 0.02, -0.013, 0.4, s, 1337L);
                double nx = RuneErosionFilter.delta(cx + nb, cz, 0.02, -0.013, 0.4, s, 1337L);
                double nz = RuneErosionFilter.delta(cx, cz + nb, 0.02, -0.013, 0.4, s, 1337L);
                for (double v : new double[]{center, nx, nz}) {
                    if (!Double.isFinite(v)) fail("far coordinate non-finite at " + cx + "," + cz);
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                if (Math.abs(nx - center) <= 1e-9)
                    fail("far neighbour in x collapsed at " + cx + "," + cz);
                if (Math.abs(nz - center) <= 1e-9)
                    fail("far neighbour in z collapsed at " + cx + "," + cz);
            }
        }
        if (max - min <= 1e-4) fail("far coordinates collapsed to a single value, spread=" + (max - min));
    }

    /**
     * 零坡度合成圆丘（n=1-r²）与盆地（n=r²-1）近顶点：解析梯度 g=∓2ε 随 ε→0 归零，
     * 输出与顶点值的差应随之衰减（实测 O(ε²)），不得跳变。仅为该合成剖面上的局部经验检查。
     */
    private static void checkApexApproach() {
        RuneErosionFilter.Settings s = base();
        double[] eps = {1e-2, 1e-3, 1e-4, 1e-6, 1e-8};
        for (int kind = 0; kind < 2; kind++) {
            double sign = kind == 0 ? -1.0 : 1.0;    // dome: g 向内负；basin: 向外正
            String name = kind == 0 ? "dome" : "basin";
            double d0 = RuneErosionFilter.delta(0.0, 0.0, 0.0, 0.0, 0.8, s, 7L);
            if (!Double.isFinite(d0)) fail(name + " apex non-finite");
            double prevX = Double.POSITIVE_INFINITY;
            double prevZ = Double.POSITIVE_INFINITY;
            double lastX = 0.0, lastZ = 0.0;
            for (double e : eps) {
                double g = sign * 2.0 * e;
                double dx = RuneErosionFilter.delta(e, 0.0, g, 0.0, 0.8, s, 7L);
                double dz = RuneErosionFilter.delta(0.0, e, 0.0, g, 0.8, s, 7L);
                if (!Double.isFinite(dx) || !Double.isFinite(dz)) fail(name + " near-apex non-finite at eps=" + e);
                double diffX = Math.abs(dx - d0);
                double diffZ = Math.abs(dz - d0);
                if (diffX > prevX + 1e-15) fail(name + " x-approach not contracting at eps=" + e + " (" + diffX + " > " + prevX + ")");
                if (diffZ > prevZ + 1e-15) fail(name + " z-approach not contracting at eps=" + e + " (" + diffZ + " > " + prevZ + ")");
                prevX = diffX;
                prevZ = diffZ;
                lastX = diffX;
                lastZ = diffZ;
            }
            // 实测 1e-6 处 5.9e-11：留 100× 余量仍远严于“任意大阈值”。
            if (lastX > 1e-8 || lastZ > 1e-8)
                fail(name + " near-apex difference did not vanish: " + lastX + "," + lastZ);
        }
    }

    /** 相邻 0.01 步长：有限且不跳变；实测 maxJump≈0.010（maxAbs≈0.025），上界取 0.05。 */
    private static void checkNearbyNeighbours() {
        RuneErosionFilter.Settings s = base();
        Random rnd = new Random(999);
        for (int k = 0; k < 256; k++) {
            double x = rnd.nextDouble() * 40.0, z = rnd.nextDouble() * 40.0;
            double gx = rnd.nextDouble() * 0.1, gz = rnd.nextDouble() * 0.1;
            double d0 = RuneErosionFilter.delta(x, z, gx, gz, 0.3, s, 31337L);
            double d1 = RuneErosionFilter.delta(x + 0.01, z, gx, gz, 0.3, s, 31337L);
            double d2 = RuneErosionFilter.delta(x, z + 0.01, gx, gz, 0.3, s, 31337L);
            if (!Double.isFinite(d0) || !Double.isFinite(d1) || !Double.isFinite(d2))
                fail("neighbour non-finite at " + x + "," + z);
            if (Math.abs(d1 - d0) > 0.05 || Math.abs(d2 - d0) > 0.05)
                fail("neighbour jump above 0.05 at " + x + "," + z);
        }
    }

    private static void fail(String msg) {
        System.err.println("RUNE-EROSION-CHECK FAIL: " + msg);
        System.exit(1);
    }

    private static final double[][] SAMPLES = {
            {0.0, 0.0, 0.0, 0.0, 0.0},
            {13.7, -5.3, 0.03, -0.01, 0.5},
            {-88.25, 42.1, -0.06, 0.02, -0.8},
            {1024.5, 2048.25, 0.08, 0.0, 0.25},
            {3.5, 3.5, 0.0, 0.05, 1.0},
            {0.37, -1.11, 0.012, 0.007, 0.9},
    };
}
