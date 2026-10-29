package com.geogenesis.diagnostics;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 水文阶段计时、缓存计数和汇总快照，默认关闭。
 *
 * <p>系统属性 {@code -Dgeogenesis.hydrologyMetrics=true} 在类初始化时读取一次，
 * 与 TOML 无关。关闭时 {@code begin} 返回共享哨兵，记录入口跳过计时和统计；
 * 仍有类初始化、调用和分支成本，不保证零开销或耗时相同。</p>
 *
 * <p>阶段按操作名聚合为 {@code stage.<op>.count|.ns|.maxNs}，缓存计数使用
 * {@code cache.<domain>.<outcome>}，{@code count/add} 使用 {@code sum.<name>}。
 * {@code value.<name>} 为 last-wins；这些全局汇总不提供逐任务归因。
 * JFR 可用且事件被录制时，事件上的 {@code taskId/x/z/round} 可关联各任务。</p>
 *
 * <pre>
 *   long task = HydrologyMetrics.newTaskId();
 *   var s = HydrologyMetrics.begin("hydro.outlets", cx, cz, -1, task);
 *   try { ... } finally { HydrologyMetrics.end(s); }
 *   HydrologyMetrics.count("hydro.rounds", rounds);
 * </pre>
 *
 * <p>逐点采样应先累加调用方的局部计数，再用批量重载提交，避免逐点访问 map/LongAdder。
 * 开启计时会读取时钟、分配句柄，并可能创建 JFR 事件。JFR 探测或事件路径失败时
 * 停用相应事件路径，聚合统计仍可使用；这不是对所有记录 API 不抛异常的保证。
 * {@code snapshot/summary} 只构造返回值，不自行写文件或日志。</p>
 */
public final class HydrologyMetrics {

    /** 开关属性名（JVM 启动参数）。 */
    public static final String PROPERTY = "geogenesis.hydrologyMetrics";

    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    /** 是否能加载 jdk.jfr.Event；事件路径另有失败保护。 */
    private static final boolean JFR_PRESENT = ENABLED && probeJfr();

    /** 本类捕获到 JFR 路径失败后，停用后续事件操作。 */
    private static volatile boolean jfrBroken;

    /**
     * 缓存域（热路径按 {@code ordinal()} 索引，<b>不做字符串拼接</b>）。
     *
     * <p>新增域只需在此追加常量；{@link #snapshot()} 的 key 自动跟随。</p>
     */
    public enum Cache {
        /** {@code HydrologyPipeline} 的窗口地形采样直接映射缓存。 */
        WINDOW_SAMPLE,
        /** {@code CellGenerator} 的侵蚀 tile 缓存。 */
        EROSION_TILE,
        /** 水文 region 级缓存。 */
        REGION,
        /** pass1 skeleton / 轮廓缓存。 */
        PASS1,
        /** chunk 级 cells 缓存。 */
        CHUNK,
        /** 次级 tile / 段索引缓存。 */
        TILE,
        /** 未分类（自定义域请走 {@code cache(String,String)}）。 */
        OTHER
    }

    /** 缓存访问结果：MISS 为本层未命中，COMPUTE 为调用底层数据源，具体边界由插点定义。 */
    public enum Outcome {
        HIT, MISS, COMPUTE, WAIT, EVICT, INIT, DROP
    }

    private static final int OUTCOMES = Outcome.values().length;

    /** 单个操作的聚合（并发安全）。 */
    private static final class OpStat {
        final LongAdder count = new LongAdder();
        final LongAdder totalNs = new LongAdder();
        final AtomicLong maxNs = new AtomicLong();
    }

    /** 聚合容器仅在 ENABLED 为 true 时分配；哨兵和其他静态字段另行初始化。 */
    private static final class State {
        final ConcurrentHashMap<String, OpStat> ops = new ConcurrentHashMap<>();
        final LongAdder[] cache = new LongAdder[Cache.values().length * OUTCOMES];
        final ConcurrentHashMap<String, LongAdder> named = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Long> values = new ConcurrentHashMap<>();

