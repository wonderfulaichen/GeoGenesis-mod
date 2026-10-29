package com.geogenesis.worldgen.terrain.candidate;

/**
 * 参考原版逐行复刻 + 地理修正版：FreeTerraForged 1.21.1
 * {@code cell/terrain/Populators.java:294-399} 的三种山地配方。
 *
 * <pre>
 * m1: perlinRidge(610,4,lac2.35,gain1.15) × alpha(perlin(S,4),0.075)
 *     → warpPerlin(350,1,150) → fancy → ×0.7
 * m2: worleyEdge(C) ×1.2 → clamp → warpPerlin(200,2,100)
 *     × alpha(perlin(10,1),0.025) × alpha(perlinRidge(125,4),A) → pow 1.1 → fancy → ×0.645
 * m3: 同 m2 但 C=600，另套 AdvancedTerrace(steps=T)，×0.645
 * </pre>
 *
 * <h3>三个地理修正项（每个只改一处，便于单变量对比）</h3>
 * <ul>
 *   <li><b>m1</b>：振幅调制波长 24→180 格。24 格对 610 格的山系而言是"每 24 格换一次山高"，
 *       地理上岩性/抬升的变化尺度是数百格。</li>
 *   <li><b>m2</b>：细胞尺度 360→520 格、表面脊线权重 0.37→0.50。参考的 360 格细胞会切成
 *       一个个孤立"山块"；放宽细胞并加强脊线，读作连续山系而非方块阵列。</li>
 *   <li><b>m3</b>：阶地级数 24→6。24 级在 600 格山体上每级仅 25 格，接近细密台阶；
 *       真实台地/阶地山是少数几级宽平台。</li>
 * </ul>
 */
public final class MountainsCandidate {

    /** 参考高度 → e：1.0 参考单位 ≈ 256 格 ≈ 1.08 e。 */
    public static final double E_PER_REF_UNIT = 1.08;

    public enum Variant {
        M1("m1", "参考 m1（振幅调制 24 格, 幅度 0.075）", 1, 24f, 0f, 0.37f, 24, 0.075f),
        /**
         * 地理修正 m1：调制波长 24→180 格，并把<b>调制幅度</b> 0.075→0.30。
         * <p>只改波长时实测几乎无差别（起伏 127.6→127.0）—— 因为 {@code alpha(x,0.075)}
         * 只让山脊振幅在 [0.925,1.0] 间变化，7.5% 的深度看不出峰与鞍部。
         * 提到 0.30 后振幅在 [0.70,1.0] 间变化，才会出现真正的峰段与鞍部。</p>
         */
        M1_GEO("m1-geo", "地理修正 m1（调制 180 格, 幅度 0.30）", 1, 180f, 0f, 0.37f, 24, 0.30f),
        M2("m2", "参考 m2（细胞 360 格）", 2, 0f, 360f, 0.37f, 24, 0.075f),
        M2_GEO("m2-geo", "地理修正 m2（细胞 520 格, 脊线 0.50）", 2, 0f, 520f, 0.50f, 24, 0.075f),
        M3("m3", "参考 m3（600 格 + 24 级阶地）", 3, 0f, 600f, 0.37f, 24, 0.075f),
        M3_GEO("m3-geo", "地理修正 m3（600 格 + 6 级阶地）", 3, 0f, 600f, 0.37f, 6, 0.075f);

        private final String id;
        private final String label;
        private final int kind;
        private final float scalerScale;   // m1：振幅调制波长
        private final float cellScale;     // m2/m3：细胞尺度
        private final float surfaceAlpha;  // m2/m3：表面脊线权重
        private final int terraceSteps;    // m3：阶地级数
        private final float scalerAlpha;   // m1：振幅调制深度

        Variant(String id, String label, int kind, float scalerScale, float cellScale,
                float surfaceAlpha, int terraceSteps, float scalerAlpha) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.scalerScale = scalerScale;
            this.cellScale = cellScale;
            this.surfaceAlpha = surfaceAlpha;
            this.terraceSteps = terraceSteps;
            this.scalerAlpha = scalerAlpha;
        }

        public String id() { return id; }

