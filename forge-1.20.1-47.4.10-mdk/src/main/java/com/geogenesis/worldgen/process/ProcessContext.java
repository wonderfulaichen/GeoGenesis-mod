package com.geogenesis.worldgen.process;

import com.geogenesis.worldgen.core.BaseWorldSampler;
import com.geogenesis.worldgen.core.BaseWorldSettings;
import com.geogenesis.worldgen.erosion.ParticleErosionParams;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.RockType;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.Objects;

/** Frozen, isolated B/P definition. No application/global configuration is read here. */
public final class ProcessContext {
    public enum Mode { OFF, PARTICLE }

    /**
     * 配置开关 → 运行模式。<b>唯一的映射点</b>，不许在调用方各写一份反过来的三元表达式
     * （{@code erosionEnabled=false} 却跑粒子，是静默且昂贵的错误）。
     *
     * <p>OFF 的语义（见 {@link #assemble}）：{@code eP == eB}、零 tile 构建
     * ⇒ 整个 P 层退化为纯 B 地形，水文看到的也是同一份 B。</p>
     */
    public static Mode modeFor(boolean erosionEnabled) {
        return erosionEnabled ? Mode.PARTICLE : Mode.OFF;
    }
    public record Definition(long seed, String baseAlgorithm, TerrainParams terrain,
                             BaseWorldSettings settings, int minY, int maxY,
                             Mode mode, ParticleErosionParams particle, boolean xs,
                             float strength, boolean material, String parameterSource) {
        public Definition {
            Objects.requireNonNull(baseAlgorithm);
            Objects.requireNonNull(terrain);
            Objects.requireNonNull(settings);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(particle);
            Objects.requireNonNull(parameterSource);
            if (baseAlgorithm.isBlank() || maxY <= minY || !Float.isFinite(strength)
                    || strength < 0 || !(terrain.horizontalScale() > 0)) {
                throw new IllegalArgumentException("invalid process definition");
            }
            double[] finite = {particle.dropsMul(), particle.erodeMul(), particle.cascadeStrength(),
                    particle.momentumTransfer(), particle.lrate()};
            for (double v : finite) if (!Double.isFinite(v) || v < 0) {
                throw new IllegalArgumentException("invalid particle parameter");
            }
            if (particle.iterations() < 0) throw new IllegalArgumentException("iterations");
        }
    }
    public record BaseSample(String identity, double x, double z, double eB, double yB) {}
    public static final String ALGORITHM = "BP-particle-v1;wu-grid=1;owner=48;halo=40;pad=9;"
            + "input=float(max(B,-.05));delta=float(clamped-post-minus-input);limit=.22;"
            + "blend=legacy-float-bilinear-double-symmetric10;softcap=legacy-second;"
            + "hardness=B-G-rock-at-16wu-center;hardgrid=16/11;coupling=.6;kernel=R0b;split=8";
    private final Definition definition;
    private final BaseWorldSampler base;
    private final HeightCurve curve;
    private final String baseIdentity, identity;

    public ProcessContext(Definition definition) {
        this.definition = Objects.requireNonNull(definition);
        // TerrainParams and its spline children are scalar/nested immutable records.
        base = new BaseWorldSampler(definition.seed(), definition.terrain(), definition.minY(),
                definition.maxY(), definition.settings());
        curve = new HeightCurve(definition.terrain(), definition.minY(), definition.maxY());
        baseIdentity = "B|" + definition.seed() + "|" + definition.baseAlgorithm() + "|"
                + definition.terrain() + "|" + definition.settings() + "|" + definition.minY()
                + "|" + definition.maxY() + "|horizontal=wu;vertical=e->HeightCurveY";
        // Full canonical identity, not a short digest; execution budgets deliberately absent.
        identity = baseIdentity + "|P|" + ALGORITHM + "|" + definition.mode() + "|"
                + definition.particle() + "|xs=" + definition.xs() + "|strength="
                + Float.toHexString(definition.strength()) + "|material=" + definition.material()
                + "|seaE=" + Float.toHexString((float) curve.seaE())
                + "|source=" + definition.parameterSource();
    }
    public Definition definition() { return definition; }
    public String identity() { return identity; }
    public String baseIdentity() { return baseIdentity; }
    public BaseSample sampleBase(double x, double z) {
        TileGeometry.checkPoint(x, z);
        double e = base.sampleE(x, z);
        return new BaseSample(baseIdentity, x, z, e, curve.heightFromE(e));
    }
    public double sampleBaseHeight(double x, double z) { return sampleBase(x, z).yB(); }
    double baseE(double x, double z) { return base.sampleE(x, z); }
    double height(double e) { return curve.heightFromE(e); }
    float seaE() { return (float) curve.seaE(); }
    double resistance(double x, double z) {
        double sx = (Math.floor(x / 16) + .5) * 16;
        double sz = (Math.floor(z / 16) + .5) * 16;
        // Value extraction only: never publish the mutable BaseColumn or its arrays.
        int rock = base.sampleTerrain(sx, sz).rockTypeId;
        return RockType.values()[rock].resistance();
    }
    double assemble(double eB, double delta) {
        if (definition.mode() == Mode.OFF) return eB;
        double e = eB + delta;
        if (e <= 0) return Math.max(-1, e);
        double hi = definition.terrain().splineConfig().maxLandHi();
        double start = hi * .97;
        if (e <= start) return e;
        double span = hi * .98 - start;
        return start + span * (1 - 1 / (1 + (e - start) / span));
    }
}
