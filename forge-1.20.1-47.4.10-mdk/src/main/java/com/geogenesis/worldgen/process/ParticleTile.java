package com.geogenesis.worldgen.process;

import com.geogenesis.worldgen.erosion.ErosionEngine;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Task-owned buffers become private immutable storage only after validation. */
final class ParticleTile {
    private final TileGeometry.Owner owner;
    private final float[] delta;
    // Filled only under the existing stutter gate; task-owned, never used by terrain math.
    long inputNs, kernelNs, buildNs;
    private static long now(boolean timed) { return timed ? System.nanoTime() : 0; }
    private ParticleTile(TileGeometry.Owner owner, float[] delta) {
        this.owner = owner;
        this.delta = delta;
    }
    static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("P flight cancelled");
        }
    }
    static ParticleTile build(ProcessContext ctx, TileGeometry.Owner owner, BooleanSupplier cancelled) {
        boolean timed = com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled();
        long start = now(timed);
        check(cancelled);
        float[] pre = buildInput(ctx, owner, cancelled);
        long inputEnd = now(timed);
        float[] flat = pre.clone();
        var d = ctx.definition();
        var kernel = new ErosionEngine(d.xs(), d::particle, 8);
        if (d.material()) kernel.setHardnessProvider((x, z) -> {
            check(cancelled);
            return ctx.resistance(x, z);
        });
        check(cancelled);
        long kernelStart = now(timed);
        kernel.runErosionOnFlat(flat, pre, 146, 128, owner.originX(), owner.originZ(),
                ctx.seaE(), d.strength(), null, (float) d.terrain().horizontalScale());
        long kernelEnd = now(timed);
        // The historical kernel can return/throw on interruption: never publish that buffer.
        check(cancelled);
        float[] delta = new float[128 * 128];
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            int i = (z + 9) * 146 + x + 9;
            if (!Float.isFinite(flat[i]) || !Float.isFinite(pre[i])) {
                throw new IllegalStateException("nonfinite P tile");
            }
            delta[z * 128 + x] = limitedDelta(flat[i], pre[i]);
        }
        check(cancelled);
        ParticleTile tile = new ParticleTile(owner, delta);
        tile.inputNs = inputEnd - start;
        tile.kernelNs = kernelEnd - kernelStart;
        tile.buildNs = now(timed) - start;
        return tile;
    }
    static float[] buildInput(ProcessContext ctx, TileGeometry.Owner owner, BooleanSupplier cancelled) {
        float[] input = new float[146 * 146];
        for (int z = 0; z < 146; z++) {
            check(cancelled);
            for (int x = 0; x < 146; x++) {
                double e = ctx.baseE(owner.inputX() + x, owner.inputZ() + z);
                if (!Double.isFinite(e)) throw new IllegalStateException("nonfinite B input");
                input[z * 146 + x] = input(e);
            }
        }
        return input;
    }
    static float input(double eB) { return (float) Math.max(eB, -.05); }
    static float limitedDelta(float post, float pre) {
        float v = post - pre;
        if (v > .22f) post = pre + .22f;
        else if (v < -.22f) post = pre - .22f;
        return post - pre;
    }
    float at(double x, double z) {
        double lx = x - owner.originX(), lz = z - owner.originZ();
        int ix = (int) Math.floor(lx), iz = (int) Math.floor(lz);
        if (ix < 0 || iz < 0 || ix >= 127 || iz >= 127) {
            if (ix >= 0 && iz >= 0 && ix < 128 && iz < 128) return delta[iz * 128 + ix];
            return 0;
        }
        float fx = (float) (lx - ix), fz = (float) (lz - iz);
        int i = iz * 128 + ix;
        float top = delta[i] + (delta[i + 1] - delta[i]) * fx;
        float bot = delta[i + 128] + (delta[i + 129] - delta[i + 128]) * fx;
        return top + (bot - top) * fz;
    }
}
