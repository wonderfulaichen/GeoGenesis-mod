package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.ClimateSpline;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.erosion.RidgeValleyErosion;
import com.geogenesis.worldgen.noise.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单格地形装配中枢 —— 统一连续场 e(x,z)。
 *
 * 流程（v8.3 连续混合海岸线）：
 *   ContinentField.sample → c ∈ [-1,1]
 *   ├─ HeightCurve.eFromC(c) → eOcean（海洋地形噪声场，含 seabed 细节，负值）
 *   ├─ TypeLandShape.sample → eLand（陆地地形噪声场，独立类型噪声配方）
 *   ├─ 大陆性 c 只决定「类型」与「海陆 mask」（海陆位置），绝不直接进高度
 *   └─ 连续混合（恢复早期 e=eOcean+eLand 的自然混合，带平滑 gating）：
 *         cont = smoothstep(oceanFadeStart, landRampEnd, cEdge)   // 0 纯海 → 1 纯陆
 *         e    = (1-cont)·eOcean + cont·eLand
 *       · cEdge < oceanFadeStart: cont=0 → e=eOcean（深海，陆地噪声完全淡出）
 *       · cEdge > landRampEnd:    cont=1 → e=eLand（内陆，海深完全淡出）
 *       · 过渡带内: 真实海岸线 = e=0 等值线，落在 (1-cont)·eOcean+cont·eLand=0
 *         即 cont=−eOcean/(eLand−eOcean)，由两侧地形噪声共同决定 → 自然岬角/海湾
 *         （早期 v8 两阶段把 e 硬锚在 coastLoc 是回归，已废除）
 *
 * 气候（v2 增强模型）：
 *   温度 = sin²(z) 纬度基值 × 海洋性修正 − 海拔递减率 + 噪声
 *   湿度 = 大陆性距海 + 山地雨影 + 噪声
 */
public final class CellGenerator {

    private static final Logger DLOG = LogManager.getLogger("geogenesis/diag");
    /** 诊断限频 */
    private static int diagCount = 0;

    private final ContinentField continent;
    private final HeightCurve heightCurve;
    private final TypeLandShape typeLandShape;
    private final SeaBedDetail seaBed;
    private final OceanFeatures oceanFeatures;
    private final LandFeatures landFeatures;
    private final CoastlineField coastline;
    private final double continentBias;
    private final double seabedAmp;
    private final double oceanDepthFactor;
    /** 海洋淡出起点（cBiased），在此以上海洋深度开始衰减到 0 */
    private final double oceanFadeStart;
    /** 陆地高度终点（cBiased），在此达到全量陆地高度 */
    private final double landRampEnd;
    /** coastLoc 保留为过渡带中心概念（配置/向后兼容），v8.3 已不再硬锚 e=0 */
    private final double coastLoc;
    private final TerrainParams params;

    // ===== 侵蚀（物理液滴侵蚀，恢复自「物理侵蚀-基本无断裂」基线） =====
    /** 物理侵蚀引擎（在 80×80 tile 上跑确定性侵蚀，仅提取中心 16×16，border 作上下文） */
    private final ErosionEngine erosion;
    /** 河流系统开关（诊断时关闭以隔离变量） */
    private boolean riversEnabled = true;
    /** 世界种子（区域确定性侵蚀，保证缓存一致、无闪烁） */
    private long worldSeed = 12345L;
    /** 全 tile 版本号计数器（滑窗收敛用：每生成一次 tile 递增） */
    private int erosionRoundCounter = 0;

    // 温度参数（v5.10 正弦纬度模型，参考 TF/RTF）
    private final double tempFreq;    // 温度纬度角频率 = 1/latitudeScale（由 TerrainParams 注入，可配置）
    private final Noise tempWarp;     // 温度噪声扰动
    private final Noise humidityNoise; // 独立湿度噪声

    public CellGenerator(TerrainParams p, double minWorldY, double maxWorldY) {
        this.continent = new ContinentField(p);
        this.heightCurve = new HeightCurve(p, minWorldY, maxWorldY);
        this.typeLandShape = new TypeLandShape(p);
        this.seaBed = new SeaBedDetail(p);
        this.oceanFeatures = new OceanFeatures();
        this.landFeatures = new LandFeatures();
        this.coastline = new CoastlineField(p);
        this.continentBias = p.continentBias();
        this.seabedAmp = p.seabedDetail();
        this.oceanDepthFactor = p.oceanDepthFactor();
        this.oceanFadeStart = p.oceanFadeStart();
        this.landRampEnd = p.landRampEnd();
        this.coastLoc = p.coastLoc(); // 复用已有 coastLoc 作为两段分界
        this.params = p;

        // 侵蚀：物理液滴侵蚀引擎（恢复自好基线）。配置在游戏内已 load；
        // 独立预览（config 未 load）时回退默认值。
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        double erosionDropsMul = cfg != null ? cfgDbl(cfg.erosionDropsMul, 1.0) : 1.0;
        this.erosion = new ErosionEngine(erosionDropsMul, 12345);
        this.riversEnabled = cfg == null || cfgBool(cfg.riversEnabled, true);

        this.tempFreq = 1.0 / p.latitudeScale();   // 纬度缩放可配置

        // 气候噪声（xz 缩放可配置）
        this.tempWarp = new Frequency(new Simplex(501), 1.0 / p.tempWarpScale());
        this.humidityNoise = new Frequency(new Simplex(502), 1.0 / p.humidityScale());
    }

