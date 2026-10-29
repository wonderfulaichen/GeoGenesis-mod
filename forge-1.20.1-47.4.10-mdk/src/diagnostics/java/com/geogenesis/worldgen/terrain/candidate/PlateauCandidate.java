package com.geogenesis.worldgen.terrain.candidate;

/**
 * 参考原版逐行复刻：FreeTerraForged 1.21.1 {@code cell/terrain/Populators.java:119-155}
 * 的 {@code makePlateau}，外加一个<b>地理修正版</b>。
 *
 * <pre>
 *   valley  = perlinRidge(next, 500, 1) → invert
 *           → warpPerlin(100, 1, 150) → warpPerlin(20, 1, 15)
 *   top     = perlinRidge(next, 150, 3, lac2.45) → warpPerlin(300,1,150) → warpPerlin(40,2,20) × 0.15
 *   scaler  = map(clamp(valley, 0.02, 0.1), 0→1)      // 谷地越深，台面细节越强
 *   top    ×= scaler
 *   surface = perlin(next, 20, 3) × 0.05 → warpPerlin(40, 2, 20)
 *   cubic   = cubic(next, 500, 1) × 0.6 + 0.3
 *   base    = valley × cubic + top
 *   height  = terrace(base, ramp 0.9, cliff 0.15, rampHeight 0.35, blend 0.4, 4 级)
 *   height += surface → × 0.475
 * </pre>
 *
 * <h3>两个变体的唯一差别</h3>
 * {@code REF} 用参考原码的台阶索引 {@code floor(v × n)}；{@code GEO} 改用
 * {@code floor((v − inMin) / spacing)}，即让<b>索引刻度与台阶值刻度一致</b>。
 * 依据：参考里台阶值取 {@code index × spacing}（{@code spacing = range/(n−1)}），
 * 而索引却按 {@code v × n} 分档，两者刻度不同 ⇒ 一半以上的区间被丢给
 * "越界后直接取下一级"，形成密集窄坎而不是层层台地。修正版把两者对齐。
 */
public final class PlateauCandidate {

    /** 参考高度 → e：1.0 参考单位 ≈ 256 格 ≈ 1.08 e。 */
    public static final double E_PER_REF_UNIT = 1.08;

    public enum Variant {
        REF("PLATEAU-ref", "参考 makePlateau（谷为线状）", 1.0f),
        GEO("PLATEAU-geo", "地理修正（valley^1.1，谷略展宽）", 1.1f);

        private final String id;
        private final String label;
        /** {@code valley} 的幂：1.0 = 不改（等于参考），>1 = 把线状谷压宽。 */
        private final float valleyPower;

        Variant(String id, String label, float valleyPower) {
            this.id = id;
            this.label = label;
            this.valleyPower = valleyPower;
        }

        public String id() { return id; }

        public String label() { return label; }
    }

    private final Variant variant;
    private RefNoise.Node height;
    private RefNoise.Node dbgValley, dbgBase, dbgTerrace;

    public PlateauCandidate(Variant variant) {
        this.variant = variant;
    }

    public String id() { return variant.id(); }

    public String label() { return variant.label(); }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;

        RefNoise.Node valley = RefNoise.PerlinRidge.ofScale(next.getAsInt(), 500f, 1);
        valley = new RefNoise.Inverted(valley, 0f, 1f);
        valley = RefNoise.warpPerlin(valley, next.getAsInt(), 100f, 1, 150f);
        valley = RefNoise.warpPerlin(valley, next.getAsInt(), 20f, 1, 15f);

        if (variant.valleyPower != 1.0f) {
            // 地理修正：把"线状深谷"略拓成有宽度的谷。
            // 取幂 p>1 把中低值压向 0 ⇒「输出 < ε 的区域」由 v<ε 扩到 v<ε^(1/p)；
            // 中段导数 p·v^(p−1) 在 v<1 时小于 1 ⇒ 谷坡变缓而非变陡。
            // 用户裁定从 2.5（谷过宽、台面碎成孤岛）收到 1.1（近似参考、仅略宽）。
            valley = new RefNoise.Power(valley, variant.valleyPower);
        }

        RefNoise.Node top = new RefNoise.PerlinRidge(next.getAsInt(), 1f / 150f, 3, 2.45f, 0.975f);
        top = RefNoise.warpPerlin(top, next.getAsInt(), 300f, 1, 150f);
        top = RefNoise.warpPerlin(top, next.getAsInt(), 40f, 2, 20f);
        top = new RefNoise.Mul(top, new RefNoise.Constant(0.15f));

        RefNoise.Node scaler = new RefNoise.Clamped(valley, 0.02f, 0.1f);
        scaler = new RefNoise.Mapped(scaler, 0.02f, 0.1f, 0f, 1f);
        top = new RefNoise.Mul(top, scaler);

        RefNoise.Node surface = new RefNoise.Mul(
                RefNoise.Perlin.ofScale(next.getAsInt(), 20f, 3), new RefNoise.Constant(0.05f));
        surface = RefNoise.warpPerlin(surface, next.getAsInt(), 40f, 2, 20f);

        RefNoise.Node cubic = new RefNoise.Mul(
                RefNoise.Cubic.ofScale(next.getAsInt(), 500f, 1, 2f, 0.5f), new RefNoise.Constant(0.6f));
        cubic = new RefNoise.Add(cubic, new RefNoise.Constant(0.3f));

        RefNoise.Node valleyBase = new RefNoise.Add(new RefNoise.Mul(valley, cubic), top);

        // 两版都用参考原码的台阶索引：GEO 与 REF 的唯一差别是上面的 valley 展宽，
        // 保证是单变量对比（此前验证过索引口径对结果无影响）。
        RefNoise.Node terrace = new RefNoise.Terrace(valleyBase, 0.9f, 0.15f, 0.35f, 0.4f, 4,
                0f, 1.05f, false);
        RefNoise.Node h = new RefNoise.Add(terrace, surface);
        this.height = new RefNoise.Mul(h, new RefNoise.Constant(0.475f));

        this.dbgValley = valley;
        this.dbgBase = valleyBase;
        this.dbgTerrace = terrace;
    }

    /** 参考归一化高度。 */
    public double height01(double wx, double wz) {
        if (height == null) throw new IllegalStateException("seed() not called");
        return height.compute((float) wx, (float) wz);
    }

    public double e(double wx, double wz) { return height01(wx, wz) * E_PER_REF_UNIT; }

    /** 诊断：反相脊线（谷地骨架）。 */
    public double debugValley(double wx, double wz) { return dbgValley.compute((float) wx, (float) wz); }

    /** 诊断：Terrace 的输入 {@code valley × cubic + top}。 */
    public double debugBase(double wx, double wz) { return dbgBase.compute((float) wx, (float) wz); }

    /** 诊断：Terrace 的输出（尚未叠加 surface 与 0.475）。 */
    public double debugTerrace(double wx, double wz) { return dbgTerrace.compute((float) wx, (float) wz); }
}