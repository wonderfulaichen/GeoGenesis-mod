package com.geogenesis.worldgen.terrain.candidate;

/**
 * 参考项目（FreeTerraForged 1.21.1 {@code noise} 包）算子的<b>原样实现</b>，供各候选地貌复刻共用。
 *
 * <h3>为什么需要它</h3>
 * 本项目只有 Simplex，参考用 Perlin / PerlinRidge / DomainWarp。要做"逐行复刻"而不是
 * "看着像就行"，就必须按参考的原实现重写这几个原语：同 GRAD 表、同哈希、同 float 精度、
 * 同归一化上界。
 *
 * <h3>流式约束（用户 2026-10-29 补充）</h3>
 * 本项目按块实时生成，不是烘焙整图。故本类所有节点必须满足：
 * <ul>
 *   <li><b>纯函数</b>：输出只由 (x,z) 与 seed 决定，不依赖遍历顺序、不读全局状态；</li>
 *   <li><b>无整图归一化</b>：归一化只用解析上界（如 Perlin 的 SIGNALS 推导），不采样全图求 min/max；</li>
 *   <li><b>无跨块缓存</b>：任何记忆化若存在，必须与调用顺序无关且可逐位复现。</li>
 * </ul>
 *
 * <p>与参考的唯一有意差异：参考的 PerlinRidge 用 {@code 1-|n|} 在 n=0 处留 V 形折痕，
 * 本项目铁律要求圆化，故 {@link PerlinRidge} 提供 {@code roundCrest} 开关；默认关闭，
 * 以保持"复刻"口径。</p>
 */
abstract class RefNoise {

    private RefNoise() { }

    /** 8 方向梯度表，顺序与 {@code NoiseUtil.GRAD_2D:233} 一致。 */
    private static final float[][] GRAD_2D = {
            {-1f, -1f}, {1f, -1f}, {-1f, 1f}, {1f, 1f},
            {0f, -1f}, {-1f, 0f}, {0f, 1f}, {1f, 0f}
    };

    /** {@code Perlin.SIGNALS:20-22}：按倍频数取信号上界。 */
    private static final float[] SIGNALS = {1.0f, 0.9f, 0.83f, 0.75f, 0.64f, 0.62f, 0.61f};

    interface Node {
        float compute(float x, float z);
    }

    record Constant(float value) implements Node {
        @Override public float compute(float x, float z) { return value; }
    }

    /** {@code Perlin.java:29-42}：多倍频梯度噪声，输出 [0,1]。 */
    static final class Perlin implements Node {
        private final int seed;
        private final float frequency, lacunarity, gain;
        private final int octaves;
        private final float min, max;

        Perlin(int seed, float frequency, int octaves, float lacunarity, float gain) {
            this.seed = seed;
            this.frequency = frequency;
            this.octaves = octaves;
            this.lacunarity = lacunarity;
            this.gain = gain;
            this.max = maxBound(octaves, gain);
            this.min = -this.max;
        }

        /** {@code Noises.perlin(seed, scale, octaves)}：lacunarity=2, gain=0.5。 */
        static Perlin ofScale(int seed, float scale, int octaves) {
            return new Perlin(seed, 1f / scale, octaves, 2f, 0.5f);
        }

        /** {@code Noises.perlin(seed, scale, octaves, lacunarity)}：gain=0.5。 */
        static Perlin ofScale(int seed, float scale, int octaves, float lacunarity) {
            return new Perlin(seed, 1f / scale, octaves, lacunarity, 0.5f);
        }

        @Override public float compute(float x, float z) {
            x *= frequency;
            z *= frequency;
            float sum = 0f, amp = gain;
            for (int i = 0; i < octaves; i++) {
                sum += sample(x, z, seed + i, true) * amp;
                x *= lacunarity;
                z *= lacunarity;
                amp *= gain;
            }
            return mapClamped(sum, min, max);
        }

