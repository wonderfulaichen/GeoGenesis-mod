package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;
import com.geogenesis.worldgen.river.HeightProvider;

/**
 * 单格地形装配中枢 —— 统一连续场 e(x,z)。
 *
 * 流程：
 *   ContinentField.sample → c ∈ [0,1]（单一连续噪声场）
 *   ├─ continentBias 偏置整体平移海陆占比
 *   ├─ HeightCurve.eFromC(c) → eOcean ∈ [ -1,0 ]（海洋基面，深海负、近岸≈0）
 *   ├─ LandShape.sample(...) → eLand ∈ [0,1]（陆地形态，连续噪声自然合成、无硬边界）
 *   └─ 统一混：e = clamp(eOcean + eLand, -1, 1)（加法模型）
 *      → 海岸线 = e 场自然过零的等值线，由海陆两侧噪声自然过渡涌现，不绑定 c=threshold；
 *        坡度由海洋样条斜率 × 陆地起伏自然决定，无 if/else 硬切、无 global 海岸过渡旋钮
 *
 * 分类：e < 0 → 海洋（实测海平面），else → 陆地（按形态+省权重）
 * 对齐 {@code terrain-rebuild-design.md §1.1 – 1.2} 统一场契约。
 */
public final class CellGenerator implements HeightProvider {

    private final ContinentField continent;
    private final HeightCurve heightCurve;
    private final LandShape landShape;
    private final SeaBedDetail seaBed;
    private final double continentBias;
    private final double seabedAmp;

    // 温度纬度参数
    private final double tempLatitudeScale = 1.0 / 5000.0;

    public CellGenerator(TerrainParams p, double minWorldY, double maxWorldY) {
        this.continent = new ContinentField(p);
        this.heightCurve = new HeightCurve(p, minWorldY, maxWorldY);
        this.landShape = new LandShape(p);
        this.seaBed = new SeaBedDetail(p);
        this.continentBias = p.continentBias();
        this.seabedAmp = p.seabedDetail();
    }

    /** 一次性播种所有噪声节点 */
    public void seed(long worldSeed) {
        continent.seed(worldSeed);
        landShape.seed(worldSeed);
        seaBed.seed(worldSeed);
    }

    /** 世界高度下界 */
    public double minY() { return heightCurve.heightFromE(-1.0); }

    /** 世界高度上界 */
    public double maxY() { return heightCurve.heightFromE(1.0); }

    /** 海平面 Y */
    public double seaLevel() {
        return heightCurve.heightFromE(heightCurve.seaE());
    }

