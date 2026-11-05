package com.geogenesis.worldgen.terrain.morph;

import java.util.Arrays;

/**
 * <b>双坐标求值器</b>：把 {@link MorphNoise} 的节点图用 double 坐标重算一遍。
 *
 * <h3>为什么要"再算一遍"而不是"把 float 结果拓宽"</h3>
 * <p>MC 的区块坐标上限约 3×10⁷ 块。float 在该量级的最小可分辨增量已超过 1 块，
 * 相邻块的噪声值会塌成同一个数 —— 于是远处地形变成阶梯/平台。本类保持
 * <b>同一公式、同一遍序、同一哈希</b>，只把坐标与中间量提升为 double，
 * 使远处仍能分辨相邻块。</p>
 *
 * <h3>★ 与 float 实现的逐位关系（必须记住）</h3>
 * <ul>
 *   <li><b>近处不逐位相同</b>：float 与 double 的舍入不同，因此本类的输出与
 *       {@link MorphNoise.Node#compute(float, float)} 在 double 意义上不相等。</li>
 *   <li><b>本类内部必须逐位确定</b>：同一 (x,z) 无论求值顺序、无论堆压力，结果必须相同。
 *       这是正典图可复现的前提（对拍靠整幅数组的 SHA-256）。</li>
 *   <li>所有节点求值都是纯函数、无缓存、无跨调用状态 —— 唯一的 {@code ThreadLocal}
 *       是 {@link MorphNoise.Erosion} 自己的 25 槽 scratch，它每次进入先整体填充，
 *       语义等价于局部变量。</li>
 * </ul>
 *
 * <h3>★ 未知节点必须失败，不许兜底</h3>
 * <p>遇到不认识的 {@link MorphNoise.Node} 实现直接抛
 * {@link UnsupportedOperationException}。历史教训：静默兜底（例如把未知节点当 0）
 * 会让整条链在"少算了一层"的情况下继续跑完并产出看似正常的图，无从察觉。</p>
 */
final class MorphNoiseEval {

    private MorphNoiseEval() { }

    /**
     * 用 double 坐标求值一个节点 —— <b>本包所有地形计算的统一入口</b>。
     *
     * <p>等价于 {@link #eval}，但方法名直说意图：本包的地形链恒走 double 坐标
     * （float 在远处会塌成同一格，见 {@link MorphNoiseEval} 的类注释）。</p>
     */
    static double precise(MorphNoise.Node node, double x, double z) {
        return eval(node, x, z);
    }

    // ============================================================ 数值工具（与 MorphNoise 同义）

    static double clamp(double x, double a, double b) { return x < a ? a : (x > b ? b : x); }

    /** {@code NoiseUtil.map}：先 clamp 到 [a,b]，再除以区间长度。 */
    static double map(double x, double a, double b) {
        double v = clamp(x, a, b) - a, r = b - a;
        return v >= r ? 1 : v / r;
    }

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** {@code NoiseUtil.interpHermite}（Interpolation.CURVE3）。 */
    static double herm(double x) { return x * x * (3 - 2 * x); }

    // ============================================================ 分派