        /** {@code Perlin.sample:64-78}（hermite = Interpolation.CURVE3）。 */
        static float sample(float x, float y, int seed, boolean hermite) {
            int x2 = floor(x), y2 = floor(y);
            int x3 = x2 + 1, y3 = y2 + 1;
            float xd0 = x - x2, yd0 = y - y2;
            float xd2 = xd0 - 1f, yd2 = yd0 - 1f;
            float xs = hermite ? hermite(xd0) : xd0;
            float ys = hermite ? hermite(yd0) : yd0;
            float xf0 = lerp(grad(seed, x2, y2, xd0, yd0), grad(seed, x3, y2, xd2, yd0), xs);
            float xf2 = lerp(grad(seed, x2, y3, xd0, yd2), grad(seed, x3, y3, xd2, yd2), xs);
            return lerp(xf0, xf2, ys);
        }

        private static float grad(int seed, int x, int y, float xd, float yd) {
            int hash = seed;
            hash ^= 1619 * x;
            hash ^= 31337 * y;
            hash = hash * hash * hash * 60493;
            hash ^= hash >> 13;
            float[] g = GRAD_2D[hash & 7];
            return xd * g[0] + yd * g[1];
        }

        private static float maxBound(int octaves, float gain) {
            float signal = SIGNALS[Math.min(octaves, SIGNALS.length - 1)];
            float sum = 0f, amp = gain;
            for (int i = 0; i < octaves; i++) {
                sum += signal * amp;
                amp *= gain;
            }
            return sum;
        }
    }

    /** {@code PerlinRidge.java:32-51}：带权重反馈的脊线，输出 [0,1]。 */
    static final class PerlinRidge implements Node {
        private final int seed;
        private final float frequency, lacunarity, gain;
        private final int octaves;
        private final float[] spectral;
        private final float max;

        PerlinRidge(int seed, float frequency, int octaves, float lacunarity, float gain) {
            this.seed = seed;
            this.frequency = frequency;
            this.octaves = Math.min(octaves, 30);
            this.lacunarity = lacunarity;
            this.gain = gain;
            this.spectral = new float[this.octaves];
            float f = 1f;
            for (int i = 0; i < this.octaves; i++) {
                this.spectral[i] = 1f / f;
                f *= lacunarity;
            }
            this.max = maxBound(this.spectral, gain);
        }

        /** {@code Noises.perlinRidge(seed, scale, octaves)}：lacunarity=2, gain=0.975。 */
        static PerlinRidge ofScale(int seed, float scale, int octaves) {
            return new PerlinRidge(seed, 1f / scale, octaves, 2f, 0.975f);
        }

        @Override public float compute(float x, float z) {
            x *= frequency;
            z *= frequency;
            float amp = 2f, value = 0f, weight = 1f;
            for (int i = 0; i < octaves; i++) {
                float signal = 1f - Math.abs(Perlin.sample(x, z, seed + i, true));
                signal *= signal;
                signal *= weight;
                weight = clamp(signal * amp, 0f, 1f);
                value += signal * spectral[i];
                x *= lacunarity;
                z *= lacunarity;
                amp *= gain;
            }
            return mapClamped(value, 0f, max);
        }

        private static float maxBound(float[] spectral, float gain) {
            float amp = 2f, value = 0f, weight = 1f;
            for (float w : spectral) {
                float noise = weight;
                weight = clamp(noise * amp, 0f, 1f);
                value += noise * w;
                amp *= gain;
            }
            return value;
        }
    }

    /** {@code Warp} + {@code DomainWarp}：偏移 = (perlin01 − 0.5) × pow。 */
    record Warped(Node input, Node wx, Node wz, float pow) implements Node {
        @Override public float compute(float x, float z) {
            float ox = (wx.compute(x, z) - 0.5f) * pow;
            float oz = (wz.compute(x, z) - 0.5f) * pow;
            return input.compute(x + ox, z + oz);
        }
    }

    /** {@code Noises.warp(input, warpX, warpZ, pow)}：直接吃两条已建好的噪声。 */
    static Node warp(Node input, Node wx, Node wz, float pow) {
        return new Warped(input, wx, wz, pow);
    }

    /** {@code Noises.warpPerlin(input, seed, scale, octaves, pow)}。 */
    static Node warpPerlin(Node input, int seed, float scale, int octaves, float pow) {
        return new Warped(input, Perlin.ofScale(seed, scale, octaves),
                Perlin.ofScale(seed + 1, scale, octaves), pow);
    }

