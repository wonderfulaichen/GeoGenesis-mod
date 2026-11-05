package com.geogenesis.worldgen.terrain.morph;

import java.util.Arrays;

/**
 * <b>唯一节点形态内核</b>：把 18 条目的信号层 + 线性外壳按权重合成一个高度。
 *
 * <h3>为什么是内核而不是"先算 18 个高度再加权"</h3>
 * <p>若先算出 18 个<b>成品高度</b>再加权平均，每个族的形态会被摊薄成一片均匀皱褶，
 * 且端点（权重退化到单条目）处仍与真配方不等。内核改为把
 * {@code raw}（信号层）与 {@code amp}/{@code bias}（线性外壳）<b>分别</b>混合，
 * 再在外壳之后施加一次截断 —— 于是"纯某个族"的内部格与真配方逐位一致。</p>
 *
 * <h3>★ 遍历顺序就是产出（改一处就换图）</h3>
 * <ol>
 *   <li>{@link #preBad}：域扭曲 → 信号层 → 台地阶地+细节 → 山地的侵蚀纹理接管；</li>
 *   <li>劣地形态按 {@code role(BADLANDS)} 与第 1 步结果混合（<b>劣地会重采样 preBad</b>）；</li>
 *   <li>再用 {@code role(MOUNTAIN)} 做第二次域扭曲取外壳参数；</li>
 *   <li>{@code value = source·amp + bias}，最后乘上"被 clamp 条目的权重和"做软截断。</li>
 * </ol>
 * <p>第 2 步里 {@code preBad} 会被以 <b>偏移后</b>的坐标再调一次 —— 那不是冗余，
 * 是劣地的真实定义（位移后的地形差）。去掉它劣地会退化成普通阶地。</p>
 *
 * <h3>★ 无量化：{@code rounded()} 是恒等</h3>
 * <p>历史上这里有一层"取整"包装，现已确认它对噪声值域做量化会把连续场切成阶梯，
 * 阶梯等值线是一族嵌套闭合曲线 ⇒ 图上出现"密集波浪状平行细线 / 同心环梯田"。
 * 故 {@link #rounded} <b>必须是恒等</b>，不得复活取整。</p>
 */
final class DiagnosticRidgeKernel {

    private final MorphRecipe[] recipes;
    private com.geogenesis.worldgen.erosion.RidgeValleyErosion.RidgeConfig ridge;
    private java.util.function.BiFunction<Double, Double, double[]> weightField;

    void ridge(com.geogenesis.worldgen.erosion.RidgeValleyErosion.RidgeConfig cfg,
               java.util.function.BiFunction<Double, Double, double[]> weights) {
        ridge = cfg; weightField = weights;
    }

    double rawShell(double x, double z, double[] weights) {
        Evaluation e = new Evaluation(weights);
        double[] p = e.domain(x, z, e.role(MorphRecipe.Role.MOUNTAIN));
        return Math.max(MorphScale.GROUND_E + (e.parameter(p[0], p[1], 0)
                * e.parameter(p[0], p[1], 1) + e.parameter(p[0], p[1], 2)) * MorphScale.E_SPAN, 0);
    }

