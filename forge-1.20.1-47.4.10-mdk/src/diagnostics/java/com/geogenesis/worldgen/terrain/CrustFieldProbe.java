package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ★ 桥接 S2（2026-11-13）：{@link CrustField} 标定探针（仪表，不进门禁）。
 *
 * <p>用法：{@code gradlew runCrustFieldProbe [-PprobeArgs="<toml> <outDir> [size] [cxB] [czB] [rB]]"]}
 * ；默认 {@code tools/p1-frozen-input.toml}、outDir=build/crustfield、501² 窗口、
 * 中心 (1590,2250) 块、半径 3000 块（与 LithoPhysicalProbe 同款坐标口径）。</p>
 *
 * <p>产出：</p>
 * <ul>
 *   <li>四张物理量图（min-max 灰度）：thickness / density / buoyancy / eBase；</li>
 *   <li>{@code crust_ebase_ageonly.png}：κ≡0 的范式对照（"无碰撞陆化"的纯粹 age 世界）——
 *       S3 停等门给用户对比 C 范式用；</li>
 *   <li>{@code crust_profile.csv}：过窗口中心的剖面（每 8 块一行）；</li>
 *   <li>stdout 统计：age/κ/thickness/ρ/buoyancy/eBase 分位、eBase&gt;0 占比
 *       （构造语义下的"陆占比"）、全球普查两范式对比。</li>
 * </ul>
 */
