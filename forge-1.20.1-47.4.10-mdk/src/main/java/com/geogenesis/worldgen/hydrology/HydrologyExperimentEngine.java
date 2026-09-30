package com.geogenesis.worldgen.hydrology;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.sim.HydroCellSampler;
import com.geogenesis.worldgen.hydrology.sim.HydroConfig;
import com.geogenesis.worldgen.hydrology.sim.HydroFlowGeometry;
import com.geogenesis.worldgen.hydrology.sim.HydroSample;
import com.geogenesis.worldgen.hydrology.sim.HydroSampler;
import com.geogenesis.worldgen.hydrology.sim.HydroSamplingAdapter;
import com.geogenesis.worldgen.hydrology.sim.HydroWorldSolver;
import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 水文接入门面（2026-08-27 建立；★ 2026-09-29 换为唯一水文管线）。
 *
 * <p><b>当前实现</b>：采样委托 {@link HydroWorldSolver}（无限世界 + 完整生命周期：
 * 源头 → 汇流 → 洼地蓄水 → 最低溢口 → 继续下泄 → 入海）。雕刻层继续消费
 * {@code HydrologyBlockSample}，但每个量都只来自新核心。</p>
 *
 * <p><b>2026-09-30（stage D）</b>：旧 {@code RiverLineNetwork} 字段与全部接线已删除
 * —— 采样只走新核心，旧链不再是任何形式的回退（归档在 git 分支
 * {@code legacy-hydrology-archive}）。</p>
 */
public final class HydrologyExperimentEngine {
    /** 日志（诊断用）：确认骨架路线开关的【实际生效值】与回退原因。 */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("geogenesis");

    private final CellGenerator terrain;
    /** 唯一水文核心（无限世界求解器）。 */
    private final HydroWorldSolver hydroSolver;
    /** 全球水文格字段采样缓存（tile halo 重叠共享）。 */
    private final HydroCellSampler hydroSampler;
    /** 核心 → 雕刻层的唯一适配器。 */
    private final HydroSamplingAdapter hydroAdapter;
    /** horizontalScale（wu↔块换算，绿洲距离查询用）。 */
    private final double hydroScale;

    public HydrologyExperimentEngine(CellGenerator terrain, long seed) {
        this.terrain = terrain;
        // ★ 2026-09-30【旧链接线已整体删除 —— 结论留痕，勿再重加】
        //   原构造器在此创建并配置 `RiverLineNetwork`（即计划里的 stage D 清理项），含：
        //     · `new RiverLineNetwork(terrainEQuick …)` + `setPrecipSampler`（降水驱动汇流）；
        //     · 4 处静态字段注入：`erodedYSampler` / `routeHeightSampler` /
        //       `lakeBasinFloodGrid` / `erosionDeltaProvider`；
        //     · `flowSkeletonRouting = true`（强制骨架路线，配置值被忽略）；
        //     · `setDecayClimate`（水文 M2-C 水量平衡）。
        //   唯一管线化后这些已无任何消费者 —— 采样走 `sim/HydroWorldSolver`，
        //   继续初始化只是"给死链路供电"，并让读者误以为旧链仍然活着。
        //
        //   历史教训（保留，仍适用于新核心）：
        //     · 建网若覆盖整个 region 的 D8 网格 ⇒ 首次建 region 会同步冷生成全域
        //       侵蚀 tile（数百个 × 100~400ms = 分钟级）⇒ 选线场必须零 tile 依赖；
        //     · `groundYAt` 必须用 `terrainEQuick` 派生，否则 region 冷构建从亚毫秒
        //       涨到 ~13.6s（两者逐位等价）；
        //     · 河网微自适应（选线场注入侵蚀 delta）默认必须关闭 —— 成本前置不成立。

        // ★★★ 2026-09-29【唯一水文管线】新核心接线 ★★★
        //   sampleBlockAll 只走 HydroWorldSolver；水文状态的唯一定义在核心内部：
        //   洼地蓄水 = 湖、最低溢口 = 出流、D8 于填洼面 = 河。旧 RiverLineNetwork
        //   不再是生产采样路径（其字段保留到 stage D 清理，但生产不再调用）。
        double hs = 1.0;
        try {
            hs = terrain.params().horizontalScale();
        } catch (RuntimeException ignore) {
            // 探针/无参数环境：退回 1.0
        }
        final double scale = hs;
        boolean decayOn = false;
        double decayMax = 2e-3, decayRef = 0.7647, decayExp = 0.5;
        try {
            decayOn = GeoGenesisConfig.INSTANCE.hydrologyDecayEnabled.get();
            decayMax = GeoGenesisConfig.INSTANCE.hydrologyDecayMax.get();
            decayRef = GeoGenesisConfig.INSTANCE.hydrologyDecayRef.get();
            decayExp = GeoGenesisConfig.INSTANCE.hydrologyDecayExponent.get();
        } catch (IllegalStateException e) {
            decayOn = false;
        }
        final boolean fDecayOn = decayOn;
        final double fDecayMax = decayMax, fDecayRef = decayRef, fDecayExp = decayExp;

        HydroSampler sampler = new HydroSampler() {
            @Override
            public double height(double bx, double bz) {
                // 新水文必须采样玩家实际看到的【侵蚀后地形】；侵蚀 tile 是 (seed, 坐标) 确定性纯函数。
                return terrain.erodedHeightForRouting(bx / scale, bz / scale);
            }

            @Override
            public double source(double bx, double bz) {
                double wx = bx / scale, wz = bz / scale;
                double p = terrain.precipitationAt(wx, wz);
                return p > 0 ? p : 0;
            }

            @Override
            public double decay(double bx, double bz) {
                if (!fDecayOn) return 0;
                double wx = bx / scale, wz = bz / scale;
                double p = terrain.precipitationAt(wx, wz);
                // 与旧 FlowField.DecayClimate 同式：降水 >= ref ⇒ decay 恒 0
                double f = Math.max(0, 1 - p / Math.max(1e-9, fDecayRef));
                return fDecayMax * Math.pow(f, fDecayExp);
            }
        };
        this.hydroSampler = new HydroCellSampler(sampler);
        this.hydroSolver = new HydroWorldSolver(seed, HydroConfig.defaults(), hydroSampler);
        this.hydroAdapter = new HydroSamplingAdapter(hydroSolver, scale);
        this.hydroScale = scale;
    }

