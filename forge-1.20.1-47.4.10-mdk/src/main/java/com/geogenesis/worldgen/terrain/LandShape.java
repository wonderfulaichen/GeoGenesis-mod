package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 陆地形态生成：省权重 × 过程形态 → eLand ∈ [0,1]。
 *
 * 四省：克拉通(平原/丘陵)、造山带(山脉)、高原、盆地。
 * 各形态由 FBM 中低频滚动生成（无 Ridge/Terrace 算子），
 * 山脊与高原崖阶由 Hydraulic 河流切割产生（阶段 2）。
 */
public final class LandShape {

    // === 省场（四组低频 Simplex → softmax 权重） ===
    private final Noise[] provinceSources;
    private final double[] softmaxWeights;
    private final double provinceScale;

    // === 过程形态噪声 ===
    private final Noise cratonNoise;    // 克拉通：FBM 低频基底
    private final Noise beltLow;        // 造山带：FBM 3 层
    private final Noise beltMid;
    private final Noise beltHigh;
    private final Noise platLow;        // 高原：FBM 2 层（低频平顶 + 细节）
    private final Noise platMid;
    private final Noise basinField;     // 盆地：低频基底

    // === 参数 ===
    private final double plainBase, plainRough;
    private final double hillsLow, hillsHigh;
    private final double foothill, peak;
    private final double platBase, platTop;
    private final double basinBase;

    private final Noise root;

    public LandShape(TerrainParams p) {
        this.softmaxWeights = p.provinceSoftmaxWeights();
        this.provinceScale = p.provinceScale();
        this.plainBase = p.plainBase();
        this.plainRough = p.plainRough();
        this.hillsLow = p.hillsLow();
        this.hillsHigh = p.hillsHigh();
        this.foothill = p.beltFoothill();
        this.peak = p.beltPeak();
        this.platBase = p.plateauBase();
        this.platTop = p.plateauTop();
        this.basinBase = p.basinBase();

        // 四省基底（独立 Simplex，不同频率区分）
        double ps = p.provinceScale();
        this.provinceSources = new Noise[]{
            new Frequency(new Simplex(100), 1.0 / ps),
            new Frequency(new Simplex(101), 1.0 / (ps * 0.73)),
            new Frequency(new Simplex(102), 1.0 / (ps * 0.85)),
            new Frequency(new Simplex(103), 1.0 / (ps * 1.1)),
        };

        // 过程形态噪声（全部 FBM，无 Ridge/Terrace 阶地量化伪影）
        this.cratonNoise = new Frequency(new Simplex(200), 1.0 / 300.0);
        this.beltLow  = new Frequency(new Simplex(201), 1.0 / 400.0);
        this.beltMid  = new Frequency(new Simplex(202), 1.0 / 180.0);
        this.beltHigh = new Frequency(new Simplex(203), 1.0 / 90.0);
        this.platLow  = new Frequency(new Simplex(204), 1.0 / 350.0);
        this.platMid  = new Frequency(new Simplex(205), 1.0 / 150.0);
        this.basinField = new Frequency(new Simplex(206), 1.0 / 400.0);

        // 构建根节点（供 seedAll 遍历播种）
        this.root = new Add(
            new Add(cratonNoise, beltLow),
            new Add(beltMid, beltHigh));
    }

    /** 播种所有 Seeded 节点 */
    public void seed(long worldSeed) {
        // root 含 cratonNoise + beltLow/Mid/High。
        // platLow/platMid/basinField 不在 root 内，必须显式播种，
        // 否则其内嵌 Simplex 未初始化 → compute 时 NPE（世界生成卡死）。
        Noises.seedAll(root, worldSeed, 0);
        for (Noise n : provinceSources) Noises.seedAll(n, worldSeed, 0);
        Noises.seedAll(platLow, worldSeed, 0);
        Noises.seedAll(platMid, worldSeed, 0);
        Noises.seedAll(basinField, worldSeed, 0);
    }

