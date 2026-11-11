package com.geogenesis.worldgen.terrain.candidate;

/** 18 land entries re-expressed for the double-coordinate kernel. No ocean recipes, no weights. */
final class DoubleLandRecipe implements MorphRecipe {
    private final RecipeMorphCatalog.Entry entry;
    private final DoubleLandRecipe left, right;

    DoubleLandRecipe(RecipeMorphCatalog.Entry entry) {
        this.entry = entry;
        this.left = entry.isComposite() ? new DoubleLandRecipe(entry.left()) : null;
        this.right = entry.isComposite() ? new DoubleLandRecipe(entry.right()) : null;
    }

    static MorphRecipe[] all(RecipeMorphCatalog catalog) {
        MorphRecipe[] r = new MorphRecipe[catalog.size()];
        for (int i = 0; i < r.length; i++) r[i] = new DoubleLandRecipe(catalog.entry(i));
        return r;
    }

    private double alpha(double x, double z) {
        double s = DoubleTreeFixed.eval(entry.selector(), x, z);
        return Math.max(0, Math.min(1, (s - RecipeMorphCatalog.ALPHA_LO)
                / (RecipeMorphCatalog.ALPHA_HI - RecipeMorphCatalog.ALPHA_LO)));
    }

    @Override public double raw(double x, double z) {
        if (!entry.isComposite()) return DoubleTreeFixed.eval(entry.rawNode(), x, z);
        double t = alpha(x, z);
        double ampA = left.amplitude(x, z), ampB = right.amplitude(x, z);
        double amp = (1 - t) * ampA + t * ampB;
        return ((1 - t) * ampA * left.raw(x, z) + t * ampB * right.raw(x, z)) / amp;
    }

    @Override public double amplitude(double x, double z) {
        if (!entry.isComposite()) return entry.amplitude(0f, 0f);
        double t = alpha(x, z);
        return (1 - t) * left.amplitude(x, z) + t * right.amplitude(x, z);
    }

    @Override public double bias(double x, double z) {
        if (!entry.isComposite()) return entry.bias(0f, 0f);
        double t = alpha(x, z);
        return (1 - t) * left.bias(x, z) + t * right.bias(x, z);
    }

    @Override public Role role() { return Role.valueOf(entry.kind().name()); }

    @Override public boolean land() { return true; }

    @Override public boolean clamped() { return entry.clamped(); }
}
