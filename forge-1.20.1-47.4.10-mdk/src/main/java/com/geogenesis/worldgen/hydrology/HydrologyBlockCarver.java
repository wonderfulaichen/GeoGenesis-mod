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

        // ★ 瀑布（跌水）豁免（2026-08-30）：跌水潭侧列的水面与雕刻高程必须是阶跃，
        //   不得参与竞争带 IDW 混合——k=4 格的混合会把垂直落差抹成缓坡，
        //   瀑布随即退化为普通急流（这正是"地形高于水面就下挖穿过"之外的第二个削平源）。
        //   ★ 唇口侧（fallDrop=0 但 frozen=true）同样冻结：它是水幕墙顶（上级阶梯水位），
        //   若走 IDW 混合会被上级/下级 tread 混出中间值，把崖顶边缘挖出垂直凹坑
        //   （悬空沙块平台、水幕与潭面脱节的根因）。唇口侧 fallDrop=0 → 只有冻结、无水幕。
        boolean atFall = nearest.fallDrop() > 0.0 || nearest.frozen();
        // ★ 冻结仅作用于河道内（dist ≤ width）：潭面/水幕的阶跃形态只在河道内需要
        //   保持——弯角属主切换线两侧的 tread 差在河道内被潭水/水幕覆盖（瀑布横向断面）。
        //   valley 谷壁区（dist > width）必须恢复 k=4 IDW 竞争带混合：冻结会让属主切换线
        //   两侧 tread 差硬切（谷壁区 profile=0 → bedTarget=carveSurfaceY），经 outer 衰减区
        //   放大成弯角放射折痕（老折痕问题回归），并使水幕侧壁悬空暴露。
        // ★ 与雕刻几何同源（2026-08-31）：冻结范围必须用雕刻几何自己的 dist（smin 合并后）
        //   与 width（IDW 混合宽），而不是灌水门控的 nearestDist/nearestWidth。
        //   两者不等价：smin 让 dist 比真实最近距离小最多 k/4，IDW 混合宽也可能大于最近
        //   段半宽 → 紧贴水道外缘出现一圈"几何上算河道内(按河床深挖)、门控上算河道外
        //   (不给水)"的错位带；该带又不冻结，瀑布处 carveSurfaceY 被 IDW 跨阶拖到远低于
        //   邻接水面（实测 124.7 vs 水面 135.5，亏缺 10.7 格）→ 河道两侧深干沟平台。
        //   改与几何同源后该带同样冻结（取最近段真值），不再被跨阶拖动。
        boolean frozenChannel = atFall && dist <= width;
        if (frozenChannel) {
            carveSurfaceY = nearest.surfaceY();
            waterSurfaceY = nearest.surfaceY();
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

        // ★ 谷壁雕刻面平滑（消弯角放射折痕回归）：瀑布冻结让雕刻面按阶硬切，
        //   弯角处各列最近段在上下阶间 Voronoi 跳变 → 谷壁折痕。谷壁（dist>width）
        //   只塑形不灌水，平滑雕刻面只消折痕、不影响水幕（水面已冻结为阶值）。
        //   只对"真水幕列"（fallDrop>0，阶跌处）平滑——唇口列（平坦潭面）保持
        //   阶值，避免被向下拉跨阶挖深。仅与"同为冻结段"样本平滑，限幅 ±maxDrop。
        if (atFall && nearest.fallDrop() > 0.0 && dist > width) {
            double fSum = nearest.surfaceY(), fWeight = 1.0;
            for (int i = 1; i < samples.size(); i++) {
                HydrologyBlockSample s = samples.get(i);
                if (!s.frozen()) continue;
                double delta = s.distToCenter() - nearestDist;
                if (delta >= k) continue;
                double weight = NoiseUtil.smooth(1.0 - NoiseUtil.saturate(delta / k));
                fSum += weight * s.surfaceY();
                fWeight += weight;
            }
            double blendSurf = fSum / fWeight;
            double maxDrop = P.waterfallMaxDrop();
            if (Math.abs(blendSurf - nearest.surfaceY()) > maxDrop) {
                blendSurf = nearest.surfaceY()
                        + Math.signum(blendSurf - nearest.surfaceY()) * maxDrop;
            }
            carveSurfaceY = blendSurf;
        }

        // 水面直接采用河线的有向单调纵剖面。不能再逐块 min(surfaceY, original)：
        // 局部凹坑会先把水面压低，离开凹坑后又恢复到河线水面，从而在下游制造反向抬升。
        // 地形高于水面时由后续 cut 下挖穿过；地形低于水面时保持原地形并按门控决定灌水。
        double waterSurface = waterSurfaceY;
        // ★ 斜切崖面连降（2026-08-31）：唇口侧冻结列若地形已从唇口水位降下
        //   （original < 唇口水位），说明崖面是"斜切角"而非直角——水应贴崖面
        //   逐列下行，而不是按唇口水位悬空、再被门控④（水面高于地形不灌）
        //   判成干崖面（实测：斜切瀑布唇口→水幕之间坡面裸露、水幕上下脱节）。
        //   处理：雕刻面与水面都钳到当地地形 → 切穿/灌水机制自动灌出贴面
        //   薄水级联（每列水深 ≤0.75），沿斜面逐级衔接到水幕与潭面。
        //   真直角崖地形从唇口直接跳到潭面、无中间列，水幕不受影响。
        double lipLevel = Math.max(nearest.surfaceY(), nearest.lipSurfaceY());
        boolean faceCascade = atFall && nearest.fallDrop() <= 0.0
                && dist <= width && original < lipLevel - 0.5;
        if (faceCascade) {
            carveSurfaceY = Math.min(carveSurfaceY, original);
            waterSurface = Math.min(waterSurface, original);
        }
        // 目标河床使用局部连续的雕刻高程；真实水面仍保持最近有向段的 PAVA 纵剖面。
        double bedTarget = carveSurfaceY - depth * profile;
        // ★ 瀑布直角岸台修复（2026-08-31）：谷壁列（dist>width）只塑形不灌水，其雕刻
        //   目标若低于附近最高水位，弯角处会沿河岸挖出一条低于河面的干平台（实测
        //   截图：平台只在瀑布直角附近出现）。根因是谷壁的雕刻面在直角附近被潭侧
        //   低位水面拖下去（通用 IDW 混入跌水节点潭面、冻结平滑跨阶混合唇口/潭面）。
        //   修复：谷壁雕刻目标钳到 k 邻域内最高水位（水面/唇口取大）——普通河段各
        //   样本水面≈自身 → 钳制≈无操作；瀑布处钳到上级 tread 水位 → 直角两侧岸坡
        //   不再被潭侧低面拖下去，恰好实现"直角两侧被地形包住"。只影响下挖量：
        //   当地地形本就低于该水位的下游侧（original < highWater）cut 仍为 0，形态不变。
        if (dist > width) {
            double highWater = Math.max(nearest.surfaceY(), nearest.lipSurfaceY());
            for (int i = 1; i < samples.size(); i++) {
                HydrologyBlockSample s = samples.get(i);
                if (s.distToCenter() - nearestDist >= k) continue;
                highWater = Math.max(highWater, Math.max(s.surfaceY(), s.lipSurfaceY()));
            }
            if (bedTarget < highWater) bedTarget = highWater;
        }
        // ★ 干地不得低于邻接水面（2026-08-31）：灌水门控①按 nearestDist/nearestWidth 判定，
        //   而雕刻几何按 smin 后的 dist 与 IDW 混合 width 判定——两者不等价，紧贴水道外缘
        //   存在一圈"几何上算河道内(按河床深挖)、门控上算河道外(不给水)"的列。该环带被
        //   挖到 carveSurfaceY − depth·profile（约低于水面一个河深）却是干的，视觉上就是
        //   紧贴水面、低于河道的干平台（展开图实测标记为 a 的环带）。
        //   物理常识：不会被灌水 = 那里没有水，干地就不允许被挖到邻接水面以下。
        //   本列是否灌水完全由门控①预判定（先于 carved），与下面的 anyFill 同源。
        boolean outsideWaterGate = nearestDist > nearestWidth;
        if (outsideWaterGate && bedTarget < waterSurface) {
            bedTarget = waterSurface;
        }
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
        // ★ 跌水列豁免"水面不得高于原始地形"（④防漫岸）：瀑布的水是坠落中的水，
        //   不是积在地面上的水。该门控本意是防水从河缘漫到地面，套到瀑布上会把
        //   过半水幕列误杀成干列（实测 142/275 干）——崖面处的 original 天然高于潭面。
        //   ★ 豁免只给真水幕列（fallDrop>0，坠落水）：唇口侧冻结列（fallDrop=0）是
        //   崖顶静水潭，必须回归严格门控——否则河缘带当地地形略低于 lip 时水会
        //   灌在草地之上（水幕顶"直角"超出原地形的漫水根因）。冻结只管雕刻（禁
        //   IDW 混合），不管灌水门控，两者语义分离。
        boolean terrainOk = nearest.fallDrop() > 0.0 || wetCore || waterSurface <= original + 1e-9;
        // ★ 门控①不放宽（2026-08-30 回退 inCurtain）：曾让真水幕列不受 d≤width
        //   约束以"水幕横跨崖面"，但陡坡横向地形常低于潭面（tread），远离河道的
        //   水幕列被灌出**孤立水柱**（斜坡上悬空水柱+底部沙块，实测截图）——
        //   水必须只在雕刻出的河道内（Streams fillRiver/DW 均如此）。水幕宽度
        //   = 河道横断面宽；需要更宽水幕应调 width 而非放宽门控。
        boolean anyFill = nearestDist <= nearestWidth
                && carved < waterSurface - 0.5
                && terrainOk
                && (punchedThrough
                    || (carveSurfaceY - carved) <= depth + 1.0
                    || original <= waterSurface - 1.0);
        // 水幕顶：跌水列取唇口水位（= 潭面 + 落差），普通列与水面同值（fallDrop()=0）。
        double lipSurfaceY = atFall ? nearest.lipSurfaceY() : waterSurface;
        // ★ 水幕顶钳到当地崖面地形（2026-08-30）：河流有宽度——水幕列的横向岸是
        //   本级位置的地形（陡坡上低于上级 tread），水幕顶若取上级 tread 会高出
        //   两侧地形、悬在坡面上（直角两侧不被地形包住）。钳到 original 后水幕
        //   完全嵌在沟里：潭缘列全高、横向边缘列贴坡变矮，水幕横向呈弧形
        //   （自然瀑布贴弧形崖面形态）。唇口列 lipY=surfaceY≤original 不受影响。
        if (lipSurfaceY > original) lipSurfaceY = original;
        return new HydrologyBlockCarvedColumn(blockX, blockZ, original, carved,
                waterSurface, lipSurfaceY, cut, anyFill);
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