    /** {@code Clamp}。 */
    record Clamped(Node input, float min, float max) implements Node {
        @Override public float compute(float x, float z) { return clamp(input.compute(x, z), min, max); }
    }

    /** {@code Map}：按 alpha 的 [minValue,maxValue] 归一化后线性映射到 [from,to]。 */
    record Mapped(Node alpha, float lo, float hi, float from, float to) implements Node {
        @Override public float compute(float x, float z) {
            float a = (alpha.compute(x, z) - lo) / (hi - lo);
            return from + a * (to - from);
        }
    }

    record Mul(Node a, Node b) implements Node {
        @Override public float compute(float x, float z) { return a.compute(x, z) * b.compute(x, z); }
    }

    record Add(Node a, Node b) implements Node {
        @Override public float compute(float x, float z) { return a.compute(x, z) + b.compute(x, z); }
    }

    /** {@code Alpha.java:14-18}：{@code input*alpha + (1-alpha)}。 */
    record AlphaMix(Node input, float alpha) implements Node {
        @Override public float compute(float x, float z) {
            return input.compute(x, z) * alpha + (1f - alpha);
        }
    }

    /**
     * 按 alpha 节点在两个兄弟节点之间线性插值：{@code lerp(a, b, alpha)}。
     *
     * <p>与 {@link Blend} 的区别：{@code Blend} 以 mid/range 定义过渡带（超出即取某一侧），
     * 适合"两质地并存、边界有限支持"；{@code Mix} 直接用 alpha 自身取值，alpha 由调用方
     * 先经 smoothstep 塑形，适合"把某片区域压成平面"这类替换语义。参考没有这个节点，
     * 是本项目为盆地盐滩补的。</p>
     */
    record Mix(Node a, Node b, Node alpha) implements Node {
        @Override public float compute(float x, float z) {
            float t = clamp(alpha.compute(x, z), 0f, 1f);
            return a.compute(x, z) * (1f - t) + b.compute(x, z) * t;
        }
    }

    /**
     * {@code NoiseUtil.cubicLerp} 已单独提供；这里是 {@code Noises.curve(CURVE3)} 的等价物：
     * 把输入按 [0,1] 归一后过 hermite。
     */
    record Curve3(Node input) implements Node {
        @Override public float compute(float x, float z) {
            float v = mapClamped(input.compute(x, z), 0f, 1f);
            return hermite(v);
        }
    }

    /** {@code Steps.java:20-34}：平顶 + 短连续斜坡（不是硬台阶）。 */
    record Steps(Node input, int steps, float slopeMin, float slopeMax, boolean useHermite) implements Node {
        @Override public float compute(float x, float z) {
            float nv = input.compute(x, z);
            float range = slopeMax - slopeMin;
            if (range <= 0f) return (int) (nv * steps) / (float) steps;
            nv = 1f - nv;
            float value = (int) (nv * steps) / (float) steps;
            // 参考是 float 运算（Steps.java:31-32）。改成 double 会改变末位 ⇒ 逐位不等的复刻。
            float delta = nv - value;
            float alpha = mapClamped(delta * steps, slopeMin, slopeMax);
            float curve = useHermite ? hermite(alpha) : alpha;
            return 1f - lerp(value, nv, curve);
        }
    }

    /**
     * {@code Billow.java:31-51}：与 PerlinRidge 同构，但取 {@code 1-|sample|} 再平方后
     * <b>整体取反</b>，得到"云团状"丘体（参考用它做丘陵的振幅包络）。
     */
    static final class Billow implements Node {
        private final int seed;
        private final float frequency, lacunarity, gain;
        private final int octaves;
        private final float[] spectral;
        private final float max;

        Billow(int seed, float frequency, int octaves, float lacunarity, float gain) {
            this.seed = seed;
            this.frequency = frequency;
            this.octaves = Math.min(octaves, 30);
            this.lacunarity = lacunarity;
            this.gain = gain;
            this.spectral = new float[this.octaves];
            float f = 1f;
            for (int i = 0; i < this.octaves; i++) {
                this.spectral[i] = 1f / f;
                f *= lacunarity;
            }
            this.max = bound(this.spectral, gain);
        }

