package com.geogenesis.worldgen.hydrology.model;

/** 隔离契约：世界对齐的床面样本，双三次 Hermite 高度及其同源解析梯度；不接生产。 */
final class TraceBedField {
    interface Nodes { double height(int x, int z); }
    record Sample(double height, double dx, double dz) { }
    private final double step;
    private final Nodes nodes;

    TraceBedField(double step, Nodes nodes) {
        if (!(step > 0) || !Double.isFinite(step) || nodes == null)
            throw new IllegalArgumentException("finite positive wu spacing and world nodes required");
        this.step = step;
        this.nodes = nodes;
    }

    double step() { return step; }

    Sample at(double x, double z) {
        int ix = lattice(x), iz = lattice(z);
        double tx = x / step - ix, tz = z / step - iz;
        double[] rows = new double[4], slopes = new double[4];
        for (int j = 0; j < 4; j++) {
            double a = node(ix - 1, iz + j - 1), b = node(ix, iz + j - 1);
            double c = node(ix + 1, iz + j - 1), d = node(ix + 2, iz + j - 1);
            rows[j] = cubic(a, b, c, d, tx);
            slopes[j] = derivative(a, b, c, d, tx);
        }
        return new Sample(cubic(rows[0], rows[1], rows[2], rows[3], tz),
                cubic(slopes[0], slopes[1], slopes[2], slopes[3], tz) / step,
                derivative(rows[0], rows[1], rows[2], rows[3], tz) / step);
    }

    private int lattice(double v) {
        double f = Math.floor(v / step);
        if (!Double.isFinite(f) || f < Integer.MIN_VALUE + 2.0 || f > Integer.MAX_VALUE - 2.0)
            throw new IllegalArgumentException("outside world lattice");
        return (int) f;
    }

    private double node(int x, int z) {
        double h = nodes.height(x, z);
        if (!Double.isFinite(h)) throw new IllegalArgumentException("unknown bed node " + x + "," + z);
        return h;
    }

    private static double cubic(double a, double b, double c, double d, double t) {
        double m0 = (c - a) * 0.5, m1 = (d - b) * 0.5;
        return ((2 * b - 2 * c + m0 + m1) * t + (-3 * b + 3 * c - 2 * m0 - m1)) * t * t
                + m0 * t + b;
    }

    private static double derivative(double a, double b, double c, double d, double t) {
        double m0 = (c - a) * 0.5, m1 = (d - b) * 0.5;
        return (3 * (2 * b - 2 * c + m0 + m1) * t
                + 2 * (-3 * b + 3 * c - 2 * m0 - m1)) * t + m0;
    }
}
