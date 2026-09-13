package com.geogenesis.worldgen.terrain;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Function;
import javax.imageio.ImageIO;

/**
 * ★ 2026-09-14：<b>陆地火山特征值场 PNG 目检</b>（用户反馈"火山地形出现被切掉一块的不自然情况"）。
 *
 * <h3>为何需要本探针</h3>
 * <p>用户实测截图显示火山区域内部出现<b>笔直竖线/斜线</b>（"被切掉一块"）。
 * 文本探针能给出数值，但"直线"是<b>几何</b>现象，必须用几何方式定位。
 * 本探针直接渲染 {@link LandFeatures} 的各分量与其梯度，一眼看出：
 * <ul>
 *   <li>是 {@code singleEdifice}（单体）还是 {@code fieldEdifice}（火山区）出现直线；</li>
 *   <li>直线的位置与格子边界（{@code SINGLE_GRID=800} / {@code FIELD_GRID=200}）是否对齐
 *       ⇒ 判断是否为"粗格点哈希"的量化痕迹；</li>
 *   <li>是"值突变（折痕）"还是"值平坦（台面）"—— 两者视觉上都像"被切"，但成因不同。</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * gradlew runLandFeatProbe -PprobeArgs="5436529513624899584 1077 -833"
 * }</pre>
 * 输出到 {@code build/landfeat/}，文件名带坐标标签。
 */
public final class LandFeatProbe {

    public static void main(String[] args) throws Exception {
        TerrainParams p = TerrainParams.defaults();
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int ox = args.length > 1 ? Integer.parseInt(args[1]) : 1077;
        int oz = args.length > 2 ? Integer.parseInt(args[2]) : -833;
        int W = 800, H = 800;

        System.out.printf("=== LandFeatProbe seed=%d origin=(%d,%d) size=%dx%d ===%n", seed, ox, oz, W, H);

        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);

        double[][] single = new double[W][H];
        double[][] field = new double[W][H];
        double[][] total = new double[W][H];
        double[][] eArr = new double[W][H];
        double[][] gate = new double[W][H];      // arcVolcanism（门控本身，用于判断直线是否来自门控）
        double[][] distArr = new double[W][H];   // 构造 dist（Voronoi 距离，其等值线是直线）
        TectonicField tf = new TectonicField(seed);

