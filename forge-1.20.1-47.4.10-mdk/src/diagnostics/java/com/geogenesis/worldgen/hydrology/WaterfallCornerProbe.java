package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.noise.NoiseUtil;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 瀑布直角岸坡探针（2026-08-31）。
 *
 * <p>问题：瀑布直角附近的河道两岸出现"低于河道的平台"（干草地/沙地凹台）。
 * WaterfallProbe 只断言有水侧不变量（水幕有水、不悬空、不出河道），对无水侧的
 * 岸坡几何没有任何断言，故可全绿而问题依旧。本探针专门量测<b>无水侧</b>。</p>
 *
 * <p><b>判据（核心）</b>：一列干地（未灌水）若被雕刻到<b>低于它所邻接的那一格水道
 * 水面</b>（最近河段的水面 s0.surfaceY()），则水本该漫过去却没漫 —— 这就是"低于
 * 河道的平台"。参考水位取<b>最近段</b>而非全局最高水位，避免把"沿河正常下降的河谷"
 * 误判成凹槽（2026-08-31 修正：初版用全局 max，误报 67 列）。</p>
 *
 * <ul>
 *   <li>{@code trenchBank}：河道外(dist&gt;width)干地被挖到邻接水面以下 ≥0.5 → 岸侧凹台。</li>
 *   <li>{@code trenchEdge}：河道内(dist≤width)河缘干带低于水面 ≥0.5（河缘浅水带属正常，仅参考）。</li>
 * </ul>
 *
 * <p>跌水点选取：相邻节点水面阶跃 ≥3 格（真瀑布 tread 落差），排除 run 起点 1.0 标记位。</p>
 */
public final class WaterfallCornerProbe {

    /** 窗口半径（block）。 */
    private static final int WINDOW = 20;
    /** 最多分析多少个跌水点。 */
    private static final int MAX_FALLS = 12;
    /** 真跌水判定：相邻节点水面阶跃（block）。 */
    private static final double MIN_STEP = 3.0;
    /** 断offset 打印条数上限。 */
    private static final int MAX_DETAIL = 40;

    private WaterfallCornerProbe() { }

    /** 一个真跌水点（tread 落差 ≥ MIN_STEP）。 */
    private static final class Fall {
        final double wx, wz;
        final double lip, pool;
        final double width;
        final double dirX, dirZ;      // 流向（单位向量）
        final double px, pz;          // 垂直于流向（单位向量）
        Fall(double wx, double wz, double lip, double pool, double width,
             double dirX, double dirZ) {
            this.wx = wx; this.wz = wz; this.lip = lip; this.pool = pool; this.width = width;
            double len = Math.hypot(dirX, dirZ);
            if (len < 1e-9) { this.dirX = 1.0; this.dirZ = 0.0; }
            else { this.dirX = dirX / len; this.dirZ = dirZ / len; }
            this.px = -this.dirZ;
            this.pz = this.dirX;
        }
    }

    /** 单列量测结果。 */
    private static final class Col {
        int bx, bz;
        double distToFall;
        double nearestSurf;   // 最近河段水面（本列所邻接的水道水面）
        double original, carved, water, lipSurf;
        double dist, width;
        double blendW;            // carver 用的 IDW 混合半宽（雕刻几何用）
        boolean fill, frozen;
        double fallDrop;
        boolean trenchBank, trenchEdge;
        /** 错位带：按混合宽属于"河道内"(被按河床挖)，却因门控①用 nearestWidth 被排除灌水。 */
        boolean mismatchBand;
        /** 岸侧凹台中被<b>主动雕刻</b>下挖的（真 bug）。 */
        boolean carvedNotch;
        /** 岸侧凹台中未被雕刻、本就低于水面的自然坡（河沿山坡走，属正常）。 */
        boolean naturalLow;
        /** 3×3 邻域内雕刻后高差（block）：小=平台，大=陡坡/崖面。 */
        double flatRange = Double.NaN;
        /** 平台：低于邻接水面 ≥1 格、且 3×3 邻域平坦（高差≤0.6）→ 真"低于河道的平台"。 */
        boolean platform;
        double deficit() { return nearestSurf - carved; }   // >0 = 干地低于邻接水面
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double hs = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;

        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        Map<Long, List<HydrologyBlockCarvedColumn>> cache = new LinkedHashMap<>();

