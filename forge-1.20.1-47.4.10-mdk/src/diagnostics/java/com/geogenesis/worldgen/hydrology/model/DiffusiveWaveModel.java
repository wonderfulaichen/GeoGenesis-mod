package com.geogenesis.worldgen.hydrology.model;

/** Finite-volume diffusive-wave prototype. Not connected to world generation. */
public final class DiffusiveWaveModel {
    private static final double SLOPE_EPS = 1e-8;
    private final WaterGrid g;
    private final ModelParams p;
    private final double[] d;
    private double inputVolume, infilVolume, evapVolume, boundaryLossVolume;
    private double time, lastRate, lastAlpha = 1;
    private int steps;
    private boolean converged;

    public DiffusiveWaveModel(WaterGrid grid, ModelParams params) {
        if (grid.cellSize != params.cellSize()) {
            throw new IllegalArgumentException("grid and parameters must use the same cell size");
        }
        g = grid;
        p = params;
        int n = grid.cells();
        d = new double[n];
    }

    public WaterGrid grid() { return g; }
    public ModelParams params() { return p; }
    public double[] depth() { return d.clone(); }
    public double depth(int i, int j) { return d[g.idx(i, j)]; }
    public int steps() { return steps; }
    public boolean converged() { return converged; }
    public double lastRate() { return lastRate; }
    public double lastAlpha() { return lastAlpha; }
    public double time() { return time; }
    public double inputVolume() { return inputVolume; }
    public double infilVolume() { return infilVolume; }
    public double evapVolume() { return evapVolume; }
    public double boundaryLossVolume() { return boundaryLossVolume; }

    /**
     * 当前状态下 a→b 面的<b>未阻尼稳态通量</b>（体积率，block³/时间）。
     *
     * <p>⚠ <b>这不是推进实际使用的转移量</b>：内部交换用隐式线性化
     * {@code transfer = Δη·λ/(1+2λ)}，λ=dt·c/A；λ 大时实际转移远小于本值。
     * 本方法给出的是 λ→0（显式极限）下的通量，用于核对该通量律的稳态口径
     * （稳定态应满足 {@code q = R·x}）。若时步过大导致 λ≫1，
     * 二者会显著不同 ⇒ 必须用 {@link #exchangeDamping(int, int)} 判断是否处于该区制。</p>
     */
    public double faceDischarge(int a, int b) {
        double ea = g.bed[a] + d[a], eb = g.bed[b] + d[b];
        double faceDepth = Math.max(0, Math.max(ea, eb) - Math.max(g.bed[a], g.bed[b]));
        double c = coefficient(faceDepth, ea - eb);
        if (c == 0) return 0;
        return c * (ea - eb);
    }

    /**
     * 隐式阻尼因子 {@code 1+2λ}（λ=dt·c/A）。接近 1 ⇒ 推进接近显式通量律；
     * 显著大于 1 ⇒ 该时步下实际通量被强烈压制，"稳定态"不再满足 {@code q=R·x}。
     */
    public double exchangeDamping(int a, int b) {
        double ea = g.bed[a] + d[a], eb = g.bed[b] + d[b];
        double faceDepth = Math.max(0, Math.max(ea, eb) - Math.max(g.bed[a], g.bed[b]));
        double c = coefficient(faceDepth, ea - eb);
        if (c == 0) return 1;
        return 1 + 2 * (p.dtMax() * c / g.cellArea());
    }

    /**
     * 该面<b>实际被推进的通量</b>（体积率）：隐式解为 {@code Δη·λ/(1+2λ)}，不含 donor 限幅。
     * 这才是稳定态应与 {@code R·x} 相等的量。未淹没/零梯度面返回 0。
     */
    public double steadyFaceDischarge(int a, int b) {
        return faceDischarge(a, b) / exchangeDamping(a, b);
    }

    public void setDepth(int i, int j, double v) {
        if (!Double.isFinite(v) || v < 0) throw new IllegalArgumentException("invalid depth");
        d[g.idx(i, j)] = v;
    }

