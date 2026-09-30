package com.geogenesis.worldgen.hydrology.sim;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 新水文核心的<b>出图探针</b>：把 {@link HydroWorldSolver} 的状态直接光栅化，供人眼判断。
 *
 * <p>为什么必须有它：所有既有 PNG 探针（{@code WaterViewProbe} / {@code HydroRefProbe}）
 * 画的都是旧链或参照 —— 新核心此前<b>一张图都没有</b>，无法判断效果。</p>
 *
 * <p>输出 {@code build/hydrocore/core_view.png}，三联：</p>
 * <ol>
 *   <li>地形山体阴影（与旧探针同口径 Lambert）；</li>
 *   <li>新核心状态：海=深蓝 / 湖=蓝 / 河=青 / 溢口=白点；</li>
 *   <li>未决（保守未决的格=橙）+ 盆地轮廓（洋红）+ 最低溢口（白）。</li>
 * </ol>
 *
 * <p>运行：{@code gradlew runHydroCoreViewProbe [-PprobeArgs="seed bx bz radius"]}</p>
 */
public final class HydroCoreViewProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 400;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        final double hs = tp.horizontalScale();
        final var curve = gen.heightCurve();

        double seaLevel = 62.0;
        try {
            seaLevel = curve.seaLevelY();
        } catch (RuntimeException ignore) {
            // 保底常量
        }
        final double sea = seaLevel;

        HydroSampler sampler = new HydroSampler() {
            @Override
            public double height(double bx2, double bz2) {
                // 与游戏可见地形同源；无侵蚀的 terrainEQuick 会把河放到错误的旧地形上。
                return gen.erodedHeightForRouting(bx2 / hs, bz2 / hs);
            }

            @Override
            public double source(double bx2, double bz2) {
                double p = gen.precipitationAt(bx2 / hs, bz2 / hs);
                return p > 0 ? p : 0;
            }
        };
        HydroConfig cfg = HydroConfig.defaults().withSeaLevel(sea);
        HydroCellSampler cachedSampler = new HydroCellSampler(sampler);
        HydroWorldSolver solver = new HydroWorldSolver(seed, cfg, cachedSampler);

        int n = 2 * radius + 1;
        double[] terr = new double[n * n];
        byte[] state = new byte[n * n];
        byte[] basin = new byte[n * n];      // 0=非盆地 1=盆地
        boolean[] isSpill = new boolean[n * n];
        boolean[] pending = new boolean[n * n];
        double[] accAll = new double[n * n];
        double[] dischAll = new double[n * n];
        int[] gxPix = new int[n * n];
        int[] gzPix = new int[n * n];
        long crossTile = 0;
        long flowingPixels = 0;

        long t0 = System.nanoTime();
        // ① 先解析窗口覆盖的全部 tile（保证后续逐像素读取命中缓存、不再触发递归）
        int cellBlocks = cfg.cellBlocks();
        int gx0 = HydroContract.cellX(bx - radius), gx1 = HydroContract.cellX(bx + radius);
        int gz0 = HydroContract.cellZ(bz - radius), gz1 = HydroContract.cellZ(bz + radius);
        int tx0 = HydroTileKey.tileOfCell(gx0), tx1 = HydroTileKey.tileOfCell(gx1);
        int tz0 = HydroTileKey.tileOfCell(gz0), tz1 = HydroTileKey.tileOfCell(gz1);
        int tilesResolved = 0;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                solver.resolve(new HydroTileKey(seed, tx, tz, 0));
                tilesResolved++;
            }
        }
        long tResolve = System.nanoTime();

        long sink = 0;
        for (int j = 0; j < n; j++) {
            int wz = bz - radius + j;
            for (int i = 0; i < n; i++) {
                int wx = bx - radius + i;
                int gx = HydroContract.cellX(wx), gz = HydroContract.cellZ(wz);
                HydroTileKey key = new HydroTileKey(seed,
                        HydroTileKey.tileOfCell(gx), HydroTileKey.tileOfCell(gz), 0);
                HydroTileResult r = solver.result(key);
                HydroTileField f = r.topo.field;
                int lx = gx - f.winMinX, lz = gz - f.winMinZ;
                int p = j * n + i;
                if (!f.inside(lx, lz)) { state[p] = (byte) FlowState.NONE.ordinal(); continue; }
                int idx = lx * f.w + lz;
                terr[p] = f.height[idx];
                state[p] = r.balance.state[idx];
                accAll[p] = r.balance.accum[idx];
                dischAll[p] = r.balance.discharge[idx];
                gxPix[p] = gx;
                gzPix[p] = gz;
                int b = r.topo.basinId[idx];
                if (b >= 0) {
                    basin[p] = 1;
                    if (r.topo.basinSpillCell[b] == idx) isSpill[p] = true;
                    // 真未决 = 盆地 rim 在 window 之外（水位只是下界），与"跨 tile 出口"无关
                    if (!r.topo.basinResolved(b)) pending[p] = true;
                }
                // 只对【真正的流动格】统计跨 tile 出口；坡面 NONE 格下水文没有意义
                FlowState stt = FlowState.values()[r.balance.state[idx]];
                if (stt.isFlowing()) {
                    flowingPixels++;
                    if (HydroSolverQuery.outcomeOf(r, idx) == OutcomeKind.BOUNDARY_PENDING) crossTile++;
                }
            }
        }
        long tPix = System.nanoTime();

        // ②b 生命周期链追踪 —— 用户判据：必须有一条【源头→汇流→洼地蓄水→最低溢口→
        //    继续下泄→入海】连成一条链，而不是各阶段计数碰巧都 >0。
        //    做法：从每个 SOURCE 沿真实 D8（逐 tile 查拓扑）一路走到终点，
        //    逐阶段打标，统计完整链并高亮到图上。
        int gx0p = HydroContract.cellX(bx - radius);
        int gz0p = HydroContract.cellZ(bz - radius);
        boolean[] vis = new boolean[n * n];
        int srcTraced = 0, fullChains = 0, seaNoLake = 0, toBoundary = 0, hitLake = 0;
        int missEntry = 0, missSpill = 0, missDown = 0;
        List<int[]> fullChainPx = new ArrayList<>();
        String firstChain = "";
        for (int p0 = 0; p0 < n * n; p0++) {
            if (state[p0] != (byte) FlowState.SOURCE.ordinal() || vis[p0]) continue;
            srcTraced++;
            int cur = p0;
            boolean hasIn = false, hasLake = false, hasSpill = false, hasDown = false;
            boolean reachedSea = false, hitBoundary = false;
            List<Integer> chain = new ArrayList<>();
            StringBuilder seq = new StringBuilder();
            FlowState prevSt = null;
            int guard = 0;
            while (cur >= 0 && guard++ < 4 * n * n) {
                vis[cur] = true;
                chain.add(cur);
                FlowState st = FlowState.values()[state[cur]];
                if (st != prevSt) {
                    if (seq.length() < 300) {
                        seq.append(seq.length() == 0 ? "" : " → ").append(st);
                    }
                    prevSt = st;
                }
                if (st == FlowState.BASIN_ENTRY) hasIn = true;
                if (st == FlowState.LAKE_STORAGE) hasLake = true;
                if (st == FlowState.SPILLWAY) hasSpill = true;
                if (st == FlowState.DOWNSTREAM) hasDown = true;
                if (st == FlowState.SEA) { reachedSea = true; break; }
                int gx = gxPix[cur], gz = gzPix[cur];
                HydroTileResult rr = solver.result(new HydroTileKey(seed,
                        HydroTileKey.tileOfCell(gx), HydroTileKey.tileOfCell(gz), 0));
                HydroTileField f = rr.topo.field;
                int lx = gx - f.winMinX, lz = gz - f.winMinZ;
                if (!f.inside(lx, lz)) { hitBoundary = true; break; }
                int li = lx * f.w + lz;
                int d = rr.topo.down[li];
                if (d < 0) { hitBoundary = f.isBorder(li); break; }
                int pi = pixelIndex(f.globalX(d), f.globalZ(d), gx0p, gz0p, n,
                        cfg.cellBlocks());
                if (pi < 0) { hitBoundary = true; break; }
                cur = pi;
            }
            if (hasLake) hitLake++;
            boolean full = hasIn && hasLake && hasSpill && hasDown && reachedSea;
            if (full) {
                fullChains++;
                int[] arr = new int[chain.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = chain.get(i);
                fullChainPx.add(arr);
                if (firstChain.isEmpty()) {
                    firstChain = seq + "  （格数 " + chain.size() + "，起点块 "
                            + bx0(p0, n, bx, radius) + "," + bz0(p0, n, bz, radius) + "）";
                }
            } else if (reachedSea) {
                seaNoLake++;
                if (!hasIn) missEntry++;
                if (!hasSpill) missSpill++;
                if (!hasDown) missDown++;
            } else if (hitBoundary) {
                toBoundary++;
            }
        }
        System.out.printf("  生命周期链追踪：源头 %d 条 · 完整六阶段→入海 %d 条"
                        + " · 到海但未经湖 %d · 出窗口 %d · 经过任意湖 %d%n",
                srcTraced, fullChains, seaNoLake, toBoundary, hitLake);
        if (!firstChain.isEmpty()) {
            System.out.println("  完整链示例：" + firstChain);
        }
        if (fullChains == 0) {
            System.out.println("  ❌ 没有任何一条链走完「源头→汇流→洼地蓄水→最低溢口→继续下泄→入海」"
                    + "（缺 入湖口=" + missEntry + " 溢口=" + missSpill
                    + " 续流=" + missDown + "，见上）");
        }

        // 统计
        int[] cnt = new int[FlowState.values().length];
        for (byte s : state) cnt[s]++;
        int[] outc = null;
        long landSink = 0;
        double riverPct = 100.0 * cnt[FlowState.CHANNEL.ordinal()] / (n * (double) n);
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                HydroTileResult r = solver.result(new HydroTileKey(seed, tx, tz, 0));
                int[] c = r.outcomeCounts();
                if (outc == null) outc = new int[c.length];
                for (int k = 0; k < c.length; k++) outc[k] += c[k];
            }
        }
        if (outc != null) landSink = outc[OutcomeKind.LAND_SINK.ordinal()];

        // ② 出图：一次解析 → 多阈值对比（阈值只影响"哪条沟算河"，不影响水量与湖泊）
        BufferedImage hs1 = shade(terr, n);
        File dir = new File("build/hydrocore");
        dir.mkdirs();
        // 默认阈值图（含诊断面板）
        BufferedImage view = renderWater(hs1, state, dischAll, cfg.channelThreshold());
        BufferedImage diag = deepCopy(hs1);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int p = j * n + i;
                if (basin[p] == 1) diag.setRGB(i, j, blend(diag.getRGB(i, j), 0xC000C0, 0.35));
                if (isSpill[p]) diag.setRGB(i, j, 0xFFFFFF);
                if (pending[p]) diag.setRGB(i, j, 0xFF8000);
            }
        }
        writeStrip(hs1, view, diag, n, new File(dir, "core_view.png"));
        // 阈值扫描图（同一份解析结果，仅重筛河道）
        //   ⚠ 2026-09-30 二修：首版统计加了 `st.isFlowing()` 过滤，而 isFlowing 的前提
        //   就是"已过当前阈值成河" ⇒ 扫描恒等于当前河道占比、对阈值完全不敏感
        //   （假象：30~852 档都是 ~2.9%）⇒ 占 95%+ 的 NONE 格真实流量从未参与。
        //   现改为【全格口径】：非湖/海的任何格，discharge ≥ 阈值即"按该阈值会成河"。
        double[] scan = {30.0, 60.0, 120.0, 300.0, 852.0};
        StringBuilder scanLog = new StringBuilder("  阈值扫描（全格口径·该阈值下的河道占比）：");
        BufferedImage scanStrip = new BufferedImage(n * scan.length + 6 * (scan.length - 1), n,
                BufferedImage.TYPE_INT_RGB);
        for (int s = 0; s < scan.length; s++) {
            BufferedImage v = renderWater(hs1, state, dischAll, scan[s]);
            long rc = 0;
            for (int p = 0; p < n * n; p++) {
                FlowState st = FlowState.values()[state[p]];
                if (st != FlowState.SEA && st != FlowState.LAKE_STORAGE
                        && dischAll[p] >= scan[s]) rc++;
            }
            scanLog.append(String.format("%.0f→%.2f%%  ", scan[s], 100.0 * rc / (n * (double) n)));
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    scanStrip.setRGB(s * (n + 6) + i, j, v.getRGB(i, j));
                }
            }
        }
        ImageIO.write(scanStrip, "png", new File(dir, "core_view_scan.png"));
        // 生命周期图：左=全部水文状态，右=完整六阶段链逐格高亮（链身黄 / 入湖品红 / 溢口白 / 续流橙）
        BufferedImage lifeA = renderWater(hs1, state, dischAll, cfg.channelThreshold());
        BufferedImage lifeB = deepCopy(lifeA);
        int cb = cfg.cellBlocks();
        for (int[] ch : fullChainPx) {
            for (int px : ch) {
                int i = px % n, j = px / n;
                FlowState st = FlowState.values()[state[px]];
                int col = switch (st) {
                    case BASIN_ENTRY -> 0xFF40FF;
                    case SPILLWAY -> 0xFFFFFF;
                    case DOWNSTREAM -> 0xFF8800;
                    case LAKE_STORAGE -> 0x4A90E2;
                    default -> 0xFFDD00;                 // SOURCE / CHANNEL / SEA：链身
                };
                // 按格铺满（1px 链在缩放图上不可读 —— 实测左右两图完全看不出差别）
                for (int dy = 0; dy < cb; dy++) {
                    int yy = j + dy;
                    if (yy < 0 || yy >= n) continue;
                    for (int dx = 0; dx < cb; dx++) {
                        int xx = i + dx;
                        if (xx < 0 || xx >= n) continue;
                        lifeB.setRGB(xx, yy, col);
                    }
                }
            }
        }
        write2Strip(lifeA, lifeB, n, new File(dir, "core_lifecycle.png"));

        System.out.println("=== HydroCoreViewProbe seed=" + seed + " center=(" + bx + "," + bz
                + ") r=" + radius + " sea=" + sea + " ===");
        System.out.printf("  tiles=%d  解析=%.1fs  取像素=%.1fs  touched=%d cached=%d sampler hit/miss=%d/%d%n", tilesResolved,
                (tResolve - t0) / 1e9, (tPix - tResolve) / 1e9,
                solver.touchedTiles(), solver.cachedTiles(), cachedSampler.hits(), cachedSampler.misses());
        StringBuilder sb = new StringBuilder("  状态：");
        for (FlowState s : FlowState.values()) {
            if (cnt[s.ordinal()] > 0) sb.append(s).append('=').append(cnt[s.ordinal()]).append(' ');
        }
        System.out.println(sb);
        if (outc != null) {
            StringBuilder ob = new StringBuilder("  归宿：");
            for (OutcomeKind k : OutcomeKind.values()) {
                if (outc[k.ordinal()] > 0) ob.append(k).append('=').append(outc[k.ordinal()]).append(' ');
            }
            System.out.println(ob);
        }
        System.out.println("  LAND_SINK = " + landSink + (landSink == 0 ? "  ✅" : "  ❌ 违反契约"));
        System.out.println("  真未决像素（rim 在窗外，水位只是下界）= " + countTrue(pending)
                + " / " + (n * n) + "  (" + String.format("%.2f", 100.0 * countTrue(pending) / (n * n)) + "%)");
        System.out.println("  跨 tile 出口（流动格中，正常，由邻 tile 接管）= " + crossTile
                + " / " + flowingPixels
                + "  (" + String.format("%.2f", 100.0 * crossTile / Math.max(1, flowingPixels)) + "%)");
        System.out.println(scanLog);
        System.out.println("  输出：build/hydrocore/core_view.png（左=侵蚀地形 / 中=水文 / 右=盆地+真未决）");
        System.out.println("        build/hydrocore/core_view_scan.png（同一次解析的阈值扫描）");
        System.out.println("        build/hydrocore/core_lifecycle.png（左=水文 / 右=完整六阶段链高亮）");

        // ★ 成河阈值标定依据：累积量分位数（不靠猜）
        double[] sorted = accAll.clone();
        java.util.Arrays.sort(sorted);
        double cur = cfg.channelThreshold();
        int below = 0, equal = 0;
        for (double v : sorted) { if (v < cur) below++; else if (v == cur) equal++; }
        System.out.printf("  累积分位：p50=%.2f p75=%.2f p90=%.2f p97=%.2f p99=%.2f max=%.1f%n",
                q(sorted, 0.50), q(sorted, 0.75), q(sorted, 0.90), q(sorted, 0.97),
                q(sorted, 0.99), sorted[sorted.length - 1]);
        System.out.printf("  当前成河阈值=%.1f ⇒ 河道格占比=%.2f%%%n", cur,
                100.0 * (sorted.length - below) / sorted.length);
        System.out.printf("  要把河道压到 ~3%%：阈值建议 ≈ p97 = %.1f%n", q(sorted, 0.97));
    }

    private static double q(double[] sorted, double p) {        int i = (int) Math.max(0, Math.min(sorted.length - 1, Math.round(p * (sorted.length - 1))));
        return sorted[i];
    }

    private static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    /**
     * 渲染水体：海/湖/溢口固定，河道按 {@code thr} 重新筛（{@code discharge >= thr}）。
     *
     * <p>这一点很关键：成河阈值只决定"哪条沟算河"，<b>不</b>改变水量、湖位与拓扑，
     * 因此同一次解析结果可以横向对比多个阈值 —— 标定成本从"每阈值一轮"降到"一轮"。</p>
     */
    private static BufferedImage renderWater(BufferedImage base, byte[] state,
                                             double[] disch, double thr) {
        BufferedImage img = deepCopy(base);
        int n = base.getWidth();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int p = j * n + i;
                FlowState st = FlowState.values()[state[p]];
                switch (st) {
                    case SEA -> img.setRGB(i, j, 0x1F4E79);
                    case LAKE_STORAGE -> img.setRGB(i, j, 0x4A90E2);
                    case SPILLWAY -> img.setRGB(i, j, 0xFFFFFF);
                    default -> {
                        // 全格口径：非湖/海的格，只要流量达标就按该阈值成河
                        //（NONE 格达标也画 —— 这正是"调阈值会多出多少河"的直观效果）
                        if (disch[p] >= thr) img.setRGB(i, j, 0x00E5D0);
                    }
                }
            }
        }
        return img;
    }

    private static void writeStrip(BufferedImage a, BufferedImage b, BufferedImage c,
                                   int n, File out) throws java.io.IOException {
        BufferedImage strip = new BufferedImage(n * 3 + 12, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                strip.setRGB(i, j, a.getRGB(i, j));
                strip.setRGB(n + 6 + i, j, b.getRGB(i, j));
                strip.setRGB(2 * n + 12 + i, j, c.getRGB(i, j));
            }
        }
        ImageIO.write(strip, "png", out);
    }

    private static void write2Strip(BufferedImage a, BufferedImage b,
                                    int n, File out) throws java.io.IOException {
        BufferedImage strip = new BufferedImage(n * 2 + 6, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                strip.setRGB(i, j, a.getRGB(i, j));
                strip.setRGB(n + 6 + i, j, b.getRGB(i, j));
            }
        }
        ImageIO.write(strip, "png", out);
    }

    /**
     * 水文格 → 像素索引。
     *
     * <p>⚠ 像素是【块】分辨率（1 px = 1 block），水文格是 {@code cellBlocks} 块见方
     * ⇒ 格偏移必须 ×cellBlocks 才是像素偏移。首版把格坐标直接当像素索引，
     * 每跳一步就错位一步，追踪链全是垃圾路径（880 条全部"出窗口"）。</p>
     */
    private static int pixelIndex(int gx, int gz, int gx0, int gz0, int n, int cb) {
        int i = (gx - gx0) * cb, j = (gz - gz0) * cb;
        return (i < 0 || j < 0 || i >= n || j >= n) ? -1 : j * n + i;
    }

    private static int bx0(int p, int n, int bx, int r) { return bx - r + (p % n); }
    private static int bz0(int p, int n, int bz, int r) { return bz - r + (p / n); }

    private static int blend(int rgb, int col, double a) {
        int r0 = (rgb >> 16) & 0xFF, g0 = (rgb >> 8) & 0xFF, b0 = rgb & 0xFF;
        int r1 = (col >> 16) & 0xFF, g1 = (col >> 8) & 0xFF, b1 = col & 0xFF;
        return ((int) (r0 * (1 - a) + r1 * a) << 16)
                | ((int) (g0 * (1 - a) + g1 * a) << 8)
                | (int) (b0 * (1 - a) + b1 * a);
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < src.getHeight(); j++) {
            for (int i = 0; i < src.getWidth(); i++) out.setRGB(i, j, src.getRGB(i, j));
        }
        return out;
    }

    /** 标准 Lambert 山体阴影（与旧探针 shadeGray 同口径：NW 光源 (-0.5, 0.7, 0.51)）。 */
    private static BufferedImage shade(double[] h, int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double lx = -0.5, ly = 0.7, lz = 0.51;
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll; ly /= ll; lz /= ll;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
                int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
                double dx = (h[j * n + i1] - h[j * n + i0]) / (i1 - i0);
                double dz = (h[j1 * n + i] - h[j0 * n + i]) / (j1 - j0);
                double nx = -dx, ny = 1.0, nz = -dz;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double d = (nx * lx + ny * ly + nz * lz) / nl;
                int g = (int) Math.max(0, Math.min(255, 60 + 175 * Math.max(0, d)));
                img.setRGB(i, j, (g << 16) | (g << 8) | g);
            }
        }
        return img;
    }

    private HydroCoreViewProbe() { }
}
