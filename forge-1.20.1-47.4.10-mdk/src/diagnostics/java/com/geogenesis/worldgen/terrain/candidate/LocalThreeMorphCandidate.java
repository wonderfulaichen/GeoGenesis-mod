package com.geogenesis.worldgen.terrain.candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * 三族局部形态原型：{@code PLAIN_GEO} / {@code HILLS1_GEO} / {@code M1_GEO} 的三角形权重组合。
 *
 * <p>独立复建，<b>不引用也不修改</b> {@link FourPairMorphCandidate} 等既有候选；任一条边上
 * （某个权重恒 0）退化回其 {@code PLAIN_HILLS} / {@code PLAIN_MOUNTAINS} /
 * {@code HILLS_MOUNTAINS}，残差仅 float 求和顺序。</p>
 *
 * <p>口径：三族种子偏移 {@code 0/11/77}；权重由调用方给出，<b>已过 smoothstep，本类不再 ease</b>；
 * {@code wM} 同时控方向扰动 {@code 2·wM} 与侵蚀混合 {@code 1−0.65·wM}；三个 raw 先加权混合，
 * <b>再</b>乘 {@code amp = Σ w·(0.15,0.6,0.7)} 并加 {@code bias = −0.02·wP}，
 * <b>绝不</b>对成品高度做 lerp；权重为 0 的族不求值。</p>
 *
 * <p><b>⚠ 只覆盖平原 + 丘陵 + 山地这一个三角形</b>；台地、劣地与真实复合项均未覆盖，
 * 也不是整幅地形接线。</p>
 */
final class LocalThreeMorphCandidate {

    /** 三族固定世界种子偏移，与 {@code FourPairMorphCandidate} 构造器一致。 */
    static final int OFFSET_PLAIN = 0;
    static final int OFFSET_HILLS = 11;
    static final int OFFSET_MOUNTAINS = 77;

    /** 三族成品振幅，与 {@code FourPairMorphCandidate} 的 {@code mix(…,0.15/0.6/0.7,…)} 同值。 */
    static final float AMP_PLAIN = 0.15f;
    static final float AMP_HILLS = 0.6f;
    static final float AMP_MOUNTAINS = 0.7f;
    /** 平原偏置，对应 {@code mix(-0.02f, 0f, t)}。 */
    static final float BIAS_PLAIN = -0.02f;
    /** 固定山地侵蚀混合强度，对应 {@code lerp(erosion, source, 1 - 0.65f*t)}。 */
    static final float EROSION_MIX = 0.65f;
    /** 二维修正单纯形容差：只用于<b>和</b>；单个分量的上下界是严格的 {@code [0,1]}。 */
    static final double SIMPLEX_TOL = 1e-6;

    private static final float DIRECTION_STRENGTH = 2f;

    /**
     * 三族身份与<b>唯一</b>可接受的严格配方 id。
     *
     * <p>⚠ 只收<b>带 {@code _GEO} 后缀的完整配方 id</b>。曾同时接受 {@code PLAIN} /
     * {@code HILLS} / {@code MOUNTAINS} 这类 <b>Category</b> 短名 —— 那是与
     * {@code RecipeId} 不同的另一层概念，混用会让"类别"冒充"已定稿配方"，
     * 故已删除，不保留兼容别名。</p>
     */
    enum Family {
        PLAIN("PLAIN_GEO"),
        HILLS("HILLS1_GEO"),
        MOUNTAINS("M1_GEO");

        private final String recipeId;

        Family(String recipeId) { this.recipeId = recipeId; }

        /** 该族唯一的严格配方 id。 */
        String recipeId() { return recipeId; }

        static Family of(String id) {
            if (id != null) {
                for (Family f : values()) {
                    if (f.recipeId.equals(id)) return f;
                }
            }
            throw new IllegalArgumentException("unsupported recipe id: " + quote(id)
                    + " (exactly PLAIN_GEO / HILLS1_GEO / M1_GEO; category short names are rejected)");
        }

        private static String quote(String id) { return id == null ? "null" : "'" + id + "'"; }
    }

    private final RefNoise.Node plainBody, hillsBody, mountainBody, direction, erosion;
    private int evaluations;

