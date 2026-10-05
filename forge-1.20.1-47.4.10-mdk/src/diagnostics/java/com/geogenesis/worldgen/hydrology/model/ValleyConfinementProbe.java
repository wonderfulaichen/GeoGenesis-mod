package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>河道是否走在地形沟壑里 —— 决定【平行直纹】是地形驱动还是欠分辨</h1>
 *
 * <h2>用户观察（本轮的设计前提）</h2>
 * <p>「上游少树的原因是地形沟壑方向是这样的，所以河流才这样。
 * 地形是会影响河流路线的」。</p>
 *
 * <p><b>这个观察如果成立，则上一轮的结论要再翻一次</b>：
 * 我曾把「高原上出现平行等距直纹」当成待修的伪影；
 * 但若地形本身就有<b>同向的平行沟壑</b>，
 * 那么河网平行 = <b>忠实反映地形</b>，不是水文缺陷。</p>
 *
 * <h2>★ 关键判据：谷约束度（valley confinement）</h2>
 * <p>对每个河道格，沿<b>垂直于流向</b>的方向（即横坡向）取 ±D 格的床面高程：</p>
 * <pre>
 *   若 bed[k] &lt; 两侧邻格  =&gt;  k 位于横坡向的局部最低点 =&gt; 【在沟里】
 * </pre>
 * <p><b>为什么这个判据能分辨两种情况</b>：</p>
 * <ul>
 *   <li><b>地形驱动</b>：沟壑存在 ⇒ 河道应<b>强烈</b>偏向沟底 ⇒
 *       谷约束度显著<b>高于</b>陆地全体基线；</li>
 *   <li><b>欠分辨</b>：地形在该尺度<b>没有</b>沟壑（采样分辨率低于沟壑波长，
 *       或沟壑被平滑掉）⇒ 河道只是「均匀斜面上各走各的」⇒
 *       谷约束度与陆地基线<b>无差别</b>。</li>
 * </ul>
 * <p>⇒ <b>必须与基线对比</b>，单看绝对值没有意义
 * （平地上随便哪条线都能找到局部低点）。</p>
 *
 * <h2>★ 第二个判据：间距匹配（spacing match）</h2>
 * <p>分别提取【河道线】与【沟壑线（横坡局部低点）】的横向间距分布。
 * 若二者<b>量级一致</b> ⇒ 河道间距由地形决定（正确）；
 * 若河道间距<b>远大于</b>沟壑间距 ⇒ 河道只是每 N 条沟才用一条（欠分辨）。</p>
 *
 * <h2>★ 第三个量：地形沟壑本身的波长（地形侧是否真有结构）</h2>
 * <p>沿横坡向对 bed 做自相关，量其<b>主波长</b>。
 * 若地形在相关尺度上<b>没有</b>显著自相关峰 ⇒ 该尺度上无沟壑
 * ⇒ 河道的周期性必来自算法（门槛/采样），而非地形。</p>
 *
 * <h2>输出</h2>
 * <p>{@code build/hydro-reassessment/confine-<ts>/}：{@code metrics.txt}
 * + {@code map.png}（等高线底图 + 沟壑线 + 河道线三者叠加，可肉眼直判）
 * + {@code zoom.png}（高密度区放大）。底图一律用无光照等高线。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。
 */
public final class ValleyConfinementProbe {

    /** 横坡向取样距离（格）。取 2 而非 1：1 太近，噪声也能满足局部最低。 */
    private static final int CROSS_D = 2;

    private ValleyConfinementProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 600_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        long t0 = System.nanoTime();
        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        double[] bed = r.bed();