    float rawDelta(double x, double z, double[] weights) {
        java.util.function.DoubleBinaryOperator f = (a, b) -> rawShell(a, b,
                weightField == null ? weights : weightField.apply(a, b));
        float h = (float) f.applyAsDouble(x, z);
        float l = (float) f.applyAsDouble(x - 2, z), r = (float) f.applyAsDouble(x + 2, z);
        float b = (float) f.applyAsDouble(x, z - 2), t = (float) f.applyAsDouble(x, z + 2);
        float denominator = 4f;
        if (ridge.horizontalScale > .01f && ridge.horizontalScale != 1f) denominator *= ridge.horizontalScale;
        return com.geogenesis.worldgen.erosion.RidgeValleyErosion.evaluatePoint((float) x, (float) z,
                h, (r - l) / denominator, (t - b) / denominator, 0f, ridge);
    }
    private final boolean mountainTexture, macroGuide;
    private final MorphNoise.Node direction, erosion, surface, badX, badZ, scaler;
    /** 台地阶地：输入是<b>标量信号值</b>（{@link MorphNoise.ScalarIdentity}）。 */
    private final MorphNoise.Node terrace = new MorphNoise.Terrace(new MorphNoise.ScalarIdentity(),
            .9f, .15f, .35f, .4f, 4, 0, .75f * .6f + .3f + .15f, false);
    private final MorphNoise.Node steps4 = new MorphNoise.Steps(new MorphNoise.ScalarIdentity(),
            4, .6f, .7f, false);
    private final MorphNoise.Node steps10 = new MorphNoise.Steps(new MorphNoise.ScalarIdentity(),
            10, .6f, .7f, false);
    private final MorphNoise.Node shapeSteps = new MorphNoise.Steps(new MorphNoise.ScalarIdentity(),
            4, .65f, .75f, true);

    DiagnosticRidgeKernel(MorphRecipe[] recipes, long seed) {
        this(recipes, seed, true);
    }

    /** 隔离候选可旁路线段纹理；原入口保持认可P1，不改变种子和其它算子。 */
    DiagnosticRidgeKernel(MorphRecipe[] recipes, long seed, boolean mountainTexture) {
        this(recipes, seed, mountainTexture, false);
    }

    /** 宏观导向旁路锐边算子与10wu方向扰动，不作为最终地表。 */
    DiagnosticRidgeKernel(MorphRecipe[] recipes, long seed, boolean mountainTexture, boolean macroGuide) {
        this.recipes = recipes.clone();
        this.mountainTexture = mountainTexture;
        this.macroGuide = macroGuide;
        int m = base(seed + 77), p = base(seed + 44), b = base(seed + 55);
        direction = MorphNoise.Perlin.ofScale(m + 3, 10, 1);
        MorphRecipe mountain = Arrays.stream(recipes)
                .filter(r -> r.role() == MorphRecipe.Role.MOUNTAIN)
                .findFirst().orElse(null);
        // 侵蚀纹理的输入必须是"山地族的信号层"：float 臂取 float 值，double 臂取 double 值。
        // 若只给 float，双坐标臂就会拿 float 结果当输入，整条链退化成 float 精度。
        erosion = new MorphNoise.Erosion(
                new MorphNoise.DualNode(
                        (x, z) -> mountain == null ? 0f : (float) mountain.raw(x, z),
                        (x, z) -> mountain == null ? 0.0 : mountain.raw(x, z)),
                m + 4, 2, 1, 128, .15f, 3.1f, .8f);
        surface = MorphNoise.warpPerlin(new MorphNoise.Mul(
                MorphNoise.Perlin.ofScale(p + 6, 20, 3), new MorphNoise.Constant(.05f)),
                p + 7, 40, 2, 20);
        badX = MorphNoise.Perlin.ofScale(b + 4, 100, 1);
        badZ = MorphNoise.Perlin.ofScale(b + 5, 100, 1);
        scaler = MorphNoise.Perlin.ofScale(b + 5, 200, 3);
    }

