package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;

/**
 * <b>N2 连续几何原语（仅诊断，未接高度链、未接生产、不替换 {@link RegionPartition}）</b>。
 *
 * <p>把分区 warp 实际用到的那一个噪声原语，用 <b>double 坐标</b>重新求值：
 * 同 GRAD 表、同整数哈希（含 32 位溢出与算术右移）、同 Hermite 曲线、同 seed、
 * 同归一化（{@code gain} 与 {@code mapClamped} 的 min/max <b>反射自</b>
 * {@link RefNoise.Perlin}，不复制字面量）。</p>
 *
 * <h3>为什么只做一倍频</h3>
 * 分区 warp 用的是 {@code Perlin.ofScale(seed, 400f, 1)}（1 倍频、gain .5）。若把倍频数也
 * 搬进来，就必须同时证明多倍频的相位与 float 版一致，超出 N2「只测前向/噪声/Jacobian」的范围。
 * 构造期校验 {@code octaves == 1}，不是 1 就抛错 —— 将来若分区改用多倍频，这里会<b>立刻失败</b>
 * 而不是静默给出不同的场。
 *
 * <h3>数值约定</h3>
 * <ul>
 *   <li>{@code frequency} 用 float 倒数的<b>原位模式</b>提升（{@code (double)(1f/scale)}），
 *       不用 {@code 1.0/scale} —— 否则"换精度"会同时变成"换有效频率"，两个变量混在一起。</li>
 *   <li>格点索引由 {@code floor(u)} 得到（负坐标同样是 floor，不是截断），哈希用全局整数。</li>
 *   <li>梯度解析式见 {@link #sampleValueAndGradient}，在一格内是 (t,s) 的 ≤3/≤4 次多项式。</li>
 * </ul>
 *
 * <p><b>⛔ 未证事项</b>：world↦u 的单射性<b>没有</b>被证明（本类只提供原语与导数，见 check 的
 * injectivity 段：可证的单轴界 &lt; 1，但二维 Banach 条件不满足）。不得据此宣称"无限世界已正确"。</p>
 */
final class GeometryTransform {

    /** 与几何同一常量，不另写数值。 */
    static final double STRENGTH = LocalBoundaryGeometry.WARP_STRENGTH;

    private final Axis warpX;
    private final Axis warpZ;
    private final double frequency;

    private GeometryTransform(Axis warpX, Axis warpZ, double frequency) {
        this.warpX = warpX;
        this.warpZ = warpZ;
        this.frequency = frequency;
    }

    // ============================================================ 构造

    /** 从真实分区实例取 warpX/warpZ 的 seed 与参数（只读反射），保证与原几何同源。 */
    static GeometryTransform of(RegionPartition partition) {
        RefNoise.Node nx = node(partition, "warpX");
        RefNoise.Node nz = node(partition, "warpZ");
        Axis ax = Axis.of(nx, "warpX");
        Axis az = Axis.of(nz, "warpZ");
        if (ax.frequency() != az.frequency()) {
            throw new IllegalStateException("warpX/warpZ frequencies differ: "
                    + ax.frequency() + " vs " + az.frequency() + " (cell grid would be ambiguous)");
        }
        double f = (double) (Float) read(partition, "frequency");
        return new GeometryTransform(ax, az, f);
    }

    static GeometryTransform of(int seedX, int seedZ, float scale) {
        return new GeometryTransform(Axis.ofSeed(seedX, scale), Axis.ofSeed(seedZ, scale),
                (double) (1f / scale));
    }

    // ============================================================ 前向

    /** 位移 {@code (ox, oz) = (n−0.5)·200}，与生产同式，只是坐标不再降 float。 */
    double[] offset(double x, double z) {
        return new double[]{(warpX.normalized(x, z) - 0.5) * STRENGTH,
                (warpZ.normalized(x, z) - 0.5) * STRENGTH};
    }

    /** {@code {ox, oz, dox/dx, dox/dz, doz/dx, doz/dz}}（world 单位）。 */
    double[] offsetAndJacobian(double x, double z) {
        double[] a = warpX.normalizedAndGradient(x, z);
        double[] b = warpZ.normalizedAndGradient(x, z);
        return new double[]{(a[0] - 0.5) * STRENGTH, (b[0] - 0.5) * STRENGTH,
                a[1] * STRENGTH, a[2] * STRENGTH, b[1] * STRENGTH, b[2] * STRENGTH};
    }

