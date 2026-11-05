package com.geogenesis.worldgen.terrain.morph;

/**
 * O3: only M1_GEO's PerlinRidge crease is rounded; all other nodes are reused.
 * raw = Warped(Mul(PerlinRidge(q,1/610,4,2.35,1.15), AlphaMix(Perlin(q+1,1/180,4),0.30)),
 *              Perlin(q+2,1/350,1), Perlin(q+3,1/350,1), 150).
 * round(n) = (sqrt(n²+r²)-r)/(sqrt(1+r²)-r) = n²(sqrt(1+r²)+r)/(sqrt(n²+r²)+r);
 * round(0)=0, round(±1)=1, so signal=1-round stays in [0,1] and keeps the ridge max.
 * r==0 returns MorphLandRecipe.all(catalog) unchanged for both entry points; r>0 is caller-supplied.
 * guideRecipes is a macro guide (first octaves only): 宏观导向不是实际最终高度/坡度，不替代 O1 高度。
 * Only entry 17 is wrapped; amp/bias/role/clamped delegate. FTF-derived MIT, as MorphNoise.
 */
final class MorphRoundedMountain {

    private static final int OCT = 4;
    private static final float RIDGE_FREQ = 1f / 610f, RIDGE_LAC = 2.35f, RIDGE_GAIN = 1.15f;
    private static final float SCALER_FREQ = 1f / 180f, SCALER_ALPHA = 0.30f;
    private static final float WARP_FREQ = 1f / 350f, WARP_POW = 150f;

    private MorphRoundedMountain() { }

    static MorphRecipe[] recipes(MorphCatalog catalog, double radius) {
        return build(catalog, radius, false);
    }

    /**
     * 宏观导向：M1 ridge 只取第一 oct（spectral/max 仍是原 4oct 值），180wu scaler 只取
     * 第一 oct 但按原 4oct 的 max/min 归一，350wu 大域 warp 保留。
     * 宏观导向不是实际最终高度/坡度，不替代 O1 高度；由主代理负责关闭其方向扰动与台地/劣地锐边。
     */
    static MorphRecipe[] guideRecipes(MorphCatalog catalog, double radius) {
        return build(catalog, radius, true);
    }

    private static MorphRecipe[] build(MorphCatalog catalog, double radius, boolean guide) {
        java.util.Objects.requireNonNull(catalog, "catalog");
        if (!Double.isFinite(radius) || radius < 0) throw new IllegalArgumentException("radius " + radius);
        MorphRecipe[] out = MorphLandRecipe.all(catalog);
        if (radius == 0.0) return out;
        ParsedM1 p = parseM1(catalog.entry(MorphCatalog.IDX_MOUNTAIN), catalog.seed());
        RoundedRidge ridge = new RoundedRidge(p.ridge(), radius, guide ? 1 : p.ridge().octaves());
        MorphNoise.Node scaler = guide ? firstOctaveScaler(p.scaler()) : p.scaler();
        MorphNoise.Node raw = new MorphNoise.Warped(new MorphNoise.Mul(
                new MorphNoise.DualNode(ridge, ridge::computeDouble), scaler),
                p.warpX(), p.warpZ(), p.warpPow());
        out[MorphCatalog.IDX_MOUNTAIN] = new RawOverride(out[MorphCatalog.IDX_MOUNTAIN], raw);
        return out;
    }

    private record ParsedM1(MorphNoise.PerlinRidge ridge, MorphNoise.Node scaler,
                            MorphNoise.Node warpX, MorphNoise.Node warpZ, float warpPow) { }

    private static ParsedM1 parseM1(MorphCatalog.Entry e, long seed) {
        MorphNoise.Node raw = e.rawNode();
        if (e.index() != MorphCatalog.IDX_MOUNTAIN || e.kind() != MorphCatalog.Kind.MOUNTAIN
                || !"M1_GEO".equals(e.id()) || e.isComposite() || raw == null)
            throw new IllegalStateException("not atomic M1_GEO: " + e.id());
        if (!(raw instanceof MorphNoise.Warped w) || w.pow() != WARP_POW
                || !(w.input() instanceof MorphNoise.Mul m)
                || !(m.a() instanceof MorphNoise.PerlinRidge ridge)
                || !(m.b() instanceof MorphNoise.AlphaMix scaler)
                || !(scaler.input() instanceof MorphNoise.Perlin sn))
            throw new IllegalStateException("M1 raw shape: " + raw);
        if (ridge.octaves() != OCT || ridge.frequency() != RIDGE_FREQ || ridge.lacunarity() != RIDGE_LAC
                || ridge.gain() != RIDGE_GAIN || !(ridge.max() > 0f) || !Float.isFinite(ridge.max())
                || scaler.alpha() != SCALER_ALPHA || sn.octaves() != OCT || sn.frequency() != SCALER_FREQ
                || sn.lacunarity() != 2f || sn.gain() != 0.5f
                || !(w.wx() instanceof MorphNoise.Perlin wx) || !(w.wz() instanceof MorphNoise.Perlin wz)
                || wx.octaves() != 1 || wz.octaves() != 1
                || wx.frequency() != WARP_FREQ || wz.frequency() != WARP_FREQ
                || wx.lacunarity() != 2f || wz.lacunarity() != 2f || wx.gain() != 0.5f || wz.gain() != 0.5f)
            throw new IllegalStateException("M1 constants changed");
        if (ridge.seed() != MorphCatalog.base(seed + MorphCatalog.OFF_MOUNTAIN)
                || sn.seed() != ridge.seed() + 1 || wx.seed() != ridge.seed() + 2
                || wz.seed() != ridge.seed() + 3)
            throw new IllegalStateException("M1 seed order changed");
        return new ParsedM1(ridge, scaler, w.wx(), w.wz(), w.pow());
    }