    /** 一次性播种所有噪声节点 + 设置海山中心水深检查器 */
    public void seed(long worldSeed) {
        continent.seed(worldSeed);
        typeLandShape.seed(worldSeed);
        seaBed.seed(worldSeed);
        // 海山中心水深检查：计算中心点的真实 eOcean（含 seabed，不含海山增量）
        // 仅在 eOcean_at_center < -0.20（足够深）时才允许生成海山
        oceanFeatures.setSeamountDepthChecker((wx, wz) -> {
            double c = continent.sample(wx, wz);
            double cBiased = c - continentBias;
            double eBase = heightCurve.eFromC(cBiased);
            double depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2;
            double seabed = seabedAmp * depthMod * seaBed.sample(wx, wz);
            double eOcean = (eBase + seabed) * oceanDepthFactor;
            return Math.min(eOcean, 0.0);
        });
        coastline.seed(worldSeed);
        oceanFeatures.seed(worldSeed);
        landFeatures.seed(worldSeed);
        erosionTileCache.clear();
        riverTileCache.clear();
        this.worldSeed = worldSeed;
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
     * 采样单格地形场（不含气候与分类）。供 {@link #sample} 与 {@link #terrainE}/{@link #landE} 复用。
     * 返回已设置 e/eLand/eOcean/blendCont/coastCoord/typeWeights/height/shape 的 Cell。
     * 水平缩放：所有噪声坐标除以 horizontalScale（HS）。
     */
    private Cell sampleCore(double wx, double wz) {
        double hs = params.horizontalScale();
        double sx = wx, sz = wz;
        if (hs > 0.01 && hs != 1.0) { sx = wx / hs; sz = wz / hs; }

        Cell cell = new Cell();

        // 1. 大陆性 c
        double c = continent.sample(sx, sz);
        cell.continent = c;
        double cBiased = c - continentBias;

        // 2. 海洋基面 eOcean（不 clamp 到 0——陆地区域 eBase 为正，自然地增高）
        double eBase = heightCurve.eFromC(cBiased);
        double depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2;
        double seabed = seabedAmp * depthMod * seaBed.sample(sx, sz);
        double eOcean = eBase + seabed;
        eOcean = eOcean * oceanDepthFactor;
        // 海洋特征：计算洋中脊/海山增量，叠加到 eOcean 使海床产生实际地形。
        OceanFeatures.FeatureResult oceanFeat = oceanFeatures.compute(sx, sz, Math.min(eOcean, 0.0), cBiased);
        eOcean += oceanFeat.total; // 海山/洋中脊抬升海床（仅海洋侧生效，陆地侧 blend 天然淡出）
        cell.oceanFeat = oceanFeat; // 缓存供 classify 使用，避免 sample() 重复 compute

        // 3. 连续类型混合结果
        TerrainCharacterField.BlendResult cellBlend = typeLandShape.sampleBlend(sx, sz);
        cell.typeWeights = cellBlend.typeWeights;

        // 4. 海岸线域扭曲（v8 CoastlineField）— 在海岸过渡带施加 c-space 噪声位移。
        double cEdge = cBiased + coastline.warpDisplacement(sx, sz, cBiased);

        // 5. 陆地形态 eLand — 用有效岸线坐标 cEdge
        double eLand = typeLandShape.sample(cellBlend, sx, sz, cEdge);

        // 6. 陆地火山特征（c 不再参与陆地高度——地形高度全权由类型噪声决定）
        LandFeatures.FeatureResult landFeat = landFeatures.compute(sx, sz);
        eLand += landFeat.total;
        // 基础地形留余量：整体 ×0.9，使类型高端（maxLandHi=0.95 → 0.855）低于理论上限，
        // 为脊谷抬升（≈0.17）与液滴沉积留出空间，实际 e 恒 < maxLandHi，softCap 仅作保险。
        // 线性缩放（峰谷对比不变），非平方（不违反"[0,1] 高度项严禁直接平方"铁律）。
        eLand *= 0.90;
        cell.eLand = eLand;
        cell.landFeat = landFeat; // 缓存供 classify 使用，避免 sample() 重复 compute

        // 7. 连续主导类型
        TerrainClass cellType = TypeLandShape.dominantFromWeights(cellBlend.typeWeights);

        // 8. 连续混合（v8.3）：海陆地形 = 海洋噪声场 与 陆地噪声场 的平滑插值。
        double cont = smoothstep(oceanFadeStart, landRampEnd, cEdge); // 0(纯海)→1(纯陆)
        double e = softCapLandE((1.0 - cont) * eOcean + cont * eLand);
        cell.e = e;
        cell.eOcean = eOcean;
        cell.blendCont = cont;
        cell.height = heightCurve.heightFromE(e);
        cell.coastCoord = cEdge;
        cell.shape = eLand * 2.0 - 1.0;
        return cell;
    }

    /**
     * 采样单格完整数据 — 统一连续场 e(x,z)，叠加气候与分类。
     * 水平缩放：所有噪声坐标除以 horizontalScale（HS），实现统一 XZ 等比缩放。
     */
    public Cell sample(double wx, double wz) {
        Cell cell = sampleCore(wx, wz);

        double hs = params.horizontalScale();
        double sx = wx, sz = wz;
        if (hs > 0.01 && hs != 1.0) { sx = wx / hs; sz = wz / hs; }

        // 8. 气候（增强模型 v2）
        //    温度：纬度基值 + 海拔递减率 + 海洋性修正 + 噪声
        //    湿度：大陆性距海 + 山区雨影 + 噪声
        double sinVal = Math.sin(wz * tempFreq);
        double temp = sinVal * sinVal * 2.0 - 1.0; // 纬度基值 [-1, 1]
        // 海洋性修正：海岸（c≈0）温差小，内陆（c>0.5）温差大
        double continentFactor = clamp(cell.continent * 1.5, 0.0, 1.0);
        temp = temp * (0.85 + 0.15 * continentFactor);
        // 海拔递减率：每 eLand 冷 0.15（山顶比山脚冷约 0.1 = ~5.8°C）
        temp -= cell.eLand * 0.15;
        // 噪声扰动
        temp += tempWarp.compute(sx, sz) * 0.10;
        temp = clamp(temp, -1.0, 1.0);

        // 湿度模型 v2：大陆性距海 + 山区雨影 + 噪声
        double montW = cell.typeWeights != null && cell.typeWeights.length > TerrainClass.MOUNTAINS.ordinal()
            ? cell.typeWeights[TerrainClass.MOUNTAINS.ordinal()] : 0.0;
        // 海岸（c≈0）湿 -> 内陆（c>0.8）干
        double humBase = 1.0 - clamp(cell.continent * 1.25, 0.0, 1.0);
        double hum = humBase * 2.0 - 1.0; // map [0,1]→[-1,1]
        // 山地雨影：山脉区域降低湿度（简化处理）
        hum -= montW * 0.3;
        // 噪声扰动
        hum += humidityNoise.compute(sx, sz) * 0.25;
        hum = clamp(hum, -1.0, 1.0);

        // 气候影响权重（tempInfluence / humidityInfluence / continentInfluence）
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        double tempInf = cfg != null ? cfgDbl(cfg.tempInfluence, 1.0) : 1.0;
        double humInf = cfg != null ? cfgDbl(cfg.humidityInfluence, 1.0) : 1.0;
        double contInf = cfg != null ? cfgDbl(cfg.continentInfluence, 1.0) : 1.0;
        double tempE = clamp(temp * tempInf, -1.0, 1.0);
        double humE = clamp(hum * humInf, -1.0, 1.0);
        double contE = clamp(cell.continent * contInf, -1.0, 1.0);

        cell.climate = new com.geogenesis.worldgen.climate.Climate(tempE, humE, contE);
        cell.temperature = tempE;
        cell.humidity = humE;

        // 9. 分类（使用 sampleCore 已缓存 FeatureResult，避免重复 compute）
        cell.terrainType = classify(cell.continent, cell.e, cell.eLand,
            TypeLandShape.dominantFromWeights(cell.typeWeights), cell.typeWeights,
            tempE, humE, cell.oceanFeat, cell.landFeat, cell.coastCoord);
        cell.continentNoise = cell.continent;

        return cell;
    }

    /** 排水高程（真实地表 e，含海陆混合）。河流节点场用它做下坡汇流，海洋侧 e<0 → 不接河。 */
    public double terrainE(double wx, double wz) { return sampleCore(wx, wz).e; }

    /** 纯陆地形态 eLand（侵蚀边际采样用，不含气候/分类）。 */
    public double landE(double wx, double wz) { return sampleCore(wx, wz).eLand; }

    /** 地形类型连续权重（诊断探针按类型分桶统计用）。 */
    public double[] typeWeightsAt(double wx, double wz) { return sampleCore(wx, wz).typeWeights; }

    // ===== 侵蚀 tile 缓存（超分辨率架构：spacing=4 粗采 + 双三次插值升采样，仿 6 月备份 70cd037） =====

    /** 每 tile 覆盖 chunk 数（3×3=9 chunk/tile，如 6 月备份 generateTileWithHydrology） */
    private static final int ERODE_TILE_CHUNKS = 3;
    /** 边缘填充块数（侵蚀 brush 上下文 + 接缝消除） */
    private static final int ERODE_TILE_BORDER = 40;
    /** tile 总边长：3×16 + 40×2 = 128（与 6 月备份的 generateTile 一致） */
    private static final int ERODE_TILE_SIZE = ERODE_TILE_CHUNKS * 16 + ERODE_TILE_BORDER * 2;
        /** 粗采间距（spacing=4：128/4=32×32=1024 次 terrainE） */
        private static final int ERODE_SAMPLING_SPACING = 4;
        /** 骨架层（脊-谷条纹）采样间距：比主 bicubic 流程更密(2)，恢复被低分辨率稀释的坡度 → combiMask 正常触发 */
        private static final int RIDGE_SKELETON_SPACING = 2;
        /** 低分辨率网格边长 */
        private static final int ERODE_LOW_RES = ERODE_TILE_SIZE / ERODE_SAMPLING_SPACING;
    /** 缓存条目数（256 条目 ≈ 全部出生区域 tiles 常驻，无 LRU 驱逐） */
    private static final int ERODE_TILE_CACHE_SIZE = 256;

    private final ConcurrentHashMap<Long, ErosionTileResult> erosionTileCache = new ConcurrentHashMap<>(ERODE_TILE_CACHE_SIZE);

    // ===== 河流（D8 流量累积 + V形河谷雕刻，纯局部无边界断裂） =====

    /** 每侵蚀 tile 的河流元数据（配合 ERODE_TILE_SIZE，128×128 格） */
    static class RiverTileData {
        boolean[][] riverMask = new boolean[ERODE_TILE_SIZE][ERODE_TILE_SIZE];
        float[][] discharge = new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE];
        int[][] flowDir = new int[ERODE_TILE_SIZE][ERODE_TILE_SIZE]; // D8 流向编码 0-7, -1=无流出
    }

    /** 侵蚀 tile 结果：delta（叠加量）+ postErosion（侵蚀后全高度）+ tile 元数据 */
    static class ErosionTileResult {
        float[][] delta;
        float[][] postErosion;
        int tileCX, tileCZ;
        int originX, originZ;
        int erosionRound; // 版本号，用于滑窗收敛：邻居新则 self 重生成
    }

    private final ConcurrentHashMap<Long, RiverTileData> riverTileCache = new ConcurrentHashMap<>(ERODE_TILE_CACHE_SIZE);

    /**
     * 获取或生成侵蚀 tile delta。接受 chunk 坐标，内部自动分组为 3×3 tile。
     *
     * <p>获取时递归确保左/上邻居 tile 先存在（游程顺序: 左→右, 上→下）。
     * 返回 delta（侵蚀后 − 侵蚀前），以保持 extractFromTile 向后兼容。</p>
     */
    public float[][] getErosionTile(int chunkX, int chunkZ) {
        int tileCX = Math.floorDiv(chunkX, ERODE_TILE_CHUNKS) * ERODE_TILE_CHUNKS;
        int tileCZ = Math.floorDiv(chunkZ, ERODE_TILE_CHUNKS) * ERODE_TILE_CHUNKS;

        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        if (!erosionOn) {
            return new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE];
        }

        long key = tileKey(tileCX, tileCZ);
        int MAX_ROUNDS = 3; // 3 轮收敛足够了（game 里逐块生成收敛更快）
        int dcx[] = {-ERODE_TILE_CHUNKS, ERODE_TILE_CHUNKS, 0, 0};
        int dcz[] = {0, 0, -ERODE_TILE_CHUNKS, ERODE_TILE_CHUNKS};

        for (int conv = 0; conv < MAX_ROUNDS; conv++) {
            // 首轮必须确保 4 方向邻居存在（之前只保证了左/上，漏了右/下！）
            if (conv == 0) {
                ensureErosionTile(tileCX - ERODE_TILE_CHUNKS, tileCZ);
                ensureErosionTile(tileCX + ERODE_TILE_CHUNKS, tileCZ);
                ensureErosionTile(tileCX, tileCZ - ERODE_TILE_CHUNKS);
                ensureErosionTile(tileCX, tileCZ + ERODE_TILE_CHUNKS);
            }

            // 生成或取出缓存
            ErosionTileResult result = erosionTileCache.get(key);
            if (result == null) {
                result = generateErosionTile(tileCX, tileCZ);
                erosionTileCache.put(key, result);
            }

            // 最后一轮直接返回
            if (conv == MAX_ROUNDS - 1) return result.delta;

            // 检查全部 4 方向邻居版本号（之前只检查左/上，漏了右/下！）
            int maxNbrRound = result.erosionRound;
            for (int d = 0; d < 4; d++) {
                ErosionTileResult nbr = erosionTileCache.get(tileKey(tileCX + dcx[d], tileCZ + dcz[d]));
                if (nbr != null && nbr.erosionRound > maxNbrRound) maxNbrRound = nbr.erosionRound;
            }

            if (maxNbrRound > result.erosionRound) {
                // 邻居更新了 → 本 tile 需用新数据重生成
                erosionTileCache.remove(key);
                // 重新确保全部 4 方向邻居
                for (int d = 0; d < 4; d++) {
                    ensureErosionTile(tileCX + dcx[d], tileCZ + dcz[d]);
                }
            } else {
                return result.delta; // 收敛
            }
        }
        return erosionTileCache.get(key).delta;
    }

