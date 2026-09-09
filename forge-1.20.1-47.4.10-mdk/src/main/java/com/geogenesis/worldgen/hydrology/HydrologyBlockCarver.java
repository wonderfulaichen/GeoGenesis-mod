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

    /**
     * 湖水位按侵蚀后短板重算的开关（2026-09-09 终版【默认开】）。
     *
     * <p>用户两次实测定案：水位必须按短板效应取【侵蚀后】地形的最低溢出坎 ——
     * 否则（false 时用无侵蚀 spill）侵蚀削低岸坎后水位高出实际地形：水悬空、
     * 且山坡上大片低于旧水位的区域被误灌（"填到洼地山外围"，截图红圈）。
     * 短板把水位压到真实缺口 → 只有真正低于水位的盆底才淹，三个问题同解。</p>
     *
     * <p>性能：rim 格紧邻湖盆，玩家加载湖边 chunk 时这些侵蚀 tile 本就要生成，
     * rim 采样只是提前访问（早前"+32 tile/2.7×"为探针窗口不覆盖湖区的测量假象，
     * 基线 0 tile 实为磁盘缓存命中，不可比）。LakeNode.erodedWaterLevel 每湖只算一次。</p>
     */
    public static final boolean LAKE_ERODED_SPILL = true;

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
                        originalGround[index], blockX, blockZ, horizontalScale));
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
                                                          int blockX, int blockZ,
                                                          double horizontalScale) {
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

        // ★ 湖分支（2026-09-09 B1，用户实测"湖泊完全就是一个圆盘" + "水面边缘没贴到
        //   地形、边缘一堆空气位"）：湖命中列【不雕刻】。carveColumn 的 original 是
        //   【无侵蚀】基线（HydrologyChunkSampling 用 sample()），拿它判"是否淹水"
        //   是错的 —— 湖盆在落块前已被 extractFromTile 侵蚀改写（GeoGenesisTerrain
        //   .generateChunk：侵蚀先于雕刻回写），必须由合成层（applyHydrologyValley，
        //   那里 cell.height 已是侵蚀后地面）用【侵蚀后 height < spill】判出水，湖岸
        //   = 侵蚀后地形与 spill 的等高线。carver 只负责：给湖域列打 lakePlan 标、
        //   不雕刻（carved=original）、水面=spill。这样湖自然吃侵蚀后地形、湖岸贴地。
        if (!samples.isEmpty() && samples.get(0).isLake()) {
            HydrologyBlockSample lakeSample = samples.get(0);
            // ★ 侵蚀短板水位（2026-09-09，用户实测"水面边缘没到地形/水面包不住"）：
            //   surfaceY(spill) 是【无侵蚀】地形的溢出坎高；侵蚀把溢出口坎（rim）削低后，
            //   旧 spill 会高出真实缺口 → 水从低坎漏走、包不住。真水位 = min(原 spill,
            //   rim 各坎的侵蚀后高度)（只降不升）。
            double spill = lakeSample.surfaceY();
            com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion.LakeNode ln =
                    lakeSample.lake();
            if (LAKE_ERODED_SPILL && ln != null && ln.hasRim()) {
                // 侵蚀短板水位（rim 格紧邻湖盆，数量少，落块时 tile 多半已缓存，每湖一次）。
                java.util.function.ToDoubleBiFunction<Double, Double> erodedY =
                        (wx, wz) -> terrain.sampleWu(wx, wz).height;
                spill = ln.erodedWaterLevel(erodedY);
                // ★ 湖形 = 侵蚀后连通淹水区（2026-09-10 终版，用户"水没铺满整个洼地"）：
                //   computeFlood 在侵蚀后地形上 BFS 出"低于水位且与盆底连通"的区域。
                //   整湖放弃条件（computeFlood=true）：① 淹水区越出认领域（湖比认领
                //   域大 → 会在认领边界被截断）；② 淹没覆盖无侵蚀洼地不足一半（侵蚀
                //   把一侧盆底垫高 → 水铺不满 → 残缺湖）。两者都是"硬生成必残缺"，
                //   按用户要求"超出填充就不生成湖"。
                //   湖域列是否出水：不在 flood 连通区内的列【不标 lakePlan】→ 该列出水
                //   自然在连通区边界结束（不会"停在半途"：水位等高线闭合在连通区内部），
                //   也不会漫出洼地（连通性约束：坡面不连通不淹）。computeFlood 每湖缓存。
                if (ln.computeFlood(erodedY, spill,
                        com.geogenesis.worldgen.hydrology.riverline.RiverLineParams
                                .defaults().gridCell())) {
                    return new HydrologyBlockCarvedColumn(blockX, blockZ,
                            original, original, original, original,
                            0.0, 1.0, false, false);
                }
                double wuX = blockX / (horizontalScale > 0.01 ? horizontalScale : 1.0);
                double wuZ = blockZ / (horizontalScale > 0.01 ? horizontalScale : 1.0);
                if (!ln.inFlood(wuX, wuZ)) {
                    // 本列在湖认领域内但不在侵蚀后连通淹水区 → 非湖列（湖形自然闭合）。
                    return new HydrologyBlockCarvedColumn(blockX, blockZ,
                            original, original, original, original,
                            0.0, 1.0, false, false);
                }
            }
            // 湖不挖地：carved = original（合成层 waterSurface vs 侵蚀后 height 判水）。
            // lakePlan=true 通知合成层走"湖出水判定"（用侵蚀后地面，而非通用河床减法）。
            return new HydrologyBlockCarvedColumn(blockX, blockZ,
                    original, original,          // carved = original（湖不雕刻）
                    spill, spill,                // waterSurface = lip = 侵蚀短板水位
                    0.0,                         // erosion = cut = 0
                    1.0,                         // 湖盆吃全量侵蚀（盆底 = 侵蚀后真实地形）
                    false,                       // fillWater 由合成层判，这里不预判
                    true);                       // lakePlan：湖域列标记
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
        // ★ 谷壁多河平滑（2026-08-31）：carveSurfaceY / junctionWaterSurface 都用 delta<k(4)
        //   窄混合，两河谷壁叠加时在属主切换线上目标水面跳变 → 雕刻诱发谷壁断层
        //   （正确口径实测 1127 处、86% 在多河列、最大 20.4 格）。只对"不会被灌水"的
        //   普通谷壁列（nearestDist>nearestWidth 且 !atFall，瀑布壁另有冻结/平滑处理）
        //   生效，不碰河道 bed/灌水：目标水面与干地钳制统一换成与宽深混合同形式的
        //   【距离平方反比 IDW】混合 —— 近河权重主导（不会被 40~60 格外宽河的低水面
        //   拖低，那是 outer 加权方案的失败根因），同缝两河权重相当（缝被平滑成鞍部）。
        if (nearestDist > nearestWidth && !atFall) {
            double gwSum = 0.0, gsSum = 0.0;
            for (HydrologyBlockSample s : samples) {
                double d = s.distToCenter();
                if (d > P.heightBlendDist()) break;   // sampleBlockAll 已按距离升序
                double fade = NoiseUtil.saturate(d / P.heightBlendDist());
                double w = (1.0 - fade) * (1.0 - fade) / Math.max(d * d, 1.0);
                gwSum += w;
                gsSum += w * s.surfaceY();
            }
            if (gwSum > 1e-9) {
                double wallSurf = gsSum / gwSum;
                carveSurfaceY = wallSurf;
                waterSurfaceY = wallSurf;
            }
        }
        width = Math.max(width, 1.0);
        double bankW = width * P.bankFactor();
        // ★ 湖命中谷壁带收窄（2026-09-07，用户截图："河岸过渡带到边缘没平滑跟随
        //   实际地形"）：谷壁带 [width, valley] 会把地形刨到水面（profile=0 →
        //   bedTarget=carveSurfaceY）。普通河带宽 ~2×width 是想要的河谷壁；但湖命中
        //   宽度已 ×3，谷壁带跟着放大 3 倍 = 整片阶梯地貌被刨平成光滑坡。湖岸应该
        //   跟随自然地形（水只灌到自然洼地处），只留 1.15×width 的过渡羽化防硬切。
        boolean lakeHit = nearest.isLake();
        // ★ 跌水段同治（2026-09-07，用户："瀑布的岸坡也会这样"）：跌水两侧地形
        //   高差最大，谷壁带往水面刨的痕迹在瀑布处最刺眼。潭体在河道内（dist≤width）
        //   不受影响；弯折外侧楔形区本就不认领（2026-08-31）。
        // ★ 2026-09-09 v2 终修（用户实测"瀑布河道两侧垂直悬崖"）：此前两版都没真正解决——
        //   v1 把岸坡雕刻面抬到崖顶 lip（bankSurfaceY），v0/中间版用 narrowWall 把瀑布谷壁
        //   压到 1.15×width（0.4 格过渡带）——落差 9~15 格于是在河道边缘 1~2 列内全部跳变
        //   = 矩形垂直槽（横断面实测 h: ...132 132 132 | 141 141...，池底 132 直跳岸 141）。
        //   正解 = 瀑布坐进【碗状谷地】：谷壁带用自适应展宽（bankRun = H×bankSlopeRun，岸越
        //   高越宽），雕刻目标逐列保持"最近河段水面/池面"，9~15 格落差经 valleyOuter 在
        //   ~22 block 内摊成 ~34° 缓坡（不是抬 lip 制造断崖，也不是窄谷硬切）。
        //   ★ 湖保持窄谷壁 1.15×width：湖不挖地、只铺水面，岸坡不主动雕刻、跟随自然地形
        //     （2026-09-07 注释原义）。
        // ★ 守卫用【真实最近距离】nearestDist（与灌水门控①同源）：smin 会被落差处多个
        //   重叠冻结样本压低（实测 nearestDist=3.84 → smin=2.28 < width），使真正岸坡列被
        //   误判"河道内"→ frozenChannel 刻到潭面床且不灌水 = 落差线旁垂直切面。
        // ★ 岸坡雕刻目标（2026-09-09 v2，用户实测"瀑布河道两侧垂直悬崖"）：
        //   v1 把所有瀑布岸坡目标抬到崖顶 lip(bankSurfaceY)——上游唇口侧确实需要（防刻穿
        //   地面成薄墙），但【潭侧池岸也被抬到 lip】→ 池面 132 vs 岸 141 无过渡列 = 垂直墙。
        //   正解：atFall 岸坡列雕刻目标 = 最近河段【自身水面】nearest.surfaceY()（跳过
        //   k=4 竞争带混合——混合会把 lip/池面抹成 136 中间值，正是 v1 上游薄墙的根因）。
        //   结果：唇口侧列 → 141（不刻穿高地）；潭侧列 → 132（宽谷壁削出缓坡入潭，碗状
        //   池岸）。落差 9~15 格由谷壁带（按岸高自适应展宽，见下）摊成 ~1:1.5 缓坡，
        //   不再是 1~2 列内的垂直跳变。
        if (nearestDist > nearestWidth && (atFall)) {
            carveSurfaceY = nearest.surfaceY();
        }
        // 湖命中：谷壁带收窄（1.15×width 羽化防硬切）。瀑布段不再收窄，走自适应宽谷壁。
        boolean narrowWall = lakeHit;
        // ★ 自适应谷宽（2026-09-09）：岸越高 → 谷壁跨度越大，保证岸坡不超过坡度上限。
        //   旧版跨度恒为 bankFactor×半宽（与岸高无关）→ 深切入地形的河（岸高 15 格、
        //   跨度仅 7.5 格）坡度达 2.0≈63°，视觉上就是"垂直面"（用户实测截图）。
        //   岸高 H = 原地形 − 河缘雕刻面（dist=width 处 profile=0 → bedTarget=carveSurfaceY）。
        double baseRun = Math.max(bankW, width * 2.0);   // 旧语义：valley − width
        double bankRun = adaptiveBankRun(carveSurfaceY, original, baseRun, P);
        double valley = narrowWall
                ? width * 1.15
                : width + bankRun;

        // ★ 侵蚀让步 mask（方案 A，RTF 式 erosionMask，2026-09-09）：河床吃多少侵蚀 delta。
        //   河心（dist≤width）→0：河床严格 = 计划 carved，与计划水面同源 → 根治图1阶梯
        //   （床不再跟侵蚀沟跳变）与图2干滩（床不再被侵蚀沉积顶穿水面）。谷外（dist≥valley）
        //   →1：全量侵蚀。中间 smoothstep 过渡，且与 cut 的 outer 淡出同区间 → 边界连续。
        double erosionMask = channelErosionMask(dist, width, valley);

        // 距离场横断面：t=0 中心 → t=1 河缘（Streams 式 V 形：线性凹断面）
        double t = NoiseUtil.saturate(dist / width);
        double profile = 1.0 - t;                 // 中心 1.0 → 缘 0.0（V 形河床）
        // 河谷壁：从河缘(valleyT=0)到谷外(valleyT=1)渐变归零；smoothstep 化保证
        // 谷外缘零导数 → 与原地形 C1 接回（根治谷壁轮廓缝）。
        double valleyT = NoiseUtil.saturate((dist - width) / Math.max(1.0, valley - width));
        // ★ 谷壁衰减改为【混合 outer 本身】而非"混合宽度再算 outer"（2026-08-31）：
        //   实测岸坡断层（种子 9139912035078620160 @ -977,-529 / -1072,-485 等 7 处、
        //   7~11 格）：窄跌水段（半宽 2.8，谷壁 9.8）的宽度被 17 格外、权重仅 12% 的
        //   宽段（6.8）混合撑到 3.28 → 谷壁 11.5；而该列正坐在谷壁边缘（9.5 格），
        //   outer 对谷壁半径极度敏感：0.007 → 0.547（78 倍）→ 这条窄跌水段用自己的
        //   唇口水位把岸坡挖深 12.9 格，与相邻归属普通段（124.3）的列形成岸坡断层。
        //   两种改法都不可取：混合宽度→谷壁被撑大；只用最近段宽度→属主切换处 outer
        //   硬跳（聚合实测 904→1107，3~6 格坎明显增多）。
        //   正解：每条河按【自己的宽度/谷壁范围】算 outerS，再按 IDW 距离权重混合。
        //   每条河的 outerS 对位置连续、IDW 权重也连续 → 既不被邻河撑大、属主切换处
        //   又无硬跳。手算该列 outer：0.547 → 0.28（接近"只用最近段"的 0.254）。
        double oSum = 0.0, oAcc = 0.0;
        // ★ 分段 carving 准备（2026-09-09）：每个样本的谷宽 vs 与其 IDW 权重 wS
        //   缓存下来，供【内层出口 outer】复用 —— 出口必须与主 outer 用同一 vs，
        //   否则出口值与主路径对不上（台阶源）。
        int nSamp = samples.size();
        double[] vsArr = new double[nSamp];
        double[] wSArr = new double[nSamp];
        for (int si = 0; si < nSamp; si++) {
            HydrologyBlockSample s = samples.get(si);
            double sd = s.distToCenter();
            if (sd > P.heightBlendDist()) { vsArr[si] = Double.NaN; wSArr[si] = 0.0; continue; }
            double ws = Math.max(s.width(), 1.0);
            // 湖/跌水样本谷壁带同样收窄（与上面 valley 同理，见湖命中注释）
            // 与主 valley 同式自适应（outer 混合必须与雕刻几何同参，否则属主切换处失配）
            double vs = (s.isLake() || s.fallDrop() > 0.0)
                    ? ws * 1.15
                    : ws + adaptiveBankRun(s.bankSurfaceY(), original,
                            Math.max(ws * P.bankFactor(), ws * 2.0), P);
            vsArr[si] = vs;
            double fadeS = NoiseUtil.saturate(sd / P.heightBlendDist());
            wSArr[si] = (1.0 - fadeS) * (1.0 - fadeS) / Math.max(sd * sd, 1.0);
        }
        for (int si = 0; si < nSamp; si++) {
            if (wSArr[si] <= 0.0) continue;
            HydrologyBlockSample s = samples.get(si);
            double ws = Math.max(s.width(), 1.0);
            double vtS = NoiseUtil.saturate((dist - ws) / Math.max(1.0, vsArr[si] - ws));
            oSum += wSArr[si];
            oAcc += wSArr[si] * valleyOuter(vtS, P.valleyExp());
        }
        double outer = oSum > 1e-9 ? oAcc / oSum : valleyOuter(valleyT, P.valleyExp());
        // ★ 内层出口 outer：与主 outer 同 vs、同权重，只把 dist 换成 formEdge → 出口连续。
        double formEdge = width + Math.max(1.0, width * P.formRunFactor());
        double edgeOuter = outer;
        if (P.formRunFactor() > 0.0) {
            double eSum = 0.0, eAcc = 0.0;
            for (int si = 0; si < nSamp; si++) {
                if (wSArr[si] <= 0.0) continue;
                HydrologyBlockSample s = samples.get(si);
                double ws = Math.max(s.width(), 1.0);
                double vtE = NoiseUtil.saturate((formEdge - ws) / Math.max(1.0, vsArr[si] - ws));
                eSum += wSArr[si];
                eAcc += wSArr[si] * valleyOuter(vtE, P.valleyExp());
            }
            if (eSum > 1e-9) {
                edgeOuter = eAcc / eSum;
            } else {
                edgeOuter = valleyOuter(NoiseUtil.saturate((formEdge - width)
                        / Math.max(1.0, valley - width)), P.valleyExp());
            }
        }

        // ★ 谷壁雕刻面平滑（消弯角放射折痕回归）：瀑布冻结让雕刻面按阶硬切，
        //   弯角处各列最近段在上下阶间 Voronoi 跳变 → 谷壁折痕。谷壁（dist>width）
        //   只塑形不灌水，平滑雕刻面只消折痕、不影响水幕（水面已冻结为阶值）。
        //   只对"真水幕列"（fallDrop>0，阶跌处）平滑——唇口列（平坦潭面）保持
        //   阶值，避免被向下拉跨阶挖深。仅与"同为冻结段"样本平滑，限幅 ±maxDrop。
        if (atFall && nearest.fallDrop() > 0.0 && dist > width) {
            // ★ 2026-09-09（用户实测"瀑布岸坡垂直切面"）：本块原先混合邻居 surfaceY（水面
            //   =潭面），会把上面刚提升的 bankSurfaceY（崖顶）又压回潭面 → 落差线岸坡列
            //   被刻穿 8~19 格（取证：(-166,-373) bank=141.25 但 carved=132.4，而下游
            //   (-165,-373) bank=140.2 → 140.2 正常）。岸坡列的雕刻面应混合邻居的
            //   【bankSurfaceY】（崖顶面）——消弯角折痕的初衷不变，只是混合的是岸坡面。
            double fSum = nearest.bankSurfaceY(), fWeight = 1.0;
            for (int i = 1; i < samples.size(); i++) {
                HydrologyBlockSample s = samples.get(i);
                if (!s.frozen()) continue;
                double delta = s.distToCenter() - nearestDist;
                if (delta >= k) continue;
                double weight = NoiseUtil.smooth(1.0 - NoiseUtil.saturate(delta / k));
                fSum += weight * s.bankSurfaceY();
                fWeight += weight;
            }
            double blendSurf = fSum / fWeight;
            double maxDrop = P.waterfallMaxDrop();
            if (Math.abs(blendSurf - nearest.bankSurfaceY()) > maxDrop) {
                blendSurf = nearest.bankSurfaceY()
                        + Math.signum(blendSurf - nearest.bankSurfaceY()) * maxDrop;
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
        if (dist > width && atFall) {   // 岸坡列（不灌水）紧邻瀑布：雕刻目标不得被潭面拖下去
            // ★ 2026-09-09（用户实测"瀑布落差处上游岸坡横墙"；WallColumnDumpProbe 实证）：
            //   落差岸坡列的最近样本常是潭面(132)、真落差样本(lip=141)次近。旧逻辑用
            //   【距离加权平均】抬 bedTarget → 只到 ~136，仍把 orig~145 的岸坡刻穿 → 墙。
            //   瀑布岸坡在落差附近应保持上游唇口高度（垂直落差本就该由水幕表现，不该由
            //   岸坡被刻出 8~19 格平墙）。改：邻域存在真落差样本(fallDrop>0)时，钳制目标
            //   直接取【全样本最高水面/唇口】而非加权平均——只影响 near-fall 岸坡列。
            //   普通河段无 fallDrop 样本 → 走下方加权平均分支（2026-08-31 岸坡断层修复不变）。
            boolean hasFallNear = false;
            double highWater = Math.max(nearest.surfaceY(), nearest.lipSurfaceY());
            double hwSum = 0.0, hwLevel = 0.0;
            for (HydrologyBlockSample s : samples) {
                double d = s.distToCenter();
                if (d > P.heightBlendDist()) break;   // sampleBlockAll 已按距离升序
                if (s.fallDrop() > 0.0) {
                    hasFallNear = true;
                    highWater = Math.max(highWater, s.lipSurfaceY());
                }
                double fade = NoiseUtil.saturate(d / P.heightBlendDist());
                double w = (1.0 - fade) * (1.0 - fade) / Math.max(d * d, 1.0);
                hwSum += w;
                hwLevel += w * Math.max(s.surfaceY(), s.lipSurfaceY());
            }
            if (!hasFallNear) {
                // 无真落差：保持 2026-08-31 的距离衰减加权语义
                highWater = Math.max(hwLevel / hwSum, highWater);
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
        // ★ 干地下界取 max(混合水面, 最近河实际水面)（2026-08-31）：waterSurface 在谷壁
        //   平滑后是各河 d² 加权的混合值，可能【低于】本列实际紧邻的那条河的水面。按混合
        //   值钳住，干岸仍会被挖到邻接河面以下 → carvedNotch（探针判据用的正是
        //   nearest.surfaceY()，故实测为"主动挖出的岸侧凹台"）。物理上这条约束本就该按
        //   "本列邻接的那条河的水面"成立：不给水的列，不得低到看得见的河面之下。
        double dryFloor = Math.max(waterSurface, nearest.surfaceY());
        if (outsideWaterGate && bedTarget < dryFloor) {
            bedTarget = dryFloor;
        }
        // 雕刻量 = 下切量 × 外缘衰减 × 高度淡出；只下挖
        double cutNeeded = Math.max(0.0, original - bedTarget);
        // ★ 保形下挖（2026-09-09，用户实测"河岸向上一整片被削平的斜坡/平台/垂直平面"）：
        //   旧式 cut = (original − bedTarget)·outer 等价于
        //   carved = original·(1−outer) + carveSurfaceY·outer —— 把地形【拉向水面这个水平面】，
        //   谷壁带整片自然起伏被刨平（带中部 outer≈0.65 → 65% 被拉平）。
        //   改为：谷壁带只减去一个【平滑】量 A0（= depth + bankIncise，再与所需量取小），
        //   carved = original − A0·outer ⇒ 起伏原样保留、只是整体沉降；带外缘 A0·outer→0
        //   时严格等于实际（已侵蚀）地形 —— 这才是"逐步平滑回实际地形"。
        //   缓岸（cutNeeded ≤ A0）取小后 = 旧行为、与河槽连续；深岸只沉 A0、保住山的形状。
        //   河槽缘 [width−k, width+k] 用 smoothstep 过渡，避免"平整床面"与"保形带"硬台阶。
        double cutAmount = cutNeeded;
        double relief = P.bankRelief();
        if (relief > 0.0) {
            // A0 = 河深 + 基准（平滑，不含 local original）
            double a0Raw = depth + P.bankIncise();
            // ★ 阈值必须是【平滑常量】，绝不能依赖 cutNeeded（含锯齿 original）——
            //   实测：用 excess=(cutNeeded−A0) 做权重会把地形锯齿放大成台阶
            //   （平缓 0→863、山地 458→1024）。
            double a0 = Math.min(a0Raw, cutNeeded);
            // 几何窗：只在【谷壁带】生效（dist>width），不碰河槽，避免与"切穿"打架
            double wGeom = NoiseUtil.smooth(NoiseUtil.saturate((dist - width) / k));
            cutAmount = cutNeeded + (a0 - cutNeeded) * (relief * wGeom);
        }
        // ★ 雕刻分段化（Zoned Carve，2026-09-09）：内层定形 + 外层纯接缝。
        //   旧的一段式 outer 从河缘一路 lerp 到谷外缘 —— 带中部 outer≈0.65 处
        //   carved 被拉向"区域平均水面"这个平面，中间地带的局部起伏全被抹掉
        //   （用户实测"河岸向上一整片被削平的斜坡/平台"；山地 footprint 宽达 ~56 格）。
        //   现拆为：内层（dist ≤ width+formRun，formRun = width×formRunFactor）保持
        //   现有 outer 行为；外层 cut = 【内层出口处的 cut】× 纯距离 seamFade(s)
        //   —— 外层不含任何目标面信息，只做"收敛到 0"；谷外缘 seamFade=0 ⇒ cut=0
        //   ⇒ carved ≡ original（数学保证恒等于实际地形）。
        //   关键：内层出口 cut 值本身是光滑的（cutNeeded×outer 在 formRun 处取值），
        //   外层又不引入任何新面 —— 接缝带内不存在"两个面打架"，没有台阶源。
        //   formRunFactor=0 ⇒ 精确回退到旧行为（edgeOuter 分支恒不执行）。
        double cut;
        if (P.formRunFactor() > 0.0 && dist > formEdge) {
            double cutAtEdge = cutAmount * edgeOuter * fadeE;
            // ★ 接缝必须【完整覆盖】[formEdge, valley]：若用 min(…, seamRun) 截断，
            //   会在 formEdge+seamRun 处形成新的 cut 硬边界 → 台阶
            //   （实测：平缓 0→125、山地 458→661，双种子恶化）。
            double seamWidth = Math.max(1.0, valley - formEdge);
            double s = NoiseUtil.saturate((dist - formEdge) / seamWidth);
            cut = cutAtEdge * (1.0 - NoiseUtil.smooth(s));
        } else {
            cut = cutAmount * outer * fadeE;
        }
        double carved = original - cut;

        // ★ 河道内切穿（Streams 语义）：地形高于水面时挖出低于水面的河槽，而不是放弃灌水。
        //   河道内保证 carved < waterSurface → 恒有水（根治干河），且水面高于地形的列
        //   被切穿后不再悬浮。下挖量有界（≤ minWaterDepth），床面连续。
        boolean punchedThrough = false;
        // ★ 河床必须为"至少 1 整块水柱"留出空间（2026-09-06，修河中段凭空断流）：
        //   落块侧（GeoGenesisGenerator.fillTerrainColumn）把水放在
        //   y ∈ (floor(carved), floor(max(ws,lip))] 这段【整数】区间，而原先只保证
        //   连续量 carved ≤ ws − 0.75。两者不等价：ws=104.80 / carved=104.05 相差 0.75
        //   通过门控，但 floor 同为 104 → y=104 被河床块占掉、水柱区间为空，该列铺出
        //   沙质河床却【一个水块都没有】。实测 20 种子 100% 命中、共 4460 列，且相邻
        //   成段 —— 正是玩家看到的"河流填水消失一小段"。
        //   下界取两者较低（min 保证永不抬高河床）：既满足 ≥0.75 的连续水深，
        //   又满足 floor(carved) < floor(ws)。
        double bedCeil = Math.min(waterSurface - 0.75, Math.floor(waterSurface) - 1e-9);
        if (nearestDist <= nearestWidth && carved > bedCeil) {
            if (bedCeil < carved) {
                cut += carved - bedCeil;
                carved = bedCeil;
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
                waterSurface, lipSurfaceY, cut, erosionMask, anyFill, false);
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

    /**
     * 自适应谷壁跨度（2026-09-09）：保证岸坡坡度不超过上限。
     *
     * <p>跨度 R ≥ 岸高 H × {@code bankSlopeRun}；低岸（want ≤ baseRun）维持旧固定
     * 跨度不变，高岸自动展宽，但不超过 {@code bankRunMax}（防深切河谷无限外扩）。</p>
     *
     * @param carveSurfaceY 河缘雕刻面（岸顶目标高度；dist=width 处 profile=0 的 bedTarget）
     * @param original      本列原地形高度
     * @param baseRun       旧固定跨度下界（valley − width 的原语义）
     */
    private static double adaptiveBankRun(double carveSurfaceY, double original,
                                          double baseRun, RiverLineParams P) {
        double h = Math.max(0.0, original - carveSurfaceY);   // 岸高
        double want = h * P.bankSlopeRun();
        if (want <= baseRun) return baseRun;
        return Math.min(want, Math.max(baseRun, P.bankRunMax()));
    }

    /**
     * 侵蚀让步系数（方案 A）：河床对侵蚀 delta 的采纳比例。
     * 河心（dist≤width）→0（河床 = 计划 carved，与计划水面自洽）；
     * 谷外（dist≥valley）→1（全量侵蚀）；中间 smoothstep 连续过渡。
     */
    private static double channelErosionMask(double dist, double width, double valley) {
        if (dist <= width) return 0.0;
        double u = NoiseUtil.saturate((dist - width) / Math.max(1.0, valley - width));
        return NoiseUtil.smooth(u);
    }
}
