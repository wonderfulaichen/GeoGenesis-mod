package com.geogenesis.worldgen.hydrology;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
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
 * <p>旧 {@link RiverLineNetwork} 字段保留至 stage D 清理，<b>生产不再调用</b>。</p>
 */
public final class HydrologyExperimentEngine {
    /** 日志（诊断用）：确认骨架路线开关的【实际生效值】与回退原因。 */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("geogenesis");

    private final CellGenerator terrain;
    private final RiverLineNetwork network;
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
        // ★ 剖面锚定与雕刻基线同源【且无侵蚀】（2026-09-08 终版，恢复管线顺序）：
        //   历史教训两轮：
        //   (a) sampleWu 剖面 + sample 基线（不同源）→ 水面判高错乱；
        //   (b) 双双 sampleWu（2026-09-07 首修）→ 同源但河网构建期触发侵蚀 tile 冷生成，
        //       预览开窗即卡（用户："管线里河流在地形侵蚀前面"——建网不得吃侵蚀）。
        //   终版：双双 sample()（同源、无侵蚀、不碰 tile），侵蚀在落块合成时叠加
        //   （applyHydrologyChunk 的 delta 移位：含侵蚀高度 − 同量雕刻深度，水面同步
        //   抬升同一 delta）→ 侵蚀在游戏里生效且床面-水面关系严格不变。
        //
        // ★ 河网微自适应（2026-09-08，用户："让河流局部路线与河道生成匹配侵蚀后的
        //   地形"）：选线场（terrainEQuick 的 routingE）注入 tile delta（e 单位同量纲）
        //   → D8 汇流场变为"侵蚀后 e 场"，河线偏向侵蚀刻出的沟槽。
        //
        //   ★★ 性能红线（2026-09-08 实测教训，用户："半天没加载进游戏"）：
        //   建网覆盖【整个 region 的 D8 网格】（每格一次 routingE）→ 首次建 region
        //   会同步冷生成全域侵蚀 tile（数百个 × 100~400ms = 分钟级）——落块只需要
        //   玩家附近几个 tile，建网却是全域，"成本前置"根本不成立。因此默认必须
        //   关闭（routingDelta=null → 选线路径与旧代码逐位一致、零 tile 依赖），
        //   仅当 erosionRoutingAdaptive=true 时启用（toml 里手写，注释已警告代价）。
        //
        //   ★ 落块期"河道横向吸附"替代路径已实现并实测【无效回滚】（2026-09-08，
        //   RiverLineNetwork 注释块有完整数据）：河线与侵蚀沟大面积天然重合，
        //   触发率 2~5%、偏移 0.09wu 不可见——无收益，代码已删。
        RiverLineNetwork.ErosionDeltaSampler deltaSampler = null;
        double gain = 0.0;
        try {
            if (GeoGenesisConfig.INSTANCE.erosionEnabled.get()
                    && GeoGenesisConfig.INSTANCE.erosionRoutingAdaptive.get()) {
                deltaSampler = terrain::erosionDeltaE;
                gain = 1.0; // delta 已随 erosionStrength 缩放，线性跟随即可
            }
        } catch (IllegalStateException e) {
            // 预览/探针进程：Forge 配置未加载 → 微自适应关闭（保持零 tile 依赖）
        }
        this.network = new RiverLineNetwork(terrain::terrainEQuick,
                // ★ 2026-09-11 D13 修复（性能，零行为变化）：
                //   RiverLineNetwork.groundYAt 的文档契约本就是"terrainEQuick 派生 →
                //   保证 region 冷构建亚毫秒级"，但此处接线传了 terrain.sample(...).height
                //   （含气候/分类/群区，实测 ~0.5ms/次并被河网逐节点调用）
                //   → runFlowAccumProbe 的 coldMs 从基线 1849ms 涨到 ~13.6s。
                //   两者【逐位等价】：sampleCore 的 e 与 terrainEQuick 同源（同一连续场），
                //   且 sample() 不含侵蚀（侵蚀由 applyTileDelta 单独施加）。
                (wx, wz) -> terrain.heightCurve().heightFromE(terrain.terrainEQuick(wx, wz)),
                deltaSampler, gain,
                terrain.heightCurve(), seed, terrain.params().horizontalScale(),
                com.geogenesis.worldgen.hydrology.riverline.RiverLineParams.defaults());
        // ★ 2026-09-11 Phase C：降水驱动汇流累积 —— 只在此【生产接线处】注入；
        //   探针若不走本类则保持旧基线（纯面积累积），便于 A/B 对比。
        this.network.setPrecipSampler(terrain::precipitationAt,
                com.geogenesis.worldgen.hydrology.flowaccum.FlowField.PrecipWeights.defaults());
        // ★ 2026-09-18 水文 M2-C：水量平衡（沿程衰减 = 蒸发/入渗）—— 同样只在【生产接线处】注入。
        //   默认关闭（hydrologyDecayEnabled=false）⇒ decayClimate 为 null
        //   ⇒ RiverLineNetwork 走 9 参构造器（decay=0）⇒ 与旧行为【逐位一致】。
        //   开启后：干旱区蒸发强 ⇒ 内流河/时令河；湿润区 decay=0 ⇒ 河流穿流到海。
        // ★ 2026-09-22【河-湖水位同口径】注入【侵蚀后地形】采样器：
        //   雕刻侧湖面 = min(无侵蚀 spill, 侵蚀后坎高)；河尾必须用同一水位才能对接
        //   （用户实测："河流根本没有和湖泊高度对接上"）。
        try {
            com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork.erodedYSampler =
                    (a, b) -> terrain.sampleWu(a, b).height;
            // ★ 2026-09-24：路由专用【便宜且等价】的侵蚀后高度采样器（性能，实测 40~90s/region）
            com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork.routeHeightSampler =
                    (a, b) -> terrain.erodedHeightForRouting(a, b);
            // ★ 2026-09-23【P2-2】湖盆连通掩码格距 = 1 块（hs wu/块）
            com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork.lakeBasinFloodGrid =
                    Math.max(0.5, terrain.params().horizontalScale());
        } catch (RuntimeException ignore) {
            // 探针/无地形时保持 null ⇒ 退回无侵蚀 spill
        }
        // ★ 2026-09-22【侵蚀感知路由场】注入：修"河不贴谷"（路由场原为侵蚀前地形）。
        //   用 peek（非阻塞）—— 绝不触发侵蚀 tile 冷生成，未缓存处退化为侵蚀前地形。
        try {
            com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork.erosionDeltaProvider =
                    (a, b) -> {
                        java.util.OptionalDouble d = terrain.peekErosionDeltaE(a, b);
                        return d.isPresent() ? d.getAsDouble() : Double.NaN;
                    };
        } catch (RuntimeException ignore) {
            // 无侵蚀模块 ⇒ 保持 null（等同侵蚀前地形）
        }
        try {
            GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
            // ★ 2026-09-29【唯一水文管线】骨架路由不再是可选实验开关。
            //   历史 TOML 中的 hydrologySkeletonRouting 仍可存在，但不能让旧湖/旧河链
            //   重新成为生产路径；唯一生产语义是洼地蓄水 + 最低溢口续流。
            try {
                boolean configured = cfg.hydrologySkeletonRouting.get();
                RiverLineNetwork.flowSkeletonRouting = true;
                LOGGER.info("[RIVER] skeletonRouting forced=true (legacy config value ignored: {})",
                        configured);
            } catch (RuntimeException e) {
                RiverLineNetwork.flowSkeletonRouting = true;
                LOGGER.info("[RIVER] skeletonRouting forced=true (legacy config unavailable)");
            }
            if (cfg.hydrologyDecayEnabled.get()) {
                this.network.setDecayClimate(
                        new com.geogenesis.worldgen.hydrology.flowaccum.FlowField.DecayClimate(
                                cfg.hydrologyDecayMax.get(),
                                cfg.hydrologyDecayRef.get(),
                                cfg.hydrologyDecayExponent.get()));
            }
        } catch (IllegalStateException e) {
            // 预览/探针进程：Forge 配置未加载 ⇒ 衰减关闭（与旧行为逐位一致）
        }

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

    public RiverLineNetwork network() {
        return network;
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
                    false, true, surfaceY, null));
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
                surfaceY, null);
        return List.of(one);
    }

    public int cachedRegions() {
        return hydroSolver.cachedTiles();
    }

    public void setSeed(long seed) {
        network.setSeed(seed);
        // ★ 2026-09-30【必须把种子传给新核心】（此前只清缓存 ⇒ solver 恒用构造时的 seed=0
        //   ⇒ 生产水文用的是"别的世界的地形"，河与地形整体错位）。
        hydroSolver.setSeed(seed);
        hydroAdapter.clearIndex();
        hydroSampler.clear();
    }

    public void clear() {
        network.clear();
        hydroSolver.clearCache();
        hydroAdapter.clearIndex();
        hydroSampler.clear();
    }
}
