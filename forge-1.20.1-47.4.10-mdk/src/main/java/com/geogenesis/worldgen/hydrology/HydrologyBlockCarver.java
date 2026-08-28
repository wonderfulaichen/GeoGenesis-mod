package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.noise.NoiseUtil;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成 16×16 block 列的水文雕刻计划（河线距离场版）。
 *
 * <p>★ 核心语义（2026-08-28 单属主范式重构）：</p>
 * <ul>
 *   <li><b>纯距离场</b>：t = dist/width ∈ [0,1]，smoothstep 断面 ——
 *       中心全量下挖、边缘连续淡出，无二值判定、无最近邻吸附；</li>
 *   <li><b>单属主（Voronoi）</b>：每块只归<b>最近一条</b>河线
 *       （sampleBlockAll 已按距离升序，get(0) 即属主），carved/surface/bed/anyFill
 *       <b>全部同源</b> —— 根除旧"多线 MAX carve"的跨线劫持
 *       （远处高水面河线压低河床/抬高水面 → 水漫出河道/巨型矩形水体）；
 *       region 边界连续性由距离场 1-Lipschitz 保证（等距中线两侧取值一致，
 *       最多极小 kink，无 MAX 式 cliff）；</li>
 *   <li><b>只下挖</b>：carved = original − cut，cut ≥ 0；未命中河线处
 *       carved == original（零破坏纯噪声基础地形）；</li>
 *   <li><b>e 空间高度淡出</b>：地形 e ≥ fadeHighE 不雕，河流自然消失于山地；</li>
 *   <li><b>灌水</b>：dist ≤ width 且 carved &lt; surface − 0.5 且
 *       水深 ≤ depth + 1（低洼/海架列不灌 —— 海由海平面判定，与河分轨）；
 *       valley 区间只做谷壁塑形，绝不灌水。</li>
 * </ul>
 */
public final class HydrologyBlockCarver {

    private HydrologyBlockCarver() { }

    public static List<HydrologyBlockCarvedColumn> carveChunk(HydrologyExperimentEngine engine,
                                                                int chunkX, int chunkZ,
                                                                double horizontalScale,
                                                                double[] originalGround) {
        if (originalGround.length != 256) throw new IllegalArgumentException("expected 16x16 ground array");
        List<HydrologyBlockCarvedColumn> result = new ArrayList<>();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                // 与全工程一致：cells/originalGround 布局为 lx * 16 + lz
                // （sampleOriginal / applyHydrologyChunk / fillFromNoise 均如此）。
                // 此前误写成 lz * 16 + lx，导致每列取到对角线转置位置的高度作基线，
                // 整片地形被镜像 → chunk 边界错位断裂（网格状）。
                int index = lx * 16 + lz;
                int blockX = chunkX * 16 + lx;
                int blockZ = chunkZ * 16 + lz;
                List<HydrologyBlockSample> samples =
                        engine.sampleBlockAll(blockX, blockZ, horizontalScale);
                if (samples.isEmpty()) continue;
                result.add(carveColumn(engine.terrain(), samples,
                        originalGround[index], blockX, blockZ));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 单属主单列雕刻（2026-08-28）：每块只归最近一条河线（Voronoi），
     * carved/surface/bed/anyFill 全部同源 —— 根除"多线 MAX carve 跨线劫持"。
     */
    private static HydrologyBlockCarvedColumn carveColumn(CellGenerator terrain,
                                                          List<HydrologyBlockSample> samples,
                                                          double original,
                                                          int blockX, int blockZ) {
        // 不再按高度淡出：河流由汇流场决定，山地也有溪（现实物理范式）。
        // 仅海床保护：真实地表已低于海平面 → 海洋区域，河流不雕（海洋本身就是水）。
        double fadeE = 1.0;
        double seaLevel = terrain.heightCurve().seaLevelY();
        if (original < seaLevel) fadeE = 0.0;

        // ★ 单属主：sampleBlockAll 已按 distToCenter 升序，get(0) 即最近河线。
        //   水面/河床/灌水全部只看属主 —— 远处河线再也无法影响本列。
        HydrologyBlockSample owner = samples.get(0);
        double width = Math.max(owner.width(), 1.0);
        double valley = Math.max(owner.valleyWidth(), width);
        double dist = owner.distToCenter();

        // 距离场横断面：t=0 中心 → t=1 河缘（Streams 式 V 形：线性凹断面）
        double t = NoiseUtil.saturate(dist / width);
        double profile = 1.0 - t;                 // 中心 1.0 → 缘 0.0（V 形河床）
        // 河谷壁：从河缘(valleyT=0)到谷外(valleyT=1)按 valleyExp 幂次渐变归零（V 形谷壁）
        double valleyT = NoiseUtil.saturate((dist - width) / Math.max(1.0, valley - width));
        double outer = 1.0 - Math.pow(valleyT, RiverLineParams.defaults().valleyExp());

        // 水面 = min(单调水面, 当地真实地形)（Streams maxSurfaceAt 范式）。
        //   owner.surfaceY() = 网络逐段插值的单调水面（applyRiverHeightSlopeDrop 产物）→ 保证不爬坡；
        //   original = 当地真实地形 → 地形低于水面时水面=地形，填满谷、不悬空；
        //   取 min 后河嵌进真实谷地且水面严格单调（根除"河爬坡"）。
        double waterSurface = Math.min(owner.surfaceY(), original);
        // 目标河床：水面 − depth × 断面形状
        double bedTarget = waterSurface - owner.depth() * profile;
        // 雕刻量 = (original − bedTarget) × 外缘衰减 × 高度淡出；只下挖
        double cut = Math.max(0.0, original - bedTarget) * outer * fadeE;
        double carved = original - cut;

        // ★ 灌水门控：
        //   ① dist ≤ width（河道半宽内，valley 谷壁区只塑形不灌水）；
        //   ② carved < waterSurface − 0.5（真雕出河床）；
        //   ③ 水深上界 waterSurface − carved ≤ depth + 1（防异常深水）。
        boolean anyFill = dist <= width
                && carved < waterSurface - 0.5
                && (waterSurface - carved) <= owner.depth() + 1.0;
        return new HydrologyBlockCarvedColumn(blockX, blockZ, original, carved,
                waterSurface, cut, anyFill);
    }
}
