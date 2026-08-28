package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.ClimateSpline;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.erosion.RidgeValleyErosion;
import com.geogenesis.worldgen.noise.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

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

    /**
     * 安全并行行循环（2026-08-14 恢复并行）：ForkJoinPool.commonPool（work-stealing）——
     * 调用线程可参与执行子任务 → 不会"等自己"死锁。曾用 TILE_SAMPLER 有界池 + latch.await
     * （池被占满 + CallerRunsPolicy 自己跑 = 池饥饿死锁，27 轮）。body 不得嵌套 parallelRows。
     */
    private static void parallelRows(int size, java.util.function.IntConsumer body) {
        if (size <= 4) {
            for (int i = 0; i < size; i++) body.accept(i);
            return;
        }
        IntStream.range(0, size).parallel().forEach(body);
    }

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
    /** 全 tile 版本号计数器（滑窗收敛用：每生成一次 tile 递增） */
    private int erosionRoundCounter = 0;

    // ===== 侵蚀 tile 采样并行（2026-08-09 无伤优化） =====
    // 线程安全依据：terrainEQuick 只读 final noise 实例 + 局部变量，无共享可变状态
    // （预览 TerrainPool 已多线程跑同一 CellGenerator，验证无竞态）。
    // ★ 池演进：fixed(4) → cached(无界) → ThreadPoolExecutor(有界, 2026-08-09 OOM 修复)。
    //   cached 无限线程 + 每线程 1MB 原生栈 + L3 递归提交 → 世界创建高峰期线程爆炸 → 
    //   原生内存 mmap 失败崩溃（hs_err_pid48068: "Native memory allocation (mmap) failed"）。
    //   有界池 + CallerRunsPolicy：队满时提交者自己跑（等价串行，天然反压，绝不排队饿死），
    //   线程数硬顶 16 → 内存可控。
    // ★ 2026-08-09 优化：4→8（20 核机器，冷启动 tile 排队吞吐 ×1.5-2；daemon 池不阻塞主线程）
    public static final int TILE_PARALLELISM = 8;
    public static final ExecutorService TILE_SAMPLER = new ThreadPoolExecutor(
        TILE_PARALLELISM, 16, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(64),
        r -> { Thread t = new Thread(r, "GeoGenesis-TileSampler"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.CallerRunsPolicy());

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
        boolean xsEnabled = cfg != null && cfgBool(cfg.erosionXSEnabled, false); // 细纹理层开关（默认关：实测加剧 chunk 边界脊）
        this.erosion = new ErosionEngine(erosionDropsMul, 12345, xsEnabled);

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

    /** TerrainParams 访问器（门面做块→wu 换算需要）。 */
    public TerrainParams params() { return params; }

    /**
     * 采样单格地形场（不含气候与分类）。供 {@link #sample} 与 {@link #terrainE}/{@link #landE} 复用。
     * 返回已设置 e/eLand/eOcean/blendCont/coastCoord/typeWeights/height/shape 的 Cell。
     *
     * <p><b>2026-08-10 wu 化（尺度解耦）</b>：坐标语义 = <b>wu（world unit）</b>，不再感知 MC 块。
     * 块→wu 换算只在 MC 门面 {@code GeoGenesisTerrain}（/horizontalScale）。噪声频率常量
     * 数值不变（语义块→wu），HS=1 时与旧实现逐位等价。</p>
     */
    private Cell sampleCore(double wx, double wz) {
        double sx = wx, sz = wz;

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
     * 坐标语义 = wu（2026-08-10 wu 化，见 {@link #sampleCore}）。
     */
    public Cell sample(double wx, double wz) {
        Cell cell = sampleCore(wx, wz);
        double sx = wx, sz = wz;

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

    /** 大陆性 c 采样（wu 语义，纯位置函数）。waterTable 水面推导用（RTF 范式河网）。 */
    public double continentAt(double wx, double wz) {
        return continent.sample(wx, wz);
    }

    /** 轻量地形 e（跳过气候/分类/height 映射/shape 赋值）。供侵蚀 tile 粗采/flat 用，
     *  省去温度/湿度噪声 + 分类 switch + heightFromE 样条 ≈ 省 30% 每次采样。
     *  坐标语义 = wu（2026-08-10 wu 化，见 {@link #sampleCore}）。 */
    public double terrainEQuick(double wx, double wz) {
        double sx = wx, sz = wz;

        // 1. 大陆性 c
        double c = continent.sample(sx, sz);
        double cBiased = c - continentBias;

        // 2. 海洋基面
        double eBase = heightCurve.eFromC(cBiased);
        double depthMod = 0.6 + (1.0 - smoothstep(-0.6, -0.2, eBase)) * 1.2;
        double seabed = seabedAmp * depthMod * seaBed.sample(sx, sz);
        double eOcean = (eBase + seabed) * oceanDepthFactor;

        // 3. 海洋特征
        OceanFeatures.FeatureResult oceanFeat = oceanFeatures.compute(sx, sz, Math.min(eOcean, 0.0), cBiased);
        eOcean += oceanFeat.total;

        // 4. 类型混合（Voronoi 场）
        TerrainCharacterField.BlendResult cellBlend = typeLandShape.sampleBlend(sx, sz);

        // 5. 海岸线扭曲
        double cEdge = cBiased + coastline.warpDisplacement(sx, sz, cBiased);

        // 6. 全类型混合 e
        double eLand = typeLandShape.sample(cellBlend, sx, sz, cEdge);

        // 7. 特征增量
        LandFeatures.FeatureResult landFeat = landFeatures.compute(sx, sz);
        double oceanW = cellBlend.typeWeights[TerrainClass.OCEAN.ordinal()]
            + cellBlend.typeWeights[TerrainClass.DEEP_OCEAN.ordinal()];
        eLand += landFeat.total * (1.0 - oceanW);

        // 8. 海陆统一 e
        return softCapLandE(eLand + oceanFeat.total * oceanW);
    }

    /** 纯陆地形态 eLand（侵蚀边际采样用，不含气候/分类）。 */
    public double landE(double wx, double wz) { return sampleCore(wx, wz).eLand; }

    /** 地形类型连续权重（诊断探针按类型分桶统计用）。 */
    public double[] typeWeightsAt(double wx, double wz) { return sampleCore(wx, wz).typeWeights; }

    // ===== 侵蚀 tile 缓存（超分辨率架构：spacing=4 粗采 + 双三次插值升采样，仿 6 月备份 70cd037） =====

    /** 每 tile 中心有效区边长（wu）。2026-08-10 wu 化：原 3×16 块（3 chunk 绑定）→ 48 wu 独立网格，
     *  chunk 覆盖数由 horizontalScale 决定（HS=1 → 3 chunk，HS=2 → 6 chunk），引擎不再感知块。 */
    private static final int ERODE_TILE_CENTER = 48;
    /** 边缘填充（wu，侵蚀 brush 上下文 + 接缝消除；数值保持 40 不变） */
    private static final int ERODE_TILE_BORDER = 40;
    /** tile 总边长（wu）：48 + 40×2 = 128（数值与 6 月备份一致） */
    private static final int ERODE_TILE_SIZE = ERODE_TILE_CENTER + ERODE_TILE_BORDER * 2;
        /** 粗采间距（spacing=4：128/4=32×32=1024 次 terrainE） */
        private static final int ERODE_SAMPLING_SPACING = 4;
        /** 骨架层（脊-谷条纹）采样间距：比主 bicubic 流程更密(2)，恢复被低分辨率稀释的坡度 → combiMask 正常触发 */
        private static final int RIDGE_SKELETON_SPACING = 2;
        /** 低分辨率网格边长 */
        private static final int ERODE_LOW_RES = ERODE_TILE_SIZE / ERODE_SAMPLING_SPACING;
    /** 缓存条目数（256 条目 ≈ 全部出生区域 tiles 常驻，无 LRU 驱逐） */
    private static final int ERODE_TILE_CACHE_SIZE = 256;
    /** tile 边界 blend 起始列/行（chunk 内部）。16-BLEND_START=10 块 blend 范围（原 4 块太窄，
     *  独立粒子模拟 delta 差异大时 smoothstep 不够 → 网格感）。 */
    private static final int BLEND_START = 6;

    /** 诊断日志（[ErosionDIAG] 前缀，latest.log 可查） */
    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    private final ConcurrentHashMap<Long, ErosionTileResult> erosionTileCache = new ConcurrentHashMap<>(ERODE_TILE_CACHE_SIZE);
    /** 侵蚀配置指纹快照（2026-08-06）：配置改动 → 侵蚀/河流 tile 缓存失效，避免旧配置结果被复用 */
    private long lastCfgFingerprint = Long.MIN_VALUE;

    /**
     * 侵蚀 tile 结果（2026-08-02 分级流水线，2026-08-09 内联终态发布）：
     * <ul>
     *   <li>base — 侵蚀前原貌（bicubic 插值场，L3 定稿）</li>
     *   <li>postErosion — 侵蚀后快照（L3 定稿，<b>永不改变</b>）</li>
     *   <li>delta — 叠加量（侵蚀增量，generateErosionTile 内一次写全，
     *       发布后永不再变；extractFromTile 读它 → 任意时序一致，无断裂）</li>
     * </ul>
     */
    static class ErosionTileResult {
        float[][] base;
        float[][] delta;
        float[][] postErosion;
        float[][] discharge; // 液滴汇聚场（粒子路径重叠计数；侵蚀副产品，供未来河流/诊断用）
        int tileCX, tileCZ;
        int originX, originZ;
        int erosionRound; // 版本号（保留字段，诊断用）
    }

    /**
     * 获取或生成侵蚀 tile delta（终态 = 侵蚀增量，2026-08-09 重构）。接受 chunk 坐标，内部自动分组为 3×3 tile。
     *
     * <p><b>确定性生成（2026-08-01）</b>：flat 全源 terrainE + 粗骨架（无邻居依赖）→
     * tile 结果只与自身世界坐标有关，缓存淘汰后重建结果不变 → 相邻 chunk 无缝。</p>
     *
     * <p><b>终态发布（2026-08-09）</b>：L1 河流已内联进 {@link #generateErosionTile}，
     * delta 一次写全（侵蚀+雕刻）后永不再变。本 tile 同步生成（调用线程直接算，绝不返回空），
     * 邻居 fire-and-forget 后台补全 + extractFromTile 消费时懒生成（终态安全，结果确定相同）。</p>
     *
     * <p>StreamTracer 自包含：界内读本 tile postErosion，出界统一 terrainEQuick（纯世界坐标函数），
     * 雕刻全在 tile 内 → L1 无邻居缓存依赖 → 任意生成时序下结果一致，无断裂。</p>
     */
    /**
     * 取侵蚀 tile（wu 坐标语义，2026-08-10 wu 化）。入参为 wu 坐标（MC 门面已 ÷horizontalScale）。
     * tile 网格 = 48 wu 中心区对齐（floorDiv → 与 HS 无关的固定 wu 网格，HS=1 时与旧 3-chunk 网格一致）。
     */
    public float[][] getErosionTile(int wuX, int wuZ) {
        int tileCX = Math.floorDiv(wuX, ERODE_TILE_CENTER) * ERODE_TILE_CENTER;
        int tileCZ = Math.floorDiv(wuZ, ERODE_TILE_CENTER) * ERODE_TILE_CENTER;

        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        // 2026-08-06 修复：侵蚀 tile 缓存随配置失效——原只在 seed() 时 clear()，配置改动
        // （开/关骨架、侵蚀/河流参数）后旧 tile 仍被复用 → "实测没变化"实锤根因。
        long fg = cfg != null ? GeoGenesisConfig.configFingerprint() : 0L;
        if (fg != lastCfgFingerprint) {
            erosionTileCache.clear();
            lastCfgFingerprint = fg;
        }
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        // 2026-08-06 修复：骨架（脊-谷条纹）与液滴侵蚀解耦——仅开骨架(erosionRidgeEnabled)
        // 而 erosionEnabled=false 时，骨架也必须生效（原门控连带跳过 → 地形零变化）。
        boolean ridgeOn = cfg != null ? cfgBool(cfg.erosionRidgeEnabled, true) : true;
        if (!erosionOn && !ridgeOn) {
            return new float[ERODE_TILE_SIZE][ERODE_TILE_SIZE];
        }

        // L3+L1：邻居惰性化 + 本 tile 同步（2026-08-09 深夜 3 重构 + 同日修复）。
        // 原实现：同步 await 8 邻居完成（串行/并行都锁死并行度——chunk(0,0) 冷启动 7s 实锤）。
        // 重构：1) 邻居 fire-and-forget 提交后台池（putIfAbsent 去重，幂等，跳过自己）
        //       2) 本 tile 直接同步计算（含 L1 河流，终态发布）→ 调用方立即拿到真 delta
        //       3) blend 阶段（extractFromTile）读邻居 null 时 → 同步懒生成该邻居（只算需要的 1-2 个）
        // 效果：chunk(0,0) 7s → 本 tile ~0.7s + 消费时懒邻居 ~0.7-1.4s
        int[] dirs = {-1, 0, 1};
        for (int dz : dirs) {
            for (int dx : dirs) {
                if (dx == 0 && dz == 0) continue; // 自己 → 下方同步计算
                int ncx = tileCX + dx * ERODE_TILE_CENTER;
                int ncz = tileCZ + dz * ERODE_TILE_CENTER;
                long nk = tileKey(ncx, ncz);
                if (erosionTileCache.containsKey(nk)) continue;
                // fire-and-forget：不 await，后台补全（putIfAbsent 去重，结果确定性相同）
                TILE_SAMPLER.execute(() -> {
                    try {
                        ErosionTileResult nr = generateErosionTile(ncx, ncz);
                        erosionTileCache.putIfAbsent(nk, nr);
                    } catch (CancellationException ce) {
                        Thread.currentThread().interrupt(); // 池线程被中断 → 静默放弃
                    }
                });
            }
        }
        // 本 tile：computeIfAbsent 原子生成（终态 delta = 侵蚀增量，一次写入永不再变）。
        // ★ 2026-08-09 优化：原 get→putIfAbsent 非原子，两线程并发时重复生成（日志实锤
        //   (-6,6) 700ms 级两次）；computeIfAbsent 让后到线程阻塞等待第一个完成 → 零重复。
        long key = tileKey(tileCX, tileCZ);
        ErosionTileResult res = erosionTileCache.computeIfAbsent(key, k -> generateErosionTile(tileCX, tileCZ));
        return res.delta;
    }

    /**
     * 生成侵蚀 tile：spacing=4 粗采 → 全局对齐 Catmull-Rom 双三次插值升采样
     * → 确定性 flat 缓冲区（全源 terrainE + 粗骨架，世界坐标对齐）
     * → 物理液滴侵蚀 → delta + postErosion 缓存。
     */
    private ErosionTileResult generateErosionTile(int tileCX, int tileCZ) {
        long tStart = System.nanoTime();   // PERF 诊断（2026-08-09，优化后保留观察）
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        boolean erosionOn = cfg != null ? cfgBool(cfg.erosionEnabled, true) : true;
        if (PROBE_SKELETON_ONLY) erosionOn = false;   // ★ 2026-08-12 探针诊断：只跑骨架（分离液滴贡献）
        double erosionStr = cfg != null ? cfgDbl(cfg.erosionStrength, 1.0) : 1.0;
        // 骨架开关独立于液滴（2026-08-06：仅开骨架时也要生效）
        RidgeValleyErosion.RidgeConfig rcfg0 = cfg != null
            ? RidgeValleyErosion.RidgeConfig.fromConfig(cfg)
            : new RidgeValleyErosion.RidgeConfig();
        // 2026-08-13：hs 以 TerrainParams 为准覆盖（fromConfig 读全局配置，探针进程为 null→1.0）
        rcfg0.horizontalScale = (float) params.horizontalScale();
        // 2026-08-13：探针/测试可覆盖骨架参数（无 Forge 环境时对齐 toml 真实配置）
        final RidgeValleyErosion.RidgeConfig rcfg = probeRidgeConfig != null ? probeRidgeConfig : rcfg0;
        boolean ridgeOn = rcfg.enabled;

        long tBaseEnd = System.nanoTime();  // PERF：base 采样结束
        long tStage3 = System.nanoTime();   // PERF：液滴阶段开始标记（防未进入 if 分支）
        long tStage4 = System.nanoTime();   // PERF：液滴阶段结束标记（防未进入 if 分支）
        // tileCX 已是 48 对齐 wu 坐标（wu 化后不再 ×16 换算）
        int originX = tileCX - ERODE_TILE_BORDER;
        int originZ = tileCZ - ERODE_TILE_BORDER;

        // 0) base 提前计算（2026-08-09 无伤优化：原第 3 步移前，粗采/骨架/骨架 flat 复用）
        //    保存 terrainE 原貌（用于算 delta）：同源保证相邻 tile delta 一致。
        //    提前后粗采（第 1 步）与骨架（第 2.5 步）在 base 覆盖区直接复用同值数组，
        //    省 ~5,249 次 terrainEQuick/tile（16,384+1,296+12,769 → 16,384+272+8,544）。
        //    值恒等式：base[x][z] = max(terrainEQuick, -0.05)，与粗采/骨架旧逻辑完全一致 → 输出不变。
        int N0 = ERODE_TILE_SIZE;
        float[][] base = new float[N0][N0];
        // ★ 2026-08-14 恢复并行（安全版：ForkJoinPool.commonPool work-stealing，见 parallelRows
        //   ——曾 TILE_SAMPLER 池 + latch.await 池饥饿死锁；曾串行恢复正确性但性能降）
        parallelRows(N0, z -> {
            for (int x = 0; x < N0; x++) {
                base[z][x] = (float) Math.max(terrainEQuick(originX + x, originZ + z), -0.05);
            }
        });
        tBaseEnd = System.nanoTime();   // PERF：base 采样结束

        // 1) spacing=4 粗采（全局对齐网格，扩展 2 格以消除 Catmull-Rom 边沿退化）
        int spacing = ERODE_SAMPLING_SPACING;
        int lowRes = ERODE_LOW_RES; // = 32
        int gridExtra = 2;
        int extendedLowRes = lowRes + gridExtra * 2; // = 36
        int alignedStartX = Math.floorDiv(originX, spacing) * spacing - gridExtra * spacing;
        int alignedStartZ = Math.floorDiv(originZ, spacing) * spacing - gridExtra * spacing;

        // ★ 2026-08-14 恢复并行（安全版，见 parallelRows）
        float[][] lowResBuf = new float[extendedLowRes][extendedLowRes];
        final float[][] lrb = lowResBuf; // lambda 捕获 final 引用（lowResBuf 后续重赋值）
        parallelRows(extendedLowRes, tz -> {
            for (int tx = 0; tx < extendedLowRes; tx++) {
                int wx = alignedStartX + tx * spacing;
                int wz = alignedStartZ + tz * spacing;
                if (wx >= originX && wx < originX + N0 && wz >= originZ && wz < originZ + N0) {
                    lrb[tz][tx] = base[wz - originZ][wx - originX];
                } else {
                    lrb[tz][tx] = (float) Math.max(terrainEQuick(wx, wz), -0.05);
                }
            }
        });

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

        long tStage1 = System.nanoTime();   // PERF：base+粗采+升采样结束
        // 2.5) 粗侵蚀骨架（脊-谷条纹滤镜）—— 作用于独立更密网格（RIDGE_SKELETON_SPACING=2）
        //       根因：主 bicubic 流程 spacing=4 把地形坡度稀释成 Δe/16，远低于 combiMask 阈值
        //       (rounding·onset≈0.0125) → combiMask 恒≈0 → 条纹被完全淡出（山谷不可见）。
        //       用 spacing=2 采样真实地形恢复坡度 → combiMask 在真实陡坡正常触发 → 脊-谷骨架成形。
        //       纯局部算子（每点独立 evaluate，世界坐标对齐）→ 跨 tile 无缝；陆地 mask 保护海洋深度一致性。
        float[][] coarseDeltaLR = null; // 低分辨率骨架网格（flat 全源双线性采样用）
        int skelSpacing = RIDGE_SKELETON_SPACING;
        // 2026-08-10: 4→8——骨架 evaluateCell 新增局部窗口抬升衰减（LIFT_WINDOW_R=8 格），
        // 扩展区必须容纳窗口使 flat 采样区内所有点的窗口读取落在已填充网格内（clamp 兜底）。
        int skelExtra = 8;
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
                // ★ 2026-08-14 恢复并行（安全版，见 parallelRows；曾串行——TILE_SAMPLER
                //   池 + latch.await 池饥饿死锁的替代方案）
                parallelRows(skelExtLR, tz -> {
                    for (int tx = 0; tx < skelExtLR; tx++) {
                        int wx = skelStartX + tx * skelSpacing;
                        int wz = skelStartZ + tz * skelSpacing;
                        if (wx >= originX && wx < originX + N0 && wz >= originZ && wz < originZ + N0) {
                            skelGrid[tz][tx] = base[wz - originZ][wx - originX];
                        } else {
                            skelGrid[tz][tx] = (float) Math.max(terrainEQuick(wx, wz), -0.05);
                        }
                    }
                });
                final float[][] deltaLR = new float[skelExtLR][skelExtLR];
                parallelRows(skelExtLR, tz -> {
                    for (int tx = 0; tx < skelExtLR; tx++) {
                        deltaLR[tz][tx] = RidgeValleyErosion.evaluateCell(
                            skelGrid, tx, tz, skelExtLR, skelSpacing, skelStartX, skelStartZ,
                            (float) seaE, rcfg);
                    }
                });
                coarseDeltaLR = deltaLR;
            }
        }

        // 3) （已移至第 0 步提前计算 base——粗采/骨架/骨架 flat 复用，省 ~5,249 次 terrainEQuick/tile）
        long tStage2 = System.nanoTime();   // PERF：骨架阶段结束

        // 4) 液滴侵蚀（SH 多轮迭代）+ flat 全源（terrainE + 粗骨架，确定性）
        float[][] dischargeNxN = null; // 液滴汇聚场（粒子路径重叠计数）
        if (erosionOn) {
            double seaE = heightCurve.seaE();
            int pad = 9;
            int bufSize = N + pad * 2;

            // flat 缓冲区全源：terrainEQuick + 粗骨架（双线性插值，世界坐标对齐）。
            // 2026-08-01 确定性化：去掉三区制（邻居 postErosion 依赖）→ tile 结果只
            // 依赖世界坐标，缓存淘汰后重建结果不变 → 相邻 chunk 无缝；收敛循环随之删除。
            // 2026-08-08 优化：内部区域（与 base 重叠）直接复用 base 值，仅 border 调 terrainEQuick。
            // ★ 2026-08-14 卡死修复：flat 构建改**串行**（曾并行 + latch.await → 池饥饿死锁）
            float[] flat = new float[bufSize * bufSize];
            final float[][] cdLR = coarseDeltaLR;
            // ★ 2026-08-14 恢复并行（安全版，见 parallelRows；曾串行）
            final int fPad = pad, fN = N;
            parallelRows(bufSize, fz -> {
                for (int fx = 0; fx < bufSize; fx++) {
                    int worldX = originX + fx - fPad;
                    int worldZ = originZ + fz - fPad;
                    int baseX = fx - fPad;
                    int baseZ = fz - fPad;
                    float val;
                    if (baseX >= 0 && baseX < fN && baseZ >= 0 && baseZ < fN) {
                        val = base[baseZ][baseX];
                    } else {
                        val = (float) Math.max(terrainEQuick(worldX, worldZ), -0.05);
                    }
                    if (cdLR != null) {
                        val += sampleBilinear(cdLR, skelExtLR, skelSpacing,
                                skelStartX, skelStartZ, worldX, worldZ);
                    }
                    flat[fz * bufSize + fx] = val;
                }
            });
            float[] flatPre = flat.clone();

            // 2026-08-02 恢复 discharge 导出：液滴路径重叠计数 = 粒子汇聚数量
            // （河流源头资格；2026-08-01 曾因两套粒子系统停用）
            float[] dischargeBuf = new float[bufSize * bufSize];
            tStage3 = System.nanoTime();   // PERF：液滴阶段开始
            // 2026-08-13：hs 从 TerrainParams 显式传入（引擎不再读全局配置——探针进程无 Forge
            // 环境时 INSTANCE=null→hs 恒 1.0，与游戏 HS=2 不一致）
            erosion.runErosionOnFlat(flat, flatPre, bufSize, N, originX, originZ,
                (float) seaE, (float) erosionStr, dischargeBuf, (float) params.horizontalScale());
            tStage4 = System.nanoTime();        // PERF：液滴阶段结束

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

        // 6.5/6.6) 河流追踪 + 河谷雕刻（L1）：2026-08-09 内联进本方法 → delta 终态发布。
        //   StreamTracer 出界采样统一 terrainEQuick（纯世界坐标函数）→ 任意 tile 追踪结果
        //   一致、无邻居缓存依赖 → 任意生成时序下 delta 相同 → 跨 tile/chunk 无缝。

        // 7) 计算 delta + postErosion（雕刻前快照），构造 ErosionTileResult
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
        if (++perfTileCount % 8 == 1) {
            double ms = (System.nanoTime() - tStart) / 1e6;
            double bMs = (tBaseEnd - tStart) / 1e6;      // base 采样
            double bicMs = (tStage1 - tBaseEnd) / 1e6;   // 粗采+平滑+bicubic 插值
            double skMs = (tStage2 - tStage1) / 1e6;     // 骨架
            double dMs = (tStage4 - tStage3) / 1e6;      // flat+液滴+平滑
            double dltMs = (System.nanoTime() - tStage4) / 1e6; // delta 并行计算
            String perf = String.format("[PERF] erosion tile (%d,%d) took %.0fms [base=%.0f bicubic=%.0f skeleton=%.0f drops=%.0f delta=%.0f]",
                tileCX, tileCZ, ms, bMs, bicMs, skMs, dMs, dltMs);
            LOGGER.info(perf);
            System.out.println(perf);
        }
        return res;
    }

    /** PERF 诊断：侵蚀 tile 耗时打印计数（每 8 个 tile 打印一次，诊断后删除） */
    private static int perfTileCount = 0;

    /** ★ 2026-08-12 探针诊断开关：true 时只跑骨架（分离液滴贡献） */
    public static volatile boolean PROBE_SKELETON_ONLY = false;
    /** 探针诊断：覆盖骨架配置（无 Forge 环境时对齐游戏 toml 参数，2026-08-13） */
    public static volatile RidgeValleyErosion.RidgeConfig probeRidgeConfig = null;

    /** 设置探针骨架配置覆盖（仅探针用，游戏不调用） */
    public void setRidgeConfig(RidgeValleyErosion.RidgeConfig rcfg) {
        probeRidgeConfig = rcfg;
    }

    private static long tileKey(int tileCX, int tileCZ) {
        return ((long) tileCX << 32) | (tileCZ & 0xFFFFFFFFL);
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
        // ★ 2026-08-14 恢复并行（安全版，见 parallelRows；曾串行——TILE_SAMPLER 池饥饿的替代方案）
        float[][] out = new float[tileSize][tileSize];
        int lrLast = lowResSize - 1;
        parallelRows(tileSize, fz -> {
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
        });
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
    /**
     * 从侵蚀 tile 提取并填充 16×16 chunk cell（wu 插值版，2026-08-10）。
     *
     * <p><b>wu 化核心</b>：块坐标 (bx,bz) → wu (bx/hs, bz/hs) → 落在 tile 网格（48wu 对齐）格点间
     * → 双线性插值读 delta/河数据。HS=1 时 wu=块整数 → 插值退化为直接索引，与旧实现逐位等价。</p>
     *
     * <p>仅把侵蚀增量（侵蚀后 − 侵蚀前）叠加到最终 e，不重写 eLand。海洋侧不跳过侵蚀
     * （插值场含海洋真实深度，ErosionEngine spawn 门控自行处理浅海/近岸）。</p>
     */
    public void extractFromTile(Cell[] cells, int chunkX, int chunkZ) {
        double hs = params.horizontalScale();
        double invHs = (hs > 0.01 && hs != 1.0) ? 1.0 / hs : 1.0;

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];
                int bx = chunkX * 16 + lx, bz = chunkZ * 16 + lz;
                double wuX = bx * invHs, wuZ = bz * invHs;
                applyTileDelta(cell, wuX, wuZ); // 含侵蚀增量 + height 重算 + 陆地重分类
            }
        }
    }

    /**
     * 对 Cell 施加 wu 坐标处的侵蚀增量（含 tile 边界 blend），并重算 height/重分类。
     * extractFromTile 与 sampleWu 共用的公共逻辑。
     */
    private void applyTileDelta(Cell cell, double wuX, double wuZ) {
        // ★ 2026-08-12 回退（半开归属方案经实测否决——48k+24 线引入新墙，且最初断崖根因是
        // SLOPE 0.0015 微坡墙而非 blend；恢复基线 floorDiv(wu,48) + 右/下缘 blend）
        int tileCX = Math.floorDiv((int) Math.floor(wuX), ERODE_TILE_CENTER) * ERODE_TILE_CENTER;
        int tileCZ = Math.floorDiv((int) Math.floor(wuZ), ERODE_TILE_CENTER) * ERODE_TILE_CENTER;
        ErosionTileResult res = getOrGenTile(tileCX, tileCZ);
        if (res == null) return; // 中断中止（不缓存半成品）→ 本格不施加 delta，chunk 由调用方丢弃/重采
        double delta = sampleTileField(res.delta, res.originX, res.originZ, wuX, wuZ);
        // ★ 2026-08-14 晚场（用户建议"流量累积图当梯度图用起来"）：RIVER_NETWORK 图层
        //   显示粒子侵蚀 discharge 场 = 流量累积图（液滴沿坡流动的集水累积，
        //   本质是地形梯度/流域方向的可视化）。
        if (res.discharge != null)
            cell.riverNetDischarge = sampleTileField(res.discharge, res.originX, res.originZ, wuX, wuZ);
        // RIVER_TYPE 图层与 isLake/lakeMask 统一由水文雕刻计划写入
        // （GeoGenesisTerrain.applyHydrologyValley / GeoGenesisGenerator.applyHydrologyChunk）。
        // 旧 RTF 河网已下线：此处曾对每个 cell 采样几何河网写入上述字段，既与水文结果
        // 互相覆盖造成误导，又是逐 cell 的无效开销。

        // tile 边界对称 4 向 blend + 角块双线性（2026-08-13 重新启用——上次试验参数未对齐
        // 作废：探针 ridge=2.0 vs 游戏 toml 0.75，hs 未参数化；现已全部对齐）。
        // 依据：液滴 delta 场在 tile 左缘（= 模拟域西界，液滴出生/截断不对称）固有突变
        // （x=-288 实测 3.6 块台阶，基础场 0.1 块连续）→ 需要 blend 吸收，单侧 10wu 不够。
        // X/Z 方向独立 smoothstep 因子 + 4 tile 双线性组合（不顺序覆盖，角块正确）。
        double w = 16 - BLEND_START;
        double rightEdge = tileCX + (double) ERODE_TILE_CENTER;
        double bottomEdge = tileCZ + (double) ERODE_TILE_CENTER;
        double fx = 0; int ncx = tileCX;
        if (rightEdge - wuX <= w) {
            double b = 1.0 - (rightEdge - wuX) / w;
            fx = b * b * (3.0 - 2.0 * b);
            ncx = tileCX + ERODE_TILE_CENTER;
        } else if (wuX - tileCX < w) {
            double b = 1.0 - (wuX - tileCX) / w;
            fx = b * b * (3.0 - 2.0 * b);
            ncx = tileCX - ERODE_TILE_CENTER;
        }
        double fz = 0; int ncz = tileCZ;
        if (bottomEdge - wuZ <= w) {
            double b = 1.0 - (bottomEdge - wuZ) / w;
            fz = b * b * (3.0 - 2.0 * b);
            ncz = tileCZ + ERODE_TILE_CENTER;
        } else if (wuZ - tileCZ < w) {
            double b = 1.0 - (wuZ - tileCZ) / w;
            fz = b * b * (3.0 - 2.0 * b);
            ncz = tileCZ - ERODE_TILE_CENTER;
        }
        if (fx > 0 || fz > 0) {
            // ★ 2026-08-14 性能修复（用户"没做河流前不崩，现在老崩"）：
            //   邻居 tile 用缓存优先（缺失不生成——d00 兜底已存在）。曾 getOrGenTile
            //   → 每 chunk 256 格 × 3 邻居 = 数百次同步生成（400-719ms/个）→ 世界生成
            //   慢到像崩溃。
            ErosionTileResult tx = erosionTileCache.get(tileKey(ncx, tileCZ));
            ErosionTileResult tz = erosionTileCache.get(tileKey(tileCX, ncz));
            ErosionTileResult txz = erosionTileCache.get(tileKey(ncx, ncz));
            double d00 = delta;
            double d10 = tx != null ? sampleTileField(tx.delta, tx.originX, tx.originZ, wuX, wuZ) : d00;
            double d01 = tz != null ? sampleTileField(tz.delta, tz.originX, tz.originZ, wuX, wuZ) : d00;
            double d11 = txz != null ? sampleTileField(txz.delta, txz.originX, txz.originZ, wuX, wuZ) : d00;
            delta = d00 * (1 - fx) * (1 - fz)
                  + d10 * fx * (1 - fz)
                  + d01 * (1 - fx) * fz
                  + d11 * fx * fz;
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
    }

    /**
     * wu 语义完整采样（含侵蚀 tile delta 叠加；SpikeLocateProbe 诊断用）。
     * 基础 sample + {@link #applyTileDelta}，与 extractFromTile 同源。
     */
    public Cell sampleWu(double wuX, double wuZ) {
        Cell cell = sample(wuX, wuZ);
        applyTileDelta(cell, wuX, wuZ);
        return cell;
    }

    /** 探针专用：直接生成/取 tile 结果（ErosionPeriodProbe 用，不经 getOrGenTile 的中断捕获）。 */
    public ErosionTileResult getErosionTileResultForProbe(int tileCX, int tileCZ) {
        long k = tileKey(tileCX, tileCZ);
        ErosionTileResult r = erosionTileCache.get(k);
        if (r == null) {
            r = generateErosionTile(tileCX, tileCZ);
            erosionTileCache.putIfAbsent(k, r);
        }
        return r;
    }

    /**
     * 取 tile 结果（缓存命中优先，缺失则同步生成——blend 懒邻居语义保留，并发安全 putIfAbsent）。
     *
     * <p><b>2026-08-10 中断安全（v2，修正过严缓存）</b>：预览拖动 cancelAll → Worker 线程中断 →
     * generateErosionTile 的 latch.await 抛 CancellationException（绝不"恢复标志后继续用未完成数据"）
     * → 此处返回 null，半成品不入缓存（永久污染 → 方块伪影）。
     * <b>但成功返回的 tile 无条件入缓存</b>——生成结果确定性完整，即使线程随后被 cancel(true)
     * 置了中断位也应缓存（否则拖动取消 → 已完成 tile 反复重算 → 面板卡顿）。
     * 注意：InterruptedException 抛出时中断位已被 JVM 清除，无需（也禁止）重新 interrupt()——
     * 重新置位会让后续任务的 isInterrupted() 检查误判，且不参与取消语义（取消判据 = canceled 标志）。</p>
     */
    private ErosionTileResult getOrGenTile(int tileCX, int tileCZ) {
        long k = tileKey(tileCX, tileCZ);
        ErosionTileResult r = erosionTileCache.get(k);
        if (r == null) {
            try {
                r = generateErosionTile(tileCX, tileCZ);
            } catch (CancellationException e) {
                return null; // 半成品（中断中止）不入缓存；InterruptedException 抛出时中断位已被清除
            }
            erosionTileCache.putIfAbsent(k, r); // 成功 = 完整 = 无条件缓存
            // ★ 2026-08-14 OOM 修复：有界驱逐——ERODE_TILE_CACHE_SIZE=256 只是初始容量，
            //   从未 prune（注释误称"常驻"）。玩家移动（视距 32）触发海量 tile 生成
            //   （每个含 delta/discharge 大数组 ~128KB+）→ 无限累积 → OOM（日志 172 行
            //   后停止、无堆栈）。超限删 1/8，删除后玩家回到该区域会重建（懒生成语义）。
            pruneErosionCache();
        }
        return r;
    }

    /** 侵蚀 tile 缓存有界驱逐（超 256 删 1/8；ConcurrentHashMap 弱一致迭代删除线程安全） */
    private void pruneErosionCache() {
        if (erosionTileCache.size() > ERODE_TILE_CACHE_SIZE) {
            var it = erosionTileCache.keySet().iterator();
            int toRemove = Math.max(1, erosionTileCache.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    /**
     * 从 tile 网格按 wu 坐标双线性插值读字段。HS=1 时 wu 为整数 → 直接索引（逐位等价旧实现）。
     * 越界返回 0（border 外无数据，delta=0 语义）。
     */
    private static float sampleTileField(float[][] field, int originX, int originZ, double wuX, double wuZ) {
        double lx = wuX - originX, lz = wuZ - originZ;
        int ix = (int) Math.floor(lx), iz = (int) Math.floor(lz);
        if (ix < 0 || ix >= ERODE_TILE_SIZE - 1 || iz < 0 || iz >= ERODE_TILE_SIZE - 1) {
            if (ix >= 0 && ix < ERODE_TILE_SIZE && iz >= 0 && iz < ERODE_TILE_SIZE) return field[iz][ix];
            return 0f;
        }
        float fx = (float) (lx - ix), fz = (float) (lz - iz);
        float v00 = field[iz][ix], v10 = field[iz][ix + 1];
        float v01 = field[iz + 1][ix], v11 = field[iz + 1][ix + 1];
        float top = v00 + (v10 - v00) * fx;
        float bot = v01 + (v11 - v01) * fx;
        return top + (bot - top) * fz;
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

    /**
     * discharge 场采样（wu 语义）——供 RIVER_NETWORK 预览图层使用。
     *
     * <p>双线性插值侵蚀 tile 的 discharge 数组。历史要点（保留以免回退）：
     * ①48 对齐中心（曾用 128 网格错位 → discharge 恒 0）；
     * ②<b>缓存优先（缺失返回 0 不生成）</b>——同步生成侵蚀 tile 约 670ms/个，
     * 会卡死首 chunk（曾见 place=2183/4964/6576ms）；
     * ③null 防御（CancellationException → 0）。</p>
     *
     * @return discharge 值（侵蚀关闭 / 无数据 = 0）
     */
    public double sampleDischarge(double wuX, double wuZ) {
        int tileCX = Math.floorDiv((int) Math.floor(wuX), ERODE_TILE_CENTER) * ERODE_TILE_CENTER;
        int tileCZ = Math.floorDiv((int) Math.floor(wuZ), ERODE_TILE_CENTER) * ERODE_TILE_CENTER;
        ErosionTileResult r = erosionTileCache.get(tileKey(tileCX, tileCZ));
        if (r == null) r = getOrGenTile(tileCX, tileCZ); // 探针/诊断：缺失生成（游戏路径用 cached 版）
        // ★ 2026-08-14 null 防御：tile 生成被取消（CancellationException → getOrGenTile
        //   返回 null）时返回 0——曾直接 r.discharge → NPE 中断 chunk 生成 → 河流雕刻被
        //   跳过（用户"实际河道完全没变动"候选根因）
        if (r == null) {
            if (dischargeDiagCount.getAndIncrement() < 5) {
                LOGGER.info("[RIVER-DIAG#{}] tile null tile=({},{}) thread={}",
                    dischargeDiagCount.get() - 1, tileCX, tileCZ, Thread.currentThread().getName());
            }
            return 0;
        }
        if (r.discharge == null) {
            if (dischargeDiagCount.getAndIncrement() < 5) {
                LOGGER.info("[RIVER-DIAG#{}] discharge null tile=({},{}) erosionOn={} thread={}",
                    dischargeDiagCount.get() - 1, tileCX, tileCZ, erosionEnabledDiag(),
                    Thread.currentThread().getName());
            }
            return 0;
        }
        double v = sampleTileField(r.discharge, r.originX, r.originZ, wuX, wuZ);
        // ★ 2026-08-14 诊断：前 5 次采样全打印（用 LOGGER——System.out 不写 latest.log，
        //   用户日志无 DIAG 的根因）
        if (dischargeDiagCount.getAndIncrement() < 5) {
            float max = 0; long nz = 0;
            for (float[] row : r.discharge) {
                for (float f : row) {
                    if (f > max) max = f;
                    if (f > 0.01f) nz++;
                }
            }
            LOGGER.info("[RIVER-DIAG#{}] sample=({},{})={} tile=({},{}) arrMax={} nz={} erosionOn={} thread={}",
                dischargeDiagCount.get() - 1, wuX, wuZ, v, tileCX, tileCZ, max, nz,
                erosionEnabledDiag(), Thread.currentThread().getName());
        }
        return v;
    }

    /**
     * discharge 场采样（缓存优先，**不触发生成**）——游戏河网构建用（性能铁律：
     * 生成版会同步生成侵蚀 tile 670ms/个 → 首 chunk 卡死 2-6s）。
     * tile 未生成（如 applyTileDelta 尚未覆盖的区域）→ 0（无引导，走地形评分，可接受）。
     */
    public double sampleDischargeCached(double wuX, double wuZ) {
        int tileCX = Math.floorDiv((int) Math.floor(wuX), ERODE_TILE_CENTER) * ERODE_TILE_CENTER;
        int tileCZ = Math.floorDiv((int) Math.floor(wuZ), ERODE_TILE_CENTER) * ERODE_TILE_CENTER;
        ErosionTileResult r = erosionTileCache.get(tileKey(tileCX, tileCZ));
        if (r == null || r.discharge == null) return 0;
        return sampleTileField(r.discharge, r.originX, r.originZ, wuX, wuZ);
    }

    /** 诊断：erosionEnabled 配置实际值（探针无 Forge 配置 → no-config） */
    private static String erosionEnabledDiag() {
        try {
            GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
            return cfg == null ? "no-config" : String.valueOf(cfg.erosionEnabled.get());
        } catch (Throwable t) {
            return "no-config(" + t.getClass().getSimpleName() + ")";
        }
    }

    /** ★ discharge 诊断计数（前 5 次采样打印） */
    private static final java.util.concurrent.atomic.AtomicInteger dischargeDiagCount = new java.util.concurrent.atomic.AtomicInteger();

}
