package com.geogenesis.worldgen.terrain.candidate;

/** Explicit boundary between historical float evaluation and the external double evaluator. */
final class DualCoordinateCall {
    @FunctionalInterface interface DoubleFunction { double compute(double x, double z); }

    /** A lambda with independently supplied implementations; never widen a float result as double. */
    static final class DualNode implements RefNoise.Node {
        private final RefNoise.Node legacy;
        private final DoubleFunction precise;
        DualNode(RefNoise.Node legacy, DoubleFunction precise) {
            this.legacy = java.util.Objects.requireNonNull(legacy);
            this.precise = java.util.Objects.requireNonNull(precise);
        }
        @Override public float compute(float x, float z) { return legacy.compute(x, z); }
        double computeDouble(double x, double z) { return precise.compute(x, z); }
    }

    static float legacy(RefNoise.Node node, double x, double z) {
        return node.compute((float) x, (float) z);
    }

    static double precise(RefNoise.Node node, double x, double z) {
        if (node instanceof DualNode dual) return dual.computeDouble(x, z);
        if (node.getClass().isSynthetic()) {
            throw new UnsupportedOperationException("float-only synthetic node: " + node.getClass().getName());
        }
        return DoubleTreeFixed.eval(node, x, z);
    }
    private DualCoordinateCall() { }
}
