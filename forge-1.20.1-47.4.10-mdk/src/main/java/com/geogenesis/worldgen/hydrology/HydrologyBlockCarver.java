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
        RiverLineParams P = RiverLineParams.defaults();
        // 不再按高度淡出：河流由汇流场决定，山地也有溪（现实物理范式）。
        // 入海段：不能因"地形低于海平面"就完全停雕——那会让河道在海岸线处直接截断。
        // 参考 Farseek Mouth：河床继续向海延伸，按"地形处于海面下的深度"平滑淡出，
        // 形成河口湾/淹没河谷后自然消失，而不是一刀切断。
        double fadeE = 1.0;
        double seaLevel = terrain.heightCurve().seaLevelY();
        if (original < seaLevel) {
            double submerge = seaLevel - original;
            fadeE = 1.0 - NoiseUtil.saturate(submerge / P.mouthFadeDepth());
        }

        // ★ 折痕根因：雕刻几何只用"最近段距离"dist，而折线距离场在弯角平分线 /
        //   region 边界处硬切（梯度方向跳变）→ 经 valleyT/outer 非线性放大成放射折痕。
        //   正确修法 = 平滑"距离场本身"：对每段距离做 smooth-min（smin ≤ min → 仍只下挖），
        //   弯角/边界处由硬切变为 k 宽 C1 过渡；属性仍按 IDW 混合（PL-RGA，河线交越接缝平滑）。
        //   注意：绝不能对"雕刻高度"做 smooth-min——那会把相邻段河谷壁叠加成新的包络脊。
        double k = P.smoothMinK();

        HydrologyBlockSample nearest = samples.get(0);
        double nearestDist = nearest.distToCenter();   // 灌水门控用（保持原语义）
        double nearestWidth = Math.max(nearest.width(), 1.0);  // 门控用最近段自身半宽
        double dist = nearestDist;
        for (HydrologyBlockSample s : samples) {
            // 保留 20ddda4 的完整平滑强度：首样本的 k/4 圆角偏移是既有河槽形态的一部分。
            dist = smin(dist, s.distToCenter(), k);
        }

        // ★ 宽深按 IDW 多段混合；真实水面必须保持最近有向河段的单调纵剖面。
        double blendDist = P.heightBlendDist();
        double wSum = 0.0, sWid = 0.0, sDep = 0.0;
        for (HydrologyBlockSample s : samples) {
            double d = s.distToCenter();
            if (d > blendDist) break;                   // sampleBlockAll 已按距离升序
            double fade = NoiseUtil.saturate(d / blendDist);
            double w = (1.0 - fade) * (1.0 - fade) / Math.max(d * d, 1.0);
            wSum += w;
            sWid += w * s.width();
            sDep += w * s.depth();
        }
        double width, depth;
        if (wSum > 1e-9) {
            width = sWid / wSum;
            depth = sDep / wSum;
        } else {
            width = nearest.width();
            depth = nearest.depth();
        }

        // PL-RGA 河高场的局部化版本：只在 smooth-min 的 k 宽属主竞争带内混合雕刻高程。
        // 这同时覆盖同折线弯角、汇流点和跨 region 重叠段；范围仅 k（默认 4 格），
        // 不会像 100 格水位 IDW 那样混入远处上游高水位，且该值绝不用于最终灌水。
        double carveSurfaceSum = nearest.surfaceY();
        double carveSurfaceWeight = 1.0;
        for (int i = 1; i < samples.size(); i++) {
            HydrologyBlockSample s = samples.get(i);
            double delta = s.distToCenter() - nearestDist;
            if (delta >= k) continue;
            double weight = NoiseUtil.smooth(1.0 - NoiseUtil.saturate(delta / k));
            carveSurfaceSum += weight * s.surfaceY();
            carveSurfaceWeight += weight;
        }
        double carveSurfaceY = carveSurfaceSum / carveSurfaceWeight;
        // 普通河段继续使用最近有向河段水面；仅在多个河线命中同一局部竞争带时，
        // 将交汇点的水面统一到局部连续值，避免支流与主流各保留一层水平面。
        double waterSurfaceY = junctionWaterSurface(samples, nearest, k);
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

        // 水面直接采用河线的有向单调纵剖面。不能再逐块 min(surfaceY, original)：
        // 局部凹坑会先把水面压低，离开凹坑后又恢复到河线水面，从而在下游制造反向抬升。
        // 地形高于水面时由后续 cut 下挖穿过；地形低于水面时保持原地形并按门控决定灌水。
        double waterSurface = waterSurfaceY;
        // 目标河床使用局部连续的雕刻高程；真实水面仍保持最近有向段的 PAVA 纵剖面。
        double bedTarget = carveSurfaceY - depth * profile;
        // 雕刻量 = (original − bedTarget) × 外缘衰减 × 高度淡出；只下挖
        double cut = Math.max(0.0, original - bedTarget) * outer * fadeE;
        double carved = original - cut;

        // ★ 河道内切穿（Streams 语义）：地形高于水面时挖出低于水面的河槽，而不是放弃灌水。
        //   河道内保证 carved < waterSurface → 恒有水（根治干河），且水面高于地形的列
        //   被切穿后不再悬浮。下挖量有界（≤ minWaterDepth），床面连续。
        boolean punchedThrough = false;
        if (nearestDist <= nearestWidth && carved > waterSurface - 0.75) {
            double minBed = waterSurface - 0.75;
            if (minBed < carved) {
                cut += carved - minBed;
                carved = minBed;
                punchedThrough = true;
            }
        }

        // ★ 灌水门控：
        //   ① dist ≤ width（河道半宽内，valley 谷壁区只塑形不灌水）；
        //   ② carved < waterSurface − 0.5（真雕出河床）；
        //   ③ 水深上界（满足其一即可）：
        //      a) 相对 IDW 混合雕刻面 carveSurfaceY − carved ≤ depth + 1（段间差免疫）；
        //      b) 切穿列水深恒 0.75，直接豁免；
        //      c) 原始地面本就低于水面的天然洼地直接灌（水填洼地）。
        //     三者缺一都会把对应场景误杀成干列（河中断流）。
        // ④ 河缘带（0.7w~1.0w）水面若高于当地原始地形，则不灌水：
        //    否则水会从河缘漫到地面上，表现为"一侧河岸被水盖过"。
        //    湿核心带（≤0.7w）与切穿列是真正的水槽，不受此限。
        boolean wetCore = nearestDist <= nearestWidth * 0.7;
        boolean terrainOk = wetCore || waterSurface <= original + 1e-9;
        boolean anyFill = nearestDist <= nearestWidth
                && carved < waterSurface - 0.5
                && terrainOk
                && (punchedThrough
                    || (carveSurfaceY - carved) <= depth + 1.0
                    || original <= waterSurface - 1.0);
        return new HydrologyBlockCarvedColumn(blockX, blockZ, original, carved,
                waterSurface, cut, anyFill);
    }

    private static double junctionWaterSurface(List<HydrologyBlockSample> samples,
                                                HydrologyBlockSample nearest, double k) {
        double sum = nearest.surfaceY();
        double weightSum = 1.0;
        int localHits = 1;
        for (int i = 1; i < samples.size(); i++) {
            HydrologyBlockSample s = samples.get(i);
            if (s.distToCenter() - nearest.distToCenter() >= k) continue;
            double w = NoiseUtil.smooth(1.0 - NoiseUtil.saturate(
                    (s.distToCenter() - nearest.distToCenter()) / k));
            sum += w * s.surfaceY();
            weightSum += w;
            localHits++;
        }
        // 单线/远距重叠保持原有最近有向水面；只在真正局部多线竞争带统一交汇水面。
        return localHits > 1 ? sum / weightSum : nearest.surfaceY();
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
