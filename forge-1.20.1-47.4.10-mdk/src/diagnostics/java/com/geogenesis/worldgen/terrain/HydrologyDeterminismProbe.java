package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.process.ProcessContext;
import com.geogenesis.worldgen.process.ProcessRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【确定性 / 缓存淘汰重建】判据探针（2026-11-06 按门禁选择 1 重写）。
 *
 * <p>本项目铁律：地形 P 是 (冻结上下文, 坐标) 的纯函数，与世界生成顺序、
 * ProcessRuntime 的 completed-tile 缓存状态和线程调度无关。旧探针度量的是已归档
 * CellGenerator 侵蚀 tile 缓存，清理后判据会变成假绿；这里改为直接度量
 * {@link ProcessRuntime#metrics()} 与缓存淘汰后的逐位 P 输出。</p>
 *
 * <h2>判据（任何一条不满足即 System.exit(1)）</h2>
 * <ol>
 *   <li>冷上下文与「故意换查询顺序 + 用有界 completedTiles 预算淘汰缓存后重建」
 *       的所有样本：eB/yB/eP/yP/deltaE/deltaY/identity 的 double 原始位逐位一致。</li>
 *   <li>实际发生 tile 重建：after.builds 大于 before.builds、completed 达到且不超出预算；
 *       off-mode 负对照没有任何 tile 构建。</li>
 *   <li>每次采样结束后 failures/cancelled/flights/queued/active 必须为 0，
 *       builds == successful，且精确 P 的缓存 completed 大于 0。</li>
 * </ol>
 *
 * <pre>{@code gradlew runHydrologyDeterminismProbe [-PprobeArgs="seed"]}</pre>
 */
public final class HydrologyDeterminismProbe {
    private static final long DEFAULT_SEED = 12345L;
    private static final int BUDGET_TILES = 24;
    private static final int BUDGET_WORKERS = 1;
    private static final int BUDGET_QUEUE = 8;

    private record Point(double x, double z) {}
    private record Sample(String identity, long eB, long yB, long eP, long yP,
                          long deltaE, long deltaY) {
        static Sample of(ProcessRuntime.Sample s) {
            return new Sample(s.identity(), Double.doubleToRawLongBits(s.base().eB()),
                    Double.doubleToRawLongBits(s.base().yB()), Double.doubleToRawLongBits(s.eP()),
                    Double.doubleToRawLongBits(s.yP()), Double.doubleToRawLongBits(s.deltaE()),
                    Double.doubleToRawLongBits(s.deltaY()));
        }
    }

    private HydrologyDeterminismProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_SEED;
        System.out.printf("=== HydrologyDeterminismProbe seed=%d budgetTiles=%d ===%n",
                seed, BUDGET_TILES);
        boolean pass = true;
        try {
            pass &= testEvictionAndQueryOrder(seed);
            pass &= testOffModeNegativeControl();
        } catch (Throwable t) {
            System.out.println("总判定: FAIL（探针异常）");
            t.printStackTrace(System.out);
            System.exit(1);
        }
        System.out.println();
        System.out.printf("总判定: %s%n", pass ? "ALL PASS（缓存淘汰重建后 P 逐位一致）"
                : "FAILURES（P 依赖缓存状态或生命周期计数异常）");
        // Gradle JavaExec 只在退出码非零时失败；FAIL 必须由退出码承载，不能只打印。
        System.exit(pass ? 0 : 1);
    }

    private static boolean testEvictionAndQueryOrder(long seed) {
        System.out.println();
        System.out.println("[1] ProcessRuntime 缓存淘汰 / 换查询顺序后的精确 P 逐位一致性");
        ProcessContext context = context(seed, ProcessContext.Mode.PARTICLE);
        List<Point> points = points();
        List<Sample> warm;
        ProcessRuntime.Metrics beforeEviction;
        try (ProcessRuntime runtime = new ProcessRuntime(context,
                new ProcessRuntime.Budget(BUDGET_WORKERS, BUDGET_QUEUE, BUDGET_TILES))) {
            warm = query(runtime, points);
            beforeEviction = runtime.metrics();
            // 24 个 completed tile 槽装不下这些点及追加点所需的全部 owner：
            // 淘汰并重建。单 worker 消除同上下文并发采样；查询顺序同时反转，
            // 覆盖「邻居旧缓存状态 / 查询顺序」两个变量。
            List<Point> reversed = new ArrayList<>(points);
            Collections.reverse(reversed);
            List<Point> evicting = new ArrayList<>(reversed);
            evicting.add(new Point(240.25, -144.75));
            evicting.add(new Point(-240.25, 240.25));
            List<Sample> cold = query(runtime, evicting);
            ProcessRuntime.Metrics after = runtime.metrics();
            int same = 0;
            for (int i = 0; i < points.size(); i++) {
                if (warm.get(i).equals(cold.get(reversed.indexOf(points.get(i))))) same++;
            }
            boolean outputEqual = same == points.size();
            boolean rebuilt = after.builds() > beforeEviction.builds()
                    && after.completed() <= BUDGET_TILES
                    && (beforeEviction.completed() == BUDGET_TILES
                        || after.completed() == BUDGET_TILES);
            boolean counters = lifecycle(beforeEviction) && lifecycle(after)
                    && after.successful() >= beforeEviction.successful();
            System.out.printf("    点数=%d 逐位相等=%d/%d%n", points.size(), same, points.size());
            System.out.printf("    metrics before=%s%n", beforeEviction);
            System.out.printf("    metrics after =%s%n", after);
            System.out.printf("    tile 实际淘汰重建=%b；成功/失败/取消/在途/队列计数=%b%n",
                    rebuilt, counters);
            boolean ok = outputEqual && rebuilt && counters;
            System.out.printf("    判定: %s%n", ok ? "PASS" : "FAIL");
            return ok;
        }
    }

    private static boolean testOffModeNegativeControl() {
        System.out.println();
        System.out.println("[2] OFF 负对照：零 tile 构建 / 生命周期计数归零 / eP 逐位等于 eB（纯 B）");
        // 配置开关 -> 模式的唯一映射点。反过来写会让「关粒子」变成昂贵又无提示的错误。
        boolean mapping = ProcessContext.modeFor(false) == ProcessContext.Mode.OFF
                && ProcessContext.modeFor(true) == ProcessContext.Mode.PARTICLE;
        System.out.printf("    modeFor(false)=%s modeFor(true)=%s%n",
                ProcessContext.modeFor(false), ProcessContext.modeFor(true));
        try (ProcessRuntime runtime = new ProcessRuntime(context(DEFAULT_SEED, ProcessContext.Mode.OFF),
                new ProcessRuntime.Budget(BUDGET_WORKERS, BUDGET_QUEUE, BUDGET_TILES))) {
            List<Sample> samples = query(runtime, points());
            ProcessRuntime.Metrics m = runtime.metrics();
            boolean pureB = samples.stream().allMatch(s -> s.eP() == s.eB() && s.yP() == s.yB()
                    && s.deltaE() == 0L && s.deltaY() == 0L);
            boolean ok = mapping && pureB && m.builds() == 0 && m.successful() == 0
                    && m.completed() == 0 && lifecycle(m);
            long mismatch = samples.stream().filter(s -> s.eP() != s.eB() || s.yP() != s.yB()).count();
            System.out.printf("    OFF 下 eP!=eB 的点数=%d/%d（必须为 0）%n", mismatch, samples.size());
            System.out.printf("    metrics=%s%n", m);
            System.out.printf("    判定: %s%n", ok ? "PASS" : "FAIL");
            return ok;
        }
    }

    private static List<Sample> query(ProcessRuntime runtime, List<Point> points) {
        List<Sample> out = new ArrayList<>(points.size());
        try {
            for (Point p : points) out.add(Sample.of(runtime.exactP(p.x(), p.z())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted exact P query", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("exact P query failed", e.getCause());
        }
        ProcessRuntime.Metrics m = runtime.metrics();
        if (m.flights() != 0 || m.queued() != 0 || m.active() != 0) {
            throw new AssertionError("exact P query left in-flight work: " + m);
        }
        return out;
    }

    /** 完成的 tile 与成功构建恒等；不得有失败、取消、在途、排队或活跃构建。 */
    private static boolean lifecycle(ProcessRuntime.Metrics m) {
        return m.failures() == 0 && m.cancelled() == 0 && m.flights() == 0
                && m.queued() == 0 && m.active() == 0 && m.builds() == m.successful()
                && (m.builds() == 0 || (m.completed() > 0 && m.completed() <= BUDGET_TILES));
    }

    private static ProcessContext context(long seed, ProcessContext.Mode mode) {
        TerrainParams params = TerrainParams.defaults();
        return new ProcessContext(new ProcessContext.Definition(seed,
                "cleanup-determinism-probe", params,
                com.geogenesis.worldgen.core.BaseWorldSettings.defaults(),
                (int) params.minY(), (int) params.maxY(), mode,
                com.geogenesis.worldgen.erosion.ParticleErosionParams.unloadedDefaults(),
                false, 1.0f, true, "probe-frozen"));
    }

    /** 覆盖多个 48wu owner 及其对称 blend 边。 */
    private static List<Point> points() {
        double[] axes = {-96.001, -48.001, -48.0, -0.001, 0.0, 0.25, 23.5,
                47.999, 48.0, 48.1, 95.9, 123.75};
        List<Point> out = new ArrayList<>();
        for (double x : axes) for (double z : axes) out.add(new Point(x, z));
        return out;
    }
}