        long t0 = System.currentTimeMillis();
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double wx = ox + x, wz = oz + z;
                Cell c = gen.sample(wx, wz);
                LandFeatures.FeatureResult lf = c.landFeat;
                single[x][z] = lf != null ? lf.singleEdifice : 0;
                field[x][z] = lf != null ? lf.fieldEdifice : 0;
                total[x][z] = lf != null ? lf.total : 0;
                eArr[x][z] = c.e;
                TectonicField.Sample s = tf.sample(wx, wz);
                gate[x][z] = tf.arcVolcanism(s);
                distArr[x][z] = s.dist();
            }
        }
        System.out.printf("采样完成 %d ms%n", System.currentTimeMillis() - t0);

        File outDir = new File("build/landfeat");
        outDir.mkdirs();
        String tag = "_" + ox + "_" + oz;

        Function<double[][], double[][]> grad = v -> {
            double[][] g = new double[W][H];
            for (int x = 1; x < W - 1; x++) {
                for (int z = 1; z < H - 1; z++) {
                    double dx = (v[x + 1][z] - v[x - 1][z]) * 0.5;
                    double dz = (v[x][z + 1] - v[x][z - 1]) * 0.5;
                    g[x][z] = Math.hypot(dx, dz);
                }
            }
            return g;
        };

        dump(outDir, "single" + tag, single);
        dump(outDir, "field" + tag, field);
        dump(outDir, "total" + tag, total);
        dump(outDir, "e" + tag, eArr);
        dump(outDir, "gate" + tag, gate);
        dump(outDir, "dist" + tag, distArr);
        dump(outDir, "single_grad" + tag, grad.apply(single));
        dump(outDir, "field_grad" + tag, grad.apply(field));
        dump(outDir, "e_grad" + tag, grad.apply(eArr));

        // ===== 直线性量化：沿 x 方向的"台阶"计数 =====
        //   直线的特征是"沿某一方向存在长距离的同一数值/同一跳变"。
        //   本段统计：x 方向相邻样点的跳变 > 阈值的列数（若某些 x 列整体跳变大 ⇒ 竖线）。
        for (String nm : new String[]{"single", "field", "gate", "dist"}) {
            double[][] v = nm.equals("single") ? single : nm.equals("field") ? field
                    : nm.equals("gate") ? gate : distArr;
            int maxColJump = 0; int maxColX = 0;
            int[] colJumps = new int[W];
            for (int x = 1; x < W - 1; x++) {
                int cnt = 0;
                for (int z = 0; z < H; z++) {
                    if (Math.abs(v[x + 1][z] - v[x - 1][z]) > 0.004) cnt++;
                }
                colJumps[x] = cnt;
                if (cnt > maxColJump) { maxColJump = cnt; maxColX = x; }
            }
            // 中位数列跳变（基线）与最大列的比值 —— 竖线的特征是某列远超其它列
            int[] sorted = colJumps.clone();
            java.util.Arrays.sort(sorted);
            int med = sorted[W / 2];
            System.out.printf("[竖线指标] %-8s 最大列跳变=%d @x=%d | 中位=%d | 比值=%.1f%n",
                    nm, maxColJump, ox + maxColX, med, med > 0 ? (double) maxColJump / med : 0.0);
        }

        // ===== 全局搜索：在更大范围找"最强各向异性梯度"（竖线/斜线）位置 =====
        System.out.println("[全局搜索] 扫描 ±3000wu 找最强各向异性梯度（陆上火山内）...");
        double worstAniso = 0, bx = 0, bz = 0;
        int anisoCnt = 0;
        for (double z = oz - 3000; z <= oz + 3000; z += 10) {
            for (double x = ox - 3000; x <= ox + 3000; x += 10) {
                Cell cA = gen.sample(x + 4, z), cB = gen.sample(x - 4, z);
                if (!(cA.e > 0.05 && cB.e > 0.05)) continue;
                boolean volA = cA.landFeat != null && (cA.landFeat.singleEdifice > 0.02
                        || cA.landFeat.fieldEdifice > 0.02);
                boolean volB = cB.landFeat != null && (cB.landFeat.singleEdifice > 0.02
                        || cB.landFeat.fieldEdifice > 0.02);
                if (!volA && !volB) continue;
                double gx = Math.abs(cA.e - cB.e) * 0.125;
                double gz = Math.abs(gen.sample(x, z + 4).e - gen.sample(x, z - 4).e) * 0.125;
                if (gx < 2.5 * gz && gz < 2.5 * gx) continue;   // 非各向异性
                double aniso = Math.max(gx, gz) / (Math.min(gx, gz) + 1e-6);
                if (aniso > 4.0 && Math.max(gx, gz) > 0.003) {
                    anisoCnt++;
                    if (Math.max(gx, gz) > worstAniso) {
                        worstAniso = Math.max(gx, gz); bx = x; bz = z;
                    }
                }
            }
        }
        System.out.printf("[全局搜索] 各向异性点=%d | 最强 (%.0f, %.0f) 梯度=%.5f e/wu (≈%.1f格/wu) ratio=%.1f%n",
                anisoCnt, bx, bz, worstAniso, worstAniso * 236.0,
                worstAniso > 0 ? 1.0 : 0.0);
        if (anisoCnt > 0) {
            System.out.println("  该处横切（沿 x, step 4wu）: 详见各分量在断崖处的行为");
            System.out.printf("  %8s %9s %9s %9s %9s %8s %8s %8s %8s%n",
                    "x", "e", "eLand", "single", "field", "gate", "stress", "dist", "type");
            for (double d = -20; d <= 20; d += 4) {
                double xx = bx + d;
                Cell cc = gen.sample(xx, bz);
                TectonicField.Sample ss = tf.sample(xx, bz);
                System.out.printf("  %8.0f %+9.4f %+9.4f %9.4f %9.4f %8.4f %+8.4f %8.1f %s%n",
                        xx, cc.e, cc.eLand,
                        cc.landFeat != null ? cc.landFeat.singleEdifice : 0,
                        cc.landFeat != null ? cc.landFeat.fieldEdifice : 0,
                        tf.arcVolcanism(ss), ss.stress(), ss.dist(), cc.terrainType);
            }
            System.out.println("  纵切（沿 z, step 4wu）—— 若竖线则此方向应平滑:");
            for (double d = -20; d <= 20; d += 8) {
                double zz = bz + d;
                Cell cc = gen.sample(bx, zz);
                TectonicField.Sample ss = tf.sample(bx, zz);
                System.out.printf("  %8.0f %+9.4f %+9.4f %9.4f %9.4f %8.4f %+8.4f %8.1f %s%n",
                        zz, cc.e, cc.eLand,
                        cc.landFeat != null ? cc.landFeat.singleEdifice : 0,
                        cc.landFeat != null ? cc.landFeat.fieldEdifice : 0,
                        tf.arcVolcanism(ss), ss.stress(), ss.dist(), cc.terrainType);
            }

            // ===== ★ 决定性 A/B：有/无【中心海陆门控】的同一位置对比 =====
            //   若"无门控"平滑而"有门控"断崖 ⇒ 缺陷由本次 P5 的中心门控引入；
            //   若两者都有断崖 ⇒ 缺陷在改造前就存在（与本次改动无关）。
            LandFeatures lfNoGate = new LandFeatures();
            lfNoGate.seed(seed);
            lfNoGate.setArcVolcanismProvider(tf::arcVolcanism);        // 不注入 landFactor ⇒ cf≡1
            LandFeatures lfWithGate = new LandFeatures();
            lfWithGate.seed(seed);
            lfWithGate.setArcVolcanismProvider(tf::arcVolcanism);
            lfWithGate.setLandFactorProvider(gen::landFactorAt);       // 本次 P5 的中心门控

            System.out.println("  ★ A/B（无门控 vs 有门控）:");
            System.out.printf("  %8s %12s %12s %10s%n", "x", "field_noGate", "field_gate", "cf(中心)");
            for (double d = -24; d <= 24; d += 4) {
                double xx = bx + d;
                TectonicField.Sample ss = tf.sample(xx, bz);
                double fNo = lfNoGate.compute(xx, bz, ss).fieldEdifice;
                double fYes = lfWithGate.compute(xx, bz, ss).fieldEdifice;
                System.out.printf("  %8.0f %12.4f %12.4f%n", xx, fNo, fYes);
            }
        }

        System.out.println("输出目录: " + outDir.getAbsolutePath());
    }

    private static void dump(File dir, String name, double[][] v) {
        int w = v.length, h = v[0].length;
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double[] col : v) {
            for (double val : col) { mn = Math.min(mn, val); mx = Math.max(mx, val); }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        double span = mx - mn;
        if (span <= 1e-12) span = 1;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                int g = (int) Math.round(255.0 * (v[x][z] - mn) / span);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                img.getRaster().setSample(x, z, 0, g);
            }
        }
        try {
            ImageIO.write(img, "png", new File(dir, name + ".png"));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        System.out.printf("  [png] %-24s 值域=[%.4f, %.4f]%n", name, mn, mx);
    }
}
