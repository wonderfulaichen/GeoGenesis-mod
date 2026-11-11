package com.geogenesis.worldgen.terrain.candidate;

/** §11数值候选，仅诊断；无生产依赖、无传播消费者、无缓存或默认接线。 */
final class DiagnosticTectonicDriver {
    static final double L = 2000, R = 150, SIGMA = 1000, GAIN = 2;
    static final double H = R / (2 * Math.sqrt(2));
    static final String VERSION = "pou36-projection150-bspline150-v1";
    private static final long SEED_SALT = 0x9E3779B97F4A7C15L;
    private static final long VELOCITY_SALT = 0xBF58476D1CE4E5B9L;
    private static final long WARP_SALT = 0x2C6EF1A384BD9075L;
    private final long seed;

    DiagnosticTectonicDriver(long seed) { this.seed = seed; }

    record Field(double value, double dx, double dz) { }
    record Site(int x, int z, double sx, double sz, double vx, double vz) { }
    record Vote(double raw, double stress, double dx, double dz, double numerator, double denominator) { }
    record Warp(double x, double z, double xx, double xz, double zx, double zz) { }
    /** stressX/Z是未限幅U的world导数；硬clamp接口不承诺C1。 */
    record Snapshot(double stressRaw, double stress, double stressX, double stressZ,
                    double distance, double distanceX, double distanceZ) { }

    Snapshot sample(double x, double z) {
        var q = warp(x, z);
        var s = stressAt(q.x, q.z);
        var d = distance(x, z);
        return new Snapshot(s.raw, s.stress, s.dx * q.xx + s.dz * q.zx,
                s.dx * q.xz + s.dz * q.zz, d.value, d.dx, d.dz);
    }

    long hash(int x, int z, long salt) {
        long h = (long) x * 374761393L + (long) z * 668265263L + salt + seed * 0x9E3779B9L;
        h = (h ^ (h >>> 16)) * 1274126177L;
        return h ^ (h >>> 16);
    }

    private static double unit(long h) { return (h & 0xFFFFFFL) / 16777216.0; }

    Site site(int x, int z) {
        long h = hash(x, z, SEED_SALT), v = hash(x, z, VELOCITY_SALT);
        double sx = (x + .5 + (unit(h) - .5) * .7) * L;
        double sz = (z + .5 + (unit(h >>> 24) - .5) * .7) * L;
        double angle = unit(v) * Math.PI * 2, magnitude = .3 + unit(v >>> 24) * .7;
        return new Site(x, z, sx, sz, Math.cos(angle) * magnitude, Math.sin(angle) * magnitude);
    }

    /** dx/dz是局部r的导数；原点直接使用多项式，其他站点投票不被截断。 */
    static Field projection(double rx, double rz, double vx, double vz) {
        double d2 = rx * rx + rz * rz, dot = vx * rx + vz * rz;
        if (d2 < R * R) {
            double f = (1.5 - d2 / (2 * R * R)) / R;
            double slope = dot / (R * R * R);
            return new Field(dot * f, vx * f - slope * rx, vz * f - slope * rz);
        }
        double d = Math.sqrt(d2), f = 1 / d, slope = dot / (d2 * d);
        return new Field(dot * f, vx * f - slope * rx, vz * f - slope * rz);
    }

    Vote stressAt(double x, double z) {
        finite(x, z);
        int bx = cell(x / L), bz = cell(z / L);
        double u = x / L - bx, v = z / L - bz;
        double hx = ease(u), hz = ease(v), dx = easeDerivative(u) / L, dz = easeDerivative(v) / L;
        double n = 0, d = 0, nx = 0, nz = 0, ddx = 0, ddz = 0;
        for (int ix = -2; ix <= 3; ix++) for (int iz = -2; iz <= 3; iz++) {
            double ax = ix == -2 ? 1 - hx : ix == 3 ? hx : 1;
            double az = iz == -2 ? 1 - hz : iz == 3 ? hz : 1;
            double dax = ix == -2 ? -dx : ix == 3 ? dx : 0;
            double daz = iz == -2 ? -dz : iz == 3 ? dz : 0;
            Site s = site(bx + ix, bz + iz);
            double rx = x - s.sx, rz = z - s.sz;
            double g = Math.exp(-(rx * rx + rz * rz) / (2 * SIGMA * SIGMA));
            double w = ax * az * g;
            double wx = g * (dax * az - ax * az * rx / (SIGMA * SIGMA));
            double wz = g * (ax * daz - ax * az * rz / (SIGMA * SIGMA));
            Field p = projection(rx, rz, s.vx, s.vz);
            n += w * p.value; d += w;
            nx += wx * p.value + w * p.dx; nz += wz * p.value + w * p.dz;
            ddx += wx; ddz += wz;
        }
        if (!(d >= Math.exp(-2.89)) || !Double.isFinite(d))
            throw new IllegalStateException("POU denominator=" + d);
        double raw = GAIN * n / d;
        return new Vote(raw, Math.max(-1, Math.min(1, raw)),
                GAIN * (nx * d - n * ddx) / (d * d), GAIN * (nz * d - n * ddz) / (d * d), n, d);
    }

