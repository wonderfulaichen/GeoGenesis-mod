package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>洋中脊分量分解渲染（2026-11-17）</b>——回答「你确定没错？我怎么感觉像海沟？」
 *
 * <p>把最终 e 拆成四条独立通道分别上色渲染，一眼看出「亮带」「黑线」分别是谁的贡献：</p>
 * <ol>
 *   <li><b>age-e</b>：年龄律热沉降（不含 ridge / 裂谷 / 海沟 / 丘陵）</li>
 *   <li><b>ridge</b>：洋中脊体形分量（{@code OceanFeatures.ridge}）</li>
 *   <li><b>rift</b>：轴部裂谷 + 海沟分量（{@code CrustField} 的负部，含
 *       {@link RidgeWander} 偏移）</li>
 *   <li><b>final e</b>：真实产出（= 以上三项 + 丘陵 + 特征）</li>
 * </ol>
 *
 * <p>判据（自动，不靠人眼）：</p>
 * <ul>
 *   <li><b>裂谷剖面必须「深−平」对称</b>：以轴心为原点，左右 60wu 内 e 应近似相等
 *       （不对称 &gt; 0.02e 即说明偏移破坏了裂谷形状）。</li>
 *   <li><b>裂谷不应出现负距离平台</b>：若 {@code distRaw + wander} 在边界附近
 *       被 {@code max(0,·)} 截平，则裂谷两侧会出现「平底」——检测裂谷底部宽度：
 *       真实 gaussian 的底部（&lt;10% 深度）宽度应 ≈ 0.6×σ，显著更宽即平台化。</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runRidgeDecomposeProbe}（默认窗 ±400wu @ (1600,1600)）。</p>
 */
public final class RidgeDecomposeProbe {

    private static final long SEED = 9139912035078620160L;
    private static final int N = 500;

    public static void main(String[] args) throws Exception {
        double cx = args.length > 0 ? Double.parseDouble(args[0]) : 1600;
        double cz = args.length > 1 ? Double.parseDouble(args[1]) : 1600;
        double half = args.length > 2 ? Double.parseDouble(args[2]) : 400;

        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);
        TectonicField tectonic = new TectonicField(SEED);

        double[][] eFinal = new double[N][N];
        double[][] ridge = new double[N][N];
        double[][] ageE = new double[N][N];
        double[][] crest = new double[N][N];   // final - age - ridge（≈ 裂谷+丘陵+其它）

        double minE = 9, maxE = -9;
        double minR = 9, maxR = -9;
        for (int j = 0; j < N; j++) {
            double wz = cz - half + 2 * half * j / (N - 1);
            for (int i = 0; i < N; i++) {
                double wx = cx - half + 2 * half * i / (N - 1);
                var c = smp.sampleColumn(wx, wz);
                var ts = tectonic.sample(wx, wz);
                var cs = CrustField.of(ts, wx, wz, SEED);
                eFinal[j][i] = c.e;
                ageE[j][i] = cs.eBase();
                ridge[j][i] = c.oceanFeat == null ? 0 : c.oceanFeat.ridge;
                crest[j][i] = c.e - ageE[j][i] - ridge[j][i];
                minE = Math.min(minE, c.e);
                maxE = Math.max(maxE, c.e);
                minR = Math.min(minR, ridge[j][i]);
                maxR = Math.max(maxR, ridge[j][i]);
            }
        }

        Path dir = Path.of("build/ridge-decompose");
        Files.createDirectories(dir);
        render(dir.resolve("1-age-law.png"),
                "AGE LAW (thermal subsidence only)  [%.3f, %.3f]e".formatted(minAge(ageE), maxAge(ageE)),
                ageE, minAge(ageE), maxAge(ageE));
        render(dir.resolve("2-ridge.png"),
                "RIDGE component (axial uplift)  [%.3f, %.3f]e".formatted(minR, maxR),
                ridge, 0, Math.max(0.001, maxR));
        renderSigned(dir.resolve("3-rift-residual.png"),
                "RESIDUAL (final - age - ridge) = rift/trench/hills",
                crest, -0.06, 0.06);
        render(dir.resolve("4-final.png"),
                "FINAL e  [%.3f, %.3f]e".formatted(minE, maxE),
                eFinal, minE, maxE);

