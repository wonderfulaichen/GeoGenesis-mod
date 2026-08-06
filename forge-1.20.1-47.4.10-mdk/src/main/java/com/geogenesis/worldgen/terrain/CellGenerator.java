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
 * 流程（v8.4 海陆类型化，2026-08-06 用户铁律："海陆就是 2 个大地形类型"）：
 *   ContinentField.sample → c ∈ [-1,1]
 *   ├─ 类型场 TerrainCharacterField：OCEAN/DEEP_OCEAN 与 5 陆地类型共同参与 Voronoi 细胞竞争，
 *   │    细胞类型概率由 c 调制（c 低→海洋细胞多，c 高→陆地细胞多；过渡带概率对半）
 *   ├─ TypeLandShape.sample → e = Σw·lo + Σw·(hi-lo)·modulated（全类型混合）：
 *   │    陆地类型 lo/hi = 类型样条区间；海洋类型 lo=hi=深度样条+seabed
 *   ├─ e 自然连续穿过 0 → 海陆边界 = Voronoi 类型竞争（400 块折线），
 *   │    与地形类型过渡（PLAIN↔MOUNTAINS）完全同构——海岸线由地形场自然决定
 *   └─ 大陆性 c 只做：细胞概率偏置 + 海洋深度样条 + 气候（湿度/温度），不直接决定海陆边界
 *
 * 气候（v2 增强模型）：
 *   温度 = sin²(z) 纬度基值 × 海洋性修正 − 海拔递减率 + 噪声
 *   湿度 = 大陆性距海 + 山地雨影 + 噪声
 */
public final class CellGenerator {

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
            // 2026-08-06 修复：depthMod 语义 = "深海加深 / 浅海不变"（x 越低值越大）→
            // 1-smoothstep(-0.6,-0.2,eBase)。原实现方向反（浅海 1.8/深海 0.6）。
            double depthMod = 0.6 + (1.0 - smoothstep(-0.6, -0.2, eBase)) * 1.2;
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
        // 2026-08-06 修复：depthMod = "深海加深/浅海不变" → 1-smoothstep(-0.6,-0.2,eBase)
        double depthMod = 0.6 + (1.0 - smoothstep(-0.6, -0.2, eBase)) * 1.2;
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

        // 4. 海岸线域扭曲（v8 CoastlineField）— 海洋深度/类型样条用的 c 空间位移（保留轻量扰动）。
        double cEdge = cBiased + coastline.warpDisplacement(sx, sz, cBiased);

        // 5. 全类型混合 e（2026-08-06 海陆类型化）：OCEAN/DEEP_OCEAN 已是类型场成员，
        //    e = Σw·lo + Σw·(hi-lo)·modulated → e 自然连续穿过 0，海陆边界 = Voronoi 类型竞争。
        double eLand = typeLandShape.sample(cellBlend, sx, sz, cEdge);

        // 6. 特征增量按类型权重调制（海洋特征→海洋权重；陆地火山→陆地权重），
        //    保证深海不被火山抬出海面、陆地不被海山垫高。
        LandFeatures.FeatureResult landFeat = landFeatures.compute(sx, sz);
        double oceanW = (cellBlend.typeWeights[TerrainClass.OCEAN.ordinal()]
            + cellBlend.typeWeights[TerrainClass.DEEP_OCEAN.ordinal()]);
        double landW = 1.0 - oceanW;
        eLand += landFeat.total * landW;
        // 2026-08-06 修复：移除整体 ×0.9 余量缩放。该设计为旧显式脊谷抬升（+0.17e）预留空间，
        // 显式抬升 2026-08-05 已全部移除 → ×0.9 只是无谓压低整个地形 10%
        // （用户反馈"有什么在限制着"；山脉 hi 0.95 实际仅 0.855）。softCapLandE（>0.9215 才压缩）
        // 仍是保险。样条/滑块设置的高度现在如实生效。
        cell.eLand = eLand;
        cell.landFeat = landFeat; // 缓存供 classify 使用，避免 sample() 重复 compute

        // 7. 连续主导类型（可能为 OCEAN/DEEP_OCEAN）
        TerrainClass cellType = TypeLandShape.dominantFromWeights(cellBlend.typeWeights);