        State() {
            for (int i = 0; i < cache.length; i++) cache[i] = new LongAdder();
        }

        OpStat op(String name) {
            OpStat st = ops.get(name);
            return st != null ? st : ops.computeIfAbsent(name, k -> new OpStat());
        }

        LongAdder named(String key) {
            LongAdder a = named.get(key);
            return a != null ? a : named.computeIfAbsent(key, k -> new LongAdder());
        }

        LongAdder counter(String name) {
            LongAdder a = counters.get(name);
            return a != null ? a : counters.computeIfAbsent(name, k -> new LongAdder());
        }
    }

    private static final State STATE = ENABLED ? new State() : null;

    private HydrologyMetrics() { }

    /** 插桩是否开启（关闭时调用方可以据此省掉包装 lambda）。 */
    public static boolean enabled() { return ENABLED; }

    // ==================================================================
    // 阶段：begin / end / note
    // ==================================================================

    /**
     * 一次阶段测量的句柄；启用时独占其 JFR 事件，关闭时复用 {@link #NONE}。
     * 可用 try-with-resources；{@link #close()} 等价于 {@link #end(Scope)}。
     * 重复串行结束只记一次，不支持多个线程同时结束同一句柄。
     */
    public static final class Scope implements AutoCloseable {
        static final Scope NONE = new Scope(null, null, 0L, null);
        /** JFR 事件（{@code jdk.jfr} 不可用时为 null）。类型故意收成 {@code Object}：
         *  避免在 {@code jdk.jfr} 缺失的 JVM 上因字段类型解析而链接失败。 */
        final Object event;
        final OpStat stat;
        final long t0;
        final Thread owner;
        boolean closed;

        private Scope(Object event, OpStat stat, long t0, Thread owner) {
            this.event = event;
            this.stat = stat;
            this.t0 = t0;
            this.owner = owner;
        }

        /** 等价于 {@link HydrologyMetrics#end(Scope)}；关闭插桩时提前返回。 */
        @Override
        public void close() {
            end(this);
        }
    }

    /** 开始一个阶段（x/z 用于把慢阶段直接定位到块）。 */
    public static Scope begin(String operation, int x, int z) {
        return begin(operation, x, z, -1, 0L);
    }

    /** 开始一个阶段，附带轮次（湖↔河耦合的逐轮归因）。 */
    public static Scope begin(String operation, int x, int z, int round) {
        return begin(operation, x, z, round, 0L);
    }

    /**
     * 开始一个阶段。
     *
     * @param operation 阶段名（常量字符串，禁止在此拼接变量）
     * @param x,z       块坐标（-1 表示无坐标）
     * @param round     轮次（-1 = 不适用）
     * @param taskId    {@link #newTaskId()} 领到的任务号（0 = 不关联）；同一任务的各阶段共用它，
     *                  据此可在 JFR 记录里把并发任务分组
     */
    public static Scope begin(String operation, int x, int z, int round, long taskId) {
        if (!ENABLED) return Scope.NONE;
        return start(operation, x, z, round, taskId);
    }

    private static Scope start(String operation, int x, int z, int round, long taskId) {
        if (operation == null) operation = "unknown";
        OpStat st = STATE.op(operation);          // 查找或创建聚合项；end 复用引用
        long t0 = System.nanoTime();
        Object ev = null;
        if (JFR_PRESENT && !jfrBroken) {
            try {
                ev = Jfr.newEvent(operation, x, z, round, taskId);
            } catch (Throwable t) {
                jfrBroken = true;                 // 只退掉事件，计数继续
            }
        }
        return new Scope(ev, st, t0, Thread.currentThread());
    }

