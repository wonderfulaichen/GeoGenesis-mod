package com.geogenesis.worldgen.terrain.candidate;

/**
 * 参考原版逐行复刻：FreeTerraForged 1.21.1 {@code cell/terrain/Populators.java:75-113}
 * 的 {@code makeSteppe} 与 {@code makePlains}（都属于参考的 {@code FLATS} 族）。
 *
 * <pre>
 * makePlains(scaleH = 250)                              makeSteppe(scaleH = 250)
 *   erosion = perlin(next, 500, 3, lac 3.75); α=0.45     同左
 *   warpX/Z = perlin(next, 62, 3, lac 3.5)               warpX/Z = perlin(next, 62, 3, lac 3.0)
 *   height  = perlin(next, 250, 1)                       height  = perlin(next, 250, 1)
 *   height ×= erosion                                    height ×= erosion
 *   height  = warp(height, warpX, warpZ, 62.5)           同左
 *   height  = warpPerlin(next, 256, 1, 256)              warpPerlin(next, 256, 1, 200)
 *   height ×= 0.15 × verticalScale                       height ×= 0.08
 *   height += −0.02                                      height += −0.02
 * </pre>
 *
 * <h3>与当前 PLAIN 的差别（为什么要换）</h3>
 * 当前 {@code TypeNoiseProvider} 的 PLAIN 是"单频 Simplex 1/800 + 60 格 warp，映射到 [0.42,0.58]"，
 * 实测起伏只有 <b>3.2 格</b>，等于一块几乎绝对的平板。参考的 plains 由
 * <b>慢变侵蚀场调制振幅 + 双尺度域扭曲 + 二次大尺度 warp</b> 构成，起伏约 <b>20–30 格</b>，
 * 且低洼处被侵蚀场压平、高处成缓丘 —— 这才是"平原上仍有地貌"。
 *
 * <p>流式约束：全部节点是 (x,z) 纯函数，无整图统计、无跨块状态。</p>
 */
public final class PlainsCandidate {

    /** 参考的 {@code globalHorizontalScale} 默认 1.0，故 scaleH 直接按 250 算。 */
    private static final int SCALE_H = 250;

    /** 参考高度 → e：1.0 参考单位 ≈ 256 格 ≈ 1.08 e。 */
    public static final double E_PER_REF_UNIT = 1.08;

    /** 三个变体：两个参考原版 + 一个基于复刻的地理修正版。 */
    public enum Variant {
        /** {@code makePlains}：起伏 0.15，末道 warp 幅度 256。 */
        PLAINS("plains", "参考 plains（起伏 0.15 / 末道 warp 256）", 3, 256f),
        /** {@code makeSteppe}：起伏 0.08，末道 warp 200，warp 噪声 lacunarity 3.0。 */
        STEPPE("steppe", "参考 steppe（起伏 0.08 / 末道 warp 200）", 3, 200f),
        /**
         * 地理修正版：结构照参考，但两处位移都收小。
         *
         * <p><b>依据 1</b>：参考的 warp 噪声 {@code perlin(62, 3, lac 3.5)} 第三倍频波长仅
         * {@code 62/3.5² ≈ 5.06} 格，仍贡献约 17% 位移 ⇒ 收到 1 倍频。</p>
         *
         * <p><b>依据 2（主要）</b>：末道 {@code warpPerlin(h, 256, 1, 256)} 的位移是
         * {@code ±256/2 = ±128} 格，而主体 {@code perlin(250,1)} 波长仅 250 格 ——
         * <b>位移达特征尺度的一半</b>，等于把高度场整体揉搓，等高线被拉成长条
         * （即实测"毛状细纹"）。地理上平原的弯曲位移应远小于其特征波长，故收到 64 格。</p>
         */
        PLAINS_GEO("plains-geo", "地理修正 plains（warp 收 1 倍频 + 末道 64）", 1, 64f);

        private final String id;
        private final String label;
        private final int warpOctaves;
        private final float finalWarpPow;

        Variant(String id, String label, int warpOctaves, float finalWarpPow) {
            this.id = id;
            this.label = label;
            this.warpOctaves = warpOctaves;
            this.finalWarpPow = finalWarpPow;
        }

        public String id() { return id; }

        public String label() { return label; }
    }

    private final Variant variant;
    private RefNoise.Node height;

    public PlainsCandidate(Variant variant) {
        this.variant = variant;
    }

    public String id() { return variant.id(); }

    public String label() { return variant.label(); }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;

        boolean steppe = variant == Variant.STEPPE;

        // erosion = alpha(perlin(scaleH*2, 3, lac 3.75), 0.45)
        RefNoise.Node erosion = RefNoise.Perlin.ofScale(next.getAsInt(), SCALE_H * 2f, 3, 3.75f);
        erosion = new RefNoise.AlphaMix(erosion, 0.45f);

        // warpX/Z：plains 用 lac 3.5，steppe 用 lac 3.0（参考两处不同）。
        // ⚠ 参考写的是 scaleH / 4（int 除法 = 62），不是 62.5。
        float warpLac = steppe ? 3.0f : 3.5f;
        float warpScale = SCALE_H / 4;
        int warpOct = variant.warpOctaves;
        RefNoise.Node warpX = RefNoise.Perlin.ofScale(next.getAsInt(), warpScale, warpOct, warpLac);
        RefNoise.Node warpZ = RefNoise.Perlin.ofScale(next.getAsInt(), warpScale, warpOct, warpLac);

        // height = perlin(scaleH, 1) × erosion
        RefNoise.Node h = RefNoise.Perlin.ofScale(next.getAsInt(), SCALE_H, 1);
        h = new RefNoise.Mul(h, erosion);

        // warp(height, warpX, warpZ, scaleH/4.0) —— 位移 ±31.25 格
        h = RefNoise.warp(h, warpX, warpZ, SCALE_H / 4f);

        // 末道 warpPerlin：plains 256/256，steppe 256/200，地理修正版 256/64
        h = RefNoise.warpPerlin(h, next.getAsInt(), 256f, 1, variant.finalWarpPow);

        // 振幅与基线：plains 0.15×verticalScale(=1)，steppe 0.08；都 −0.02
        h = new RefNoise.Mul(h, new RefNoise.Constant(steppe ? 0.08f : 0.15f));
        h = new RefNoise.Add(h, new RefNoise.Constant(-0.02f));

        this.height = h;
    }

    /** 参考归一化高度（约 −0.02 … 0.13）。 */
    public double height01(double wx, double wz) {
        if (height == null) throw new IllegalStateException("seed() not called");
        return height.compute((float) wx, (float) wz);
    }

    public double e(double wx, double wz) { return height01(wx, wz) * E_PER_REF_UNIT; }
}