    public void fillTo(int i, int j, double level) {
        if (!Double.isFinite(level)) throw new IllegalArgumentException("invalid surface");
        int k = g.idx(i, j);
        d[k] = Math.max(d[k], Math.max(0, level - g.bed[k]));
    }

    public double storage() {
        double sum = 0;
        for (double v : d) sum += v;
        return sum * g.cellArea();
    }

    public double surface(int i, int j) {
        int k = g.idx(i, j);
        return g.bed[k] + d[k];
    }

    /** A small update caused by a small time step must never count as equilibrium. */
    public void steadyState() {
        converged = false;
        int quietSteps = 0;
        for (steps = 0; steps < p.maxSteps(); steps++) {
            if (Thread.currentThread().isInterrupted()) return;
            double dt = step();
            double maxDepth = 0;
            for (double v : d) maxDepth = Math.max(maxDepth, v);
            if (lastRate <= p.steadyTol() * Math.max(1, maxDepth) && lastAlpha == 1) {
                quietSteps++;
                if (lastRate == 0 || quietSteps >= 8) {
                    converged = true;
                    steps++;
                    return;
                }
            } else {
                quietSteps = 0;
            }
        }
    }

    /** Advance to a fixed physical time for candidate-model comparison. */
    public void runFor(double endTime) {
        if (!Double.isFinite(endTime) || endTime < time) throw new IllegalArgumentException("endTime");
        int runSteps = 0;
        converged = false;
        while (time < endTime - 1e-12) {
            if (Thread.currentThread().isInterrupted() || ++runSteps > p.maxSteps()) return;
            double dt = Math.min(p.dtMax(), endTime - time);
            advance(dt);
        }
        steps += runSteps;
    }

    /** Symmetric backward-Euler face splitting; every exchange conserves volume. */
    public double step() {
        return advance(p.dtMax());
    }

    private double advance(double dt) {
        double[] before = d.clone();
        sourceStep(dt * 0.5);
        rotationallyBalancedExchange(dt);
        sourceStep(dt * 0.5);
        lastRate = 0;
        for (int k = 0; k < d.length; k++) {
            lastRate = Math.max(lastRate, Math.abs(d[k] - before[k]) / dt);
        }
        lastAlpha = 1;
        time += dt;
        return dt;
    }

    private void rotationallyBalancedExchange(double dt) {
        // 退化的一维网格没有旋转对称：4 个"旋转"会重复/错配面并破坏守恒。
        //   1D 只需一次正反向对称面扫（同一面各半步），每个物理面严格处理一次。
        if (g.nx == 1 || g.nz == 1) {
            rotatedSweep(dt * 0.5, 0, false);
            rotatedSweep(dt * 0.5, 0, true);
            return;
        }
        double[] initial = d.clone();
        double[] sum = new double[d.length];
        double lossBefore = boundaryLossVolume;
        double sumLoss = 0;
        // Average the four rotations of the split operator, not four time steps.
        for (int rotation = 0; rotation < 4; rotation++) {
            System.arraycopy(initial, 0, d, 0, d.length);
            boundaryLossVolume = lossBefore;
            rotatedSweep(dt * 0.5, rotation, false);
            rotatedSweep(dt * 0.5, rotation, true);
            for (int k = 0; k < d.length; k++) sum[k] += d[k] * 0.25;
            sumLoss += (boundaryLossVolume - lossBefore) * 0.25;
        }
        System.arraycopy(sum, 0, d, 0, d.length);
        boundaryLossVolume = lossBefore + sumLoss;
    }

