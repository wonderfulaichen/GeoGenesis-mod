package com.geogenesis.diagnostics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 【全流程诊断】世界生成分段计时器（★ 2026-09-18，应"从创建世界开始测试"而建）。
 *
 * <h2>为什么需要（既有插桩的三个硬伤）</h2>
 * <ol>
 *   <li><b>阈值门控 ⇒ 看不见分布</b>：{@code [PERF-TERRAIN]} 只在 {@code >50ms} 打印、
 *       {@code [PERF] fillFromNoise} 只在 {@code >100ms} 打印 ⇒ <b>快的块完全没有记录</b>，
 *       无法统计"平均/P50/P95"，只能看到离群值 ⇒ 无法判断"整体是否变慢"。</li>
 *   <li><b>阶段盲区</b>：{@code applyBiomeDecoration}（原版特征：树/草/花/矿）、
 *       {@code applyCarvers}（洞穴雕刻）、{@code spawnOriginalMobs}、{@code buildSurface}
 *       <b>此前零插桩</b> —— 而它们都在 chunk 生成的关键路径上。</li>
 *   <li><b>无汇总</b>：只有逐条日志，没有"这批 chunk 共花多久 / 各阶段占比 / 最慢是哪块"。</li>
 * </ol>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li><b>默认关闭、零开销</b>：{@link #begin()} 在关闭时只做一次 volatile 读并返回 0，
 *       {@link #end} 直接返回 ⇒ 不调 {@code System.nanoTime()}。产出<b>逐位不变</b>。</li>
 *   <li><b>全阶段覆盖</b>：{@link Stage} 枚举覆盖从 engine 初始化到装饰/雕刻/生物生成的
 *       <b>每一个</b> chunk 生成阶段。</li>
 *   <li><b>统计量</b>：次数 / 总耗时 / 均值 / <b>P50 / P95 / max</b> / 占比
 *       （P95 用于识别"少数极慢块"，均值会掩盖它们）。</li>
 *   <li><b>最慢块 Top</b>：记录每块总耗时与坐标 ⇒ 直接定位到具体 chunk 去复查。</li>
 *   <li><b>线程安全</b>：chunk 生成是多线程的 ⇒ 全部走 {@link LongAdder}/{@link AtomicLong}；
 *       百分位样本用环形缓冲（写竞争只会覆盖个别样本，对诊断无影响）。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>
 *   config/geogenesis-common.toml:
 *     worldgenProfilerEnabled = true      # 打开
 *     worldgenProfilerEveryChunks = 200   # 每 200 块打一次滚动汇总
 * </pre>
 * <p>输出在 {@code logs/latest.log}，前缀 {@code [WGP]}。世界卸载时会打一次总计。</p>
 *
 * <p>⚠ <b>本类只做计时，不参与任何生成决策</b> ⇒ 不影响确定性（不消费任何随机数）。</p>
 */
public final class WorldGenProfiler {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis-wgp");

    /** chunk 生成阶段。顺序即日志中的显示顺序（按流水线先后）。 */
    public enum Stage {
        ENSURE("engine 初始化"),
        SAMPLE("地形采样"),
        EXTRACT("侵蚀tile提取"),
        HYDRO("水文雕刻"),
        PLACE("方块铺设"),
        DECORATE("原版装饰"),
        CARVE("洞穴雕刻"),
        SURFACE("地表构建"),
        MOBS("初始生物");

        final String label;
        Stage(String label) { this.label = label; }
    }

    /** 百分位样本容量（每阶段）。8192 样本 × 8B × 9 阶段 ≈ 590KB，可接受。 */
    private static final int SAMPLE_CAP = 8192;
    /** 最慢块记录容量。 */
    private static final int TOP_CAP = 8;

    // ===== 开关（volatile：热路径只读一次）=====
    private static volatile boolean enabled = false;
    private static volatile int reportEvery = 200;

    // ===== 每阶段统计 =====
    private static final LongAdder[] COUNT = new LongAdder[Stage.values().length];
    private static final LongAdder[] TOTAL_NS = new LongAdder[Stage.values().length];
    private static final AtomicLong[] MAX_NS = new AtomicLong[Stage.values().length];
    private static final long[][] SAMPLES = new long[Stage.values().length][];
    private static final AtomicInteger[] SAMPLE_CURSOR = new AtomicInteger[Stage.values().length];

    // ===== 每块总耗时（环形，含坐标）=====
    private static final int CHUNK_CAP = 2048;
    private static final long[] CHUNK_NS = new long[CHUNK_CAP];
    private static final int[] CHUNK_X = new int[CHUNK_CAP];
    private static final int[] CHUNK_Z = new int[CHUNK_CAP];
    private static final AtomicInteger CHUNK_CURSOR = new AtomicInteger();
    private static final LongAdder CHUNK_COUNT = new LongAdder();
    private static final LongAdder CHUNK_TOTAL_NS = new LongAdder();

    private static volatile long startedAtNs = 0L;
    private static final AtomicInteger SINCE_REPORT = new AtomicInteger();

    static {
        for (Stage s : Stage.values()) {
            int i = s.ordinal();
            COUNT[i] = new LongAdder();
            TOTAL_NS[i] = new LongAdder();
            MAX_NS[i] = new AtomicLong();
            SAMPLES[i] = new long[SAMPLE_CAP];
            SAMPLE_CURSOR[i] = new AtomicInteger();
        }
    }

    private WorldGenProfiler() { }

    // ==================================================================
    // 开关与生命周期
    // ==================================================================

    /**
     * 设置开关。由配置注入（世界加载时）。
     *
     * @param on    是否启用
     * @param every 每生成多少块打一次滚动汇总（&lt;=0 ⇒ 只在卸载时打总计）
     */
    public static void configure(boolean on, int every) {
        reportEvery = Math.max(0, every);
        boolean was = enabled;
        enabled = on;
        if (on && !was) {
            reset();
            LOGGER.info("[WGP] 全流程诊断已启用（每 {} 块打一次滚动汇总；世界卸载时打总计）", reportEvery);
            LOGGER.info("[WGP] 阶段：{}", stageHeader());
        } else if (!on && was) {
            LOGGER.info("[WGP] 全流程诊断已关闭");
        }
    }

    public static boolean enabled() { return enabled; }

    // ===== 自检入口（供 ProfilerSelfCheckProbe；生产不调用）=====

    /** 各阶段记录总数（自检用：关闭时必须为 0）。 */
    public static long debugTotalRecords() {
        long n = 0;
        for (Stage s : Stage.values()) n += COUNT[s.ordinal()].sum();
        return n;
    }

    /** 单块总耗时记录数（自检用）。 */
    public static long debugChunkRecords() { return CHUNK_COUNT.sum(); }

    /** 各阶段是否已记到（自检用：验证插桩点没漏接）。 */
    public static boolean[] debugStageHasRecords() {
        Stage[] all = Stage.values();
        boolean[] out = new boolean[all.length];
        for (int i = 0; i < all.length; i++) out[i] = COUNT[i].sum() > 0;
        return out;
    }

    /** 阶段名（自检用）。 */
    public static String debugStageName(int i) { return Stage.values()[i].label; }

    /** 手动打汇总（世界卸载 / 自检）。 */
    public static void reportNow(String reason) { report(reason); }

    /** 重置所有统计（新世界/新测试开始时调用）。 */
    public static void reset() {
        for (Stage s : Stage.values()) {
            int i = s.ordinal();
            COUNT[i].reset();
            TOTAL_NS[i].reset();
            MAX_NS[i].set(0L);
            SAMPLE_CURSOR[i].set(0);
            java.util.Arrays.fill(SAMPLES[i], 0L);
        }
        CHUNK_CURSOR.set(0);
        CHUNK_COUNT.reset();
        CHUNK_TOTAL_NS.reset();
        SINCE_REPORT.set(0);
        startedAtNs = System.nanoTime();
    }

    // ==================================================================
    // 计时（热路径：关闭时零开销）
    // ==================================================================

    /** 开始计时。关闭时返回 0（且不调 nanoTime）。 */
    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** 结束计时并记账。{@code t0 == 0}（关闭）时直接返回。 */
    public static void end(Stage stage, long t0) {
        if (t0 == 0L) return;
        record(stage, System.nanoTime() - t0);
    }

    /** 直接记账（已有耗时值，如既有插桩处）。关闭时零开销。 */
    public static void record(Stage stage, long ns) {
        if (!enabled) return;
        int i = stage.ordinal();
        COUNT[i].increment();
        TOTAL_NS[i].add(ns);
        long prev = MAX_NS[i].get();
        if (ns > prev) MAX_NS[i].compareAndSet(prev, ns);
        int c = SAMPLE_CURSOR[i].getAndIncrement();
        SAMPLES[i][Math.floorMod(c, SAMPLE_CAP)] = ns;
    }

    /**
     * 记录【单块总耗时】并（按需）触发滚动汇总。由 {@code fillFromNoise} 末尾调用一次。
     */
    public static void endChunk(int cx, int cz, long ns) {
        if (!enabled) return;
        CHUNK_COUNT.increment();
        CHUNK_TOTAL_NS.add(ns);
        int c = CHUNK_CURSOR.getAndIncrement();
        int k = Math.floorMod(c, CHUNK_CAP);
        CHUNK_NS[k] = ns;
        CHUNK_X[k] = cx;
        CHUNK_Z[k] = cz;
        if (reportEvery > 0 && SINCE_REPORT.incrementAndGet() >= reportEvery) {
            SINCE_REPORT.set(0);
            report("滚动汇总（每 " + reportEvery + " 块）");
        }
    }

    // ==================================================================
    // 汇总输出
    // ==================================================================

    /** 打一份汇总（世界卸载 / 滚动触发 / 手动）。关闭时静默。 */
    public static void report(String reason) {
        if (!enabled) return;
        long chunks = CHUNK_COUNT.sum();
        long totalNs = CHUNK_TOTAL_NS.sum();
        double elapsedS = startedAtNs == 0 ? 0 : (System.nanoTime() - startedAtNs) / 1e9;

        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== 全流程诊断（").append(reason).append("）===\n");
        // ⚠ 修正（2026-09-18，自检首轮发现）：原来 chunks==0 时【直接 return】，
        //   连阶段明细都不打 —— 但【离线探针路径不经过 fillFromNoise】⇒ CHUNK_COUNT 恒为 0，
        //   于是最需要看阶段明细的场景反而什么都看不到。现改为：chunks==0 时
        //   仍打印阶段明细，只把"块均总耗时"标为不可用。
        if (chunks == 0) {
            sb.append("chunk 数=0（未经过 fillFromNoise；阶段明细仍如下）\n");
        } else {
            sb.append(String.format("chunk 数=%d；块均总耗时=%.2f ms；自启用已过 %.1f s（含空载）%n",
                    chunks, totalNs / 1e6 / chunks, elapsedS));
        }
        sb.append(String.format("%-14s %8s %12s %9s %9s %9s %9s %8s%n",
                "阶段", "次数", "总耗时ms", "均ms", "P50", "P95", "max", "占比"));

        // 各阶段：占比按【该阶段总耗时 / 所有阶段总耗时之和】（阶段之间有重叠的可能，
        // 但本 profiler 只在 fillFromNoise 串行段与各回调内计时，基本互斥）
        long sumAll = 0;
        for (Stage s : Stage.values()) sumAll += TOTAL_NS[s.ordinal()].sum();
        for (Stage s : Stage.values()) {
            int i = s.ordinal();
            long n = COUNT[i].sum();
            long tot = TOTAL_NS[i].sum();
            if (n == 0) {
                sb.append(String.format("%-14s %8d %12s %9s %9s %9s %9s %8s%n",
                        s.label, 0, "-", "-", "-", "-", "-", "-"));
                continue;
            }
            double[] pct = percentiles(i);
            sb.append(String.format("%-14s %8d %12.1f %9.3f %9.3f %9.3f %9.3f %7.1f%%%n",
                    s.label, n, tot / 1e6, tot / 1e6 / n,
                    pct[0] / 1e6, pct[1] / 1e6, MAX_NS[i].get() / 1e6,
                    sumAll == 0 ? 0 : 100.0 * tot / sumAll));
        }

        // 最慢块 Top（从环形缓冲扫描，只报最近 CHUNK_CAP 块内的最慢者）
        if (chunks > 0) sb.append(topChunks());

        LOGGER.info("[WGP] {}", sb);
        // ★ 同时落一份【UTF-8 纯文本】到运行目录 —— Windows 终端是 GBK，中文日志会乱码，
        //   而 read_file 读 UTF-8 文件正常 ⇒ 便于直接查看（本项目既有教训：别信终端输出）。
        dumpToFile(sb.toString());
    }

    /** 汇总写文件（失败静默 —— 诊断工具绝不能因写盘失败而影响游戏）。 */
    private static void dumpToFile(String text) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("geogenesis-wgp.txt");
            java.nio.file.Files.writeString(p, text,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
            // 诊断输出失败不影响任何生成逻辑
        }
    }

    /** 阶段表头（启用时打印一次，便于对照）。 */
    public static String stageHeader() {
        StringBuilder sb = new StringBuilder();
        for (Stage s : Stage.values()) sb.append(s.label).append(' ');
        return sb.toString();
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** @return {P50, P95}（ms 的 ns 值；样本不足时用已有样本）。 */
    private static double[] percentiles(int stageIdx) {
        long[] src = SAMPLES[stageIdx];
        int n = Math.min(SAMPLE_CURSOR[stageIdx].get(), SAMPLE_CAP);
        if (n <= 0) return new double[]{0, 0};
        long[] copy = java.util.Arrays.copyOf(src, n);
        java.util.Arrays.sort(copy);
        double p50 = copy[(int) (n * 0.50)];
        double p95 = copy[Math.min(n - 1, (int) (n * 0.95))];
        return new double[]{p50, p95};
    }

    private static String topChunks() {
        int n = Math.min(CHUNK_CURSOR.get(), CHUNK_CAP);
        if (n <= 0) return "";
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Long.compare(CHUNK_NS[b], CHUNK_NS[a]));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("最慢块 Top%d（最近 %d 块内）:%n", Math.min(TOP_CAP, n), n));
        for (int i = 0; i < Math.min(TOP_CAP, n); i++) {
            int k = idx[i];
            sb.append(String.format("  chunk(%d,%d) = %.1f ms%n",
                    CHUNK_X[k], CHUNK_Z[k], CHUNK_NS[k] / 1e6));
        }
        return sb.toString();
    }
}
