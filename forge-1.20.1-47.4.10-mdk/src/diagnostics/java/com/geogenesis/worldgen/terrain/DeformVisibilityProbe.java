package com.geogenesis.worldgen.terrain;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 构造地貌「可见性」探针（★ 2026-09-14）。
 *
 * <h3>要回答的问题</h3>
 * <p>用户反馈「构造地貌（断层崖/地垒/地堑）看不到」。实测确认
 * {@code CellGenerator:377-379} <b>早已把 {@code deform.offset} 累加进 eLand</b>
 * （{@code DEFORM_ENABLED=true}），所以问题<b>不是"没接"</b>，而是<b>信噪比</b>：
 * </p>
 * <pre>
 *   deform 峰值   ≈ [-0.031, +0.0498] e   （≈ -6 ~ +9.6 块）
 *   deform 均值   ≈ 0.00153 e             （≈ 0.29 块）
 *   eLand 全域跨度 ≈ [-0.35, +0.475] e    （≈ 158 块）
 *   ⇒ 信噪比 ≈ 3.7%，肉眼不可辨
 * </pre>
 *
 * <h3>方法学：为什么必须"先出图再动参"</h3>
 * <p>{@code AGENTS.md} 的铁律：<b>「判定伪影的最终依据只能是渲染图」</b>
 * （历史上 6 轮"假设→修→仍存在"）。故本探针先把<b>现状</b>与
 * <b>若干候选参数</b>并排渲染成山体阴影图，用<b>同一视觉口径</b>对比，
 * 一轮即可定死参数量级，避免盲改。
 *
 * <h3>关键设计：镜像 + 逐位自检</h3>
 * <p>{@code TectonicDeformation} 的 {@code valueNoise} / {@code beltMask} /
 * {@code segmentMask} 是 {@code private}，无法直接调参探索。故本探针<b>镜像</b>
 * 其数学（参数化：振幅 / 崖宽 / 带掩码偏置 / 段掩码地板），并先用
 * <b>生产参数</b>跑一次<b>逐位比对自检</b>（{@code Double.compare}）——
 * <b>只有自检通过，探索结果才可信</b>。这是"镜像必须被验证"的纪律。
 *
 * <h3>输出（build/deform/）</h3>
 * <ul>
 *   <li>{@code shade_base.png} —— 现状：eLand(+deform) 山体阴影</li>
 *   <li>{@code shade_&lt;tag&gt;.png} —— 各候选参数的山体阴影（同光照同色阶）</li>
 *   <li>{@code deform_&lt;tag&gt;.png} —— 各候选的 deform 分量灰度（正负中心化）</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runDeformVisibilityProbe [-PprobeArgs="seed size step ox oz"]}</p>
 */
public final class DeformVisibilityProbe {

    // ===================== 镜像 TectonicDeformation 的私有常量 =====================
    //   刻意保持与生产源码【完全相同】的数值 —— 自检会验证这一点。
    private static final double FOLD_WAVELENGTH = 600.0;
    private static final double FOLD_REACH = 2600.0;
    private static final double FAULT_SPACING = 240.0;
    private static final double FAULT_REACH = 2600.0;
    private static final double FAULT_BLOCK_FREQ = 2.5;
    private static final double FAULT_SEGMENT = 4000.0;
    private static final double MASK_SCALE = 1500.0;
    private static final double BELT_SCALE = 1100.0;
    private static final double FOLD_RIDGE_ROUND = 0.15;
    private static final double FOLD_RIDGE_NORM =
            Math.sqrt(1.0 + FOLD_RIDGE_ROUND * FOLD_RIDGE_ROUND) - FOLD_RIDGE_ROUND;

    /** 生产振幅（{@code TectonicDeformation.FOLD_AMP} / {@code FAULT_AMP}）。 */
    private static final double PROD_FOLD_AMP = 0.032;
    /** ★ 2026-09-14：已随生产同步为 0.090（原 0.045）。 */
    private static final double PROD_FAULT_AMP = 0.090;
    /** 生产崖宽（{@code SCARP_HALF_WIDTH}）。★ 2026-09-14：已同步为 0.18（原 0.30）。 */
    private static final double PROD_SCARP_HALF_WIDTH = 0.18;
    /** ★ 2026-09-14：生产 beltMask 偏置（{@code TectonicDeformation.BELT_BIAS}）。 */
    private static final double PROD_BELT_BIAS = 0.10;

