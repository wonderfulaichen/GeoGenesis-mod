package com.geogenesis.worldgen.hydrology.riverline;

import com.geogenesis.worldgen.noise.Frequency;
import com.geogenesis.worldgen.noise.Noise;
import com.geogenesis.worldgen.noise.Noises;
import com.geogenesis.worldgen.noise.Simplex;

/**
 * 每河<b>域扭曲</b>（domain warp）—— 复刻 FreeTerraForged 的
 * {@code rivermap/river/RiverWarp.java:37-53}。
 *
 * <h2>为什么需要它（用户实机判据）</h2>
 * <p>用户报"河网太直 / 太规则"，并给出坐标 块(-772,515)。实测
 * （{@code runRiverWidthProfileProbe} 定点模式）该河的<b>每一段都是精确 45°</b>
 * —— 它是一条完美的斜直线。而本项目的动量场救不了它：动量按<b>上游累积量</b>加权，
 * 一级河累积量极小 ⇒ 方向退化为纯 D8 最陡下降 ⇒ 精确 45°。</p>
 *
 * <h2>参考怎么做的</h2>
 * <p>FTF 的河线几何本身就是<b>直线段</b>（{@code River.java:35-57} 只有两端点），
 * 自然观感<b>全部来自域扭曲</b>，且它对<b>所有</b>河流一视同仁（不看汇流量）。
 * 其 {@code getOffset} 的关键是：<b>用 simplex 噪声</b>沿河法向位移，
 * 并让<b>正弦的相位也被噪声调制</b>（{@code rads = noise + t·2π·wiggleFreq}）
 * ⇒ 不是等距正弦 ⇒ 不规则。</p>
 *
 * <h2>⚠ 为什么不能直接照搬 FTF 的参数</h2>
 * <p>FTF 的 {@code scale = 125~174 wu}、{@code frequency = 5e-4}（波长 ≈2000wu）
 * 是按<b>大陆尺度</b>河流标定的；而本项目实测河流总长常仅 <b>34wu</b>
 * （块(-772,515) 那条 11 节点河）。直接照搬 ⇒ 噪声沿整条河近乎常数 ⇒ <b>完全无效果</b>。
 * <b>⇒ 复刻机制，参数按本项目尺度标定</b>（波长取既有 {@code meanderWavelength}）。</p>
 *
 * <h2>实现（刻意使用项目既有噪声组件，与 {@code TypeNoiseProvider} / {@code LandFeatures} 同惯用法）</h2>
 * <pre>
 *   offset(x,z) ∝ n₁(x,z) + 0.5·n₂(x,z)       n₁ 波长 = λ，n₂ 波长 = λ/2
 * </pre>
 * <p>双频叠加 ⇒ 单一频率的规则周期被打散。返回 {@code [-1,1]}，
 * 由调用方乘振幅（{@code meanderAmp}）。</p>
 *
 * <h2>确定性</h2>
 * <p>噪声由 {@code (worldSeed, salt)} 确定性播种（{@link Noises#seedAll}），
 * {@code salt} 由该河自身几何导出 ⇒ 同 seed 同结果、与 chunk 访问顺序无关。</p>
 */
public final class RiverWarp {

    /** 次频（1/半波长）振幅占主频的比例。 */
    private static final double SUB_SHARE = 0.5;
    /** 归一化因子：使 (main + SUB_SHARE·sub) 落在 [-1,1]。 */
    private static final double NORM = 1.0 / (1.0 + SUB_SHARE);

    private final Noise main;
    private final Noise sub;

    /**
     * @param worldSeed  世界种子（确定性）
     * @param salt       每河盐（由该河几何导出，保证不同河不同相位）
     * @param wavelength 主频波长（wu）；建议取 {@code RiverLineParams.meanderWavelength()}
     */
    public RiverWarp(long worldSeed, long salt, double wavelength) {
        double f = 1.0 / Math.max(1e-6, wavelength);
        this.main = new Frequency(new Simplex(0), f);
        this.sub = new Frequency(new Simplex(0), f * 2.0);
        // Noises.seedAll 的第三参是 int（level/盐）；把 long 盐折叠成 int（保留高低位信息）
        int lv = (int) (salt ^ (salt >>> 32));
        Noises.seedAll(this.main, worldSeed, lv);
        Noises.seedAll(this.sub, worldSeed, lv + 0x9E3779B9);   // 次频换相位，避免与主频同相
    }

    /**
     * 单位法向位移 ∈ {@code [-1,1]}（调用方乘振幅）。
     *
     * @param x 世界 wu X
     * @param z 世界 wu Z
     */
    public double unitOffset(double x, double z) {
        return (this.main.compute(x, z) + SUB_SHARE * this.sub.compute(x, z)) * NORM;
    }
}
