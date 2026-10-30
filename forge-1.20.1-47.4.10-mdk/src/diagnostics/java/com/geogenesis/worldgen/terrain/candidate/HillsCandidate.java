package com.geogenesis.worldgen.terrain.candidate;

/**
 * 参考原版逐行复刻：FreeTerraForged 1.21.1 {@code cell/terrain/Populators.java:157-214}
 * 的三种丘陵族配方（都属于参考的 {@code HILLS} 类型）。
 *
 * <pre>
 * hills_1 (157-170)                          hills_2 (172-190)
 *   height = perlin(200, 3)                    height = cubic(128, 2)
 *   scaler = billow(400, 3); α=0.5             scaler1 = perlin(32, 4); α=0.075
 *   height ×= scaler                           height ×= scaler1
 *   warpPerlin(30, 3, 20)                      warpPerlin(30, 3, 20)
 *   warpPerlin(400, 3, 200)                    warpPerlin(400, 3, 200)
 *   height ×= 0.6×verticalScale                scaler2 = perlinRidge(512, 2); α=0.8
 *                                              height ×= scaler2
 *                                              height ×= 0.55×verticalScale
 *
 * dales (192-214)
 *   hills1 = billow(300, 4, lac4.0, gain0.8) → powCurve(0.5) → ×0.75
 *   hills2 = billow(350, 3, lac4.0, gain0.8) → pow(1.25)
 *   selector = perlin(400, 1) → clamp(0.3,0.6) → map(0,1)
 *   height = blend(selector, hills1, hills2, mid 0.4, range 0.75) → pow(1.125)
 *   height = warpPerlin(height, 300, 1, 100) → ×0.4
 * </pre>
 *
 * <h3>为什么值得替换现有 HILLS</h3>
 * 当前 {@code TypeNoiseProvider} 的 HILLS 是"两频 Simplex 相加 → 映射 [0,1]"，只有
 * 频率与振幅，没有<b>形态</b>：既无丘体的圆顶/谷地结构，也无丘陵尺度的变化。
 * 参考的三者分别解决不同问题：hills_1 用 billow 包络做<b>圆缓丘群</b>；hills_2 用 cubic 做
 * <b>更成块的丘体</b>并叠 ridged 调制；dales 用两套 billow 由慢变 selector 选择，
 * 得到<b>开阔谷地 + 宽缓丘坡</b>两种质地交替。
 *
 * <p>流式约束：全部节点是 (x,z) 纯函数，无整图统计、无跨块状态。</p>
 */
public final class HillsCandidate {

    /** 参考高度 → e：1.0 参考单位 ≈ 256 格 ≈ 1.08 e。 */
    public static final double E_PER_REF_UNIT = 1.08;

    public enum Variant {
        HILLS_1("hills1", "参考 hills_1（perlin×billow 包络）", 400f),
        /** 地理修正：丘群包络 400→650 格。400 格包络下每群只有数座丘，读作"散点丘"；
         *  放宽后丘群连片、群间有宽缓谷，更接近真实丘陵区。 */
        HILLS_1_GEO("hills1-geo", "地理修正 hills_1（包络 650 格）", 650f),
        HILLS_2("hills2", "参考 hills_2（cubic×ridged 调制）", 400f, 4f),
        DALES("dales", "参考 dales（双 billow + selector 混合）", 400f, 4f),
        /**
         * 地理修正 dales：两套 billow 的 lacunarity 4.0→2.5。
         * <p>参考 4 级 + lac 4.0 时最高倍频波长为 {@code 300/64 ≈ 4.7} 格 —— 那是块级抖动，
         * 会把谷底与丘坡打碎成噪点。收到 2.5 后最高倍频约 {@code 300/2.5³ ≈ 19} 格，
         * 谷底变宽、坡面变缓，符合真实谷地"宽底缓坡"的形态。</p>
         */
        DALES_GEO("dales-geo", "地理修正 dales（billow lac 4.0→2.5）", 400f, 2.5f),
        /**
         * ★ 去高频 dales（用户 2026-10-30："把这个 dales 的高频去了看看，我就是嫌这个"）。
         *
         * <p>{@code DALES_GEO} 的碎纹来自两套 billow 的<b>第 3、4 倍频</b>：
         * {@code 300/2.5² ≈ 48} 格、{@code 300/2.5³ ≈ 19} 格 —— 红圈圈住的正是这些
         * 波纹状细纹。本变体把两套 billow 的倍频压到 <b>2</b>，最高波长变为
         * {@code 300/2.5 = 120} 格、{@code 350/2.5 = 140} 格，碎纹消失，
         * 而 selector / warp / 混合结构与 dales 完全一致（单变量 A/B）。</p>
         */
        DALES_LO("dales-lo", "去高频 dales（billow 倍频 4/3 → 2，最高波长 120 格）", 400f, 2.5f, 2, 2);

        private final String id;
        private final String label;
        private final float envelopeScale;
        private final float dalesLacunarity;
        /** 两套 billow 的倍频数（DALES_LO = 2/2 去高频，其余 = 参考 4/3）。 */
        private final int billowOct1;
        private final int billowOct2;

