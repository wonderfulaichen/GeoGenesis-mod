package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphLandField;
import com.geogenesis.worldgen.terrain.morph.MorphOceanAssembler;
import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>陆缘剖面 A/B 对比图</b>——三个生产状态同窗渲染，供目检验收（2026-11-14）。
 *
 * <ol>
 *   <li><b>A = 阶段0</b>：旧均衡式 eBase（buoyancy 公式）、无陆缘削减——生产在阶段一之前</li>
 *   <li><b>B = 阶段一</b>：平台模型年龄律 + 深海丘陵 + 轴谷 + 海沟、无陆缘削减</li>
 *   <li><b>C = 阶段二</b>：B + 主动陆缘削减（陆架控制点压深 2200 米）</li>
 * </ol>
 *
 * <p>三条链共享同一个 {@link MorphLandField} / {@link ContinentField} / {@link TectonicField}
 * （确定性纯函数，与生产 MorphTerrainSampler 内部实例逐位等价），只替换
 * {@link MorphOceanAssembler} 的陆缘剖面绑定 ⇒ A/B 差异<b>全部来自洋底模型</b>。</p>
 *
 * <p>产出（固定写 {@code build/ocean-ab/}，经 {@link #safeFile} 规范化校验）：
 * {@code ab-A.png / ab-B.png / ab-C.png / ab-diff-BA.png / ab-diff-CB.png}。</p>
 */
public final class OceanABProbe {

    /** 输出根目录（相对工作目录 = 项目根，不越界）。 */
    private static final String OUT_DIR = "build/ocean-ab";

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 700;
        double radius = args.length > 1 ? Double.parseDouble(args[1]) : 1750.0;
        long seed = 9139912035078620160L;

        // 共享层：与生产 MorphTerrainSampler 同构造路径 ⇒ land/continent 逐位等价；
        // TectonicField 是确定性纯函数（同种子）⇒ 与 sampler 内部实例逐位等价
        MorphTerrainSampler smp = new MorphTerrainSampler(seed, TerrainParams.defaults(), -64, 320);
        MorphLandField land = smp.landField();
        ContinentField continent = smp.continentField();
        TectonicField tectonic = new TectonicField(seed);

        // ---- 窗口搜索：主动陆缘削减只在「会聚边界穿窗」处生效 ⇒ 必须先找到这样的窗口 ----
        double[] win = findWindow(smp, tectonic, radius);
        double cx = win[0], cz = win[1];
        System.out.printf(Locale.ROOT,
                "[window] center (%.0f, %.0f)  landFrac %.2f  maxMargin %.2f%n",
                cx, cz, win[2], win[3]);

        // 三条陆缘剖面绑定
        MorphOceanAssembler.CrustProfileFn fnA = (x, z) -> {
            var cs = CrustField.of(tectonic.sample(x, z), x, z, seed);
            // 旧均衡式（阶段0）：与 CrustField 内部 OCEAN_PLATEAU_MODEL=false 分支同式，
            // 直接引用同包常量，不硬编码
            double oldE = (cs.buoyancy() - CrustField.BUOYANCY_SEA_LEVEL)
                    * CrustField.E_PER_BUOYANCY_SEA;
            return new MorphOceanAssembler.CrustAnchors(
                    Math.min(oldE * MorphOceanAssembler.BLOCKS_PER_E_SEA,
                            MorphOceanAssembler.shallowBlocks()),
                    MorphOceanAssembler.shallowBlocks());
        };
        MorphOceanAssembler.CrustProfileFn fnB = (x, z) -> {
            var ts = tectonic.sample(x, z);
            var cs = CrustField.of(ts, x, z, seed);
            return new MorphOceanAssembler.CrustAnchors(
                    Math.min(cs.eBase() * MorphOceanAssembler.BLOCKS_PER_E_SEA,
                            MorphOceanAssembler.shallowBlocks()),
                    MorphOceanAssembler.shallowBlocks());
        };
        MorphOceanAssembler.CrustProfileFn fnC = (x, z) -> {
            var ts = tectonic.sample(x, z);
            var cs = CrustField.of(ts, x, z, seed);
            double margin = marginFactor(ts);
            double shallow = MorphOceanAssembler.shallowBlocks()
                    - margin * 2200.0 / 11000.0 * 127.0;
            return new MorphOceanAssembler.CrustAnchors(
                    Math.min(cs.eBase() * MorphOceanAssembler.BLOCKS_PER_E_SEA, shallow),
                    shallow);
        };

        MorphOceanAssembler asmA = new MorphOceanAssembler(land::e, continent::sample, seed + 3)
                .enableCoastalDetail(seed).withCrustProfile(fnA);
        MorphOceanAssembler asmB = new MorphOceanAssembler(land::e, continent::sample, seed + 3)
                .enableCoastalDetail(seed).withCrustProfile(fnB);
        MorphOceanAssembler asmC = new MorphOceanAssembler(land::e, continent::sample, seed + 3)
                .enableCoastalDetail(seed).withCrustProfile(fnC);

        double[][] hA = new double[n][n];
        double[][] hB = new double[n][n];
        double[][] hC = new double[n][n];
        for (int j = 0; j < n; j++) {
            double wz = cz - radius + 2 * radius * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - radius + 2 * radius * i / (n - 1);
                hA[j][i] = asmA.e(wx, wz);
                hB[j][i] = asmB.e(wx, wz);
                hC[j][i] = asmC.e(wx, wz);
            }
        }

        render(hA, n, radius, "ab-A.png", "A = stage0 (old isostasy)");
        render(hB, n, radius, "ab-B.png", "B = stage1 (plateau age law)");
        render(hC, n, radius, "ab-C.png", "C = stage2 (active margin shelf cut)");

        // 差值热力图（B−A = 阶段一变化；C−B = 阶段二变化）
        renderDiff(hB, hA, "ab-diff-BA.png", "diff B-A (stage 1)");
        renderDiff(hC, hB, "ab-diff-CB.png", "diff C-B (stage 2)");

        // 统计：变化幅度
        statDiff(hB, hA, "B-A (stage 1)");
        statDiff(hC, hB, "C-B (stage 2)");
    }

    /**
     * 窗口自动搜索：主动陆缘削减（阶段二）只在会聚边界邻近生效 ⇒ 对比图窗口必须
     * 同时满足 ① 会聚边界穿窗（max marginFactor &gt; 0.4）② 有海岸带（陆地占比
     * 0.10~0.55）。搜索范围 ±16km wu、步长 2000wu；找不到时回退 max margin 最高的候选。
     */
    private static double[] findWindow(MorphTerrainSampler smp, TectonicField tectonic,
                                       double radius) {
        final int m = 96;
        double h = radius * 0.5;
        double[][] offs = {{0, 0}, {h, 0}, {-h, 0}, {0, h}, {0, -h}};
        double bestScore = -1, fbScore = -1;
        double[] best = {0, 0, 0, 0}, fb = {0, 0, 0, 0};
        for (double cz = -16000; cz <= 16000.001; cz += 2000) {
            for (double cx = -16000; cx <= 16000.001; cx += 2000) {
                // 快速门：中心 5 点 margin 因子（tectonic 纯函数，极便宜）
                double mm = 0;
                for (double[] o : offs) {
                    mm = Math.max(mm, marginFactor(tectonic.sample(cx + o[0], cz + o[1])));
                }
                if (mm < 0.15) {
                    continue;   // 边界不穿窗，直接跳过（省掉 sampleE）
                }
                // 粗采样估陆地占比 + 窗内 max margin
                int land = 0;
                double mm2 = 0;
                for (int j = 0; j < m; j++) {
                    double wz = cz - radius + 2 * radius * j / (m - 1);
                    for (int i = 0; i < m; i++) {
                        double wx = cx - radius + 2 * radius * i / (m - 1);
                        if (smp.sampleE(wx, wz) > 0) {
                            land++;
                        }
                        mm2 = Math.max(mm2, marginFactor(tectonic.sample(wx, wz)));
                    }
                }
                double lf = land / (double) (m * m);
                double[] cand = {cx, cz, lf, mm2};
                if (mm2 > fbScore) {
                    fbScore = mm2;
                    fb = cand;
                }
                if (lf < 0.10 || lf > 0.55) {
                    continue;
                }
                if (mm2 > bestScore) {
                    bestScore = mm2;
                    best = cand;
                }
            }
        }
        if (bestScore > 0) {
            return best;
        }
        System.out.println("[window] ±16km 内无「边界穿窗+海岸带」窗口，回退 max margin 候选");
        return fb;
    }

    private static void statDiff(double[][] nb, double[][] ob, String label) {
        int n = nb.length;
        int changed = 0;
        double maxD = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double v = Math.abs(nb[j][i] - ob[j][i]) * 127.0;
                if (v > 5.0) {
                    changed++;
                }
                maxD = Math.max(maxD, v);
            }
        }
        System.out.printf(Locale.ROOT, "[%s] 变化>5格的格数 %d（%.1f%%）  最大 %.0f 格%n",
                label, changed, 100.0 * changed / (n * n), maxD);
    }

    /** 主动陆缘因子（与 MorphTerrainSampler.marginFactor 同式）。 */
    private static double marginFactor(TectonicField.Sample ts) {
        double t = clamp01((ts.stress() - 0.25) / 0.60);
        double gate = t * t * (3 - 2 * t);
        return gate * Math.exp(-(ts.distRaw() * ts.distRaw()) / (90.0 * 90.0));
    }

    private static double clamp01(double t) {
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    // ---- 渲染（沙箱自研配色：水深蓝阶 + 陆地绿阶 + 山影）----

    private static void render(double[][] h, int n, double radius, String name, String label)
            throws Exception {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double cellWu = 2 * radius / (n - 1);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double e = h[j][i];
                double m = e * 11000.0;
                int r, g, b;
                if (m <= 0) {
                    double t = Math.min(1.0, -e / 0.45);
                    r = (int) (40 + 45 * (1 - t));
                    g = (int) (78 + 52 * (1 - t));
                    b = (int) (110 + 60 * (1 - t));
                } else {
                    double t = Math.min(1.0, e / 0.30);
                    r = (int) (58 + 150 * t);
                    g = (int) (112 + 70 * t);
                    b = (int) (52 + 90 * t);
                }
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        // 山影（物理坡度 + 4 格宽窗，沙箱同款）
        double cellBlocks = cellWu;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] <= 0) {
                    continue;
                }
                int i0 = Math.max(0, i - 4), i1 = Math.min(n - 1, i + 4);
                int j0 = Math.max(0, j - 4), j1 = Math.min(n - 1, j + 4);
                double gx = (h[j][i1] - h[j][i0]) * 236.0 / ((i1 - i0) * cellBlocks);
                double gy = (h[j1][i] - h[j0][i]) * 236.0 / ((j1 - j0) * cellBlocks);
                double nx = -gx, ny = -gy, nz = 1.0;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double dd = (-nx * 0.5 - ny * 0.5 + nz * 0.7071) / len;
                double sh = 0.55 + 0.7 * Math.max(0, Math.min(1, dd));
                int c = img.getRGB(i, j);
                int r = Math.min(255, (int) (((c >> 16) & 0xFF) * sh));
                int g = Math.min(255, (int) (((c >> 8) & 0xFF) * sh));
                int b2 = Math.min(255, (int) ((c & 0xFF) * sh));
                img.setRGB(i, j, (r << 16) | (g << 8) | b2);
            }
        }
        drawLabel(img, label);
        ImageIO.write(img, "png", safeFile(name));
        System.out.println("image -> " + safeFile(name).getAbsolutePath());
    }

    private static void renderDiff(double[][] a, double[][] b, String name, String label)
            throws Exception {
        int n = a.length;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double scale = 127.0;   // e → 格
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double d = (b[j][i] - a[j][i]) * scale;   // 格（正 = B 比 A 深）
                int r, g, bl;
                if (Math.abs(d) < 3) {
                    r = 40; g = 40; bl = 48;              // 无变化：暗底
                } else if (d > 0) {
                    int t = (int) Math.min(255, 60 + Math.abs(d) * 6);
                    r = 30; g = t / 2; bl = t;            // 变深：蓝
                } else {
                    int t = (int) Math.min(255, 60 + Math.abs(d) * 6);
                    r = t; g = t / 2; bl = 30;            // 变浅/变陆：橙红
                }
                img.setRGB(i, j, (r << 16) | (g << 8) | bl);
            }
        }
        drawLabel(img, label);
        ImageIO.write(img, "png", safeFile(name));
        System.out.println("image -> " + safeFile(name).getAbsolutePath());
    }

    private static void drawLabel(BufferedImage img, String label) {
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));
        g.drawString(label, 10, 20);
        g.dispose();
    }

    /**
     * 路径安全：规范化并校验输出必须落在 {@code build/ocean-ab/} 内
     * （Mimosa 路径穿越检测的要求；探针固定写自己的输出目录，不收用户路径）。
     */
    private static java.io.File safeFile(String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("output name must be a plain file name: " + name);
        }
        Path p = Path.of(OUT_DIR, name).normalize();
        Path allowed = Path.of(OUT_DIR).normalize();
        if (!p.startsWith(allowed)) {
            throw new IllegalArgumentException("output escapes allowed dir: " + p);
        }
        java.io.File f = p.toFile();
        java.io.File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot mkdir " + parent);
        }
        return f;
    }
}