        /** {@code Noises.billow(seed, scale, octaves[, lacunarity[, gain]])}。 */
        static Billow ofScale(int seed, float scale, int octaves, float lacunarity, float gain) {
            return new Billow(seed, 1f / scale, octaves, lacunarity, gain);
        }

        @Override public float compute(float x, float z) {
            x *= frequency;
            z *= frequency;
            float amp = 2f, value = 0f, weight = 1f;
            for (int i = 0; i < octaves; i++) {
                float signal = 1f - Math.abs(Perlin.sample(x, z, seed + i, true));
                signal *= signal;
                signal *= weight;
                weight = clamp(signal * amp, 0f, 1f);
                value += signal * spectral[i];
                x *= lacunarity;
                z *= lacunarity;
                amp *= gain;
            }
            // 注意 Billow 是 1 - map(...)，与 PerlinRidge 的 map(...) 相反。
            return 1f - mapClamped(value, 0f, max);
        }

        private static float bound(float[] spectral, float gain) {
            float amp = 2f, value = 0f, weight = 1f;
            for (float w : spectral) {
                float noise = weight;
                weight = clamp(noise * amp, 0f, 1f);
                value += noise * w;
                amp *= gain;
            }
            return value;
        }
    }

    /** {@code Cubic.java:9-70}：双三次插值的值噪声，输出 [0,1]。 */
    static final class Cubic implements Node {
        private final int seed;
        private final float frequency, lacunarity, gain;
        private final int octaves;
        private final float min, max;

        Cubic(int seed, float frequency, int octaves, float lacunarity, float gain) {
            this.seed = seed;
            this.frequency = frequency;
            this.octaves = octaves;
            this.lacunarity = lacunarity;
            this.gain = gain;
            this.max = bound(0.75f, octaves, gain);
            this.min = bound(-0.75f, octaves, gain);
        }

        static Cubic ofScale(int seed, float scale, int octaves, float lacunarity, float gain) {
            return new Cubic(seed, 1f / scale, octaves, lacunarity, gain);
        }

        @Override public float compute(float x, float z) {
            x *= frequency;
            z *= frequency;
            float sum = sample(x, z, seed);
            float amplifier = 1f;
            for (int i = 1; i < octaves; i++) {
                x *= lacunarity;
                z *= lacunarity;
                amplifier *= gain;
                sum += sample(x, z, seed + i) * amplifier;
            }
            return mapClamped(sum, min, max);
        }

        /** {@code Cubic.sample:48-60}。 */
        static float sample(float x, float y, int seed) {
            int x2 = floor(x), y2 = floor(y);
            int x3 = x2 - 1, y3 = y2 - 1;
            int x4 = x2 + 1, y4 = y2 + 1;
            int x5 = x2 + 2, y5 = y2 + 2;
            float xs = x - x2, ys = y - y2;
            return cubicLerp(
                    cubicLerp(val(seed, x3, y3), val(seed, x2, y3), val(seed, x4, y3), val(seed, x5, y3), xs),
                    cubicLerp(val(seed, x3, y2), val(seed, x2, y2), val(seed, x4, y2), val(seed, x5, y2), xs),
                    cubicLerp(val(seed, x3, y4), val(seed, x2, y4), val(seed, x4, y4), val(seed, x5, y4), xs),
                    cubicLerp(val(seed, x3, y5), val(seed, x2, y5), val(seed, x4, y5), val(seed, x5, y5), xs),
                    ys) * 0.44444445f;
        }

        /** {@code NoiseUtil.valCoord2D:173-178}。 */
        private static float val(int seed, int x, int y) {
            int n = seed;
            n ^= 1619 * x;
            n ^= 31337 * y;
            return n * n * n * 60493 / 2.14748365E9F;
        }

        private static float bound(float signal, int octaves, float gain) {
            float amp = 1f, value = signal;
            for (int i = 1; i < octaves; i++) {
                amp *= gain;
                value += signal * amp;
            }
            return value;
        }
    }

    /** {@code NoiseUtil.cubicLerp:105-107}。 */
    static float cubicLerp(float a, float b, float c, float d, float t) {
        float p = d - c - (a - b);
        return t * t * t * p + t * t * (a - b - p) + t * (c - a) + b;
    }

