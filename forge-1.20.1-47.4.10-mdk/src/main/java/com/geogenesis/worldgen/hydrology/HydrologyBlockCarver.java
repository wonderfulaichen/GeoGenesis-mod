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
 *   <li><b>距离场平滑（C1，根治属主切换放射折痕）</b>：几何用 smooth-min(k) 合并各段
 *       距离（≠ 对雕刻高度做 smooth-min——后者会把相邻段河谷壁叠成包络脊）；
 *       弯角平分线 / region 边界处由硬切变为 k 宽 C1 过渡 → 放射折痕消失；
 *       属性仍按 IDW 混合（PL-RGA），河线交越接缝平滑，不复发旧"多线 MAX carve"跨线劫持；</li>
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

        // ★ 折痕根因：雕刻几何只用"最近段距离"dist，而折线距离场在弯角平分线 /
        //   region 边界处硬切（梯度方向跳变）→ 经 valleyT/outer 非线性放大成放射折痕。
        //   正确修法 = 平滑"距离场本身"：对每段距离做 smooth-min（smin ≤ min → 仍只下挖），
        //   弯角/边界处由硬切变为 k 宽 C1 过渡；属性仍按 IDW 混合（PL-RGA，河线交越接缝平滑）。
        //   注意：绝不能对"雕刻高度"做 smooth-min——那会把相邻段河谷壁叠加成新的包络脊。
        RiverLineParams P = RiverLineParams.defaults();
        double k = P.smoothMinK();

        double nearestDist = samples.get(0).distToCenter();   // 灌水门控用（保持原语义）
        double nearestWidth = Math.max(samples.get(0).width(), 1.0);  // 门控用最近段自身半宽（与 sampleBlock 复查一致）
        double dist = nearestDist;
        for (HydrologyBlockSample s : samples) {
            dist = smin(dist, s.distToCenter(), k);           // C1 距离场，根治几何折痕
        }

        // ★ IDW 多段混合（PL-RGA riverHeightField: (1−fade)²/dist² 加权）
        //   取 dist ≤ heightBlendDist 的全部命中按反距离平方混合 surfaceY/width/depth，
        //   河线交越处平滑过渡，根除"单属主硬切"接缝；blendDist 仅 ~valley 量级，远处河线不入场。
        double blendDist = P.heightBlendDist();
        double wSum = 0.0, sSurf = 0.0, sWid = 0.0, sDep = 0.0;
        for (HydrologyBlockSample s : samples) {
            double d = s.distToCenter();
            if (d > blendDist) break;                   // sampleBlockAll 已按距离升序
            double fade = NoiseUtil.saturate(d / blendDist);
            double w = (1.0 - fade) * (1.0 - fade) / Math.max(d * d, 1.0);
            wSum += w;
            sSurf += w * s.surfaceY();
            sWid  += w * s.width();
            sDep  += w * s.depth();
        }
        double width, surfaceY, depth;
        if (wSum > 1e-9) {
            width = sWid / wSum;
            surfaceY = sSurf / wSum;
            depth = sDep / wSum;
        } else {                                         // 退化：仅最近一条（与旧单属主等价）
            HydrologyBlockSample o = samples.get(0);
            width = o.width(); surfaceY = o.surfaceY(); depth = o.depth();
        }
        width = Math.max(width, 1.0);
        double bankW = width * P.bankFactor();
        double valley = Math.max(width + bankW, width * 3.0);

        // 距离场横断面：t=0 中心 → t=1 河缘（Streams 式 V 形：线性凹断面）
        double t = NoiseUtil.saturate(dist / width);
        double profile = 1.0 - t;                 // 中心 1.0 → 缘 0.0（V 形河床）
        // 河谷壁：从河缘(valleyT=0)到谷外(valleyT=1)渐变归零；smoothstep 化保证
        // 谷外缘零导数 → 与原地形 C1 接回（根治谷壁轮廓缝）。
        double valleyT = NoiseUtil.saturate((dist - width) / Math.max(1.0, valley - width));
        double outer = valleyOuter(valleyT, P.valleyExp());

        // 水面 = min(单调水面, 当地真实地形)（Streams maxSurfaceAt 范式）。
        double waterSurface = Math.min(surfaceY, original);
        // 目标河床：水面 − depth × 断面形状
        double bedTarget = waterSurface - depth * profile;
        // 雕刻量 = (original − bedTarget) × 外缘衰减 × 高度淡出；只下挖
        double cut = Math.max(0.0, original - bedTarget) * outer * fadeE;
        double carved = original - cut;

        // ★ 灌水门控：
        //   ① dist ≤ width（河道半宽内，valley 谷壁区只塑形不灌水）；
        //   ② carved < waterSurface − 0.5（真雕出河床）；
        //   ③ 水深上界 waterSurface − carved ≤ depth + 1（防异常深水）。
        boolean anyFill = nearestDist <= nearestWidth
                && carved < waterSurface - 0.5
                && (waterSurface - carved) <= depth + 1.0;
        return new HydrologyBlockCarvedColumn(blockX, blockZ, original, carved,
                waterSurface, cut, anyFill);
    }

    /** 二次 smooth-min（IQ）：smin ≤ min(a,b)，C1，且 ≤ 每个输入 → 合并距离时仍只下挖。
     *  注意：mix 须为 a*h + b*(1-h)（即 mix(b,a,h)），结果才≈min；参数写反会退化为 max。 */
    private static double smin(double a, double b, double k) {
        double h = NoiseUtil.clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
        return (a * h + b * (1.0 - h)) - k * h * (1.0 - h);
    }

    /**
     * 河谷壁外缘衰减：valleyT=1（谷外缘）处零导数 → 与原地形 C1 接回（根治谷壁轮廓缝）。
     * 中段保留 1−valleyT^exp 的 V 形谷壁，仅在外缘渐变到零导数 smoothstep。
     */
    private static double valleyOuter(double vt, double exp) {
        double inner = 1.0 - Math.pow(vt, exp);          // 中段 V 形谷壁
        double tail = 1.0 - NoiseUtil.smooth(vt);         // 末端零导数
        double m = NoiseUtil.smooth(NoiseUtil.saturate((vt - 0.5) / 0.5));
        return inner * (1.0 - m) + tail * m;
    }
}