    /** 确保指定 tile 已存在于缓存（若不存在则递归生成）。 */
    private void ensureErosionTile(int tileCX, int tileCZ) {
        long key = tileKey(tileCX, tileCZ);
        erosionTileCache.computeIfAbsent(key, k -> generateErosionTile(tileCX, tileCZ));
    }

    /**
     * 生成侵蚀 tile：spacing=4 粗采 → 全局对齐 Catmull-Rom 双三次插值升采样
     * → 三区制 flat 缓冲区初始化（左/上邻居 postErosion + 其余 terrainE）
     * → 物理液滴侵蚀 → delta + postErosion 缓存。
     */
    private ErosionTileResult generateErosionTile(int tileCX, int tileCZ) {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        double erosionStr = cfg != null ? cfgDbl(cfg.erosionStrength, 1.0) : 1.0;

        int originX = tileCX * 16 - ERODE_TILE_BORDER;
        int originZ = tileCZ * 16 - ERODE_TILE_BORDER;

        // 1) spacing=4 粗采（全局对齐网格，扩展 2 格以消除 Catmull-Rom 边沿退化）
        int spacing = ERODE_SAMPLING_SPACING;
        int lowRes = ERODE_LOW_RES; // = 32
        int gridExtra = 2;
        int extendedLowRes = lowRes + gridExtra * 2; // = 36
        int alignedStartX = Math.floorDiv(originX, spacing) * spacing - gridExtra * spacing;
        int alignedStartZ = Math.floorDiv(originZ, spacing) * spacing - gridExtra * spacing;

        float[][] lowResBuf = new float[extendedLowRes][extendedLowRes];
        for (int tz = 0; tz < extendedLowRes; tz++) {
            for (int tx = 0; tx < extendedLowRes; tx++) {
                int wx = alignedStartX + tx * spacing;
                int wz = alignedStartZ + tz * spacing;
                lowResBuf[tz][tx] = (float) Math.max(terrainE(wx, wz), -0.05);
            }
        }

        // 备份未平滑副本 B（粗侵蚀骨架作用于真实地形，不走 step1.5 平滑）
        float[][] rawLowRes = new float[extendedLowRes][extendedLowRes];
        for (int i = 0; i < extendedLowRes; i++)
            System.arraycopy(lowResBuf[i], 0, rawLowRes[i], 0, extendedLowRes);

        // 1.5) 粗网格 Gaussian 低通滤波：消除控制点对齐噪声
        //     kernel = [1,2,1; 2,4,2; 1,2,1] / 16（σ≈0.85 grid cells ≈ 3.4 blocks）
        //     保留最外层 1 格作为 bicubic 边界条件（扩展带原值）
        float[][] smoothBuf = new float[extendedLowRes][extendedLowRes];
        for (int tz = 0; tz < extendedLowRes; tz++)
            System.arraycopy(lowResBuf[tz], 0, smoothBuf[tz], 0, extendedLowRes);
        for (int tz = 1; tz < extendedLowRes - 1; tz++) {
            for (int tx = 1; tx < extendedLowRes - 1; tx++) {
                float sum = lowResBuf[tz-1][tx-1] + lowResBuf[tz-1][tx]*2 + lowResBuf[tz-1][tx+1]
                          + lowResBuf[tz][tx-1]*2   + lowResBuf[tz][tx]*4 + lowResBuf[tz][tx+1]*2
                          + lowResBuf[tz+1][tx-1] + lowResBuf[tz+1][tx]*2 + lowResBuf[tz+1][tx+1];
                smoothBuf[tz][tx] = sum / 16f;
            }
        }
        lowResBuf = smoothBuf;

        // 2) Catmull-Rom 双三次插值升采样到全分辨率
        int N = ERODE_TILE_SIZE;
        float[][] tile = bicubicUpsampleAligned(lowResBuf, extendedLowRes, spacing,
            alignedStartX, alignedStartZ, originX, originZ, N);

        // 2.5) 粗侵蚀骨架（脊-谷条纹滤镜）—— 作用于独立更密网格（RIDGE_SKELETON_SPACING=2）
        //       根因：主 bicubic 流程 spacing=4 把地形坡度稀释成 Δe/16，远低于 combiMask 阈值
        //       (rounding·onset≈0.0125) → combiMask 恒≈0 → 条纹被完全淡出（山谷不可见）。
        //       用 spacing=2 采样真实地形恢复坡度 → combiMask 在真实陡坡正常触发 → 脊-谷骨架成形。
        //       纯局部算子（每点独立 evaluate，世界坐标对齐）→ 跨 tile 无缝；陆地 mask 保护海洋深度一致性。
        float[][] coarseDeltaUp = null;
        float[][] coarseDeltaLR = null; // 保留 LR 网格供 pad 回退时采样
        int skelSpacing = RIDGE_SKELETON_SPACING;
        int skelExtra = 4;
        int skelCover = ERODE_TILE_SIZE + 2 * (ERODE_TILE_BORDER + skelExtra * skelSpacing);
        int skelExtLR = (int) Math.ceil((double) skelCover / skelSpacing) + 1;
        int skelStartX = Math.floorDiv(originX - skelExtra * skelSpacing, skelSpacing) * skelSpacing;
        int skelStartZ = Math.floorDiv(originZ - skelExtra * skelSpacing, skelSpacing) * skelSpacing;
        if (erosionOn) {
            RidgeValleyErosion.RidgeConfig rcfg = RidgeValleyErosion.RidgeConfig.fromConfig(cfg);
            if (rcfg.enabled) {
                double seaE = heightCurve.seaE();
                float[][] skelGrid = new float[skelExtLR][skelExtLR];
                // 类型权重同步采样：脊谷强度按类型调制（平原/高原少切保平坦，山地/丘陵强化崎岖）
                double[][] skelW = new double[skelExtLR][];
                for (int tz = 0; tz < skelExtLR; tz++) {
                    for (int tx = 0; tx < skelExtLR; tx++) {
                        int wx = skelStartX + tx * skelSpacing;
                        int wz = skelStartZ + tz * skelSpacing;
                        Cell c = sampleCore(wx, wz);
                        skelGrid[tz][tx] = (float) Math.max(c.e, -0.05);
                        if (skelW[tz] == null) skelW[tz] = new double[skelExtLR * 5];
                        double[] w = c.typeWeights;
                        if (w != null) {
                            skelW[tz][tx * 5] = w[TerrainClass.PLAIN.ordinal()];
                            skelW[tz][tx * 5 + 1] = w[TerrainClass.HILLS.ordinal()];
                            skelW[tz][tx * 5 + 2] = w[TerrainClass.MOUNTAINS.ordinal()];
                            skelW[tz][tx * 5 + 3] = w[TerrainClass.PLATEAU.ordinal()];
                            skelW[tz][tx * 5 + 4] = w[TerrainClass.BASIN.ordinal()];
                        }
                    }
                }
                coarseDeltaLR = RidgeValleyErosion.computeCoarseDelta(
                        skelGrid, skelExtLR, skelSpacing, skelStartX, skelStartZ, (float) seaE, rcfg);
                // 类型差异化调制：平原/盆地 0.3、丘陵 ~0.65、山地 1.0、高原 ×(1-0.6×platW) 保平顶。
                // 连续权重场 → 调制连续 → 跨 tile 无缝。低地切谷减少还顺带改善海岸负偏。
                for (int tz = 0; tz < skelExtLR; tz++) {
                    if (skelW[tz] == null) continue;
                    for (int tx = 0; tx < skelExtLR; tx++) {
                        double mountW = skelW[tz][tx * 5 + 2];
                        double hillsW = skelW[tz][tx * 5 + 1];
                        double platW = skelW[tz][tx * 5 + 3];
                        double gain = Math.min(1.0, mountW + 0.5 * hillsW);
                        float typeMod = (float) ((0.30 + 0.70 * gain) * (1.0 - 0.6 * platW));
                        coarseDeltaLR[tz][tx] *= typeMod;
                    }
                }
                coarseDeltaUp = bilinearUpsample(coarseDeltaLR, skelExtLR, skelSpacing,
                        skelStartX, skelStartZ, N, originX, originZ);
            }
        }

        // 3) 保存 terrainE 原貌（用于算 delta）：同源保证相邻 tile delta 一致
        float[][] base = new float[N][N];
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                base[z][x] = (float) Math.max(terrainE(originX + x, originZ + z), -0.05);

        // 3.5) D8 流量累积（解析流功率侵蚀需要 flowDir + discharge，无论 riversEnabled 与否）
        long rkey = tileKey(tileCX, tileCZ);
        RiverTileData rd = computeRivers(tile, N);
        if (riversEnabled) {
            riverTileCache.put(rkey, rd);
        }

        // 4) 液滴侵蚀（单轮）+ 3-zone flat 边界上下文（用邻居 postErosion 消断差）
        if (erosionOn) {
            double seaE = heightCurve.seaE();
            int pad = 9;
            int bufSize = N + pad * 2;

            // flat 缓冲区直接用 terrainE（同源），替代 bicubic 插值场使相邻 tile 的
            // 侵蚀一致 → 消除 tile 边界上下文断裂。pad 区用 neighbor postErosion。
            float[] flat = new float[bufSize * bufSize];
            for (int fz = 0; fz < bufSize; fz++) {
                for (int fx = 0; fx < bufSize; fx++) {
                    int worldX = originX + fx - pad;
                    int worldZ = originZ + fz - pad;
                    float val;
                    if (worldX >= originX && worldX < originX + N &&
                        worldZ >= originZ && worldZ < originZ + N) {
                        // 关键：用 terrainE 而非 tile（bicubic），保证相邻 tile 同源
                        val = (float) Math.max(terrainE(worldX, worldZ), -0.05);
                        if (coarseDeltaUp != null) {
                            int lx = worldX - originX, lz = worldZ - originZ;
                            val += coarseDeltaUp[lz][lx];
                        }
                    } else {
                        val = readFlatBorder(tileCX, tileCZ, worldX, worldZ, originX, originZ);
                        if (Float.isNaN(val)) {
                            // 无邻居 postErosion 可用 → terrainE + coarseDelta（与 interior 同源，消除 pad 高度跳变）
                            val = (float) Math.max(terrainE(worldX, worldZ), -0.05);
                            if (coarseDeltaLR != null) {
                                val += sampleBilinear(coarseDeltaLR, skelExtLR, skelSpacing,
                                        skelStartX, skelStartZ, worldX, worldZ);
                            }
                        }
                    }
                    flat[fz * bufSize + fx] = val;
                }
            }
            float[] flatPre = flat.clone();

            erosion.runErosionOnFlat(flat, flatPre, bufSize, N, originX, originZ,
                (float) seaE, (float) erosionStr);

            for (int z = 0; z < N; z++)
                for (int x = 0; x < N; x++)
                    tile[z][x] = flat[(z + pad) * bufSize + (x + pad)];

            // 5) 轻量 Gaussian 已移除：原 bnd 列表 {40,44,48,...,88} 在 tile 内部每 4 块做一次
            //    5 点平滑，形成可见「网格条带」伪影（用户反馈 2026-07-31）。删除以恢复平滑地形。

        }

        // 6) delta 限幅：仅 per-cell 安全性限制（防单格暴切），不人为加类型/高度钳制。
        //    噪声已经调好地形类型与高度范围，侵蚀只叠加纹理，不该再有硬上限。
        //    0.22：脊谷陡坡脊点 delta 峰值随 strength=0.12 达 ≈0.25，0.22 仅截断 <5% 极值点
        //    （峰侧衰减后峰顶 delta=0 不受影响；截断点分散不形成平台）。
        float maxDeltaPerCell = 0.22f;
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                float val = tile[z][x] - base[z][x];
                if (val > maxDeltaPerCell) tile[z][x] = base[z][x] + maxDeltaPerCell;
                else if (val < -maxDeltaPerCell) tile[z][x] = base[z][x] - maxDeltaPerCell;
            }
        }

        // 7) 计算 delta + postErosion，构造 ErosionTileResult
        ErosionTileResult res = new ErosionTileResult();
        res.erosionRound = ++erosionRoundCounter;
        res.delta = new float[N][N];
        res.postErosion = new float[N][N];
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                res.postErosion[z][x] = tile[z][x];
                res.delta[z][x] = tile[z][x] - base[z][x];
            }
        }
        res.tileCX = tileCX;
        res.tileCZ = tileCZ;
        res.originX = originX;
        res.originZ = originZ;
        return res;
    }

    /**
     * 读取 tile 边界外的 flat 填充值。返回 Float.NaN 表示无邻居可用（调用方应回退采样）。
     * 优先级：左邻居 postErosion > 上邻居 postErosion > NaN（调用方回退）。
     */
    private float readFlatBorder(int tileCX, int tileCZ,
                                  int worldX, int worldZ,
                                  int originX, int originZ) {
        // 左邻居（worldX < originX）
        if (worldX < originX) {
            ErosionTileResult left = erosionTileCache.get(
                tileKey(tileCX - ERODE_TILE_CHUNKS, tileCZ));
            if (left != null) {
                int nx = worldX - left.originX;
                int nz = worldZ - left.originZ;
                if (nx >= 0 && nx < ERODE_TILE_SIZE && nz >= 0 && nz < ERODE_TILE_SIZE)
                    return left.postErosion[nz][nx];
            }
        }
        // 上邻居（worldZ < originZ）
        if (worldZ < originZ) {
            ErosionTileResult top = erosionTileCache.get(
                tileKey(tileCX, tileCZ - ERODE_TILE_CHUNKS));
            if (top != null) {
                int nx = worldX - top.originX;
                int nz = worldZ - top.originZ;
                if (nx >= 0 && nx < ERODE_TILE_SIZE && nz >= 0 && nz < ERODE_TILE_SIZE)
                    return top.postErosion[nz][nx];
            }
        }
        return Float.NaN; // 无邻居可用，调用方回退采样
    }

    /**
     * 从邻居 tile 缓存读取 postErosion（辅助方法，需传入 tileCX/CZ）。
     */
    private float readNeighborHeight(int ncx, int ncz, int worldX, int worldZ) {
        ErosionTileResult nr = erosionTileCache.get(tileKey(ncx, ncz));
        if (nr == null) return Float.NaN;
        int lx = worldX - nr.originX;
        int lz = worldZ - nr.originZ;
        if (lx >= 0 && lx < ERODE_TILE_SIZE && lz >= 0 && lz < ERODE_TILE_SIZE) {
            return nr.postErosion[lz][lx];
        }
        return Float.NaN;
    }

    private static long tileKey(int tileCX, int tileCZ) {
        return ((long) tileCX << 32) | (tileCZ & 0xFFFFFFFFL);
    }

