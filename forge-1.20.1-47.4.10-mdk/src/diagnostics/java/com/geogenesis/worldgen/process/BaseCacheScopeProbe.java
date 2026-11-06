package com.geogenesis.worldgen.process;

import com.geogenesis.diagnostics.WorldGenProfiler;
import com.geogenesis.worldgen.core.BaseWorldSettings;
import com.geogenesis.worldgen.erosion.ParticleErosionParams;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** Context gate and real tile-input integration, without erosion kernel or Minecraft. */
public final class BaseCacheScopeProbe {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        var ctx = new ProcessContext(new ProcessContext.Definition(9139, "probe", TerrainParams.defaults(),
                BaseWorldSettings.defaults(), -64, 320, ProcessContext.Mode.PARTICLE,
                new ParticleErosionParams(1, 1, .5, 1, 2, .1), false, 1, true, "probe"));
        require(!WorldGenProfiler.stutterEnabled(), "requires default-disabled JVM");
        var field = ProcessContext.class.getDeclaredField("baseCacheDiag");
        field.setAccessible(true);
        ctx.sampleBase(0, 0);
        require(field.get(ctx) == null && ctx.baseCacheDiagnostics() == null, "off allocated diagnostics");
        WorldGenProfiler.configure(true, 200);
        try {
            ctx.sampleBase(0, 0);
            var diag = ctx.baseCacheDiagnostics();
            require(diag != null && diag.otherSummary().contains("total=1 "), "other scope");
            var owner = new TileGeometry.Owner(0, 0);
            float[] observed = ParticleTile.buildInput(ctx, owner, () -> false);
            require(diag.otherSummary().contains("total=1 "), "tile leaked into other");
            require(diag.finish(diag.input()).contains("contextTile={total=21316 "), "input point count");
            int[] rows = {0};
            try {
                ParticleTile.buildInput(ctx, owner, () -> ++rows[0] > 1);
                throw new AssertionError("expected cancellation");
            } catch (java.util.concurrent.CancellationException expected) { }
            require(diag.finish(diag.input()).contains("contextTile={total=21462 "), "partial input count");
            WorldGenProfiler.configure(false, 200);
            require(ctx.baseCacheDiagnostics() == null, "disabled gate ignored");
            float[] plain = ParticleTile.buildInput(ctx, owner, () -> false);
            require(java.util.Arrays.equals(observed, plain), "tile values changed");
        } finally { WorldGenProfiler.configure(false, 200); }
        System.out.println("PASS: disabled lazy allocation, explicit input scope, tile bit invariance");
    }
}
