package com.geogenesis.worldgen.terrain.candidate;

/**
 * 参考 FreeTerraForged {@code Steps} 的<b>正确</b>算法复刻（候选专用，暂不替换生产算子）。
 *
 * <h3>为什么必须重写，而不是用本项目的 {@code Steps}</h3>
 * 本项目 {@code com.geogenesis.worldgen.noise.Steps} 的实现是
 * {@code Math.round(f * steps) / steps} —— 在台阶边界处<b>直接跳变</b>（C⁻¹ 不连续，
 * 梯度无穷大）。这才是历史上「密集平行细线 / 同心环梯田」伪影的真正来源。
 *
 * <p>参考的 {@code Steps.compute}（FreeTerraForged 1.21.1，{@code noise/module/Steps.java:20-34}）是：</p>
 * <pre>
 *   noiseValue = 1 - input;
 *   value      = floor(noiseValue * stepCount) / stepCount;   // 台阶底
 *   delta      = noiseValue - value;
 *   alpha      = map(delta * stepCount, slopeMin, slopeMax, slopeMax - slopeMin);
 *   result     = 1 - lerp(value, noiseValue, slopeCurve(alpha));
 * </pre>
 *
 * <p>关键在 {@code map(...)}：{@code NoiseUtil.map(value, min, max, range)} 先
 * <b>clamp 到 [min,max] 再除以 range</b>。以 {@code slopeMin=0.6, slopeMax=0.7} 为例：</p>
 * <ul>
 *   <li>{@code delta*stepCount < 0.6} ⇒ {@code alpha = 0} ⇒ 完全等于台阶底 ⇒ <b>每个台阶 60% 区间是平的</b></li>
 *   <li>{@code 0.6~0.7} ⇒ {@code alpha} 从 0 线性升到 1 ⇒ <b>仅 10% 区间是过渡斜坡</b></li>
 *   <li>{@code > 0.7} ⇒ {@code alpha = 1} ⇒ 短暂回到原曲面</li>
 * </ul>
 *
 * <p>⇒ 参考的「层理」是 <b>平顶 + 短而连续的斜坡</b>，曲面处处连续，不存在跳跃。
 * 这与本项目被否决的 {@code Math.round} 硬台阶在数学性质上完全不同。本项目当初
 * 把「量化本身」列为禁令，是把算子实现缺陷误判成方法禁区。</p>
 *
 * <p>坐标系与 {@code Noise} 一致：输入输出都在 [-1,1]（内部先映射到 [0,1] 处理）。</p>
 */
public final class SoftSteps {

    private final double slopeMin;
    private final double slopeMax;

    /**
     * @param slopeMin 斜坡起点（占一个台阶宽度的比例，0~1）。越小斜坡越长。
     * @param slopeMax 斜坡终点（占一个台阶宽度的比例，0~1）。
     */
    public SoftSteps(double slopeMin, double slopeMax) {
        if (!(slopeMax > slopeMin)) {
            throw new IllegalArgumentException("slopeMax must exceed slopeMin");
        }
        this.slopeMin = slopeMin;
        this.slopeMax = slopeMax;
    }

    /**
     * 参考算法的直接复刻。
     *
     * @param input     原始噪声（[-1,1] 或任意，内部按 [0,1] 处理）
     * @param stepCount 台阶级数
     * @return 带平顶与连续斜坡的层理值 ∈ [0,1]
     */
    public double apply01(double input, int stepCount) {
        int n = Math.max(1, stepCount);
        double noise = 1.0 - clamp01(input);
        double value = Math.floor(noise * n) / n;
        double delta = noise - value;
        double alpha = mapClamped(delta * n, slopeMin, slopeMax);
        return 1.0 - lerp(value, noise, alpha);
    }

    /**
     * 参考 {@code NoiseUtil.map(value, min, max, range)}：先 clamp 再除以 range。
     *
     * <p>⚠ 这个「先 clamp」是整套层理的关键 —— 它让 {@code alpha} 在两端各有一大段
     * <b>恒定</b>区间，从而产生平顶。若换成不 clamp 的线性映射，就退化成纯斜坡、没有层理。</p>
     */
    private double mapClamped(double value, double min, double max) {
        double range = max - min;
        double dif = clamp(value, min, max) - min;
        return dif >= range ? 1.0 : dif / range;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }
}