        // 沟壑掩码：横坡向局部低点（对【所有陆地格】判定，不只河道）
        boolean[] valley = new boolean[cells];
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (r.sea()[k] || r.lake()[k]) continue;
                if (isCrossMin(bed, n, i, j)) valley[k] = true;
            }
        }

        // 谷约束度：河道格 vs 陆地格基线
        int chInValley = 0, chTotal = 0;
        int landInValley = 0, landTotal = 0;
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (r.sea()[k] || r.lake()[k]) continue;
                landTotal++;
                if (valley[k]) landInValley++;
                if (r.channel()[k]) {
                    chTotal++;
                    if (valley[k]) chInValley++;
                }
            }
        }
        double chConf = 100.0 * chInValley / Math.max(1, chTotal);
        double landConf = 100.0 * landInValley / Math.max(1, landTotal);

        // 间距分布：逐行扫描，记录相邻【游程】之间的空白格数
        int[] chGaps = gapsPerRow(r.channel(), n);
        int[] valGaps = gapsPerRow(valley, n);

        // 地形横坡向自相关（量沟壑波长）—— 只取陆地
        double[] acf = crossAutocorr(bed, n, r.sea(), 64);

        StringBuilder log = new StringBuilder();
        log.append("=== 谷约束度：河道是否走在地形沟壑里 ===\n");
        log.append(String.format("seed=%d radius=%d step=%d (%.0fs)%n",
                seed, radius, ViewSpec.STEP, (System.nanoTime() - t0) / 1e9));
        log.append(String.format("CROSS_D=%d（横坡向取样距离）%n%n", CROSS_D));
        log.append(String.format("[1] 谷约束度：河道格=%.2f%%  陆地基线=%.2f%%  超出=%.2fx  (河道格=%d)%n",
                chConf, landConf, landConf > 0 ? chConf / landConf : Double.NaN, chTotal));
        log.append(String.format("[2] 河道线横向间距(格): n=%d 中位=%.0f P90=%.0f max=%d%n",
                chGaps.length, med(chGaps), pct(chGaps, 0.90), max(chGaps)));
        log.append(String.format("    沟壑线横向间距(格): n=%d 中位=%.0f P90=%.0f max=%d%n",
                valGaps.length, med(valGaps), pct(valGaps, 0.90), max(valGaps)));
        log.append("[3] 地形横坡向自相关（找沟壑主波长）:\n");
        log.append(String.format("    lag: "));
        for (int lag = 1; lag <= 48; lag++) {
            if (acf[lag] < 0) break;
            if (lag % 6 == 0 || lag <= 8) log.append(String.format("L%d=%.3f ", lag, acf[lag]));
        }
        log.append("\n");
        int peak = peakLag(acf);
        log.append(String.format("    主波长 ≈ %d 格（自相关首个极大）%n", peak));
        double chMed = med(chGaps), valMed = med(valGaps);
        log.append(String.format("%n★ 判读%n"));
        if (chConf > landConf * 1.5) {
            log.append(String.format("  谷约束度是基线的 %.2f 倍 ⇒ 【河道明显走在沟壑里】。%n",
                    chConf / landConf));
            log.append("  ⇒ 平行直纹是【地形驱动】的，符合用户观察：地形决定河流路线。\n");
            log.append("  ⇒ 水文侧无需修改；若要改的是【地形沟壑本身】，那是地形设计议题。\n");
        } else {
            log.append(String.format("  谷约束度仅为基线的 %.2f 倍 ⇒ 河道【并未】走在沟壑里。%n",
                    chConf / Math.max(1e-9, landConf)));
            log.append("  河道是【均匀斜面上各自独立成河】，属欠分辨现象。\n");
            log.append("  ⇒ 这才是真缺陷，修法在【采样分辨率/地形细节】或【成河判定的汇流加权】。\n");
        }

        Path dir = Path.of("build/hydro-reassessment/confine-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(render(r, n, valley), "png", dir.resolve("map.png").toFile());
        ImageIO.write(zoom(r, n, valley), "png", dir.resolve("zoom.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("map.png").toAbsolutePath());
        System.out.println("ZOOM=" + dir.resolve("zoom.png").toAbsolutePath());
    }

    /**
     * 是否为【横坡向】局部最低点。
     * <p>横坡向 = 垂直于床面梯度 ⇒ 沿它找最低点，才等价于【在沟里】。
     * 若沿梯度方向找最低点，任何格都会「自己」是最低点（判据失效）。</p>
     */
    private static boolean isCrossMin(double[] bed, int n, int i, int j) {
        double gx = (bed[(i + 1) * n + j] - bed[(i - 1) * n + j]) / 2.0;
        double gz = (bed[i * n + (j + 1)] - bed[i * n + (j - 1)]) / 2.0;
        double g = Math.hypot(gx, gz);
        if (g < 1e-9) return false;          // 无坡度 => 无横坡方向 => 不判
        double px = -gz / g, pz = gx / g;     // 垂直于梯度
        double here = bed[i * n + j];
        for (int s = -CROSS_D; s <= CROSS_D; s++) {
            if (s == 0) continue;
            int a = i + (int) Math.round(s * px);
            int b = j + (int) Math.round(s * pz);
            if (a < 0 || b < 0 || a >= n || b >= n) continue;
            if (bed[a * n + b] < here) return false;
        }
        return true;
    }

    /** 逐行扫描掩码，返回相邻游程之间的空白长度（格）。 */
    private static int[] gapsPerRow(boolean[] m, int n) {
        java.util.List<Integer> g = new java.util.ArrayList<>();
        for (int j = 0; j < n; j++) {
            // ⚠ 上一版用 prevRun 同时表示"当前在游程内"与"游程结束后进入空白"，
            //   进入空白时就把 prevRun 置 false ⇒ 下次遇到 true 时 `prevRun` 恒为 false
            //   ⇒ 条件永不成立 ⇒ n=0（全 NaN）。教训：**一个标志位不能表达两个状态**。
            boolean inRun = false, hadRunBefore = false;
            int gap = 0;
            for (int i = 0; i < n; i++) {
                boolean v = m[i * n + j];
                if (v) {
                    if (hadRunBefore && gap > 0) g.add(gap);
                    inRun = true; hadRunBefore = true; gap = 0;
                } else {
                    if (inRun) { inRun = false; gap = 1; } else if (gap > 0) gap++;
                }
            }
        }
        int[] out = new int[g.size()];
        for (int t = 0; t < out.length; t++) out[t] = g.get(t);
        java.util.Arrays.sort(out);
        return out;
    }

    /**
     * 床面沿【横坡向】的归一化自相关。
     * <p>用固定方向（x）近似横坡向：在有明确坡向的地形上，
     * 沿 x 的起伏主要就是沟壑的横向起伏 ⇒ 其自相关主波长即沟壑波长。</p>
     */
    private static double[] crossAutocorr(double[] bed, int n, boolean[] sea, int maxLag) {
        double[] c = new double[maxLag + 1];
        for (int lag = 1; lag <= maxLag; lag++) {
            double sxy = 0, sx = 0, sy = 0, sxx = 0, syy = 0;
            int m = 0;
            for (int i = 0; i + lag < n; i++) {
                for (int j = 1; j < n - 1; j++) {
                    int k = i * n + j, k2 = (i + lag) * n + j;
                    if (sea[k] || sea[k2]) continue;
                    double x = bed[k], y = bed[k2];
                    sxy += x * y; sx += x; sy += y; sxx += x * x; syy += y * y; m++;
                }
            }
            // ⚠ 上一版 m<100 判据对 lag=64 仍可能触发（601×601 的 r=300 窗口够），
            //   但在 r=700 下 lag=48 已只剩 ~1350×599 个样本 —— 足够。
            //   真正的 NaN 来源是 den≈0（床面沿 x 近乎无变化）⇒ 相关系数无定义。
            //   此时应报告"该 lag 无信息"，而不是把 NaN 传给下游分位计算。
            if (m < 50) { c[lag] = Double.NaN; continue; }
            double cov = sxy / m - (sx / m) * (sy / m);
            double vx = sxx / m - (sx / m) * (sx / m);
            double vy = syy / m - (sy / m) * (sy / m);
            double den = Math.sqrt(Math.max(0, vx * vy));
            c[lag] = den > 1e-9 ? cov / den : 0.0;   // 无起伏 ⇒ 相关记 0（非 NaN）
        }
        return c;
    }

    private static int peakLag(double[] acf) {
        for (int lag = 2; lag < acf.length; lag++) {
            if (acf[lag] < 0) return -1;
            if (acf[lag] > acf[lag - 1] && acf[lag] >= acf[lag + 1]) return lag;
        }
        return -1;
    }

    private static double med(int[] a) { return pct(a, 0.50); }

    private static double pct(int[] a, double q) {
        if (a.length == 0) return Double.NaN;
        int i = (int) Math.round(q * (a.length - 1));
        return a[Math.max(0, Math.min(a.length - 1, i))];
    }

    private static int max(int[] a) { return a.length == 0 ? 0 : a[a.length - 1]; }

    /**
     * 三层叠加：等高线底图 + 沟壑线(暗绿) + 河道线(青)。
     * <p>目的：<b>一眼看出河道是否压在沟壑线上</b> ——
     * 这是本探针的最终依据（渲染图优先于数值，项目铁律）。</p>
     */
    private static BufferedImage render(HydrologyPipeline.Result r, int n, boolean[] valley) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (double v : r.bed()) { hMin = Math.min(hMin, v); hMax = Math.max(hMax, v); }
        double band = Math.max(4.0, (hMax - hMin) / 24.0);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int g = ViewSpec.contour(r.bed(), n, i, j, hMin, band);
                if (valley[k]) g = darken(g, 0.45f, 0x00FF60);
                if (r.channel()[k]) g = 0x00E5D0;
                else if (r.lake()[k]) g = ViewSpec.LAKE_COLOR;
                else if (r.sea()[k]) g = ViewSpec.SEA_COLOR;
                img.setRGB(i, j, g);
            }
        }
        return img;
    }

    /** 高密度区放大：河道格最密的 400×400 窗口。 */
    private static BufferedImage zoom(HydrologyPipeline.Result r, int n, boolean[] valley) {
        int W = 400, best = -1, bx = 0, by = 0, bestCnt = -1;
        for (int i = 0; i + W < n; i += 50) {
            for (int j = 0; j + W < n; j += 50) {
                int cnt = 0;
                for (int a = i; a < i + W; a += 2) {
                    for (int b = j; b < j + W; b += 2) {
                        if (r.channel()[a * n + b]) cnt++;
                    }
                }
                if (cnt > bestCnt) { bestCnt = cnt; bx = i; by = j; }
            }
        }
        BufferedImage out = new BufferedImage(W, W, BufferedImage.TYPE_INT_RGB);
        BufferedImage full = render(r, n, valley);
        out.getGraphics().drawImage(full, -bx, -by, null);
        return out;
    }

    private static int darken(int rgb, float f, int tintMask) {
        int rr = (int) (((rgb >> 16) & 0xFF) * f);
        int gg = (int) (((rgb >> 8) & 0xFF) * f);
        int bb = (int) ((rgb & 0xFF) * f);
        if ((tintMask & 0xFF00) != 0) gg = Math.min(255, gg + 40);
        return (rr << 16) | (gg << 8) | bb;
    }
}