    /**
     * 采样省权重（softmax 归一化，和为 1）。
     * @param wx 世界 X
     * @param wz 世界 Z
     * @param out 至少 4 长度的输出数组
     */
    public void provinceWeights(double wx, double wz, double[] out) {
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            double raw = NoiseUtil.saturate((provinceSources[i].compute(wx, wz) + 1.0) * 0.5);
            out[i] = Math.exp(raw * softmaxWeights[i]);
            sum += out[i];
        }
        if (sum > 1e-9) {
            for (int i = 0; i < 4; i++) out[i] /= sum;
        } else {
            for (int i = 0; i < 4; i++) out[i] = 0.25;
        }
    }

    /**
     * 采样陆地 eLand ∈ [0,1]。
     * 各类型地形（克拉通/造山带/高原/盆地）由连续噪声自然合成，彼此平滑过渡、无硬边界。
     * 不含任何"海岸过渡"机制——海岸线由 CellGenerator 的 e = eOcean + eLand 自然过零涌现，
     * 不绑定 c=threshold 硬阈值（见 terrain-rebuild §1.4）。
     * @param wx 世界 X
     * @param wz 世界 Z
     * @param weights 省权重（由 provinceWeights 预计算）
     */
    public double sample(double wx, double wz, double[] weights) {
        double wCraton = weights[0], wBelt = weights[1],
               wPlateau = weights[2], wBasin = weights[3];

        // === 克拉通：平原/丘陵混合 ===
        double cri = NoiseUtil.saturate((cratonNoise.compute(wx, wz) + 1.0) * 0.5);
        double plain = plainBase + plainRough * (cri * 0.5);
        double hill = NoiseUtil.lerp(hillsLow, hillsHigh, cri);
        double craton = NoiseUtil.lerp(plain, hill, cri * 0.8);

        // === 造山带：FBM 中频滚动（Simplex 3 octaves，非 Ridge） ===
        // 山脊由 Hydraulic 河流切割产生（阶段 2），此处 FBM 仅决定山脉高度幅值。
        // Ridge 算子（1-|simplex|）产生脊线伪影（等高线状台阶+蜿蜒曲线），已否决。
        double b0 = NoiseUtil.saturate((beltLow.compute(wx, wz) + 1.0) * 0.5);
        double b1 = NoiseUtil.saturate((beltMid.compute(wx, wz) + 1.0) * 0.5);
        double b2 = NoiseUtil.saturate((beltHigh.compute(wx, wz) + 1.0) * 0.5);
        double beltFbm = 0.5 * b0 + 0.35 * b1 + 0.15 * b2;  // FBM 加权 [0,1]
        double belt = NoiseUtil.lerp(foothill, peak, beltFbm);

        // === 高原：低频 FBM 平顶（2 octaves，高基面 + 低起伏） ===
        // Terrace 算子（量化阶地）已否决——产生环状台阶伪影（截图红线反馈）。
        // 高原崖阶由 Hydraulic 河谷切割出（阶段 2），此处 FBM 仅决定平顶高度。
        double p0 = NoiseUtil.saturate((platLow.compute(wx, wz) + 1.0) * 0.5);
        double p1 = NoiseUtil.saturate((platMid.compute(wx, wz) + 1.0) * 0.5);
        double platFbm = 0.7 * p0 + 0.3 * p1;  // 低频主导，平坦台地
        double plat = NoiseUtil.lerp(platBase, platTop, platFbm);

        // === 盆地：近海平面低填 ===
        double basin = NoiseUtil.saturate((basinField.compute(wx, wz) + 1.0) * 0.5);
        basin = basinBase + 0.02 * basin;

        // === 加权合成（连续噪声自然合成，无海岸过渡因子） ===
        double raw = wCraton * craton + wBelt * belt + wPlateau * plat + wBasin * basin;

        return NoiseUtil.clamp(raw, 0.0, 1.0);
    }
}
