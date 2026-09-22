package com.geogenesis.worldgen.hydrology;

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
import java.util.List;

/**
 * 水景出图探针（★ 2026-09-19）—— 山体阴影 + 水体染色，用于【人眼目检】湖岸/河岸形态。
 *
 * <h2>为什么补这个</h2>
 * <p>用户按 F3 给出坐标后要求『跑图片』。但全仓 10 个出 PNG 的探针里，
 * <b>没有任何一个画蓝色水体</b>（{@code CanyonProfileProbe} 的 water.png 是灰度的）
 * ⇒ 重构期间那张『山体阴影 + 蓝色水体』的出图程序已不存在。
 * 本探针把它补回来，并保持<b>只用生产路径</b>（{@code getChunkCells}）。</p>
 *
 * <h2>口径（必须与游戏一致）</h2>
 * <ul>
 *   <li>高度：{@code cell.height}（生产路径，含侵蚀 + 水文雕刻回写）；</li>
 *   <li>水体：{@code cell.isLake}（湖泊，水文雕刻写入）与 {@code cell.isWater()}（海洋，e<0）；</li>
 *   <li>像素 ↔ 块：<b>1 像素 = 1 块</b>。</li>
 * </ul>
 *
 * <h2>输出</h2>
 * <pre>
 *   build/waterview/hillshade.png   纯地形山体阴影（NW 光）
 *   build/waterview/water_view.png  山体阴影 + 湖泊蓝 + 海洋深蓝   ← 目检用
 * </pre>
 *
 * <pre>{@code gradlew runWaterViewProbe [-PprobeArgs="seed blockX blockZ radiusBlocks"]}</pre>
 */
public final class WaterViewProbe {

    private WaterViewProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        // ★★★ 2026-09-22【窗口 = 用户认可的锁定框架】★★★
        //   用户判据："你干嘛放大？明明之前就挺好的，局部是看不到问题的。"
        //   ⇒ 默认窗口 = **块(-840,-363) 起算、1400 块见方**（与 HydroPhysicsAudit
        //     的 COMPARE_* 完全一致）。中心 = (-840+700, -363+700) = (-140, 337)。
        //   ★ 2026-09-22【窗口上移】（用户判据："图的渲染位置应该向上挪，因为目前看到
        //     顶部河流与湖泊比较密集，可能更容易找问题"）：中心 z 由 337 → **137**
        //     （上移 200 块），使窗口北缘覆盖原来"顶部那一片密集河湖"更完整。
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 700;