    private static final long SALT_FOLD = 0x3B91_D7C4_5E02_1A87L;
    private static final long SALT_FAULT = 0x6D2A_F813_B94C_70E5L;
    private static final long SALT_MASK = 0x8B41_0C7E_2D93_5AF6L;
    private static final long SALT_BELT = 0x4E2B_9C17_53AF_D081L;

    /** 1 e 折合方块数（= (maxY − minY)/2 = 192），用于把 e 换算成"格"。 */
    private static final double BLOCKS_PER_E = 192.0;

    private final long seed;

    private DeformVisibilityProbe(long seed) {
        this.seed = seed;
    }

    /**
     * 候选参数集。
     *
     * @param foldAmp        褶皱振幅（e）
     * @param faultAmp       断层断距（e）
     * @param scarpHalfWidth 崖过渡半宽（越小崖越陡 → 越可见）
     * @param beltBias       构造带掩码偏置（加到噪声上 = 等效降低阈值 → 带更宽）
     * @param segFloor       段掩码地板（0.25 = 生产值；提高 → 沿走向更连续）
     */
    private record Cfg(String tag, double foldAmp, double faultAmp,
                       double scarpHalfWidth, double beltBias, double segFloor) {

        /** 生产参数（用于逐位自检）。 */
        static Cfg prod() {
            return new Cfg("base", PROD_FOLD_AMP, PROD_FAULT_AMP,
                    PROD_SCARP_HALF_WIDTH, PROD_BELT_BIAS, 0.25);
        }
    }

    // ===================== 镜像实现（自检通过后方可用于探索） =====================

    /** 镜像 {@code TectonicDeformation.offset}。 */
    private double offset(TectonicField.Sample s, double wx, double wz, Cfg cfg) {
        double eps = TectonicField.STRESS_POS_EPS;
        double cw = TectonicField.smoothPos(s.stress(), eps);
        double dw = TectonicField.smoothPos(-s.stress(), eps);
        double mask = segmentMask(wx, wz, cfg.segFloor());
        double fold = cw * foldOffset(s, wx, wz, mask, cfg);
        double fault = (cw * 0.6 + dw * 1.0) * faultOffsetUnit(s, wx, wz, mask, cfg);
        return fold + fault;
    }

    /** 镜像 {@code foldOffset}。 */
    private double foldOffset(TectonicField.Sample s, double wx, double wz,
                              double mask, Cfg cfg) {
        double d = s.dist();
        if (d >= FOLD_REACH) return 0.0;
        double decay = decay(d, FOLD_REACH) * beltMask(wx, wz, cfg.beltBias());
        double n = valueNoise(wx / FOLD_WAVELENGTH, wz / FOLD_WAVELENGTH, SALT_FOLD);
        double n2 = valueNoise(wx / (FOLD_WAVELENGTH * 2.1) + 13.7,
                wz / (FOLD_WAVELENGTH * 2.1) - 7.3, SALT_FOLD + 1);
        double x = 0.7 * n + 0.3 * n2;
        double roundAbs = (Math.sqrt(x * x + FOLD_RIDGE_ROUND * FOLD_RIDGE_ROUND)
                - FOLD_RIDGE_ROUND) / FOLD_RIDGE_NORM;
        double ridge = 1.0 - roundAbs;
        double wave = ridge * 2.0 - 1.0;
        return cfg.foldAmp() * wave * decay * mask;
    }

    /** 镜像 {@code faultOffsetUnit}。 */
    private double faultOffsetUnit(TectonicField.Sample s, double wx, double wz,
                                   double mask, Cfg cfg) {
        double d = s.dist();
        if (d >= FAULT_REACH) return 0.0;
        double decay = decay(d, FAULT_REACH) * beltMask(wx, wz, cfg.beltBias());
        double slip = valueNoise(wx / FAULT_SEGMENT, wz / FAULT_SEGMENT, SALT_FAULT);
        double q = FAULT_BLOCK_FREQ;
        double fN = valueNoise(wx / FAULT_SPACING * q, wz / FAULT_SPACING * q, SALT_FAULT + 7);
        double hw = cfg.scarpHalfWidth();
        double t = (fN + hw) / (2.0 * hw);
        t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        double scarp = t * t * (3.0 - 2.0 * t);
        double slipBlk = scarp * 2.0 - 1.0;
        return cfg.faultAmp() * (slip * 0.5 + slipBlk * 0.5) * decay * mask;
    }