    /** 180wu scaler 只算第一倍频，但仍按原 4 倍频的 min/max 归一（不抬山）。 */
    private static MorphNoise.Node firstOctaveScaler(MorphNoise.Node scaler) {
        MorphNoise.AlphaMix mix = (MorphNoise.AlphaMix) scaler;
        FirstOctavePerlin first = new FirstOctavePerlin((MorphNoise.Perlin) mix.input());
        return new MorphNoise.AlphaMix(new MorphNoise.DualNode(first, first::computeDouble), mix.alpha());
    }

    private static final class RawOverride implements MorphRecipe {
        private final MorphRecipe delegate;
        private final MorphNoise.Node raw;

        RawOverride(MorphRecipe delegate, MorphNoise.Node raw) {
            this.delegate = java.util.Objects.requireNonNull(delegate);
            this.raw = java.util.Objects.requireNonNull(raw);
        }

        @Override public double raw(double x, double z) { return MorphNoiseEval.eval(raw, x, z); }
        @Override public double amplitude(double x, double z) { return delegate.amplitude(x, z); }
        @Override public double bias(double x, double z) { return delegate.bias(x, z); }
        @Override public MorphRecipe.Role role() { return delegate.role(); }
        @Override public boolean clamped() { return delegate.clamped(); }
    }

    /** Perlin 单倍频：只算 i=0，但用原多倍频的 min/max 归一。 */
    private static final class FirstOctavePerlin implements MorphNoise.Node {
        private final int seed;
        private final float frequency, gain, min, max;

        FirstOctavePerlin(MorphNoise.Perlin src) {
            seed = src.seed();
            frequency = src.frequency();
            gain = src.gain();
            min = src.min();
            max = src.max();
        }

        @Override public float compute(float x, float z) {
            float sum = MorphNoise.Perlin.sample(x * frequency, z * frequency, seed, true) * gain;
            return MorphNoise.mapClamped(sum, min, max);
        }

        double computeDouble(double x, double z) {
            double sum = MorphNoiseEval.sample(seed, x * frequency, z * frequency) * gain;
            return MorphNoiseEval.map(sum, min, max);
        }
    }

    private static final class RoundedRidge implements MorphNoise.Node {
        private final int seed, octaves;
        private final float frequency, lacunarity, gain, max;
        private final float[] spectral;
        private final double radius, norm;

        RoundedRidge(MorphNoise.PerlinRidge src, double radius, int octaveLimit) {
            seed = src.seed();
            octaves = Math.min(src.octaves(), octaveLimit);
            frequency = src.frequency();
            lacunarity = src.lacunarity();
            gain = src.gain();
            max = src.max();
            spectral = src.spectral();
            this.radius = radius;
            norm = Math.sqrt(1 + radius * radius) + radius;
            if (!(radius > 0) || !Double.isFinite(radius) || !(norm > 0) || !Double.isFinite(norm)
                    || octaves < 1)
                throw new IllegalArgumentException("radius " + radius + " octaves " + octaves);
        }

        private double round(double n) {
            return n * n * norm / (Math.sqrt(n * n + radius * radius) + radius);
        }

        @Override public float compute(float x, float z) {
            float fx = x * frequency, fz = z * frequency;
            float amp = 2f, value = 0f, weight = 1f;
            for (int i = 0; i < octaves; i++) {
                float s = 1f - (float) round(MorphNoise.Perlin.sample(fx, fz, seed + i, true));
                s *= s;
                s *= weight;
                weight = MorphNoise.clamp(s * amp, 0f, 1f);
                value += s * spectral[i];
                fx *= lacunarity;
                fz *= lacunarity;
                amp *= gain;
            }
            return MorphNoise.mapClamped(value, 0f, max);
        }

        double computeDouble(double x, double z) {
            double u = x * frequency, v = z * frequency;
            double amp = 2, value = 0, weight = 1;
            for (int i = 0; i < octaves; i++) {
                double s = 1 - round(MorphNoiseEval.sample(seed + i, u, v));
                s *= s;
                s *= weight;
                weight = MorphNoiseEval.clamp(s * amp, 0, 1);
                value += s * spectral[i];
                u *= lacunarity;
                v *= lacunarity;
                amp *= gain;
            }
            return MorphNoiseEval.map(value, 0, max);
        }
    }
}
