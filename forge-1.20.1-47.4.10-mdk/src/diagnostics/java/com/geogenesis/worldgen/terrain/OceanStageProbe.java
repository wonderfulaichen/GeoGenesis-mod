package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 海洋多环节诊断探针 —— 追踪完整 eOcean 管线，验证修复隔离性。
 * 
 * <p>分阶段输出 eBase → depthMod → seabed → eOcean → ridgeFade → seamount → eFull，
 * 分别对陆地区域(eLand>0)和海洋区域(eLand<=0)统计最大梯度。
 * 验证 depthMod smoothstep 和 ridgeFade smoothstep 不会污染陆地。
 */
public final class OceanStageProbe {

    private static final String[] STAGE_NAMES = {
        "cBiased",           // 0: 偏置大陆性 [-1, 1]
        "eBase",             // 1: HeightCurve.eFromC
        "depthMod",          // 2: depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2
        "seabed",            // 3: seabedAmp * depthMod * seaBed.sample()
        "eOcean_preFeature", // 4: eBase + seabed (before OceanFeatures)
        "ridgeFade",         // 5: OceanFeatures ridgeFade
        "seamount",          // 6: OceanFeatures seamount
        "eOcean_postFeature",// 7: eOcean + features
        "eLand",             // 8: TypeLandShape.sample()
        "eFull(v8 twoStage)",// 9: two-stage blend (eOcean fade + eLand ramp)
    };