        // ★ 2026-09-20：可选第 6 参 = 采样密度缩放（gridCell × scale；1.0 = 生产默认）。
        //   用于肉眼对比"格距更细 ⇒ 河线是否更自然"（0.5 ⇒ 24 block/格）。
        //   ★ 2026-09-23：容错 —— 非数字（如 "fastlake"）直接忽略，不再抛 NumberFormatException。
        if (args.length > 5) {
            try {
                RiverLineParams.gridCellScale = Double.parseDouble(args[5]);
                System.out.printf("  [采样密度] gridCell × %.2f（1.0 = 生产默认）%n",
                        RiverLineParams.gridCellScale);
            } catch (NumberFormatException ignore) {
                // 非数字（例如 fastlake 标志）⇒ 保持默认采样密度
            }
        }

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);

        int w = 2 * radius + 1;
        double[][] h = new double[w][w];
        // ★★★ 2026-09-22【双底图】（用户裁定三联图语义）★★★
        //   hPre  = 雕刻前地形（sampleWu = 侵蚀后、河雕刻前）—— 湖泊生成时看到的输入
        //   h(现) = 最终地形（cell.height = 侵蚀 + 河雕刻后）—— 河流雕刻的产物
        //   左图(湖)用 hPre、中图(河)用 h ⇒ 可直接观察"管线顺序"有没有问题。
        double[][] hPre = new double[w][w];
        boolean[][] lake = new boolean[w][w];
        boolean[][] ocean = new boolean[w][w];
        // ★ 2026-09-22【河/湖分色】（用户要求"渲染图要区分河流与湖泊，方便调试"）
        boolean[][] riverOnly = new boolean[w][w];
        boolean[][] lakeOnly = new boolean[w][w];
        boolean[][] prodLake = new boolean[w][w];

        // ★ 流量图 = 液滴物理侵蚀的汇流累积（discharge 场）—— 【物理机制跑出来的结果】，
        //   作为"哪里该有水"的参照物（ground truth）。预览图层 RIVER_NETWORK 即此场。
        double[][] flow = new double[w][w];

        // ★ 2026-09-22【填色诊断】—— 用户判据："填色绝对有 bug"。
        //   直接量：湖域掩码标了多少块 / 有多少水列落在掩码内 / 湖节点清单。
        int maskN = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) if (prodLake[j][i]) maskN++;
        }
        System.out.printf("[湖域掩码] 标记 %d 块（窗口 %d 块的 %.2f%%）· 湖节点样本：%n",
                maskN, w * w, 100.0 * maskN / (w * w));
        {
            RiverLineParams rlpD = RiverLineParams.defaults();
            double hsD = tp.horizontalScale();
            RiverLineNetwork netD = gt.hydrologyNetwork() /* ★ 游戏同实例（勿自建：自建缺 precip/delta 等接线 ⇒ 与雕刻不同源） */;
            netD.setSeed(seed);
            List<String> dbg = new java.util.ArrayList<>();
            int nLakesTotal = 0, nRiversTotal = 0, nLakeReachPolys = 0;
            for (int rgx = (int) Math.floor((bx - radius) / hsD / rlpD.regionSize()) - 1;
                    rgx <= (int) Math.floor((bx + radius) / hsD / rlpD.regionSize()) + 1; rgx++) {
                for (int rgz = (int) Math.floor((bz - radius) / hsD / rlpD.regionSize()) - 1;
                        rgz <= (int) Math.floor((bz + radius) / hsD / rlpD.regionSize()) + 1; rgz++) {
                    RiverLineRegion rrD = netD.region(rgx, rgz);
                    nLakesTotal += rrD.lakes.size();
                    nRiversTotal += rrD.rivers.size();
                    for (RiverLineRegion.LakeNode ln : rrD.lakes) {
                        if (dbg.size() < 8) {
                            dbg.add(String.format("    湖 block(%d,%d) r=%.0f outline=%b cells=%d spill=%.1f",
                                    (int) Math.round(ln.x * hsD), (int) Math.round(ln.z * hsD),
                                    ln.radius * hsD, ln.hasOutline(),
                                    ln.hasOutline() ? ln.cellX.length : 0, ln.height));
                        }
                    }
                    for (RiverLineRegion.RiverPolyline pl : rrD.rivers) {
                        if (pl.lakeLevel != null) {
                            boolean any = false;
                            for (double v : pl.lakeLevel) if (!Double.isNaN(v)) { any = true; break; }
                            if (any) nLakeReachPolys++;
                        }
                    }
                }
            }
            System.out.printf("    区域统计：lakes=%d · rivers=%d · 带湖段(lakeLevel)的折线=%d%n",
                    nLakesTotal, nRiversTotal, nLakeReachPolys);
            dbg.forEach(System.out::println);
        }

        // ★★★ 2026-09-22【分色口径改成"直接问生产网络"】—— 修"填色绝对有 bug" ★★★
        //   ⚠ 自建掩码（遍历 region.lakes 轮廓 / lakeLevel）与雕刻结果【不是同一套】：
        //     实测 lakes=5（轮廓仅 2~23 格）却有 376 条折线 ⇒ 大片真实湖泊根本不在掩码里
        //     ⇒ 被渲染成河色 ⇒ 图上"湖泊是青色的、河是蓝色"完全错乱。
        //   正解：用【雕刻侧选分支时的同一判据】逐列问网络 ——
        //     `net.sample(wu).isLake()`（HydrologyBlockCarver 正是按 samples.get(0).isLake()
        //     决定走湖分支还是河分支）⇒ 渲染与实际雕刻必然一致。
        RiverLineParams rlpS = RiverLineParams.defaults();
        double hsS = tp.horizontalScale();
        RiverLineNetwork netS = gt.hydrologyNetwork() /* ★ 游戏同实例（勿自建：自建缺 precip/delta 等接线 ⇒ 与雕刻不同源） */;
        netS.setSeed(seed);

        // ===== ★★★ 2026-09-22【三联域图：河域 / 湖域 / 重叠】★★★ =====
        //   用户判据："你这河流填色，湖泊填色还是不对。你这样我都不知道河流、湖泊的
        //   各自区域是哪些。或者你渲染3张拼接图，第一个渲染河流，第二个渲染湖泊，
        //   第三个二者重叠。"
        //   ⇒ 三个面板各自独立，【域】= 命中影响范围（不是雕刻结果）：
        //     ① riverDom = 该列存在【河】命中（河线影响域）
        //     ② lakeDom  = 该列存在【湖】命中（湖域）
        //     ③ overlap  = 两者同时存在 ⇒ 这正是"河湖冲突带"，是我们要修的地方
        //   ⚠ 与"雕刻分支"（按最近命中二选一）区分开：域描述的是【影响范围】，
        //     分支描述的是【谁胜出】——两者都要能看，所以分开画。
        boolean[][] riverDom = new boolean[w][w];
        boolean[][] lakeDom = new boolean[w][w];
        boolean[][] overlap = new boolean[w][w];
        boolean[][] riverDomNear = new boolean[w][w];   // 最近命中是河（雕刻实际走河分支）
        boolean[][] lakeDomNear = new boolean[w][w];    // 最近命中是湖（雕刻实际走湖分支）

        int nLake = 0, nOcean = 0;
        // ★★★ 2026-09-23【快速模式：只出左图（湖）】★★★
        //   用户要求："我们现在是在修复湖泊，其实你可以渲染左边图，中间和右边可以先不渲染节省时间"。
        //   【省在哪】最贵的一步是【每列调用 net.sampleAll】（1.4M 列 × 3×3 region × 段）——
        //   而左图只需要"实际有水的湖列"，干列（陆地，占 ~91.5%）根本不需要命中查询。
        //   ⇒ 快速模式下：只为 `riverType != 0` 的列调 sampleAll；跳过中/右面板、
        //     骨架图、流量图，并把左图直接写成 panels_3.png（路径不变，便于对比）。
        final boolean fastLakeOnly = java.util.Arrays.asList(args).contains("fastlake");
        // ★★★ 2026-09-22【湖内"该有水却无水"的成因分解】（用户判据：湖被截短）★★★
        //   定义：c.riverType==0（干） ∧ 非海 ∧ 存在湖命中 ∧ c.height < 湖命中水位−0.5
        //   ⇒ 物理上该是湖面，却没水。三条成因：
        //     ① 无任何命中       ⇒ 湖域没铺到（域/影响半径问题）
        //     ② 有湖命中却判干   ⇒ 判水层（inFlood 连通 / 落块闸门）
        int holeNoHit = 0, holeJudged = 0;
        double flowMax = 0;
        int nRiverWet = 0, nLakeWet = 0, nOverlap = 0, nMskWet = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int x = bx - radius + i, z = bz - radius + j;
                Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
                // X 主序（与 generateChunk / 预览层一致）：cells[lx*16 + lz]
                Cell c = cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                h[j][i] = c.height;
                // 雕刻前地形（左图底）：sampleWu = 侵蚀后、河雕刻前（= 湖泊生成的输入）
                //   tile 已由上面 getChunkCells 触发生成 ⇒ 此处命中缓存，成本可控。
                hPre[j][i] = gen.sampleWu(x / tp.horizontalScale(), z / tp.horizontalScale()).height;
                lake[j][i] = c.riverType != 0;   // 兼容旧字段（预览口径）
                ocean[j][i] = c.isWater();
                flow[j][i] = c.riverNetDischarge;
                if (c.riverNetDischarge > flowMax) flowMax = c.riverNetDischarge;
                if (c.riverType != 0) nLake++;
                if (c.isWater()) nOcean++;

                // —— 域判定（全部命中）与分支判定（最近命中）——
                // ★ 快速模式：干列（非水、非海）无需命中查询 ⇒ 直接跳过（省掉 ~91% 的 sampleAll）。
                List<RiverLineNetwork.RiverLineHit> hs2;
                if (fastLakeOnly && c.riverType == 0) {
                    hs2 = List.of();
                } else {
                    hs2 = netS.sampleAll(x / hsS, z / hsS);
                }
                boolean anyRv = false, anyLk = false;
                for (RiverLineNetwork.RiverLineHit hh : hs2) {
                    if (hh.isLake()) anyLk = true; else anyRv = true;
                }
                // ★★★ 2026-09-22【域 = 实际水列，不是影响半径】★★★
                //   ⚠ 上一版把"命中影响范围"（valleyReach，可达数十块）整片涂色 ⇒
                //     左面板把整条河谷都涂成青色（用户："我都不知道河流、湖泊的各自区域
                //     是哪些"）。河谷影响域 ≠ 水体。
                //   修法：三面板只标注【实际有水】的列（{@code riverType != 0}，排除海洋），
                //     再按【雕刻实际走的分支】拆分：
                //       · 只有河命中 ⇒ ① 河
                //       · 只有湖命中 ⇒ ② 湖
                //       · 河湖都有命中 ⇒ ③ 重叠（= 冲突带，重点修复区）
                boolean wet = c.riverType != 0 && !c.isWater();
                if (wet && !hs2.isEmpty()) {
                    // 面板①②=雕刻【实际分支】（最近命中胜出，与 carver 同源）
                    boolean branchLk = hs2.get(0).isLake();
                    if (branchLk) { lakeDom[j][i] = true; nLakeWet++; }
                    else          { riverDom[j][i] = true; nRiverWet++; }
                }
                // ★★★ 2026-09-22【面板② = 游戏里实际的湖水】—— 修"游戏湖正常、图却不同"★★★
                //   用户判据："游戏实测湖泊正常了，但为什么图里面的湖泊还是这样的？"
                //   【根因】旧图把"湖的管辖域"（inDomain 轮廓，含无水的岸带/浅滩格）
                //   整片画蓝 ⇒ 图 ⊃ 游戏水面，且轮廓格方块拼合 ⇒ 形状带方块感。
                //   而用户在游戏里看到的湖 = 【湖域内实际有水的列】（落块后 height<sink 淹水）。
                //   ⇒ 中面板只画：湖分支(wet && branchLk) —— 上面 wet 分支已赋值，
                //     此处不再用"管辖域"覆盖（删掉 lkDom 段）。
                if (anyRv && anyLk) nOverlap++;
                // ★ 湖内"该有水却无水"判定（成因分解；只用上面已算好的命中，零额外采样）
                if (c.riverType == 0 && !c.isWater()) {
                    double lvl = Double.NaN;
                    for (RiverLineNetwork.RiverLineHit hh : hs2) {
                        if (hh.isLake() && (Double.isNaN(lvl) || hh.surfaceY() > lvl)) {
                            lvl = hh.surfaceY();
                        }
                    }
                    if (!Double.isNaN(lvl) && c.height < lvl - 0.5) {
                        if (hs2.isEmpty()) holeNoHit++;
                        else holeJudged++;
                    }
                }
                if (!hs2.isEmpty()) {
                    boolean nearLk = hs2.get(0).isLake();   // sampleAll 按距离升序
                    lakeDomNear[j][i] = nearLk;
                    riverDomNear[j][i] = !nearLk;
                    // 兼容旧渲染字段（water_view 用）
                    lakeOnly[j][i] = nearLk && (c.riverType != 0 || c.isWater());
                    riverOnly[j][i] = !nearLk && c.riverType != 0;
                    prodLake[j][i] = nearLk;
                    if (nearLk && c.riverType != 0) nMskWet++;
                }
            }
        }

        // ★★★ 2026-09-22【湖域完整性量测】—— 回答用户判据："有部分明显还是湖泊的部分" ★★★
        //   只统计【最近命中=湖】的列（= 湖管辖内、按设计就该是湖面），看有多少【没水】。
        //   意义：若这批列大量无水 ⇒ 湖在自己的管辖域内就是破的（截断在判水那一层，
        //   不是域没铺到）；若几乎全有水 ⇒ 用户看到的"缺"在域外（域没铺到）⇒ 下一刀改域。
        int lkDomWet = 0, lkDomDry = 0;
        int rvDomWet = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                if (prodLake[j][i]) {
                    if (lake[j][i]) lkDomWet++; else lkDomDry++;
                } else if (riverDom[j][i]) {
                    rvDomWet++;
                }
            }
        }
        int lkDomAll = lkDomWet + lkDomDry;
        System.out.printf("  [湖域完整性] 最近命中=湖的列 %d：有水 %d · 无水 %d（无水 %.1f%%）"
                        + " | 最近命中=河的有水列 %d%n",
                lkDomAll, lkDomWet, lkDomDry,
                lkDomAll == 0 ? 0.0 : 100.0 * lkDomDry / lkDomAll, rvDomWet);
        int holeAll = holeNoHit + holeJudged;
        System.out.printf("  [湖内缺格] 该有水却无水 %d 格（占比 %.2f%%）"
                        + " ⇒ 成因①无任何命中 %d · 成因②有湖命中却判干 %d%n",
                holeAll, 100.0 * holeAll / (w * (double) w), holeNoHit, holeJudged);
        System.out.printf("  [结论指引] ①占多 ⇒ 改【湖域/影响半径】；②占多 ⇒ 改【判水层 inFlood/落块闸门】%n");

        // 面板③ = 面板① ∩ 面板②（用户："右图是显示前面2张图重叠的效果"）
        int nP3 = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                if (riverDom[j][i] && lakeDom[j][i]) { overlap[j][i] = true; nP3++; }
            }
        }
        System.out.printf("[三联图统计] ① 湖 %d 列 · ② 河 %d 列 · ③ 叠加(①+②) %d 列 "
                        + "· 分支互斥残留交集 %d%n", nLakeWet, nRiverWet,
                nRiverWet + nLakeWet, nP3);

        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double[] row : h) {
            for (double v : row) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        }

        try {
            File dir = new File("build/waterview");
            dir.mkdirs();

            ImageIO.write(shade(h, mn, mx), "png", new File(dir, "hillshade.png"));

            // ===== ★★★ 2026-09-22【水体渲染重写：半透明填充 + 各自描边】★★★ =====
            //   用户判据："渲染图效果做的非常差。河流和湖泊都区分不了，没有各自的
            //   边缘显示，没有半透明色彩填充。"
            //   设计：
            //     · 底图 = 地形山体阴影（灰）—— 地形纹理仍可见；
            //     · 水体 = 【半透明】色填充（α≈0.62）叠加在山体阴影上（不是硬覆盖）；
            //     · 每种水体有【自己的描边色】（边缘 1px 高亮）：河亮青、湖亮蓝、海白蓝；
            //     · 河/湖用【不同色相】：河 = 青绿 #00C8B4、湖 = 亮蓝 #2E86FF。
            double[][] hs = shadeGray(h, mn, mx);
            BufferedImage wv = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            final double alpha = 0.62;
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    int g = (int) Math.max(0, Math.min(255, hs[y][x]));
                    int rgb = (g << 16) | (g << 8) | g;
                    // ★★★ 2026-09-22【河/湖各自描边 + 重叠区双描边】（用户三条要求）★★★
                    //   ① 河的描边与湖的描边【不同色】：河 = 亮青白 #7CFFE8、湖 = 亮蓝白 #9CC8FF；
                    //   ② 【重叠区域各自描边都画】：判"该像素是否位于【河域】边界"与
                    //      "是否位于【湖域】边界"是两件独立的事，互不遮蔽 ⇒ 交界处能同时
                    //      看到两种描边（用于判断"河湖在哪里重叠/谁盖住谁"）；
                    //   ③ 填充仍半透明（保留地形明暗）。
                    final int F_SEA = 0x0E3C8C, F_LAKE = 0x2E86FF, F_RIVER = 0x00C8B4;
                    final int E_SEA = 0xBFE0FF, E_LAKE = 0x9CC8FF, E_RIVER = 0x7CFFE8;
                    boolean isSea = ocean[y][x], isLk = lakeOnly[y][x], isRv = riverOnly[y][x];
                    if (isSea || isLk || isRv) {
                        int fc = isSea ? F_SEA : (isLk ? F_LAKE : F_RIVER);
                        int r0 = (rgb >> 16) & 0xFF, g0 = (rgb >> 8) & 0xFF, b0 = rgb & 0xFF;
                        int r1 = (fc >> 16) & 0xFF, g1 = (fc >> 8) & 0xFF, b1 = fc & 0xFF;
                        int r = (int) (r0 * (1 - alpha) + r1 * alpha);
                        int gg = (int) (g0 * (1 - alpha) + g1 * alpha);
                        int b = (int) (b0 * (1 - alpha) + b1 * alpha);
                        rgb = (r << 16) | (gg << 8) | b;
                        // —— 各域边界各自判定（互不遮蔽）——
                        boolean edgeRv = isRv && (x == 0 || y == 0 || x == w - 1 || y == w - 1
                                || !riverOnly[y][x - 1] || !riverOnly[y][x + 1]
                                || !riverOnly[y - 1][x] || !riverOnly[y + 1][x]);
                        boolean edgeLk = isLk && (x == 0 || y == 0 || x == w - 1 || y == w - 1
                                || !lakeOnly[y][x - 1] || !lakeOnly[y][x + 1]
                                || !lakeOnly[y - 1][x] || !lakeOnly[y + 1][x]);
                        boolean edgeSea = isSea && (x == 0 || y == 0 || x == w - 1 || y == w - 1
                                || !ocean[y][x - 1] || !ocean[y][x + 1]
                                || !ocean[y - 1][x] || !ocean[y + 1][x]);
                        // 河描边最优先显示（它是调试重点）；湖/海描边依次兜底。
                        if (edgeRv)      rgb = E_RIVER;
                        else if (edgeLk) rgb = E_LAKE;
                        else if (edgeSea) rgb = E_SEA;
                    }
                    wv.setRGB(x, y, rgb);
                }
            }
            ImageIO.write(wv, "png", new File(dir, "water_view.png"));

            // ===== ★★★ 2026-09-22【三联拼接图 · 按管线顺序】★★★
            //   用户裁定（本轮最终版）：
            //     ① 左 = 【湖泊】+【雕刻前地形】（sampleWu = 侵蚀后、河雕刻前）
            //        —— 湖最先生成，底图必须是它看到的输入；若底图带河雕刻痕迹 = 管线顺序 bug；
            //     ② 中 = 【河流】+【雕刻后地形】（cell.height 最终）—— 河雕刻的产物；
            //     ③ 右 = 【左+中叠加】（不变）—— 河湖衔接检查。
            //   颜色：湖亮蓝 #2E86FF/描边 #9CC8FF · 河青绿 #00C8B4/描边 #7CFFE8。
            double mnPre = Double.MAX_VALUE, mxPre = -Double.MAX_VALUE;
            for (double[] row : hPre) {
                for (double v : row) { mnPre = Math.min(mnPre, v); mxPre = Math.max(mxPre, v); }
            }
            double[][] hsPre = shadeGray(hPre, mnPre, mxPre);   // 雕刻前地形阴影（左图底）
            BufferedImage p1 = domainPanel(hsPre, lakeDom, 0x2E86FF, 0x9CC8FF, alpha);  // 湖+雕刻前
            // ★ 快速模式：只写左图（湖），跳过中/右面板、骨架图、流量图 ⇒ 显著省时。
            if (fastLakeOnly) {
                ImageIO.write(p1, "png", new File(dir, "panels_3.png"));
                ImageIO.write(p1, "png", new File(dir, "lake_only.png"));
                System.out.println("  panels_3.png  ★ 快速模式：仅左图（湖 + 雕刻前地形）");
                return;      // main 方法收尾（后续中/右面板与其余图全部跳过）
            }
            BufferedImage p2 = domainPanel(hs, riverDom, 0x00C8B4, 0x7CFFE8, alpha);    // 河+最终
            BufferedImage p3 = mergePanel(hs,                       // 叠加（最终地形底，不变）
                    riverDom, 0x00C8B4, 0x7CFFE8,
                    lakeDom, 0x2E86FF, 0x9CC8FF, alpha);
            BufferedImage tri = new BufferedImage(w * 3 + 8, w, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2 = tri.createGraphics();
            g2.setColor(new java.awt.Color(0x22, 0x22, 0x22));
            g2.fillRect(0, 0, w * 3 + 8, w);
            g2.drawImage(p1, 0, 0, null);
            g2.drawImage(p2, w + 4, 0, null);
            g2.drawImage(p3, w * 2 + 8, 0, null);
            g2.dispose();
            ImageIO.write(tri, "png", new File(dir, "panels_3.png"));
            System.out.println("  panels_3.png  ★ 三联：左=湖+雕刻前地形 · 中=河+雕刻后地形 · 右=叠加");

            // ★★★ 2026-09-22【骨架折线叠加图】（用户要求："应该直接显示骨架河流"）★★★
            //   在【地形山体阴影】底图上，直接画生产河网的【折线骨架】（node 连线）
            //   + 湖域轮廓 —— 完全绕开雕刻结果，用于验证"骨架本身长什么样"、
            //   "骨架与湖是否接上"。这条链路用生产网络（与雕刻同源）。
            BufferedImage sk = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    int g = (int) Math.max(0, Math.min(255, hs[y][x]));
                    sk.setRGB(x, y, (g << 16) | (g << 8) | g);
                }
            }
            {
                RiverLineParams rlp = RiverLineParams.defaults();
                double hsG = tp.horizontalScale();
                RiverLineNetwork net = gt.hydrologyNetwork() /* ★ 游戏同实例（勿自建：自建缺 precip/delta 等接线 ⇒ 与雕刻不同源） */;
                net.setSeed(seed);
                int rgx0 = (int) Math.floor((bx - radius) / hsG / rlp.regionSize()) - 1;
                int rgz0 = (int) Math.floor((bz - radius) / hsG / rlp.regionSize()) - 1;
                int rgx1 = (int) Math.floor((bx + radius) / hsG / rlp.regionSize()) + 1;
                int rgz1 = (int) Math.floor((bz + radius) / hsG / rlp.regionSize()) + 1;
                int drawn = 0, lakePts = 0;
                for (int rgx = rgx0; rgx <= rgx1; rgx++) {
                    for (int rgz = rgz0; rgz <= rgz1; rgz++) {
                        RiverLineRegion rr = net.region(rgx, rgz);
                        for (RiverLineRegion.RiverPolyline pl : rr.rivers) {
                            for (int k = 0; k + 1 < pl.nodes.length; k++) {
                                // 湖段用蓝色、河段用青色，便于直观看"骨架在哪止步"
                                boolean isLk = pl.lakeLevel != null
                                        && (!Double.isNaN(pl.lakeLevel[k]) || !Double.isNaN(pl.lakeLevel[k + 1]));
                                int col = isLk ? 0x1E78D2 : 0x00B4A0;
                                drawn += drawSeg(sk, pl.nodes[k].x() * hsG, pl.nodes[k].z() * hsG,
                                        pl.nodes[k + 1].x() * hsG, pl.nodes[k + 1].z() * hsG,
                                        bx, bz, radius, col);
                                if (isLk) lakePts++;
                            }
                        }
                        // 湖域轮廓（逐格）
                        for (RiverLineRegion.LakeNode ln : rr.lakes) {
                            if (ln.hasOutline()) {
                                for (int ci = 0; ci < ln.cellX.length; ci++) {
                                    int px = (int) Math.round(ln.cellX[ci] * hsG) - (bx - radius);
                                    int pz = (int) Math.round(ln.cellZ[ci] * hsG) - (bz - radius);
                                    if (px >= 0 && pz >= 0 && px < w && pz < w) sk.setRGB(px, pz, 0x0A3C7A);
                                }
                            }
                        }
                    }
                }
                System.out.printf("  skeleton.png  骨架折线叠加（河段青 #00B4A0 / 湖段蓝 #1E78D2 / "
                        + "湖域暗蓝 #0A3C7A）· 画线 %d 段（其中湖段 %d）%n", drawn, lakePts);
            }
            ImageIO.write(sk, "png", new File(dir, "skeleton.png"));

            // ---- 流量图（discharge 场，对数拉伸）----
            BufferedImage fimg = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            double lmax = Math.log1p(flowMax);
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    double t = lmax <= 0 ? 0 : Math.log1p(flow[y][x]) / lmax;
                    int v = (int) Math.max(0, Math.min(255, 255 * t));
                    fimg.setRGB(x, y, (v << 16) | (v << 8) | v);
                }
            }
            ImageIO.write(fimg, "png", new File(dir, "flow.png"));

            // ---- 叠加图：地形阴影(灰) + 流量(暖色) + 湖泊(蓝)，用于【对照】----
            BufferedImage ov = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    int g = (int) Math.max(0, Math.min(255, hs[y][x]));
                    int rgb = (g << 16) | (g << 8) | g;
                    double t = lmax <= 0 ? 0 : Math.log1p(flow[y][x]) / lmax;
                    if (t > 0.35) {                       // 流量明显处：暖色叠加
                        int r = (int) (255 * Math.min(1, t));
                        rgb = (r << 16) | ((int) (110 * t) << 8) | 0;
                    }
                    // ★ 与 water_view.png 同口径：半透明 + 河/湖/海分色（用户判据）
                    int water = ocean[y][x] ? 1 : (lakeOnly[y][x] ? 2 : (riverOnly[y][x] ? 3 : 0));
                    if (water != 0) {
                        int fc = water == 1 ? 0x0E3C8C : (water == 2 ? 0x2E86FF : 0x00C8B4);
                        int r0 = (rgb >> 16) & 0xFF, g0 = (rgb >> 8) & 0xFF, b0 = rgb & 0xFF;
                        int r1 = (fc >> 16) & 0xFF, g1 = (fc >> 8) & 0xFF, b1 = fc & 0xFF;
                        int r = (int) (r0 * (1 - alpha) + r1 * alpha);
                        int gg = (int) (g0 * (1 - alpha) + g1 * alpha);
                        int b = (int) (b0 * (1 - alpha) + b1 * alpha);
                        rgb = (r << 16) | (gg << 8) | b;
                    }
                    ov.setRGB(x, y, rgb);
                }
            }
            ImageIO.write(ov, "png", new File(dir, "overlay.png"));

            System.out.printf("=== WaterViewProbe seed=%d ===%n", seed);
            System.out.printf("block=(%d,%d) radius=%d  窗口=%d×%d 块（1 像素 = 1 块）%n",
                    bx, bz, radius, w, w);
            System.out.printf("height 范围 = [%.1f, %.1f]%n", mn, mx);
            System.out.printf("湖泊块 = %d (%.2f%%)   海洋块 = %d (%.2f%%)%n",
                    nLake, 100.0 * nLake / (w * (double) w),
                    nOcean, 100.0 * nOcean / (w * (double) w));
            System.out.printf("流量图 discharge max = %.4f（0 表示该格无汇流）%n", flowMax);
            // ★ 2026-09-19：直边度量（客观判据，取代"看图判断"）
            //   用户红圈标注的是【水平长直线】的水界 —— 那就是轴对齐粗格/方块并集的签名。
            //   本度量统计"水体边缘沿 X（水平）/沿 Z（竖直）连续 ≥ MIN 块"的段数。
            reportStraightEdges("湖泊", lake, w);
            reportStraightEdges("海洋", ocean, w);
            boolean[][] all = new boolean[w][w];
            for (int j = 0; j < w; j++) {
                for (int i = 0; i < w; i++) all[j][i] = lake[j][i] || ocean[j][i];
            }
            reportStraightEdges("水体合计", all, w);
            System.out.println("  ★ 上边界剖面（最能对应『上边界是水平直线』）：");
            reportTopEdgeProfile("湖泊", lake, w, 8);
            reportTopEdgeProfile("水体合计", all, w, 8);
            printAscii("湖泊掩膜 isLake", lake, w, Math.max(1, w / 64));
            // ★★★ 2026-09-19 绝对等高线判据（用户指正）：湖面是【绝对高度】，
            //   凡 height < 湖面 的格就该有水。低于水面却干 = 水在【没到岸】的地方被切断。
            //   —— 这是直边问题的正解判据（不是"岸线是等高线所以平"，那是错的方向）。
            reportDryBelowLevel(gt, bx - radius, bz - radius, w);
            // ★ 沿【切断线】做横剖面：读该行每一格的 height / riverSurfaceY / riverType，
            //   直接看水是在"height 越过 surf"处停（自然岸），还是在"height 仍 < surf"处停（被门控切）。
            int rz2 = args.length > 4 ? Integer.parseInt(args[4]) : Integer.MIN_VALUE;
            // ★★★ 2026-09-19 门控链诊断（用户指正：水没到岸就停）：
            //   对"该有水却干"的格，逐层打印是哪一道门把它拒了。
            //   这是唯一能定死"直线是谁切的"的办法。
            int dx1 = args.length > 6 ? Integer.parseInt(args[6]) : Integer.MIN_VALUE;
            int dz1 = args.length > 7 ? Integer.parseInt(args[7]) : Integer.MIN_VALUE;
            if (dx1 != Integer.MIN_VALUE) {
                RiverLineNetwork net = gt.hydrologyNetwork() /* ★ 游戏同实例（勿自建：自建缺 precip/delta 等接线 ⇒ 与雕刻不同源） */;
                RiverLineParams rlp = RiverLineParams.defaults();
                net.region((int) Math.floor(dx1 / rlp.regionSize()),
                        (int) Math.floor((double) dz1 / rlp.regionSize()));
                double hsG = tp.horizontalScale();
                Cell cg = gt.getChunkCells(dx1 >> 4, dz1 >> 4)[Math.floorMod(dx1, 16) * 16 + Math.floorMod(dz1, 16)];
                System.out.printf("%n  ── ★ 门控链诊断 块(%d,%d)：h=%.3f surf=%.3f riverType=%d%n",
                        dx1, dz1, cg.height, cg.riverSurfaceY, cg.riverType);
                System.out.printf("     该有水? %b（h < surf-0.5）%n",
                        cg.riverSurfaceY > 0 && cg.height < cg.riverSurfaceY - 0.5);
                RiverLineNetwork.RiverLineHit h2 = net.sample(dx1 / hsG, dz1 / hsG);
                if (h2 == null) {
                    System.out.println("     net.sample = null（无任何河线/湖命中）");
                } else {
                    System.out.printf("     hit: isLake=%b dist=%.3f surf=%.3f w=%.2f%n",
                            h2.isLake(), h2.distToCenter(), h2.surfaceY(), h2.width());
                    var ln2 = h2.lake();
                    if (ln2 == null) {
                        System.out.println("     lake = null ⇒ 不是湖命中（可能纯河）");
                    } else {
                        double tol = rlp.gridCell() * 2.0;
                        System.out.printf("     lake: 中心(%.1f,%.1f) 面=%.3f 有rim=%b floodLevel=%.3f%n",
                                ln2.x, ln2.z, ln2.height, ln2.hasRim(), ln2.floodLevel());
                        System.out.printf("       inDomain(±%.0fwu)=%b   inFlood=%b%n",
                                tol, ln2.inDomain(dx1 / hsG, dz1 / hsG, tol),
                                ln2.inFlood(dx1 / hsG, dz1 / hsG));
                    }
                }
            }
            if (rz2 != Integer.MIN_VALUE) {
                System.out.printf("  ── 横剖面 z=%d（height / surf / riverType）：%n", rz2);
                for (int i = 0; i < w; i++) {
                    int x = bx + i;
                    Cell c = gt.getChunkCells(x >> 4, rz2 >> 4)[Math.floorMod(x, 16) * 16 + Math.floorMod(rz2, 16)];
                    char mark = c.riverType != 0 ? '#' : (c.height < c.riverSurfaceY - 0.5 ? '!' : '.');
                    System.out.printf("    x=%-6d h=%8.3f surf=%8.3f rt=%d %c%n",
                            x, c.height, c.riverSurfaceY, c.riverType, mark);
                }
            }
            // ★ 带世界坐标的水体逐行范围 —— 用于【精确读数】（ASCII 只能看形，读不出坐标）
            // ★ 逐块宽度跳变检测 —— 直接定位"水体宽度在 1 行内突变"的位置（= 直边/截断）
            reportWidthJumps(lake, w, bx - radius, bz - radius);
            // ★ 列剖面：看跳变列的内部量（height vs riverSurfaceY）—— 判别"等高线"还是"门控"
            int px = args.length > 4 ? Integer.parseInt(args[4]) : Integer.MIN_VALUE;
            if (px != Integer.MIN_VALUE) {
                System.out.printf("  ── 列剖面 x=%d（height / riverSurfaceY / riverType / isLake）：%n", px);
                for (int j = 0; j < w; j++) {
                    int z = bz - radius + j;
                    Cell c = gt.getChunkCells(px >> 4, z >> 4)[Math.floorMod(px, 16) * 16 + Math.floorMod(z, 16)];
                    System.out.printf("    z=%-5d h=%8.3f  surf=%8.3f  riverType=%d  isLake=%-5b%n",
                            z, c.height, c.riverSurfaceY, c.riverType, c.isLake);
                }
                // ★ hit 剖面：湖认领范围 = inDomain && lakeDist <= bestRiverDist
                //   ⇒ 湖/河两个距离场的【等分线】—— 疑似"直边"的真正来源。
                //   沿同一列采样 net.sample，看 isLake / dist 在哪一行翻转。
                RiverLineNetwork net = gt.hydrologyNetwork() /* ★ 游戏同实例（勿自建：自建缺 precip/delta 等接线 ⇒ 与雕刻不同源） */;
                // ⚠ 必须先定位 region —— 否则查的是 region(0,0) 的河网（实测：查错 region
                //   ⇒ 整列无湖命中，与图上蓝水矛盾）。湖在 region(floor(x/640), floor(z/640))。
                RiverLineParams rlp = RiverLineParams.defaults();
                int rgx = (int) Math.floor(px / rlp.regionSize());
                int rgz = (int) Math.floor((double) bz / rlp.regionSize());
                net.region(rgx, rgz);
                double hs2 = tp.horizontalScale();
                System.out.printf("  ── hit 剖面 x=%d（isLake / distToCenter / surfaceY / width）：%n", px);
                for (int j = 0; j < w; j++) {
                    int z = bz - radius + j;
                    RiverLineNetwork.RiverLineHit hit = net.sample(px / hs2, z / hs2);
                    if (hit == null) continue;
                    System.out.printf("    z=%-5d isLake=%-5b dist=%9.3f surf=%8.3f width=%7.2f%n",
                            z, hit.isLake(), hit.distToCenter(), hit.surfaceY(), hit.width());
                }
            }

            System.out.printf("输出目录 = %s%n", dir.getAbsolutePath());
            System.out.println("  hillshade.png  纯地形山体阴影");
            System.out.println("  water_view.png 山体阴影 + 湖泊蓝(#1E78D2) + 海洋深蓝(#1040A0)");
            System.out.println("  flow.png       ★ 流量图（discharge 场，对数拉伸）—— 物理机制参照物");
            System.out.println("  overlay.png    ★ 对照图：地形 + 流量(暖色) + 湖泊(蓝)");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * ★ 2026-09-19 <b>直边度量</b> —— 客观判据，取代"看图判断"。
     *
     * <p>用户红圈标注的水界是【水平长直线】⇒ 轴对齐粗格 / 方块并集的签名。
     * 本方法统计：水体边缘中，沿 X（水平）或沿 Z（竖直）连续 ≥ {@code MIN} 块的段数，
     * 以及最长段长度。<b>贴等高线的自然岸线不会产生长直线段。</b></p>
     *
     * <p>{@code MIN} 取 24 块（= 一个 region 的 gridCell）—— 短于此的阶梯属噪声级。</p>
     */
    private static void reportStraightEdges(String tag, boolean[][] wet, int w) {
        final int MIN = 24;
        int hRuns = 0, hMax = 0, vRuns = 0, vMax = 0;
        // 水平边缘：该格是水、其【上方】格非水 ⇒ 边缘沿 X 延伸
        for (int y = 0; y < w; y++) {
            int run = 0;
            for (int x = 0; x < w; x++) {
                boolean edge = y > 0 && wet[y][x] && !wet[y - 1][x];
                if (edge) {
                    run++;
                } else {
                    if (run >= MIN) { hRuns++; hMax = Math.max(hMax, run); }
                    run = 0;
                }
            }
            if (run >= MIN) { hRuns++; hMax = Math.max(hMax, run); }
        }
        // 竖直边缘
        for (int x = 0; x < w; x++) {
            int run = 0;
            for (int y = 0; y < w; y++) {
                boolean edge = x > 0 && wet[y][x] && !wet[y][x - 1];
                if (edge) {
                    run++;
                } else {
                    if (run >= MIN) { vRuns++; vMax = Math.max(vMax, run); }
                    run = 0;
                }
            }
            if (run >= MIN) { vRuns++; vMax = Math.max(vMax, run); }
        }
        System.out.printf("    [%s] 水平直边(≥%d块)=%d 段 最长 %d 块  |  竖直直边=%d 段 最长 %d 块%n",
                tag, MIN, hRuns, hMax, vRuns, vMax);
    }

    /**
     * ★ 2026-09-19 <b>水体【上边界】剖面</b> —— 直接对应"湖泊上边界是一条水平直线"。
     *
     * <p>对每一列 x 求"水体最上边的 y"，然后找<b>该值恒定</b>的最长连续段 ——
     * 恒定段越长，说明上边界越"平"（= 用户红圈的形态）。</p>
     *
     * <p>同时打印剖面抽样（每 {@code step} 列一个值），可<b>数值上</b>判断是否直线。</p>
     */
    private static void reportTopEdgeProfile(String tag, boolean[][] wet, int w, int step) {
        int[] top = new int[w];
        java.util.Arrays.fill(top, -1);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < w; y++) {
                if (wet[y][x]) { top[x] = y; break; }
            }
        }
        // 最长"同一 y"连续段（仅统计连续有水的列）
        int best = 0, bestAt = -1, run = 0, runY = -1;
        for (int x = 0; x < w; x++) {
            if (top[x] < 0) { run = 0; runY = -1; continue; }
            if (top[x] == runY) {
                run++;
            } else {
                run = 1;
                runY = top[x];
            }
            if (run > best) { best = run; bestAt = x; }
        }
        System.out.printf("    [%s] 上边界最长【水平段】(同一 y 连续) = %d 块（止于 x=%d）%n",
                tag, best, bestAt);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int x = 0; x < w && n < 18; x += step) {
            if (top[x] < 0) continue;
            sb.append(top[x]).append(' ');
            n++;
        }
        System.out.printf("        上边界抽样（每 %d 列一个 y）: %s%n", step, sb);
    }

    /**
     * ★ 2026-09-19 <b>水体掩膜 ASCII 降采样</b> —— 取代"看图判断"。
     *
     * <p>每 {@code step}×{@code step} 块取"任一为水"合并成一个字符 ⇒ 可在终端<b>数格子</b>，
     * 轴对齐直边会以连续同字符行/列直接显现，不再依赖人眼对 PNG 的印象。</p>
     */
    private static void printAscii(String tag, boolean[][] wet, int w, int step) {
        System.out.printf("  ── %s ASCII（%d×%d，每 %d 块 1 字符；# 水 / . 陆）%n",
                tag, w / step, w / step, step);
        for (int j = 0; j < w; j += step) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < w; i += step) {
                boolean any = false;
                for (int dj = 0; dj < step && !any; dj++) {
                    for (int di = 0; di < step && !any; di++) {
                        int y = j + dj, x = i + di;
                        if (y < w && x < w && wet[y][x]) any = true;
                    }
                }
                sb.append(any ? '#' : '.');
            }
            System.out.println("    " + sb);
        }
    }

    /**
     * ★ 2026-09-19 <b>水体逐行世界坐标范围</b> —— 精确读数，直接看边界形状与【截断位置】。
     *
     * <p>每 {@code step} 行打印该行水体的 [minX, maxX]（世界块坐标）。
     * 若某行的范围【突变为空】或跨度突变，即为硬截断/直边所在，
     * 可直接读出世界坐标去定位是哪层门控（region 边界 / inDomain / …）切的。</p>
     */
    private static void printRowRanges(boolean[][] wet, int w, int step, int originX, int originZ) {
        System.out.println("  ── 水体逐行范围（世界块坐标；空 = 该行无水）");
        for (int j = 0; j < w; j += step) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            for (int i = 0; i < w; i++) {
                boolean any = false;
                for (int dj = 0; dj < step && !any; dj++) {
                    int y = j + dj;
                    if (y < w && wet[y][i]) any = true;
                }
                if (any) {
                    minX = Math.min(minX, i);
                    maxX = Math.max(maxX, i);
                }
            }
            if (minX == Integer.MAX_VALUE) {
                System.out.printf("    z=%-6d : (无水)%n", originZ + j);
            } else {
                System.out.printf("    z=%-6d : x ∈ [%d, %d]  跨度 %d 块%n",
                        originZ + j, originX + minX, originX + maxX, maxX - minX + 1);
            }
        }
    }

    /**
     * ★ 2026-09-19 <b>逐行宽度跳变检测</b> —— 直接定位"水体宽度在 1 行内突变"的位置。
     *
     * <p>对每一行求水体的 [minX, maxX] 与宽度；凡相邻两行宽度差 &gt; 阈值者即报告。
     * <b>贴等高线的自然岸线宽度连续变化；轴对齐直边/区块截断会产生突变。</b>
     * 输出带世界块坐标 ⇒ 可直接定位是哪一层门控切的。</p>
     */
    private static void reportWidthJumps(boolean[][] wet, int w, int originX, int originZ) {
        final int TH = 20;                       // 相邻两行宽度差阈值（块）
        int[] width = new int[w];
        int[] lo = new int[w], hi = new int[w];
        for (int j = 0; j < w; j++) {
            int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
            for (int i = 0; i < w; i++) {
                if (wet[j][i]) { mn = Math.min(mn, i); mx = Math.max(mx, i); }
            }
            if (mn == Integer.MAX_VALUE) {
                width[j] = 0;
                lo[j] = 0; hi[j] = 0;
            } else {
                width[j] = mx - mn + 1;
                lo[j] = originX + mn;
                hi[j] = originX + mx;
            }
        }
        System.out.printf("  ── ★ 逐行宽度跳变（|Δ宽度| > %d 块）—— 直边/截断定位：%n", TH);
        int found = 0;
        for (int j = 1; j < w; j++) {
            int d = width[j] - width[j - 1];
            if (Math.abs(d) > TH) {
                found++;
                if (found <= 24) {
                    System.out.printf("    z=%d→%d : 宽度 %d → %d（Δ%+d）  x [%d,%d] → [%d,%d]%n",
                            originZ + j - 1, originZ + j, width[j - 1], width[j], d,
                            lo[j - 1], hi[j - 1], lo[j], hi[j]);
                }
            }
        }
        System.out.printf("    共 %d 处跳变（窗口 z ∈ [%d, %d]）%n", found, originZ, originZ + w - 1);
    }

    /**
     * ★★★ 2026-09-19 <b>绝对等高线判据</b>（用户指正）—— 直边问题的正解判据。
     *
     * <p>水面是一个<b>绝对高度</b> {@code riverSurfaceY}。凡 {@code height < riverSurfaceY − 0.5}
     * 的格就该有水。<b>低于水面却是干的 ⇒ 水在【还没到岸】的地方被切断</b> ——
     * 这正是用户圈出的"完全不是岸边、完全没到岸边"的直线。</p>
     *
     * <p>按 z 逐行统计「该有水 / 实际有水 / 漏灌」⇒ 可精确定位切断线所在的 z。</p>
     */
    private static void reportDryBelowLevel(GeoGenesisTerrain gt, int bx, int bz, int w) {
        System.out.println("  ── ★★ 绝对等高线判据：低于水面却干（逐 z 行）");
        long tBelow = 0, tWet = 0, tDry = 0;
        int shown = 0;
        for (int j = 0; j < w; j++) {
            int z = bz + j;
            int below = 0, wet = 0;
            for (int i = 0; i < w; i++) {
                int x = bx + i;
                Cell c = gt.getChunkCells(x >> 4, z >> 4)[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                double surf = c.riverSurfaceY;
                if (surf > 0 && c.height < surf - 0.5) below++;   // 该有水
                if (c.riverType != 0) wet++;                       // 实际有水
            }
            tBelow += below;
            tWet += wet;
            int dry = below - wet;
            if (dry > 0) tDry += dry;
            if (dry > 2 && shown < 26) {
                // ★ 同时打印【该有水】与【实际有水】的 x 范围 —— 两者的差集就是被切断的区域
                int bMinX = Integer.MAX_VALUE, bMaxX = Integer.MIN_VALUE;
                int wMinX = Integer.MAX_VALUE, wMaxX = Integer.MIN_VALUE;
                for (int i = 0; i < w; i++) {
                    int x = bx + i;
                    Cell c = gt.getChunkCells(x >> 4, z >> 4)
                            [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                    if (c.riverSurfaceY > 0 && c.height < c.riverSurfaceY - 0.5) {
                        bMinX = Math.min(bMinX, x); bMaxX = Math.max(bMaxX, x);
                    }
                    if (c.riverType != 0) {
                        wMinX = Math.min(wMinX, x); wMaxX = Math.max(wMaxX, x);
                    }
                }
                System.out.printf("    z=%-6d 该有水=%-5d 实际=%-5d 漏灌=%-4d "
                                + "| 该有水 x∈[%d,%d]  实际 x∈[%d,%d]%n",
                        z, below, wet, dry, bMinX, bMaxX, wMinX, wMaxX);
                shown++;
            }
        }
        System.out.printf("    合计：该有水=%d  实际有水=%d  ★漏灌=%d（%.1f%%）%n",
                tBelow, tWet, tDry, 100.0 * tDry / Math.max(1, tBelow));
        System.out.println("    判读：漏灌格若沿某几行 z 突增 ⇒ 那条 z 就是被硬切断的位置。");

        // ★★★ 2026-09-19【判据统一度量】—— 对齐 Farseek 范式前的【先量后改】
        //
        //   Farseek Streams 只有一套判据：isStreamBed = maxFloorLevel < surfaceLevel（绝对等高线）。
        //   我们有两套：
        //     · 湖分支：cell.height < spill − 0.5          （等高线）
        //     · 河分支：column.fillWater()（dist ≤ width）  （几何）
        //   ⇒ 本段量【两套判据的分歧】：有多少列"几何判湿、等高线判干"（多灌），
        //     有多少列"几何判干、等高线判湿"（漏灌）。
        //   若分歧小 ⇒ 统一判据低风险；若分歧大 ⇒ 统一会改变大量地形，须先标定。
        long geomWetContourDry = 0, geomDryContourWet = 0, agreeWet = 0, agreeDry = 0;
        long riverCols = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int x = bx + i, z = bz + j;
                Cell c = gt.getChunkCells(x >> 4, z >> 4)[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                if (c.riverSurfaceY <= 0) continue;          // 无水位 ⇒ 非水文列
                if (c.isLake) continue;                      // 只看【河分支】列（湖已用等高线）
                riverCols++;
                boolean geomWet = c.riverType != 0;
                boolean contourWet = c.height < c.riverSurfaceY - 0.5;
                if (geomWet && !contourWet) geomWetContourDry++;
                else if (!geomWet && contourWet) geomDryContourWet++;
                else if (geomWet) agreeWet++;
                else agreeDry++;
            }
        }
        System.out.printf("%n  ── ★★ 判据统一度量（河分支列 %d）%n", riverCols);
        System.out.printf("    两判据一致：湿 %d / 干 %d%n", agreeWet, agreeDry);
        System.out.printf("    几何湿·等高线干（多灌）= %d%n", geomWetContourDry);
        System.out.printf("    几何干·等高线湿（漏灌）= %d%n", geomDryContourWet);
        System.out.printf("    分歧合计 = %d（%.1f%% of 河列）%n",
                geomWetContourDry + geomDryContourWet,
                100.0 * (geomWetContourDry + geomDryContourWet) / Math.max(1, riverCols));
        System.out.println("    判读：分歧小 ⇒ 可用一套等高线判据（Farseek 范式）；");
        System.out.println("          分歧大 ⇒ 统一判据会大幅改地形，须先标定再动。");
    }

    /** 山体阴影灰度（NW 光，真实比例）。 */
    private static double[][] shadeGray(double[][] h, double mn, double mx) {
        int w = h.length;
        double[][] out = new double[w][w];
        final double lx = -0.5, ly = 0.7, lz = 0.51;
        for (int y = 1; y < w - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double dhx = (h[y][x + 1] - h[y][x - 1]) / 2.0;
                double dhz = (h[y + 1][x] - h[y - 1][x]) / 2.0;
                double nx = -dhx, ny = 1.0, nz = -dhz;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double d = (nx * lx + ny * ly + nz * lz) / Math.max(1e-9, len);
                out[y][x] = 60 + 175 * Math.max(0, d);
            }
        }
        // 边框用邻值填充，避免黑边
        for (int i = 0; i < w; i++) {
            out[0][i] = out[1][i];
            out[w - 1][i] = out[w - 2][i];
            out[i][0] = out[i][1];
            out[i][w - 1] = out[i][w - 2];
        }
        return out;
    }

    /**
     * ★ 2026-09-22【叠加面板】—— 右图：把域 A（河）与域 B（湖）画到同一张图上。
     *
     * <p>用户语义："把前面2个的效果叠加重合在一起看看有没有什么问题"。
     * 各自颜色填充、**各自描边独立判定**（交界处两边描边都出）⇒ 可直接检查河湖衔接。</p>
     */
    private static BufferedImage mergePanel(double[][] hs, boolean[][] domA, int fillA, int edgeA,
                                            boolean[][] domB, int fillB, int edgeB, double alpha) {
        int w = domA.length;
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                int g = (int) Math.max(0, Math.min(255, hs[y][x]));
                int rgb = (g << 16) | (g << 8) | g;
                boolean inA = domA[y][x], inB = domB[y][x];
                if (inA || inB) {
                    int fill = inA ? fillA : fillB;
                    int r0 = (rgb >> 16) & 0xFF, g0 = (rgb >> 8) & 0xFF, b0 = rgb & 0xFF;
                    int r1 = (fill >> 16) & 0xFF, g1 = (fill >> 8) & 0xFF, b1 = fill & 0xFF;
                    rgb = ((int) (r0 * (1 - alpha) + r1 * alpha) << 16)
                            | ((int) (g0 * (1 - alpha) + g1 * alpha) << 8)
                            | (int) (b0 * (1 - alpha) + b1 * alpha);
                    // 各域描边独立判定（交界两侧各出各的边）
                    boolean edgeAHit = inA && (x == 0 || y == 0 || x == w - 1 || y == w - 1
                            || !domA[y][x - 1] || !domA[y][x + 1]
                            || !domA[y - 1][x] || !domA[y + 1][x]);
                    boolean edgeBHit = inB && (x == 0 || y == 0 || x == w - 1 || y == w - 1
                            || !domB[y][x - 1] || !domB[y][x + 1]
                            || !domB[y - 1][x] || !domB[y + 1][x]);
                    if (edgeAHit) rgb = edgeA;
                    else if (edgeBHit) rgb = edgeB;
                }
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    /**
     * ★ 2026-09-22【域面板】—— 三联拼接图用：底图 = 山体阴影，域内 = 半透明填充 + 1px 描边。
     *
     * @param hs    山体阴影底图（0~255）
     * @param dom   该域的布尔掩码
     * @param fill  填充色（0xRRGGBB）
     * @param edge  描边色（0xRRGGBB，域的边界像素）
     * @param alpha 填充透明度（保留地形明暗）
     */
    private static BufferedImage domainPanel(double[][] hs, boolean[][] dom,
                                             int fill, int edge, double alpha) {
        int w = dom.length;
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                int g = (int) Math.max(0, Math.min(255, hs[y][x]));
                int rgb = (g << 16) | (g << 8) | g;
                if (dom[y][x]) {
                    int r0 = (rgb >> 16) & 0xFF, g0 = (rgb >> 8) & 0xFF, b0 = rgb & 0xFF;
                    int r1 = (fill >> 16) & 0xFF, g1 = (fill >> 8) & 0xFF, b1 = fill & 0xFF;
                    rgb = ((int) (r0 * (1 - alpha) + r1 * alpha) << 16)
                            | ((int) (g0 * (1 - alpha) + g1 * alpha) << 8)
                            | (int) (b0 * (1 - alpha) + b1 * alpha);
                    boolean isEdge = x == 0 || y == 0 || x == w - 1 || y == w - 1
                            || !dom[y][x - 1] || !dom[y][x + 1]
                            || !dom[y - 1][x] || !dom[y + 1][x];
                    if (isEdge) rgb = edge;
                }
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    /**
     * 在图上画一段线（Bresenham 的简化版，1 像素步进）—— 骨架折线叠加用。
     *
     * @param col 颜色（0xRRGGBB）
     * @return 实际写入的像素数
     */
    private static int drawSeg(BufferedImage img, double x0, double z0, double x1, double z1,
                               int bx, int bz, int radius, int col) {
        int w = img.getWidth();
        double dx = x1 - x0, dz = z1 - z0;
        double len = Math.hypot(dx, dz);
        int steps = Math.max(1, (int) Math.ceil(len));
        int n = 0;
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            int px = (int) Math.round(x0 + dx * t) - (bx - radius);
            int pz = (int) Math.round(z0 + dz * t) - (bz - radius);
            if (px < 0 || pz < 0 || px >= w || pz >= w) continue;
            img.setRGB(px, pz, col);
            n++;
        }
        return n;
    }

    private static BufferedImage shade(double[][] h, double mn, double mx) {
        int w = h.length;
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        double[][] g = shadeGray(h, mn, mx);
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                int v = (int) Math.max(0, Math.min(255, g[y][x]));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }
}
