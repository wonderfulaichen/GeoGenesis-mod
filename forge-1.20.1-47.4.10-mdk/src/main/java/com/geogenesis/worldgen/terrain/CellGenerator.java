package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;
import com.geogenesis.worldgen.river.HeightProvider;

/**
 * 单格地形装配中枢 —— 统一连续场 e(x,z)。
 *
 * 流程：
 *   ContinentField.sample → c ∈ [-1,1]（单一连续噪声场）
 *   ├─ continentBias 偏置整体平移海陆占比
 *   ├─ HeightCurve.eFromC(c) → eOcean ∈ [ -1,0 ]（海洋基面）
 *   ├─ TypeLandShape.sample(...) → eLand ∈ [0,1]
 *   │     └─ 类型由独立 baseElev 噪声确定（取代旧的省权重）
 *   │     └─ 每个类型有独立的噪声配方（样条嵌入噪声对象）
 *   └─ 统一混：e = clamp(eOcean + eLand, -1, 1)（加法模型）
 *      → 海岸线 = e 场自然过零的等值线，由海陆两侧噪声自然过渡涌现
 *
 * 分类：e < 0 → 海洋（实测海平面），else → 陆地（按类型分布）
 */
public final class CellGenerator implements HeightProvider {

    private final ContinentField continent;
    private final HeightCurve heightCurve;
    private final TypeLandShape typeLandShape;
    private final SeaBedDetail seaBed;
    private final double continentBias;
    private final double seabedAmp;

    // 温度纬度参数（scale 1/1500：-1500→北极，1500→赤道，保证预览范围内可见温度梯度）
    private final double tempLatitudeScale = 1.0 / 1500.0;

    public CellGenerator(TerrainParams p, double minWorldY, double maxWorldY) {
        this.continent = new ContinentField(p);
        this.heightCurve = new HeightCurve(p, minWorldY, maxWorldY);
        this.typeLandShape = new TypeLandShape();
        this.seaBed = new SeaBedDetail(p);
        this.continentBias = p.continentBias();
        this.seabedAmp = p.seabedDetail();
    }