    LocalThreeMorphCandidate(long seed) {
        int p = base(seed + OFFSET_PLAIN);
        int h = base(seed + OFFSET_HILLS);
        int m = base(seed + OFFSET_MOUNTAINS);
        this.plainBody = plainBody(p);
        this.hillsBody = hillsBody(h);
        this.mountainBody = mountainBody(m);
        this.direction = RefNoise.Perlin.ofScale(m + 3, 10f, 1);
        // strength = 1f ⇒ 该节点返回<b>纯侵蚀纹理</b>（lerp 的第二参数权重为 0）；
        // 0.65 的混合在 sample() 里按 wM 施加，与 FourPair 的分工逐字一致。
        this.erosion = new RefNoise.Erosion(mountainBody, m + 4, 2, 1f, 128f, 0.15f, 3.1f, 0.8f);
    }

    // ================================================================ raw 节点（完整复制）

    /** {@code PlainsCandidate.PLAINS_GEO} 的 body（不含 ×0.15 与 −0.02）。 */
    private static RefNoise.Node plainBody(int p) {
        RefNoise.Node plain = new RefNoise.Mul(RefNoise.Perlin.ofScale(p + 3, 250f, 1),
                new RefNoise.AlphaMix(RefNoise.Perlin.ofScale(p, 500f, 3, 3.75f), 0.45f));
        plain = RefNoise.warp(plain, RefNoise.Perlin.ofScale(p + 1, 62f, 1, 3.5f),
                RefNoise.Perlin.ofScale(p + 2, 62f, 1, 3.5f), 62.5f);
        return RefNoise.warpPerlin(plain, p + 4, 256f, 1, 64f);
    }

    /** {@code HillsCandidate.HILLS_1_GEO} 的 body（不含 ×0.6）。 */
    private static RefNoise.Node hillsBody(int h) {
        RefNoise.Node hill = new RefNoise.Mul(RefNoise.Perlin.ofScale(h, 200f, 3),
                new RefNoise.AlphaMix(RefNoise.Billow.ofScale(h + 1, 650f, 3, 2f, 0.5f), 0.5f));
        hill = RefNoise.warpPerlin(hill, h + 2, 30f, 3, 20f);
        return RefNoise.warpPerlin(hill, h + 3, 400f, 3, 200f);
    }

    /** {@code MountainsCandidate.M1_GEO} 的 body（不含 fancy 与 ×0.7）。 */
    private static RefNoise.Node mountainBody(int m) {
        RefNoise.Node ridge = new RefNoise.PerlinRidge(m, 1f / 610f, 4, 2.35f, 1.15f);
        ridge = new RefNoise.Mul(ridge, new RefNoise.AlphaMix(
                RefNoise.Perlin.ofScale(m + 1, 180f, 4), 0.30f));
        return RefNoise.warpPerlin(ridge, m + 2, 350f, 1, 150f);
    }

    // ================================================================ 采样

    /**
     * 三向采样。<b>权重须已在单纯形上（每分量严格 [0,1]、和 = 1）、且已做过 smoothstep</b>；
     * 本方法不再做任何 ease，也不接受任何越界补偿。
     *
     * @param plainWeight    平原权重（决定 {@code −0.02·wP} 偏置）
     * @param hillsWeight    丘陵权重
     * @param mountainWeight 山地权重（决定方向扰动幅度与侵蚀混合强度）
     * @throws IllegalArgumentException 任一分量越出严格 {@code [0,1]} / 非有限，或和不为 1
     */
    double sample(double x, double z, double plainWeight, double hillsWeight, double mountainWeight) {
        requireSimplex(plainWeight, hillsWeight, mountainWeight);
        float wP = (float) plainWeight, wH = (float) hillsWeight, wM = (float) mountainWeight;
        float wx = (float) x, wz = (float) z;
        if (wM != 0f) {
            float angle = eval(direction, wx, wz) * 6.2831855f;
            float offset = DIRECTION_STRENGTH * wM;
            wx += (float) Math.sin(angle) * offset;
            wz += (float) Math.cos(angle) * offset;
        }
        float source = 0f;
        if (wP != 0f) source += wP * eval(plainBody, wx, wz);
        if (wH != 0f) source += wH * eval(hillsBody, wx, wz);
        if (wM != 0f) source += wM * eval(mountainBody, wx, wz);
        if (wM != 0f) source = RefNoise.lerp(eval(erosion, wx, wz), source, 1f - EROSION_MIX * wM);
        return source * amplitude(wP, wH, wM) + BIAS_PLAIN * wP;
    }

