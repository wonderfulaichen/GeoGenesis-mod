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

    /** 兼容旧探针的 wu 直接采样（返回格点样本语义；河线版取最近命中）。 */
    public HydrologyRiverSample sample(double wx, double wz) {
        RiverLineNetwork.RiverLineHit hit = network.sample(wx, wz);
        if (hit == null) return null;
        int id = (int) Long.hashCode(Double.doubleToLongBits(wx) * 31
                + Double.doubleToLongBits(wz));
        return new HydrologyRiverSample(id, -1, hit.surfaceY(),
                hit.surfaceY() - hit.depth(), hit.width(), hit.depth(),
                hit.width() * 2.5, Math.max(hit.width() * 7.5, hit.width() * 3.0),
                hit.dischargeArea(),
                hit.reachesOcean() ? RiverOutlet.Type.OCEAN : null);
    }

    /** 上次 sampleBlock 是否直接命中河道（兼容旧 API；距离场版恒 true——null 即未命中）。 */
    public boolean lastQueryWasDirect() {
        return true;
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