    /**
     * 到最近水体的距离（wu，与旧 {@code RiverLineNetwork.distanceToWater} 单位一致）——
     * 河流绿洲规则的输入。
     *
     * <p>★ 2026-09-29 起改走新核心（创建世界卡死的修复）：旧实现每个沙漠格触发
     * 旧链 region 构建 + 湖水位 sampleWu 生成远端侵蚀 tile（日志实测 605 个
     * 0.6~1.1s/个、region 0.9~16.7s/个 ⇒ 创建界面卡死十分钟级）。
     * 新实现命中<b>与雕刻同一个 solver/tile 缓存</b> ⇒ 边际成本≈0。</p>
     */
    public double distanceToWaterWu(double wuX, double wuZ) {
        double blocks = hydroAdapter.distanceToWater(wuX * hydroScale, wuZ * hydroScale);
        return blocks / hydroScale;
    }

    /** 新核心单格状态样本（诊断探针用，零副作用：命中与雕刻同一 solver 缓存）。 */
    public com.geogenesis.worldgen.hydrology.sim.HydroSample hydroSampleAt(double blockX,
                                                                          double blockZ) {
        return hydroAdapter.sample(blockX, blockZ);
    }

    /** 新核心到最近河道中心线的距离（块；无河道 = NaN。诊断探针用）。 */
    public double hydroDistanceToCenterline(double blockX, double blockZ) {
        return hydroAdapter.distanceToCenterline(blockX, blockZ);
    }

    /** 新核心求解器（诊断探针用：读闭包截断/环/未收敛计数）。 */
    public com.geogenesis.worldgen.hydrology.sim.HydroWorldSolver hydroSolver() {
        return hydroSolver;
    }

    public CellGenerator terrain() {
        return terrain;
    }

    /**
     * MC 块坐标采样（距离场版）。
     *
     * <p>block → wu = block ÷ horizontalScale；命中河线影响范围时返回
     * 含 distToCenter 的样本（雕刻器据此做连续断面），否则 null。</p>
     */
    public HydrologyBlockSample sampleBlock(double blockX, double blockZ, double horizontalScale) {
        List<HydrologyBlockSample> all = sampleBlockAll(blockX, blockZ, horizontalScale);
        if (all.isEmpty()) return null;
        HydrologyBlockSample best = all.get(0);
        for (HydrologyBlockSample s : all) {
            if (s.distToCenter() < best.distToCenter()) best = s;
        }
        return best;
    }