        public String label() { return label; }
    }

    private final Variant variant;
    private RefNoise.Node height;

    public MountainsCandidate(Variant variant) {
        this.variant = variant;
    }

    public String id() { return variant.id(); }

    public String label() { return variant.label(); }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;
        this.height = switch (variant.kind) {
            case 1 -> m1(next, variant.scalerScale, variant.scalerAlpha);
            case 2 -> m2(next, variant.cellScale, variant.surfaceAlpha, 0, false);
            default -> m2(next, variant.cellScale, variant.surfaceAlpha, variant.terraceSteps, true);
        };
    }

    /** mountains_1：多频脊线 × 振幅调制 → 大位移 warp → fancy → ×0.7（参考 legacy 值）。 */
    private static RefNoise.Node m1(java.util.function.IntSupplier next, float scalerScale, float scalerAlpha) {
        RefNoise.Node h = new RefNoise.PerlinRidge(next.getAsInt(), 1f / 610f, 4, 2.35f, 1.15f);
        RefNoise.Node scaler = new RefNoise.AlphaMix(
                RefNoise.Perlin.ofScale(next.getAsInt(), scalerScale, 4), scalerAlpha);
        h = new RefNoise.Mul(h, scaler);
        h = RefNoise.warpPerlin(h, next.getAsInt(), 350f, 1, 150f);
        h = fancy(h, next);
        return new RefNoise.Mul(h, new RefNoise.Constant(0.7f));
    }

    /**
     * mountains_2 / mountains_3 共用主体：细胞边缘 × 模糊 × 表面脊线。
     *
     * @param cellScale    细胞尺度（参考 360 / 600）
     * @param surfaceAlpha 表面脊线权重（参考 0.37）
     * @param terraceSteps 阶地级数（0 = 不做阶地；参考 24）
     */
    private static RefNoise.Node m2(java.util.function.IntSupplier next, float cellScale,
                                    float surfaceAlpha, int terraceSteps, boolean terrace) {
        RefNoise.Node cell = RefNoise.WorleyEdge.ofScale(next.getAsInt(), cellScale);
        cell = new RefNoise.Mul(cell, new RefNoise.Constant(1.2f));
        cell = new RefNoise.Clamped(cell, 0f, 1f);
        cell = RefNoise.warpPerlin(cell, next.getAsInt(), 200f, 2, 100f);

        RefNoise.Node blur = new RefNoise.AlphaMix(RefNoise.Perlin.ofScale(next.getAsInt(), 10f, 1), 0.025f);
        RefNoise.Node surface = new RefNoise.AlphaMix(
                RefNoise.PerlinRidge.ofScale(next.getAsInt(), 125f, 4), surfaceAlpha);

        RefNoise.Node h = new RefNoise.Clamped(cell, 0f, 1f);
        h = new RefNoise.Mul(h, blur);
        h = new RefNoise.Mul(h, surface);
        h = new RefNoise.Power(h, 1.1f);

        if (terrace) {
            RefNoise.Node modulation = new RefNoise.Mul(
                    RefNoise.Perlin.ofScale(next.getAsInt(), 50f, 1), new RefNoise.Constant(0.5f));
            RefNoise.Node mask = new RefNoise.Clamped(RefNoise.Perlin.ofScale(next.getAsInt(), 100f, 1),
                    0.5f, 0.95f);
            mask = new RefNoise.Mapped(mask, 0.5f, 0.95f, 0f, 1f);
            RefNoise.Node slope = new RefNoise.Constant(0.45f);
            h = new RefNoise.AdvancedTerrace(h, modulation, mask, slope, 0.2f, 0.45f, terraceSteps, 1, 1f, 0.5f);
        }
        h = fancy(h, next);
        return new RefNoise.Mul(h, new RefNoise.Constant(0.645f));
    }

    /** {@code Populators.makeFancy:401-409}：方向扰动 + 侵蚀纹理。 */
    private static RefNoise.Node fancy(RefNoise.Node input, java.util.function.IntSupplier next) {
        RefNoise.Node direction = RefNoise.Perlin.ofScale(next.getAsInt(), 10f, 1);
        int erosionSeed = next.getAsInt();
        RefNoise.Node erosion = new RefNoise.Erosion(input, erosionSeed, 2, 0.65f, 128f, 0.15f, 3.1f, 0.8f);
        return new RefNoise.DirectionWarped(erosion, direction, 2.0f);
    }

    /** 参考归一化高度。 */
    public double height01(double wx, double wz) {
        if (height == null) throw new IllegalStateException("seed() not called");
        return height.compute((float) wx, (float) wz);
    }

    public double e(double wx, double wz) { return height01(wx, wz) * E_PER_REF_UNIT; }
}