    Warp warp(double x, double z) {
        finite(x, z);
        Field nx = noise(x * (1.0 / 400), z * (1.0 / 400), WARP_SALT);
        Field nz = noise(x * (1.0 / 400) + 17.3, z * (1.0 / 400) + 31.7, WARP_SALT + 1);
        return new Warp(x + 130 * nx.value, z + 130 * nz.value,
                1 + 130 * nx.dx / 400, 130 * nx.dz / 400,
                130 * nz.dx / 400, 1 + 130 * nz.dz / 400);
    }

    private Field noise(double x, double z, long salt) {
        int ix = cell(x), iz = cell(z);
        double[] wx = catmull(x - ix), wz = catmull(z - iz);
        double[] dx = catmullDerivative(x - ix), dz = catmullDerivative(z - iz);
        double[] cols = new double[4], colsZ = new double[4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            double c = unit(hash(ix + i - 1, iz + j - 1, salt)) * 2 - 1;
            cols[i] += c * wz[j]; colsZ[i] += c * dz[j];
        }
        double value = 0, vx = 0, vz = 0;
        for (int i = 0; i < 4; i++) {
            value += cols[i] * wx[i]; vx += cols[i] * dx[i]; vz += colsZ[i] * wx[i];
        }
        return new Field(value, vx, vz);
    }

    double rawDistance(double x, double z) {
        var q = warp(x, z);
        int bx = cell(q.x / L), bz = cell(q.z / L);
        double first = Double.POSITIVE_INFINITY, second = first;
        for (int ix = -2; ix <= 2; ix++) for (int iz = -2; iz <= 2; iz++) {
            Site s = site(bx + ix, bz + iz);
            double dx = q.x - s.sx, dz = q.z - s.sz;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < first) { second = first; first = d; }
            else if (d < second) second = d;
        }
        return (second - first) * .5;
    }

    /** Field.dx为B3一阶导，Field.dz为二阶导；此方法不接world域。 */
    static Field spline(double t) {
        double a = Math.abs(t), sign = Math.signum(t);
        if (a < 1) return new Field(2.0 / 3 - a * a + a * a * a / 2,
                sign * (-2 * a + 1.5 * a * a), -2 + 3 * a);
        if (a < 2) {
            double b = 2 - a;
            return new Field(b * b * b / 6, -sign * b * b / 2, b);
        }
        return new Field(0, 0, 0);
    }

    Field distance(double x, double z) {
        finite(x, z);
        int ix = cell(x / H), iz = cell(z / H);
        double value = 0, dx = 0, dz = 0;
        for (int i = -1; i <= 2; i++) for (int j = -1; j <= 2; j++) {
            Field a = spline(x / H - (ix + i)), b = spline(z / H - (iz + j));
            double coefficient = rawDistance((ix + i) * H, (iz + j) * H);
            value += a.value * b.value * coefficient;
            dx += a.dx * b.value * coefficient / H;
            dz += a.value * b.dx * coefficient / H;
        }
        return new Field(value, dx, dz);
    }

    private static double ease(double x) { return x * x * (3 - 2 * x); }
    private static double easeDerivative(double x) { return 6 * x * (1 - x); }
    private static double[] catmull(double t) {
        double t2 = t * t, t3 = t2 * t;
        return new double[]{-.5 * t3 + t2 - .5 * t, 1.5 * t3 - 2.5 * t2 + 1,
                -1.5 * t3 + 2 * t2 + .5 * t, .5 * t3 - .5 * t2};
    }
    private static double[] catmullDerivative(double t) {
        return new double[]{-1.5 * t * t + 2 * t - .5, 4.5 * t * t - 5 * t,
                -4.5 * t * t + 4 * t + .5, 1.5 * t * t - t};
    }
    private static int cell(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE + 4.0 || value > Integer.MAX_VALUE - 4.0)
            throw new IllegalArgumentException("unsupported coordinate=" + value);
        return (int) Math.floor(value);
    }
    private static void finite(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("nonfinite coordinate");
    }
}