    /**
     * 按配方 id 采样：同 id 的多个分量<b>先逐分量校验、再按 id 归并求和</b>，故结果与传入顺序无关。
     *
     * <p>每个分量先各自过 {@link #requireWeight}（finite 且 {@code 0 ≤ w ≤ 1}），
     * <b>然后</b>才允许归并。否则 {@code PLAIN_GEO:-0.5} 与 {@code PLAIN_GEO:+1.0} 会
     * 抵消成合法的 {@code 0.5}，把两个非法分量洗成合法配方 —— 这是必须堵掉的漏洞。</p>
     *
     * @param ids     配方 id，只接受 {@code PLAIN_GEO} / {@code HILLS1_GEO} / {@code M1_GEO}
     * @param weights 与 {@code ids} 一一对应
     * @throws IllegalArgumentException 长度不一致、未支持配方、分量越界/非有限、或不构成单纯形权重
     */
    double sample(double x, double z, String[] ids, double[] weights) {
        if (ids == null || weights == null || ids.length != weights.length) {
            throw new IllegalArgumentException("ids and weights must be non-null and of equal length");
        }
        Family[] families = new Family[ids.length];
        for (int i = 0; i < ids.length; i++) {
            families[i] = Family.of(ids[i]);
            requireWeight(weights[i], "ids/weights[" + i + "] '" + ids[i] + "'");
        }
        double[] merged = new double[Family.values().length];
        for (Family family : Family.values()) {
            List<Double> parts = new ArrayList<>();
            for (int i = 0; i < families.length; i++) {
                if (families[i] == family) parts.add(weights[i]);
            }
            if (parts.isEmpty()) continue;
            // double 加法不满足结合律 ⇒ 先排序，使归并结果与分量出现顺序无关。
            parts.sort(null);
            double sum = 0;
            for (double part : parts) sum += part;
            merged[family.ordinal()] = sum;
        }
        return sample(x, z, merged[Family.PLAIN.ordinal()],
                merged[Family.HILLS.ordinal()], merged[Family.MOUNTAINS.ordinal()]);
    }

    /** 本实例已完成的本体求值次数（含方向/侵蚀节点），用于验证纯区短路。 */
    int evaluations() { return evaluations; }

    void resetEvaluations() { evaluations = 0; }

    // ================================================================ 内部

    private float eval(RefNoise.Node node, float wx, float wz) {
        evaluations++;
        return node.compute(wx, wz);
    }

    static float amplitude(float wP, float wH, float wM) {
        return wP * AMP_PLAIN + wH * AMP_HILLS + wM * AMP_MOUNTAINS;
    }

    private static void requireSimplex(double wP, double wH, double wM) {
        requireWeight(wP, "plainWeight");
        requireWeight(wH, "hillsWeight");
        requireWeight(wM, "mountainWeight");
        // 只有"和"给容差；单个分量的上下界上面已经严格判过，不接受 −tol 之类的小负数。
        double sum = wP + wH + wM;
        if (Math.abs(sum - 1.0) > SIMPLEX_TOL) {
            throw new IllegalArgumentException(
                    "weights are not on the 2D simplex: wP+wH+wM=" + sum + " (expected 1)");
        }
    }

    /** 单个分量的<b>严格</b>上下界：finte 且 {@code 0 ≤ w ≤ 1}，无容差。 */
    private static void requireWeight(double w, String label) {
        if (!(w >= 0.0 && w <= 1.0)) {
            // 写成正向区间判定，NaN 会在此处失败（NaN 的比较全为 false）。
            throw new IllegalArgumentException(label + " outside strict [0,1]: " + w
                    + (Double.isFinite(w) ? "" : " (not finite)"));
        }
    }

    private static int base(long seed) { return (int) (seed ^ (seed >>> 32)); }
}