    private void rotatedSweep(double dt, int rotation, boolean reverse) {
        int uSize = rotation % 2 == 0 ? g.nx : g.nz;
        int vSize = rotation % 2 == 0 ? g.nz : g.nx;
        int first = reverse ? d.length - 1 : 0;
        int end = reverse ? -1 : d.length;
        int stride = reverse ? -1 : 1;
        for (int k = first; k != end; k += stride) {
            int u = k / vSize, v = k % vSize;
            int a = rotatedCell(u, v, rotation);
            if (reverse) {
                if (v + 1 < vSize) exchangeFace(a, rotatedCell(u, v + 1, rotation), dt);
                if (u + 1 < uSize) exchangeFace(a, rotatedCell(u + 1, v, rotation), dt);
            } else {
                if (u + 1 < uSize) exchangeFace(a, rotatedCell(u + 1, v, rotation), dt);
                if (v + 1 < vSize) exchangeFace(a, rotatedCell(u, v + 1, rotation), dt);
            }
            int sides = g.openSides(g.ix(a), g.iz(a));
            exchangeBoundary(a, sides, dt);
        }
    }

    private int rotatedCell(int u, int v, int rotation) {
        return switch (rotation) {
            case 0 -> g.idx(u, v);
            case 1 -> g.idx(g.nx - 1 - v, u);
            case 2 -> g.idx(g.nx - 1 - u, g.nz - 1 - v);
            case 3 -> g.idx(v, g.nz - 1 - u);
            default -> throw new IllegalArgumentException("rotation");
        };
    }

    private void exchangeFace(int a, int b, double dt) {
        double etaA = g.bed[a] + d[a], etaB = g.bed[b] + d[b];
        double c = faceConductance(a, b);
        if (c == 0 || etaA == etaB) return;
        // Solve the two-cell implicit linearized exchange, not an explicit flux clamp.
        double lambda = dt * c / g.cellArea();
        double transfer = (etaA - etaB) * lambda / (1 + 2 * lambda);
        transfer = Math.max(-d[b], Math.min(d[a], transfer));
        d[a] -= transfer;
        d[b] += transfer;
    }

    private void exchangeBoundary(int a, int sides, double dt) {
        double c = boundaryConductance(a) * sides;
        if (c == 0) return;
        double lambda = dt * c / g.cellArea();
        // 外部水位按【本格】取值：DRAIN_MASK 下每个出口格可有各自的外部水位
        // （由窗口外真实地形决定），而不是一个全局常数。
        double level = g.exteriorLevelAt(g.ix(a), g.iz(a));
        double transfer = (g.bed[a] + d[a] - level) * lambda / (1 + lambda);
        transfer = Math.min(d[a], transfer);
        d[a] -= transfer;
        boundaryLossVolume += transfer * g.cellArea();
    }

    /** Hydrostatic face reconstruction prevents flow across an unsubmerged bed step. */
    private double faceConductance(int a, int b) {
        double ea = g.bed[a] + d[a], eb = g.bed[b] + d[b];
        double faceDepth = Math.max(0, Math.max(ea, eb) - Math.max(g.bed[a], g.bed[b]));
        return coefficient(faceDepth, ea - eb);
    }

    private double coefficient(double h, double delta) {
        if (h <= 0) return 0;
        double slope = Math.abs(delta) / g.cellSize;
        return Math.pow(h, 5.0 / 3.0) * g.edgeLength()
                / (p.manningN() * g.cellSize * Math.sqrt(Math.max(SLOPE_EPS, slope)));
    }

    private double boundaryConductance(int k) {
        if (g.boundary() == WaterGrid.Boundary.CLOSED) return 0;
        double eta = g.bed[k] + d[k];
        double level = g.exteriorLevelAt(g.ix(k), g.iz(k));
        if (eta <= level) return 0;
        return coefficient(d[k], eta - level);
    }

    private void sourceStep(double dt) {
        double area = g.cellArea();
        for (int k = 0; k < d.length; k++) {
            double rain = p.rainRate() * dt;
            double available = d[k] + rain;
            double infiltration = Math.min(available, p.infilRate() * dt);
            available -= infiltration;
            double evaporation = Math.min(available,
                    (p.evapRate() + p.evapDepthCoeff() * available) * dt);
            d[k] = available - evaporation;
            inputVolume += rain * area;
            infilVolume += infiltration * area;
            evapVolume += evaporation * area;
        }
    }


}
