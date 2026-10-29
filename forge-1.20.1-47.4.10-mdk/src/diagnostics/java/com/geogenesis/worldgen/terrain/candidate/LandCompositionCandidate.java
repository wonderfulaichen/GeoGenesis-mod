package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.TerrainCharacterField;
import com.geogenesis.worldgen.terrain.TerrainClass;

/**
 * 陆地类型<b>合成</b>沙箱：把五份已定稿的配方合成一张完整陆地地形。
 *
 * <p>单类型好看 ≠ 合成好看。合成阶段才会暴露三类真问题：</p>
 * <ol>
 *   <li><b>绝对基高不一致</b>：平原 62–85、丘陵 76–162、山地 77–216、台地 160–230、
 *       盆地 54–66 格。直接按权重平均会在接缝处差几十格，读作断层悬崖。</li>
 *   <li><b>混合方式决定串扰</b>：现行生产是"先混噪声、再套混合振幅区间"，等于把各类型的
 *       噪声串在一起（历史 per-block Δe 0.04–0.07、morph 卡在 0.75 的机制来源）。</li>
 *   <li><b>过渡带宽度</b>：五类全开时，过渡带到底是"平滑中间形态"还是"两不像的糊状"。</li>
 * </ol>
 *
 * <h3>本类做的三件事</h3>
 * <ol>
 *   <li><b>完整配方加权</b>：每类先各自算完整高度，再按权重混合。不做"先混噪声"，
 *       因此某类的噪声不会借到别类的振幅。</li>
 *   <li><b>统一高度带</b>：每类把自身实测值域线性映射到固定的地理高度带（山地最高、
 *       盆地最低），保证绝对基高可比。</li>
 *   <li><b>连续权重</b>：直接复用生产 {@link TerrainCharacterField} 的 400 格网 + σ=200 高斯
 *       Voronoi 权重，不另造选择机制 —— 这样"权重是否合理"与"配方是否好看"两个问题分开。</li>
 * </ol>
 *
 * <p><b>平原 / 草原</b>：生产只有一个 PLAIN 类型位，所以本沙箱把 PLAIN 位的权重按
 * {@code plainsShare} 拆给 plains 与 steppe（默认各半）。这样不必改枚举就能看到两种质地
 * 共存的效果；真实接入时若要分开需要新增类型位。</p>
 *
 * <p>流式约束：全部 (x,z) 纯函数。每点代价 = 49 格中心判定（生产同款）+ 5 次配方求值。</p>
 */
public final class LandCompositionCandidate {

    /** 混合模式。 */
    public enum Mode {
        /** 完整配方加权：Σ w·e_t，每类的 e 已按高度带对齐。 */
        RECIPE,
        /** 完整配方加权 + 权重锐化（w³ 归一）：过渡带更窄、类型更分明。 */
        RECIPE_SHARP
    }

    /**
     * 地理高度带：每类在"归一化地形高度"上的目标区间（0 = 最低洼，1 = 最高）。
     *
     * <p>相对顺序盆地 &lt; 平原/草原 &lt; 丘陵 &lt; 台地 &lt; 山地是<b>地貌学约束</b>，
     * 数值可调但顺序不能变 —— 变了就会出现"盆地比平原高"这种反地理的接缝。</p>
     */
    private enum Band {
        BASIN(0.00, 0.09),
        PLAIN(0.12, 0.22),
        HILLS(0.28, 0.48),
        PLATEAU(0.42, 0.62),
        MOUNTAINS(0.52, 1.00);

        final double lo, hi;

        Band(double lo, double hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }

    /** 五类各自的已定稿配方（统一成 height01 归一化输出）。 */
    private interface Recipe {
        double height01(double wx, double wz);
    }

    private final Mode mode;
    private final TerrainCharacterField character;
    private final Recipe[] recipes = new Recipe[5];
    private final double[] rawLo = new double[5];
    private final double[] rawHi = new double[5];
    private final Band[] bands = {
            Band.BASIN, Band.PLAIN, Band.HILLS, Band.PLATEAU, Band.MOUNTAINS
    };
    /** PLAIN 类型位里 plains 与 steppe 的分摊比例（0.5 = 各半）。 */
    private double plainsShare = 0.5;

    public LandCompositionCandidate(Mode mode, TerrainCharacterField character) {
        this.mode = mode;
        this.character = character;
    }

    public LandCompositionCandidate plainsShare(double share) {
        this.plainsShare = Math.max(0, Math.min(1, share));
        return this;
    }

    public String id() {
        return "COMPOSE-" + mode.name().toLowerCase().replace('_', '-');
    }