    /** 镜像 {@code segmentMask}（地板可调）。 */
    private double segmentMask(double wx, double wz, double floor) {
        double n = valueNoise(wx / MASK_SCALE, wz / MASK_SCALE, SALT_MASK);
        double t = n * 0.5 + 0.5;
        return floor + (1.0 - floor) * t;
    }

    /** 镜像 {@code beltMask}（偏置可调：等效于平移阈值）。 */
    private double beltMask(double wx, double wz, double bias) {
        double n = valueNoise(wx / BELT_SCALE, wz / BELT_SCALE, SALT_BELT);
        double t = (n + bias - 0.10) / 0.40;
        t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        return t * t * (3.0 - 2.0 * t);
    }

    /** 镜像 {@code decay}。 */
    private static double decay(double d, double reach) {
        double t = 1.0 - d / reach;
        if (t <= 0.0) return 0.0;
        return t * t * (3.0 - 2.0 * t);
    }

    /** 镜像 {@code valueNoise}（Catmull-Rom 双三次，C¹）。 */
    private double valueNoise(double x, double z, long salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double fx2 = fx * fx, fx3 = fx2 * fx;
        double wx0 = -0.5 * fx3 + fx2 - 0.5 * fx;
        double wx1 = 1.5 * fx3 - 2.5 * fx2 + 1.0;
        double wx2 = -1.5 * fx3 + 2.0 * fx2 + 0.5 * fx;
        double wx3 = 0.5 * fx3 - 0.5 * fx2;
        double fz2 = fz * fz, fz3 = fz2 * fz;
        double wz0 = -0.5 * fz3 + fz2 - 0.5 * fz;
        double wz1 = 1.5 * fz3 - 2.5 * fz2 + 1.0;
        double wz2 = -1.5 * fz3 + 2.0 * fz2 + 0.5 * fz;
        double wz3 = 0.5 * fz3 - 0.5 * fz2;
        double r0 = cell(ix - 1, iz - 1, salt) * wz0 + cell(ix - 1, iz, salt) * wz1
                + cell(ix - 1, iz + 1, salt) * wz2 + cell(ix - 1, iz + 2, salt) * wz3;
        double r1 = cell(ix, iz - 1, salt) * wz0 + cell(ix, iz, salt) * wz1
                + cell(ix, iz + 1, salt) * wz2 + cell(ix, iz + 2, salt) * wz3;
        double r2 = cell(ix + 1, iz - 1, salt) * wz0 + cell(ix + 1, iz, salt) * wz1
                + cell(ix + 1, iz + 1, salt) * wz2 + cell(ix + 1, iz + 2, salt) * wz3;
        double r3 = cell(ix + 2, iz - 1, salt) * wz0 + cell(ix + 2, iz, salt) * wz1
                + cell(ix + 2, iz + 1, salt) * wz2 + cell(ix + 2, iz + 2, salt) * wz3;
        return r0 * wx0 + r1 * wx1 + r2 * wx2 + r3 * wx3;
    }

    private double cell(int ix, int iz, long salt) {
        long h = (long) ix * 374761393L + (long) iz * 668265263L + salt + seed * 0x9E3779B9L;
        h = (h ^ (h >>> 16)) * 1274126177L;
        h ^= (h >>> 16);
        return ((h & 0xFFFFFFL) / (double) 0x1000000L) * 2.0 - 1.0;
    }

    // ================================== 主流程 ==================================

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 384;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        Integer oxArg = args.length > 3 ? Integer.parseInt(args[3]) : null;
        Integer ozArg = args.length > 4 ? Integer.parseInt(args[4]) : null;

        System.out.printf("=== DeformVisibilityProbe seed=%d size=%d step=%d ===%n",
                seed, size, step);

        DeformVisibilityProbe probe = new DeformVisibilityProbe(seed);
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        TectonicField tf = new TectonicField(seed);
        TectonicDeformation td = new TectonicDeformation(seed);

        // ---------- [0] 镜像自检（不通过则探索结果不可信）----------
        int mism = 0;
        double maxDiff = 0;
        for (int i = 0; i < 4000; i++) {
            double x = i * 137.3 - 30000, z = i * 91.7 - 22000;
            TectonicField.Sample s = tf.sample(x, z);
            double a = td.offset(s, x, z);
            double b = probe.offset(s, x, z, Cfg.prod());
            if (Double.compare(a, b) != 0) {
                mism++;
                maxDiff = Math.max(maxDiff, Math.abs(a - b));
            }
        }
        System.out.printf("[0] 镜像自检(n=4000): 不一致=%d 最大差=%.3e %s%n",
                mism, maxDiff, mism == 0 ? "PASS" : "FAIL");
        if (mism != 0) {
            System.out.println("    ⚠ 镜像与生产不符 —— 后续探索结果不可信，请先修正镜像。");
            System.exit(1);
        }

