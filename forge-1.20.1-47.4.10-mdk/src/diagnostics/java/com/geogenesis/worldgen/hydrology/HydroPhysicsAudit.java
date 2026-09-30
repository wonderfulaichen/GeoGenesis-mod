package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flow.TerrainFlowSim;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【水文物理审计】探针（2026-09-20）。
 *
 * <h2>用户给出的物理定义（本探针唯一判据来源）</h2>
 * <blockquote>"水文就是水的流体物理运动形成的几种状态，堆积就是湖/海，流动路线就是河流。
 * 我的要求就是达到模拟现实物理的仿真水文效果。"</blockquote>
 *
 * <p>由此导出<b>可测判据</b>：</p>
 * <ol>
 *   <li><b>河必有水</b>：河线存在的每一处，都必须能看见水（用户："河线规划出来必须有水"）；</li>
 *   <li><b>水必在低处</b>：水面必须高于河床、且不高于两岸（水往低处流的必然结果）；</li>
 *   <li><b>水必向低处流</b>：沿程水面不得抬升；</li>
 *   <li><b>堆积成湖/海</b>：水在洼地堆积 ⇒ 终点必须是湖或海（或合法跨区续流），
 *       不允许断在内陆；</li>
 *   <li><b>汇合守恒</b>：支流汇入后下游不得比支流细（汇流面积只增不减的必然结果）。</li>
 * </ol>
 *
 * <h2>输出</h2>
 * 逐条判据的 <b>红/绿</b> + 计数 + 前几例（含坐标，便于定位与实机核对）；
 * 以及 {@code build/hydroaudit/{current,overlay}.png}（<b>统一用生产地形灰度底图</b>，
 * 河/湖/海叠加其上 ⇒ 可直接目视判定"河是否在有水的低处"）。
 *
 * <pre>{@code
 *   gradlew runHydroPhysicsAudit -PprobeArgs="seed centerBX centerBZ windowBlocks"
 * }</pre>
 */
public final class HydroPhysicsAudit {

    private HydroPhysicsAudit() { }

    private static final double C_SEA = 0xFF10314F;
    private static final double C_LAKE = 0xFF4FA3D1;
    private static final double C_RIVER = 0xFF1E6BD6;

    private static int win, x0, z0;
    private static Cell[] cellArr;
    private static CellGenerator gen;

    /**
     * 版本模式：驱动 {@code RiverLineNetwork} 的静态开关，使同进程可跑两套。
     *
     * <ul>
     *   <li>{@link #MODE_NEW}：<b>最新改进</b>（填洼优先 + PL-RGA 地形比例插值水面）；</li>
     *   <li>{@link #MODE_PREV}：<b>上一版本</b>（原始 e 流向 + PAVA 水面）—— 即本轮回退前的行为。</li>
     * </ul>
     * <p>用于"逐步改进"：左图 = NEW，右图 = PREV，每改一次都有一张可比对的图。</p>
     */
    private static final int MODE_NEW = 0, MODE_PREV = 1;

    /**
     * 试验开关：<b>D8 采样格距加密</b>（{@code gridCellScale}）。
     *
     * <p>依据（本项目既有文档 + 用户原话）：{@code RiverLineParams.gridCellScale} 的注释写着
     * "用户判据：<b>运动路线还是有点不自然，感觉像采样太稀少导致的</b> —— 根因就是 D8 采样格距
     * 本身是 48 block；有界粒子只能在格内弯，改不了'格粗'"。
     * ⇒ 这是针对"不自然"的<b>现成杠杆</b>（非新机制）。{@code widthAreaRef} /
     * {@code riverAccumThreshold} 会随 scale² 同步缩放 ⇒ 宽度口径不变。</p>
     */
    private static volatile double gridCellScale = 1.0;

    /**
     * ★ 2026-09-21 采样口径开关（性能诊断）：
     * {@code true} = 用 {@code terrainEQuick} 派生廉价场（纯函数、无侵蚀 tile）；
     * {@code false}（默认）= 用 {@code sampleWu}（含侵蚀，与游戏地表同源但慢 ~100×）。
     *
     * <p>用 {@code -PprobeArgs="seed cx cz win scale cheap"} 传第 6 参开启。</p>
     */
    private static volatile boolean flowSimCheapTerrain = false;

    private static void applyMode(int mode) {
        // NEW = 加密 D8 采样格距（针对"采样太稀少导致不自然"）；PREV = 生产基线格距。
        RiverLineParams.gridCellScale = (mode == MODE_NEW) ? gridCellScale : 1.0;
    }

    public static void main(String[] args) throws Exception {
        // ★ 默认 = 用户认可的对比框架：块(-840,-363) 起算、1400 块见方（勿改，见 COMPARE_* 注释）。
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : COMPARE_CX;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : COMPARE_CZ;
        win = args.length > 3 ? Integer.parseInt(args[3]) : COMPARE_WIN;
        gridCellScale = args.length > 4 ? Double.parseDouble(args[4]) : 0.5;
        flowSimCheapTerrain = args.length > 5 && "cheap".equals(args[5]);
        // ★ 2026-09-22 定向断点：追踪失败样本 block 的 carver 决策链（回答"命中在·水不在"）。
        //   样本 block(-444,-76) = 审计河尾无水 r(-1,-1)#26 k=141..144 所在（同湖多节点）。
        // ★ 2026-09-23 R-C1：args[7],args[8] 可改断点坐标（不传 = 旧行 -444,-76）。
        int trX = args.length > 7 ? Integer.parseInt(args[7]) : -444;
        int trZ = args.length > 8 ? Integer.parseInt(args[8]) : -76;
        HydrologyBlockCarver.TRACE_BLOCK = HydrologyBlockCarver.traceKey(trX, trZ);
        // ★ 2026-09-21：审计【生产侧】时可选打开流体骨架路线（对比新/旧生产路线）
        if (args.length > 6 && "skeleton".equals(args[6])) {
            RiverLineNetwork.flowSkeletonRouting = true;
            System.out.println("★ 生产侧 = 流体骨架路线（flowSkeletonRouting=true）");
        }
        if (args.length > 5 && "macro".equals(args[5])) {
            macro(seed, cx, cz, win);
            return;
        }
        boolean compare = true;

        TerrainParams tp = TerrainParams.defaults();
        gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        currentSeed = seed;
        x0 = cx - win / 2;
        z0 = cz - win / 2;

        System.out.printf("=== HydroPhysicsAudit  seed=%d  窗口 block x∈[%d,%d] z∈[%d,%d] %d×%d ===%n",
                seed, x0, x0 + win - 1, z0, z0 + win - 1, win, win);

        // ---------- 新版：审计 + 出图 ----------
        applyMode(MODE_NEW);
        int[] imgNew = buildAndAudit("NEW（流体物理模拟 TerrainFlowSim）");

        int[] imgPrev = null;
        if (compare) {
            System.out.println();
            applyMode(MODE_PREV);
            imgPrev = buildAndAudit("PREV（上一版本：原始 e 流向 + PAVA 水面）");
        }

        // ---------- 出图：左=NEW，右=PREV，统一标题标注 ----------
        File dir = new File("build/hydroaudit");
        dir.mkdirs();
        BufferedImage a = toImage(imgNew);
        ImageIO.write(a, "png", new File(dir, "new.png"));
        if (imgPrev != null) {
            BufferedImage b = toImage(imgPrev);
            ImageIO.write(b, "png", new File(dir, "prev.png"));
            ImageIO.write(side(a, b), "png", new File(dir, "compare.png"));
            System.out.printf("%n[出图] build/hydroaudit/compare.png"
                    + "（左=NEW 最新改进 · 右=PREV 上一版本；同一 seed/窗口/统一灰度底图规则）%n");
        } else {
            System.out.printf("%n[出图] build/hydroaudit/new.png%n");
        }
    }