    public String label() {
        return mode == Mode.RECIPE
                ? "合成（完整配方加权 + 统一高度带）"
                : "合成（配方加权 + 权重锐化 w³）";
    }

    public void seed(long worldSeed) {
        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        PlainsCandidate steppe = new PlainsCandidate(PlainsCandidate.Variant.STEPPE);
        HillsCandidate hills = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        MountainsCandidate mountains = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        BasinCandidate basin = new BasinCandidate(BasinCandidate.Variant.B1);
        plains.seed(worldSeed);
        steppe.seed(worldSeed);
        hills.seed(worldSeed);
        plateau.seed(worldSeed);
        mountains.seed(worldSeed);
        basin.seed(worldSeed);

        // 盆地配方直接产出 e，其余产出参考归一化高度 0..1；两者都只作为
        // "该类自身的单调坐标"，对齐时空的高度带会把它线性拉进目标区间，
        // 所以单位不同不影响结果。
        recipes[0] = basin::e;   // 直接产出 e（见下）
        recipes[1] = plains::height01;
        recipes[2] = hills::height01;
        recipes[3] = plateau::height01;
        recipes[4] = mountains::height01;
        this.steppeRecipe = steppe::height01;
        measureRanges();
    }

    private Recipe steppeRecipe;

    /**
     * 在黄金角螺旋上测出每类的<b>归一化形状值域</b>。
     *
     * <p>这些配方都是纯函数，值域只取决于配方本身。这里用实测而不是把数字写死 ——
     * 写死会在任何配方微调后静默失准。</p>
     *
     * <p>盆地配方直接产出 e（其自身量级约 ±0.05），其余产出参考归一化高度 0..1。
     * 两者都只是"该类自身的单调坐标"，对齐会把它线性拉进目标高度带，因此单位差异
     * 不影响结果；但<b>混合时必须在 e 空间做</b> —— 先把每类对齐成归一化高度、
     * 再换算成同一 e 尺度，否则 0..1 与 ±0.05 直接相加会把盆地淹没。</p>
     */
    private void measureRanges() {
        int n = 4096;
        for (int t = 0; t < recipes.length; t++) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double a = i * 2.399963229728653;
                double r = 40.0 * Math.sqrt(i + 1);
                double v = recipes[t].height01(Math.cos(a) * r, Math.sin(a) * r);
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            rawLo[t] = lo;
            rawHi[t] = hi;
        }
    }

    /** 合成后的归一化地形高度（0..1）。 */
    public double height01(double wx, double wz) {
        double[] w = new double[5];
        double sum = 0;
        for (int t = 0; t < 5; t++) {
            double v = character.sampleBlend(wx, wz).typeWeights[ordinal(t)];
            if (mode == Mode.RECIPE_SHARP) v = v * v * v;
            w[t] = v;
            sum += v;
        }
        if (sum <= 1e-12) return 0.5;
        double acc = 0;
        for (int t = 0; t < 5; t++) {
            if (w[t] <= 0) continue;   // 零权重不求值
            acc += (w[t] / sum) * aligned(t, wx, wz);
        }
        return acc;
    }

    /**
     * 该类的对齐高度。PLAIN 位按 {@code plainsShare} 在 plains 与 steppe 之间插值 ——
     * 两者同属一个高度带，只是质地不同，所以共用一次对齐。
     */
    private double aligned(int t, double wx, double wz) {
        double raw;
        if (t == 1 && plainsShare < 1.0) {
            double a = recipes[1].height01(wx, wz);
            double b = steppeRecipe.height01(wx, wz);
            raw = a + (b - a) * (1.0 - plainsShare);
        } else {
            raw = recipes[t].height01(wx, wz);
        }
        double span = rawHi[t] - rawLo[t];
        double u = span > 1e-9 ? (raw - rawLo[t]) / span : 0.5;
        if (u < 0) u = 0;
        if (u > 1) u = 1;
        return bands[t].lo + u * (bands[t].hi - bands[t].lo);
    }

    /** 主导类型（诊断用）。 */
    public TerrainClass dominant(double wx, double wz) {
        return character.sampleBlend(wx, wz).dominantType;
    }

    /** 五类在 {@code typeWeights} 里的下标。 */
    private static int ordinal(int t) {
        return switch (t) {
            case 0 -> TerrainClass.BASIN.ordinal();
            case 1 -> TerrainClass.PLAIN.ordinal();
            case 2 -> TerrainClass.HILLS.ordinal();
            case 3 -> TerrainClass.PLATEAU.ordinal();
            default -> TerrainClass.MOUNTAINS.ordinal();
        };
    }
}