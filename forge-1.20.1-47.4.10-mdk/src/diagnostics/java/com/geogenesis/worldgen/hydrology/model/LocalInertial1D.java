package com.geogenesis.worldgen.hydrology.model;

/**
 * 一维局部惯性（local-inertial）浅水原型，仅用于模型判别，不接生产。
 *
 * <p>方程（宽浅矩形断面、单位宽度）：</p>
 * <pre>
 *   dh/dt + dq/dx = s
 *   dq/dt + g h d(eta)/dx + g n^2 |q|q / h^(7/3) = 0
 * </pre>
 * <p>采用固定床面、守恒水深更新、明确 CFL 步长。它省略动量对流项，正是与
 * {@link DiffusiveWaveModel} 的判别差异；两者仍共享 Manning 单位和水面变量。</p>
 */
public final class LocalInertial1D {
    private static final double G = 9.81;
    private final double[] bed, h, q;
    private final double dx, manning, rain, outletLevel, maxDt;
    private final boolean openWest;
    private double time, sourceVolume, boundaryOutflow, westOutflow;
    private long steps;

    public LocalInertial1D(double[] bed, double dx, double manning, double rain, double outletLevel) {
        this(bed, dx, manning, rain, outletLevel, 0.05, false);
    }

    public LocalInertial1D(double[] bed, double dx, double manning, double rain,
                           double outletLevel, double maxDt) {
        this(bed, dx, manning, rain, outletLevel, maxDt, false);
    }

    public LocalInertial1D(double[] bed, double dx, double manning, double rain,
                           double outletLevel, double maxDt, boolean openWest) {
        if (bed.length < 3 || dx <= 0 || manning <= 0 || rain < 0 || !(maxDt > 0)) {
            throw new IllegalArgumentException("configuration");
        }
        this.bed = bed.clone(); this.h = new double[bed.length]; this.q = new double[bed.length - 1];
        this.dx = dx; this.manning = manning; this.rain = rain;
        this.outletLevel = outletLevel; this.maxDt = maxDt; this.openWest = openWest;
    }

    public void setDepth(int i, double value) {
        if (value < 0 || !Double.isFinite(value)) throw new IllegalArgumentException("depth");
        h[i] = value;
    }

    public double depth(int i) { return h[i]; }
    public double discharge(int face) { return q[face]; }
    public double time() { return time; }
    public long steps() { return steps; }
    public double storage() { double v = 0; for (double x : h) v += x * dx; return v; }
    public double sourceVolume() { return sourceVolume; }
    public double boundaryOutflow() { return boundaryOutflow; }
    public double westOutflow() { return westOutflow; }

    /** Run a fixed physical duration; dt is CFL-limited per step. */
    public void run(double duration) {
        while (time < duration - 1e-12) {
            double dt = Math.min(duration - time, stableDt());
            step(dt);
        }
    }

    private void step(double dt) {
        double[] nextQ = q.clone();
        for (int i = 0; i < q.length; i++) {
            double leftEta = bed[i] + h[i], rightEta = bed[i + 1] + h[i + 1];
            double depth = Math.max(1e-8, Math.max(h[i], h[i + 1]));
            double slope = (rightEta - leftEta) / dx;
            double friction = G * manning * manning * Math.abs(q[i])
                    / Math.pow(depth, 7.0 / 3.0);
            nextQ[i] = (q[i] - dt * G * depth * slope) / (1 + dt * friction);
        }
        double[] nextH = h.clone();
        for (int i = 0; i < h.length; i++) {
            double in = i == 0 ? 0 : nextQ[i - 1];
            double out = i == h.length - 1 ? boundaryFlux(i, nextQ[i - 1]) : nextQ[i];
            double source = rain;
            double west = i == 0 && openWest ? Math.max(0, -nextQ[0]) : 0;
            nextH[i] = Math.max(0, h[i] + dt * (source + (in - out - west) / dx));
            sourceVolume += source * dt * dx;
            westOutflow += west * dt;
        }
        System.arraycopy(nextQ, 0, q, 0, q.length);
        System.arraycopy(nextH, 0, h, 0, h.length);
        time += dt; steps++;
    }

    private double boundaryFlux(int cell, double incoming) {
        double eta = bed[cell] + h[cell];
        if (eta <= outletLevel) return 0;
        double depth = Math.max(h[cell], eta - outletLevel);
        double slope = Math.max(1e-8, (eta - outletLevel) / dx);
        double out = Math.pow(depth, 5.0 / 3.0) * Math.sqrt(slope) / manning;
        boundaryOutflow += out;
        return out;
    }

    private double stableDt() {
        // CFL 波速必须包含"本步源项可能加进来的浅水"。
        //   若从干床起步只看当前 maxDepth=0，会把速度估成 0；
        //   旧 sourceLimit ∝ maxDepth 又把 dt 压到极小 ⇒ 水永远起不来（实测全程 depth=0）。
        double predictedDepth = maxDepth() + rain * maxDt;
        double maxWave = Math.sqrt(G * Math.max(0, predictedDepth));
        for (int i = 0; i < q.length; i++) {
            double faceH = 0.5 * (h[i] + h[i + 1]);
            maxWave = Math.max(maxWave, Math.abs(q[i]) / Math.max(1e-8, faceH));
        }
        // 降雨源项不应被错误限制为"不超过当前水深的10%"；在干床上那会冻结水源。
        // CFL maxDt 已根据含源项的预测水深控制本步波速。
        return Math.min(maxDt, 0.25 * dx / Math.max(1e-6, maxWave));
    }

    private double maxDepth() {
        double max = 0;
        for (double value : h) max = Math.max(max, value);
        return max;
    }

}