        // ---------- [1] 全局分布统计（真实坐标网格）----------
        System.out.println("[1] deform 分布（真实网格）:");
        Cfg prod = Cfg.prod();
        double sum = 0, sumAbs = 0, mn = 1e9, mx = -1e9;
        int n = 0, active = 0;
        java.util.List<Double> absList = new java.util.ArrayList<>();
        for (double z = -12000; z <= 12000; z += 149) {
            for (double x = -12000; x <= 12000; x += 151) {
                double v = probe.offset(tf.sample(x, z), x, z, prod);
                sum += v; sumAbs += Math.abs(v);
                if (v != 0.0) active++;
                mn = Math.min(mn, v); mx = Math.max(mx, v);
                absList.add(Math.abs(v));
                n++;
            }
        }
        absList.sort(null);
        double mean = sum / n, meanAbs = sumAbs / n;
        double p99 = absList.get((int) (absList.size() * 0.99));
        System.out.printf("    n=%d 有形变=%.0f%% 均值=%+.5f e(%.2f 块) 平均|v|=%.5f e(%.2f 块)%n",
                n, 100.0 * active / n, mean, mean * BLOCKS_PER_E,
                meanAbs, meanAbs * BLOCKS_PER_E);
        System.out.printf("    p99|v|=%.5f e(%.2f 块)  max|v|=%.5f e(%.2f 块)  范围=[%.4f, %.4f] e%n",
                p99, p99 * BLOCKS_PER_E, Math.max(-mn, mx), Math.max(-mn, mx) * BLOCKS_PER_E, mn, mx);

        // ---------- 定位地质活跃区 ----------
        int ox, oz;
        if (oxArg != null && ozArg != null) {
            ox = oxArg; oz = ozArg;
        } else {
            int[] hit = locateActive(terrain, tf, probe, prod);
            ox = hit[0]; oz = hit[1];
        }
        System.out.printf("[2] 渲染原点=(%d,%d)%n", ox, oz);

        // ---------- [3] 候选参数 ----------
        java.util.List<Cfg> cfgs = java.util.List.of(
                prod,
                new Cfg("amp2", PROD_FOLD_AMP * 2, PROD_FAULT_AMP * 2,
                        PROD_SCARP_HALF_WIDTH, 0.0, 0.25),
                new Cfg("amp2_belt", PROD_FOLD_AMP * 2, PROD_FAULT_AMP * 2,
                        PROD_SCARP_HALF_WIDTH, 0.20, 0.25),
                new Cfg("amp3_belt_steep", PROD_FOLD_AMP * 3, PROD_FAULT_AMP * 3,
                        0.15, 0.20, 0.40),
                // ★ 断层主导（褶皱保持生产幅度）：diff 图证实蠕虫细线来自褶皱 ridged 结构，
                //   高幅褶皱 = 复活「波浪细线」伪影；断层崖才是"地垒/地堑"的视觉主体。
                new Cfg("fault2_steep", PROD_FOLD_AMP, PROD_FAULT_AMP * 2,
                        0.18, 0.20, 0.40),
                new Cfg("fault3_steep", PROD_FOLD_AMP, PROD_FAULT_AMP * 3,
                        0.15, 0.20, 0.40)
        );

        File outDir = new File("build/deform");
        outDir.mkdirs();

