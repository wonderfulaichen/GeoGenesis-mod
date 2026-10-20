package com.geogenesis.worldgen.hydrology;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 水文实验接入门面（河线范式，2026-08-27 重构）。
 *
 * <p>内部委托 {@link RiverLineNetwork}：region 锚点分形河线 + 距离场采样。
 * 这是当前生产实现（由 GeoGenesisTerrain→HydrologyChunkEngine 驱动）；
 * 旧格点水文（HydrologySimulator/Adapter 等）已于 2026-08-29 整体移除。</p>
 */
public final class HydrologyExperimentEngine {
    /** 日志（诊断用）：确认骨架路线开关的【实际生效值】与回退原因。 */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("geogenesis");

    private final CellGenerator terrain;
    private final RiverLineNetwork network;

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
        // ⚠【2026-10 试验：河网基准对齐侵蚀后地形 —— 已撤销，理由见下】
        //
        //   试验内容：把选线场与地形基准都改为 terrainEQuick + peekErosionDeltaE
        //   （只读已缓存 tile，理论上零冷生成），期望让河流走在落地地形上。
        //
        //   实测（RiverBasisProbe，seed 9139912035078620160, centre (-140,137), r=200）：
        //     OFF  贴近无侵蚀 5666 (99.0%)  高出无侵蚀谷底 -14.92 / 高出含侵蚀谷底  -8.88
        //     ON   贴近无侵蚀 6533 (99.1%)  高出无侵蚀谷底 -10.96 / 高出含侵蚀谷底  -5.04
        //   ⇒ 偏差减 43%，但"更接近"仍 99%。**判据未达成，方案撤回。**
        //
        //   为什么无效：河网 region 是【懒构建】的，雕刻阶段（③）才用到它，
        //   而那时该列所在侵蚀 tile 未必已生成 ⇒ peek 大量返回 empty ⇒ 退回无侵蚀基准。
        //   若改用同步 erosionDeltaE，则正是本文件上方注释记录的旧陷阱
        //   （建网覆盖整个 region 的 D8 网格 ⇒ 冷生成数百个 tile = 分钟级卡死）。
        //
        //   结论：河网建在无侵蚀地形上，是【有代价的性能取舍】，不是简单的 bug。
        //   要真正对齐必须另立方案（例如把侵蚀纳入建网自身的地形场，且接受成本）。
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
            // ★ 2026-09-21【流体骨架路线开关】config 缺省 false ⇒ 零行为变更。
            //   打开即让河道折线走块分辨率流体模拟（物理正确路线）。
            try {
                boolean v = cfg.hydrologySkeletonRouting.get();
                RiverLineNetwork.flowSkeletonRouting = v;
                // ★ 2026-09-21【必须打日志】用户判据："我在游戏里面看到的还是旧版？"
                //   两条可能：① 磁盘 config 里仍是旧值 false（本次改动前生成的配置文件
                //   会永久保留旧值，默认值改了也没用）；② 骨架在建河里静默失败回退。
                //   这条日志直接给出【实际生效值】，把两种情况分开。
                LOGGER.info("[RIVER] skeletonRouting config={} => effective={}", v,
                        RiverLineNetwork.flowSkeletonRouting);
            } catch (RuntimeException e) {
                LOGGER.info("[RIVER] skeletonRouting config MISSING (old config file) => keep default={}",
                        RiverLineNetwork.flowSkeletonRouting);
            }
            if (cfg.hydrologyDecayEnabled.get()) {
                this.network.setDecayClimate(
                        new com.geogenesis.worldgen.hydrology.flowaccum.FlowField.DecayClimate(
                                cfg.hydrologyDecayMax.get(),
                                cfg.hydrologyDecayRef.get(),
                                cfg.hydrologyDecayExponent.get()));
            }
        } catch (IllegalStateException e) {
            // 预览/探针进程：Forge 配置未加载 ⇒ 衰减关闭（与旧行为逐位一致）。
            // ★ 2026-10【诊断一致性】骨架开关原本只从 Forge 配置读，探针恒为 false
            //   ⇒ 探针分析的河与游戏实际看到的河不是同一条（游戏 config=true）。
            //   此处补系统属性覆盖，使探针能复现游戏路径；生产仍以 Forge 配置为准。
            //   用法：-Dgeogenesis.skeletonRouting=true
            try {
                String v = System.getProperty("geogenesis.skeletonRouting");
                if (v != null) {
                    RiverLineNetwork.flowSkeletonRouting = Boolean.parseBoolean(v);
                    LOGGER.info("[RIVER] skeletonRouting SYSTEM-PROPERTY={} => effective={}",
                            v, RiverLineNetwork.flowSkeletonRouting);
                }
            } catch (RuntimeException ignore) {
                // 保持默认
            }
        }
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
     * 返回全部命中河线的块采样（按距离升序）。
     *
     * <p>★ DW 式多路径叠加：雕刻器对全部命中取最大雕刻量，
     * 相邻 region 河线切换点无缝过渡（连续函数的 max 仍连续）。</p>
     */
    public List<HydrologyBlockSample> sampleBlockAll(double blockX, double blockZ, double horizontalScale) {
        double scale = horizontalScale > 0.01 ? horizontalScale : 1.0;
        double wx = blockX / scale, wz = blockZ / scale;
        List<RiverLineNetwork.RiverLineHit> hits = network.sampleAll(wx, wz);
        if (hits.isEmpty()) return List.of();
        List<HydrologyBlockSample> out = new ArrayList<>(hits.size());
        for (RiverLineNetwork.RiverLineHit hit : hits) {
            double width = hit.width() * scale;
            double bankWidth = width * 2.5;   // bankFactor 与 RiverLineParams.defaults 一致
            double valleyWidth = Math.max(width + bankWidth, width * 3.0);
            // ★ 出口类型传播：海洋出口段（reachesOcean）→ OCEAN，供预览/诊断识别入海口
            RiverOutlet.Type outlet = hit.reachesOcean() ? RiverOutlet.Type.OCEAN : null;
            out.add(new HydrologyBlockSample(hit.surfaceY(), hit.surfaceY() - hit.depth(),
                    width, hit.depth(), bankWidth, valleyWidth,
                    hit.dischargeArea(), outlet, hit.distToCenter() * scale,
                    hit.fallDrop(), hit.frozen(), hit.isLake(), hit.bankSurfaceY(),
                    hit.lake()));
        }
        return out;
    }

    public int cachedRegions() {
        return network.cachedRegions();
    }

    public void setSeed(long seed) {
        network.setSeed(seed);
    }

    public void clear() {
        network.clear();
    }
}
