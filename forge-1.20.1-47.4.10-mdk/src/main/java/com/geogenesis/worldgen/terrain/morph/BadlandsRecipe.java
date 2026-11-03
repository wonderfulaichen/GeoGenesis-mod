package com.geogenesis.worldgen.terrain.morph;

/**
 * <b>参考原版逐行复刻</b>：FreeTerraForged 1.21.1 {@code cell/terrain/Populators.java:216-259}
 * 的 {@code makeBadlands}。
 *
 * <p>算子实现见 {@link MorphNoise}（同 GRAD 表、同哈希、同 float 精度、同解析上界归一化）。</p>
 *
 * <pre>
 *   mask  = perlin(next,270,3) → clamp(0.35,0.65) → map(0,1)
 *   hills = perlinRidge(next,275,4) → warp(next,400,2,100) → warp(next,18,1,20) → ×mask
 *   mod1  = warp(hills,next,100,1,50) × 0.4
 *   low   = steps(hills,4, 0.6,0.7)×0.6 + mod1
 *   high  = steps(hills,10,0.6,0.7)×0.6 + mod1
 *   detail= (low+high)×0.5 + 0.5
 *   scaler= perlin(next,200,3) × 0.4
 *   mod2  = hills × scaler
 *   shape = (steps(hills,4,0.65,0.75,CURVE3)×0.6 + mod2) × 0.6
 *   height= shape×detail×0.55 + 0.025
 * </pre>
 *
 * <p>流式约束：所有节点是 (x,z) 的纯函数，归一化只用解析上界，无整图统计、无跨块状态。</p>
 */
public final class BadlandsRecipe {

    /** 参考高度 → 本项目 e 的换算：1.0 参考单位 ≈ 256 格 ≈ 1.08 e（陆侧 236 格/e）。 */
    public static final double E_PER_REF_UNIT = 1.08;

    private MorphNoise.Node height;
    private MorphNoise.Node dbgMask, dbgHills, dbgDetail, dbgShape;

    public BadlandsRecipe() { }

    public String id() { return "ref"; }

    public String label() { return "参考原版复刻 makeBadlands"; }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;

        MorphNoise.Node mask = new MorphNoise.Mapped(
                new MorphNoise.Clamped(MorphNoise.Perlin.ofScale(next.getAsInt(), 270f, 3), 0.35f, 0.65f),
                0.35f, 0.65f, 0f, 1f);

        MorphNoise.Node hills = MorphNoise.PerlinRidge.ofScale(next.getAsInt(), 275f, 4);
        hills = MorphNoise.warpPerlin(hills, next.getAsInt(), 400f, 2, 100f);
        hills = MorphNoise.warpPerlin(hills, next.getAsInt(), 18f, 1, 20f);
        hills = new MorphNoise.Mul(hills, mask);

        MorphNoise.Node mod1 = new MorphNoise.Mul(
                MorphNoise.warpPerlin(hills, next.getAsInt(), 100f, 1, 50f), new MorphNoise.Constant(0.4f));

        MorphNoise.Node low = new MorphNoise.Add(new MorphNoise.Mul(
                new MorphNoise.Steps(hills, 4, 0.6f, 0.7f, false), new MorphNoise.Constant(0.6f)), mod1);
        MorphNoise.Node high = new MorphNoise.Add(new MorphNoise.Mul(
                new MorphNoise.Steps(hills, 10, 0.6f, 0.7f, false), new MorphNoise.Constant(0.6f)), mod1);
        MorphNoise.Node detail = new MorphNoise.AlphaMix(new MorphNoise.Add(low, high), 0.5f);

        MorphNoise.Node scaler = new MorphNoise.Mul(MorphNoise.Perlin.ofScale(next.getAsInt(), 200f, 3),
                new MorphNoise.Constant(0.4f));
        MorphNoise.Node mod2 = new MorphNoise.Mul(hills, scaler);

        MorphNoise.Node shape = new MorphNoise.Mul(new MorphNoise.Add(new MorphNoise.Mul(
                new MorphNoise.Steps(hills, 4, 0.65f, 0.75f, true), new MorphNoise.Constant(0.6f)), mod2),
                new MorphNoise.Constant(0.6f));

        this.height = new MorphNoise.Add(new MorphNoise.Mul(
                new MorphNoise.Mul(shape, detail), new MorphNoise.Constant(0.55f)), new MorphNoise.Constant(0.025f));
        this.dbgMask = mask;
        this.dbgHills = hills;
        this.dbgDetail = detail;
        this.dbgShape = shape;
    }

    /** 参考归一化高度（0.025 起，约 0.5 跨度）。 */
    public double height01(double wx, double wz) {
        if (height == null) throw new IllegalStateException("seed() not called");
        return height.compute((float) wx, (float) wz);
    }

    public double e(double wx, double wz) { return height01(wx, wz) * E_PER_REF_UNIT; }

    public double debugMask(double wx, double wz) { return dbgMask.compute((float) wx, (float) wz); }

    public double debugHills(double wx, double wz) { return dbgHills.compute((float) wx, (float) wz); }

    public double debugDetail(double wx, double wz) { return dbgDetail.compute((float) wx, (float) wz); }

    public double debugShape(double wx, double wz) { return dbgShape.compute((float) wx, (float) wz); }

    // ---- 目录用的节点访问器（与 height01 同一棵树，只读）----

    /** 完整高度链根 {@code shape×detail×0.55 + 0.025}。 */
    public MorphNoise.Node heightNode() {
        if (height == null) throw new IllegalStateException("seed() not called");
        return height;
    }

    /** mask 之后的山丘场 {@code hills = (ridge 双 warp) × mask}（morph 的 raw）。 */
    public MorphNoise.Node hillsNode() {
        if (dbgHills == null) throw new IllegalStateException("seed() not called");
        return dbgHills;
    }

    /** 劣地遮罩 {@code map(clamp(perlin(270,3), 0.35, 0.65))}。 */
    public MorphNoise.Node maskNode() {
        if (dbgMask == null) throw new IllegalStateException("seed() not called");
        return dbgMask;
    }
}