    /** 一次性播种所有噪声节点 */
    public void seed(long worldSeed) {
        continent.seed(worldSeed);
        typeLandShape.seed(worldSeed);
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
     * 自然过渡涌现（不绑定 c=threshold）；坡度由海洋样条斜率 × 陆地起伏自然决定。
     */
    public Cell sample(double wx, double wz) {
        Cell cell = new Cell();

        // 1. 大陆性 c（单一连续噪声场）
        double c = continent.sample(wx, wz);
        cell.continent = c;

        // 1.5 应用 continentBias 偏置（正=更多海，负=更多陆）
        double cBiased = c - continentBias;

        // 2. 海洋基面 eOcean（c 轴样条 + 海床细节，深海为负、近岸≈0）
        double seabed = seabedAmp * seaBed.sample(wx, wz);
        double eOcean = heightCurve.eFromC(cBiased) + seabed;
        eOcean = Math.min(eOcean, 0.0);  // 海床细节不过海平面
        eOcean = clamp(eOcean, -1.0, 0.0);

        // 3. Voronoi 混合结果（含连续类型权重）
        VoronoiRegionField.BlendResult cellBlend = typeLandShape.sampleBlend(wx, wz);
        cell.typeWeights = cellBlend.typeWeights;

        // 4. Voronoi 区域驱动陆地形态 eLand（传入已有 blend 避免重复计算）
        double eLand = typeLandShape.sample(cellBlend, wx, wz);
        cell.eLand = eLand;

        // 5. 连续主导类型：argmax(类型权重) — 比旧 hash 最近 cell 平滑
        TerrainClass cellType = TypeLandShape.dominantFromWeights(cellBlend.typeWeights);

        // 6. 统一连续场（加法模型）
        double e = clamp(eOcean + eLand, -1.0, 1.0);
        cell.e = e;
        cell.height = heightCurve.heightFromE(e);

        // 6. 连续形态分类（基于 Voronoi 区域 + 叠加检测）
        cell.terrainType = classify(c, e, eLand, cellType, wx, wz);

        // 7. 气候（纬度驱动温度 + 大陆性驱动湿度）
        double temp = saturate((wx * tempLatitudeScale + 1.0) * 0.5) * 2.0 - 1.0;
        double hum = (c - 0.5) * 2.0;
        cell.climate = new com.geogenesis.worldgen.climate.Climate(temp, hum);

        // 8. 兼容字段填充
        cell.temperature = temp;
        cell.humidity = hum;
        cell.continentNoise = c;
        cell.shape = eLand * 2.0 - 1.0;

        return cell;
    }

    /**
     * 连续形态分类（实测海平面 e<0 判洋）。
     * <p>
     * 陆地类型由 Voronoi 区域系统确定 + 叠加检测：
     * <ul>
     *   <li>BEACH：海岸薄环（e<0.03）</li>
     *   <li>PEAK：MOUNTAINS 区域 + 高海拔（eLand>0.60）</li>
     *   <li>SNOW：高海拔（eLand>0.45）+ 低温（temperature<-0.1）</li>
     *   <li>默认：Voronoi 细胞类型（PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN）</li>
     * </ul>
     */
    public TerrainClass classify(double c, double e, double eLand,
                                  TerrainClass cellType, double wx, double wz) {
        // 实测海平面：海洋 = 最终 e<0
        if (e < 0.0) {
            return e < -0.4 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }

        // 海岸薄环（陆地侧，e 略高于海平面）
        if (e < 0.03) return TerrainClass.BEACH;

        // 峰值叠加：MOUNTAINS 区域内 eLand>0.60 标记为 PEAK
        if (cellType == TerrainClass.MOUNTAINS && eLand > 0.60) {
            return TerrainClass.PEAK;
        }

        // 雪盖叠加：高海拔 + 低温（阈值降低确保可见）
        if (eLand > 0.45) {
            double temp = saturate((wx * tempLatitudeScale + 1.0) * 0.5) * 2.0 - 1.0;
            if (temp < -0.1) return TerrainClass.SNOW;
        }

        // 默认：Voronoi 细胞主导类型
        return cellType;
    }

    /**
     * 静态分类方法（侵蚀回写后重分类用）。
     * @param ne 侵蚀后的 e 值
     * @param eLand 陆地形态值
     * @param cellType Voronoi 主导类型（旧签名，当 typeWeights 不可用时回落）
     * @param temperature 温度
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature) {
        return classifyTerrain(ne, eLand, cellType, temperature, null);
    }

    /**
     * 静态分类方法（带连续类型权重，侵蚀回写后重分类用）。
     * @param typeWeights 连续类型权重（>= 3 元素：PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN），为空则用 cellType
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature,
                                                double[] typeWeights) {
        if (ne < 0.0) return ne < -0.4 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        if (ne < 0.03) return TerrainClass.BEACH;
        if (cellType == TerrainClass.MOUNTAINS && eLand > 0.60) return TerrainClass.PEAK;
        if (eLand > 0.45 && temperature < -0.1) return TerrainClass.SNOW;
        // 连续权重优先（argmax 比旧 hash 最近 cell 平滑）
        if (typeWeights != null && typeWeights.length >= TerrainClass.COUNT) {
            return TypeLandShape.dominantFromWeights(typeWeights);
        }
        return cellType;
    }

    // ===== 内联工具（避免依赖 NoiseUtil 的 MC-static） =====
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private static double saturate(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 采样大陆性快捷接口 */
    public double sampleContinent(double wx, double wz) {
        return continent.sample(wx, wz);
    }

    /** HeightProvider：返回真实陆地 e（海洋→NaN），供 RiverField 下坡汇流。 */
    @Override
    public double landHeight(int wx, int wz) {
        double e = sample(wx, wz).e;
        return e > 0.0 ? e : Double.NaN;
    }

    /** HeightProvider：返回「统一 e」（含海洋海床，负值） */
    @Override
    public double terrainE(int wx, int wz) {
        return sample(wx, wz).e;
    }

    /** HeightCurve 引用（给 GeoGenesisTerrain） */
    public HeightCurve heightCurve() { return heightCurve; }

    /** TypeLandShape 引用（给诊断工具） */
    public TypeLandShape typeLandShape() { return typeLandShape; }
}
