package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.TectonicField;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code orogen} / {@code rift} 两个构造剖面的<b>形态探针</b>（只读，不接生产）。
 *
 * <h3>这两个场是什么</h3>
 * <pre>
 *   orogen = smoothPos(stress)  × gaussian(dist, σ)      // 汇聚带：造山
 *   rift   = smoothPos(−stress) × gaussian(dist, σ)      // 拉张带：裂谷/盆地
 * </pre>
 * <p>{@code stress} 与 {@code dist} 都取自 {@link TectonicField.Sample}，两者都是
 * <b>连续量</b>（项目已实测：{@code stress} 是加权投票构造、{@code dist} 是
 * {@code (d2−d1)/2} 加局部环形平均）。这里<b>不用</b> {@code btype}/{@code rate}
 * —— 它们是分片常数/离散标签，项目已明令禁止参与地形合成。</p>
 *
 * <h3>为什么必须先做探针</h3>
 * <p>本项目历史上三次因 {@code dist} 出伪影（{@code chainModulation} 同心环、
 * {@code arcVolcanism} 直线疤痕、{@code TectonicDeformation} 断层崖）。
 * 那三次的用法都是<b>振荡</b>或<b>阈值</b>；本探针的用法是<b>平滑单峰包络</b>
 * （单调、无周期），理论上安全，但必须实测确认。判据三条：</p>
 * <ol>
 *   <li><b>无平行直线/同心环</b>：看 {@code |∇|·σ} 的长尾比（平滑场 ~3~8；含折痕极大），
 *       以及沿边界的法向/切向各向异性。</li>
 *   <li><b>带状伸长比</b>：连通分量的面积加权伸长比应显著大于 1（现实造山带 5–20；
 *       当前各向同性类型场只有 1.3–2.0）。</li>
 *   <li><b>值域合理</b>：两场峰值 ≈ 1、内部 ≈ 0。</li>
 * </ol>
 *
 * <p>用法：{@code <toml> <outDir> <size> <step> <cx> <cz> [sigma]}</p>
 */