    /** {@code (u,v) = ((x+ox)·f, (z+oz)·f)}。 */
    double[] forward(double x, double z) {
        double[] o = offset(x, z);
        return new double[]{(x + o[0]) * frequency, (z + o[1]) * frequency};
    }

    /** {@code {u, v, du/dx, du/dz, dv/dx, dv/dz}}（u 每块）。 */
    double[] forwardAndJacobian(double x, double z) {
        double[] j = offsetAndJacobian(x, z);
        return new double[]{(x + j[0]) * frequency, (z + j[1]) * frequency,
                (1 + j[2]) * frequency, j[3] * frequency, j[4] * frequency, (1 + j[5]) * frequency};
    }

    double frequency() {
        return frequency;
    }

    /**
     * <b>warp 噪声</b>的频率（world → 噪声格）。
     *
     * <p>⚠ 与 {@link #frequency()} <b>不是同一个东西</b>：分区频率把 world 换成 u（细胞单位，
     * 1/600），噪声频率把 world 换成噪声格（{@code WARP_SCALE}=400 的倒数）。
     * 二者比值 600/400 = 1.5 ⇒ <b>一个 u 格跨 1.5 个噪声格</b>，梯度四角并不固定。
     * 因此"整格梯度固定"的包围必须以<b>噪声格</b>为单位，不能拿 u 格冒充。</p>
     */
    double warpFrequency() {
        return warpX.frequency();
    }

    /**
     * 归一化偏导 → world 偏导的比例 {@code 200·gain/(max−min)·frequency}，
     * <b>由反射到的节点参数算出</b>（不在别处复制 0.5/0.9/400 这些字面量）。
     */
    double derivativeScale() {
        return STRENGTH * warpX.normalizedDerivativeScale();
    }

    int seedX() {
        return warpX.seed();
    }

    int seedZ() {
        return warpZ.seed();
    }

    /** 归一化是否曾撞上 clamp 平台（一倍频下不该发生；发生即影响导数分段）。 */
    boolean clampReached(double x, double z) {
        return warpX.clamped(x, z) || warpZ.clamped(x, z);
    }

    // ============================================================ 原语

    /**
     * 一倍频 Perlin 的 {@code (值, ∂/∂u, ∂/∂v)}（<b>未</b>乘 gain、<b>未</b>归一化，值域约 ±0.5）。
     *
     * <p>与 {@code RefNoise.Perlin.sample(u,v,seed,true)} 同一算式，只把 float 换成 double：
     * 四角梯度点积 → Hermite 权重 → 双线性 lerp。导数按乘积法则解析求，含
     * {@code dhermite(0)=dhermite(1)=0} 带来的跨界 C¹。</p>
     */
    static double[] sampleValueAndGradient(int seed, double u, double v) {
        double fu = Math.floor(u), fv = Math.floor(v);
        int x2 = (int) fu, y2 = (int) fv;
        double t = u - x2, s = v - y2;
        double[] g00 = grad(seed, x2, y2), g10 = grad(seed, x2 + 1, y2);
        double[] g01 = grad(seed, x2, y2 + 1), g11 = grad(seed, x2 + 1, y2 + 1);
        double d00 = g00[0] * t + g00[1] * s;
        double d10 = g10[0] * (t - 1) + g10[1] * s;
        double d01 = g01[0] * t + g01[1] * (s - 1);
        double d11 = g11[0] * (t - 1) + g11[1] * (s - 1);
        double xs = t * t * (3 - 2 * t), ys = s * s * (3 - 2 * s);
        double dxs = 6 * t * (1 - t), dys = 6 * s * (1 - s);
        double f0 = d00 + xs * (d10 - d00);
        double f2 = d01 + xs * (d11 - d01);
        double value = f0 + ys * (f2 - f0);
        double a = g00[0] + dxs * (d10 - d00) + xs * (g10[0] - g00[0]);
        double b = g01[0] + dxs * (d11 - d01) + xs * (g11[0] - g01[0]);
        double du = a + ys * (b - a);
        double s0 = g00[1] + xs * (g10[1] - g00[1]);
        double s2 = g01[1] + xs * (g11[1] - g01[1]);
        double dv = s0 + ys * (s2 - s0) + dys * (f2 - f0);
        return new double[]{value, du, dv};
    }

