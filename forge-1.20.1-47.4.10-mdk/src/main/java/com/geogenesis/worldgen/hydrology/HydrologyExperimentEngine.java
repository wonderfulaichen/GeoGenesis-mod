package com.geogenesis.worldgen.hydrology;

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
        // ★ 剖面锚定与雕刻基线同源：用无侵蚀 sample().height（= carveChunk 的 originalGround）。
        //   旧 sampleWu 含侵蚀 delta，与方块雕刻基线系统性偏差数格 → 水面判高/判低错乱
        //   （干河与悬浮并存）。选线/贴谷仍用 terrainEQuick（轻量、无 tile 依赖）。
        this.network = new RiverLineNetwork(terrain::terrainEQuick,
                (wx, wz) -> terrain.sample(wx, wz).height,
                terrain.heightCurve(), seed);
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
                    hit.dischargeArea(), outlet, hit.distToCenter() * scale));
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