        List<Fall> falls = collectFalls(engine);
        System.out.println("=== WaterfallCornerProbe ===");
        System.out.println("seed=" + seed + " horizontalScale=" + hs
                + " 真跌水点=" + falls.size() + " (阶跃>=" + MIN_STEP + "格，分析前 " + MAX_FALLS + " 个)");
        if (falls.isEmpty()) {
            System.out.println("未找到真跌水点（阶跃>=" + MIN_STEP + "），无法分析。");
            return;
        }
        System.out.println();
        System.out.println("跌水点      | 唇口→潭面     | 半宽 | trenchBank | trenchEdge | 最深亏缺");
        System.out.println("------------|---------------|------|------------|------------|--------");

        int totalBank = 0, totalEdge = 0, totalMismatch = 0;
        int totalNotch = 0, totalNatural = 0, totalPlatform = 0;
        Fall worst = null;
        List<Col> worstCols = new ArrayList<>();
        int worstCount = -1;

        int analyzed = 0;
        for (Fall f : falls) {
            if (analyzed >= MAX_FALLS) break;
            analyzed++;
            List<Col> cols = analyze(engine, terrain, f, hs, cache);
            int bank = 0, edge = 0, mism = 0, notch = 0, natur = 0, plat = 0;
            double worstDeficit = 0.0;
            for (Col c : cols) {
                if (c.trenchBank) { bank++; worstDeficit = Math.max(worstDeficit, c.deficit()); }
                if (c.trenchEdge) edge++;
                if (c.mismatchBand) mism++;
                if (c.carvedNotch) notch++;
                if (c.naturalLow) natur++;
                if (c.platform) plat++;
            }
            totalBank += bank;
            totalEdge += edge;
            totalMismatch += mism;
            totalNotch += notch;
            totalNatural += natur;
            totalPlatform += plat;
            System.out.printf("#%-2d(%.0f,%.0f) | %6.2f→%6.2f | %4.1f | %10d | %10d | %6.2f | %6d%n",
                    analyzed, f.wx, f.wz, f.lip, f.pool, f.width, bank, edge, worstDeficit, mism);
            if (bank + edge > worstCount) { worstCount = bank + edge; worst = f; worstCols = cols; }
        }

        System.out.println();
        System.out.println("trenchBank=" + totalBank + " (河道外干地被挖到邻接水面下≥0.5格 —— 岸侧凹台，须为 0)");
        System.out.println("trenchEdge=" + totalEdge + " (河道内河缘干带低于水面 —— 河缘浅水带属正常，仅参考)");
        System.out.println("  ├ carvedNotch=" + totalNotch
                + " (被<b>主动雕刻</b>下挖到水面以下的岸坡 —— 真 bug，须为 0)");
        System.out.println("  └ naturalLow =" + totalNatural
                + " (未雕刻、本就低于水面的自然坡 —— 河沿山坡走属正常，仅参考)");
        System.out.println("mismatchBand=" + totalMismatch
                + " (按IDW混合宽属河道内、却被门控① nearestWidth 排除灌水的干沟 —— 须为 0)");
        System.out.println("★ platform=" + totalPlatform
                + " (低于邻接水面≥1格 且 3×3邻域平坦(高差≤0.6) —— 真正的'低于河道的平台'，须为 0)");
        System.out.println("status=" + ((totalPlatform == 0 && totalNotch == 0 && totalMismatch == 0)
                ? "PASS" : "FAIL"));

        if (worst != null && worstCount > 0) {
            System.out.println();
            System.out.printf("最差跌水点：世界(%.1f,%.1f) 唇口=%.2f 潭面=%.2f 半宽=%.2f%n",
                    worst.wx, worst.wz, worst.lip, worst.pool, worst.width);
            printFlowMap(engine, terrain, worst, hs, cache);
            printMap(worst, worstCols, hs);
            printSection(engine, terrain, worst, hs, cache, true);
            printSection(engine, terrain, worst, hs, cache, false);
            printDetails(worstCols);
        }
    }