public final class OrogenFieldProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int size = Integer.parseInt(args[2]);
        int step = Integer.parseInt(args[3]);
        double cx = Double.parseDouble(args[4]);
        double cz = Double.parseDouble(args[5]);
        double[] sigmas = args.length > 6
                ? new double[]{Double.parseDouble(args[6])}
                : new double[]{35, 55, 80};
        Files.createDirectories(out);

        TerrainParams params = ProbeSupport.loadParams(toml, out);
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);
        TectonicField tectonic = new TectonicField(SEED);
        tectonic.setSeed(SEED);

        for (double sigma : sigmas) {
            String tag = String.format(Locale.ROOT, "s%02.0f", sigma);
            double[] orogen = new double[size * size];
            double[] rift = new double[size * size];
            double[] dist = new double[size * size];

            double oMin = Double.MAX_VALUE, oMax = -Double.MAX_VALUE;
            double rMin = Double.MAX_VALUE, rMax = -Double.MAX_VALUE;

            for (int z = 0; z < size; z++) {
                double wz = cz + (z - size / 2.0) * step;
                for (int x = 0; x < size; x++) {
                    double wx = cx + (x - size / 2.0) * step;
                    TectonicField.Sample s = tectonic.sample(wx, wz);
                    double d = s.dist();
                    double g = Math.exp(-(d * d) / (2.0 * sigma * sigma));
                    double o = smoothPos(s.stress(), TectonicField.STRESS_POS_EPS) * g;
                    double r = smoothPos(-s.stress(), TectonicField.STRESS_POS_EPS) * g;
                    int i = z * size + x;
                    orogen[i] = o;
                    rift[i] = r;
                    dist[i] = d;
                    if (o < oMin) oMin = o;
                    if (o > oMax) oMax = o;
                    if (r < rMin) rMin = r;
                    if (r > rMax) rMax = r;
                }
            }
            writeRaw(out.resolve("orogen-" + tag + ".f64be.raw"), orogen);
            writeRaw(out.resolve("rift-" + tag + ".f64be.raw"), rift);
            if (sigma == sigmas[0]) writeRaw(out.resolve("dist-" + tag + ".f64be.raw"), dist);

            report("orogen", tag, orogen, size, step, oMin, oMax);
            report("rift", tag, rift, size, step, rMin, rMax);
        }

        Files.writeString(out.resolve("probe-geometry.json"), String.format(Locale.ROOT,
                "{\"size\":%d,\"stepBlocks\":%d,\"centerBlocks\":[%.1f,%.1f],\"sigmas\":%s}%n",
                size, step, cx, cz, java.util.Arrays.toString(sigmas)));
        System.out.println("out=" + out.toAbsolutePath());
    }

    /** 与 {@code TectonicField.smoothPos} 同义（那边是 public static）。 */
    private static double smoothPos(double v, double eps) {
        return TectonicField.smoothPos(v, eps);
    }

    /**
     * 打印形态统计：值域、有效区内的 |∇| 长尾比、方向各向异性、连通分量伸长比。
     *
     * <p><b>为什么必须只在有效区内统计</b>：{@code orogen} 远离边界处恒为 0
     * （{@code gaussian(dist)} 下溢），整幅图 P50 会是 1e-44 这类数值 ⇒
     * 长尾比 Inf、各向异性恒 1.00 全部失去意义。首版就踩了这个坑。
     * 现在用 {@code v >= 峰值×1%} 作有效区掩码，所有统计只在该掩码内做。</p>
     */
    private static void report(String name, String tag, double[] v, int size, int step,
                               double lo, double hi) {
        double peak = Math.max(Math.abs(lo), Math.abs(hi));
        double maskThr = peak * 0.01;
        int valid = 0;
        for (double x : v) if (x >= maskThr) valid++;
        double validFrac = 100.0 * valid / v.length;

        // 1) 有效区内的 |∇|
        double[] grad = new double[size * size];
        for (int z = 1; z < size - 1; z++) {
            for (int x = 1; x < size - 1; x++) {
                int i = z * size + x;
                if (v[i] < maskThr) { grad[i] = -1; continue; }
                double gx = (v[i + 1] - v[i - 1]) / (2.0 * step);
                double gz = (v[i + size] - v[i - size]) / (2.0 * step);
                grad[i] = Math.hypot(gx, gz);
            }
        }
        double[] sorted = new double[Math.max(1, valid)];
        int k = 0;
        for (double g : grad) if (g >= 0 && k < sorted.length) sorted[k++] = g;
        java.util.Arrays.sort(sorted, 0, k);
        double p50 = k > 0 ? sorted[k / 2] : 0;
        double p999 = k > 0 ? sorted[Math.min(k - 1, (int) (0.999 * k))] : 0;
        double tailRatio = p50 > 1e-12 ? p999 / p50 : 0;

        // 2) 方向各向异性（同样只在有效区内）
        double aniso = anisotropy(v, size, step, maskThr);

        // 3) 连通分量伸长比（阈值 = 峰值的 15%，比有效区掩码高，只取带状主体）
        double thr = peak * 0.15;
        double elong = weightedElongation(v, size, thr);
        int comps = countComponents(v, size, thr);

        System.out.printf(Locale.ROOT,
                "%-7s %-4s 峰值=%.3f 有效区=%.1f%% | 有效|g|P50=%.2e P99.9=%.2e 长尾比=%.1f "
                        + "| 各向异性=%.2f | 伸长比=%.2f 分量=%d%n",
                name, tag, peak, validFrac, p50, p999, tailRatio, aniso, elong, comps);
    }

    private static int countComponents(double[] v, int size, double thr) {
        boolean[] seen = new boolean[v.length];
        int[] stack = new int[v.length];
        int comps = 0;
        for (int start = 0; start < v.length; start++) {
            if (seen[start] || v[start] < thr) continue;
            int sp = 0, cnt = 0;
            stack[sp++] = start;
            seen[start] = true;
            while (sp > 0) {
                int i = stack[--sp];
                cnt++;
                int x = i % size, z = i / size;
                if (x > 0 && !seen[i - 1] && v[i - 1] >= thr) { seen[i - 1] = true; stack[sp++] = i - 1; }
                if (x < size - 1 && !seen[i + 1] && v[i + 1] >= thr) { seen[i + 1] = true; stack[sp++] = i + 1; }
                if (z > 0 && !seen[i - size] && v[i - size] >= thr) { seen[i - size] = true; stack[sp++] = i - size; }
                if (z < size - 1 && !seen[i + size] && v[i + size] >= thr) { seen[i + size] = true; stack[sp++] = i + size; }
            }
            if (cnt >= 40) comps++;
        }
        return comps;
    }

    /**
     * 方向各向异性：用结构张量求主导梯度方向，再比较"沿主导方向"与"垂直方向"的 |∇| 均值。
     *
     * <p>若场是"沿边界成带"的，梯度几乎全部指向<b>跨带</b>方向 ⇒ 比值应显著 &gt; 1。
     * 若场含平行直线伪影，梯度方向会高度集中在少数几个离散角度 ⇒ 比值极大。</p>
     */
    private static double anisotropy(double[] v, int size, int step, double maskThr) {
        double jxx = 0, jzz = 0, jxz = 0;
        for (int z = 1; z < size - 1; z++) {
            for (int x = 1; x < size - 1; x++) {
                int i = z * size + x;
                if (v[i] < maskThr) continue;
                double gx = (v[i + 1] - v[i - 1]) / (2.0 * step);
                double gz = (v[i + size] - v[i - size]) / (2.0 * step);
                jxx += gx * gx;
                jzz += gz * gz;
                jxz += gx * gz;
            }
        }
        // 主方向角度（结构张量最大特征向量）
        double theta = 0.5 * Math.atan2(2 * jxz, jxx - jzz);
        double ux = Math.cos(theta), uz = Math.sin(theta);
        double along = 0, across = 0;
        int n = 0;
        for (int z = 1; z < size - 1; z++) {
            for (int x = 1; x < size - 1; x++) {
                int i = z * size + x;
                if (v[i] < maskThr) continue;
                double gx = (v[i + 1] - v[i - 1]) / (2.0 * step);
                double gz = (v[i + size] - v[i - size]) / (2.0 * step);
                if (gx * gx + gz * gz < 1e-20) continue;
                along += Math.abs(gx * ux + gz * uz);
                across += Math.abs(-gx * uz + gz * ux);
                n++;
            }
        }
        return n == 0 ? 1.0 : (along / n) / Math.max(1e-20, across / n);
    }

    /**
     * 连通分量的面积加权伸长比（阈值以上为前景，4-连通）。
     *
     * <p>伸长比 = sqrt(λ1/λ2)，λ 为主成分方差。各向同性圆形 ≈ 1；
     * 现实造山带（长 2000km、宽 200km）≈ 10。</p>
     */
    private static double weightedElongation(double[] v, int size, double thr) {
        boolean[] seen = new boolean[v.length];
        int[] stack = new int[v.length];
        double sumE = 0, sumA = 0;
        for (int start = 0; start < v.length; start++) {
            if (seen[start] || v[start] < thr) continue;
            int sp = 0;
            stack[sp++] = start;
            seen[start] = true;
            double sx = 0, sz = 0;
            int cnt = 0;
            // 第一遍：收集点并求均值
            int tail = 0;
            int[] pts = new int[1024];
            while (sp > 0) {
                int i = stack[--sp];
                if (tail == pts.length) pts = java.util.Arrays.copyOf(pts, tail * 2);
                pts[tail++] = i;
                int x = i % size, z = i / size;
                sx += x;
                sz += z;
                cnt++;
                if (x > 0 && !seen[i - 1] && v[i - 1] >= thr) { seen[i - 1] = true; stack[sp++] = i - 1; }
                if (x < size - 1 && !seen[i + 1] && v[i + 1] >= thr) { seen[i + 1] = true; stack[sp++] = i + 1; }
                if (z > 0 && !seen[i - size] && v[i - size] >= thr) { seen[i - size] = true; stack[sp++] = i - size; }
                if (z < size - 1 && !seen[i + size] && v[i + size] >= thr) { seen[i + size] = true; stack[sp++] = i + size; }
            }
            if (cnt < 40) continue;
            double mx = sx / cnt, mz = sz / cnt;
            double cxx = 0, czz = 0, cxz = 0;
            for (int k = 0; k < tail; k++) {
                int i = pts[k];
                double dx = i % size - mx, dz = i / size - mz;
                cxx += dx * dx;
                czz += dz * dz;
                cxz += dx * dz;
            }
            cxx /= cnt; czz /= cnt; cxz /= cnt;
            double tr = cxx + czz, det = cxx * czz - cxz * cxz;
            double disc = Math.max(0, tr * tr / 4 - det);
            double l1 = tr / 2 + Math.sqrt(disc);
            double l2 = Math.max(1e-9, tr / 2 - Math.sqrt(disc));
            double e = Math.sqrt(l1 / l2);
            sumE += e * cnt;
            sumA += cnt;
        }
        return sumA > 0 ? sumE / sumA : 0;
    }

    private static void writeRaw(Path path, double[] values) throws Exception {
        try (DataOutputStream s = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
            for (double v : values) s.writeDouble(v);
        }
    }
}