    /** {@code Power.java:15-18}：直接幂。 */
    record Power(Node input, float power) implements Node {
        @Override public float compute(float x, float z) {
            return (float) Math.pow(input.compute(x, z), power);
        }
    }

    /**
     * {@code PowerCurve.java:33-44}：以中点为中心的对称幂曲线。
     * <p>与 {@link Power} 不同：它在 mid 附近平滑，只重塑两端，不会把 0 附近拉成奇点。</p>
     */
    record PowerCurve(Node input, float power, float mid, float min, float max) implements Node {
        static PowerCurve of(Node input, float power, float inMin, float inMax) {
            float mid = inMin + (inMax - inMin) / 2f;
            float lo = mid - (float) Math.pow(mid - inMin, power);
            float hi = mid + (float) Math.pow(inMax - mid, power);
            return new PowerCurve(input, power, mid, lo, hi);
        }

        @Override public float compute(float x, float z) {
            float v = input.compute(x, z);
            float out = v >= mid
                    ? mid + (float) Math.pow(v - mid, power)
                    : mid - (float) Math.pow(mid - v, power);
            return mapClamped(out, min, max);
        }
    }

    /**
     * {@code Blend.java:31-44}：按 alpha 在 lower/upper 之间过渡。
     * <p>区间由 {@code mid}（0..1 的比例）与 {@code range} 决定，边界外直接取一侧，不插值。</p>
     */
    record Blend(Node alpha, Node lower, Node upper, float mid, float range,
                 float alphaMin, float alphaMax) implements Node {
        @Override public float compute(float x, float z) {
            float center = alphaMin + (alphaMax - alphaMin) * mid;
            float lo = Math.max(alphaMin, center - range / 2f);
            float hi = Math.min(alphaMax, center + range / 2f);
            float a = alpha.compute(x, z);
            if (a < lo) return lower.compute(x, z);
            if (a > hi) return upper.compute(x, z);
            return lerp(lower.compute(x, z), upper.compute(x, z), (a - lo) / (hi - lo));
        }
    }

    /**
     * {@code WorleyEdge.java:19-81}：细胞边缘噪声。输出 =
     * {@code map(d2 − 1, −1, 1, 2)}，即 {@code clamp(d2, 0, 2) / 2}。
     * <p>参考用它做 {@code mountains_2/3} 的"山块"轮廓：细胞内部接近 0、边缘接近 1。</p>
     */
    static final class WorleyEdge implements Node {
        private final int seed;
        private final float frequency;

        WorleyEdge(int seed, float frequency) {
            this.seed = seed;
            this.frequency = frequency;
        }

        static WorleyEdge ofScale(int seed, float scale) {
            return new WorleyEdge(seed, 1f / scale);
        }

