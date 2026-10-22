package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 从<b>生产骨架的真实源头</b>放物理粒子，叠到<b>生产图</b>上。
 *
 * <p>源头数据来自 {@link ModelHydrologyProvider#skeletonSourcesForProbe}
 * （= 生产 {@code Trace.cell[0]}，用户口径「源头不是起点吗」），
 * 粒子物理与 {@code ErosionEngine.simulateDrop} 同式。</p>
 *
 * <p><b>为什么不自己算骨架</b>：诊断侧任何自建版本都会漏掉生产的
 * REGION 网格 / HALO 外扩 / 跨区接力（{@code incoming}），
 * 实测自建源头仅 3.3% 落在生产河格上 ⇒ 图不可作为验收依据。</p>
 *
 * <p><b>坐标约定</b>：生产图由 {@code img.setRGB(x, z)} 写出
 * ⇒ 图像 <b>列 = x、行 = z</b>；numpy 读回后是 {@code [row=z, col=x]}。
 * 度量脚本必须按此索引，否则结论会被转置成假象。</p>
 */
public final class RootedParticleProbe {

    private static final int STEP = Integer.getInteger("geogenesis.modelStep", 4);
    private static final double INERTIA = 0.005;
    private static final double GRAVITY = 2.5;
    private static final double SPD_CAP = 1.0;
    private static final int LIFE = 20;
    private static final int MAX_STEPS = 20000;

    /**
     * 有效动线的最短行程（块）。短于此的判为<b>短河</b>：<b>图上照画、但不进度量</b>。
     *
     * <p><b>为什么要这个阈值</b>（用户 2026-10-21）：「物理粒子动线参照因为没有溢出后的
     * 河流判定所以会有一些短河，这些短河就不必注意」。
     * 粒子遇洼地即按积水判定停止，而真实河流会溢流继续——这是<b>参照模型自身的局限</b>，
     * 不是骨架的缺陷。把它们计入偏差会系统性惩罚正确行为。</p>
     *
     * <p><b>为什么仍要画</b>（用户同批指示）：「这个在图上还是要显示，只是不进入判断，
     * 可以换个色显示」⇒ 短河用<b>灰色</b>画，长河用橙色画，一眼可辨。</p>
     */
    private static final double MIN_VALID_RUN = Double.parseDouble(
            System.getProperty("rp.minRun", "120"));

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;

        String src = System.getProperty("rp.source", "build/hydro-production/latest/production.png");
        String outDir = System.getProperty("rp.output", "build/rooted");

        BufferedImage base = ImageIO.read(new File(src));
        if (base == null) throw new IllegalStateException("生产图不存在：" + src);
        int n = base.getWidth();
        int ox = cx - ViewSpec.RADIUS, oz = cz - ViewSpec.RADIUS;
        System.out.printf("base=%s %dx%d  origin=(%d,%d)%n", src, n, base.getHeight(), ox, oz);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = Math.max(0.01, tp.horizontalScale());
        double seaLevel = gen.seaLevel();
        var heights = new Heights(gen, hs);
        var provider = new ModelHydrologyProvider(gen, seed);

        // ---- 取生产源头（按【生产的 REGION 网格】遍历，不是窗口网格）----
        //
        // ★★★ 2026-10-21 修正【画幅左侧取不到源头】★★★
        //
        // 【错在哪】此前用 `pipeForProbe().nx()*STEP`(=708) 当窗口边长并铺 3×3：
        //     win=708，中心点 x = -1062 / -354 / 354
        //   但生产 region 划分是 `floorDiv(block, REGION=512)`，
        //     solve 窗口 = 2*(REGION/2+HALO) = 704，中心点 x = -1280 / -768 / -256 / 256
        //   ⇒ 两套窗口【根本不同步】⇒ 画幅左段取不到任何 region。
        //
        // 【实测症状】画幅 x 分 10 带，最左两带 5028 个河格却只有 1 个源头；
        //   左侧每源头负担 679 个河格，其余 123 个（差 5.5 倍）。
        //
        // 【正解】按 REGION 网格枚举 region 索引，覆盖整个画幅（外扩 1 圈兜住边界）。
        int regionSize = Integer.getInteger("geogenesis.modelRegion", 512);
        int rxLo = Math.floorDiv(ox, regionSize) - 1;
        int rxHi = Math.floorDiv(ox + n - 1, regionSize) + 1;
        int rzLo = Math.floorDiv(oz, regionSize) - 1;
        int rzHi = Math.floorDiv(oz + n - 1, regionSize) + 1;
        var roots = new ArrayList<int[]>();
        var seen = new HashSet<Long>();
        // ★ 骨架折线：**必须与源头一一配对**（用户：「不要把不同源头的河流做对比」）
        //
        //   旧做法把「所有源头」和「所有折线」混成两个集合，再用「起点最近的折线」配对
        //   ⇒ 相邻两条河的源头相距 <24 块时会【配错】⇒ 拿 A 的物理线比 B 的骨架线。
        //   正解：生产里 `shaped.get(i)` 与 `traces.get(i)` 下标严格对应
        //   ⇒ 直接按同一下标取「源头 + 该条折线」，天然一对一。
        var paired = new ArrayList<double[]>();   // 每项 = {srcX,srcZ, x1,z1, x2,z2, ...}
        var seenSeg = new HashSet<String>();
        int traceTotal = 0, regionCount = 0;
        for (int rx = rxLo; rx <= rxHi; rx++) {
            for (int rz = rzLo; rz <= rzHi; rz++) {
                int bx = rx * regionSize + regionSize / 2;
                int bz = rz * regionSize + regionSize / 2;
                regionCount++;
                for (double[] row : provider.sourceToPolylineForProbe(bx, bz)) {
                    if (row == null || row.length < 4) continue;
                    // 去重（3×3 邻区合并会让同一 region 重复出现）：用源头+首节点做键
                    StringBuilder dk = new StringBuilder();
                    dk.append(Math.round(row[0])).append(',').append(Math.round(row[1])).append(',')
                      .append(Math.round(row[2] * 10)).append(',').append(Math.round(row[3] * 10));
                    if (!seenSeg.add(dk.toString())) continue;
                    traceTotal++;
                    paired.add(row);
                    long k = ((long) Math.round(row[0]) << 32) ^ (Math.round(row[1]) & 0xffffffffL);
                    if (seen.add(k)) roots.add(new int[]{(int) Math.round(row[0]), (int) Math.round(row[1])});
                }
            }
        }
        System.out.printf("regionSize=%d  rx∈[%d,%d] rz∈[%d,%d]  regions=%d  paired=%d"
                        + "  distinct roots=%d%n",
                regionSize, rxLo, rxHi, rzLo, rzHi, regionCount, paired.size(), roots.size());
        if (roots.isEmpty()) throw new IllegalStateException("生产源头为空（检查 seed/region）");

        // ---- 放粒子，并按行程长度分为【有效】与【短河】----
        //
        //   有效 = 行程 ≥ MIN_VALID_RUN ⇒ 橙色，计入度量
        //   短河 = 行程 <  MIN_VALID_RUN ⇒ 灰色，只画不计（参照模型缺溢流判定所致）
        var validParticles = new ArrayList<List<double[]>>();
        var shortParticles = new ArrayList<List<double[]>>();
        // ★ 逐条路线配对：源头与该源头【对应的那条骨架折线】一一对应
        var pairs = new ArrayList<RoutePair>();
        int skipWater = 0, skipOut = 0;
        for (double[] row : paired) {
            double rx0 = row[0], rz0 = row[1];
            if (rx0 < ox || rz0 < oz || rx0 >= ox + n || rz0 >= oz + n) { skipOut++; continue; }
            if (heights.at(rx0, rz0) <= seaLevel) { skipWater++; continue; }
            List<double[]> path = trace(heights, rx0, rz0, ox, oz, n, seaLevel);
            boolean valid = pathLength(path) >= MIN_VALID_RUN;
            if (valid) validParticles.add(path);
            else shortParticles.add(path);
            if (valid) {
                // 折线节点：row[2..] 为展平的 {x,z}
                int m = (row.length - 2) / 2;
                double[][] skel = new double[m][2];
                for (int i = 0; i < m; i++) {
                    skel[i][0] = row[2 + i * 2];
                    skel[i][1] = row[3 + i * 2];
                }
                pairs.add(new RoutePair(rx0, rz0, path, m >= 2 ? skel : null));
            }
        }
        System.out.printf("particles=%d  valid=%d (行程≥%.0f块)  short=%d (短河，仅显示不计分)"
                        + "  (skipOut=%d skipWater=%d)%n",
                validParticles.size() + shortParticles.size(),
                validParticles.size(), MIN_VALID_RUN,
                shortParticles.size(), skipOut, skipWater);

        Files.createDirectories(Path.of(outDir));

        // ---- 叠加图（红点 = 生产源头，人眼核验"起点在河格上"）----
        BufferedImage img = copy(base);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // ① 短河：灰色（只显示、不计分）—— 先画，避免压住有效动线
        g.setColor(new Color(0x9E, 0x9E, 0x9E));
        for (List<double[]> p : shortParticles) drawPath(g, p, ox, oz);
        // ② 有效动线：橙色（计分）
        g.setColor(new Color(0xFF, 0x6D, 0x00));
        for (List<double[]> p : validParticles) drawPath(g, p, ox, oz);
        // ③ 源头红点
        g.setColor(new Color(0xFF, 0x17, 0x44));
        int drawn = 0;
        for (int[] r : roots) {
            if (r[0] < ox || r[1] < oz || r[0] >= ox + n || r[1] >= oz + n) continue;
            g.fillOval(r[0] - ox - 4, r[1] - oz - 4, 8, 8);
            drawn++;
        }
        g.dispose();
        Path f = Path.of(outDir, "particle-on-production.png");
        ImageIO.write(img, "png", f.toFile());
        System.out.println("wrote " + f.toAbsolutePath() + "  (roots drawn=" + drawn + ")");

        // ---- 纯粒子掩码（度量用）----
        //   ★ 只含【有效动线】：短河是参照模型缺溢流判定的产物，计入即惩罚正确行为。
        //   另出 mask-all 供人眼核对"短河确实被排除"。
        writeMask(validParticles, n, ox, oz, Path.of(outDir, "particle-mask.png"));
        var all = new ArrayList<List<double[]>>(validParticles);
        all.addAll(shortParticles);
        writeMask(all, n, ox, oz, Path.of(outDir, "particle-mask-all.png"));
        System.out.println("wrote " + Path.of(outDir, "particle-mask.png").toAbsolutePath()
                + "  (valid only)");
        System.out.println("wrote " + Path.of(outDir, "particle-mask-all.png").toAbsolutePath()
                + "  (valid + short)");

        // ---- 源头表（供 python 核验"落在生产河格上"）----
        var sb = new StringBuilder("x,z\n");
        for (int[] r : roots) {
            if (r[0] < ox || r[1] < oz || r[0] >= ox + n || r[1] >= oz + n) continue;
            sb.append(r[0]).append(',').append(r[1]).append('\n');
        }
        Files.writeString(Path.of(outDir, "roots.csv"), sb.toString());
        System.out.println("wrote " + Path.of(outDir, "roots.csv").toAbsolutePath());

        // ---- ★ 逐条路线配对导出（用户要求：分色是为了逐条对比，不是只看百分比）----
        //
        //   routes.csv：每条 = 一个源头 + 其物理路径 + 其骨架折线（各自点序列）
        //   格式：route,kind,order,x,z    kind ∈ {P=物理, S=骨架}
        var rc = new StringBuilder("route,root_x,root_z,kind,order,x,z\n");
        int rid = 0, matched = 0;
        for (RoutePair rp : pairs) {
            rid++;
            if (rp.skeleton() != null) matched++;
            for (int i = 0; i < rp.particle().size(); i++) {
                double[] p = rp.particle().get(i);
                rc.append(rid).append(',').append(String.format("%.0f", rp.rootX())).append(',')
                        .append(String.format("%.0f", rp.rootZ())).append(",P,")
                        .append(i).append(',').append(String.format("%.2f", p[0])).append(',')
                        .append(String.format("%.2f", p[1])).append('\n');
            }
            if (rp.skeleton() != null) {
                for (int i = 0; i < rp.skeleton().length; i++) {
                    rc.append(rid).append(',').append(String.format("%.0f", rp.rootX())).append(',')
                            .append(String.format("%.0f", rp.rootZ())).append(",S,")
                            .append(i).append(',')
                            .append(String.format("%.2f", rp.skeleton()[i][0])).append(',')
                            .append(String.format("%.2f", rp.skeleton()[i][1])).append('\n');
                }
            }
        }
        Files.writeString(Path.of(outDir, "routes.csv"), rc.toString());
        System.out.println("wrote " + Path.of(outDir, "routes.csv").toAbsolutePath()
                + "   routes=" + rid + "  骨架匹配到 " + matched);

        // ---- ★ A/B/C 三图（用户要求：1=物理, 2=骨架, 3=合并）----
        //
        //   A-physics.png    只有物理动线（分色）
        //   B-skeleton.png   只有骨架（同色，便于与 A 逐条对照）
        //   C-overlay.png    两者叠加：物理=实线，骨架=同色虚线
        //   同色 = 同一条路线（同一源头出发）
        //
        // ★★★ 2026-10-21 修正【底图不纯】★★★
        //   用户：「你第一张物理路线图都不是纯的，叠加了之前旧的骨架路线」。
        //   原因：三图都用了 `base` = production.png，而**生产图里已经画了河格**（青色），
        //   于是 A 图（纯物理）上叠加着生产的河 → 看着像"骨架也画进来了"。
        //   修法：A/B/C **全部改用纯地形灰度底图**（只由 erodedHeight 生成，不含任何河）。
        //   C 图里因此只剩"物理 + 骨架"两种线，无第三者为干扰。
        BufferedImage terrainBg = renderTerrainBg(gen, hs, ox, oz, n);
        float pW = Float.parseFloat(System.getProperty("rp.partWidth", "2.0"));
        float sW = Float.parseFloat(System.getProperty("rp.skelWidth", "1.2"));

        // A：物理（纯地形底图）
        BufferedImage a = copy(terrainBg);
        Graphics2D ag = a.createGraphics();
        ag.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int ia = 0;
        for (RoutePair rp : pairs) {
            ag.setColor(routeColor(ia++));
            ag.setStroke(new BasicStroke(pW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawPath(ag, rp.particle(), ox, oz);
        }
        ag.dispose();
        ImageIO.write(a, "png", Path.of(outDir, "A-physics.png").toFile());

        // B：骨架（纯地形底图）
        BufferedImage b = copy(terrainBg);
        Graphics2D bg = b.createGraphics();
        bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int ib = 0;
        for (RoutePair rp : pairs) {
            Color c = routeColor(ib++);
            if (rp.skeleton() == null) continue;
            bg.setColor(c);
            bg.setStroke(new BasicStroke(sW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < rp.skeleton().length; i++) {
                bg.drawLine((int) Math.round(rp.skeleton()[i - 1][0] - ox),
                        (int) Math.round(rp.skeleton()[i - 1][1] - oz),
                        (int) Math.round(rp.skeleton()[i][0] - ox),
                        (int) Math.round(rp.skeleton()[i][1] - oz));
            }
        }
        bg.dispose();
        ImageIO.write(b, "png", Path.of(outDir, "B-skeleton.png").toFile());

        // C：合并（物理实线 + 骨架虚线，同色）
        BufferedImage ov = copy(terrainBg);
        Graphics2D og = ov.createGraphics();
        og.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int idx = 0;
        for (RoutePair rp : pairs) {
            Color c = routeColor(idx++);
            og.setColor(c);
            og.setStroke(new BasicStroke(pW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawPath(og, rp.particle(), ox, oz);
            if (rp.skeleton() != null) {
                og.setColor(c.darker());
                og.setStroke(new BasicStroke(sW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{5f, 4f}, 0f));
                for (int i = 1; i < rp.skeleton().length; i++) {
                    og.drawLine((int) Math.round(rp.skeleton()[i - 1][0] - ox),
                            (int) Math.round(rp.skeleton()[i - 1][1] - oz),
                            (int) Math.round(rp.skeleton()[i][0] - ox),
                            (int) Math.round(rp.skeleton()[i][1] - oz));
                }
            }
        }
        og.dispose();
        ImageIO.write(ov, "png", Path.of(outDir, "C-overlay.png").toFile());
        System.out.println("wrote A-physics.png / B-skeleton.png / C-overlay.png in "
                + Path.of(outDir).toAbsolutePath());
        System.out.println("   同色=同一条路线   实线=物理   虚线=骨架");
    }

    /**
     * <b>纯地形灰度底图</b> —— 只由 {@code erodedHeight} 生成，<b>不含任何河/湖/海</b>。
     *
     * <p>用户判据（2026-10-21）：「你第一张物理路线图都不是纯的，叠加了之前旧的骨架路线」。
     * 根因是三图都用了 {@code production.png} 当底图，而生产图里已经画了河格。
     * 本方法给出干净的底图，A/B/C 三图因此只含各自的线。</p>
     *
     * <p>色阶：取画幅内 min/max 线性拉伸到 [40, 245]，并叠 35% 白蒙版压淡，
     * 让彩色路线保持高对比。</p>
     */
    private static BufferedImage renderTerrainBg(CellGenerator gen, double hs,
                                                 int ox, int oz, int n) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        double[] h = new double[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double v = gen.erodedHeight((ox + x) / hs, (oz + y) / hs);
                h[y * n + x] = v;
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
        }
        double span = Math.max(1e-6, hi - lo);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int g = (int) Math.round((h[y * n + x] - lo) / span * 205 + 40);
                img.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        Graphics2D g2 = img.createGraphics();
        g2.setColor(new Color(255, 255, 255, 90));
        g2.fillRect(0, 0, n, n);
        g2.dispose();
        return img;
    }

    /**
     * <b>为每条路线生成唯一色相</b>（黄金角步进，避免相邻色混淆）。
     *
     * <p><b>为什么不能用固定色板</b>：旧版只有 12 色而路线有近 200 条
     * ⇒ 约 16 条共用一个颜色 ⇒ 图上「同色=同一条路线」是<b>假的</b>，
     * 用户看到的"红色在右上和左下两处"其实是不同的河。
     * 黄金角 {@code 0.6180339887} 让步进后的色相在圆周上均匀铺开。</p>
     */
    private static Color routeColor(int idx) {
        final double GOLDEN = 0.618033988749895;
        float hue = (float) ((idx * GOLDEN) % 1.0);
        // 交替明暗，进一步拉开相邻路线的可辨识度
        float bright = (idx % 2 == 0) ? 0.95f : 0.72f;
        return Color.getHSBColor(hue, 0.85f, bright);
    }

    private static BufferedImage copy(BufferedImage s) {
        BufferedImage o = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.drawImage(s, 0, 0, null);
        g.dispose();
        return o;
    }

    /** 折线累计行程（块）—— 用于区分"有效动线"与"短河"。 */
    private static double pathLength(List<double[]> path) {
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1), b = path.get(i);
            len += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return len;
    }

    /** 在当前 Graphics2D 的画笔颜色下画一条折线。 */
    private static void drawPath(Graphics2D g, List<double[]> path, int ox, int oz) {
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1), b = path.get(i);
            g.drawLine((int) Math.round(a[0] - ox), (int) Math.round(a[1] - oz),
                    (int) Math.round(b[0] - ox), (int) Math.round(b[1] - oz));
        }
    }

    /** 一条"配对路线"：同一源头出发的物理路径 + 与之最近的骨架折线。 */
    private record RoutePair(double rootX, double rootZ,
                             List<double[]> particle, double[][] skeleton) { }

    /**
     * 找出<b>起点离 (x,z) 最近</b>的骨架折线（该折线从同一源头出发）。
     *
     * <p>用户要求（2026-10-21）「每条路线都分色了，骨架路线和物理路线对比不出来吗」
     * ⇒ 必须能逐条配对，才能回答"这一条到底差在哪"，而不是只给全局百分比。</p>
     */
    private static double[][] nearestShapedContaining(List<double[][]> shaped, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        double[][] pick = null;
        for (double[][] pts : shaped) {
            if (pts.length < 2) continue;
            double d = Math.hypot(pts[0][0] - x, pts[0][1] - z);
            if (d < best) { best = d; pick = pts; }
        }
        return best <= 24 ? pick : null;      // 起点须在 24 块内才视为同一条
    }

    /** 出白线黑底掩码（度量用）。 */
    private static void writeMask(List<List<double[]>> paths, int n, int ox, int oz,
                                  Path out) throws Exception {
        BufferedImage mask = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        Graphics2D mg = mask.createGraphics();
        mg.setColor(Color.BLACK);
        mg.fillRect(0, 0, n, n);
        mg.setColor(Color.WHITE);
        mg.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (List<double[]> p : paths) drawPath(mg, p, ox, oz);
        mg.dispose();
        ImageIO.write(mask, "png", out.toFile());
    }

    // ------------------------------------------------------------------
    // 物理（对齐 ErosionEngine.simulateDrop）
    // ------------------------------------------------------------------

    private static List<double[]> trace(Heights h, double x, double z,
                                        int ox, int oz, int n, double seaLevel) {
        var path = new ArrayList<double[]>();
        path.add(new double[]{x, z});
        double dirX = 0, dirY = 0, spd = 1.0;
        int segAge = 0;
        final int POOL_WINDOW = 400;
        var recent = new java.util.ArrayDeque<double[]>();
        for (int i = 0; i < MAX_STEPS; i++) {
            if (segAge >= LIFE) { segAge = 0; spd = 1.0; }
            segAge++;
            double[] gr = h.gradient(x, z);
            if (Math.hypot(gr[0], gr[1]) < 1e-12) break;
            dirX = dirX * INERTIA - gr[0] * (1 - INERTIA);
            dirY = dirY * INERTIA - gr[1] * (1 - INERTIA);
            double dl = Math.hypot(dirX, dirY);
            if (dl < 1e-12) break;
            dirX /= dl; dirY /= dl;
            double h0 = h.at(x, z);
            spd = Math.sqrt(spd * spd + Math.abs(h.at(x + dirX, z + dirY) - h0) * GRAVITY);
            double move = Math.min(spd, SPD_CAP);
            x += dirX * move;
            z += dirY * move;
            path.add(new double[]{x, z});
            if (h.at(x, z) <= seaLevel + 0.5) break;                 // 入海
            if (x < ox || z < oz || x >= ox + n || z >= oz + n) break;
            recent.addLast(new double[]{x, z});
            if (recent.size() > POOL_WINDOW) recent.removeFirst();
            if (recent.size() == POOL_WINDOW) {
                double[] f0 = recent.peekFirst(), l = recent.peekLast();
                if (Math.hypot(l[0] - f0[0], l[1] - f0[1]) < 3.0) break;   // 积水
            }
        }
        return path;
    }

    private static final class Heights {
        final CellGenerator gen; final double hs;
        final java.util.Map<Long, Double> cache = new java.util.HashMap<>();

        Heights(CellGenerator gen, double hs) { this.gen = gen; this.hs = hs; }

        double at(double x, double z) {
            long k = ((long) Math.round(x) << 32) ^ (Math.round(z) & 0xffffffffL);
            Double v = cache.get(k);
            if (v != null) return v;
            double h = gen.erodedHeight(x / hs, z / hs);
            cache.put(k, h);
            return h;
        }

        /** 3x3 Sobel（同 ErosionEngine:619-624）。 */
        double[] gradient(double x, double z) {
            double hNW = at(x - 1, z - 1), hN = at(x, z - 1), hNE = at(x + 1, z - 1);
            double hW = at(x - 1, z), hE = at(x + 1, z);
            double hSW = at(x - 1, z + 1), hS = at(x, z + 1), hSE = at(x + 1, z + 1);
            double gx = (hNE + 2 * hE + hSE) - (hNW + 2 * hW + hSW);
            double gz = (hSW + 2 * hS + hSE) - (hNW + 2 * hN + hNE);
            return new double[]{gx / 8.0, gz / 8.0};
        }
    }
}