        Variant(String id, String label, float envelopeScale) {
            this(id, label, envelopeScale, 4f, 4, 3);
        }

        Variant(String id, String label, float envelopeScale, float dalesLacunarity) {
            this(id, label, envelopeScale, dalesLacunarity, 4, 3);
        }

        Variant(String id, String label, float envelopeScale, float dalesLacunarity,
                int billowOct1, int billowOct2) {
            this.id = id;
            this.label = label;
            this.envelopeScale = envelopeScale;
            this.dalesLacunarity = dalesLacunarity;
            this.billowOct1 = billowOct1;
            this.billowOct2 = billowOct2;
        }

        public String id() { return id; }

        public String label() { return label; }
    }

    private final Variant variant;
    private RefNoise.Node height;

    public HillsCandidate(Variant variant) {
        this.variant = variant;
    }

    public String id() { return variant.id(); }

    public String label() { return variant.label(); }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;
        this.height = build(variant, next);
    }

    /**
     * 构建某个丘陵配方的噪声图（供本类与 {@link HillsRegionCandidate} 共用）。
     * <p>每次调用会从 {@code next} 取若干连续种子，调用顺序必须与参考一致。</p>
     */
    static RefNoise.Node build(Variant variant, java.util.function.IntSupplier next) {
        switch (variant) {
            case HILLS_1, HILLS_1_GEO -> {
                RefNoise.Node h = RefNoise.Perlin.ofScale(next.getAsInt(), 200f, 3);
                RefNoise.Node scaler = new RefNoise.AlphaMix(
                        RefNoise.Billow.ofScale(next.getAsInt(), variant.envelopeScale, 3, 2f, 0.5f), 0.5f);
                h = new RefNoise.Mul(h, scaler);
                h = RefNoise.warpPerlin(h, next.getAsInt(), 30f, 3, 20f);
                h = RefNoise.warpPerlin(h, next.getAsInt(), 400f, 3, 200f);
                return new RefNoise.Mul(h, new RefNoise.Constant(0.6f));
            }
            case HILLS_2 -> {
                RefNoise.Node h = RefNoise.Cubic.ofScale(next.getAsInt(), 128f, 2, 2f, 0.5f);
                RefNoise.Node scaler1 = new RefNoise.AlphaMix(
                        RefNoise.Perlin.ofScale(next.getAsInt(), 32f, 4), 0.075f);
                h = new RefNoise.Mul(h, scaler1);
                h = RefNoise.warpPerlin(h, next.getAsInt(), 30f, 3, 20f);
                h = RefNoise.warpPerlin(h, next.getAsInt(), 400f, 3, 200f);
                RefNoise.Node scaler2 = new RefNoise.AlphaMix(
                        RefNoise.PerlinRidge.ofScale(next.getAsInt(), 512f, 2), 0.8f);
                h = new RefNoise.Mul(h, scaler2);
                return new RefNoise.Mul(h, new RefNoise.Constant(0.55f));
            }
            case DALES, DALES_GEO, DALES_LO -> {
                return dales(next, variant.dalesLacunarity, variant.billowOct1, variant.billowOct2);
            }
            default -> throw new IllegalStateException("unknown variant " + variant);
        }
    }

    /**
     * dales：两套 billow 由慢变 selector 混合，得到"谷地/丘坡"两种质地。
     *
     * @param oct1 hills1 billow 倍频（参考 4；DALES_LO 用 2 去掉碎纹）
     * @param oct2 hills2 billow 倍频（参考 3；DALES_LO 用 2）
     */
    private static RefNoise.Node dales(java.util.function.IntSupplier next, float lacunarity,
                                       int oct1, int oct2) {
        RefNoise.Node hills1 = RefNoise.Billow.ofScale(next.getAsInt(), 300f, oct1, lacunarity, 0.8f);
        hills1 = RefNoise.PowerCurve.of(hills1, 0.5f, 0f, 1f);
        hills1 = new RefNoise.Mul(hills1, new RefNoise.Constant(0.75f));

        RefNoise.Node hills2 = RefNoise.Billow.ofScale(next.getAsInt(), 350f, oct2, lacunarity, 0.8f);
        hills2 = new RefNoise.Power(hills2, 1.25f);

        RefNoise.Node selector = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        selector = new RefNoise.Clamped(selector, 0.3f, 0.6f);
        selector = new RefNoise.Mapped(selector, 0.3f, 0.6f, 0f, 1f);

        int warpSeed = next.getAsInt();
        RefNoise.Node blended = new RefNoise.Blend(selector, hills1, hills2, 0.4f, 0.75f, 0f, 1f);
        RefNoise.Node h = new RefNoise.Power(blended, 1.125f);
        h = RefNoise.warpPerlin(h, warpSeed, 300f, 1, 100f);
        return new RefNoise.Mul(h, new RefNoise.Constant(0.4f));
    }

    /** 参考归一化高度。 */
    public double height01(double wx, double wz) {
        if (height == null) throw new IllegalStateException("seed() not called");
        return height.compute((float) wx, (float) wz);
    }

    public double e(double wx, double wz) { return height01(wx, wz) * E_PER_REF_UNIT; }
}