        // 8. 海陆统一 e = 类型混合 + 海洋特征增量（海山/洋中脊按海洋权重平滑淡入）
        double e = softCapLandE(eLand + oceanFeat.total * oceanW);
        cell.e = e;
        cell.eOcean = eOcean;      // 海洋基面（预特征，分类/诊断用）
        cell.blendCont = oceanW;   // 语义（2026-08-06）：海洋类型权重和（原 cont 已废除）
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

    /** 诊断日志（[ErosionDIAG] 前缀，latest.log 可查） */
    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    private final ConcurrentHashMap<Long, ErosionTileResult> erosionTileCache = new ConcurrentHashMap<>(ERODE_TILE_CACHE_SIZE);
    /** 侵蚀配置指纹快照（2026-08-06）：配置改动 → 侵蚀/河流 tile 缓存失效，避免旧配置结果被复用 */
    private long lastCfgFingerprint = Long.MIN_VALUE;

    // ===== 河流（D8 流量累积 + V形河谷雕刻，纯局部无边界断裂） =====

    /** 每侵蚀 tile 的河流元数据（配合 ERODE_TILE_SIZE，128×128 格） */
    static class RiverTileData {
        boolean[][] riverMask = new boolean[ERODE_TILE_SIZE][ERODE_TILE_SIZE]; // 3×3 膨胀（灌水区）
        boolean[][] riverCore = new boolean[ERODE_TILE_SIZE][ERODE_TILE_SIZE]; // 河道中心线（原始 mask，未膨胀）
        float[][] discharge = new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE]; // 上游源头数（StreamTracer 累积，V 形雕刻深度归一化用）
        float[][] carveDepth = new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE]; // V 形河谷累计雕刻深度（水面计算用）
        float[][] distance = new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE]; // 到河道中心线距离场（TF-style 剖面用）
        float maxDischarge; // 全 tile 最大流量（雕刻深度归一化用）
    }

    /**
     * 侵蚀 tile 结果（2026-08-02 分级流水线）：
     * <ul>
     *   <li>base — 侵蚀前原貌（bicubic 插值场，L3 定稿；L1 雕刻后重算 delta 用）</li>
     *   <li>postErosion — 侵蚀后、雕刻前快照（L3 定稿，<b>永不改变</b>；本 tile L1 河流
     *       追踪界内采样场；出界采样统一 terrainE → 跨 tile 确定性完备）</li>
     *   <li>delta — 叠加量（L3 = 侵蚀增量；L1 后 = 侵蚀+雕刻增量，extractFromTile 用）</li>
     * </ul>
     */
    static class ErosionTileResult {
        float[][] base;
        float[][] delta;
        float[][] postErosion;
        float[][] discharge; // 液滴汇聚场（粒子路径重叠计数；2026-08-02 河流源头资格用）
        int tileCX, tileCZ;
        int originX, originZ;
        int erosionRound; // 版本号（保留字段，诊断用）
    }

    private final ConcurrentHashMap<Long, RiverTileData> riverTileCache = new ConcurrentHashMap<>(ERODE_TILE_CACHE_SIZE);

    /**
     * 获取或生成侵蚀 tile delta（分级流水线 L3→L1，2026-08-02）。接受 chunk 坐标，内部自动分组为 3×3 tile。
     *
     * <p><b>确定性生成（2026-08-01）</b>：flat 全源 terrainE + 粗骨架（无邻居依赖）→
     * tile 结果只与自身世界坐标有关，缓存淘汰后重建结果不变 → 相邻 chunk 无缝。</p>
     *
     * <p><b>分级调度（2026-08-02）</b>：
     * <ul>
     *   <li>L3 侵蚀：预生成 1 环邻居 tile（9 个 computeIfAbsent 顶层循环，避免嵌套递归异常）</li>
     *   <li>L1 河流：本 tile 追踪河流 + 河谷雕刻——此时 1 环邻居侵蚀已定稿，
     *       StreamTracer 出界采样读到的邻居场永远是确定值 → 河永远生成在确定完成的地形上</li>
     * </ul>
     * 返回 delta（L1 后 = 侵蚀+雕刻增量），以保持 extractFromTile 向后兼容。</p>
     */
    public float[][] getErosionTile(int chunkX, int chunkZ) {
        int tileCX = Math.floorDiv(chunkX, ERODE_TILE_CHUNKS) * ERODE_TILE_CHUNKS;
        int tileCZ = Math.floorDiv(chunkZ, ERODE_TILE_CHUNKS) * ERODE_TILE_CHUNKS;

        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        // 2026-08-06 修复：侵蚀 tile 缓存随配置失效——原只在 seed() 时 clear()，配置改动
        // （开/关骨架、侵蚀/河流参数）后旧 tile 仍被复用 → "实测没变化"实锤根因。
        long fg = cfg != null ? GeoGenesisConfig.configFingerprint() : 0L;
        if (fg != lastCfgFingerprint) {
            erosionTileCache.clear();
            riverTileCache.clear();
            lastCfgFingerprint = fg;
        }
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        // 2026-08-06 修复：骨架（脊-谷条纹）与液滴侵蚀解耦——仅开骨架(erosionRidgeEnabled)
        // 而 erosionEnabled=false 时，骨架也必须生效（原门控连带跳过 → 地形零变化）。
        boolean ridgeOn = cfg != null ? cfgBool(cfg.erosionRidgeEnabled, true) : true;
        if (!erosionOn && !ridgeOn) {
            return new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE];
        }

        // L3：预生成 1 环邻居侵蚀（先邻居后自己）。
        // 2026-08-03 死锁修复（回退版本重放）：原 computeIfAbsent 与 GeoGenesisTerrain.getChunkCells
        // 的 computeIfAbsent 形成嵌套锁（26 线程并发偶发死锁）。改 get + putIfAbsent。
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int ncx = tileCX + dx * ERODE_TILE_CHUNKS;
                int ncz = tileCZ + dz * ERODE_TILE_CHUNKS;
                long nk = tileKey(ncx, ncz);
                if (!erosionTileCache.containsKey(nk)) {
                    ErosionTileResult nr = generateErosionTile(ncx, ncz);
                    erosionTileCache.putIfAbsent(nk, nr); // 并发重复生成时结果确定性相同
                }
            }
        }
        // L1：本 tile 河流 + 雕刻（1 环侵蚀场已定稿）
        long key = tileKey(tileCX, tileCZ);
        ErosionTileResult res = erosionTileCache.get(key);
        if (res != null) ensureRiverTile(tileCX, tileCZ);
        return res != null ? res.delta : new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE];
    }

    /**
     * L1 河流段（分级流水线）：本 tile 追踪河流（StreamTracer）+ 河谷雕刻，只执行一次。
     * 前提：本 tile 与 1 环邻居的 L3 侵蚀均已定稿（getErosionTile 保证）。
     *
     * <p>雕刻在 postErosion 副本上做——postErosion 保持 L3 定稿不变（邻居出界采样场），
     * 雕刻增量写回 delta（extractFromTile 读它 → 最终地形含河谷）。</p>
     */
    private void ensureRiverTile(int tileCX, int tileCZ) {
        long rkey = tileKey(tileCX, tileCZ);
        if (riverTileCache.containsKey(rkey)) return; // 已 L1，幂等
        ErosionTileResult res = erosionTileCache.get(rkey);
        if (res == null) return; // 理论不触发（getErosionTile 先 L3）
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        if (!riversEnabled || !erosionOn) {
            riverTileCache.put(rkey, new RiverTileData());
            return;
        }
        int N = ERODE_TILE_SIZE;
        // 追踪场 = base（侵蚀前 = terrainE + 骨架，纯世界坐标对齐、重叠区逐格一致）。
        // 2026-08-02 关键结论：postErosion（液滴侵蚀后）在 tile 重叠区天然不一致
        // （~0.007e：相邻 tile 撒点 chunk 范围不同 → A 独有液滴流入重叠区侵蚀，B 无）
        // → StreamTracer 读 postErosion 必致两侧路径分叉 → seam 断流（探针实测重叠区
        // core 差异 42 格）。base 无此问题（骨架双线性升采样世界坐标对齐）。
        // 代价：河沿"侵蚀前场"追踪，槽刻在侵蚀后场，偏移 ≈ 液滴侵蚀量（<0.01e ≈ 3 块，
        // 小于槽宽 8 格）→ 视觉可接受。出界统一 terrainE（两侧同源，无缓存时序依赖）。
        float[][] base = res.base;
        StreamTracer.WorldHeight wh = (wx, wz) -> (float) Math.max(terrainE(wx, wz), -0.05);
        RiverTileData rd = StreamTracer.trace(base, N, res.originX, res.originZ,
                (float) heightCurve.seaE(), wh);
        computeDistanceField(rd, N);
        // 雕刻：postErosion 副本（L3 定稿不动），增量写回 delta
        float[][] carved = new float[N][N];
        float[][] post = res.postErosion;
        for (int z = 0; z < N; z++) System.arraycopy(post[z], 0, carved[z], 0, N);
        carveRiverValleys(carved, N, rd);
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                res.delta[z][x] = carved[z][x] - res.base[z][x];
        riverTileCache.put(rkey, rd);
    }

    /**
     * 生成侵蚀 tile：spacing=4 粗采 → 全局对齐 Catmull-Rom 双三次插值升采样
     * → 确定性 flat 缓冲区（全源 terrainE + 粗骨架，世界坐标对齐）
     * → 物理液滴侵蚀 → delta + postErosion 缓存。
     */
    private ErosionTileResult generateErosionTile(int tileCX, int tileCZ) {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        double erosionStr = cfg != null ? cfgDbl(cfg.erosionStrength, 1.0) : 1.0;
        // 骨架开关独立于液滴（2026-08-06：仅开骨架时也要生效）
        RidgeValleyErosion.RidgeConfig rcfg = cfg != null
            ? RidgeValleyErosion.RidgeConfig.fromConfig(cfg)
            : new RidgeValleyErosion.RidgeConfig();
        boolean ridgeOn = rcfg.enabled;

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
        float[][] coarseDeltaLR = null; // 低分辨率骨架网格（flat 全源双线性采样用）
        int skelSpacing = RIDGE_SKELETON_SPACING;
        int skelExtra = 4;
        int skelCover = ERODE_TILE_SIZE + 2 * (ERODE_TILE_BORDER + skelExtra * skelSpacing);
        int skelExtLR = (int) Math.ceil((double) skelCover / skelSpacing) + 1;
        int skelStartX = Math.floorDiv(originX - skelExtra * skelSpacing, skelSpacing) * skelSpacing;
        int skelStartZ = Math.floorDiv(originZ - skelExtra * skelSpacing, skelSpacing) * skelSpacing;
        // 2026-08-06 修复：骨架计算不再被液滴开关(erosionOn)门控，仅开骨架也生效
        if (erosionOn || ridgeOn) {
            if (ridgeOn) {
                double seaE = heightCurve.seaE();
                float[][] skelGrid = new float[skelExtLR][skelExtLR];
                // 2026-08-06 用户决策：骨架完全无类型限制（纯噪声地形上直接雕刻）。
                // 移除 typeMod 类型调制——原按类型权重在过渡带连续渐变 → 类型过渡带出现
                // 人为强度渐变带（用户反馈"骨架与地形类型打架，过渡带变明显"）。
                // 平原条纹弱由坡度自然控制（combiMask 坡度触发，平原坡度小→条纹弱），无需人工调制。
                for (int tz = 0; tz < skelExtLR; tz++) {
                    for (int tx = 0; tx < skelExtLR; tx++) {
                        int wx = skelStartX + tx * skelSpacing;
                        int wz = skelStartZ + tz * skelSpacing;
                        Cell c = sampleCore(wx, wz);
                        skelGrid[tz][tx] = (float) Math.max(c.e, -0.05);
                    }
                }
                coarseDeltaLR = RidgeValleyErosion.computeCoarseDelta(
                        skelGrid, skelExtLR, skelSpacing, skelStartX, skelStartZ, (float) seaE, rcfg);
            }
        }

        // 3) 保存 terrainE 原貌（用于算 delta）：同源保证相邻 tile delta 一致
        float[][] base = new float[N][N];
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                base[z][x] = (float) Math.max(terrainE(originX + x, originZ + z), -0.05);

        // 4) 液滴侵蚀（SH 多轮迭代）+ flat 全源（terrainE + 粗骨架，确定性）
        float[][] dischargeNxN = null; // 液滴汇聚场（粒子路径重叠计数）
        if (erosionOn) {
            double seaE = heightCurve.seaE();
            int pad = 9;
            int bufSize = N + pad * 2;

            // flat 缓冲区全源：terrainE + 粗骨架（双线性插值，世界坐标对齐）。
            // 2026-08-01 确定性化：去掉三区制（邻居 postErosion 依赖）→ tile 结果只
            // 依赖世界坐标，缓存淘汰后重建结果不变 → 相邻 chunk 无缝；收敛循环随之删除。
            float[] flat = new float[bufSize * bufSize];
            for (int fz = 0; fz < bufSize; fz++) {
                for (int fx = 0; fx < bufSize; fx++) {
                    int worldX = originX + fx - pad;
                    int worldZ = originZ + fz - pad;
                    float val = (float) Math.max(terrainE(worldX, worldZ), -0.05);
                    if (coarseDeltaLR != null) {
                        val += sampleBilinear(coarseDeltaLR, skelExtLR, skelSpacing,
                                skelStartX, skelStartZ, worldX, worldZ);
                    }
                    flat[fz * bufSize + fx] = val;
                }
            }
            float[] flatPre = flat.clone();

            // 2026-08-02 恢复 discharge 导出：液滴路径重叠计数 = 粒子汇聚数量
            // （河流源头资格；2026-08-01 曾因两套粒子系统停用）
            float[] dischargeBuf = new float[bufSize * bufSize];
            erosion.runErosionOnFlat(flat, flatPre, bufSize, N, originX, originZ,
                (float) seaE, (float) erosionStr, dischargeBuf);

            for (int z = 0; z < N; z++)
                for (int x = 0; x < N; x++)
                    tile[z][x] = flat[(z + pad) * bufSize + (x + pad)];
            dischargeNxN = new float[N][N];
            for (int z = 0; z < N; z++)
                for (int x = 0; x < N; x++)
                    dischargeNxN[z][x] = dischargeBuf[(z + pad) * bufSize + (x + pad)];

            // 5) 轻量 Gaussian 已移除：原 bnd 列表 {40,44,48,...,88} 在 tile 内部每 4 块做一次
            //    5 点平滑，形成可见「网格条带」伪影（用户反馈 2026-07-31）。删除以恢复平滑地形。

        } else if (ridgeOn && coarseDeltaLR != null) {
            // 仅骨架模式（液滴关）：tile = base + 骨架 delta（双线性采样，世界坐标对齐）
            for (int z = 0; z < N; z++) {
                for (int x = 0; x < N; x++) {
                    float d = sampleBilinear(coarseDeltaLR, skelExtLR, skelSpacing,
                        skelStartX, skelStartZ, originX + x, originZ + z);
                    tile[z][x] = base[z][x] + d;
                }
            }
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

        // 6.5/6.6) 河流追踪 + 河谷雕刻已移至 L1（ensureRiverTile，2026-08-02 分级流水线）：
        //   本 tile 升 L1 前，1 环邻居侵蚀先定稿（getErosionTile 预生成）；StreamTracer
        //   出界采样统一 terrainE（纯世界坐标函数）→ 任意 tile 追踪结果一致。本方法
        //   只做 L3（侵蚀），postErosion 保持雕刻前快照。

        // 7) 计算 delta + postErosion（雕刻前快照；雕刻增量由 L1 更新 delta），构造 ErosionTileResult
        ErosionTileResult res = new ErosionTileResult();
        res.erosionRound = ++erosionRoundCounter;
        res.base = base;
        res.delta = new float[N][N];
        res.postErosion = new float[N][N];
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                res.postErosion[z][x] = tile[z][x];
                res.delta[z][x] = tile[z][x] - base[z][x];
            }
        }
        res.discharge = dischargeNxN;
        res.tileCX = tileCX;
        res.tileCZ = tileCZ;
        res.originX = originX;
        res.originZ = originZ;
        return res;
    }

    private static long tileKey(int tileCX, int tileCZ) {
        return ((long) tileCX << 32) | (tileCZ & 0xFFFFFFFFL);
    }

    /** 8 邻方向增量（0=N 1=E 2=S 3=W 4=NE 5=SE 6=SW 7=NW），BFS 距离场（computeDistanceField）用 */
    private static final int[][] DIR8 = {{0,-1},{1,0},{0,1},{-1,0},{1,-1},{1,1},{-1,1},{-1,-1}};

    /**
     * 河道中心线 BFS 距离场（TF-style 河谷剖面用）：每格 = 到最近河道中心线（riverCore）的距离。
     * 直邻 +1、斜邻 +1.414（欧氏近似）。tile 全网格计算（含 40 块超区）→ 提取区（中心 48 块）
     * 及 valleyWidth 邻域内距离始终完整 → 相邻 tile 提取区雕刻一致 → 无缝。
     */
    private static void computeDistanceField(RiverTileData rd, int N) {
        float[][] dist = rd.distance;
        boolean[][] core = rd.riverCore;
        int[] qx = new int[N * N * 8], qz = new int[N * N * 8]; // 格子可被多轮松弛重复入队
        int head = 0, tail = 0;
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                if (core[z][x]) {
                    dist[z][x] = 0f;
                    qx[tail] = x; qz[tail] = z; tail++;
                } else {
                    dist[z][x] = Float.MAX_VALUE;
                }
            }
        }
        while (head < tail) {
            int x = qx[head], z = qz[head]; head++;
            float d = dist[z][x];
            for (int i = 0; i < 8; i++) {
                int nx = x + DIR8[i][0], nz = z + DIR8[i][1];
                if (nx < 0 || nx >= N || nz < 0 || nz >= N) continue;
                float nd = d + (i < 4 ? 1f : 1.414f);
                if (nd < dist[nz][nx]) {
                    dist[nz][nx] = nd;
                    qx[tail] = nx; qz[tail] = nz; tail++;
                }
            }
        }
    }

    /**
     * TF-style 河谷雕刻（2026-08-01 二版）：连续距离场剖面，取代离散笔刷。
     *
     * <p>公式移植自 TerraForged RiverCarver.carve（MIT）的河道级：</p>
     * <ul>
     *   <li>d ≤ bedWidth（河床半径 1.5）：完全下切到 bedLevel（河床平底，水面 3 格宽）</li>
     *   <li>bedWidth &lt; d &lt; bankWidth（岸坡半径 4）：lerp 渐变回原高 → <b>V 形斜坡</b></li>
     *   <li>深度随流量：主河 0.05e≈12 块，支流 0.02e≈5 块</li>
     * </ul>
     * <p>水面 = 槽底 + carveDepth ≈ 原地面（extractFromTile 用），河床水面等高、V 形深水
     * → 消除"1 格浅水三明治"。确定性 + 40 块超区保证跨 tile 一致。</p>
     */
    private static void carveRiverValleys(float[][] tile, int N, RiverTileData rd) {
        if (rd.maxDischarge <= 0) return;
        float[][] carve = rd.carveDepth;
        float[][] dist = rd.distance;
        float bedWidth = 1.5f;    // 河床半径（平底水面 3 格宽）
        float bankWidth = 4.0f;   // 岸坡半径（V 形斜坡区）
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                float d = dist[z][x];
                if (d >= bankWidth) continue;
                float base = tile[z][x];
                float riverAlpha = (d - bedWidth) / (bankWidth - bedWidth);
                riverAlpha = riverAlpha < 0f ? 0f : (riverAlpha > 1f ? 1f : riverAlpha);
                if (riverAlpha >= 1f) continue;
                float q = rd.discharge[z][x];
                float depth = 0.02f + (q / rd.maxDischarge) * 0.03f;
                float bedLevel = base - depth;
                float nh = bedLevel + (base - bedLevel) * riverAlpha;
                tile[z][x] = nh;
                carve[z][x] += base - nh;
            }
        }
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
    /** 从粗网格低分场采样单个世界坐标的双线性插值（flat 全源采样，世界坐标对齐 → 跨 tile 一致）。 */
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
                        // 2026-08-01：去掉高度限制（高山河道也灌水）；低地 clamp 到海平面与海连通。
                        // 水面 = 雕刻前地面高度（e 域恢复：carveDepth 是 e 单位，用 heightFromE 精确转 Y，
                        // 禁止 e 直接加 Y——曾导致水面≈槽底+0.5 的"1 格浅水三明治"）。
                        // 河道中心格 cell.height 是侵蚀后槽底（StreamTracer 已在侵蚀后场追踪）。
                        cell.riverMask = true;
                        cell.riverFloorY = cell.height - 0.5;
                        float carve = rd.carveDepth[offsetZ + lz][offsetX + lx];
                        double surfE = Math.min(1.0, cell.e + carve); // 雕前 e ≈ 原地面
                        cell.riverSurfaceY = Math.max(seaLevel(), heightCurve.heightFromE(surfE) + 0.5);
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
            double baseE = oceanFeat != null ? oceanFeat.baseE : e;
            double ridgeAmp = oceanFeat != null ? oceanFeat.ridge : 0;
            double seamountAmp = oceanFeat != null ? oceanFeat.seamount : 0;
            // 【2026-08-06 修复】深度判定改用基面 e（不含特征增量）：海山/洋中脊把 e 抬升后
            // 原 "e < -0.08" 自相矛盾（海山顶峰 e>-0.08 → 被判 SHELF，探针实测 SEAMOUNT 仅 11 个）。
            // SEAMOUNT 判定优先于 RIDGE（海山独立特征，避免被洋中脊抢走）。
            if (seamountAmp > 0.02 && baseE < -0.08) return TerrainClass.SEAMOUNT;
            // 2026-08-06 调稀洋中脊：阈值 0.03→0.05（用户反馈"洋中脊有点多"）——边缘弱贡献区
            // 归入 OCEAN/DEEP_OCEAN，仅脊线主体保留 RIDGE 类型（地形抬升不受影响）。
            if (ridgeAmp > 0.05 && baseE < -0.08) return TerrainClass.SUBMARINE_RIDGE;
            if (baseE > -0.08) return TerrainClass.CONTINENTAL_SHELF;
            return baseE < -0.18 ? TerrainClass.DEEP_OCEAN : TerrainClass.OCEAN;
        }
        // 【2026-08-03 用户决策】BEACH 不再作为独立地形类型（沙滩是海岸过渡带而非地形形态）：
        // 海岸窄条自然落入后续陆地类型（PLAIN/HILLS 等），群系层面仍由 BiomeClassifier 按气候映射。

        // 火山优先（可见特征，用户核心诉求：陆地需有火山地形）。
        if (landFeat != null) {
            if (landFeat.single > 0.05) return TerrainClass.VOLCANO;
            if (landFeat.field > 0.04) return TerrainClass.VOLCANIC_FIELD;
        }

        // 【2026-08-05 用户决策】PEAK 不再作为独立地形类型（山峰=山脉的高海拔部分，
        // 与 BEACH/SNOW 同为状态/过渡而非独立形态）：高海拔山地直接落入 MOUNTAINS
        // （typeWeights dominant），雪峰/尖峰群系由 BiomeClassifier 按 cell.e>0.60 映射，不丢失。
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
        // 【2026-08-03 用户决策】BEACH/SNOW 不再作为独立地形类型（见主 classify 注释）。
        // 【2026-08-05 用户决策】PEAK 不再独立分类（山峰=山脉高海拔部分，群系由
        // BiomeClassifier 按 cell.e 阈值映射），此处直接取 typeWeights 主导类型。
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
        // 2026-08-01：压缩窗口起点 0.92→0.97×maxLandHi（0.874→0.922）。
        // 原起点太贴上限：山峰自然分布 p99≈0.8745（MOUNTAINS p99=0.89）落在窗口内 →
        // 峰尖被压平（用户"山脉碰生成上限"观感，HeightHitProbe 实测 max=0.9044 且 2% 进入压缩区）。
        // 0.97 后 99% 山峰自然尖峰保留，仅超设计极限（>0.92）的极端叠加兜底压缩。
        double softStart = maxLandHi * 0.97;
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

    /** 包级私有：获取缓存的河流数据（用于 RiverSeamProbe 诊断）。返回 null 若 tile 未升 L1。 */
    RiverTileData getRiverTileData(int tileCX, int tileCZ) {
        return riverTileCache.get(tileKey(tileCX, tileCZ));
    }

    /** 设置河流系统开关（诊断时关闭以隔离变量）。 */
    void setRiversEnabled(boolean enabled) {
        this.riversEnabled = enabled;
        if (!enabled) riverTileCache.clear(); // 关闭时清理已缓存的河网数据
    }
}