        // 基准 eLand（含生产 deform）—— 用于"减去现值再加候选值"
        double[][] eBase = new double[size][size];
        double[][] dProd = new double[size][size];
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double x = ox + px * (double) step, z = oz + py * (double) step;
                Cell c = terrain.sampleCellCoarse(x, z);
                eBase[py][px] = c.eLand;
                dProd[py][px] = probe.offset(tf.sample(x, z), x, z, prod);
            }
        }
        HeightCurve curve = terrain.heightCurve();
        writeShade(outDir, "shade_base", eBase, size, step, curve);
        System.out.println("[3] 影像输出（同光照/同色阶，可直接目视比较）:");
        System.out.println("    shade_base.png   ← 现状");

        for (int i = 1; i < cfgs.size(); i++) {
            Cfg cfg = cfgs.get(i);
            double[][] eVar = new double[size][size];
            double[][] dVar = new double[size][size];
            double sAbs = 0, sAbsVar = 0, sGrad = 0, sGradVar = 0;
            double gMax = 0, gMaxVar = 0;
            int nScree = 0, nRock = 0, nScreeVar = 0, nRockVar = 0;
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    double x = ox + px * (double) step, z = oz + py * (double) step;
                    double dv = probe.offset(tf.sample(x, z), x, z, cfg);
                    dVar[py][px] = dv;
                    eVar[py][px] = eBase[py][px] - dProd[py][px] + dv;
                    sAbs += Math.abs(dProd[py][px]);
                    sAbsVar += Math.abs(dv);
                }
            }
            // 梯度（块/wu）与坡度分档覆盖：deform 本身对地形的"改形能力"
            for (int py = 1; py < size - 1; py++) {
                for (int px = 1; px < size - 1; px++) {
                    double gA = gradBlocks(eBase, px, py, step, curve);
                    double gB = gradBlocks(eVar, px, py, step, curve);
                    sGrad += gA; sGradVar += gB;
                    gMax = Math.max(gMax, gA); gMaxVar = Math.max(gMaxVar, gB);
                    if (gA > 0.30) nScree++;
                    if (gA > 0.40) nRock++;
                    if (gB > 0.30) nScreeVar++;
                    if (gB > 0.40) nRockVar++;
                }
            }
            int m = (size - 2) * (size - 2);
            writeShade(outDir, "shade_" + cfg.tag(), eVar, size, step, curve);
            writeGray(outDir, "deform_" + cfg.tag(), dVar, size);
            // 差值图：候选相对现状"新增/削去"的地形（块）—— 直接展示改动幅度与空间分布
            double[][] diff = new double[size][size];
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    diff[py][px] = (eVar[py][px] - eBase[py][px]) * BLOCKS_PER_E;
                }
            }
            writeDiff(outDir, "diff_" + cfg.tag(), diff, size);
            System.out.printf("    shade_%-14s mean|deform|=%.3f 块(%.1f×)  mean|∇h|=%.4f→%.4f 块/wu  max|∇h|=%.2f→%.2f%n",
                    cfg.tag() + ".png", sAbsVar / (size * size), (sAbsVar / Math.max(1e-9, sAbs)),
                    sGrad / m, sGradVar / m, gMax, gMaxVar);
            System.out.printf("      坡度>0.30 占比 %.1f%%→%.1f%%   >0.40 占比 %.1f%%→%.1f%%%n",
                    100.0 * nScree / m, 100.0 * nScreeVar / m,
                    100.0 * nRock / m, 100.0 * nRockVar / m);
        }
        System.out.println("输出目录: " + outDir.getAbsolutePath());
        System.out.println("判读：shade_*.png 同光照同色阶并排看 —— 崖线是否肉眼可辨即最终判据。");
    }

    /** 高度梯度（块/wu），用中心差分。 */
    private static double gradBlocks(double[][] e, int px, int py, int step, HeightCurve curve) {
        double hxm = curve.heightFromE(e[py][px - 1]);
        double hxp = curve.heightFromE(e[py][px + 1]);
        double hzm = curve.heightFromE(e[py - 1][px]);
        double hzp = curve.heightFromE(e[py + 1][px]);
        double dhx = (hxp - hxm) / (2.0 * step);
        double dhz = (hzp - hzm) / (2.0 * step);
        return Math.sqrt(dhx * dhx + dhz * dhz);
    }

    /** 扫描定位｜deform｜最大的陆地采样点（构造带内才复现得出问题）。 */
    private static int[] locateActive(GeoGenesisTerrain terrain, TectonicField tf,
                                      DeformVisibilityProbe probe, Cfg cfg) {
        int bx = 0, bz = 0;
        double best = -1;
        for (int r = 0; r <= 20000; r += 400) {
            for (int a = 0; a < 360; a += 15) {
                double rad = Math.toRadians(a);
                int x = (int) (r * Math.cos(rad)), z = (int) (r * Math.sin(rad));
                Cell c = terrain.sampleCellCoarse(x, z);
                if (c == null || c.eLand < 0.20) continue;
                double o = Math.abs(probe.offset(tf.sample(x, z), x, z, cfg));
                if (o > best) { best = o; bx = x; bz = z; }
            }
        }
        System.out.printf("    活跃点 |deform|=%.5f e (%.2f 块)%n", best, best * BLOCKS_PER_E);
        return new int[]{bx, bz};
    }

    // ------------------------------ 渲染工具 ------------------------------

    /**
     * 山体阴影（NW 光照）。用<b>高度</b>（HeightCurve 映射）而非 e，使"崖"的
     * 明暗对比与实机一致。
     */
    private static void writeShade(File dir, String name, double[][] e, int size, int step,
                                   HeightCurve curve) throws Exception {
        double[][] h = new double[size][size];
        double mn = 1e9, mx = -1e9;
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                // 用真实 e→Y 映射（HeightCurve），使阴影与实机观感一致
                h[py][px] = curve.heightFromE(e[py][px]);
                mn = Math.min(mn, h[py][px]); mx = Math.max(mx, h[py][px]);
            }
        }
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        // 光照方向（NW）
        final double lx = -0.5, ly = 0.7, lz = 0.51;
        for (int py = 1; py < size - 1; py++) {
            for (int px = 1; px < size - 1; px++) {
                double dhx = (h[py][px + 1] - h[py][px - 1]) / (2.0 * step);
                double dhz = (h[py + 1][px] - h[py - 1][px]) / (2.0 * step);
                // 真实比例（1 wu = 1 块 ⇒ 45° 坡的梯度 = 1.0），不做夸张 ——
                //   这样阴影明暗才对应玩家实机观感（此前 192× 夸张使全图过饱和、无法判读）。
                final double vScale = 1.0;
                double nx = -dhx * vScale, ny = 1.0, nz = -dhz * vScale;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double lam = (nx * lx + ny * ly + nz * lz) / Math.max(1e-9, nl);
                // 底色：按高度做地形色带
                int g = (int) Math.round(255.0 * Math.max(0.0, Math.min(1.0, 0.35 + 0.75 * lam)));
                int[] base = elevRamp((h[py][px] - mn) / Math.max(1e-9, mx - mn));
                int r = Math.min(255, base[0] * g / 255 + g / 4);
                int gg = Math.min(255, base[1] * g / 255 + g / 4);
                int b = Math.min(255, base[2] * g / 255 + g / 4);
                img.setRGB(px, py, (r << 16) | (gg << 8) | b);
            }
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    /** 差值图（块，发散配色：暖=抬升 / 冷=削低 / 灰=不变）。 */
    private static void writeDiff(File dir, String name, double[][] v, int size) throws Exception {
        double mxAbs = 1e-9;
        for (double[] row : v) for (double x : row) mxAbs = Math.max(mxAbs, Math.abs(x));
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double t = v[py][px] / mxAbs;   // [-1,1]
                int r = t > 0 ? 255 : (int) (128 + 127 * (1 + t));
                int g = (int) (128 + 127 * (1 - Math.abs(t)));
                int b = t < 0 ? 255 : (int) (128 + 127 * (1 - t));
                img.setRGB(px, py, (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b));
            }
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** 灰度（中心化到 0.5）：用于展示有符号的 deform 分量。 */
    private static void writeGray(File dir, String name, double[][] v, int size) throws Exception {
        double mxAbs = 1e-9;
        for (double[] row : v) for (double x : row) mxAbs = Math.max(mxAbs, Math.abs(x));
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int g = (int) Math.round(127.5 + 127.5 * v[py][px] / mxAbs);
                g = Math.max(0, Math.min(255, g));
                img.setRGB(px, py, (g << 16) | (g << 8) | g);
            }
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    /** 高程色带（蓝→绿→黄→白）。 */
    private static int[] elevRamp(double p) {
        p = Math.max(0.0, Math.min(1.0, p));
        int[][] stops = {{20, 60, 130}, {60, 140, 150}, {70, 150, 70}, {170, 180, 100}, {255, 255, 255}};
        double t = p * (stops.length - 1);
        int i = (int) Math.min(stops.length - 2, Math.floor(t));
        double f = t - i;
        return new int[]{
                (int) (stops[i][0] + (stops[i + 1][0] - stops[i][0]) * f),
                (int) (stops[i][1] + (stops[i + 1][1] - stops[i][1]) * f),
                (int) (stops[i][2] + (stops[i + 1][2] - stops[i][2]) * f)};
    }
}