        @Override public float compute(float x, float z) {
            x *= frequency;
            z *= frequency;
            int xi = floor(x), yi = floor(z);
            float nearest1 = Float.MAX_VALUE, nearest2 = Float.MAX_VALUE;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = xi + dx, cy = yi + dy;
                    float[] vec = RegionCellTable.CELL[hash2D(seed, cx, cy) & 0xFF];
                    // distance 参数 = 1.0（参考 WorleyEdge 的 distance 字段默认 1）
                    float ddx = cx + vec[0] - x;
                    float ddy = cy + vec[1] - z;
                    float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy); // DistanceFunction.EUCLIDEAN
                    if (dist < nearest1) {
                        nearest2 = nearest1;
                        nearest1 = dist;
                    } else if (dist < nearest2) {
                        nearest2 = dist;
                    }
                }
            }
            nearest1 = Math.max(1.0E-6f, nearest1);
            float result = nearest2 - 1f;    // EdgeFunction.DISTANCE_2
            return mapClamped(result, -1f, 1f);
        }
    }

    /**
     * {@code WorleyEdge} + {@code EdgeFunction.DISTANCE_2_ADD}：{@code d1 + d2 − 1}，
     * 归一到 {@code [-1, 1.6] → [0, 1]}。
     *
     * <p>参考的 {@code Heightmap.java:115} 用的是这一种（不是 {@link WorleyEdge} 里的
     * {@code DISTANCE_2}）。两者形态不同：{@code d1+d2−1} 在细胞边界给出连续抬升的
     * "鞍形"，而 {@code d2−1} 给出的是"细胞内部平坦、边界一条线"。山形遮罩要前者。</p>
     */
    static final class WorleyEdgeAdd implements Node {
        private final int seed;
        private final float frequency;

        WorleyEdgeAdd(int seed, float frequency) {
            this.seed = seed;
            this.frequency = frequency;
        }

        @Override public float compute(float x, float z) {
            x *= frequency;
            z *= frequency;
            int xi = floor(x), yi = floor(z);
            float nearest1 = Float.MAX_VALUE, nearest2 = Float.MAX_VALUE;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = xi + dx, cy = yi + dy;
                    float[] vec = RegionCellTable.CELL[hash2D(seed, cx, cy) & 0xFF];
                    float ddx = cx + vec[0] - x;
                    float ddy = cy + vec[1] - z;
                    float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                    if (dist < nearest1) {
                        nearest2 = nearest1;
                        nearest1 = dist;
                    } else if (dist < nearest2) {
                        nearest2 = dist;
                    }
                }
            }
            nearest1 = Math.max(1.0E-6f, nearest1);
            float result = nearest2 + nearest1 - 1f;   // DISTANCE_2_ADD：max 1.6 / min −1.0
            float mapped = mapClamped(result, -1f, 1.6f);
            return Float.isNaN(mapped) || Float.isInfinite(mapped) ? 0f : mapped;
        }
    }

    /**
     * {@code AdvancedTerrace.java:21-44}：参考的山地阶地。
     *
     * <p><b>注意</b>：它内部用 {@code NoiseUtil.round} 做台阶（硬量化），与 {@link Steps}
     * 的软层理不同。这是参考 {@code mountains_3} 的真实做法，本类照实复刻，
     * 是否沿用由出图与用户裁定。</p>
     */
    record AdvancedTerrace(Node source, Node modulation, Node mask, Node slope,
                           float blendMin, float blendMax, int steps, int octaves,
                           float sourceMax, float modulationMax) implements Node {
        @Override public float compute(float x, float z) {
            float value = source.compute(x, z);
            if (value <= blendMin) return value;
            float m = mask.compute(x, z);
            if (m == 0f) return value;
            float result = value;
            float s = slope.compute(x, z);
            float mod = modulation.compute(x, z);
            for (int i = 1; i <= octaves; i++) {
                result = stepped(result, steps * i);
                result = sloped(value, result, s);
            }
            result = (result + mod) / (sourceMax + modulationMax);
            float alpha = value > blendMax ? 1f : (value - blendMin) / (blendMax - blendMin);
            if (m != 1f) alpha *= m;
            return lerp(value, result, alpha);
        }

        private static float stepped(float v, int steps) {
            float r = v >= 0f ? (int) (v * steps + 0.5f) : (int) (v * steps - 0.5f);
            return r / steps;
        }

        private static float sloped(float value, float stepped, float slope) {
            float delta = value - stepped;
            return stepped + delta * slope;
        }
    }

    /**
     * {@code DirectionWarp.java:17-27}：把"方向噪声 × 2π"当作角度，沿该方向位移。
     * <p>参考里用于 {@code makeFancy} 的最后一步（强度 2.0）。</p>
     */
    record DirectionWarped(Node input, Node direction, float strength) implements Node {
        @Override public float compute(float x, float z) {
            float angle = direction.compute(x, z) * 6.2831855f;
            float ox = (float) Math.sin(angle) * strength;
            float oz = (float) Math.cos(angle) * strength;
            return input.compute(x + ox, z + oz);
        }
    }

    /**
     * {@code Erosion.java:13-127}：参考的"侵蚀纹理"噪声（<b>不是</b>水文侵蚀）。
     *
     * <p>做法：以 {@code gridSize} 为格距布抖动点，每个格心找出"邻居中最低的那个点"，
     * 用点到该线段的距离作为冲刷强度，逐倍频叠加。结果是沿谷线分布的细密沟纹 ——
     * 这就是 {@code makeFancy} 让山地"越看越有细节"的来源。</p>
     *
     * <p>实现差异（已记录）：参考用自建 sin/cos 查表，这里用 {@code Math.sin/cos}；
     * 缓存用 {@code ThreadLocal} 复用 25 槽 scratch（每次计算先整体填 −1，
     * 语义上等价于局部变量，不构成跨块状态）。</p>
     */
    static final class Erosion implements Node {
        private final Node input;
        private final int seed;
        private final int octaves;
        private final float strength, gridSize, amplitude, lacunarity, distanceFallOff;
        private final ThreadLocal<float[]> cache = ThreadLocal.withInitial(() -> new float[25]);

        Erosion(Node input, int seed, int octaves, float strength, float gridSize,
                float amplitude, float lacunarity, float distanceFallOff) {
            this.input = input;
            this.seed = seed;
            this.octaves = octaves;
            this.strength = strength;
            this.gridSize = gridSize;
            this.amplitude = amplitude;
            this.lacunarity = lacunarity;
            this.distanceFallOff = distanceFallOff;
        }

        /** {@code Erosion.java:30-35}，BlendMode.CONSTANT：{@code alpha = 1 − strength}。 */
        @Override public float compute(float x, float z) {
            float value = input.compute(x, z);
            float erosion = erosionValue(x, z, cache.get());
            return lerp(erosion, value, 1f - strength);
        }

        private float erosionValue(float x, float y, float[] scratch) {
            float sum = 0f, max = 0f, gain = 1f, distance = gridSize;
            for (int i = 0; i < octaves; i++) {
                sum += singleErosion(x, y, distance, scratch) * gain;
                max += gain;
                gain *= amplitude;
                distance *= distanceFallOff;
                x *= lacunarity;
                y *= lacunarity;
            }
            return sum / max;
        }

        private float singleErosion(float x, float y, float grid, float[] scratch) {
            java.util.Arrays.fill(scratch, -1f);
            int pix = floor(x / grid), piy = floor(y / grid);
            float minDist2 = Float.MAX_VALUE;
            for (int dy1 = -1; dy1 <= 1; dy1++) {
                for (int dx1 = -1; dx1 <= 1; dx1++) {
                    int pax = pix + dx1, pay = piy + dy1;
                    float[] v1 = RegionCellTable.CELL[hash2D(seed, pax, pay) & 0xFF];
                    float ax = (pax + v1[0]) * grid;
                    float ay = (pay + v1[1]) * grid;
                    float bx = ax, by = ay;
                    float lowest = Float.MAX_VALUE;
                    for (int dy2 = -1; dy2 <= 1; dy2++) {
                        for (int dx2 = -1; dx2 <= 1; dx2++) {
                            int pbx = pax + dx2, pby = pay + dy2;
                            float[] v2 = (pbx == pax && pby == pay) ? v1
                                    : RegionCellTable.CELL[hash2D(seed, pbx, pby) & 0xFF];
                            float cx = (pbx + v2[0]) * grid;
                            float cy = (pby + v2[1]) * grid;
                            float h = cachedHeight(dx1 + dx2, dy1 + dy2, cx, cy, scratch);
                            if (h < lowest) {
                                lowest = h;
                                bx = cx;
                                by = cy;
                            }
                        }
                    }
                    float d2 = segmentDistance2(x, y, ax, ay, bx, by);
                    if (d2 < minDist2) minDist2 = d2;
                }
            }
            return clamp((float) Math.sqrt(minDist2) / grid, 0f, 1f);
        }

        private float cachedHeight(int dx, int dy, float px, float py, float[] scratch) {
            int index = (dy + 2) * 5 + (dx + 2);
            float v = scratch[index];
            if (v == -1f) {
                v = input.compute(px, py);
                scratch[index] = v;
            }
            return v;
        }

        /** {@code Erosion.sd:129-140}：点到线段的最短距离平方。 */
        private static float segmentDistance2(float px, float py, float ax, float ay, float bx, float by) {
            float padx = px - ax, pady = py - ay;
            float badx = bx - ax, bady = by - ay;
            float paba = padx * badx + pady * bady;
            float baba = badx * badx + bady * bady;
            float h = (paba == 0f && baba == 0f) ? 0f : clamp(paba / baba, 0f, 1f);
            float dx = badx * h - padx;
            float dy = bady * h - pady;
            return dx * dx + dy * dy;
        }
    }

    /** {@code Invert.java:15-19}：{@code max − clamp(v)}。对 [0,1] 的场即 {@code 1−v}。 */
    record Inverted(Node input, float min, float max) implements Node {
        @Override public float compute(float x, float z) {
            return max - clamp(input.compute(x, z), min, max);
        }
    }

    /**
     * {@code Terrace.java:9-97}：参考的台地算子（与 {@link AdvancedTerrace} 不同）。
     *
     * <p>它把输入切成 {@code steps} 级，每级内部再分三段：</p>
     * <ol>
     *   <li>低于 {@code lowerBound}：保持本级台面值（<b>平顶</b>）；</li>
     *   <li>{@code alpha > ramp}：向下一级抬升 {@code rampAlpha × rampHeight}（<b>缓坡</b>）；</li>
     *   <li>{@code alpha > cliff}：直接 lerp 到下一级（<b>陡崖</b>）。</li>
     * </ol>
     * <p>{@code ramp} 与 {@code cliff} 由独立噪声给出（此处为常量 0.9 / 0.15），
     * 因此同一台地不同位置可以有不同比例的缓坡与陡崖 —— 这是它做"真实台地"的关键。</p>
     *
     * @param inMin/inMax 输入的理论值域（参考取 {@code input.minValue()/maxValue()}，
     *                     本实现由调用方显式给出，避免为每个节点维护值域元数据）
     */
    record Terrace(Node input, float ramp, float cliff, float rampHeight,
                   float blendRange, int steps, float inMin, float inMax,
                   boolean indexBySpacing) implements Node {
        @Override public float compute(float x, float z) {
            int n = Math.max(2, steps);
            float v = clamp(input.compute(x, z), 0f, 0.999999f);
            float range = inMax - inMin;
            float spacing = range / (n - 1);
            // 参考原码用 floor(v × n)；地理修正版改用 v/spacing（与台阶值同刻度）。
            int index = indexBySpacing
                    ? Math.min(n - 1, (int) Math.floor((v - inMin) / spacing))
                    : Math.min(n - 1, (int) Math.floor(v * n));
            float value = index * spacing;
            if (index == n - 1) return value;

            float bound = (spacing - spacing * blendRange) / 2f;
            float lower = value - bound;
            float upper = value + bound;
            if (v < lower) return value;
            if (v > upper) return value + spacing;

            float rampT = 1f - ramp * 0.5f;
            float cliffT = 1f - cliff * 0.5f;
            float alpha = (v - lower) / (upper - lower);
            float out = value;
            if (alpha > rampT) {
                float rampAlpha = (alpha - rampT) / (1f - rampT);
                out += spacing * rampAlpha * rampHeight;
            }
            if (alpha > cliffT) {
                float cliffAlpha = (alpha - cliffT) / (1f - cliffT);
                out = lerp(out, value + spacing, cliffAlpha);
            }
            return out;
        }
    }

    /** 平滑阈值：低于 {@code edge0} 恒 0、高于 {@code edge1} 恒 1，中间 smoothstep。 */
    record Smoothstep(Node input, float edge0, float edge1) implements Node {
        @Override public float compute(float x, float z) {
            float t = clamp((input.compute(x, z) - edge0) / (edge1 - edge0), 0f, 1f);
            return t * t * (3f - 2f * t);
        }
    }

    // ===== 数值工具（与参考 NoiseUtil 同义） =====

    /** {@code NoiseUtil.map(value,min,max,range)}：先 clamp，再除以 range。 */
    static float mapClamped(float value, float min, float max) {
        float range = max - min;
        float dif = clamp(value, min, max) - min;
        return dif >= range ? 1f : dif / range;
    }

    static int floor(float f) { return f >= 0f ? (int) f : (int) f - 1; }

    /** 参考 {@code NoiseUtil.hash2D}：用于取 CELL_2D 抖动表索引。 */
    static int hash2D(int seed, int x, int y) {
        int hash = seed;
        hash ^= 1619 * x;
        hash ^= 31337 * y;
        hash = hash * hash * hash * 60493;
        hash ^= hash >> 13;
        return hash;
    }

    /** {@code NoiseUtil.interpHermite}（Interpolation.CURVE3）。 */
    static float hermite(float t) { return t * t * (3f - 2f * t); }

    static float lerp(float a, float b, float t) { return a + t * (b - a); }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}