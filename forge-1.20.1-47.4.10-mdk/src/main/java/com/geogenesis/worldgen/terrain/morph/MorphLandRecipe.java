package com.geogenesis.worldgen.terrain.morph;

/**
 * 18 个陆地条目按<b>双坐标内核</b>重新表达。无海洋配方、无权重。
 *
 * <p>复合条目的 {@code raw} 是两个成员线性外壳的<b>加权平均</b>：
 * <pre>
 *   amp  = (1-t)·ampA + t·ampB
 *   raw  = ((1-t)·ampA·rawA + t·ampB·rawB) / amp
 * </pre>
 * 于是 {@code raw·amp + bias == (1-t)(ampA·rawA + biasA) + t(ampB·rawB + biasB)} 是
 * <b>数学恒等</b>（两边同式），即"两个成员线性外壳输出的加权平均"。
 * ⚠ 在 float 下它只是近似：把 raw 重新乘回 amp 再加 bias 与成员成品高度之间会有
 * float 舍入残差，故<b>不</b>称"逐位精确"。这不是缺陷 —— 它就是"过渡"的定义。</p>
 */
final class MorphLandRecipe implements MorphRecipe {

    private final MorphCatalog.Entry entry;
    private final MorphLandRecipe left, right;

    MorphLandRecipe(MorphCatalog.Entry entry) {
        this.entry = entry;
        this.left = entry.isComposite() ? new MorphLandRecipe(entry.left()) : null;
        this.right = entry.isComposite() ? new MorphLandRecipe(entry.right()) : null;
    }

    static MorphRecipe[] all(MorphCatalog catalog) {
        MorphRecipe[] r = new MorphRecipe[catalog.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = new MorphLandRecipe(catalog.entry(i));
        }
        return r;
    }

    /** 复合过渡量：selector 经 [0.375,0.625] 归一并 clamp 到 [0,1]。 */
    private double alpha(double x, double z) {
        double s = MorphNoiseEval.eval(entry.selector(), x, z);
        return Math.max(0, Math.min(1, (s - MorphCatalog.ALPHA_LO)
                / (MorphCatalog.ALPHA_HI - MorphCatalog.ALPHA_LO)));
    }

    @Override public double raw(double x, double z) {
        if (!entry.isComposite()) {
            return MorphNoiseEval.eval(entry.rawNode(), x, z);
        }
        double t = alpha(x, z);
        double ampA = left.amplitude(x, z), ampB = right.amplitude(x, z);
        double amp = (1 - t) * ampA + t * ampB;
        return ((1 - t) * ampA * left.raw(x, z) + t * ampB * right.raw(x, z)) / amp;
    }

    @Override public double amplitude(double x, double z) {
        if (!entry.isComposite()) {
            return entry.amplitude(0, 0);
        }
        double t = alpha(x, z);
        return (1 - t) * left.amplitude(x, z) + t * right.amplitude(x, z);
    }

    @Override public double bias(double x, double z) {
        if (!entry.isComposite()) {
            return entry.bias(0, 0);
        }
        double t = alpha(x, z);
        return (1 - t) * left.bias(x, z) + t * right.bias(x, z);
    }

    @Override public Role role() { return Role.valueOf(entry.kind().name()); }


    @Override public boolean clamped() { return entry.clamped(); }
}