        // ---- 自动判据：裂谷对称 + 平台检测 ----
        // 找 ridge 峰值点作为轴心，沿东西方向取剖面
        int bi = 0, bj = 0;
        double best = -1;
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                if (ridge[j][i] > best) {
                    best = ridge[j][i];
                    bi = i;
                    bj = j;
                }
            }
        }
        double axX = cx - half + 2 * half * bi / (N - 1);
        double axZ = cz - half + 2 * half * bj / (N - 1);
        System.out.printf(Locale.ROOT, "[shoulder] ridge peak %.3fe @ (%.0f, %.0f)%n",
                best, axX, axZ);

        // ★ ridge 峰值点是【脊肩】，不是谷底（谷底 ridge 值更低）。
        //   沿「垂直脊轴」方向找 ridge 的最小值 = 真正的轴心/谷底。
        {
            var tsS = tectonic.sample(axX, axZ);
            double px = -tsS.tangentZ(), pz = tsS.tangentX();
            double len = Math.hypot(px, pz);
            if (len < 1e-6) { px = 1; pz = 0; } else { px /= len; pz /= len; }
            double bestD = 0, bestV = 9;
            for (double t = -400; t <= 400; t += 4) {
                var c = smp.sampleColumn(axX + px * t, axZ + pz * t);
                double v = c.oceanFeat == null ? 0 : c.oceanFeat.ridge;
                if (v < bestV) { bestV = v; bestD = t; }
            }
            axX += px * bestD;
            axZ += pz * bestD;
            System.out.printf(Locale.ROOT,
                    "[axis] rift floor @ offset %.0fwu along perpendicular; ridge there %.3fe%n",
                    bestD, bestV);
        }

        // ★ 剖面沿「垂直于脊轴」方向的切线取（否则斜切会模糊地堑/脊肩）。
        var ts0 = tectonic.sample(axX, axZ);
        double perpX = -ts0.tangentZ();
        double perpZ = ts0.tangentX();
        double perpLen = Math.hypot(perpX, perpZ);
        if (perpLen < 1e-6) {
            perpX = 1;
            perpZ = 0;
        } else {
            perpX /= perpLen;
            perpZ /= perpLen;
        }
        System.out.printf(Locale.ROOT,
                "[方向] 切向 (%.3f, %.3f) ⇒ 垂直剖面沿 (%.3f, %.3f)%n",
                ts0.tangentX(), ts0.tangentZ(), perpX, perpZ);

        int halfW = 400;
        double[] prof = new double[2 * halfW + 1];
        double[] ageProf = new double[2 * halfW + 1];
        double[] ridgeProf = new double[2 * halfW + 1];
        for (int k = -halfW; k <= halfW; k++) {
            double px = axX + perpX * k;
            double pz = axZ + perpZ * k;
            var c = smp.sampleColumn(px, pz);
            var ts = tectonic.sample(px, pz);
            prof[k + halfW] = c.e;
            ageProf[k + halfW] = CrustField.of(ts, px, pz, SEED).eBase();
            ridgeProf[k + halfW] = c.oceanFeat == null ? 0 : c.oceanFeat.ridge;
        }
        // 残余剖面（= 最终 − 年龄 − ridge）
        double[] resProf = new double[prof.length];
        for (int k = 0; k < prof.length; k++) {
            resProf[k] = prof[k] - ageProf[k] - ridgeProf[k];
        }
        int center = halfW;

        // 1) 对称性：±100wu 内左右镜像差
        double maxAsym = 0;
        for (int k = 1; k <= 100; k++) {
            maxAsym = Math.max(maxAsym, Math.abs(resProf[center - k] - resProf[center + k]));
        }
        // 2) 平台检测：残余最深处（裂谷底）的「<12% 深度」宽度
        int lo = center - 200, hi = center + 200;
        double resMin = 9;
        for (int k = lo; k <= hi; k++) {
            resMin = Math.min(resMin, resProf[k]);
        }
        double baseRes = (resProf[center - 200] + resProf[center + 200]) * 0.5;
        double depth = baseRes - resMin;
        double thresh = resMin + 0.12 * depth;
        int width = 0;
        for (int k = lo; k <= hi; k++) {
            if (resProf[k] < thresh) {
                width++;
            }
        }
        System.out.printf(Locale.ROOT,
                "[rift] depth %.3fe  floor-width %.0fwu"
                + " (RidgeProfile: half 80wu, flat 40%% => expect ~64wu; >>100 = plateau)%n",
                resMin, depth, (double) width);
        System.out.printf(Locale.ROOT,
                "[sym] max mirror diff %.4fe over 100wu (criterion <0.03; >limit = shape broken)%n",
                maxAsym);
        boolean symmetric = maxAsym < 0.03;
        boolean noPlateau = width <= 110;
        System.out.println(symmetric ? "  rift symmetry: PASS" : "  rift symmetry: FAIL");
        System.out.println(noPlateau ? "  no plateau: PASS" : "  no plateau: FAIL");
        System.out.println(symmetric && noPlateau ? "=== ALL PASS ===" : "=== FAIL ===");
        System.out.println("images -> " + dir.toAbsolutePath());
        if (!symmetric || !noPlateau) {
            System.exit(1);
        }
    }

    private static double minAge(double[][] a) {
        double m = 9;
        for (double[] r : a) for (double v : r) m = Math.min(m, v);
        return m;
    }

    private static double maxAge(double[][] a) {
        double m = -9;
        for (double[] r : a) for (double v : r) m = Math.max(m, v);
        return m;
    }

    private static void render(Path out, String title, double[][] v, double lo, double hi) throws Exception {
        renderSigned(out, title, v, lo, hi);
    }

    /** 渲染：蓝(深/小) → 白 → 棕(高/大)，带 hillshade。 */
    private static void renderSigned(Path out, String title, double[][] v,
                                     double lo, double hi) throws Exception {
        BufferedImage img = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
        double span = Math.max(1e-9, hi - lo);
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                double t = (v[j][i] - lo) / span;
                t = Math.max(0, Math.min(1, t));
                int r = (int) (30 + 200 * t);
                int g = (int) (60 + 150 * t);
                int b = (int) (150 - 60 * t + 60 * t * t);
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        // hillshade（固定梯度放大，便于看形态）
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int i0 = Math.max(0, i - 1), i1 = Math.min(N - 1, i + 1);
                int j0 = Math.max(0, j - 1), j1 = Math.min(N - 1, j + 1);
                double gx = (v[j][i1] - v[j][i0]) / Math.max(1, i1 - i0) * 800;
                double gy = (v[j1][i] - v[j0][i]) / Math.max(1, j1 - j0) * 800;
                double nx = -gx, ny = -gy, nz = 1;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double dd = (-nx * 0.6 - ny * 0.6 + nz * 0.53) / len;
                double sh = 0.5 + 0.8 * Math.max(0, Math.min(1, dd));
                int c = img.getRGB(i, j);
                int r = Math.min(255, (int) (((c >> 16) & 0xFF) * sh));
                int g = Math.min(255, (int) (((c >> 8) & 0xFF) * sh));
                int b = Math.min(255, (int) ((c & 0xFF) * sh));
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 13));
        g2.drawString(title, 8, 18);
        g2.dispose();
        ImageIO.write(img, "png", out.toFile());
    }

    private RidgeDecomposeProbe() { }
}