    /** {@code RefNoise.GRAD_2D[hash & 7]}，哈希与 {@code RefNoise.Perlin.grad} 逐位同式。 */
    private static double[] grad(int seed, int x, int y) {
        int hash = seed;
        hash ^= 1619 * x;
        hash ^= 31337 * y;
        hash = hash * hash * hash * 60493;
        hash ^= hash >> 13;
        return GRAD[hash & 7];
    }

    private static final double[][] GRAD = {
            {-1, -1}, {1, -1}, {-1, 1}, {1, 1}, {0, -1}, {-1, 0}, {0, 1}, {1, 0}
    };

    // ============================================================ 单轴

    /** 一个 warp 轴：参数反射自 float 节点，求值走 double。 */
    static final class Axis {
        private final int seed;
        private final double frequency, gain, min, max;

        private Axis(int seed, double frequency, double gain, double min, double max) {
            this.seed = seed;
            this.frequency = frequency;
            this.gain = gain;
            this.min = min;
            this.max = max;
        }

        static Axis ofSeed(int seed, float scale) {
            return of(RefNoise.Perlin.ofScale(seed, scale, 1), "ofSeed");
        }

        /** 只读反射 float 节点：octaves 必须是 1，否则拒绝（见类注释）。 */
        static Axis of(RefNoise.Node node, String name) {
            if (!(node instanceof RefNoise.Perlin)) {
                throw new IllegalStateException(name + " is not a RefNoise.Perlin: " + node);
            }
            int octaves = (Integer) read(node, "octaves");
            if (octaves != 1) {
                throw new IllegalStateException(name + " has " + octaves
                        + " octaves; the N2 continuous primitive covers exactly 1");
            }
            return new Axis((Integer) read(node, "seed"), (Float) read(node, "frequency"),
                    (Float) read(node, "gain"), (Float) read(node, "min"), (Float) read(node, "max"));
        }

        int seed() {
            return seed;
        }

        double frequency() {
            return frequency;
        }

        /** {@code gain/(max−min)·frequency}：把 {@code ∂sample/∂u} 换成 world 单位归一化偏导。 */
        double normalizedDerivativeScale() {
            return gain / (max - min) * frequency;
        }

        /** {@code n = mapClamped(gain·sample, min, max)}，与 {@code RefNoise.mapClamped} 同判据。 */
        double normalized(double x, double z) {
            return normalizedAndGradient(x, z)[0];
        }

        /** {@code {n, dn/dx, dn/dz}}（world 单位）。clamp 平台内导数为 0，与分段一致。 */
        double[] normalizedAndGradient(double x, double z) {
            double[] d = sampleValueAndGradient(seed, x * frequency, z * frequency);
            double sum = gain * d[0];
            double range = max - min;
            double clamped = clamp(sum);
            double dif = clamped - min;
            double n = dif >= range ? 1.0 : dif / range;
            if (clamped != sum) {
                return new double[]{n, 0.0, 0.0};
            }
            double k = gain * frequency / range;
            return new double[]{n, d[1] * k, d[2] * k};
        }

        boolean clamped(double x, double z) {
            double sum = gain * sampleValueAndGradient(seed, x * frequency, z * frequency)[0];
            return sum < min || sum > max;
        }

        private double clamp(double v) {
            return v < min ? min : (v > max ? max : v);
        }
    }

    // ============================================================ 反射

    private static RefNoise.Node node(Object owner, String name) {
        Object v = read(owner, name);
        if (!(v instanceof RefNoise.Node n)) {
            throw new IllegalStateException(name + " is not a RefNoise.Node: " + v);
        }
        return n;
    }

    private static Object read(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // 继续向父类找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("reflect " + name, e);
            }
        }
        throw new IllegalStateException("no field '" + name + "' on " + target.getClass().getName());
    }
}