    /**
     * 采样单格完整数据 — 统一连续场 e(x,z)。
     *
     * 范式：不分支判断海洋/陆地，全程计算海洋基面 eOcean 和陆地形态 eLand，
     * 加法合成 e = eOcean + eLand。海岸线是 e 场自然过零的等值线，由海陆两侧连续噪声
     * 自然过渡涌现（不绑定 c=threshold）；坡度由海洋样条斜率 × 陆地起伏自然决定，无硬切面。
     *
     * @param wx 世界 X
     * @param wz 世界 Z
     */
    public Cell sample(double wx, double wz) {
        Cell cell = new Cell();

        // 1. 大陆性 c（单一连续噪声场）
        double c = continent.sample(wx, wz);
        cell.continent = c;

        // 1.5 应用 continentBias 偏置（正=更多海，负=更多陆）
        double cBiased = NoiseUtil.clamp(c - continentBias, 0.0, 1.0);

        // 2. 海洋基面 eOcean（c 轴样条 + 海床细节，深海为负、近岸≈0）
        double seabed = seabedAmp * seaBed.sample(wx, wz);
        double eOcean = heightCurve.eFromC(cBiased) + seabed;
        eOcean = Math.min(eOcean, 0.0);  // 海床细节不过海平面
        eOcean = NoiseUtil.clamp(eOcean, -1.0, 0.0);

        // 3. 省权重 + 陆地形态 eLand（含海岸距离衰减包络）
        double[] weights = new double[4];
        landShape.provinceWeights(wx, wz, weights);
        cell.provinceWeights = weights;
        double eLand = landShape.sample(wx, wz, weights);
        cell.eLand = eLand;

        // 4. 统一连续场（加法模型）：e = eOcean + eLand
        //    eOcean ∈ [-1,0]（深海负、近岸≈0），eLand ∈ [0,1]（连续噪声自然合成，无海岸过渡衰减）。
        //    海岸线 = e 场自然过零的等值线：平原处 eOcean 平缓跨 0 → 缓岸；山脉处 eOcean 在更深处方跨 0
        //    → 陡岸贴海。坡度由海洋样条斜率 × 陆地起伏自然决定，无 global 海岸过渡旋钮（见 §1.4）。
        double e = NoiseUtil.clamp(eOcean + eLand, -1.0, 1.0);

        cell.e = e;
        cell.height = heightCurve.heightFromE(e);

        // 6. 连续形态分类
        cell.terrainType = classify(c, e, eLand, cell.provinceWeights);

        // 7. 气候（纬度驱动温度 + 大陆性驱动湿度）
        double temp = NoiseUtil.saturate((wx * tempLatitudeScale + 1.0) * 0.5) * 2.0 - 1.0;
        double hum = (c - 0.5) * 2.0;
        cell.climate = new com.geogenesis.worldgen.climate.Climate(temp, hum);

        // 8. 兼容字段填充
        cell.temperature = temp;
        cell.humidity = hum;
        cell.continentNoise = c;
        cell.shape = eLand * 2.0 - 1.0;

        return cell;
    }

    /** 连续形态分类（实测海平面 e<0 判洋，消除椒盐）。供侵蚀回写后重分类调用。 */
    public TerrainClass classify(double c, double e, double eLand,
                                   double[] weights) {
        // 海岸薄环（陆地侧，e 略高于海平面）：识别为海滩。内陆平原 eLand 不接近 0，
        // 故只生成薄薄一圈岸线，不会误标内陆盆地。
        if (e > 0.0 && e < 0.03) return TerrainClass.BEACH;

        // 实测海平面：海洋 = 最终 e<0（非 c<threshold）
        if (e < 0.0) {
            return e < -0.4 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }

        // 陆地
        double wBelt = weights[1], wPlateau = weights[2];

        if (e > 0.90 && wBelt > 0.45) return TerrainClass.SNOW;   // 最高雪峰核（雪线以上雪盖）
        if (e > 0.85 && wBelt > 0.4)  return TerrainClass.PEAK;
        if (e > 0.60 && wBelt > 0.3)  return TerrainClass.MOUNTAINS;
        if (wPlateau > 0.3 && eLand > 0.5)  return TerrainClass.PLATEAU;  // 放宽：高原省识
        if (eLand < 0.08 && weights[3] > 0.5) return TerrainClass.BASIN;
        if (eLand < 0.12) return TerrainClass.PLAIN;
        if (eLand < 0.30) return TerrainClass.HILLS;
        return TerrainClass.MOUNTAINS;
    }

    /** 采样大陆性快捷接口（海洋→landShape 可跳过） */
    public double sampleContinent(double wx, double wz) {
        return continent.sample(wx, wz);
    }

    /** HeightProvider：返回真实陆地 e（海洋→NaN），供 RiverField 下坡汇流。 */
    @Override
    public double landHeight(int wx, int wz) {
        double e = sample(wx, wz).e;
        return e > 0.0 ? e : Double.NaN;
    }

    /** HeightProvider：返回「统一 e」（含海洋海床，负值），供 RiverField 沿陆架海床连续汇流（海陆一体）。 */
    @Override
    public double terrainE(int wx, int wz) {
        return sample(wx, wz).e;
    }

    /** HeightCurve 引用（给 GeoGenesisTerrain） */
    public HeightCurve heightCurve() { return heightCurve; }
}