    /** 按当前开关跑一遍：采样生产 Cell → 审计 → 出图色块；返回 RGB 数组。 */
    private static int[] buildAndAudit(String label) {
        System.out.printf("%n---------- %s ----------%n", label);
        runFlowSim = label.startsWith("NEW");
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(currentSeed);
        staticGt = gt;   // ★ F1i：供 cellAtB 窗外取格（河尾可能落在窗口矩形外）
        Map<Long, Cell[]> cache = new HashMap<>();
        cellArr = new Cell[win * win];
        for (int j = 0; j < win; j++) {
            for (int i = 0; i < win; i++) {
                cellArr[j * win + i] = cellAt(gt, cache, x0 + i, z0 + j, () -> { });
            }
        }
        int[] rgb = new int[win * win];
        for (int j = 0; j < win; j++) {
            for (int i = 0; i < win; i++) {
                Cell c = cellArr[j * win + i];
                int v;
                if (c == null) v = 0xFF000000;
                else if (c.isWater()) v = (int) C_SEA;
                else if (c.isLake) v = (int) C_LAKE;
                else if (c.riverType != 0) v = (int) C_RIVER;
                else {
                    int g = (int) Math.max(0, Math.min(255, 60 + 0.6 * c.height));
                    v = 0xFF000000 | (g << 16) | (g << 8) | g;
                }
                rgb[j * win + i] = v;
            }
        }
        basemapSnapshot = rgb;

        if (runFlowSim) {
            // ★★★ 2026-09-21【先清底图的生产水色，再画模拟水】★★★
            //   用户实测："怎么还有之前的河流也显示在里面？"
            //   底图 = 生产 Cell 灰度图（含生产河的深蓝细线/湖的浅蓝）⇒ 必须先抹掉，
            //   否则模拟水没覆盖到的地方会漏出【另一套系统】的颜色 = 两张图叠一起。
            //   此处位于任何模拟水绘制【之前】⇒ 无条件清除所有非海的生产水色。
            for (int j = 0; j < win; j++) {
                for (int i = 0; i < win; i++) {
                    int k = j * win + i;
                    int v = rgb[k];
                    if (v != (int) C_RIVER && v != (int) C_LAKE && v != (int) C_SEA) continue;
                    Cell c = cellArr[k];
                    if (c != null && c.isWater() && c.height <= gen.seaLevel()) continue; // 真海保留
                    int g = (int) Math.max(0, Math.min(255, 60 + 0.6 * (c == null ? 0 : c.height)));
                    rgb[k] = 0xFF000000 | (g << 16) | (g << 8) | g;
                }
            }
            // ★ NEW 决策层 = 块分辨率流体物理模拟（TerrainFlowSim）
            final double hs = 2.0;   // 水平缩放：block ÷ wu（生产 TerrainParams.horizontalScale）
            int thr = Math.max(200, (win * win) / 300);          // ≈窗口 0.33% 集水即成河
            long t0 = System.nanoTime();
            // ★★ 关键口径修正（2026-09-21）：模拟必须喂【雕刻前的地形】。
        //   原因：cellArr = getChunkCells 的最终地形（已被【旧河网】雕刻过）⇒ 那些人工挖出的
        //   河谷/河床会被填洼算法当成"天然洼地" ⇒ 整片河谷灌成湖（实测湖占 28.12%）。
        //   正解：喂 {@code CellGenerator.sampleWu}（= 侵蚀后、雕刻前的真实地表）。
        //   ⚠ 底图灰度仍用生产最终地形（两侧同底，保证对比公平）；只有【模拟输入】换口径。
        final double hsSim = hs;
        // ★★★ 无限世界正确性（用户判据："我就怕这个水文的湖泊是有限地形搞出来的假湖"）★★★
        //   窗口截断会把"真出口在窗外"的河谷误判成封闭洼地 = 假湖。
        //   对策 = margin 采样：外环 FLOW_MARGIN 块只用于让填洼/汇流收敛，
        //   湖/河判定只采信【内窗】。与现有 FlowField 的 region margin 同一范式
        //   （HANDOFF[4] 实测 margin 320wu ≈ 13 格已足够收敛）⇒ 无限世界 = region 平铺。
        // ★★★ 2026-09-21【采样口径 A/B 开关】★★★
        //   实测（1400² 框架）：模拟总 92.6s 中【地形采样 89.07s（96%）】、算法仅 3.50s（4%）。
        //   ⇒ 接入生产的死结在采样，不在算法。两种口径：
        //     · false（默认）= sampleWu：含侵蚀 tile（与游戏地表同源，但 ~5.5ms/块）
        //     · true        = terrainEQuick 派生：纯函数场（无 tile），生产接线的河网选线
        //                     用的就是它（HydrologyExperimentEngine 的 eSampler）⇒ 快 ~100×
        //   生产接入必须走后者（PLAN T3.1：金字塔采样 = 粗场定走廊、精场只算走廊）。
        flow = flowSimCheapTerrain
                ? TerrainFlowSim.simulate(
                        (bx, bz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(bx / hsSim, bz / hsSim)),
                        x0 - FLOW_MARGIN, z0 - FLOW_MARGIN, win + 2 * FLOW_MARGIN,
                        gen.seaLevel(), thr)
                : TerrainFlowSim.simulate(
                        (bx, bz) -> gen.sampleWu(bx / hsSim, bz / hsSim).height,
                        x0 - FLOW_MARGIN, z0 - FLOW_MARGIN, win + 2 * FLOW_MARGIN,
                        gen.seaLevel(), thr);
        flow = projectToWindow(flow, win);
            System.out.printf("[flow] 阈值=%d 块 · 模拟 %.2f s（其中地形采样 %.2f s · 算法 %.2f s）%n", thr,
                    (System.nanoTime() - t0) / 1e9,
                    TerrainFlowSim.lastSampleMs / 1000.0, TerrainFlowSim.lastAlgoMs / 1000.0);
            // ★★★ 河-湖-河循环可视化（用户物理定义）★★★
            //   河（源→低处）→ 到洼地终止 → 堆积成湖 → 湖满从溢出口外泄
            //   → 溢出口 = 【下一段河的起点】→ 循环到海。
            //   渲染规则：① 河画到湖岸为止（湖格不画河）② 湖面铺湖色
            //   ③ 溢出口 = 湖格中 down 指向【非湖】者，用亮青标记 ⇒ 起点肉眼可见。
            int chan = 0, lakeN = 0;
            boolean[] lake = flow.lake;
            // ★★★ 2026-09-21【河道按汇流面积分级宽度】★★★
            //   用户判据："仿真河流各种地貌特征" —— 河流地貌第一特征就是【宽度沿程增大】。
            //   旧渲染把河道画成 1 像素线（1400 块图上 = 钢丝），分级完全看不出，
            //   且无法目视判断"支流细、干流粗"这一汇流守恒的可见结果。
            //   物理律：W ∝ √A（下游汇流面积只增 ⇒ 河只变宽不变细）。
            //   本工程既有同源公式：`width = W0·√(accum/widthAreaRef)`（RiverLineNetwork
            //   的 widthFromAccum）。此处取其同一量纲：以成河阈值 accum=chanThreshold 为
            //   1 块宽基准，向下游按 √ 增长、上限 maxW 块（= 视觉可辨的大江）。
            for (int j = 0; j < win; j++) {
                for (int i = 0; i < win; i++) {
                    int k = j * win + i;
                    if (lake[k]) { rgb[k] = (int) C_LAKE; lakeN++; }
                }
            }
            final double maxW = 14.0;      // 干流最大全宽（块）
            // ★★★ 2026-09-21【保下坡曲流（meander）】★★★
            //   用户判据："仿真河流各种地貌特征" —— D8 在缓坡上是直线/直角，太假。
            //   物理依据：真实河流的曲流是水流在缓坡上的【横向摆动】，摆动沿【等高线方向】
            //   进行 —— 沿等高线走【高程不变】⇒ 不破坏"水往低处流"（这是本项目铁律）。
            //   实现：对每个河道格，取其局部梯度 → 等高线方向 t = (-gz, gx)/|g|；
            //         相位 = 沿程距离/波长；位移 = 振幅·sin(相位)，仅沿 t 施加。
            //   ⚠ 纯几何位移（不改高程、不改 down[]），故判据 1（横切 0/上坡 0）不受影响。
            final double meanderWavelength = 90.0;   // 曲流波长（块）—— 真实河 5~10 倍河宽
            final double meanderAmpMax = 5.0;        // 最大横向振幅（块）
            for (int j = 0; j < win; j++) {
                for (int i = 0; i < win; i++) {
                    int k = j * win + i;
                    if (!flow.channel[k] || lake[k]) continue;
                    chan++;
                    double wFull = Math.min(maxW,
                            Math.sqrt((double) flow.accum[k] / Math.max(1, flow.chanThreshold)));
                    // 局部梯度（中央差分，块）
                    double hx = 0, hz = 0;
                    if (i > 0 && i < win - 1) hx = flow.height[k + 1] - flow.height[k - 1];
                    if (j > 0 && j < win - 1) hz = flow.height[k + win] - flow.height[k - win];
                    double gl = Math.hypot(hx, hz);
                    double tx = 0, tz = 0;                       // 等高线方向（单位）
                    if (gl > 1e-9) { tx = -hz / gl; tz = hx / gl; }
                    // 相位：沿等高线的投影坐标（保证同一条等高线上相位一致 = 曲流成"波"）
                    double phase = (i * tx + j * tz) / meanderWavelength * 2.0 * Math.PI;
                    double amp = Math.min(meanderAmpMax, 0.35 * wFull) * (gl > 1e-9 ? 1.0 : 0.0);
                    double off = amp * Math.sin(phase);
                    int shiftI = (int) Math.round(tx * off);
                    int shiftJ = (int) Math.round(tz * off);
                    int r = (int) Math.ceil(wFull / 2.0);          // 半宽（块）
                    for (int dj = -r; dj <= r; dj++) {
                        for (int di = -r; di <= r; di++) {
                            if (di * di + dj * dj > r * r) continue;
                            int ni = i + di + shiftI, nj = j + dj + shiftJ;
                            if (ni < 0 || nj < 0 || ni >= win || nj >= win) continue;
                            int nk = nj * win + ni;
                            if (lake[nk]) continue;                 // 河不覆盖湖面
                            rgb[nk] = (int) C_RIVER;
                        }
                    }
                }
            }
            // 逐湖统计：是否有溢出口（判据3：每湖必有出流；内流盆地单列）
            int[] comp = new int[win * win];
            java.util.Arrays.fill(comp, -1);
            int lakes = 0, withOutflow = 0;
            for (int j = 0; j < win; j++) {
                for (int i = 0; i < win; i++) {
                    int k = j * win + i;
                    if (!lake[k] || comp[k] >= 0) continue;
                    int id = lakes++;
                    java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
                    q.add(k); comp[k] = id;
                    boolean out = false;
                    while (!q.isEmpty()) {
                        int c = q.poll();
                        int d = flow.down[c];
                        if (d < 0 || !lake[d]) out = true;
                        int ci = c % win, cj = c / win;
                        for (int dj = -1; dj <= 1; dj++) {
                            for (int di = -1; di <= 1; di++) {
                                int ni = ci + di, nj = cj + dj;
                                if (ni < 0 || nj < 0 || ni >= win || nj >= win) continue;
                                int nb = nj * win + ni;
                                if (lake[nb] && comp[nb] < 0) { comp[nb] = id; q.add(nb); }
                            }
                        }
                    }
                    if (out) withOutflow++;
                }
            }
            // ===== ★★★ 2026-09-21【每湖只标一个溢出口】★★★ =====
            //
            //   【被修的缺陷】旧实现把"湖内每一格 down 指向非湖格"都标成溢出口（2×2 亮青）
            //   —— 实测打出 4054 个标记（16218 像素），糊在湖面上就是用户看到的
            //   "为什么湖泊里面还有线呢？"。物理上这是错的：
            //     ① 湖水位涨到 spill 高度后，只会从【最低的那一个缺口】溢出 —— 一个湖一个出口；
            //     ② `lake[]` 只是"填洼面 > 地形"的水淹格，湖内的【平岸浅水格】
            //        （填洼面 == 地形）不算湖 ⇒ 湖内每格指向它的 down 都被误判成"出口"。
            //
            //   【正解】按连通湖组分（comp 已算）分组，每组只取【溢出高程最低】的那一格作为
            //   真出口（= 用户定义的"堆积到发现新的溢出口 = 下一段河的起点"）。
            int spills = 0;
            int[] bestExit = new int[Math.max(1, lakes)];
            java.util.Arrays.fill(bestExit, -1);
            for (int j = 1; j < win - 1; j++) {
                for (int i = 1; i < win - 1; i++) {
                    int k = j * win + i;
                    if (!lake[k]) continue;
                    int d = flow.down[k];
                    if (d < 0 || lake[d]) continue;
                    int id = comp[k];
                    if (id < 0) continue;
                    // 取溢出高程（本格填洼面）最低者 = 真正的最低缺口
                    if (bestExit[id] < 0 || flow.fill[k] < flow.fill[bestExit[id]]) bestExit[id] = k;
                }
            }
            for (int id = 0; id < lakes; id++) {
                int k = bestExit[id];
                if (k < 0) continue;
                spills++;
                int i = k % win, j = k / win;
                for (int dj = 0; dj <= 1; dj++) {
                    for (int di = 0; di <= 1; di++) {
                        int ni = i + di, nj = j + dj;
                        if (ni < 0 || nj < 0 || ni >= win || nj >= win) continue;
                        rgb[nj * win + ni] = 0xFF00E5FF;
                    }
                }
            }
            System.out.printf("[河湖循环] 湖 %d 个 · 有溢出口 %d 个（判据3：应相等）· "
                    + "溢出口标记 %d 个（每湖一个，旧实现 4054 个）· 湖外河格 %d%n",
                    lakes, withOutflow, spills, chan);
            // ★★★ 2026-09-21【湖内河线】诊断 + 渲染修正 ★★★
            //   用户判据："为什么湖泊里面还有线呢？是不是这个是需要排除的线？" —— 是，必须排除。
            //   物理语义：湖面是【静止水面】，其上一律是湖；若某格被湖包围却仍被标成
            //   channel（汇流累积达阈值），那是"河道标记"与"淹没判定"两套判据在湖心重叠，
            //   渲染上必须让【湖】胜出（水已经漫过那里了，不存在露出来的河槽）。
            int chanInLake = 0;
            List<String> inLakeEx = new ArrayList<>();
            for (int j = 1; j < win - 1; j++) {
                for (int i = 1; i < win - 1; i++) {
                    int k = j * win + i;
                    if (!flow.channel[k] || flow.lake[k]) continue;
                    int nl = 0;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            if (di == 0 && dj == 0) continue;
                            if (flow.lake[(j + dj) * win + (i + di)]) nl++;
                        }
                    }
                    if (nl >= 6) {
                        chanInLake++;
                        rgb[k] = (int) C_LAKE;          // 被湖包围 ⇒ 它是湖面，不是河
                        if (inLakeEx.size() < 5) {
                            inLakeEx.add(String.format(
                                    "  湖内河格 block(%d,%d) accum=%d 地形=%.1f 填洼=%.1f",
                                    x0 + i, z0 + j, flow.accum[k], flow.height[k], flow.fill[k]));
                        }
                    }
                }
            }
            System.out.printf("[湖内河线] channel∧¬lake 且 8 邻中≥6 为湖 = %d 格（已按湖面渲染）%n",
                    chanInLake);
            inLakeEx.forEach(System.out::println);