    /**
     * 用 double 坐标求值一个节点。
     *
     * @throws IllegalArgumentException  坐标为 NaN / 无穷
     * @throws UnsupportedOperationException 节点类型不在覆盖集内
     */
    static double eval(MorphNoise.Node n, double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("nonfinite coordinate");
        }
        if (n instanceof MorphNoise.ScalarIdentity) return x;
        if (n instanceof MorphNoise.Perlin a) return perlin(a, x, z);
        if (n instanceof MorphNoise.PerlinRidge || n instanceof MorphNoise.Billow) return ridge(n, x, z);
        if (n instanceof MorphNoise.Cubic) return cubic(n, x, z);
        if (n instanceof MorphNoise.WorleyEdge || n instanceof MorphNoise.WorleyEdgeAdd) return worley(n, x, z);
        if (n instanceof MorphNoise.Constant a) return a.value();
        if (n instanceof MorphNoise.Add a) return eval(a.a(), x, z) + eval(a.b(), x, z);
        if (n instanceof MorphNoise.Mul a) return eval(a.a(), x, z) * eval(a.b(), x, z);
        if (n instanceof MorphNoise.AlphaMix a) return eval(a.input(), x, z) * a.alpha() + (1f - a.alpha());
        if (n instanceof MorphNoise.Warped a) {
            return eval(a.input(), x + (eval(a.wx(), x, z) - .5) * a.pow(),
                    z + (eval(a.wz(), x, z) - .5) * a.pow());
        }
        if (n instanceof MorphNoise.Clamped a) return clamp(eval(a.input(), x, z), a.min(), a.max());
        if (n instanceof MorphNoise.Mapped a) {
            return a.from() + (eval(a.alpha(), x, z) - a.lo()) / (a.hi() - a.lo()) * (a.to() - a.from());
        }
        if (n instanceof MorphNoise.Mix a) {
            double t = clamp(eval(a.alpha(), x, z), 0, 1);
            return eval(a.a(), x, z) * (1 - t) + eval(a.b(), x, z) * t;
        }
        if (n instanceof MorphNoise.Curve3 a) return herm(map(eval(a.input(), x, z), 0, 1));
        if (n instanceof MorphNoise.Power a) return Math.pow(eval(a.input(), x, z), a.power());
        if (n instanceof MorphNoise.PowerCurve a) {
            double v = eval(a.input(), x, z);
            double out = v >= a.mid()
                    ? a.mid() + Math.pow(v - a.mid(), a.power())
                    : a.mid() - Math.pow(a.mid() - v, a.power());
            return map(out, a.min(), a.max());
        }
        if (n instanceof MorphNoise.Inverted a) return a.max() - clamp(eval(a.input(), x, z), a.min(), a.max());
        if (n instanceof MorphNoise.Smoothstep a) {
            return herm(clamp((eval(a.input(), x, z) - a.edge0()) / (a.edge1() - a.edge0()), 0, 1));
        }
        if (n instanceof MorphNoise.Blend a) return blend(a, x, z);
        if (n instanceof MorphNoise.Steps a) return steps(a, x, z);
        if (n instanceof MorphNoise.DualNode d) return d.computeDouble(x, z);
        if (n instanceof MorphNoise.DirectionWarped a) {
            double angle = eval(a.direction(), x, z) * (double) 6.2831855f;
            return eval(a.input(), x + Math.sin(angle) * a.strength(), z + Math.cos(angle) * a.strength());
        }
        if (n instanceof MorphNoise.Erosion a) return erosion(a, x, z);
        if (n instanceof MorphNoise.Terrace a) return terrace(a, x, z);
        throw new UnsupportedOperationException("unsupported node: "
                + (n == null ? "null" : n.getClass().getName()));
    }

    // ============================================================ 逐算子

    /** {@code Perlin.compute}：多倍频梯度噪声，double 坐标下重算。 */
    private static double perlin(MorphNoise.Perlin a, double x, double z) {
        double freq = a.frequency(), lac = a.lacunarity(), gain = a.gain();
        int octaves = a.octaves(), seed = a.seed();
        x *= freq;
        z *= freq;
        double sum = 0, amp = gain;
        for (int i = 0; i < octaves; i++) {
            sum += sample(seed + i, x, z) * amp;
            x *= lac;
            z *= lac;
            amp *= gain;
        }
        return Math.max(0, Math.min(1, (sum - a.min()) / (a.max() - a.min())));
    }

    /**
     * 一倍频梯度的解析值（{@code MorphNoise.Perlin.sample} 的 double 版）。
     *
     * <p>四角梯度点积 → Hermite 权重 → 双线性 lerp，与 float 版同式同哈希。</p>
     *
     * <p><b>★ 格点索引必须走 {@link #lattice}，不许裸 {@code (int) Math.floor}</b>：
     * 裸转换在坐标超出 int 范围时按 Java 语义饱和到 {@code Integer.MAX_VALUE}
     * （而不是抛错），于是大片远处的不同坐标会塌到同一个格点 —— 产出看似正常、
     * 实际是阶梯。这与 {@link #cubicSample} / {@link #worley} 走同一守卫，
     * 保证"任何 lattice 索引都先验范围"。正常坐标下 {@code lattice} 与裸转换
     * 逐位相同（都是 {@code (int) Math.floor(v)}），故不改变已有产出。</p>
     */
    static double sample(int seed, double u, double v) {
        int x2 = lattice(u, 2), y2 = lattice(v, 2);
        double t = u - x2, s = v - y2;
        double[] g00 = grad(seed, x2, y2), g10 = grad(seed, x2 + 1, y2);
        double[] g01 = grad(seed, x2, y2 + 1), g11 = grad(seed, x2 + 1, y2 + 1);
        double d00 = g00[0] * t + g00[1] * s;
        double d10 = g10[0] * (t - 1) + g10[1] * s;
        double d01 = g01[0] * t + g01[1] * (s - 1);
        double d11 = g11[0] * (t - 1) + g11[1] * (s - 1);
        double xs = herm(t), ys = herm(s);
        double f0 = d00 + xs * (d10 - d00);
        double f2 = d01 + xs * (d11 - d01);
        return f0 + ys * (f2 - f0);
    }

    /** {@code RefNoise.GRAD_2D[hash & 7]}，哈希与 {@code MorphNoise.hash2D} 逐位同式。 */
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

    /**
     * {@code PerlinRidge} / {@code Billow}：同构，只有最后一步取反不同。
     *
     * <p>逐倍频的权重反馈 {@code weight = clamp(signal*amp, 0, 1)} 必须与 float 版同序，
     * 否则最高倍频的调制会走不同的分支。</p>
     */
    private static double ridge(MorphNoise.Node n, double x, double z) {
        int seed, oct;
        double freq, lac, gain, max;
        float[] spectral;
        if (n instanceof MorphNoise.PerlinRidge a) {
            seed = a.seed(); oct = a.octaves(); freq = a.frequency(); lac = a.lacunarity();
            gain = a.gain(); max = a.max(); spectral = a.spectral();
        } else {
            MorphNoise.Billow a = (MorphNoise.Billow) n;
            seed = a.seed(); oct = a.octaves(); freq = a.frequency(); lac = a.lacunarity();
            gain = a.gain(); max = a.max(); spectral = a.spectral();
        }
        x *= freq;
        z *= freq;
        double amp = 2, value = 0, weight = 1;
        for (int i = 0; i < oct; i++) {
            double s = 1 - Math.abs(sample(seed + i, x, z));
            s *= s;
            s *= weight;
            weight = clamp(s * amp, 0, 1);
            value += s * spectral[i];
            x *= lac;
            z *= lac;
            amp *= gain;
        }
        double result = map(value, 0, max);
        return n instanceof MorphNoise.Billow ? 1 - result : result;
    }

    /** {@code Cubic.compute}：双三次插值的值噪声。 */
    private static double cubic(MorphNoise.Node n, double x, double z) {
        MorphNoise.Cubic a = (MorphNoise.Cubic) n;
        int seed = a.seed(), oct = a.octaves();
        double lac = a.lacunarity(), gain = a.gain();
        x *= a.frequency();
        z *= a.frequency();
        double sum = cubicSample(seed, x, z), amp = 1;
        for (int i = 1; i < oct; i++) {
            x *= lac;
            z *= lac;
            amp *= gain;
            sum += cubicSample(seed + i, x, z) * amp;
        }
        return map(sum, a.min(), a.max());
    }

    private static double cubicSample(int seed, double x, double z) {
        int ix = lattice(x, 3), iz = lattice(z, 3);
        double[] row = new double[4];
        for (int j = -1; j <= 2; j++) {
            row[j + 1] = cub(val(seed, ix - 1, iz + j), val(seed, ix, iz + j),
                    val(seed, ix + 1, iz + j), val(seed, ix + 2, iz + j), x - ix);
        }
        return cub(row[0], row[1], row[2], row[3], z - iz) * (double) 0.44444445f;
    }

    private static double cub(double a, double b, double c, double d, double t) {
        double p = d - c - (a - b);
        return t * t * t * p + t * t * (a - b - p) + t * (c - a) + b;
    }

    private static double val(int seed, int x, int z) {
        int n = seed ^ (1619 * x) ^ (31337 * z);
        return (double) (n * n * n * 60493) / 2147483648.0;
    }

    /**
     * {@code WorleyEdge} / {@code WorleyEdgeAdd}：3×3 细胞边缘距离。
     *
     * <p>⚠ 这里是 <b>sqrt</b> 版（不是参考的平方距离版）。原因见 {@link MorphNoise.WorleyEdgeAdd}
     * 的类注释：该节点是共享底层，山形遮罩的阈值 0.55/0.78 就是按这个分布标定的。
     * 改成平方距离会让已实机认可的地形整体改变。</p>
     */
    private static double worley(MorphNoise.Node n, double x, double z) {
        int seed;
        double freq;
        if (n instanceof MorphNoise.WorleyEdgeAdd a) {
            seed = a.seed(); freq = a.frequency();
        } else {
            MorphNoise.WorleyEdge a = (MorphNoise.WorleyEdge) n;
            seed = a.seed(); freq = a.frequency();
        }
        x *= freq;
        z *= freq;
        int ix = lattice(x, 2), iz = lattice(z, 2);
        double d1 = Double.MAX_VALUE, d2 = d1;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                int cx = ix + i, cz = iz + j;
                float[] v = MorphCellTable.CELL[MorphNoise.hash2D(seed, cx, cz) & 255];
                double dx = cx + (double) v[0] - x, dz = cz + (double) v[1] - z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                } else if (d < d2) {
                    d2 = d;
                }
            }
        }
        if (n instanceof MorphNoise.WorleyEdge) return map(d2 - 1, -1, 1);
        return map(d2 + Math.max(1e-6f, d1) - 1, -1, 1.6f);
    }

    /** {@code Blend.compute}：按 alpha 在 lower/upper 之间过渡，区间外直接取一侧。 */
    private static double blend(MorphNoise.Blend a, double x, double z) {
        double center = a.alphaMin() + (a.alphaMax() - a.alphaMin()) * a.mid();
        double lo = Math.max(a.alphaMin(), center - a.range() / 2);
        double hi = Math.min(a.alphaMax(), center + a.range() / 2);
        double v = eval(a.alpha(), x, z);
        if (v < lo) return eval(a.lower(), x, z);
        if (v > hi) return eval(a.upper(), x, z);
        return lerp(eval(a.lower(), x, z), eval(a.upper(), x, z), (v - lo) / (hi - lo));
    }

    /**
     * {@code Steps.compute}：平顶 + 短连续斜坡（不是硬台阶）。
     *
     * <p>先取输入值 → 翻转（{@code nv = 1-v}）→ 量化到 {@code steps} 档 → 在档内用
     * {@code slopeMin..slopeMax} 的 map 做短斜坡 → 再翻回来。翻转是参考的真实做法，
     * 少了它台阶方向会整体反过来。</p>
     *
     * <p><b>量化前先夹到 [0,1]</b>：本算子的输入是"信号层标量"，正常就在 [0,1]；
     * 但一旦有非有限值或越界值进来，{@code (int)(nv*steps)} 会饱和成
     * {@code Integer.MIN/MAX_VALUE}，量化档位直接跳到一个荒谬的值而不是抛错。
     * 夹到 [0,1] 后 {@code (int)} 恒在 {@code [0, steps]} 内，行为可预测；
     * 正常输入下夹取不改变任何位。</p>
     */
    private static double steps(MorphNoise.Steps a, double x, double z) {
        int stepCount = a.steps();
        double nv = clamp(eval(a.input(), x, z), 0, 1);
        double range = a.slopeMax() - a.slopeMin();
        if (range <= 0) return (int) (nv * stepCount) / (double) stepCount;
        nv = 1 - nv;
        double base = (int) (nv * stepCount) / (double) stepCount;
        double t = map((nv - base) * stepCount, a.slopeMin(), a.slopeMax());
        return 1 - lerp(base, nv, a.useHermite() ? herm(t) : t);
    }

    /**
     * {@code Terrace.compute}：参考的台地算子。
     *
     * <p>把输入切成 {@code steps} 级，每级内部再分三段：低于 lowerBound 保持本级台面
     * （平顶）、超过 ramp 阈值向下一级抬升 {@code rampHeight}（缓坡）、超过 cliff 阈值
     * 直接 lerp 到下一级（陡崖）。{@code indexBySpacing} 决定台阶索引按
     * {@code v*n} 还是 {@code (v-inMin)/spacing} 分档。</p>
     */
    private static double terrace(MorphNoise.Terrace a, double x, double z) {
        int steps = Math.max(2, a.steps());
        double v = clamp(eval(a.input(), x, z), 0, 0.999999);
        double inMin = a.inMin(), inMax = a.inMax();
        double spacing = (inMax - inMin) / (steps - 1);
        int index = a.indexBySpacing()
                ? (int) Math.min(steps - 1, Math.floor((v - inMin) / spacing))
                : (int) Math.min(steps - 1, Math.floor(v * steps));
        double value = index * spacing;
        if (index == steps - 1) return value;
        double blendRange = a.blendRange(), ramp = a.ramp(), cliff = a.cliff(),
                rampHeight = a.rampHeight();
        double bound = (spacing - spacing * blendRange) / 2, lower = value - bound, upper = value + bound;
        if (v < lower) return value;
        if (v > upper) return value + spacing;
        double rampT = 1 - ramp * 0.5, cliffT = 1 - cliff * 0.5;
        double alpha = (v - lower) / (upper - lower), out = value;
        if (alpha > rampT) {
            double rampAlpha = (alpha - rampT) / (1 - rampT);
            out += spacing * rampAlpha * rampHeight;
        }
        if (alpha > cliffT) {
            double cliffAlpha = (alpha - cliffT) / (1 - cliffT);
            out = lerp(out, value + spacing, cliffAlpha);
        }
        return out;
    }

    // ============================================================ 侵蚀纹理

    /**
     * {@code Erosion.compute}：{@code lerp(erosionTexture, input(x,z), 1-strength)}。
     *
     * <p>注意遍历顺序：<b>先</b>取 input 的值，<b>再</b>算侵蚀纹理。侵蚀纹理内部还会以
     * 多个偏移点调用同一个 input —— 那些调用共享 25 槽 scratch，缓存键是
     * {@code (dx1+dx2, dy1+dy2)} ∈ [-2,2]²，恰好覆盖 5×5。顺序改了就改产出。</p>
     */
    private static double erosion(MorphNoise.Erosion n, double x, double z) {
        double strength = n.strength();
        double value = eval(n.input(), x, z);
        double texture = erosionValue(n, x, z, new double[25]);
        return lerp(texture, value, 1 - strength);
    }

    private static double erosionValue(MorphNoise.Erosion n, double x, double y, double[] scratch) {
        int oct = n.octaves(), seed = n.seed();
        double grid = n.gridSize(), amp = n.amplitude(), fall = n.distanceFallOff(),
                lac = n.lacunarity();
        double sum = 0, max = 0, gain = 1, distance = grid;
        for (int i = 0; i < oct; i++) {
            sum += singleErosion(n, seed, x, y, distance, scratch) * gain;
            max += gain;
            gain *= amp;
            distance *= fall;
            x *= lac;
            y *= lac;
        }
        return sum / max;
    }

    private static double singleErosion(MorphNoise.Erosion n, int seed, double x, double y,
                                        double grid, double[] scratch) {
        Arrays.fill(scratch, Double.NaN);
        int pix = (int) Math.floor(x / grid), piy = (int) Math.floor(y / grid);
        double minDist2 = Double.MAX_VALUE;
        for (int dy1 = -1; dy1 <= 1; dy1++) {
            for (int dx1 = -1; dx1 <= 1; dx1++) {
                int pax = pix + dx1, pay = piy + dy1;
                float[] v1 = MorphCellTable.CELL[MorphNoise.hash2D(seed, pax, pay) & 255];
                double ax = (pax + (double) v1[0]) * grid, ay = (pay + (double) v1[1]) * grid;
                double bx = ax, by = ay, lowest = Double.MAX_VALUE;
                for (int dy2 = -1; dy2 <= 1; dy2++) {
                    for (int dx2 = -1; dx2 <= 1; dx2++) {
                        int pbx = pax + dx2, pby = pay + dy2;
                        float[] v2 = (pbx == pax && pby == pay) ? v1
                                : MorphCellTable.CELL[MorphNoise.hash2D(seed, pbx, pby) & 255];
                        double cx = (pbx + (double) v2[0]) * grid, cy = (pby + (double) v2[1]) * grid;
                        double h = cachedHeight(n, dx1 + dx2, dy1 + dy2, cx, cy, scratch);
                        if (h < lowest) {
                            lowest = h;
                            bx = cx;
                            by = cy;
                        }
                    }
                }
                double d2 = segmentDistance2(x, y, ax, ay, bx, by);
                if (d2 < minDist2) minDist2 = d2;
            }
        }
        return clamp(Math.sqrt(minDist2) / grid, 0, 1);
    }

    private static double cachedHeight(MorphNoise.Erosion n, int dx, int dy, double px, double py,
                                       double[] scratch) {
        int index = (dy + 2) * 5 + (dx + 2);
        double v = scratch[index];
        if (Double.isNaN(v)) {
            v = eval(n.input(), px, py);
            scratch[index] = v;
        }
        return v;
    }

    private static double segmentDistance2(double px, double py, double ax, double ay,
                                           double bx, double by) {
        double padx = px - ax, pady = py - ay, badx = bx - ax, bady = by - ay;
        double paba = padx * badx + pady * bady, baba = badx * badx + bady * bady;
        double h = (paba == 0 && baba == 0) ? 0 : clamp(paba / baba, 0, 1);
        double dx = badx * h - padx, dy = bady * h - pady;
        return dx * dx + dy * dy;
    }

    // ============================================================ 格点安全

    /**
     * 噪声格点索引：先确认坐标在 int 范围内，再 floor。
     *
     * <p>{@code margin} 是该算子会向两侧探的格数（Worley 探 1、Cubic 探 2）。
     * 越界时<b>抛异常</b>而不是截断：截断会让远处地形静默塌成同一格，比崩溃更难发现。</p>
     */
    static int lattice(double v, int margin) {
        if (!Double.isFinite(v) || v <= Integer.MIN_VALUE + margin || v >= Integer.MAX_VALUE - margin) {
            throw new IllegalArgumentException("noise lattice outside int range: " + v);
        }
        return (int) Math.floor(v);
    }
}