    /** 结束阶段并记账；null、哨兵和串行重复调用直接返回。调用方应在 finally 中结束。 */
    public static void end(Scope scope) {
        if (!ENABLED || scope == null || scope == Scope.NONE || scope.closed) return;
        scope.closed = true;
        long ns = System.nanoTime() - scope.t0;
        OpStat st = scope.stat;
        if (st != null) {
            st.count.increment();
            st.totalNs.add(ns);
            long prev = st.maxNs.get();
            while (ns > prev && !st.maxNs.compareAndSet(prev, ns)) {
                prev = st.maxNs.get();
            }
        }
        Object ev = scope.event;
        if (ev != null && !jfrBroken) {
            try {
                Jfr.finish(ev, Thread.currentThread() != scope.owner);
            } catch (Throwable t) {
                jfrBroken = true;
            }
        }
    }

    /**
     * 创建并立即结束一条 JFR 事件，携带 taskId、坐标和标量；时长不保证为零。
     * 与 value 的共享最近值不同，录制的事件可按 taskId 关联。
     *
     * @param value  整型标量（例：converged 0/1）
     * @param amount 浮点标量（例：residual）；不适用填 0
     */
    public static void note(String operation, int x, int z, int round, long taskId,
                            long value, double amount) {
        if (!ENABLED || !JFR_PRESENT || jfrBroken) return;
        try {
            Jfr.note(operation, x, z, round, taskId, value, amount);
        } catch (Throwable t) {
            jfrBroken = true;
        }
    }

    /** 领一个任务号（关闭插桩时恒为 0，不递增）。 */
    public static long newTaskId() {
        return ENABLED ? TASK_SEQ.incrementAndGet() : 0L;
    }

    private static final AtomicLong TASK_SEQ = new AtomicLong();

    // ==================================================================
    // 缓存计数
    // ==================================================================

    /** 缓存计数（热路径重载：枚举下标 + LongAdder，无字符串、无 map）。 */
    public static void cache(Cache domain, Outcome outcome) {
        if (!ENABLED) return;
        STATE.cache[domain.ordinal() * OUTCOMES + outcome.ordinal()].increment();
    }

    /** 缓存计数（批量：一次调用记 {@code delta} 次，用于"入口只量一次总量"的场景）。 */
    public static void cache(Cache domain, Outcome outcome, long delta) {
        if (!ENABLED || delta == 0L) return;
        STATE.cache[domain.ordinal() * OUTCOMES + outcome.ordinal()].add(delta);
    }

    /**
     * 自定义域缓存计数（低频：拼接字符串、查找或创建聚合项；不要放进逐格循环）。
     *
     * <p>key = {@code domain + "." + outcome}，在 {@link #snapshot()} 中呈现为
     * {@code cache.<domain>.<outcome>}。</p>
     */
    public static void cache(String domain, String outcome) {
        if (!ENABLED || domain == null || outcome == null) return;
        STATE.named(domain + '.' + outcome).increment();
    }

    // ==================================================================
    // 标量累加
    // ==================================================================

    /**
     * 计数 +1，snapshot key 为 {@code sum.<name>}。
     * 逐点循环应先局部累加，再用 {@link #count(String, long)} 批量提交。
     */
    public static void count(String name) {
        if (!ENABLED || name == null) return;
        STATE.counter(name).increment();
    }

    /** 计数 {@code +delta}（批量交账；{@code delta == 0} 时不碰 map）。 */
    public static void count(String name, long delta) {
        if (!ENABLED || delta == 0L || name == null) return;
        STATE.counter(name).add(delta);
    }

    /** {@link #count(String, long)} 的别名，使用相同的 sum key。 */
    public static void add(String name, long delta) {
        count(name, delta);
    }