            // 注：河道加粗/底图清理已在【绘制之前】统一完成（见上方两段），此处不再重复。
            flowSimSnapshot = rgb;
            // ★ 2026-09-21 量化"湖里的线"：湖色像素里若混着河色/青色，就是视觉上的湖内线。
            int riverInLakeArea = 0, spillMarks = 0;
            for (int j = 1; j < win - 1; j++) {
                for (int i = 1; i < win - 1; i++) {
                    int k = j * win + i;
                    if (!flow.lake[k]) continue;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            int v = rgb[(j + dj) * win + (i + di)];
                            if (v == (int) C_RIVER) riverInLakeArea++;
                            if (v == 0xFF00E5FF) spillMarks++;
                        }
                    }
                }
            }
            System.out.printf("[湖内线诊断] 湖格邻域内的河色像素=%d · 青色溢出口标记像素=%d%n",
                    riverInLakeArea, spillMarks);
            System.out.printf("[flow] 河格 %d（%.2f%%）· 湖格 %d（%.2f%%）%n",
                    chan, 100.0 * chan / rgb.length, lakeN, 100.0 * lakeN / rgb.length);
            auditFlowSim();
            return rgb;
        }

        // 河网（★ 2026-09-21 必须用【生产正在用的那份】，见 GeoGenesisTerrain.hydrologyNetwork）
        //   旧写法自建 `new RiverLineNetwork(gen::terrainEQuick, null, ...)`：缺生产接线里的
        //   precipSampler（降水加权汇流）+ horizontalScale ⇒ 路由与生产雕刻的不是同一条河
        //   ⇒ 干节点判据大面积假阳性（样本出现"折线水面 166.2 而 Cell 水面 178.0"的矛盾）。
        RiverLineNetwork net = gt.hydrologyNetwork();
        int rx0 = Math.floorDiv(x0, 640) - 1, rz0 = Math.floorDiv(z0, 640) - 1;
        int rx1 = Math.floorDiv(x0 + win, 640) + 1, rz1 = Math.floorDiv(z0 + win, 640) + 1;
        List<RiverLineRegion> regions = new ArrayList<>();
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) regions.add(net.region(rx, rz));
        }
        audit(regions, net);
        return rgb;
    }

    private static TerrainFlowSim.Result flow;

    /** 外环采样余量（块）：吸收"真出口在窗外"的截断假湖（无限世界 = region 平铺 + margin）。 */
    public static final int FLOW_MARGIN = 384;

    /** 把带 margin 的模拟结果裁剪回审计窗口（down 重映射到窗口索引；步出窗 = -1）。 */
    private static TerrainFlowSim.Result projectToWindow(TerrainFlowSim.Result r, int win) {
        int fn = r.n, mo = (fn - win) / 2;
        int total = win * win;
        double[] h = new double[total], f = new double[total];
        int[] down = new int[total];
        long[] acc = new long[total];
        boolean[] ch = new boolean[total], lk = new boolean[total];
        for (int j = 0; j < win; j++) {
            for (int i = 0; i < win; i++) {
                int w = j * win + i, g = (j + mo) * fn + (i + mo);
                h[w] = r.height[g]; f[w] = r.fill[g]; acc[w] = r.accum[g];
                ch[w] = r.channel[g]; lk[w] = r.lake[g];
                int d = r.down[g];
                if (d >= 0) {
                    int di = d % fn - (i + mo), dj = d / fn - (j + mo);
                    int ni = i + di, nj = j + dj;
                    down[w] = (ni < 0 || nj < 0 || ni >= win || nj >= win) ? -1 : nj * win + ni;
                } else {
                    down[w] = -1;
                }
            }
        }
        return new TerrainFlowSim.Result(win, h, f, down, acc, ch, lk,
                r.chanThreshold, r.seaLevel);
    }

    private static boolean neighChannel(int i, int j) {
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                TerrainFlowSim.Result f = flow;
                int nk = (j + dj) * win + (i + di);
                // ★ 2026-09-21：湖格不算"相邻河道"—— 否则湖岸被加粗成河色，
                //   视觉上"湖里长出河流的线"（用户实测判据："为什么湖泊里面还有线？"）。
                if (f.lake[nk]) continue;
                if (f.channel[nk] && f.accum[nk] >= f.chanThreshold * 6L) return true;
            }
        }
        return false;
    }

    /**
     * ★ 对【流体模拟】的河道量路线物理保真度 —— 这是本次重写的验收指标：
     * 每个河道格的流动方向 = 填洼面 D8；对它测与【生产可见地形】最陡下降的夹角
     * （理论上应接近 0°：填洼只发生在洼地内部，坡面上 fill == 原始地形）。
     */
    private static void auditFlowSim() {
        int steps = 0, uphill = 0, beyond45 = 0, crossCut = 0;
        double angSum = 0;
        for (int j = 1; j < win - 1; j++) {
            for (int i = 1; i < win - 1; i++) {
                int k = j * win + i;
                if (!flow.channel[k]) continue;
                int d = flow.down[k];
                if (d < 0) continue;
                int di = (d % win) - i, dj = (d / win) - j;
                double len = Math.hypot(di, dj);
                if (len < 1e-6) continue;
                steps++;
                double bx = x0 + i, bz = z0 + j;
                double h0 = heightAtBlock(bx, bz), h1 = heightAtBlock(bx + di, bz + dj);
                if (!Double.isNaN(h0) && !Double.isNaN(h1) && h1 > h0 + 1e-6) uphill++;
                double[] g = descentDir(bx, bz);
                if (g == null) continue;
                double cos = (di / len) * g[0] + (dj / len) * g[1];
                double ang = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
                angSum += ang;
                if (ang > 45.0) beyond45++;
                if (ang > 60.0) crossCut++;
            }
        }
        System.out.println();
        System.out.println("【NEW 路线物理保真度】（目标：夹角→0°，横切/上坡→0）");
        System.out.printf("  步数 %d · 与最陡下降平均夹角 %.1f° · >45° %d (%.1f%%) · "
                        + ">60° %d (%.1f%%) · 上坡步 %d%n",
                steps, angSum / Math.max(1, steps), beyond45, 100.0 * beyond45 / Math.max(1, steps),
                crossCut, 100.0 * crossCut / Math.max(1, steps), uphill);
    }

    private static long currentSeed = 9139912035078620160L;
    private static int[] basemapSnapshot;
    // NEW 模式 = 跑【流体物理模拟】并以它的河网出图/审计；PREV 模式 = 当前生产画线。
    private static boolean runFlowSim;
    private static int[] flowSimSnapshot;

    private static BufferedImage toImage(int[] rgb) {
        BufferedImage img = new BufferedImage(win, win, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < win; j++) {
            for (int i = 0; i < win; i++) img.setRGB(i, j, rgb[j * win + i]);
        }
        return img;
    }

    /**
     * ★ 宏观验收模式（用户判据："看局部看不出问题"）——
     * 在【整片大陆尺度】(默认 2048×2048 块) 上跑流体物理模拟，出整图。
     *
     * <p>地形用 {@code heightCurve().heightFromE(terrainEQuick)}（与河网同源的快速纯函数场，
     * 有直接映射缓存 ⇒ 大范围采样可承受；生产侵蚀在落块侧叠加，不影响流路骨架的验收）。</p>
     *
     * <p>成河阈值随窗口面积走（默认 窗口面积/2000）：大陆尺度上河流应该稀疏而分级。</p>
     *
     * <p>同图画出【当前生产河线】做对照（同一片地形上，蓝=新模拟，紫=当前画线）。</p>
     */
    /**
     * ★★★ <b>对比框架（用户认可，勿改）</b> ★★★
     *
     * <p>依据：用户以 {@code compare.f81bfad03c.png} 明确认定"这图才对"
     * —— 该图尺度 = <b>块(-840,-363) 起算、1400 块见方</b>，
     * 框架 = <b>左（新机制） / 右（当前系统）</b>、共用同一份生产地形灰度底图。</p>
     *
     * <p>⚠ <b>纪律</b>：不得擅自改尺度（此前擅自跑 2048/1536/256 均被用户否定：
     * "你现在的预览图尺度太大了，明明之前不是定好了吗？"）。
     * 需要其它尺度时用参数显式指定，<b>不要动默认值</b>。</p>
     */
    private static final int COMPARE_CX = -840, COMPARE_CZ = -363, COMPARE_WIN = 1400;

    private static void macro(long seed, int cx, int cz, int win) throws Exception {
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale() > 0.01 ? tp.horizontalScale() : 1.0;
        double sea = gen.seaLevel();
        int x0 = cx - win / 2, z0 = cz - win / 2;
        System.out.printf("=== MACRO 流体物理模拟 seed=%d 窗口 x∈[%d,%d] z∈[%d,%d] %d×%d ===%n",
                seed, x0, x0 + win - 1, z0, z0 + win - 1, win, win);

        long t0 = System.nanoTime();
        int thr = Math.max(500, win * win / 2000);
        // ★ 底图升级（用户判据："预览图像之前这图这样才对"）—— 用【生产级地形】
        //   {@code sampleWu}（含侵蚀 tile，与游戏地表同源 ⇒ 能看见谷地纹理），
        //   而不是平滑的 terrainEQuick。侵蚀 tile 有缓存 ⇒ 大范围一次性成本可承受。
        var res = com.geogenesis.worldgen.hydrology.flow.TerrainFlowSim.simulate(
                (bx, bz) -> gen.sampleWu(bx / hs, bz / hs).height,
                x0, z0, win, sea, thr);
        System.out.printf("[sim] 阈值=%d 块 · %.1f s（含生产级地形采样）%n", thr,
                (System.nanoTime() - t0) / 1e9);

        int chan = 0, lakeN = 0;
        for (int k = 0; k < win * win; k++) {
            if (res.lake[k]) lakeN++;
            else if (res.channel[k]) chan++;
        }
        System.out.printf("[统计] 河格 %d（%.2f%%）· 湖格 %d（%.2f%%）%n",
                chan, 100.0 * chan / (win * win), lakeN, 100.0 * lakeN / (win * win));

        // ---------- 渲染（山体阴影 + 河(按 W∝√A 铺宽) + 湖）----------
        // ★ 宽度铺放（用户判据：宏观上大河必须可见宽度层级）：
        //   半宽 = clamp(0.5·√(accum/阈值), 0.5, 窗口/64)，对每个河道格盖圆盘 ⇒ 连续变宽。
        int maxR = Math.max(2, win / 64);
        java.util.BitSet wet = new java.util.BitSet(win * win);
        for (int j = 0; j < win; j++) {
            for (int i = 0; i < win; i++) {
                int k = j * win + i;
                if (!res.channel[k]) continue;
                double r = Math.min(maxR, Math.max(0.7,
                        0.5 * Math.sqrt((double) res.accum[k] / thr)));
                int ri = (int) Math.ceil(r);
                for (int dj = -ri; dj <= ri; dj++) {
                    for (int di = -ri; di <= ri; di++) {
                        int ni = i + di, nj = j + dj;
                        if (ni < 0 || nj < 0 || ni >= win || nj >= win) continue;
                        if (di * di + dj * dj <= r * r) wet.set(nj * win + ni);
                    }
                }
            }
        }
        System.out.printf("[render] 最大半宽 %d 块（W ∝ √A）%n", maxR);
        BufferedImage img = new BufferedImage(win, win, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < win; j++) {
            for (int i = 0; i < win; i++) {
                int k = j * win + i;
                int rgb;
                if (res.height[k] <= sea) rgb = C_SEA_I;
                else if (res.lake[k]) rgb = C_LAKE_I;
                else if (wet.get(k)) rgb = C_RIVER_I;
                else {
                    double hx = i > 0 && i < win - 1 ? res.height[k + 1] - res.height[k - 1] : 0;
                    double hz = j > 0 && j < win - 1 ? res.height[k + win] - res.height[k - win] : 0;
                    double l = 0.5 - 0.04 * (hx + hz);
                    int v = (int) Math.max(0, Math.min(255, 70 + 130 * l));
                    rgb = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
                img.setRGB(i, j, rgb);
            }
        }
        File dir = new File("build/hydroaudit");
        dir.mkdirs();
        ImageIO.write(img, "png", new File(dir, "macro.png"));
        System.out.printf("[出图] build/hydroaudit/macro.png（%d×%d 整片大陆：河网/湖/海全局形态）%n",
                win, win);
    }

    private static final int C_SEA_I = 0xFF10314F, C_RIVER_I = 0xFF1E6BD6, C_LAKE_I = 0xFF4FA3D1;

    /** 并排 + 标题条（左侧写"最新改进"，右侧写"上一版本"）。 */
    private static BufferedImage side(BufferedImage a, BufferedImage b) {
        int bar = 34, w = a.getWidth() + b.getWidth() + 4;
        BufferedImage o = new BufferedImage(w, a.getHeight() + bar, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = o.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, w, a.getHeight() + bar);
        g.drawImage(a, 0, bar, null);
        g.drawImage(b, a.getWidth() + 4, bar, null);
        g.setColor(java.awt.Color.RED);
        g.fillRect(a.getWidth(), 0, 4, a.getHeight() + bar);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 15));
        g.setColor(java.awt.Color.WHITE);
        g.drawString("NEW: physical flow sim (block-res D8 on real terrain)", 8, 22);
        g.drawString("PREV (current): 48-block trace + meander decor", a.getWidth() + 12, 22);
        g.dispose();
        return o;
    }

    /**
     * ★★★ 【路线物理保真度】—— 用户判据的核心："流体运动路线完全不符合现实物理"。
     *
     * <p>水的运动路线只有一条物理法则：<b>沿最陡下降方向走</b>。故对每一段河道测：
     * <ol>
     *   <li><b>上坡步占比</b>：沿程地形不得抬升（水不往高处流）——目标 0；</li>
     *   <li><b>与最陡下降方向的夹角</b>：0° = 完全顺坡；90° = 横切等高线（物理上不可能）
     *       ——用生产地形高程的中央差分算局部最陡下降方向；</li>
     *   <li><b>横切度</b>：{@code |夹角|>60°} 的步占比；</li>
     *   <li><b>蜿蜒度 sinuosity</b>：路径长 ÷ 首尾直线距（真实山地河 1.2~2.0）。</li>
     * </ol>
     */
    private static void routeFidelity(List<RiverLineRegion> regions) {
        int steps = 0, uphill = 0, crossCut = 0, beyond45 = 0;
        int upLegit = 0, upReal = 0;   // ★ F3 上坡二分类：合法(湖面/回水穿越) vs 真违例
        int crLegit = 0, crReal = 0;    // ★ F3 横切二分类：同一"填洼面穿越"机理
        double angSum = 0, angAbsSum = 0, sinuSum = 0;
        int sinuN = 0;
        List<String> worst = new ArrayList<>();

        for (RiverLineRegion r : regions) {
            for (RiverLineRegion.RiverPolyline p : r.rivers) {
                int n = p.nodes.length;
                if (n < 3) continue;
                double mx = p.nodes[n / 2].x() * 2.0, mz = p.nodes[n / 2].z() * 2.0;
                if (mx < x0 || mz < z0 || mx > x0 + win || mz > z0 + win) continue;
                double pathLen = 0;
                for (int k = 1; k < n; k++) {
                    double bx0 = p.nodes[k - 1].x() * 2.0, bz0 = p.nodes[k - 1].z() * 2.0;
                    double bx1 = p.nodes[k].x() * 2.0, bz1 = p.nodes[k].z() * 2.0;
                    double dx = bx1 - bx0, dz = bz1 - bz0;
                    double d = Math.hypot(dx, dz);
                    pathLen += d;
                    if (d < 1e-6) continue;
                    steps++;
                    double h0 = heightAtBlock(bx0, bz0), h1 = heightAtBlock(bx1, bz1);
                    // ★ F3 沉水判据（上坡/横切共用）：步任一端在【本节点水面下】或湖格
                    //   ⇒ 该步走在填洼/回水面上（对真实地形的夹角与上坡读数物理合法）。
                    boolean sub = false;
                    {
                        Cell ca = cellAtB((int) Math.round(bx0), (int) Math.round(bz0));
                        Cell cb = cellAtB((int) Math.round(bx1), (int) Math.round(bz1));
                        double s0 = p.surfaceY[k - 1], s1 = p.surfaceY[k];
                        if (ca != null && (ca.isLake
                                || (ca.riverType != 0 && h0 < s0 - 0.5))) sub = true;
                        if (cb != null && (cb.isLake
                                || (cb.riverType != 0 && h1 < s1 - 0.5))) sub = true;
                    }
                    if (h1 > h0 + 1e-6) {
                        uphill++;
                        if (sub) upLegit++; else upReal++;
                    }
                    // 局部最陡下降方向（中央差分，观察窗 ±6 块）
                    double[] g = descentDir(bx0, bz0);
                    if (g == null) continue;
                    double cos = (dx / d) * g[0] + (dz / d) * g[1];
                    double ang = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
                    angSum += ang;
                    angAbsSum += ang;
                    if (ang > 45.0) beyond45++;
                    if (ang > 60.0) {
                        crossCut++;
                        // ★ F3：沉水步（填洼/回水面穿越）= 物理合法 —— 水沿 ε 微坡走，
                        //   与真实地形最陡方向本就可垂直（等高线方向）；非沉水才计真违例。
                        if (sub) {
                            crLegit++;
                        } else {
                            crReal++;
                            if (worst.size() < 4) {
                                worst.add(String.format(
                                        "       横切 %d° block(%.0f,%.0f)→(%.0f,%.0f) 高差 %.2f 局部梯度(%.2f,%.2f)",
                                        (int) ang, bx0, bz0, bx1, bz1, h1 - h0, g[0], g[1]));
                            }
                        }
                    }
                }
                double straight = Math.hypot(p.nodes[n - 1].x() - p.nodes[0].x(),
                        p.nodes[n - 1].z() - p.nodes[0].z()) * 2.0;
                if (straight > 1e-3) {
                    sinuSum += pathLen / straight;
                    sinuN++;
                }
            }
        }
        System.out.println();
        System.out.println("【路线物理保真度】（水只能沿最陡下降走）");
        System.out.printf("  步数 %d · 上坡步 %d (%.1f%%) · 横切>60° %d (%.1f%%) · >45° %d (%.1f%%)%n",
                steps, uphill, 100.0 * uphill / Math.max(1, steps),
                crossCut, 100.0 * crossCut / Math.max(1, steps),
                beyond45, 100.0 * beyond45 / Math.max(1, steps));
        System.out.printf("  上坡二分类：湖面/回水穿越(物理合法) %d · 真路由违例 %d（目标 0）%n",
                upLegit, upReal);
        System.out.printf("  横切二分类：填洼面穿越(物理合法) %d · 真路由违例 %d（目标 0）%n",
                crLegit, crReal);
        System.out.printf("  与最陡下降的平均夹角 %.1f° · 蜿蜒度(均值) %.2f%n",
                angSum / Math.max(1, steps), sinuSum / Math.max(1, sinuN));
        worst.forEach(System.out::println);
    }

    private static double heightAtBlock(double bx, double bz) {
        int i = (int) Math.round(bx) - x0, j = (int) Math.round(bz) - z0;
        i = Math.max(0, Math.min(win - 1, i));
        j = Math.max(0, Math.min(win - 1, j));
        Cell c = cellArr[j * win + i];
        return c == null ? Double.NaN : c.height;
    }

    /** 局部最陡【下降】单位方向（中央差分）；数据不足返回 null。 */
    private static double[] descentDir(double bx, double bz) {
        double r = 6.0;
        double hxp = heightAtBlock(bx + r, bz), hxm = heightAtBlock(bx - r, bz);
        double hzp = heightAtBlock(bx, bz + r), hzm = heightAtBlock(bx, bz - r);
        if (Double.isNaN(hxp) || Double.isNaN(hxm) || Double.isNaN(hzp) || Double.isNaN(hzm)) return null;
        double gx = (hxp - hxm) / (2 * r);          // 上坡方向
        double gz = (hzp - hzm) / (2 * r);
        double len = Math.hypot(gx, gz);
        if (len < 1e-9) return null;                 // 平地：无最陡方向
        return new double[]{-gx / len, -gz / len};   // 最陡下降
    }

    /** 按【用户的物理定义】审计一组 region 的河网，打印判据表与前几例。 */
    private static void audit(List<RiverLineRegion> regions, RiverLineNetwork net) {
        // ---------- 判据统计 ----------
        int riverNodes = 0, nodeDry = 0, nodeUp = 0, nodeUnder = 0;
        int rivers = 0, tailDry = 0, tailInland = 0;
        int terminals = 0, inland = 0, okOcean = 0, okLake = 0, okJoin = 0, okOutlet = 0;
        int junc = 0, juncBad = 0;
        // 河面半宽统计（判据6：宽度分层）
        List<Double> widths = new ArrayList<>();
        double wMin = 0, wMed = 0, wMax = 0;
        double sea = gen.seaLevel();
        List<String> dryEx = new ArrayList<>(), inlandEx = new ArrayList<>(), upEx = new ArrayList<>();

        for (RiverLineRegion r : regions) {
            double bx0 = r.rx * 640.0, bz0 = r.rz * 640.0;
            for (int ri = 0; ri < r.rivers.size(); ri++) {
                RiverLineRegion.RiverPolyline p = r.rivers.get(ri);
                int n = p.nodes.length;
                if (n < 2) continue;
                // 只统计落在窗口内的河，避免窗口外噪声
                double mx = p.nodes[n / 2].x() * 2.0, mz = p.nodes[n / 2].z() * 2.0;
                if (mx < x0 || mz < z0 || mx > x0 + win || mz > z0 + win) continue;
                rivers++;
                for (int k = 0; k < n; k++) {
                    riverNodes++;
                    if (p.width[k] > 0) widths.add(p.width[k]);
                    int bx = (int) Math.round(p.nodes[k].x() * 2.0);
                    int bz = (int) Math.round(p.nodes[k].z() * 2.0);
                    Cell c = cellAtB(bx, bz);
                    // 判据1：河必有水
                    if (c != null && c.riverType == 0 && !c.isWater()) {
                        nodeDry++;
                        if (dryEx.size() < 5) {
                            // ★ 2026-09-21 成因裁决：规划器看到的地形（p.terrainY）vs 生产 Cell
                            //   最终地形（c.height）。若 p.terrainY ≈ 折线水面 而 c.height 高 12 块
                            //   ⇒ 水面本身对（埋在规划地形下 = 已挖开），是【生产 Cell 地形没被挖】
                            //     ⇒ 根因在雕刻层；反之则水面构造把水面拉低了。
                            // ★ 2026-09-22【命中诊断】：riverType=0 ⇒ 雕刻侧没取到命中。
                            //   直接打印该列 sampleAll 的返回，钉死"是被湖域过滤剥掉、
                            //   还是湖命中没发出、还是命中距离超裁剪"。
                            StringBuilder hb = new StringBuilder();
                            double wxD = p.nodes[k].x(), wzD = p.nodes[k].z();
                            var hitsD = net.sampleAll(wxD, wzD);
                            hb.append(" hits=").append(hitsD.size());
                            for (var hh : hitsD) {
                                hb.append(hh.isLake() ? " [湖 d=" : " [河 d=")
                                        .append(String.format("%.2f", hh.distToCenter()))
                                        .append(" s=").append(String.format("%.1f", hh.surfaceY()))
                                        .append(']');
                            }
                            dryEx.add(String.format(
                                    "       干河节点 r(%d,%d)#%d k=%d/%d block(%d,%d) 折线水面=%.1f 规划地形=%.1f "
                                            + "生产地形=%.1f 半宽=%.1f 深=%.1f riverType=%d lake=%s water=%s surfY=%.1f%s",
                                    r.rx, r.rz, ri, k, n - 1, bx, bz, p.surfaceY[k],
                                    p.terrainY != null && p.terrainY.length == n ? p.terrainY[k] : Double.NaN,
                                    c.height, p.width[k], p.depth[k], c.riverType, c.isLake, c.isWater(),
                                    c.riverSurfaceY, hb));
                        }
                    }
                    // 判据2：水面必须 > 河床（没有"水面低于河床"的倒置）
                    if (p.surfaceY[k] < p.surfaceY[k] - p.depth[k]) nodeUnder++;   // 恒 false，占位
                    // 判据3：沿程不得抬升
                    if (k > 0 && p.surfaceY[k] > p.surfaceY[k - 1] + 1e-6) {
                    nodeUp++;
                    // ★ R-C1：补打抬升样例（坐标+两节点水面，定位是②河适应湖还是尾抬升）
                    if (upEx.size() < 7) {
                        upEx.add(String.format(
                                "       水面抬升 r(%d,%d)#%d k=%d block(%d,%d) 上游=%.2f 本节点=%.2f"
                                        + " 地形Y=%.2f lakeNode=%b",
                                r.rx, r.rz, ri, k,
                                (int) Math.round(p.nodes[k].x() * 2.0),
                                (int) Math.round(p.nodes[k].z() * 2.0),
                                p.surfaceY[k - 1], p.surfaceY[k],
                                p.terrainY != null ? p.terrainY[k] : Double.NaN,
                                p.lakeNodes != null && p.lakeNodes[k] != null));
                    }
                }
                }
                // 河尾
                int tx = (int) Math.round(p.nodes[n - 1].x() * 2.0);
                int tz = (int) Math.round(p.nodes[n - 1].z() * 2.0);
                Cell tc = cellAtB(tx, tz);
                boolean wet = tc != null && (tc.riverType != 0 || tc.isWater() || tc.isLake);
                if (!wet) {
                    tailDry++;
                    // ★ R-C1：补打河尾无水样例（原表只给计数，无坐标无法定位）
                    if (dryEx.size() < 8) {
                        dryEx.add(String.format(
                                "       河尾无水 r(%d,%d)#%d k=%d/%d block(%d,%d) 折线水面=%.1f"
                                        + " 地形Y=%.1f rt=%d surf=%.2f isLake=%b",
                                r.rx, r.rz, ri, n - 1, n, tx, tz, p.surfaceY[n - 1],
                                p.terrainY != null ? p.terrainY[n - 1] : Double.NaN,
                                tc == null ? -1 : tc.riverType,
                                tc == null ? -1 : tc.riverSurfaceY,
                                tc != null && tc.isLake));
                    }
                }
                // 判据4：终点语义
                terminals++;
                boolean isOcean = tc != null && tc.isWater();
                boolean isLake = tc != null && tc.isLake;
                boolean isJoin = nearOtherRiver(regions, r, ri, p.nodes[n - 1].x(), p.nodes[n - 1].z());
                boolean isOutlet = nearOutlet(r, p.nodes[n - 1].x(), p.nodes[n - 1].z());
                boolean inside = p.nodes[n - 1].x() * 2 >= bx0 && p.nodes[n - 1].x() * 2 <= bx0 + 640
                        && p.nodes[n - 1].z() * 2 >= bz0 && p.nodes[n - 1].z() * 2 <= bz0 + 640;
                if (isOcean) okOcean++;
                else if (isLake) okLake++;
                else if (isJoin) okJoin++;
                else if (isOutlet) okOutlet++;
                // ★ 判据5：汇合守恒（本河尾半宽 vs 它河在汇入点的半宽）
                double[] jn = nearestOtherWidth(regions, r, ri, p.nodes[n - 1].x(), p.nodes[n - 1].z(),
                        p.width[n - 1]);
                if (jn != null) {
                    junc++;
                    if (jn[1] > 0 && p.width[n - 1] > jn[1] * 1.10) juncBad++;
                }
                // ★ R-C1 判据修正：内陆 = 终点四分类【全 MISS】（非海/非湖/非汇/非出口）
                //   且在区内。原写法挂在 jn==null 后 ⇒ 【终点是湖但不在汇合距离内】的
                //   尾被误计内陆（实测 8/8 样例尾格 isLake=true 却进内陆桶）。
                boolean endpointMiss = !isOcean && !isLake && !isJoin && !isOutlet;
                if (endpointMiss && inside) {
                    inland++;
                    if (inlandEx.size() < 8) {
                        // ★ R-C1：补"距最近湖"判据数据 —— 区分【尾在湖里但 isLake=false】
                        //   （判据口径漏判，河口进了湖但格标志是河）vs 真死尾。
                        double dl = Double.POSITIVE_INFINITY;
                        for (RiverLineRegion.LakeNode ln : r.lakes) {
                            dl = Math.min(dl, Math.hypot(
                                    p.nodes[n - 1].x() - ln.x, p.nodes[n - 1].z() - ln.z) * 2.0);
                        }
                        inlandEx.add(String.format(
                                "       内陆终止 r(%d,%d)#%d 节点=%d 尾block(%d,%d) 折线水面=%.1f"
                                        + " 地形=%.1f 尾格rt=%d isLake=%b 距最近湖=%.0f块",
                                r.rx, r.rz, ri, n, tx, tz, p.surfaceY[n - 1],
                                p.terrainY != null ? p.terrainY[n - 1] : Double.NaN,
                                tc == null ? -1 : tc.riverType, tc != null && tc.isLake, dl));
                    }
                }
            }
        }

        // ---------- 报告 ----------
        java.util.Collections.sort(widths);
        wMin = widths.isEmpty() ? 0 : widths.get(0);
        wMax = widths.isEmpty() ? 0 : widths.get(widths.size() - 1);
        wMed = widths.isEmpty() ? 0 : widths.get(widths.size() / 2);
        System.out.println();
        System.out.printf("%-38s %10s  %s%n", "物理判据（源自用户定义）", "实测", "判定");
        System.out.printf("%-38s %10s  %s%n", "--------------------------------------", "----------", "----");
        row("河数（窗口内）", String.valueOf(rivers), "");
        row("沿河线节点总数", String.valueOf(riverNodes), "");
        row("★ 河线无水节点（河必有水）", nodeDry + " (" + pct(nodeDry, riverNodes) + "%)",
                pass(nodeDry == 0));
        row("★ 河尾无水", tailDry + " (" + pct(tailDry, rivers) + "%)", pass(tailDry == 0));
        row("★ 水面沿程抬升节点（水向低处流）", String.valueOf(nodeUp), pass(nodeUp == 0));
        row("★ 内陆终止（堆积成湖/海）", inland + " (" + pct(inland, terminals) + "%)",
                pass(inland == 0));
        // ★ 判据5：汇合守恒 —— 用户明确要求"支流汇入后下游不得比支流细"。
        //   做法：对每条河的河尾，找最近它河节点；若在汇入距离内且【本河尾半宽 > 下游半宽】⇒ 违反。
        row("★ 汇合处下游更细（汇合守恒）", juncBad + " / " + junc + " (" + pct(juncBad, junc) + "%)",
                pass(juncBad == 0));
        // ★ 判据6：河宽分层（水流的必然结果：汇流只增 ⇒ 沿程宽度必须递增）
        //   span = 窗口内河面格半宽的最大/最小比。比值 ≪ 10 ⇒ "等宽管子"，不符合汇流物理。
        row("  河面半宽 最小/中位/最大", String.format("%.1f / %.1f / %.1f 块  (max÷min=%.1f×)",
                        wMin, wMed, wMax, wMin > 1e-6 ? wMax / wMin : 0.0), "");
        row("  终点-入海", String.valueOf(okOcean), "");
        row("  终点-入湖", String.valueOf(okLake), "");
        row("  终点-汇入它河", String.valueOf(okJoin), "");
        row("  终点-跨区出口", String.valueOf(okOutlet), "");
        System.out.println();
        if (!dryEx.isEmpty()) {
            System.out.println("干河节点前几例：");
            dryEx.forEach(System.out::println);
            System.out.println();
        }
        if (!inlandEx.isEmpty()) {
            System.out.println("内陆终止前几例：");
            inlandEx.forEach(System.out::println);
            System.out.println();
        }
        if (!upEx.isEmpty()) {
            System.out.println("水面抬升前几例：");
            upEx.forEach(System.out::println);
            System.out.println();
        }
        routeFidelity(regions);
    }

    private static Cell cellAtB(int bx, int bz) {
        int i = bx - x0, j = bz - z0;
        if (i < 0 || j < 0 || i >= win || j >= win) {
            // ★ F1i：窗外格不再假 null（原判据把"跨窗河尾"一律计成河尾无水，12 例中
            //   至少 8 例实为窗口边缘尾 rt=-1）—— 改走生产取路给真实湿润状态。
            GeoGenesisTerrain g = staticGt;
            if (g == null) return null;
            Cell[] cs = g.getChunkCells(bx >> 4, bz >> 4);
            return cs[Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
        }
        return cellArr[j * win + i];
    }

    /** 生产地形（窗外取格回退用；在 runFlowSim 段随 gt 赋值）。 */
    private static volatile GeoGenesisTerrain staticGt;

    private static Cell cellAt(GeoGenesisTerrain gt, Map<Long, Cell[]> cache, int bx, int bz,
                               Runnable onFail) {
        int cx = bx >> 4, cz = bz >> 4;
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Cell[] cs;
        if (cache.containsKey(key)) {
            cs = cache.get(key);
        } else {
            try {
                cs = gt.getChunkCells(cx, cz);
            } catch (RuntimeException ignore) {
                cs = null;
                onFail.run();
            }
            cache.put(key, cs);
        }
        return cs == null ? null : cs[Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
    }

    private static boolean nearOtherRiver(List<RiverLineRegion> regions, RiverLineRegion own,
                                          int ownIdx, double wx, double wz) {
        for (RiverLineRegion r : regions) {
            for (int i = 0; i < r.rivers.size(); i++) {
                if (r == own && i == ownIdx) continue;
                RiverLineRegion.RiverPolyline q = r.rivers.get(i);
                for (int k = 0; k < q.nodes.length; k++) {
                    if (Math.hypot(q.nodes[k].x() - wx, q.nodes[k].z() - wz) * 2.0 <= 16.0) return true;
                }
            }
        }
        return false;
    }

    /**
     * 河尾处的【汇合】判定 + 下游半宽：返回 {@code {距它河距离(block), 它河在该点半宽}}；无它河返回 null。
     *
     * <p>汇入判据（block）：{@code 距离 ≤ 16} —— 与雕刻侧 {@code nearestRiverDist ≤
     * nearestRiverWidth} 同量级；末端节点间距 4wu=8 block ⇒ 16 block 内即视为已相接。</p>
     */
    private static double[] nearestOtherWidth(List<RiverLineRegion> regions, RiverLineRegion own,
                                              int ownIdx, double wx, double wz, double ownW) {
        double best = Double.MAX_VALUE, bestW = -1;
        for (RiverLineRegion r : regions) {
            for (int i = 0; i < r.rivers.size(); i++) {
                if (r == own && i == ownIdx) continue;
                RiverLineRegion.RiverPolyline q = r.rivers.get(i);
                for (int k = 0; k < q.nodes.length; k++) {
                    double d = Math.hypot(q.nodes[k].x() - wx, q.nodes[k].z() - wz) * 2.0;
                    if (d < best) { best = d; bestW = q.width[k]; }
                }
            }
        }
        return best * 2.0 > 16.0 ? null : new double[]{best, bestW};
    }

    private static boolean nearOutlet(RiverLineRegion r, double wx, double wz) {
        for (RiverLineRegion.OutletSeed s : r.outlets) {
            if (Math.hypot(s.wx - wx, s.wz - wz) * 2.0 <= 48.0) return true;
        }
        return false;
    }

    private static String pct(int a, int b) {
        return b == 0 ? "0.0" : String.format("%.1f", 100.0 * a / b);
    }

    private static String pass(boolean ok) {
        return ok ? "PASS" : "FAIL";
    }

    private static void row(String name, String val, String verdict) {
        System.out.printf("%-38s %10s  %s%n", name, val, verdict);
    }
}
