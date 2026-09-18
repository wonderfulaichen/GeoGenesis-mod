package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

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
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -426;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -347;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 128;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);

        int w = 2 * radius + 1;
        double[][] h = new double[w][w];
        boolean[][] lake = new boolean[w][w];
        boolean[][] ocean = new boolean[w][w];
        // ★ 流量图 = 液滴物理侵蚀的汇流累积（discharge 场）—— 【物理机制跑出来的结果】，
        //   作为"哪里该有水"的参照物（ground truth）。预览图层 RIVER_NETWORK 即此场。
        double[][] flow = new double[w][w];

        int nLake = 0, nOcean = 0;
        double flowMax = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int x = bx - radius + i, z = bz - radius + j;
                Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
                // X 主序（与 generateChunk / 预览层一致）：cells[lx*16 + lz]
                Cell c = cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                h[j][i] = c.height;
                // ★ 2026-09-19 口径修正：与预览 RIVER_TYPE 层同口径（c.riverType != 0），
                //   而不是 c.isLake —— 两者不同（riverType 含河列 + spill<seaLevel 的湖列）。
                //   此前用 isLake ⇒ 与用户看到的预览形态对不上 ⇒ 定位错了位置。
                lake[j][i] = c.riverType != 0;
                ocean[j][i] = c.isWater();
                flow[j][i] = c.riverNetDischarge;
                if (c.riverNetDischarge > flowMax) flowMax = c.riverNetDischarge;
                if (c.riverType != 0) nLake++;
                if (c.isWater()) nOcean++;
            }
        }

        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double[] row : h) {
            for (double v : row) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        }

        try {
            File dir = new File("build/waterview");
            dir.mkdirs();

            ImageIO.write(shade(h, mn, mx), "png", new File(dir, "hillshade.png"));

            BufferedImage wv = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            double[][] hs = shadeGray(h, mn, mx);
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    int g = (int) Math.max(0, Math.min(255, hs[y][x]));
                    int rgb = (g << 16) | (g << 8) | g;
                    if (ocean[y][x])      rgb = 0x1040A0;   // 海洋：深蓝
                    else if (lake[y][x])  rgb = 0x1E78D2;   // 湖泊：亮蓝
                    wv.setRGB(x, y, rgb);
                }
            }
            ImageIO.write(wv, "png", new File(dir, "water_view.png"));

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
                    if (ocean[y][x])     rgb = 0x1040A0;
                    else if (lake[y][x]) rgb = 0x1E78D2;
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
                RiverLineNetwork net = new RiverLineNetwork(gen::terrainEQuick, gen.heightCurve(), seed);
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
                RiverLineNetwork net = new RiverLineNetwork(gen::terrainEQuick, gen.heightCurve(), seed);
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
