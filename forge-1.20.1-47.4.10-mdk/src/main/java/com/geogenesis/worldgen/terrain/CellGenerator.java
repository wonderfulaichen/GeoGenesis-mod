package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.ClimateSpline;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.noise.*;
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
    /** 世界种子（区域确定性侵蚀，保证缓存一致、无闪烁） */
    private long worldSeed = 12345L;

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
        cell.eLand = eLand;
        cell.landFeat = landFeat; // 缓存供 classify 使用，避免 sample() 重复 compute

        // 7. 连续主导类型
        TerrainClass cellType = TypeLandShape.dominantFromWeights(cellBlend.typeWeights);

        // 8. 连续混合（v8.3）：海陆地形 = 海洋噪声场 与 陆地噪声场 的平滑插值。
        double cont = smoothstep(oceanFadeStart, landRampEnd, cEdge); // 0(纯海)→1(纯陆)
        double e = (1.0 - cont) * eOcean + cont * eLand;
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

    // ===== 侵蚀 tile 缓存（超分辨率架构：spacing=4 粗采 + 双三次插值升采样，仿 6 月备份 70cd037） =====

    /** 每 tile 覆盖 chunk 数（3×3=9 chunk/tile，如 6 月备份 generateTileWithHydrology） */
    private static final int ERODE_TILE_CHUNKS = 3;
    /** 边缘填充块数（侵蚀 brush 上下文 + 接缝消除） */
    private static final int ERODE_TILE_BORDER = 40;
    /** tile 总边长：3×16 + 40×2 = 128（与 6 月备份的 generateTile 一致） */
    private static final int ERODE_TILE_SIZE = ERODE_TILE_CHUNKS * 16 + ERODE_TILE_BORDER * 2;
    /** 粗采间距（仿旧版 spacing=4：128/4=32×32=1024 次 terrainE 替换 6400 次） */
    private static final int ERODE_SAMPLING_SPACING = 4;
    /** 低分辨率网格边长 */
    private static final int ERODE_LOW_RES = ERODE_TILE_SIZE / ERODE_SAMPLING_SPACING;
    /** 缓存条目数（256 条目 ≈ 全部出生区域 tiles 常驻，无 LRU 驱逐） */
    private static final int ERODE_TILE_CACHE_SIZE = 256;

    private final ConcurrentHashMap<Long, float[][]> erosionTileCache = new ConcurrentHashMap<>(ERODE_TILE_CACHE_SIZE);

    /**
     * 获取或生成侵蚀 tile。接受 chunk 坐标，内部自动分组为 3×3 tile。
     *
     * <p>仿 6 月备份 70cd037 架构：spacing=4 粗采 → Catmull-Rom 双三次插值升采样到 ERODE_TILE_SIZE
     * → 物理液滴侵蚀 → delta 缓存。</p>
     */
    public float[][] getErosionTile(int chunkX, int chunkZ) {
        // chunk 坐标分组为 tile 坐标（lambda 需要 effectively final 变量）
        int tileCX = Math.floorDiv(chunkX, ERODE_TILE_CHUNKS) * ERODE_TILE_CHUNKS;
        int tileCZ = Math.floorDiv(chunkZ, ERODE_TILE_CHUNKS) * ERODE_TILE_CHUNKS;

        // 侵蚀关闭时返回零增量 tile，彻底跳过 terrainE 采样
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        if (!erosionOn) {
            return new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE]; // 全零 = 无增量
        }

        long key = ((long) tileCX << 32) | (tileCZ & 0xFFFFFFFFL);
        float[][] tile = erosionTileCache.get(key);
        if (tile == null) {
            tile = erosionTileCache.computeIfAbsent(key, k -> generateErosionTile(tileCX, tileCZ));
        }
        // 无 LRU 驱逐（缓存 ≥ 256，出生区域 tiles 常驻不会触发）
        return tile;
    }

    /**
     * 生成侵蚀 tile：spacing=4 粗采 → 全局对齐 Catmull-Rom 双三次插值升采样 → 侵蚀 → delta。
     *
     * <p>256 条目缓存无 LRU 驱逐，出生区域 tiles 常驻。</p>
     */
    private float[][] generateErosionTile(int tileCX, int tileCZ) {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        double erosionStr = cfg != null ? cfgDbl(cfg.erosionStrength, 1.0) : 1.0;

        int originX = tileCX * 16 - ERODE_TILE_BORDER;
        int originZ = tileCZ * 16 - ERODE_TILE_BORDER;

        // 1) spacing=4 粗采（全局对齐网格）
        int spacing = ERODE_SAMPLING_SPACING;
        int lowRes = ERODE_LOW_RES;
        int alignedStartX = Math.floorDiv(originX, spacing) * spacing;
        int alignedStartZ = Math.floorDiv(originZ, spacing) * spacing;

        float[][] lowResBuf = new float[lowRes][lowRes];
        for (int tz = 0; tz < lowRes; tz++) {
            for (int tx = 0; tx < lowRes; tx++) {
                int wx = alignedStartX + tx * spacing;
                int wz = alignedStartZ + tz * spacing;
                lowResBuf[tz][tx] = (float) Math.max(terrainE(wx, wz), -0.05);
            }
        }

        // 2) Catmull-Rom 双三次插值升采样到全分辨率
        int N = ERODE_TILE_SIZE;
        float[][] tile = bicubicUpsampleAligned(lowResBuf, lowRes, spacing,
            alignedStartX, alignedStartZ, originX, originZ, N);

        // 3) 保存插值原貌（用于算 delta）
        float[][] base = new float[N][N];
        for (int z = 0; z < N; z++)
            System.arraycopy(tile[z], 0, base[z], 0, N);

        // 4) 物理侵蚀（在全分辨率插值场上跑）
        if (erosionOn) {
            double seaE = heightCurve.seaE();
            erosion.applyErosionNormalized(tile, N, originX, originZ, (float) seaE, (float) erosionStr);
        }

        // 5) delta 缓存
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                tile[z][x] = tile[z][x] - base[z][x];

        return tile;
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

                float r0 = catmullRom(lowRes[z0][x0], lowRes[z0][x1], lowRes[z0][x2], lowRes[z0][x3], tx);
                float r1 = catmullRom(lowRes[z1][x0], lowRes[z1][x1], lowRes[z1][x2], lowRes[z1][x3], tx);
                float r2 = catmullRom(lowRes[z2][x0], lowRes[z2][x1], lowRes[z2][x2], lowRes[z2][x3], tx);
                float r3 = catmullRom(lowRes[z3][x0], lowRes[z3][x1], lowRes[z3][x2], lowRes[z3][x3], tx);

                out[fz][fx] = catmullRom(r0, r1, r2, r3, tz);
            }
        }
        return out;
    }

    /** Catmull-Rom 样条插值核：4 个控制点 + 参数 t ∈ [0,1] */
    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
            + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
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

                // 对全地形施加侵蚀增量（**含海洋**）。
                // 原先用 `delta * cell.blendCont` 保护海洋侧（blendCont=0 → delta=0），
                // 但这导致**水下完全没有侵蚀**——河谷/水下峡谷被擦除，河口三角洲也异常平整。
                // 现已废除：ErosionEngine 自身已有 NaN/Inf 守卫（spd sqrt 处 + 末尾 clampF(-1,1)），
                // 不会再产生柱子伪影。delta 直接施加给所有地形（含海洋/陆架/深海）。
                // NaN/Inf 守卫：避免 fillTerrainColumn 因 (int)Math.floor(NaN)→0 把全列铺到世界底。
                double newE = cell.e + delta;
                if (Double.isNaN(newE) || Double.isInfinite(newE)) newE = cell.e;
                double e = clamp(newE, -1.0, 1.0);
                cell.e = e;
                cell.height = heightCurve.heightFromE(e);

                // 重分类：仅陆地侧（e>=0），避免海洋的 12 类细分被破坏
                if (e >= 0) {
                    TerrainClass ct = TypeLandShape.dominantFromWeights(cell.typeWeights);
                    cell.terrainType = classifyTerrain(e, cell.eLand, ct, cell.temperature,
                        cell.humidity, cell.typeWeights, cell.coastCoord);
                }

                cell.isRiver = false;
                cell.riverMask = false;
                cell.riverWetness = 0.0;
                cell.riverDistance = 1.0;
                cell.riverFloorY = 0.0;
                cell.riverSurfaceY = 0.0;
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
}