    /** 最近值（<b>last-wins</b>，并发下不可作归因；仅供单任务/离线自检读取）。 */
    public static void value(String name, long v) {
        if (!ENABLED || name == null) return;
        STATE.values.put(name, v);
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /**
     * 按字典序返回汇总；关闭插桩时为空表，计数为 0 的缓存枚举组合不出现。
     * 并发读取不是原子快照：count/ns 为 LongAdder 求和，maxNs 为 CAS 最大值，
     * value 为最近写入值；均不是逐任务视图。
     */
    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        if (!ENABLED) return out;
        for (Map.Entry<String, OpStat> e : STATE.ops.entrySet()) {
            String p = "stage." + e.getKey();
            OpStat st = e.getValue();
            out.put(p + ".count", st.count.sum());
            out.put(p + ".ns", st.totalNs.sum());
            out.put(p + ".maxNs", st.maxNs.get());
        }
        Cache[] domains = Cache.values();
        Outcome[] outcomes = Outcome.values();
        for (Cache d : domains) {
            for (Outcome o : outcomes) {
                long v = STATE.cache[d.ordinal() * OUTCOMES + o.ordinal()].sum();
                if (v != 0L) out.put("cache." + d + "." + o, v);
            }
        }
        for (Map.Entry<String, LongAdder> e : STATE.named.entrySet()) {
            out.put("cache." + e.getKey(), e.getValue().sum());
        }
        for (Map.Entry<String, LongAdder> e : STATE.counters.entrySet()) {
            out.put("sum." + e.getKey(), e.getValue().sum());
        }
        for (Map.Entry<String, Long> e : STATE.values.entrySet()) {
            out.put("value." + e.getKey(), e.getValue());
        }
        return out;
    }

    /** 人读汇总（多行；供离线探针/手动日志，<b>不写文件</b>）。 */
    public static String summary() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("HydrologyMetrics[enabled=").append(ENABLED)
          .append(", jfr=").append(JFR_PRESENT && !jfrBroken).append("]\n");
        for (Map.Entry<String, Long> e : snapshot().entrySet()) {
            sb.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** 清空统计；仅用于没有并发写入或未结束 Scope 的离线测试批次。 */
    public static void reset() {
        if (!ENABLED) return;
        STATE.ops.clear();
        for (LongAdder a : STATE.cache) a.reset();
        STATE.named.clear();
        STATE.counters.clear();
        STATE.values.clear();
    }

    // ==================================================================
    // JFR 适配：由调用入口捕获事件路径失败
    // ==================================================================

    private static boolean probeJfr() {
        try {
            Class.forName("jdk.jfr.Event", false, HydrologyMetrics.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** JFR 适配层，事件句柄用 Object 传递，避免外层签名直接依赖 jdk.jfr 类型。 */
    private static final class Jfr {

        static Object newEvent(String operation, int x, int z, int round, long taskId) {
            StageEvent ev = new StageEvent();
            ev.operation = operation;
            ev.x = x;
            ev.z = z;
            ev.round = round;
            ev.taskId = taskId;
            ev.begin();
            return ev;
        }

        static void finish(Object handle, boolean crossThread) {
            StageEvent ev = (StageEvent) handle;
            ev.crossThread = crossThread;
            ev.end();
            if (ev.shouldCommit()) ev.commit();
        }

        static void note(String operation, int x, int z, int round, long taskId,
                         long value, double amount) {
            StageEvent ev = (StageEvent) newEvent(operation, x, z, round, taskId);
            ev.value = value;
            ev.amount = amount;
            finish(ev, false);
        }
    }

    /** 通用阶段事件；关闭堆栈采集以减少开销，JFR 附带 startTime/duration/eventThread。 */
    @jdk.jfr.Name("geogenesis.hydrology.stage")
    @jdk.jfr.Label("GeoGenesis hydrology stage")
    @jdk.jfr.Category({"GeoGenesis", "Hydrology"})
    @jdk.jfr.Description("Local hydrology stage duration (task-local; disabled unless -Dgeogenesis.hydrologyMetrics=true)")
    @jdk.jfr.StackTrace(false)
    static final class StageEvent extends jdk.jfr.Event {
        @jdk.jfr.Label("Operation") String operation;
        @jdk.jfr.Label("Task") long taskId;
        @jdk.jfr.Label("Round") int round;
        @jdk.jfr.Label("Block X") int x;
        @jdk.jfr.Label("Block Z") int z;
        @jdk.jfr.Label("Ended on another thread") boolean crossThread;
        @jdk.jfr.Label("Value") long value;
        @jdk.jfr.Label("Amount") double amount;
    }
}
