package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <b>对拍桥</b>：把已验证候选的包内类包装成一组可直接调用的方法。
 *
 * <p>为什么需要它：候选包的 {@code RecipeMorphCatalog} / {@code DoubleFullMorphLand} 等是
 * <b>包级可见</b>，外部无法直接构造，而给它们加 public 就会改到冻结源码。
 * 本类落在同一个包里、只做转发，因此<b>不改动候选的任何一行</b>，也不需要反射。</p>
 *
 * <p>链的顺序严格照 {@code MultiSeedChain.Chain}：
 * {@code ContinentField.seed(seed)} → {@code RecipeMorphCatalog(seed)} →
 * {@code DoubleFullMorphLand(catalog)} →
 * {@code DoubleOceanAssemblyCandidate(land::e, continent::sample, seed+3).enableCoastalDetail(seed)} →
 * {@code HeightCurve(params, minY, maxY)}。</p>
 *
 * <p><b>本类只是验证工具</b>，不进入拟生产包。</p>
 */
public final class ParityBridge {

    private final ContinentField continent;
    private final DoubleFullMorphLand land;
    private final DoubleOceanAssemblyCandidate ocean;
    private final HeightCurve curve;

    public ParityBridge(long seed, TerrainParams params, int minY, int maxY) {
        this.continent = new ContinentField(params);
        this.continent.seed(seed);
        RecipeMorphCatalog catalog = new RecipeMorphCatalog(seed);
        this.land = new DoubleFullMorphLand(catalog);
        this.ocean = new DoubleOceanAssemblyCandidate(land::e, continent::sample, seed + 3)
                .enableCoastalDetail(seed);
        this.curve = new HeightCurve(params, minY, maxY);
    }

    /** 海陆装配后的 e。 */
    public double e(double x, double z) { return ocean.e(x, z); }

    /** 纯陆地侧 e（未经海洋装配）。 */
    public double landE(double x, double z) { return land.e(x, z); }

    /** 高度 Y。 */
    public double height(double x, double z) { return curve.heightFromE(ocean.e(x, z)); }

    /** 大陆性 c。 */
    public double continentality(double x, double z) { return continent.sample(x, z); }

    /** 山形遮罩值。 */
    public double mask(double x, double z) { return land.mask(x, z); }
}