    private static List<Fall> collectFalls(HydrologyExperimentEngine engine) {
        List<Fall> falls = new ArrayList<>();
        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                RiverLineRegion region = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline river : region.rivers) {
                    for (int i = 1; i < river.nodes.length; i++) {
                        double step = river.surfaceY[i - 1] - river.surfaceY[i];
                        if (step >= MIN_STEP) {
                            falls.add(new Fall(river.nodes[i].x(), river.nodes[i].z(),
                                    river.surfaceY[i - 1], river.surfaceY[i],
                                    Math.max(river.width[i], 1.0),
                                    river.nodes[i].x() - river.nodes[i - 1].x(),
                                    river.nodes[i].z() - river.nodes[i - 1].z()));
                        }
                    }
                    if (falls.size() >= MAX_FALLS) break;
                }
                if (falls.size() >= MAX_FALLS) break;
            }
            if (falls.size() >= MAX_FALLS) break;
        }
        return falls;
    }

    private static List<Col> analyze(HydrologyExperimentEngine engine, CellGenerator terrain,
                                     Fall f, double hs,
                                     Map<Long, List<HydrologyBlockCarvedColumn>> cache) {
        List<Col> out = new ArrayList<>();
        int cbx = (int) Math.floor(f.wx * hs);
        int cbz = (int) Math.floor(f.wz * hs);
        int cx0 = Math.floorDiv(cbx - WINDOW, 16), cx1 = Math.floorDiv(cbx + WINDOW, 16);
        int cz0 = Math.floorDiv(cbz - WINDOW, 16), cz1 = Math.floorDiv(cbz + WINDOW, 16);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (HydrologyBlockCarvedColumn c : columns(engine, terrain, cx, cz, hs, cache)) {
                    int dx = c.blockX() - cbx, dz = c.blockZ() - cbz;
                    if (Math.abs(dx) > WINDOW || Math.abs(dz) > WINDOW) continue;
                    Col col = measure(engine, c, hs);
                    if (col != null) { col.distToFall = Math.hypot(dx, dz); out.add(col); }
                }
            }
        }
        // 平坦度：3×3 邻域内 carved 的极差（平台≈0，崖坡很大）
        Map<Long, Col> byPos = new LinkedHashMap<>();
        for (Col c : out) byPos.put((((long) c.bx) << 32) | (c.bz & 0xffffffffL), c);
        for (Col c : out) {
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            int n = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    Col nb = byPos.get((((long) (c.bx + dx)) << 32) | ((c.bz + dz) & 0xffffffffL));
                    if (nb == null) continue;
                    lo = Math.min(lo, nb.carved);
                    hi = Math.max(hi, nb.carved);
                    n++;
                }
            }
            if (n >= 6) {
                c.flatRange = hi - lo;
                c.platform = c.deficit() >= 1.0 && c.flatRange <= 0.6;
            }
        }
        out.sort((a, b) -> Double.compare(b.deficit(), a.deficit()));
        return out;
    }

    /** 对一列做量测：判据用<b>最近河段</b>的水面。 */
    private static Col measure(HydrologyExperimentEngine engine,
                               HydrologyBlockCarvedColumn c, double hs) {
        List<HydrologyBlockSample> samples = engine.sampleBlockAll(c.blockX(), c.blockZ(), hs);
        if (samples.isEmpty()) return null;
        HydrologyBlockSample s0 = samples.get(0);
        Col col = new Col();
        col.bx = c.blockX();
        col.bz = c.blockZ();
        col.nearestSurf = s0.surfaceY();
        col.original = c.originalGroundY();
        col.carved = c.carvedGroundY();
        col.water = c.waterSurfaceY();
        col.lipSurf = c.lipSurfaceY();
        col.dist = s0.distToCenter();
        col.width = Math.max(s0.width(), 1.0);
        col.fill = c.fillWater();
        col.frozen = s0.frozen();
        col.fallDrop = s0.fallDrop();
        // 复算 carver 的 IDW 混合半宽（雕刻几何 t/valleyT/profile 用的就是它）
        RiverLineParams P = RiverLineParams.defaults();
        double blendDist = P.heightBlendDist();
        double wSum = 0.0, sWid = 0.0;
        for (HydrologyBlockSample s : samples) {
            double d = s.distToCenter();
            if (d > blendDist) break;
            double fade = NoiseUtil.saturate(d / blendDist);
            double w = (1.0 - fade) * (1.0 - fade) / Math.max(d * d, 1.0);
            wSum += w;
            sWid += w * s.width();
        }
        col.blendW = wSum > 1e-9 ? Math.max(sWid / wSum, 1.0) : col.width;
        col.mismatchBand = col.dist > col.width && col.dist <= col.blendW
                && !col.fill && col.carved < col.water - 0.5;
        col.trenchBank = !col.fill && col.dist > col.width && col.deficit() >= 0.5;
        col.trenchEdge = !col.fill && col.dist <= col.width && col.deficit() >= 0.5;
        // 拆分：主动挖下去的凹坑（真 bug） vs 未被雕刻、本就低于水面的自然坡（河沿山坡，正常）
        col.carvedNotch = col.trenchBank && (col.original - col.carved) > 0.5;
        col.naturalLow = col.trenchBank && (col.original - col.carved) <= 0.5;
        return col;
    }

    private static List<HydrologyBlockCarvedColumn> columns(HydrologyExperimentEngine engine,
                                                            CellGenerator terrain, int cx, int cz,
                                                            double hs,
                                                            Map<Long, List<HydrologyBlockCarvedColumn>> cache) {
        long key = (((long) cx) << 32) | (cz & 0xffffffffL);
        List<HydrologyBlockCarvedColumn> cols = cache.get(key);
        if (cols == null) {
            cols = HydrologyBlockCarver.carveChunk(engine, cx, cz, hs, ground(terrain, cx, cz, hs));
            cache.put(key, cols);
        }
        return cols;
    }

    /**
     * 俯视状态图。
     * 图例：F=跌水节点 C=水幕列 w=灌水列 X=岸侧凹台(trenchBank) x=河缘干带(trenchEdge)
     *       ^=高于邻接水面>2格 +=与邻接水面差≤2格 1/2/3=低于邻接水面 0.5~3 / 3~8 / >8 格
     *       .=未命中河线（原地形）
     */
    private static void printMap(Fall f, List<Col> cols, double hs) {
        int cbx = (int) Math.floor(f.wx * hs);
        int cbz = (int) Math.floor(f.wz * hs);
        Map<Long, Col> byPos = new LinkedHashMap<>();
        for (Col c : cols) byPos.put((((long) c.bx) << 32) | (c.bz & 0xffffffffL), c);
        System.out.println();
        System.out.println("俯视图（行=Z 增，列=X 增，中心 F=跌水节点）：");
        for (int dz = -WINDOW; dz <= WINDOW; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -WINDOW; dx <= WINDOW; dx++) {
                int bx = cbx + dx, bz = cbz + dz;
                if (dx == 0 && dz == 0) { sb.append('F'); continue; }
                Col c = byPos.get((((long) bx) << 32) | (bz & 0xffffffffL));
                if (c == null) { sb.append('.'); continue; }
                if (c.trenchBank) sb.append('X');
                else if (c.trenchEdge) sb.append('x');
                else if (c.fill) sb.append(c.fallDrop > 0.0 ? 'C' : 'w');
                else {
                    double d = c.deficit();
                    if (d < -2.0) sb.append('^');
                    else if (d < 0.5) sb.append('+');
                    else if (d < 3.0) sb.append('1');
                    else if (d < 8.0) sb.append('2');
                    else sb.append('3');
                }
            }
            System.out.println(sb);
        }
    }

    /**
     * 沿流/横流展开图：行 = 沿流向（负=上游/唇口侧，0=跌水点，正=下游/潭侧），
     * 列 = 横向偏移（0 = 河心）。值 = 雕刻后地形相对"该列最近河段水面"的高差：
     * C=水幕 w=灌水；小写/大写区分是否被<b>主动雕刻</b>下挖（a/b/c/V/- = 被挖）：
     * a/b/c = 被挖到低于水面 0.5~3 / 3~8 / >8 格（病态，即"低于河道的平台"）；
     * 1/2/3 = 未被挖、本就低于水面（崖坡/山坡，正常）；^ ~ = 高于/齐水面。
     */
    private static void printFlowMap(HydrologyExperimentEngine engine, CellGenerator terrain,
                                     Fall f, double hs,
                                     Map<Long, List<HydrologyBlockCarvedColumn>> cache) {
        System.out.println();
        System.out.println("展开图：行=沿流向(-上游 → +下游)，列=横向(- → +)，值=地形相对当地水面高差");
        System.out.println("      " + String.format("%" + (2 * WINDOW + 1) + "s", "横向偏移 →"));
        for (int s = -WINDOW; s <= WINDOW; s++) {
            StringBuilder sb = new StringBuilder();
            for (int p = -WINDOW; p <= WINDOW; p++) {
                double wx = f.wx + (f.dirX * s + f.px * p) / hs;
                double wz = f.wz + (f.dirZ * s + f.pz * p) / hs;
                int bx = (int) Math.floor(wx * hs);
                int bz = (int) Math.floor(wz * hs);
                HydrologyBlockCarvedColumn col = null;
                for (HydrologyBlockCarvedColumn c : columns(engine, terrain,
                        Math.floorDiv(bx, 16), Math.floorDiv(bz, 16), hs, cache)) {
                    if (c.blockX() == bx && c.blockZ() == bz) { col = c; break; }
                }
                if (col == null) { sb.append('.'); continue; }
                Col m = measure(engine, col, hs);
                if (m == null) { sb.append('.'); continue; }
                if (m.fill) { sb.append(m.fallDrop > 0.0 ? 'C' : 'w'); continue; }
                double d = m.deficit();
                boolean dug = (m.original - m.carved) > 0.5;   // 被主动雕刻下挖
                if (d < -2.0) sb.append(dug ? 'V' : '^');       // V=被挖低但仍高于水面
                else if (d < 0.5) sb.append(dug ? '-' : '~');   // -=被挖到水面附近
                else if (d < 3.0) sb.append(dug ? 'a' : '1');   // a=挖成低于水面(病态)
                else if (d < 8.0) sb.append(dug ? 'b' : '2');   // b=挖成远低于水面(病态)
                else sb.append(dug ? 'c' : '3');                // c=挖成极低于水面(病态)
            }
            System.out.printf("%4d %s%s%n", s, sb, s == 0 ? "  <= 跌水点所在行" : "");
        }
    }

    /**
     * 断面扫描：{@code perp=true} 垂直于流向（过跌水点），
     * {@code perp=false} 沿流向（穿过跌水点上/下游）。
     */
    private static void printSection(HydrologyExperimentEngine engine, CellGenerator terrain,
                                     Fall f, double hs,
                                     Map<Long, List<HydrologyBlockCarvedColumn>> cache,
                                     boolean perp) {
        System.out.println();
        System.out.println(perp ? "断面 A：垂直于流向（过跌水点，0 = 河心）"
                : "断面 B：沿流向（0 = 跌水点，负=上游/唇口侧）");
        System.out.println("off |    bx     bz | dist width | orig carved water  lipSurf "
                + "| 亏缺 | fill froz   fd");
        for (int o = -WINDOW; o <= WINDOW; o++) {
            double ux = perp ? f.px : f.dirX;
            double uz = perp ? f.pz : f.dirZ;
            double wx = f.wx + ux * (o / hs);
            double wz = f.wz + uz * (o / hs);
            int bx = (int) Math.floor(wx * hs);
            int bz = (int) Math.floor(wz * hs);
            HydrologyBlockCarvedColumn col = null;
            for (HydrologyBlockCarvedColumn c : columns(engine, terrain,
                    Math.floorDiv(bx, 16), Math.floorDiv(bz, 16), hs, cache)) {
                if (c.blockX() == bx && c.blockZ() == bz) { col = c; break; }
            }
            if (col == null) {
                System.out.printf("%3d | %6d %6d |  —— 未命中河线（原地形）——%n", o, bx, bz);
                continue;
            }
            Col m = measure(engine, col, hs);
            if (m == null) continue;
            System.out.printf("%3d | %6d %6d | %4.1f %5.1f | %5.1f %6.1f %5.1f %7.1f "
                            + "| %5.1f | %s    %s  %5.1f%s%n",
                    o, bx, bz, m.dist, m.width, m.original, m.carved, m.water, m.lipSurf,
                    m.deficit(),
                    m.fill ? "Y" : ".", m.frozen ? "Y" : ".", m.fallDrop,
                    m.trenchBank ? "  <= 岸侧凹台" : (m.trenchEdge ? "  <= 河缘干带" : ""));
        }
    }

    private static void printDetails(List<Col> cols) {
        List<Col> bad = new ArrayList<>();
        for (Col c : cols) if (c.trenchBank) bad.add(c);
        if (bad.isEmpty()) return;
        bad.sort((a, b) -> Double.compare(b.deficit(), a.deficit()));
        System.out.println();
        System.out.println("岸侧凹台明细（前 " + MAX_DETAIL + " 条，按亏缺降序）：");
        System.out.println("    bx     bz dFall  dist nWdt bWdt |  orig carved nearSurf | 亏缺 "
                + "| 挖深 | 钳制? froz   fd");
        int n = 0;
        for (Col c : bad) {
            if (n++ >= MAX_DETAIL) break;
            // 钳制条件 = dist > IDW混合宽（carver 的 width）；若混合宽>实际距离则钳制被绕过
            boolean clampOn = c.dist > c.blendW;
            System.out.printf("%6d %6d %5.1f %5.1f %4.1f %4.1f | %5.1f %6.1f %8.1f | %5.1f "
                            + "| %5.1f | %-5s %s %5.1f%s%n",
                    c.bx, c.bz, c.distToFall, c.dist, c.width, c.blendW, c.original, c.carved,
                    c.nearestSurf, c.deficit(), c.original - c.carved,
                    clampOn ? "ON" : "绕过", c.frozen ? "Y" : ".", c.fallDrop,
                    c.carvedNotch ? "  <= 主动挖坑" : "  (自然低地)");
        }
        System.out.println("（岸侧凹台共 " + bad.size() + " 列）");
    }

    private static double[] ground(CellGenerator terrain, int cx, int cz, double scale) {
        double[] values = new double[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                values[x * 16 + z] = terrain.sample((cx * 16 + x) / scale,
                        (cz * 16 + z) / scale).height;
            }
        }
        return values;
    }
}