public final class CrustFieldProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 501;
        double cxB = args.length > 3 ? Double.parseDouble(args[3]) : 1590.0;
        double czB = args.length > 4 ? Double.parseDouble(args[4]) : 2250.0;
        double rB = args.length > 5 ? Double.parseDouble(args[5]) : 3000.0;
        Files.createDirectories(out);

        double hs;
        try (CommentedFileConfig cfg = CommentedFileConfig.of(toml, TomlFormat.instance())) {
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            hs = GeoGenesisConfig.INSTANCE.buildParams().horizontalScale();
        }

        TectonicField tect = new TectonicField(SEED);
        double[][] thickness = new double[size][size];
        double[][] density = new double[size][size];
        double[][] buoyancy = new double[size][size];
        double[][] eBase = new double[size][size];
        double[][] eBaseAgeOnly = new double[size][size];
        double[][] age = new double[size][size];
        double[][] kappa = new double[size][size];

        List<String> profile = new ArrayList<>();
        profile.add("bx,bz,wx,wz,stress,age,kappa,thickness,density,buoyancy,eBase");
        List<Double> buoyList = new ArrayList<>();
        List<Double> eList = new ArrayList<>();
        List<Double> kappaList = new ArrayList<>();
        List<Double> distList = new ArrayList<>();

        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double bx = cxB - rB + (double) i / (size - 1) * 2 * rB;
                double bz = czB - rB + (double) j / (size - 1) * 2 * rB;
                double wx = bx / hs, wz = bz / hs;
                TectonicField.Sample ts = tect.sample(wx, wz);
                CrustField.Sample cs = CrustField.of(ts);

                thickness[j][i] = cs.thickness();
                density[j][i] = cs.density();
                buoyancy[j][i] = cs.buoyancy();
                eBase[j][i] = cs.eBase();
                age[j][i] = cs.age();
                kappa[j][i] = cs.kappa();

                // 范式对照（A' = 复刻忠实）：κ≡0 且密度不随龄增（A' 与 C 的物理分歧点）——
                // 厚度随龄增 → buoyancy 随龄升 → 成熟 crust = 陆（复刻 area=1 怪癖的语义）
                double tAge = 0.20 + 0.35 * cs.age();
                double rAge = 0.62;
                double bAge = tAge * (0.95 - rAge);
                eBaseAgeOnly[j][i] = (bAge - 0.12) * 3.9;

                buoyList.add(cs.buoyancy());
                eList.add(cs.eBase());
                kappaList.add(cs.kappa());
                distList.add(ts.dist());

                if (j == size / 2 && i % 8 == 0) {
                    profile.add(String.format(Locale.ROOT,
                            "%.0f,%.0f,%.1f,%.1f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                            bx, bz, wx, wz, ts.stress(), cs.age(), cs.kappa(),
                            cs.thickness(), cs.density(), cs.buoyancy(), cs.eBase()));
                }
            }
        }

        gray(thickness, out, "crust_thickness.png", "thickness");
        gray(density, out, "crust_density.png", "density");
        gray(buoyancy, out, "crust_buoyancy.png", "buoyancy");
        gray(eBase, out, "crust_ebase.png", "eBase");
        gray(eBaseAgeOnly, out, "crust_ebase_ageonly.png", "eBase(age-only, k=0)");
        gray(age, out, "crust_age.png", "age");
        gray(kappa, out, "crust_kappa.png", "kappa");
        Files.writeString(out.resolve("crust_profile.csv"), String.join("\n", profile));

        // ===== 全球普查（★ 2026-11-13 应用户要求：局部窗口说明不了海陆占比）=====
        // ±globalR wu 的确定性均匀网格（无随机数），两范式各算全球陆占比 + 出全球布局图。
        int globalR = args.length > 6 ? Integer.parseInt(args[6]) : 20000;   // wu（=20×spacing）
        int gcN = 448;                                   // 448² = 200,704 点
        double gStep = 2.0 * globalR / (gcN - 1);
        long landC = 0, landA = 0, total = 0;
        for (int j = 0; j < gcN; j++) {
            for (int i = 0; i < gcN; i++) {
                double wx = -globalR + i * gStep, wz = -globalR + j * gStep;
                CrustField.Sample cs = CrustField.of(tect.sample(wx, wz));
                if (cs.eBase() > 0) landC++;
                double tA = 0.20 + 0.35 * cs.age();
                double eA = (tA * (0.95 - 0.62) - 0.12) * 3.9;   // A'：密度恒 0.62
                if (eA > 0) landA++;
                total++;
            }
        }
        System.out.printf(Locale.ROOT, "GLOBAL CENSUS ±%dwu (%d pts): landFrac C(碰撞=陆)=%.4f  A'(成熟=陆)=%.4f%n",
                globalR, total, (double) landC / total, (double) landA / total);

        // 全球布局图（700²，每像素 ≈114 块）
        int gSize = 700;
        double gMapStep = 2.0 * globalR / (gSize - 1);
        double[][] gC = new double[gSize][gSize];
        double[][] gA = new double[gSize][gSize];
        for (int j = 0; j < gSize; j++) {
            for (int i = 0; i < gSize; i++) {
                double wx = -globalR + i * gMapStep, wz = -globalR + j * gMapStep;
                CrustField.Sample cs = CrustField.of(tect.sample(wx, wz));
                gC[j][i] = cs.eBase();
                double tA = 0.20 + 0.35 * cs.age();
                gA[j][i] = (tA * (0.95 - 0.62) - 0.12) * 3.9;
            }
        }
        gray(gC, out, "crust_global.png", "global eBase (C, ±" + globalR + "wu)");
        gray(gA, out, "crust_global_ageonly.png", "global eBase (A', ±" + globalR + "wu)");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "=== CrustField seed=%d size=%d cxB=%.0f czB=%.0f rB=%.0f hs=%.1f ===%n",
                SEED, size, cxB, czB, rB, hs));
        sb.append(String.format(Locale.ROOT, "dist(wu) p5=%.0f p50=%.0f p95=%.0f%n",
                q(distList, .05), q(distList, .5), q(distList, .95)));
        sb.append(String.format(Locale.ROOT, "kappa    p50=%.4f p90=%.4f coverage(>0.1)=%.3f%n",
                q(kappaList, .5), q(kappaList, .9), fracAbove(kappaList, 0.1)));
        sb.append(String.format(Locale.ROOT, "buoyancy p5=%.4f p50=%.4f p95=%.4f%n",
                q(buoyList, .05), q(buoyList, .5), q(buoyList, .95)));
        sb.append(String.format(Locale.ROOT, "eBase    p5=%.4f p50=%.4f p95=%.4f  min=%.4f max=%.4f%n",
                q(eList, .05), q(eList, .5), q(eList, .95), eList.stream().min(Double::compare).orElse(0.0),
                eList.stream().max(Double::compare).orElse(0.0)));
        sb.append(String.format(Locale.ROOT, "landFrac(eBase>0)=%.4f  (S2 只接 eBase<0 海床；此占比=构造语义陆占比)%n",
                fracAbove(eList, 0.0)));
        // R6 性能纪律：构造采样 ns/点。基线对照：ea65a01 前后为 2130 → 1346 ns/点（格点缓存）。
        // 方法：3 轮取最小（单轮受 JIT/缓存状态漂移影响实测可达 2×，最小值才稳定）。
        double x0 = cxB / hs, z0 = czB / hs;
        for (int i = 0; i < 5000; i++) {
            tect.sample(x0 + 0.1 * i, z0 + 0.07 * i);   // warmup（JIT + 缓存）
        }
        int N = 50000;
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) {
                tect.sample(x0 + 0.021 * i, z0 + 0.013 * i);
            }
            long t1 = System.nanoTime();
            best = Math.min(best, t1 - t0);
        }
        sb.append(String.format(Locale.ROOT, "perf: TectonicField.sample = %d ns/pt (N=%d, 3轮取min)%n", best / N, N));
        System.out.print(sb);
        System.out.println("CRUST PROBE done (instrument only)");
    }

    /** min-max 归一化灰度 PNG（LithoPhysicalProbe 同款口径）。 */
    private static void gray(double[][] f, Path out, String name, String label) throws Exception {
        int n = f.length;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double[] row : f) {
            for (double v : row) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double t = (max - min) < 1e-12 ? 0.5 : (f[j][i] - min) / (max - min);
                int g = (int) (t * 255);
                img.setRGB(i, j, (g << 16) | (g << 8) | g);
            }
        }
        File file = out.resolve(name).toFile();
        ImageIO.write(img, "png", file);
        System.out.printf(Locale.ROOT, "%s: [%s] range=[%.4f, %.4f] -> %s%n",
                label, name, min, max, file);
    }

    private static double q(List<Double> sorted, double frac) {
        List<Double> copy = new ArrayList<>(sorted);
        copy.sort(Double::compare);
        int i = (int) Math.min(copy.size() - 1, Math.floor(frac * copy.size()));
        return copy.get(i);
    }

    private static double fracAbove(List<Double> values, double threshold) {
        long n = values.stream().filter(v -> v > threshold).count();
        return values.isEmpty() ? 0 : (double) n / values.size();
    }
}
