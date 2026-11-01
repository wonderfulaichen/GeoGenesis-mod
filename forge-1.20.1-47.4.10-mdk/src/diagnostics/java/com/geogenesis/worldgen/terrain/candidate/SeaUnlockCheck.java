package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>海侧解锁开关的门禁</b>（方案 {@code ocean-unified-terrain} 步 3）。
 *
 * <h3>本步真正的风险</h3>
 * <p>{@link CombineCandidate#e} 里的 {@code Math.max(..., 0.0)} 是<b>共享代码</b>：
 * 陆地臂与未来的统一臂用的是同一个方法。任何"改默认行为"的写法都会静默改变
 * 用户已认可的陆地基准。</p>
 *
 * <h3>六项判据</h3>
 * <pre>
 * E1 default-locked    新构造的实例默认 seaUnlocked == false
 * E2 e-identical       默认状态下，e(x,z) 与"旧公式 Math.max(GROUND_E+h*E_SPAN,0)" 逐位相同
 * E3 unlock-negatives  解锁后，存在负值输出（证明解锁真的生效，不是空开关）
 * E4 unlock-only-e     解锁只影响 e()；height01() 与默认实例逐位相同（截断仍在族内部）
 * E5 seaE-contract     seaE() 的映射：[-1,0] 内恒等、越界钳位
 * E6 no-global-leak    解锁一个实例不影响另一个实例（不是静态状态）
 * </pre>
 *
 * <p>E2 与 E6 是安全绳：E2 保证"不开锁时逐字节等于旧实现"，
 * E6 保证"开关是实例级、不会泄漏成全局"。</p>
 */
public final class SeaUnlockCheck {

    private static final long SEED = 9139912035078620160L;
    private static final int PTS = 6000;

    private static final StringBuilder csv = new StringBuilder();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/sea-unlock-check");
        Files.createDirectories(out);

        csv.append("check,metric,value,verdict\n");

        RecipeMorphCatalog catalog = new RecipeMorphCatalog(SEED);
        CombineCandidate base = catalog.original();
        if (base == null) {
            System.out.println("SEA UNLOCK CHECK FAIL: original() returned null");
            System.exit(1);
            return;
        }

        // ---------------- E1
        record("E1", "default-seaUnlocked", base.seaUnlocked() ? 1 : 0, !base.seaUnlocked(),
                "新构造的实例必须默认未解锁");

        // ---------------- E2
        // 对拍"当前 e()" 与 "旧公式"，逐位相同
        int diffOld = 0;
        double worst = 0.0;
        double[] xs = new double[PTS], zs = new double[PTS];
        java.util.Random rnd = new java.util.Random(20261101);
        for (int i = 0; i < PTS; i++) {
            xs[i] = rnd.nextDouble() * 14000 - 7000;
            zs[i] = rnd.nextDouble() * 14000 - 7000;
        }
        for (int i = 0; i < PTS; i++) {
            double got = base.e(xs[i], zs[i]);
            double old = Math.max(CombineCandidate.GROUND_E + base.height01(xs[i], zs[i])
                    * CombineCandidate.E_SPAN, 0.0);
            if (Double.compare(got, old) != 0) {
                diffOld++;
                worst = Math.max(worst, Math.abs(got - old));
            }
        }
        record("E2", "points", PTS, true, "对拍点数");
        record("E2", "diff-vs-old-formula", diffOld, diffOld == 0,
                "★ 未解锁时 e(x,z) 必须与旧公式逐位相同（改默认行为=毁基线）");
        record("E2", "worst-delta", worst, diffOld == 0, "最大差（应为 0）");

        // ---------------- E3 / E4
        // 另建一个实例并解锁（不要污染 base）
        RecipeMorphCatalog catalog2 = new RecipeMorphCatalog(SEED);
        CombineCandidate unlocked = catalog2.original();
        if (unlocked == null) {
            record("E3", "original-null", 1, false, "第二次 original() 返回 null");
        } else {
            unlocked.enableSeaSideUnlock();
            record("E3", "unlocked-flag", unlocked.seaUnlocked() ? 1 : 0, unlocked.seaUnlocked(),
                    "调用 enableSeaSideUnlock() 后标志必须为 true");
            int negatives = 0;
            double minE = Double.POSITIVE_INFINITY;
            for (int i = 0; i < PTS; i++) {
                double v = unlocked.e(xs[i], zs[i]);
                minE = Math.min(minE, v);
                if (v < 0.0) negatives++;
            }
            record("E3", "negative-outputs", negatives, negatives > 0,
                    "★ 解锁后必须真的出现负值（否则开关是空壳，E3 不成立）");
            record("E3", "min-e", minE, minE < 0.0, "解锁后最小值应为负");

            // E4：height01 必须与 base 完全相同（解锁只动 e 出口）
            int hDiff = 0;
            for (int i = 0; i < PTS; i++) {
                if (Double.compare(unlocked.height01(xs[i], zs[i]),
                        base.height01(xs[i], zs[i])) != 0) hDiff++;
            }
            record("E4", "height01-diff", hDiff, hDiff == 0,
                    "★ 解锁只影响 e()；height01() 必须与默认实例逐位相同（截断仍在族内部）");
        }

        // ---------------- E5
        // seaE 契约：[-1,0] 内恒等，越界钳位
        int seaEOk = 0, seaEBad = 0;
        for (double v = -1.2; v <= 0.2; v += 0.02) {
            double got = CombineCandidate.seaE(v);
            double want = v < -1.0 ? -1.0 : (v > 0.0 ? 0.0 : v);
            if (Double.compare(got, want) == 0) seaEOk++;
            else {
                seaEBad++;
                System.out.printf(Locale.ROOT, "  [E5] seaE(%.3f) = %.6f, want %.6f%n", v, got, want);
            }
        }
        record("E5", "mapping-cases", seaEOk + seaEBad, true, "契约测试点数");
        record("E5", "mismatches", seaEBad, seaEBad == 0,
                "seaE() 必须：[-1,0] 内恒等、<-1 钳到 -1、>0 钳到 0");

        // ---------------- E6
        if (unlocked != null) {
            int leak = 0;
            for (int i = 0; i < PTS; i++) {
                double v = base.e(xs[i], zs[i]);
                if (v < 0.0) leak++;
            }
            record("E6", "base-negative-after-other-unlock", leak, leak == 0,
                    "★ 解锁另一个实例不得影响本实例（开关必须是实例级，不是静态状态）");
        }

        Files.writeString(out.resolve("checks.csv"), csv.toString());
        System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
    }

    private static void record(String id, String metric, double value, boolean ok, String note) {
        checks++;
        csv.append(String.format(Locale.ROOT, "%s,%s,%.9g,%s%n", id, metric, value,
                ok ? "PASS" : "FAIL"));
        System.out.printf(Locale.ROOT, "%-3s %-34s %14.6g  %s%n", id, metric, value,
                ok ? "PASS" : "FAIL");
        if (!ok) {
            System.out.println("SEA UNLOCK CHECK FAIL: " + id + " " + metric + " = " + value);
            System.out.println("  note: " + note);
            System.exit(1);
        }
    }

    private SeaUnlockCheck() { }
}
