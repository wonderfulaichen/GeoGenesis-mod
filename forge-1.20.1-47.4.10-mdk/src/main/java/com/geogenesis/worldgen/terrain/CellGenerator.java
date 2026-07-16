package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;
import com.geogenesis.worldgen.river.HeightProvider;

/**
 * 单格地形装配中枢 —— 统一连续场 e(x,z)。
 *
 * 流程：
 *   ContinentField.sample → c ∈ [-1,1]
 *   ├─ HeightCurve.eFromC(c) → eOcean（海洋基面，负值）
 *   ├─ 大陆斜升：cBiased>0 时陆地随大陆性升高
 *   ├─ TypeLandShape.sample → eLand（独立类型噪声配方）
 *   └─ 统一混：e = clamp(eOcean + eLand, -1, 1)
 *
 * 气候（v5.10）：
 *   温度 = sin(z/freq)^2 + noise warp（纬度正弦模型）
 *   湿度 = 独立 Simplex 噪声（非大陆性驱动）
 */
public final class CellGenerator implements HeightProvider {

    private final ContinentField continent;
    private final HeightCurve heightCurve;
    private final TypeLandShape typeLandShape;
    private final SeaBedDetail seaBed;
    private final double continentBias;
    private final double seabedAmp;

    // 温度参数（v5.10 正弦纬度模型，参考 TF/RTF）
    private static final double TEMP_FREQ = 1.0 / 6000.0; // 6000 块一个完整温度周期
    private final Noise tempWarp;     // 温度噪声扰动
    private final Noise humidityNoise; // 独立湿度噪声

    // 大陆斜升参数（v5.10 参考 RTF continentRise）
    private static final double CONTINENT_SLOPE_FACTOR = 0.08; // 每单位 cBiased 斜升
    private static final double CONTINENT_SLOPE_MAX = 0.15;    // 最大斜升 e

    public CellGenerator(TerrainParams p, double minWorldY, double maxWorldY) {
        this.continent = new ContinentField(p);
        this.heightCurve = new HeightCurve(p, minWorldY, maxWorldY);
        this.typeLandShape = new TypeLandShape();
        this.seaBed = new SeaBedDetail(p);
        this.continentBias = p.continentBias();
        this.seabedAmp = p.seabedDetail();

        // 气候噪声
        this.tempWarp = new Frequency(new Simplex(501), 1.0 / 1500.0);
        this.humidityNoise = new Frequency(new Simplex(502), 1.0 / 800.0);
    }

    /** 一次性播种所有噪声节点 */
    public void seed(long worldSeed) {
        continent.seed(worldSeed);
        typeLandShape.seed(worldSeed);
        seaBed.seed(worldSeed);
        Noises.seedAll(tempWarp, worldSeed, 0);
        Noises.seedAll(humidityNoise, worldSeed, 0);
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
     */
    public Cell sample(double wx, double wz) {
        Cell cell = new Cell();

        // 1. 大陆性 c
        double c = continent.sample(wx, wz);
        cell.continent = c;
        double cBiased = c - continentBias;

        // 2. 海洋基面 eOcean
        double seabed = seabedAmp * seaBed.sample(wx, wz);
        double eOcean = heightCurve.eFromC(cBiased) + seabed;
        eOcean = Math.min(eOcean, 0.0);
        eOcean = clamp(eOcean, -1.0, 0.0);

        // 3. Voronoi 混合结果
        VoronoiRegionField.BlendResult cellBlend = typeLandShape.sampleBlend(wx, wz);
        cell.typeWeights = cellBlend.typeWeights;

        // 4. 陆地形态 eLand
        double eLand = typeLandShape.sample(cellBlend, wx, wz);

        // 5. 大陆斜升（v5.10）：内陆随大陆性升高
        //    参考 RTF: continentRise = clamp(cBiased * 10, 0, 0.5)
        //    我们用更温和的系数，保持海岸平坦
        double continentalSlope = clamp(cBiased * CONTINENT_SLOPE_FACTOR, 0.0, CONTINENT_SLOPE_MAX);
        eLand += continentalSlope;
        cell.eLand = eLand;

        // 6. 连续主导类型
        TerrainClass cellType = TypeLandShape.dominantFromWeights(cellBlend.typeWeights);

        // 7. 统一连续场
        double e = clamp(eOcean + eLand, -1.0, 1.0);
        cell.e = e;
        cell.height = heightCurve.heightFromE(e);

        // 8. 分类
        cell.terrainType = classify(c, e, eLand, cellType, wx, wz);

        // 9. 气候（v5.10）：
        //    温度：sin(z/freq)^2 + noise warp（正弦纬度模型，参考 TF/RTF）
        //    湿度：独立 Simplex 噪声（非大陆性驱动，参考 TF moisture）
        double sinVal = Math.sin(wz * TEMP_FREQ);
        double temp = sinVal * sinVal * 2.0 - 1.0; // [0,1]*2-1 = [-1,1]
        temp += tempWarp.compute(wx, wz) * 0.08;
        temp = clamp(temp, -1.0, 1.0);

        double hum = humidityNoise.compute(wx, wz); // [-1, 1]
        hum = clamp(hum, -1.0, 1.0);

        cell.climate = new com.geogenesis.worldgen.climate.Climate(temp, hum);
        cell.temperature = temp;
        cell.humidity = hum;
        cell.continentNoise = c;
        cell.shape = eLand * 2.0 - 1.0;

        return cell;
    }

    /**
     * 连续形态分类。
     */
    public TerrainClass classify(double c, double e, double eLand,
                                  TerrainClass cellType, double wx, double wz) {
        if (e < 0.0) {
            return e < -0.4 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }
        if (e < 0.03) return TerrainClass.BEACH;
        if (cellType == TerrainClass.MOUNTAINS && eLand > 0.60) {
            return TerrainClass.PEAK;
        }
        if (eLand > 0.45) {
            double sinVal = Math.sin(wz * TEMP_FREQ);
            double temp = sinVal * sinVal * 2.0 - 1.0;
            temp += tempWarp.compute(wx, wz) * 0.08;
            temp = clamp(temp, -1.0, 1.0);
            if (temp < -0.1) return TerrainClass.SNOW;
        }
        return cellType;
    }

    /**
     * 静态分类方法（侵蚀回写后重分类用）。
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature) {
        return classifyTerrain(ne, eLand, cellType, temperature, null);
    }

    /**
     * 静态分类方法（带连续类型权重）。
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature,
                                                double[] typeWeights) {
        if (ne < 0.0) return ne < -0.4 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        if (ne < 0.03) return TerrainClass.BEACH;
        if (cellType == TerrainClass.MOUNTAINS && eLand > 0.60) return TerrainClass.PEAK;
        if (eLand > 0.45 && temperature < -0.1) return TerrainClass.SNOW;
        if (typeWeights != null && typeWeights.length >= TerrainClass.COUNT) {
            return TypeLandShape.dominantFromWeights(typeWeights);
        }
        return cellType;
    }

    // ===== 内联工具 =====
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

    /** HeightProvider：返回真实陆地 e（海洋→NaN） */
    @Override
    public double landHeight(int wx, int wz) {
        double e = sample(wx, wz).e;
        return e > 0.0 ? e : Double.NaN;
    }

    /** HeightProvider：返回统一 e（含海洋海床） */
    @Override
    public double terrainE(int wx, int wz) {
        return sample(wx, wz).e;
    }

    /** HeightCurve 引用 */
    public HeightCurve heightCurve() { return heightCurve; }

    /** TypeLandShape 引用 */
    public TypeLandShape typeLandShape() { return typeLandShape; }
}