    public static void main(String[] args) throws Exception {
        TerrainParams p = TerrainParams.defaults();
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int ox = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int oz = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        int W = 800, H = 800;
        System.out.println("=== OceanStageProbe v1 ===");
        System.out.println("Region " + W + "x" + H + " blocks, step=1");
        System.out.println("Verifying depthMod/ridgeFade isolation: land areas should have delta=0");
        System.out.println();

        // 创建引擎组件（与 CellGenerator.sample() 一致的流水线）
        ContinentField continent = new ContinentField(p);
        continent.seed(seed);
        HeightCurve curve = new HeightCurve(p, -64, 320);
        double continentBias = p.continentBias();
        SeaBedDetail seaBed = new SeaBedDetail(p);
        seaBed.seed(seed);
        double seabedAmp = p.seabedDetail();

        TypeLandShape landShape = new TypeLandShape(p);
        landShape.seed(seed);

        OceanFeatures oceanFeatures = new OceanFeatures();
        oceanFeatures.setSeamountDepthChecker((wx, wz) -> {
            double c = continent.sample(wx, wz);
            double cBiased = c - continentBias;
            double eBase = curve.eFromC(cBiased);
            double depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2;
            double seabed = seabedAmp * depthMod * seaBed.sample(wx, wz);
            double eOcean = eBase + seabed;
            return Math.min(eOcean, 0.0);
        });
        oceanFeatures.seed(seed);
        // ★ 2026-09-13 Phase T6：本探针也走完整链路（洋中脊门控 / 火山弧需构造场）。
        TectonicField tectonic = new TectonicField(seed);

        // 采样各阶段
        final int L = STAGE_NAMES.length;
        double[][][] stages = new double[L][W][H];
        // ★ T6 排查用：构造 dist 与弧纯分量（定位"细直线 + Y 汇聚"归属哪个分量）
        double[][] distF = new double[W][H];
        double[][] arcF = new double[W][H];

        System.out.print("Sampling " + W + "x" + H + " ... ");
        long t0 = System.currentTimeMillis();

        long landCount = 0, oceanCount = 0;

        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double wx = ox + x, wz = oz + z;

                // 1. 大陆性
                double c = continent.sample(wx, wz);
                double cBiased = c - continentBias;
                stages[0][x][z] = cBiased;

                // 2. eBase
                double eBase = curve.eFromC(cBiased);
                stages[1][x][z] = eBase;

                // 3. depthMod (smoothstep 版本)
                double depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2;
                stages[2][x][z] = depthMod;

                // 4. seabed
                double seabed = seabedAmp * depthMod * seaBed.sample(wx, wz);
                stages[3][x][z] = seabed;

                // 5. eOcean pre-feature
                double eOcean = eBase + seabed;
                eOcean = Math.min(eOcean, 0.0);
                eOcean = eOcean < -1.0 ? -1.0 : eOcean;
                stages[4][x][z] = eOcean;

                // 6. OceanFeatures（★ T6：带构造场，与生产路径一致）
                TectonicField.Sample ts = tectonic.sample(wx, wz);
                double arcProf = tectonic.oceanProfile(ts);
                OceanFeatures.FeatureResult feat = oceanFeatures.compute(wx, wz, eOcean, cBiased, ts, arcProf);
                stages[5][x][z] = feat.ridge;
                stages[6][x][z] = feat.seamount + feat.arc;
                distF[x][z] = ts.dist();
                arcF[x][z] = feat.arc;

                // 7. eOcean post-feature
                double eOceanFinal = eOcean + feat.total;
                eOceanFinal = eOceanFinal < -1.0 ? -1.0 : (eOceanFinal > 0.0 ? 0.0 : eOceanFinal);
                stages[7][x][z] = eOceanFinal;

                // 8. eLand
                TerrainCharacterField.BlendResult blend = landShape.sampleBlend(wx, wz);
                double eLand = landShape.sample(blend, wx, wz);
                stages[8][x][z] = eLand;

                // 9. eFull (v8 two-stage blend)
                double oceanFadeStart = p.oceanFadeStart();
                double coastLoc = p.coastLoc();
                double landRampEnd = p.landRampEnd();
                double t1 = smoothstep(oceanFadeStart, coastLoc, cBiased);
                double eOceanStage = eOceanFinal * (1.0 - t1);
                double t2 = smoothstep(coastLoc, landRampEnd, cBiased);
                double eLandStage = eLand * t2;
                double eFull = eOceanStage + eLandStage;
                stages[9][x][z] = eFull;

                if (eLand > 0) landCount++;
                else oceanCount++;
            }
            if ((x + 1) % 100 == 0) System.out.print(".");
        }
        long t1 = System.currentTimeMillis();
        System.out.println(" done in " + (t1 - t0) + "ms");
        System.out.println("Land samples: " + landCount + ", Ocean samples: " + oceanCount);
        System.out.println();

        // =========================================================
        // 梯度统计（分陆地/海洋）
        // =========================================================
        for (String region : new String[]{"land", "ocean"}) {
            boolean isLand = region.equals("land");
            System.out.println("=== Stage Gradient (maxDelta per stage) — " + region + " ===");
            System.out.println("Stage                | maxDelta_X | maxDelta_Z | maxDelta | approxBlk | meanDelta | samples");
            System.out.println("---------------------+------------+------------+----------+-----------+----------+---------");

            for (int li = 0; li < L; li++) {
                double maxGrad = 0, maxGradX = 0, maxGradZ = 0, sumGrad = 0;
                long count = 0;

                for (int x = 0; x < W; x++) {
                    for (int z = 0; z < H; z++) {
                        // 按区域筛选
                        double eLand = stages[8][x][z];
                        boolean thisIsLand = eLand > 0;
                        if (thisIsLand != isLand) continue;

                        double v = stages[li][x][z];
                        double gx = 0, gz = 0;
                        if (x > 0 && isLand == (stages[8][x-1][z] > 0)) {
                            gx = Math.abs(v - stages[li][x-1][z]);
                            if (gx > maxGradX) maxGradX = gx;
                        }
                        if (z > 0 && isLand == (stages[8][x][z-1] > 0)) {
                            gz = Math.abs(v - stages[li][x][z-1]);
                            if (gz > maxGradZ) maxGradZ = gz;
                        }
                        double g = Math.max(gx, gz);
                        if (g > maxGrad) maxGrad = g;
                        sumGrad += g;
                        count++;
                    }
                }

                double meanGrad = count > 0 ? sumGrad / count : 0;
                double heightBlocks = maxGrad * 384;

                System.out.printf("%-20s | %10.6f | %10.6f | %8.6f | %9.1f | %9.6f | %8d%n",
                        STAGE_NAMES[li], maxGradX, maxGradZ, maxGrad, heightBlocks, meanGrad, count);
            }
            System.out.println();
        }

        // =========================================================
        // 海洋隔离验证：eLand > 0 区域各阶段 maxDelta
        // =========================================================
        System.out.println("=== Ocean Isolation Verification ===");
        System.out.println("If depthMod/ridgeFade correctly isolated, land stages should have delta=0.");
        System.out.println();

        // depthMod 陆地最大值（正常应为 0.6，纯陆地无一 > 0.6001）
        double maxDepthModLand = 0;
        double maxRidgeLand = 0;
        double maxSeamountLand = 0;
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double eLand = stages[8][x][z];
                if (eLand > 0) {
                    if (stages[2][x][z] > maxDepthModLand) maxDepthModLand = stages[2][x][z];
                    if (stages[5][x][z] > maxRidgeLand) maxRidgeLand = stages[5][x][z];
                    if (stages[6][x][z] > maxSeamountLand) maxSeamountLand = stages[6][x][z];
                }
            }
        }

        System.out.printf("Land depthMod max: %.6f (should be ≤ 0.6 + tiny)%n", maxDepthModLand);
        System.out.printf("Land ridgeFade max: %.6f (should be 0.0)%n", maxRidgeLand);
        System.out.printf("Land seamount max: %.6f (should be 0.0)%n", maxSeamountLand);

        boolean isolated = maxDepthModLand <= 0.601 && maxRidgeLand < 0.001 && maxSeamountLand < 0.001;
        System.out.println("Ocean isolation: " + (isolated ? "✅ PASS" : "❌ FAIL"));
        System.out.println();

        // =========================================================
        // depthMod / ridgeFade 分布统计
        // =========================================================
        System.out.println("=== depthMod value distribution (by eBase range) ===");
        System.out.println("eBase range      | count | depthMod min | depthMod max | depthMod mean");
        System.out.println("------------------+-------+-------------+-------------+--------------");
        String[] ranges = {"eBase < -0.6", "-0.6 <= eBase < -0.2", "-0.2 <= eBase < 0", "eBase >= 0"};
        for (int ri = 0; ri < 4; ri++) {
            long rcnt = 0; double rmin = 999, rmax = -999, rsum = 0;
            for (int x = 0; x < W; x++) {
                for (int z = 0; z < H; z++) {
                    double eb = stages[1][x][z];
                    boolean inRange = switch (ri) {
                        case 0 -> eb < -0.6;
                        case 1 -> eb >= -0.6 && eb < -0.2;
                        case 2 -> eb >= -0.2 && eb < 0;
                        case 3 -> eb >= 0;
                        default -> false;
                    };
                    if (inRange) {
                        double dm = stages[2][x][z];
                        rcnt++;
                        if (dm < rmin) rmin = dm;
                        if (dm > rmax) rmax = dm;
                        rsum += dm;
                    }
                }
            }
            double rmean = rcnt > 0 ? rsum / rcnt : 0;
            System.out.printf("%-17s | %5d | %11.4f | %11.4f | %12.4f%n", ranges[ri], rcnt, rmin, rmax, rmean);
        }

        System.out.println();
        System.out.println("=== OceanStageProbe v1 done ===");
        System.out.println("Threshold: maxDelta > 0.01 (~3.8 blocks) needs watch, >0.05 (~19 blocks) severe.");

        // =========================================================
        // ★ 2026-09-13 T6：PNG 目检（海沟 / 火山弧 / 洋中脊 形态是否成立）
        // =========================================================
        //   gradmag（梯度幅值）是判定"细线/折痕伪影"的标准视图；
        //   eOcean_postFeature 是"海床最终形态"，可直接看海沟(暗线)/弧(亮线)/洋中脊(亮带)。
        //   注意：海沟槽**不写入剖面**（负部归 T1 权重调制），故此处看 T1 是否已给出海沟。
        java.io.File outDir = new java.io.File("build/oceanstage");
        outDir.mkdirs();
        final String tag = "_" + ox + "_" + oz;
        java.util.function.BiConsumer<String, double[][]> dump = (name, v) -> {
            double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            for (double[] col : v) for (double val : col) { mn = Math.min(mn, val); mx = Math.max(mx, val); }
            try {
                javax.imageio.ImageIO.write(gray(v, mn, mx), "png", new java.io.File(outDir, name + tag + ".png"));
            } catch (Exception e) { throw new RuntimeException(e); }
            System.out.printf("  [png] %-22s 值域=[%.4f, %.4f]%n", name + tag, mn, mx);
        };
        double[][] oceanFinal = stages[7];
        double[][] ridgeField = stages[5];
        double[][] seamountField = stages[6];
        dump.accept("eOcean_postFeature", oceanFinal);
        dump.accept("ridge", ridgeField);
        dump.accept("seamount_arc", seamountField);
        dump.accept("tect_dist", distF);
        dump.accept("arc_only", arcF);
        // 梯度幅值（细线/折痕检测）：分别给"总场"与"各分量"，一次运行即可定位元凶
        java.util.function.Function<double[][], double[][]> grad = v -> {
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
        dump.accept("eOcean_gradmag", grad.apply(oceanFinal));
        dump.accept("arc_gradmag", grad.apply(arcF));
        dump.accept("dist_gradmag", grad.apply(distF));
        // 长尾比诊断（P99.9/P50）：含"折痕线"的场比值远高于平滑场（~3~8）
        for (String nm : new String[]{"eOcean", "arc", "tect_dist"}) {
            double[][] src = nm.equals("eOcean") ? oceanFinal : (nm.equals("arc") ? arcF : distF);
            double[][] g = grad.apply(src);
            java.util.List<Double> vals = new java.util.ArrayList<>();
            for (int x = 1; x < W - 1; x++) {
                for (int z = 1; z < H - 1; z++) vals.add(g[x][z]);
            }
            vals.sort(Double::compare);
            int n = vals.size();
            double p50 = vals.get(n / 2), p999 = vals.get((int) (n * 0.999));
            System.out.printf("  [长尾比] %-12s P50=%.6g P99.9=%.6g max=%.6g ratio=%.2f%n",
                    nm, p50, p999, vals.get(n - 1), p50 > 1e-15 ? p999 / p50 : Double.POSITIVE_INFINITY);
        }
        // 洋中脊 与 弧 的分离度（验证二者位置确实不同 —— 弧离轴）
        int both = 0, arcOnly = 0, ridgeOnly = 0;
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                boolean r = ridgeField[x][z] > 0.05;
                boolean a = seamountField[x][z] > 0.02 && stages[8][x][z] <= 0;
                if (r && a) both++; else if (a) arcOnly++; else if (r) ridgeOnly++;
            }
        }
        System.out.printf("  [分离度] 脊弧同点=%d 仅弧=%d 仅脊=%d%n", both, arcOnly, ridgeOnly);
        System.out.println("  输出目录: " + outDir.getAbsolutePath());
    }

    /** 灰度归一化出图（最小值→黑，最大值→白）。 */
    private static java.awt.image.BufferedImage gray(double[][] v, double mn, double mx) {
        int w = v.length, h = v[0].length;
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        double span = mx - mn;
        if (span <= 1e-12) span = 1;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                int g = (int) Math.round(255.0 * (v[x][z] - mn) / span);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                // TYPE_BYTE_GRAY 的 raster 已按 sRGB 约定存放亮度值
                img.getRaster().setSample(x, z, 0, g);
            }
        }
        return img;
    }

    // ===== 内联工具（与 CellGenerator 一致） =====
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private static double saturate(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = saturate((x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }
}