    /**
     * 把"稀疏的 (id, weight)"折叠成密权重表。
     *
     * <p>同一 id 的多个分量<b>先排序再累加</b>：排序让累加顺序与输入顺序无关，
     * 于是同一个 id 无论由几条分量凑成，结果都逐位相同（可复现性的前提）。</p>
     */
    double[] merge(int[] ids, double[] weights) {
        if (ids.length != weights.length || ids.length == 0) throw new IllegalArgumentException("ids/weights");
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || ids[i] >= recipes.length || !Double.isFinite(weights[i])
                    || weights[i] < 0 || weights[i] > 1) throw new IllegalArgumentException("recipe/weight");
        }
        double[] merged = new double[recipes.length];
        for (int id = 0; id < recipes.length; id++) {
            double[] parts = new double[ids.length];
            int n = 0;
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) parts[n++] = weights[i];
            Arrays.sort(parts, 0, n);
            for (int i = 0; i < n; i++) merged[id] += parts[i];
        }
        return merged;
    }

    /**
     * 内部量（相对共享地面基准的增量）—— 覆盖层要在这个空间里做混合。
     *
     * <p>这是内核<b>唯一</b>的求值出口。换算到 e 由调用方在覆盖层之后做
     * （{@code max(GROUND_E + covered·E_SPAN, 0)}，见 {@link MorphScale}）——
     * 顺序不能反：先换算再覆盖会把覆盖层也乘上 {@code E_SPAN}。</p>
     */
    double sampleLand(double x, double z, double[] weights) {
        Evaluation e = new Evaluation(weights);
        return e.sample(x, z);
    }

    // ============================================================ 单点求值

    private final class Evaluation {
        final double[] weights;

        Evaluation(double[] weights) {
            if (weights.length != recipes.length) throw new IllegalArgumentException("recipe count");
            double sum = 0;
            for (int i = 0; i < weights.length; i++) {
                double w = weights[i];
                if (!Double.isFinite(w) || w < 0 || w > 1) throw new IllegalArgumentException("weight");
                sum += w;
            }
            if (Math.abs(sum - 1) > 1e-9) throw new IllegalArgumentException("weights sum=" + sum);
            this.weights = weights;
        }

        double sample(double x, double z) {
            double source = preBad(x, z);
            double bad = role(MorphRecipe.Role.BADLANDS);
            if (!macroGuide && bad != 0) source = blend(source, badShape(x, z, source), bad);
            double[] domain = domain(x, z, role(MorphRecipe.Role.MOUNTAIN));
            double amp = parameter(domain[0], domain[1], 1), bias = parameter(domain[0], domain[1], 2);
            double value = source * amp + bias;
            // 复合条目的软截断：只对被 clamp 的条目施加 max(·,0)，
            // 强度 = 它们的权重和 ⇒ 权重退化到单条目时行为与"纯该条目"逐位一致。
            double clipping = clampStrength();
            return value + (Math.max(value, 0) - value) * clipping;
        }

        /** 劣地与山地接管之前的信号层。 */
        double preBad(double x, double z) {
            double mountain = role(MorphRecipe.Role.MOUNTAIN), plateau = role(MorphRecipe.Role.PLATEAU);
            double[] point = domain(x, z, mountain);
            double source = parameter(point[0], point[1], 0);
            // True node insertion: alter raw signal, never bias or finished height.
            // Neighbour evaluation reads only the unfiltered raw node; domain is applied once.
            if (ridge != null && ridge.enabled && ridge.strength != 0) {
                double amp = parameter(point[0], point[1], 1);
                source += rawDelta(x, z, weights) / (MorphScale.E_SPAN * amp);
            }
            if (!macroGuide && plateau != 0) {
                source = rounded(blend(source, MorphNoiseEval.eval(terrace, source, 0), plateau)
                        + rounded(MorphNoiseEval.eval(surface, point[0], point[1]) * plateau));
            }
            if (mountainTexture && mountain != 0) {
                source = blend(MorphNoiseEval.eval(erosion, point[0], point[1]), source, 1 - .65 * mountain);
            }
            return source;
        }

        /**
         * 按权重求某个 stage 的参数（0 = raw 信号、1 = amp、2 = bias）。
         *
         * <p>只有 1 个 active 条目时直接返回它自己（不做无意义的乘加）；
         * 2 个时用 {@code blend} 的端点特判；3 个以上才用加权和。</p>
         */
        double parameter(double x, double z, int stage) {
            int first = -1, second = -1, count = 0;
            double sum = 0;
            for (int i = 0; i < recipes.length; i++) {
                if (weights[i] == 0) continue;
                if (first < 0) first = i;
                else if (second < 0) second = i;
                count++;
                sum += weights[i] * parameter(recipes[i], x, z, stage);
            }
            if (count == 1) return rounded(parameter(recipes[first], x, z, stage));
            if (count == 2) {
                return blend(parameter(recipes[first], x, z, stage),
                        parameter(recipes[second], x, z, stage), weights[second]);
            }
            return rounded(sum);
        }

        double parameter(MorphRecipe recipe, double x, double z, int stage) {
            return switch (stage) {
                case 0 -> recipe.raw(x, z);
                case 1 -> recipe.amplitude(x, z);
                default -> recipe.bias(x, z);
            };
        }

        /** 某地貌族的权重和（0 = 该族不参与，1 = 纯该族）。 */
        double role(MorphRecipe.Role target) {
            double sum = 0;
            for (int i = 0; i < recipes.length; i++) if (recipes[i].role() == target) sum += weights[i];
            return sum;
        }

        /** 被 {@code max(·,0)} 软截断的条目的权重和（复合条目有该截断，其余没有）。 */
        double clampStrength() {
            double sum = 0;
            for (int i = 0; i < recipes.length; i++) {
                if (recipes[i].clamped()) sum += weights[i];
            }
            return sum;
        }

        /**
         * 域扭曲：山地权重越大，采样点在方向上被推得越远（半径 2）。
         * <p>权重为 0 时<b>原样返回</b>坐标 —— 不只是省算，也保证没有山地时
         * 这条链与"不扭曲"逐位相同。</p>
         */
        double[] domain(double x, double z, double weight) {
            if (macroGuide || weight == 0) return new double[]{x, z};
            double angle = MorphNoiseEval.eval(direction, x, z) * 6.2831855f;
            return new double[]{x + Math.sin(angle) * 2 * weight, z + Math.cos(angle) * 2 * weight};
        }

        /**
         * 劣地形态：两级台阶（4 级 + 10 级）叠成竖向纹理，再乘一个 4 级形状。
         *
         * <p>{@code modulation} 先用<b>位移后</b>的坐标重采一次 {@link #preBad}，
         * 这让劣地纹理贴合"大尺度地形起伏"而不是孤立噪声。</p>
         */
        double badShape(double x, double z, double source) {
            double ox = (MorphNoiseEval.eval(badX, x, z) - .5) * 50;
            double oz = (MorphNoiseEval.eval(badZ, x, z) - .5) * 50;
            double modulation = preBad(x + ox, z + oz) * .4f;
            double low = MorphNoiseEval.eval(steps4, source, 0) * .6f + modulation;
            double high = MorphNoiseEval.eval(steps10, source, 0) * .6f + modulation;
            double detail = (low + high) * .5f + .5f;
            double mod2 = source * (MorphNoiseEval.eval(scaler, x, z) * .4f);
            double shape = (MorphNoiseEval.eval(shapeSteps, source, 0) * .6f + mod2) * .6f;
            return shape * detail;
        }

        /**
         * <b>恒等</b>（见类注释）。历史上的"取整"包装在这里，已确认会造成值域量化伪影，
         * 故保留形状但明确写成恒等 —— 若将来有人想恢复取整，必须先读类注释。
         */
        double rounded(double value) { return value; }

        /**
         * 端点特判的三点插值：{@code t==0} 返回 a、{@code t==1} 返回 b，否则线性。
         *
         * <p>端点特判不是优化：{@code a + (b-a)*0} 在 {@code b} 为 NaN/Inf 时
         * 会污染结果，而 t 恰好落在端点时它代表"纯该侧"，应当原样返回。</p>
         */
        double blend(double a, double b, double t) {
            return t == 0 ? a : t == 1 ? b : a + (b - a) * t;
        }
    }

    private static int base(long seed) { return (int) (seed ^ (seed >>> 32)); }
}
