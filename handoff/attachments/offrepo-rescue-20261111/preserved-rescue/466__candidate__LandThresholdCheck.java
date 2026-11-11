package com.geogenesis.worldgen.terrain.candidate;

/** 只检查阈值平移：c/随机抽签不变、海单调扩张、同侧配方不变、零偏移兼容。 */
public final class LandThresholdCheck {
    private static final double[] SHIFTS = {.20, .30};

    public static void main(String[] args) {
        int checks = 0;
        StringBuilder changed = new StringBuilder();
        for (long seed : new long[]{9139912035078620160L, 0, -419, 12345}) {
            var legacy = new CoherentRegionDistribution(seed);
            var zero = new CoherentRegionDistribution(seed, 0);
            for (double shift : SHIFTS) {
                var shifted = new CoherentRegionDistribution(seed, shift);
                int converted = 0;
                for (int z = -40; z <= 40; z++) for (int x = -40; x <= 40; x++) {
                    var anchor = legacy.anchor(x * 1200.0, z * 1200.0, 0);
                    var before = legacy.choice(anchor);
                    var unshifted = zero.choice(anchor);
                    var after = shifted.choice(anchor);
                    require(before.equals(unshifted), "zero compatibility");
                    require(before.c() == after.c() && before.draw() == after.draw(), "noise/draw drift");
                    double t = Math.max(0, Math.min(1, (shift - before.c()) / .35));
                    require(Math.abs(after.probability() - t * t * (3 - 2 * t)) < 1e-14,
                            "independent shift formula");
                    require(after.probability() >= before.probability(), "probability not monotonic");
                    boolean wasSea = before.recipe() >= 18, isSea = after.recipe() >= 18;
                    require(!wasSea || isSea, "old sea became land");
                    if (wasSea == isSea) require(before.recipe() == after.recipe(),
                            "same side changed recipe");
                    else converted++;
                    checks++;
                }
                require(converted > 0, "empty shift " + shift);
                changed.append(" shift=").append(shift).append(" landToSea=").append(converted);
            }
        }
        try {
            new CoherentRegionDistribution(0, Double.NaN);
            throw new AssertionError("NaN accepted");
        } catch (IllegalArgumentException expected) {
            // 拒绝非有限偏移
        }
        System.out.println("LAND THRESHOLD CHECK PASS samples=" + checks + changed);
    }

    private static void require(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