/** D∞ 流量累积 + 河网检测（纯检测，不修改 tile 高度）。
     *
     * <p>不做河谷雕刻：雕刻会改 tile 高度，但 D8 流方向在 tile 边界不一致，
     * 导致边界两侧雕刻不同的格 → 断裂加剧。河道高度由侵蚀液滴自然塑造，
     * 河道填水由 fillRiverColumn 在 output 阶段处理，不影响地形连续性。</p>
     */
    private RiverTileData computeRivers(float[][] tile, int N) {
        RiverTileData rd = new RiverTileData();
        int[][] dir8 = {{0,-1},{1,0},{0,1},{-1,0},{1,-1},{1,1},{-1,1},{-1,-1}};
        float[] w8 = {1f, 1f, 1f, 1f, 0.7071f, 0.7071f, 0.7071f, 0.7071f};
        int total = N * N;

        int[] flowDir = new int[total];
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++) {
                float h = tile[z][x];
                float bestSlope = 0f;
                int bestD = -1;
                for (int d = 0; d < 8; d++) {
                    int nx = x + dir8[d][0], nz = z + dir8[d][1];
                    if (nx < 0 || nx >= N || nz < 0 || nz >= N) continue;
                    float slope = (h - tile[nz][nx]) * w8[d];
                    if (slope > bestSlope) { bestSlope = slope; bestD = d; }
                }
                flowDir[z * N + x] = bestD;
            }

        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(tile[b / N][b % N], tile[a / N][a % N]));

        float[][] dis = rd.discharge;
        float maxQ = 0f;
        for (int i = 0; i < total; i++) {
            int idx = order[i], cx = idx % N, cz = idx / N;
            float q = dis[cz][cx] + 1.0f;
            dis[cz][cx] = q;
            if (q > maxQ) maxQ = q;
            int d = flowDir[idx];
            if (d >= 0) dis[cz + dir8[d][1]][cx + dir8[d][0]] += q;
        }

        boolean[][] mask = rd.riverMask;
        float thr = Math.max(8.0f, maxQ * 0.02f);
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                if (dis[z][x] >= thr) mask[z][x] = true;

        // 3×3 膨胀
        boolean[][] src = new boolean[N][N];
        for (int z = 0; z < N; z++) System.arraycopy(mask[z], 0, src[z], 0, N);
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++) {
                if (!src[z][x]) continue;
                for (int dz = -1; dz <= 1; dz++) {
                    int nz = z + dz;
                    if (nz < 0 || nz >= N) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= N) continue;
                        mask[nz][nx] = true;
                        dis[nz][nx] = Math.max(dis[nz][nx], dis[z][x]);
                    }
                }
            }

        // 保存流向（D8 编码：0=N 1=E 2=S 3=W 4=NE 5=SE 6=SW 7=NW，-1=无流出）
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                rd.flowDir[z][x] = flowDir[z * N + x];

        return rd;
    }

    // ===== Catmull-Rom 双三次插值（全局对齐版，从 6 月备份 70cd037 GeoGenesisGenerator.java 移植） =====

    /**
     * 全局对齐的双三次插值：相邻 tile 在边界处使用相同的控制点 → 插值结果连续。
     *
     * @param lowRes 低分辨率高度图 [lowRes×lowRes]
     * @param lowResSize 低分辨率网格边长
     * @param spacing 低分辨率网格间距（世界单位）
     * @param alignedStartX 全局对齐的低分辨率网格起始 X（世界坐标）
     * @param alignedStartZ 全局对齐的低分辨率网格起始 Z（世界坐标）
     * @param tileStartX 当前 tile 起始 X（世界坐标）
     * @param tileStartZ 当前 tile 起始 Z（世界坐标）
     * @param tileSize 当前 tile 边长（世界单位）
     */
    /** 双线性升采样粗侵蚀 delta：lowRes[extLR×extLR]（世界间距 spacing，网格起点 alignedStart）→ N×N（tile 起点 origin）。 */
    private static float[][] bilinearUpsample(float[][] lowRes, int extLR, int spacing,
                                              int alignedStartX, int alignedStartZ,
                                              int N, int originX, int originZ) {
        float[][] out = new float[N][N];
        int last = extLR - 1;
        for (int z = 0; z < N; z++) {
            float lz = (float) (originZ + z - alignedStartZ) / spacing;
            int iz0 = Math.max(0, Math.min(last - 1, (int) Math.floor(lz)));
            int iz1 = Math.min(last, iz0 + 1);
            float fz = lz - iz0;
            for (int x = 0; x < N; x++) {
                float lx = (float) (originX + x - alignedStartX) / spacing;
                int ix0 = Math.max(0, Math.min(last - 1, (int) Math.floor(lx)));
                int ix1 = Math.min(last, ix0 + 1);
                float fx = lx - ix0;
                float v00 = lowRes[iz0][ix0], v10 = lowRes[iz0][ix1];
                float v01 = lowRes[iz1][ix0], v11 = lowRes[iz1][ix1];
                float top = v00 + (v10 - v00) * fx;
                float bot = v01 + (v11 - v01) * fx;
                out[z][x] = top + (bot - top) * fz;
            }
        }
        return out;
    }

    /** 从粗网格低分场采样单个世界坐标的双线性插值（用于 pad 回退，保持与 interior 同源）。 */
    private static float sampleBilinear(float[][] grid, int extLR, int spacing,
                                         int stX, int stZ, int worldX, int worldZ) {
        float lx = (float) (worldX - stX) / spacing;
        float lz = (float) (worldZ - stZ) / spacing;
        int last = extLR - 1;
        int ix = (int) Math.floor(lx);
        int iz = (int) Math.floor(lz);
        if (ix < 0 || ix >= last || iz < 0 || iz >= last) return 0f;
        float fx = lx - ix, fz = lz - iz;
        float v00 = grid[iz][ix], v10 = grid[iz][ix + 1];
        float v01 = grid[iz + 1][ix], v11 = grid[iz + 1][ix + 1];
        float top = v00 + (v10 - v00) * fx;
        float bot = v01 + (v11 - v01) * fx;
        return top + (bot - top) * fz;
    }

    private static float[][] bicubicUpsampleAligned(float[][] lowRes, int lowResSize, int spacing,
                                                     int alignedStartX, int alignedStartZ,
                                                     int tileStartX, int tileStartZ, int tileSize) {
        float[][] out = new float[tileSize][tileSize];
        int lrLast = lowResSize - 1;

        for (int fz = 0; fz < tileSize; fz++) {
            int worldZ = tileStartZ + fz;
            float lz = (float) (worldZ - alignedStartZ) / spacing;
            int iz = (int) lz;
            float tz = lz - iz;
            int z0 = Math.max(0, iz - 1), z1 = Math.min(lrLast, iz),
                z2 = Math.min(lrLast, iz + 1), z3 = Math.min(lrLast, iz + 2);

            for (int fx = 0; fx < tileSize; fx++) {
                int worldX = tileStartX + fx;
                float lx = (float) (worldX - alignedStartX) / spacing;
                int ix = (int) lx;
                float tx = lx - ix;
                int x0 = Math.max(0, ix - 1), x1 = Math.min(lrLast, ix),
                    x2 = Math.min(lrLast, ix + 1), x3 = Math.min(lrLast, ix + 2);

                float r0 = bspline(lowRes[z0][x0], lowRes[z0][x1], lowRes[z0][x2], lowRes[z0][x3], tx);
                float r1 = bspline(lowRes[z1][x0], lowRes[z1][x1], lowRes[z1][x2], lowRes[z1][x3], tx);
                float r2 = bspline(lowRes[z2][x0], lowRes[z2][x1], lowRes[z2][x2], lowRes[z2][x3], tx);
                float r3 = bspline(lowRes[z3][x0], lowRes[z3][x1], lowRes[z3][x2], lowRes[z3][x3], tx);

                out[fz][fx] = bspline(r0, r1, r2, r3, tz);
            }
        }
        return out;
    }

    /** 三次 B-spline 样条插值核：4 个控制点 + 参数 t ∈ [0,1]。
     *  B-spline 是 C2 连续，无 Catmull-Rom 的 overshoot（控制点处过冲伪影），
     *  适合地形插值——平滑且不会产生 chunk 边界周期性阶梯。 */
    private static float bspline(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return ((-p0 + 3f*p1 - 3f*p2 + p3) * t3
               + (3f*p0 - 6f*p1 + 3f*p2) * t2
               + (-3f*p0 + 3f*p2) * t
               + (p0 + 4f*p1 + p2)) * (1f/6f);
    }

    /**
     * 从侵蚀 tile 提取并填充 16×16 chunk cell。
     * 仅把侵蚀增量（侵蚀后 − 侵蚀前）叠加到最终 e，不重写 eLand。
     *
     * <p>海洋侧不再跳过侵蚀——插值场包含海洋真实深度（海山/洋中脊/深海），
     * ErosionEngine 通过 spawn 门控在浅海大陆架和近岸水下自然侵蚀，
     * 海底地形也会被沉积/剥蚀。</p>
     */
    public void extractFromTile(float[][] tile, Cell[] cells, int chunkX, int chunkZ) {
        int tileCX = Math.floorDiv(chunkX, ERODE_TILE_CHUNKS) * ERODE_TILE_CHUNKS;
        int tileCZ = Math.floorDiv(chunkZ, ERODE_TILE_CHUNKS) * ERODE_TILE_CHUNKS;
        int offsetX = (chunkX - tileCX) * 16 + ERODE_TILE_BORDER;
        int offsetZ = (chunkZ - tileCZ) * 16 + ERODE_TILE_BORDER;

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];
                double delta = (double) tile[offsetZ + lz][offsetX + lx];

                // 跨 tile 边界 delta 收敛（单向渐变）：相邻 tile 的液滴场独立（种子不同）→ 场不同。
                // chunk 右/下缘 4 块渐变读右/下邻 tile 的 delta（lx=13 全自己 → lx=15 全邻居场），
                // 而左/上缘用本 tile 自己的 delta（= 左/上邻 tile 的右/下缘已渐变到本场，两侧同场无缝）。
                // 邻居 delta 覆盖对应区域（其 tile 全尺寸含超区），同源有效。
                int cr = chunkX - tileCX; // 本 chunk 在 tile 内的序号 (0/1/2)
                int crz = chunkZ - tileCZ;
                if (cr == 2 && lx >= 12) {
                    // 右边缘：渐变读右邻 tile 的 delta（lx=12 全自己 → lx=15 全邻居场，边界列同场无缝）
                    ErosionTileResult nr = erosionTileCache.get(tileKey(tileCX + ERODE_TILE_CHUNKS, tileCZ));
                    if (nr != null) {
                        int worldX = chunkX * 16 + lx;
                        int worldZ = chunkZ * 16 + lz;
                        int nlx = worldX - nr.originX;
                        int nlz = worldZ - nr.originZ;
                        if (nlx >= 0 && nlx < ERODE_TILE_SIZE && nlz >= 0 && nlz < ERODE_TILE_SIZE) {
                            double nd = nr.delta[nlz][nlx];
                            double b = (lx - 12) / 3.0; // lx=12→0, lx=15→1 ✓
                            double blend = b * b * (3.0 - 2.0 * b);
                            delta = delta * (1.0 - blend) + nd * blend;
                        }
                    }
                } else if (crz == 2 && lz >= 12) {
                    // 下边缘：渐变读下邻 tile 的 delta（lz=12 全自己 → lz=15 全邻居场）
                    ErosionTileResult nb = erosionTileCache.get(tileKey(tileCX, tileCZ + ERODE_TILE_CHUNKS));
                    if (nb != null) {
                        int worldX = chunkX * 16 + lx;
                        int worldZ = chunkZ * 16 + lz;
                        int nlx = worldX - nb.originX;
                        int nlz = worldZ - nb.originZ;
                        if (nlx >= 0 && nlx < ERODE_TILE_SIZE && nlz >= 0 && nlz < ERODE_TILE_SIZE) {
                            double nd = nb.delta[nlz][nlx];
                            double b = (lz - 12) / 3.0; // lz=12→0, lz=15→1 ✓
                            double blend = b * b * (3.0 - 2.0 * b);
                            delta = delta * (1.0 - blend) + nd * blend;
                        }
                    }
                }

                // 对全地形施加侵蚀增量（**含海洋**）。
                // 原先用 `delta * cell.blendCont` 保护海洋侧（blendCont=0 → delta=0），
                // 但这导致**水下完全没有侵蚀**——河谷/水下峡谷被擦除，河口三角洲也异常平整。
                // 现已废除：ErosionEngine 自身已有 NaN/Inf 守卫（spd sqrt 处 + 末尾 clampF(-1,1)），
                // 不会再产生柱子伪影。delta 直接施加给所有地形（含海洋/陆架/深海）。
                // NaN/Inf 守卫：避免 fillTerrainColumn 因 (int)Math.floor(NaN)→0 把全列铺到世界底。
                double newE = cell.e + delta;
                if (Double.isNaN(newE) || Double.isInfinite(newE)) newE = cell.e;
                double e = softCapLandE(newE);
                cell.e = e;
                cell.height = heightCurve.heightFromE(e);

                // 重分类：仅陆地侧（e>=0），避免海洋的 12 类细分被破坏
                if (e >= 0) {
                    TerrainClass ct = TypeLandShape.dominantFromWeights(cell.typeWeights);
                    cell.terrainType = classifyTerrain(e, cell.eLand, ct, cell.temperature,
                        cell.humidity, cell.typeWeights, cell.coastCoord);
                }

                // 河流数据：从 river tile cache 读（河流关闭时重置为默认）
                if (riversEnabled) {
                    long tileKey = ((long) tileCX << 32) | (tileCZ & 0xFFFFFFFFL);
                    RiverTileData rd = riverTileCache.get(tileKey);
                    if (rd != null && rd.riverMask[offsetZ + lz][offsetX + lx]) {
                        cell.isRiver = true;
                        cell.riverWetness = 1.0;
                        cell.riverDistance = 0.0;
                        if (cell.height <= seaLevel() + 1.0) {
                            cell.riverMask = true;
                            cell.riverFloorY = cell.height - 0.5;
                            cell.riverSurfaceY = seaLevel();
                        } else {
                            cell.riverMask = false;
                            cell.riverFloorY = 0.0;
                            cell.riverSurfaceY = 0.0;
                        }
                        cell.riverNetDischarge = rd.discharge[offsetZ + lz][offsetX + lx];
                    } else {
                        cell.isRiver = false;
                        cell.riverMask = false;
                        cell.riverWetness = 0.0;
                        cell.riverDistance = 1.0;
                        cell.riverFloorY = 0.0;
                        cell.riverSurfaceY = 0.0;
                    }
                } else {
                    cell.isRiver = false;
                    cell.riverMask = false;
                    cell.riverWetness = 0.0;
                    cell.riverDistance = 1.0;
                    cell.riverFloorY = 0.0;
                    cell.riverSurfaceY = 0.0;
                }
            }
        }

        // 诊断：右边缘 delta 值（用于追踪 tile 边界断裂）。每10次记录一次以降低日志噪声。
        diagCount++;
        if (diagCount % 10 == 0) {
            double minD = 1, maxD = -1, avgD = 0;
            double reMin = 1, reMax = -1;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    double d = (double) tile[offsetZ + lz][offsetX + lx];
                    if (d < minD) minD = d;
                    if (d > maxD) maxD = d;
                    avgD += d;
                }
                double re = (double) tile[offsetZ + lz][offsetX + 15];
                if (re < reMin) reMin = re;
                if (re > reMax) reMax = re;
            }
            avgD /= 256;
            boolean atTileXEdge = (chunkX % ERODE_TILE_CHUNKS == 2); // 本 chunk 在 tile 右边缘（与下个 tile 交界）
            boolean atTileZEdge = (chunkZ % ERODE_TILE_CHUNKS == 2);
            DLOG.info("[DIAG-EXT] c({},{}): world=({},{}) tile=({},{}){} {}"
                + " delta min={} max={} avg={} | reMin={} reMax={}",
                chunkX, chunkZ, chunkX * 16, chunkZ * 16,
                tileCX, tileCZ,
                atTileXEdge ? " RIGHT-EDGE" : "",
                atTileZEdge ? " BOTTOM-EDGE" : "",
                String.format("%.4f", minD), String.format("%.4f", maxD), String.format("%.4f", avgD),
                String.format("%.4f", reMin), String.format("%.4f", reMax));
        }
    }

    /**
     * 连续形态分类（使用 typeWeights 连续权重替代离散 argmax）。
     * 海洋区域按特征分量细分：大陆架 / 洋中脊 / 海山 / 海洋 / 深海。
     */
    public TerrainClass classify(double c, double e, double eLand,
                                  TerrainClass cellType, double[] typeWeights,
                                  double temperature, double humidity,
                                  OceanFeatures.FeatureResult oceanFeat,
                                  LandFeatures.FeatureResult landFeat,
                                  double cEdge) {
        if (e < 0.0) {
            // 海洋地形细分
            double ridgeAmp = oceanFeat != null ? oceanFeat.ridge : 0;
            double seamountAmp = oceanFeat != null ? oceanFeat.seamount : 0;
            // SUBMARINE_RIDGE / SEAMOUNT 仅在大陆架以下（e < -0.08）的较深水域分类
            // 避免浅水/近岸被误判为海山
            if (ridgeAmp > 0.03 && e < -0.08) return TerrainClass.SUBMARINE_RIDGE;
            if (seamountAmp > 0.02 && e < -0.08) return TerrainClass.SEAMOUNT;
            if (e > -0.08) return TerrainClass.CONTINENTAL_SHELF;
            return e < -0.18 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }
        // BEACH：贴真实海岸线（e≈0 的陆地侧窄条）+ 位于海陆过渡带（cEdge 在 fade..ramp 内）。
        // 用有效岸线坐标 cEdge 判定，使沙滩跟随 e=0 等值线游走，而非钉在固定 c 阈值；
        // 过渡带约束排除内陆低地（盆地/平原）误判为沙滩。
        if (e < 0.04 && e > -0.03 && cEdge > oceanFadeStart && cEdge < landRampEnd) {
            return TerrainClass.BEACH;
        }

        // 火山优先（可见特征，用户核心诉求：陆地需有火山地形）。
        if (landFeat != null) {
            if (landFeat.single > 0.05) return TerrainClass.VOLCANO;
            if (landFeat.field > 0.04) return TerrainClass.VOLCANIC_FIELD;
        }

        // 取 MOUNTAINS 连续权重（而非离散 cellType == MOUNTAINS）
        double mountW = typeWeights != null && typeWeights.length > TerrainClass.MOUNTAINS.ordinal()
            ? typeWeights[TerrainClass.MOUNTAINS.ordinal()] : 0.0;

        // PEAK/SNOW 使用 post-blend e（而非预 blend eLand），
        // 确保海岸过渡带不被误标为雪峰材质（用户反馈：海岸边 SNOW 贴图硬切下降）。
        if (mountW > 0.35 && e > 0.60) {
            return TerrainClass.PEAK;
        }
        // SNOW 判定：高海拔 + 寒冷温度 + 湿度条件
        // 双曲线模型：cold+wet → 雪线低（容易积雪），cold+dry → 雪线高（难积雪）
        // 使用实例化 params 而非静态温度样条
        double snowBase = params.snowLine();
        double snowTempInf = params.snowLatitudeInfluence();
        double snowHumInf = params.snowHumidityInfluence();
        double tNorm = (temperature + 1.0) / 2.0;  // [0,1], cold=0, hot=1
        double hNorm = (humidity + 1.0) / 2.0;     // [0,1], dry=0, wet=1
        double effectiveSnowElev = snowBase + (tNorm - 0.5) * snowTempInf - (hNorm - 0.5) * snowHumInf;
        effectiveSnowElev = Math.max(0.02, Math.min(1.0, effectiveSnowElev));
        if (e > effectiveSnowElev && e > 0.15) {
            return TerrainClass.SNOW;
        }
        return cellType;
    }

    /**
     * 静态分类方法（侵蚀回写后重分类用）。
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature, double humidity) {
        return classifyTerrain(ne, eLand, cellType, temperature, humidity, null, 0.0);
    }

    /**
     * 静态分类方法（带连续类型权重 + coastCoord 海岸约束）。
     * PEAK/SNOW 使用 typeWeights 连续阈值，避免离散 argmax 跳变。
     * 海洋区域按深度细分：大陆架 / 海洋 / 深海（无特征分量，仅靠 e）。
     */
    public static TerrainClass classifyTerrain(double ne, double eLand,
                                                TerrainClass cellType,
                                                double temperature,
                                                double humidity,
                                                double[] typeWeights,
                                                double coastCoord) {
        if (ne < 0.0) {
            if (ne > -0.08) return TerrainClass.CONTINENTAL_SHELF;
            return ne < -0.18 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }
        // BEACH：贴真实海岸线（ne≈0 陆地侧窄条）+ 位于海陆过渡带（coastCoord 在 fade..ramp 内）。
        // coastCoord ≈ cEdge；过渡带约束排除内陆低地误判为沙滩。
        if (ne > -0.03 && ne < 0.04 && coastCoord > 0.0 && coastCoord < 0.08) {
            return TerrainClass.BEACH;
        }

        // 使用连续 typeWeights 判断 PEAK（用 ne 替代 eLand，与主 classify 一致）
        double mountW = typeWeights != null && typeWeights.length > TerrainClass.MOUNTAINS.ordinal()
            ? typeWeights[TerrainClass.MOUNTAINS.ordinal()] : 0.0;
        if (mountW > 0.35 && ne > 0.60) return TerrainClass.PEAK;

        // SNOW 判定：高海拔 + 寒冷温度 + 湿度（双曲线模型）
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        double snowBase = cfg != null ? cfgDbl(cfg.snowLine, 0.70) : 0.70;
        double snowTempInf = cfg != null ? cfgDbl(cfg.snowLatitudeInfluence, 0.25) : 0.25;
        double snowHumInf = cfg != null ? cfgDbl(cfg.snowHumidityInfluence, 0.15) : 0.15;
        double tNorm = (temperature + 1.0) / 2.0;
        double hNorm = (humidity + 1.0) / 2.0;
        double effectiveSnowElev = snowBase + (tNorm - 0.5) * snowTempInf - (hNorm - 0.5) * snowHumInf;
        effectiveSnowElev = Math.max(0.02, Math.min(1.0, effectiveSnowElev));
        if (ne > effectiveSnowElev && ne > 0.15) return TerrainClass.SNOW;
        if (typeWeights != null && typeWeights.length >= TerrainClass.COUNT) {
            return TypeLandShape.dominantFromWeights(typeWeights);
        }
        return cellType;
    }

    // ===== 内联工具 =====

    /**
     * 计算温度的"寒冷权重"（样条连续值）。
     * 综合 frozenWeight + coldWeight，用于 SNOW/冰雪判定。
     * 在温度阈值附近平滑过渡，避免硬边界跳变。
     */
    private static double temperatureColdWeight(double temperature) {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            ClimateSpline spl = ClimateSpline.temperature(
                cfg.tempFrozenThreshold.get(),
                cfg.tempColdThreshold.get(),
                cfg.tempWarmThreshold.get(),
                cfg.tempHotThreshold.get());
            // frozen + cold 区域权重之和
            return spl.zoneWeight(temperature, ClimateSpline.TEMP_FROZEN)
                 + spl.zoneWeight(temperature, ClimateSpline.TEMP_COLD);
        }
        // 默认：简单线性插值
        return temperature < -0.6 ? 1.0 : temperature < -0.2 ? (-0.2 - temperature) / 0.4 : 0.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 陆地 e 软钳制（实际地形 < 理论峰顶铁律）：
     * 类型高端 maxLandHi（默认 0.95 → 理论峰顶 Y≈288）之上渐近压缩、永不触顶，
     * 实际 e 恒低于 maxLandHi，HeightCurve 的 clamp(e×verticalScale,0,1) 永不触发 → 无压平山尖、
     * 不超配置地形图表的理论最高高度。海洋侧（e≤0）不受影响（clamp 下界 -1 保底）。
     */
    private double softCapLandE(double e) {
        if (e <= 0.0) return Math.max(-1.0, e);
        double maxLandHi = params.splineConfig().maxLandHi();
        double softStart = maxLandHi * 0.92; // 基础地形 ×0.9 后峰顶 0.855 < 0.874 → 正常地形不触发，仅液滴极端叠加兜底
        if (e <= softStart) return e;
        double cap = maxLandHi * 0.98;       // 实际天花板（默认 0.931），低于理论 0.95
        double span = cap - softStart;
        return softStart + span * (1.0 - 1.0 / (1.0 + (e - softStart) / span));
    }
    private static double saturate(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = saturate((x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * 安全读取 Forge 配置值。
     * 游戏内（config 已加载）→ 正常返回 toml 值；独立预览/探针（config 未加载运行时）
     * 的 {@code ConfigValue.get()} 会抛 IllegalStateException，此时回退到代码默认值。
     * 仅在配置未加载时改变行为，不影响游戏内结果。
     */
    private static double cfgDbl(net.minecraftforge.common.ForgeConfigSpec.DoubleValue v, double fallback) {
        try {
            return v.get();
        } catch (IllegalStateException ex) {
            return fallback;
        }
    }

    private static boolean cfgBool(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v, boolean fallback) {
        try {
            return v.get();
        } catch (IllegalStateException ex) {
            return fallback;
        }
    }

    /** 采样大陆性快捷接口 */
    public double sampleContinent(double wx, double wz) {
        return continent.sample(wx, wz);
    }

    /** HeightCurve 引用 */
    public HeightCurve heightCurve() { return heightCurve; }

    /** TypeLandShape 引用 */
    public TypeLandShape typeLandShape() { return typeLandShape; }

    // ===== 诊断访问器 =====

    /** 包级私有：获取缓存的侵蚀 tile 结果（用于 ErosionTileProbe 诊断）。返回 null 若 tile 未生成。 */
    ErosionTileResult getTileResult(int tileCX, int tileCZ) {
        return erosionTileCache.get(tileKey(tileCX, tileCZ));
    }

    /** 设置河流系统开关（诊断时关闭以隔离变量）。 */
    void setRiversEnabled(boolean enabled) {
        this.riversEnabled = enabled;
        if (!enabled) riverTileCache.clear(); // 关闭时清理已缓存的河网数据
    }
}