    /**
     * 返回命中水的块采样（按距离升序；当前实现至多 1 条 —— 单一真相）。
     *
     * <p><b>2026-09-29 唯一管线</b>：所有量取自 {@link HydroSample}：
     * 状态决定河/湖/海，水面与宽深取自同一份核心结果，{@code distToCenter} 取自核心中心线。
     * 不再有"湖位在这里算一遍、雕刻层再算一遍"的两套口径。</p>
     */
    public List<HydrologyBlockSample> sampleBlockAll(double blockX, double blockZ, double horizontalScale) {
        double scale = horizontalScale > 0.01 ? horizontalScale : 1.0;
        HydroSample s = hydroAdapter.sample(blockX, blockZ);
        if (s == null) return List.of();
        if (s.state() == com.geogenesis.worldgen.hydrology.sim.FlowState.SEA) return List.of();
        boolean isLake = s.state() == com.geogenesis.worldgen.hydrology.sim.FlowState.LAKE_STORAGE;
        double width = isLake ? Math.max(s.width(), scale) : s.width();
        double depth = isLake ? 0 : s.depth();
        double bankWidth = width * 2.5;
        double valleyWidth = Math.max(width + bankWidth, width * 3.0);
        double surfaceY = Double.isNaN(s.surfaceY()) ? s.terrainY() : s.surfaceY();
        // ★★★ 2026-09-29【湖列必须直接命中】★★★
        //   湖【没有中心线】——按 distToCenterline 过滤会把整片湖剔空：湖心离最近河段
        //   恒大于 valleyWidth ⇒ 湖列全部返回空 ⇒ 雕刻层 lakeSample=null ⇒ 不进湖分支 ⇒ 不灌水。
        //   端到端实测（runLakeHoleSplitProbe，seed 9139912035078620160 @(-140,137) r=400）：
        //   34 个湖中 31 个"生产湿(命中可达)=0"、P2-2 该淹未淹 ≈ 108 万块²。
        //   dist=0：同湖各列水位恒同（=同一 spillLevel），雕刻层"取 dist 最小的湖命中"不受影响；
        //   湖分支内按 1 块精度等高线（height < spill−0.5）判水 ⇒ 湖岸自然贴地。
        if (isLake) {
            return List.of(new HydrologyBlockSample(surfaceY, s.terrainY(), width, depth,
                    bankWidth, valleyWidth, s.discharge(), RiverOutlet.Type.LAKE, 0.0, 0.0,
                    false, true, surfaceY));
        }
        double dist = hydroAdapter.distanceToCenterline(blockX, blockZ);
        if (Double.isNaN(dist)) dist = 0;
        if (dist > valleyWidth) return List.of();          // 影响范围外：不雕
        double bedY = surfaceY - depth;
        RiverOutlet.Type outlet = switch (s.outletKind()) {
            case SEA -> RiverOutlet.Type.OCEAN;
            case SPILL_TO_BASIN, CLOSED_BASIN -> RiverOutlet.Type.LAKE;
            case SPILL_TO_TILE, BOUNDARY_PENDING -> RiverOutlet.Type.REGION_BOUNDARY;
            case LAND_SINK -> RiverOutlet.Type.LAND_SINK;
        };
        HydrologyBlockSample one = new HydrologyBlockSample(surfaceY, bedY, width, depth,
                bankWidth, valleyWidth, s.discharge(), outlet, dist, 0.0, false, isLake,
                surfaceY);
        return List.of(one);
    }

    public int cachedRegions() {
        return hydroSolver.cachedTiles();
    }

    public void setSeed(long seed) {
        // ★ 2026-09-30【必须把种子传给新核心】（此前只清缓存 ⇒ solver 恒用构造时的 seed=0
        //   ⇒ 生产水文用的是"别的世界的地形"，河与地形整体错位）。
        hydroSolver.setSeed(seed);
        hydroAdapter.clearIndex();
        hydroSampler.clear();
    }

    public void clear() {
        hydroSolver.clearCache();
        hydroAdapter.clearIndex();
        hydroSampler.clear();
    }
}
