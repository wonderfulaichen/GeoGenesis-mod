package com.geogenesis.worldgen.erosion;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.sun.management.ThreadMXBean;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>R3 侵蚀 cascade scratch 的"冻结基线 vs 候选"逐位等价探针</b>（★ 2026-10-28）。
 *
 * <h3>要证明什么</h3>
 * <p>{@code ErosionEngine.cascadeLocal} 里 {@code new float[8]} / {@code new int[8]} 是全图基线
 * JFR 的最大分配热点（采样加权各约 68GB，见
 * {@code build/hydrology-perf/baseline/jfr-summary.json}）。R3 把这份邻居 scratch 提升成
 * "每次 {@code runErosionOnFlat} 独占一份"，沿 {@code spawnAt → simulateDrop → cascadeLocal}
 * 以参数下传。<b>本探针只验证一件事：产出逐位不变</b>（浮点算术、邻居收集顺序、排序、
 * {@code curH} 快照、滴集合、{@code locked}、放电、取消语义全部不许动）。</p>
 *
 * <h3>怎么隔离两个实现</h3>
 * <ul>
 *   <li><b>冻结基线</b>（<b>只读，绝不写入</b>）：{@code build/hydrology-perf/baseline/main}
 *       的 {@code ErosionEngine.class}；</li>
 *   <li><b>候选</b>：本探针用 {@code javax.tools} 独立编译
 *       {@code src/main/.../ErosionEngine.java} → {@code build/hydrology-perf/erosion-candidate}
 *       （<b>不跑 Gradle、不碰主树产物</b>）；</li>
 *   <li>两者各用一个 <b>child-first {@link URLClassLoader}</b>：只对
 *       {@code com.geogenesis.worldgen.erosion.ErosionEngine*} 生效，其余类
 *       （{@code GeoGenesisConfig} / Forge / log4j）走父加载器 ⇒ 配置与静态状态共享，
 *       差异只可能来自被测引擎源码。</li>
 *   <li><b>装配自检</b>（否则比对会静默退化成"自己比自己"）：冻结类 {@code cascadeLocal}
 *       必须 6 参、候选 7 参、两侧 {@code .class} 字节 SHA-256 必须不同、
 *       候选必须含 {@code CascadeScratch} 内部类、两个 Class/loader 必须不同。</li>
 * </ul>
 *
 * <h3>覆盖矩阵</h3>
 * <ul>
 *   <li>多 seed（地形场维度）+ hs ∈ {1.0, 2.0, 1.3} + 正/负 tile 原点；</li>
 *   <li>{@code locked}（私有重载，反射调）、{@code str=0} 早退、{@code str<1}；</li>
 *   <li>XS 微笔刷开关、{@code dischargeOut=null} 与 {@code !=null}、三个 public 重载；</li>
 *   <li>64² 与 128² 合成 flat（低成本，不启动生产全图 / 不重负载），外加 sz=16 临界与
 *       hs 归一化分支；</li>
 *   <li><b>并发同一 engine 实例</b>：多线程共享一个引擎、各自独立 buffer，结果须等于串行参考；
 *       且以 frozen 自身并发 == 串行作为"用例本身无噪声"的对照。</li>
 *   <li><b>取消 + 清位重试</b>（all/fingerprint）：每侧专用线程内同一 engine 先正常运行，
 *       再预置 interrupt 运行、清位后用新 buffer 重试；比较两侧异常/中断位及重试逐位结果，
 *       不以取消阶段或耗时为判据。</li>
 * </ul>
 *
 * <h3>变异反控（隔离在 build/ 下，主树与 baseline 都不动）</h3>
 * <p>把候选源码做一次文本变异 → 独立编译到
 * {@code build/hydrology-perf/erosion-mutants/<kind>/classes} → <b>期望探针能抓到</b>。
 * 抓不到 = 断言没有分辨力 → 判 FAIL（退出码 3）。</p>
 * <ul>
 *   <li>{@code stale-count}：{@code int num = 0;} → {@code int num = 7;}（scratch 有效数不重填、
 *       未用槽位被读）——应由串行矩阵抓到；</li>
 *   <li>{@code shared-scratch}：scratch 改 static 单例（跨线程共享）——只有并发用例能抓到。</li>
 * </ul>
 *
 * <h3>CLI / 退出码</h3>
 * <pre>
 *   java -cp "&lt;probe-classes&gt;;&lt;classpath.txt&gt;" com.geogenesis.worldgen.erosion.ErosionScratchProbe \
 *        [--mode=all|matrix|fingerprint|bench|selftest|help] [--frozen=DIR] [--candidate=DIR] \
 *        [--source=FILE] [--out=FILE|off] [--toml=FILE] [--mutant-root=DIR] [--compile=on|off] \
 *        [--mutation=on|off] [--reps=N] [--warmup=N] [--threads=N] [--quick=on|off]
 * </pre>
 * <ul>
 *   <li><b>0</b> 全通过 · <b>1</b> 逐位/断言不一致 · <b>2</b> 环境或装配错误（缺类、编译失败、
 *       候选未重编、写入越界） · <b>3</b> 变异反控未检出（断言无分辨力）</li>
 * </ul>
 *
 * <p>⚠ 未传 {@code --toml} 时保留原 {@code cfgDbl/cfgInt} fallback（dropsMul=1、
 * erodeMul=1、iterations=2、cascadeStrength=0.8、momentumTransfer=1、lrate=0.1），
 * <b>不代表用户 TOML 验收</b>。传入时只加载 {@code build/hydrology-perf/} 下唯一运行目录的
 * 副本，输出输入 SHA-256 / SPEC.isLoaded / configFingerprint / Forge 校正状态及实际侵蚀参数；
 * 显式加载失败判 setup error，绝不静默 fallback、绝不保存原件。未指定 --out 时，TOML 模式的
 * 指纹也放进该唯一目录，不覆盖已有证据。</p>
 *
 * <p>⚠ {@code all}/{@code fingerprint} 另跑「预置 interrupt + 清位重试」：同一 engine 实例先取正常结果，
 * 再在<b>专用 daemon 线程</b>置位后 {@code runOn}，只比较冻结/候选两臂的异常类型+消息与中断位；
 * 同线程清位后用 fresh buffers 重试，要求各自 == 之前的正常结果且两臂逐位相同。
 * 不把耗时/取消发生阶段当判据；未命中取消时如实降级为「两臂一致 + retry 可恢复」。</p>
 *
 * <p>⚠ {@code ErosionEngine} 构造器<b>不消费</b> dropsMul/seed（只有 xsEnabled 被保存），
 * 所以"多 seed"通过<b>合成地形场</b>实现（液滴集合由地形坡度 + 世界坐标哈希决定）。</p>
 */
public final class ErosionScratchProbe {

    static final String ENGINE_CLASS = "com.geogenesis.worldgen.erosion.ErosionEngine";
    /** child-first 前缀：同时覆盖 {@code ErosionEngine$HardGrid} / {@code $CascadeScratch}。 */
    static final String ENGINE_PREFIX = "com.geogenesis.worldgen.erosion.ErosionEngine";
    static final String CLASS_REL = "com/geogenesis/worldgen/erosion/ErosionEngine.class";

    static final String FROZEN_DEFAULT = "build/hydrology-perf/baseline/main";
    static final String CANDIDATE_DEFAULT = "build/hydrology-perf/erosion-candidate";
    static final String SOURCE_DEFAULT = "src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java";
    static final String OUT_DEFAULT = "build/hydrology-perf/erosion-scratch-fingerprint.json";
    static final String MUTANT_DEFAULT = "build/hydrology-perf/erosion-mutants";

    static final String USAGE =
            "usage: ErosionScratchProbe [--mode=all|matrix|fingerprint|bench|selftest|help]\n"
          + "       [--frozen=DIR] [--candidate=DIR] [--source=FILE] [--out=FILE|off] [--toml=FILE]\n"
          + "       [--mutant-root=DIR] [--compile=on|off] [--mutation=on|off]\n"
          + "       [--reps=N] [--warmup=N] [--threads=N] [--quick=on|off]\n"
          + "  0=PASS 1=mismatch 2=setup error 3=mutation control not detected\n";

    private ErosionScratchProbe() {}

    // ==================================================================================
    // 入口
    // ==================================================================================

    public static void main(String[] argv) {
        Args a;
        try {
            a = Args.parse(argv);
        } catch (RuntimeException e) {
            System.err.println("[args] " + e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
            return;
        }
        if ("help".equals(a.mode)) {
            System.out.print(USAGE);
            return;
        }
        int code;
        try (Run run = new Run(a)) {
            code = run.execute();
        } catch (Throwable t) {
            System.out.println("**[FAIL] 探针异常终止: " + t);
            t.printStackTrace(System.out);
            code = 2;
        }
        System.out.println("\n=== exit " + code + " ("
                + (code == 0 ? "PASS" : code == 1 ? "MISMATCH" : code == 2 ? "SETUP ERROR"
                   : code == 3 ? "MUTATION CONTROL NOT DETECTED" : "?") + ") ===");
        System.exit(code);
    }

    // ==================================================================================
    // 参数
    // ==================================================================================

    static final class Args {
        Path frozen = Path.of(FROZEN_DEFAULT);
        Path candidate = Path.of(CANDIDATE_DEFAULT);
        Path source = Path.of(SOURCE_DEFAULT);
        Path out = Path.of(OUT_DEFAULT);
        Path toml;
        Path mutantRoot = Path.of(MUTANT_DEFAULT);
        boolean outExplicit;
        String mode = "all";
        boolean compile = true;
        boolean mutation = true;
        boolean writeFingerprint = true;
        int reps = 9;
        int warmup = 3;
        int threads = 4;
        boolean quick = false;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (String raw : argv) {
                if (raw == null || raw.isBlank()) continue;
                if (!raw.startsWith("--")) throw new IllegalArgumentException("不认识的参数: " + raw);
                String body = raw.substring(2);
                int eq = body.indexOf('=');
                String key = eq < 0 ? body : body.substring(0, eq);
                String val = eq < 0 ? "on" : body.substring(eq + 1);
                switch (key) {
                    case "mode" -> a.mode = val;
                    case "frozen" -> a.frozen = Path.of(val);
                    case "candidate" -> a.candidate = Path.of(val);
                    case "source" -> a.source = Path.of(val);
                    case "mutant-root" -> a.mutantRoot = Path.of(val);
                    case "toml" -> {
                        if (eq < 0 || val.isBlank()) throw new IllegalArgumentException("--toml 需要文件路径");
                        a.toml = Path.of(val);
                    }
                    case "out" -> {
                        a.outExplicit = true;
                        a.writeFingerprint = !"off".equalsIgnoreCase(val);
                        if (a.writeFingerprint) a.out = Path.of(val);
                    }
                    case "compile" -> a.compile = truthy(val);
                    case "mutation" -> a.mutation = truthy(val);
                    case "reps" -> a.reps = Integer.parseInt(val);
                    case "warmup" -> a.warmup = Integer.parseInt(val);
                    case "threads" -> a.threads = Integer.parseInt(val);
                    case "quick" -> a.quick = truthy(val);
                    default -> throw new IllegalArgumentException("未知开关 --" + key);
                }
            }
            if (!List.of("all", "matrix", "fingerprint", "bench", "selftest", "help").contains(a.mode)) {
                throw new IllegalArgumentException("未知 mode=" + a.mode);
            }
            return a;
        }

        private static boolean truthy(String v) {
            return "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v)
                    || "1".equals(v) || "yes".equalsIgnoreCase(v);
        }
    }

    // ==================================================================================
    // 判定汇总
    // ==================================================================================

    static final class Verdict {
        int checks;
        int failures;
        int undetected;
        int setupErrors;

        void ok(String what) {
            checks++;
            System.out.println("  [PASS] " + what);
        }

        void fail(String what, String detail) {
            checks++;
            failures++;
            System.out.println("  **[FAIL] " + what + " -- " + detail);
        }

        void undetected(String what, String detail) {
            checks++;
            undetected++;
            System.out.println("  **[MUT-UNDETECTED] " + what + " -- " + detail);
        }

        void setup(String what, String detail) {
            checks++;
            setupErrors++;
            System.out.println("  **[SETUP] " + what + " -- " + detail);
        }

        void info(String s) {
            System.out.println("  [info] " + s);
        }

        int exitCode() {
            if (setupErrors > 0) return 2;
            if (failures > 0) return 1;
            if (undetected > 0) return 3;
            return 0;
        }
    }

    // ==================================================================================
    // 用例 / 结果
    // ==================================================================================

    /** 走哪个重载（覆盖全部 public 入口 + 私有 locked 入口）。 */
    enum Entry { PUBLIC8, PUBLIC9, PUBLIC10, PRIVATE11 }

    record Case(String name, Entry entry, int bufSize, int sz, int ox, int oz,
                float seaNorm, float str, float hs, boolean xs, boolean locked,
                boolean discharge, long seed) {

        int pad() {
            return (bufSize - sz) / 2;
        }
    }

    record Outcome(float[] flat, float[] discharge, String error, long nanos) {
        static Outcome failed(String msg) {
            return new Outcome(null, null, msg, 0L);
        }
    }

    record InterruptTrial(Outcome normal, Outcome interrupted, boolean interruptedAfter,
                          boolean retryStartsClear, Outcome retry, boolean retryInterrupted) {}

    // ==================================================================================
    // 主流程
    // ==================================================================================

    static final class Run implements AutoCloseable {

        final Args args;
        final Path frozenDir;
        final Path candidateDir;
        final Verdict v = new Verdict();
        EngineRef frozen;
        EngineRef candidate;
        List<Case> cases = List.of();
        final Map<String, float[]> templateCache = new LinkedHashMap<>();
        final Map<String, boolean[][]> lockedCache = new LinkedHashMap<>();
        CommentedFileConfig config;
        Path configRunDir;
        Path configCopy;
        String tomlSha = "not-requested";
        boolean configLoaded;
        boolean configCorrected;
        long configFingerprint;
        String erosionConfig = "engine fallback (SPEC not loaded)";

        Run(Args args) {
            this.args = args;
            this.frozenDir = args.frozen.toAbsolutePath().normalize();
            this.candidateDir = args.candidate.toAbsolutePath().normalize();
        }

        int execute() {
            System.out.println("=== ErosionScratchProbe (R3 cascade scratch) ===");
            System.out.println("  java      : " + System.getProperty("java.version")
                    + " (" + System.getProperty("java.vm.name") + ")");
            System.out.println("  workdir   : " + Path.of("").toAbsolutePath());
            System.out.println("  frozen    : " + frozenDir + "   [READ-ONLY]");
            System.out.println("  candidate : " + candidateDir);
            System.out.println("  source    : " + args.source.toAbsolutePath().normalize());
            System.out.println("  mode      : " + args.mode);

            if (loadConfig() != 0) return v.exitCode();
            if (prepare() != 0) return v.exitCode();
            if (wireCheck() != 0) return v.exitCode();

            cases = matrix(args.quick);
            v.info("用例矩阵: " + cases.size() + " 例（quick=" + args.quick + "）");

            switch (args.mode) {
                case "selftest" -> SelfTest.run(this, v);
                case "bench" -> benchSection();
                case "matrix" -> matrixSection();
                case "fingerprint" -> {
                    matrixSection();
                    concurrencySection();
                    cancellationSection();
                }
                default -> {
                    matrixSection();
                    concurrencySection();
                    cancellationSection();
                    mutationSection();
                }
            }

            if (v.failures == 0 && v.setupErrors == 0 && v.undetected == 0) {
                System.out.println("\n  [PASS] 全部 " + v.checks + " 项检查通过");
            } else {
                System.out.println("\n  汇总: checks=" + v.checks + " failures=" + v.failures
                        + " setup=" + v.setupErrors + " mutation-undetected=" + v.undetected);
            }
            return v.exitCode();
        }

        // ---------------------------------------------------------------- 准备 / 装配

        int loadConfig() {
            if (args.toml == null) {
                v.info("--toml 未传：未加载 TOML，保留原 cfgDbl/cfgInt fallback；非 TOML 生产验收");
                v.info("fallback: dropsMul=1 erodeMul=1 cascadeStrength=0.8"
                        + " momentumTransfer=1 iterations=2 lrate=0.1（未采集 TOML sha/fingerprint）");
                return 0;
            }
            Path source = args.toml.toAbsolutePath().normalize();
            try {
                byte[] input = Files.readAllBytes(source);
                tomlSha = sha256Hex(input);
                Path root = Path.of("build/hydrology-perf").toAbsolutePath().normalize();
                if (!safeScratch(root.resolve("erosion-scratch-run"))) {
                    throw new IOException("配置副本必须在 build/hydrology-perf/ 非冻结目录内");
                }
                Files.createDirectories(root);
                configRunDir = Files.createTempDirectory(root, "erosion-scratch-run-");
                Path snapshot = configRunDir.resolve("config-input.toml");
                configCopy = configRunDir.resolve("config-effective.toml");
                Files.write(snapshot, input, StandardOpenOption.CREATE_NEW);
                Files.copy(snapshot, configCopy); // 不覆盖；SPEC 只持有工作副本，绝不持有原件
                if (!args.outExplicit) args.out = configRunDir.resolve("fingerprint.json");
                config = CommentedFileConfig.of(configCopy, TomlFormat.instance());
                config.load();
                configCorrected = !GeoGenesisConfig.SPEC.isCorrect(config);
                GeoGenesisConfig.SPEC.setConfig(config); // 必须早于两个 engine loader 及任何 .get()
                configLoaded = GeoGenesisConfig.SPEC.isLoaded();
                configFingerprint = GeoGenesisConfig.configFingerprint();
                if (!configLoaded || configFingerprint == 0L) {
                    throw new IllegalStateException("TOML 未真实加载或配置指纹不可用: loaded="
                            + configLoaded + " fingerprint=" + configFingerprint);
                }
                erosionConfig = erosionSettings(); // 直接 .get()，失败不伪装成 fallback 成功
                config.save(); // 仅保存 build/ 下工作副本的 Forge 校正结果
                if (!tomlSha.equals(sha256File(source))) {
                    throw new IOException("读取期间原 TOML 内容发生变化，不能宣称同一输入");
                }
                reportConfig(source);
                return 0;
            } catch (Throwable t) {
                v.setup("加载 --toml（不静默 fallback）", t.getClass().getName() + ": " + t.getMessage());
                v.info("toml=" + source + " sha256=" + tomlSha + " loaded=" + configLoaded
                        + " fingerprint=" + configFingerprint + " copy=" + configCopy);
                return 2;
            }
        }

        String erosionSettings() {
            GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
            return "dropsMul=" + c.erosionDropsMul.get() + " erodeMul=" + c.erosionErodeMul.get()
                    + " cascadeStrength=" + c.erosionCascadeStrength.get()
                    + " momentumTransfer=" + c.erosionMomentumTransfer.get()
                    + " iterations=" + c.erosionIterations.get() + " lrate=" + c.erosionLrate.get();
        }

        void reportConfig(Path source) throws IOException {
            String text = "toml=" + source + "\nsha256=" + tomlSha
                    + "\ncopy=" + configCopy + "\nloaded=" + configLoaded
                    + "\nfingerprint=" + configFingerprint + "\ncorrectedByForge=" + configCorrected
                    + "\nerosion=" + erosionConfig + "\njava=" + System.getProperty("java.version")
                    + "\nsourceReadOnly=true\n";
            Files.writeString(configRunDir.resolve("config-provenance.txt"), text,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            v.info(text.stripTrailing());
            v.info("唯一运行目录 → " + configRunDir);
            if (configCorrected) v.info("TOML 已由 Forge 校正；实际值以工作副本及上述读取值为准");
            v.info("str/hs/xs 仍由合成矩阵指定；本探针不是生产全图验收");
        }

        int prepare() {
            if (!Files.isDirectory(frozenDir)) {
                v.setup("冻结基线目录", "不存在: " + frozenDir);
                return 2;
            }
            if (!Files.isRegularFile(frozenDir.resolve(CLASS_REL))) {
                v.setup("冻结基线类", "缺 " + frozenDir + "/" + CLASS_REL
                        + "（--frozen 是否指向 build/hydrology-perf/baseline/main？）");
                return 2;
            }
            // ★ 只读保护：候选/变异输出不得落在 baseline 内，也不得写进 src/
            if (candidateDir.startsWith(frozenDir)) {
                v.setup("候选输出目录", "不得位于冻结基线内: " + candidateDir);
                return 2;
            }
            if (unsafeWrite(candidateDir)) {
                v.setup("候选输出目录", "必须位于 build/ 下（不得写 src/）: " + candidateDir);
                return 2;
            }
            if (args.compile) {
                String err = Compile.engine(args.source, candidateDir);
                if (err != null) {
                    v.setup("编译候选", err);
                    return 2;
                }
                v.info("已用 javac 独立重编候选 → " + candidateDir);
            } else if (!Files.isRegularFile(candidateDir.resolve(CLASS_REL))) {
                v.setup("候选类", "缺 " + candidateDir + "/" + CLASS_REL
                        + "（--compile=on 或先手工 javac）");
                return 2;
            }
            return 0;
        }

        boolean unsafeWrite(Path p) {
            String s = p.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            return s.contains("/src/") || s.endsWith("/src") || s.startsWith("src/");
        }

        int wireCheck() {
            try {
                frozen = EngineRef.load(frozenDir);
                candidate = EngineRef.load(candidateDir);
            } catch (Exception e) {
                v.setup("加载引擎类", String.valueOf(e));
                return 2;
            }
            v.info("frozen    loader=" + frozen.type.getClassLoader());
            v.info("candidate loader=" + candidate.type.getClassLoader());

            int fp = frozen.cascadeParamCount();
            int cp = candidate.cascadeParamCount();
            boolean candScratch = candidate.hasNested("CascadeScratch");
            boolean frozenScratch = frozen.hasNested("CascadeScratch");
            String shaF = sha256File(frozenDir.resolve(CLASS_REL));
            String shaC = sha256File(candidateDir.resolve(CLASS_REL));

            v.info("cascadeLocal 参数: frozen=" + fp + "（R3 前=6） candidate=" + cp + "（R3 后=7）");
            v.info("CascadeScratch 内部类: frozen=" + frozenScratch + " candidate=" + candScratch);
            v.info("ErosionEngine.class sha256: frozen=" + shaF);
            v.info("ErosionEngine.class sha256: candidate=" + shaC);

            boolean isolated = frozen.type != candidate.type
                    && frozen.type.getClassLoader() != candidate.type.getClassLoader();
            if (!isolated) {
                v.setup("类隔离", "两侧加载到同一个 Class/loader —— 比对会退化成自比");
                return 2;
            }
            if (fp != 6) {
                v.setup("冻结基线形状异常", "cascadeLocal 参数=" + fp + "（期望 6 = R3 前）");
                return 2;
            }
            if (cp != 7) {
                v.setup("候选未含 R3 改动", "cascadeLocal 参数=" + cp + "（期望 7；候选是否忘了重编？）");
                return 2;
            }
            if (!candScratch || frozenScratch) {
                v.setup("CascadeScratch 形状", "candidate 有=" + candScratch + " frozen 有=" + frozenScratch);
                return 2;
            }
            if (shaF.equals(shaC)) {
                v.setup("候选与冻结字节相同", "候选没有真正重编（sha256 一致）");
                return 2;
            }
            v.ok("装配自检（双 ClassLoader 隔离 + 候选确含 R3 结构改动）");
            return 0;
        }

        // ---------------------------------------------------------------- 逐位等价矩阵

        void matrixSection() {
            System.out.println("\n--- [1] 逐位等价矩阵（frozen vs candidate） ---");
            Map<String, String> perCase = new LinkedHashMap<>();
            int diffCount = 0;
            for (Case c : cases) {
                float[] tpl = template(c);
                boolean[][] lk = locked(c);
                Outcome a = frozen.run(c, tpl, lk);
                Outcome b = candidate.run(c, tpl, lk);
                String diff = compare(c, a, b);
                if (diff == null) {
                    v.ok(c.name() + "  " + summary(c));
                } else {
                    v.fail(c.name() + "  " + summary(c), diff);
                    diffCount++;
                }
                perCase.put(c.name(), fingerprintOf(c, a));
                checkInvariants(c, b, tpl);
            }
            v.info("矩阵 " + cases.size() + " 例，不一致 " + diffCount + " 例");
            if (args.writeFingerprint) writeFingerprint(perCase);
        }

        /** 早退 / locked 语义的独立断言（只看候选结果，不依赖 frozen 对照）。 */
        void checkInvariants(Case c, Outcome o, float[] tpl) {
            if (o.error() != null) {
                v.fail(c.name() + " 执行异常", o.error());
                return;
            }
            if (c.str() <= 0f) {
                if (bitsEqual(tpl, o.flat())) {
                    v.ok(c.name() + " 早退语义: str<=0 → flat 未改");
                } else {
                    v.fail(c.name() + " 早退语义", "str<=0 却改动了 flat");
                }
            }
            if (c.locked()) {
                boolean[][] lk = locked(c);
                int bad = 0;
                for (int z = 0; z < c.sz(); z++) {
                    for (int x = 0; x < c.sz(); x++) {
                        if (!lk[z][x]) continue;
                        int i = (z + c.pad()) * c.bufSize() + (x + c.pad());
                        if (Float.floatToRawIntBits(o.flat()[i]) != Float.floatToRawIntBits(tpl[i])) bad++;
                    }
                }
                if (bad == 0) {
                    v.ok(c.name() + " locked 语义: 锁定格全部复原");
                } else {
                    v.fail(c.name() + " locked 语义", bad + " 个 locked 格被改动");
                }
            }
        }

        // ---------------------------------------------------------------- 并发

        void concurrencySection() {
            System.out.println("\n--- [2] 并发同一 engine 实例（跨调用不串状态） ---");
            List<Case> pool = new ArrayList<>();
            for (Case c : cases) {
                if (c.bufSize() == 64 && c.str() > 0f && pool.size() < 4) pool.add(c);
            }
            if (pool.size() < 2) {
                v.info("可用并发用例不足 → 跳过");
                return;
            }
            List<Case> order = new ArrayList<>(pool);
            order.addAll(pool);            // 每个 case 被两个任务同时跑 → 并发同输入

            Map<String, Outcome> seqFrozen = new LinkedHashMap<>();
            Map<String, Outcome> seqCand = new LinkedHashMap<>();
            int seqDiff = 0;
            for (int i = 0; i < order.size(); i++) {
                Case c = order.get(i);
                Outcome a = frozen.run(c, template(c), locked(c));
                Outcome b = candidate.run(c, template(c), locked(c));
                seqFrozen.put(key(order, i), a);
                seqCand.put(key(order, i), b);
                if (compare(c, a, b) != null) seqDiff++;
            }
            if (seqDiff == 0) {
                v.ok("并发用例池串行一致（" + order.size() + " 次）");
            } else {
                v.fail("并发用例池串行不一致", seqDiff + " 处");
            }

            Map<String, Outcome> concFrozen = concurrent(frozen, order);
            Map<String, Outcome> concCand = concurrent(candidate, order);
            int badFrozen = mismatches(order, seqFrozen, concFrozen, "frozen 并发");
            int badCand = mismatches(order, seqCand, concCand, "candidate 并发");

            if (badFrozen == 0) {
                v.ok("对照组: frozen 自身并发 == 串行（证明用例本身无噪声）");
            } else {
                v.fail("对照组: frozen 并发 != 串行", badFrozen + " 处 —— 用例不可信");
            }
            if (badCand == 0) {
                v.ok("candidate 并发（同一 engine，" + Math.min(args.threads, order.size())
                        + " 线程）== 串行参考（无跨调用状态泄漏）");
            } else {
                v.fail("candidate 并发 != 串行", badCand + " 处 —— scratch 疑似被共享");
            }
        }

        /** 一个 engine 实例（按 xs 开关分组）× 多线程；每个任务独立 buffer。 */
        Map<String, Outcome> concurrent(EngineRef ref, List<Case> order) {
            int threads = Math.max(1, Math.min(args.threads, order.size()));
            Map<String, Outcome> out = new ConcurrentHashMap<>();
            Map<Boolean, Object> engines = Map.of(false, ref.newEngine(false), true, ref.newEngine(true));
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> fs = new ArrayList<>();
            try {
                for (int i = 0; i < order.size(); i++) {
                    Case c = order.get(i);
                    String k = key(order, i);
                    Object engine = engines.get(c.xs());
                    Future<?> f = pool.submit(() -> {
                        try {
                            start.await(60, TimeUnit.SECONDS);
                            out.put(k, ref.runOn(engine, c, template(c), locked(c)));
                        } catch (Throwable t) {
                            out.put(k, Outcome.failed(t.getClass().getSimpleName() + ": " + t.getMessage()));
                        }
                        return null;
                    });
                    fs.add(f);
                }
                start.countDown();
                for (Future<?> f : fs) f.get(240, TimeUnit.SECONDS);
            } catch (Exception e) {
                v.fail("并发执行", String.valueOf(e));
            } finally {
                pool.shutdownNow();
            }
            return out;
        }

        int mismatches(List<Case> order, Map<String, Outcome> ref, Map<String, Outcome> got, String tag) {
            int bad = 0;
            for (int i = 0; i < order.size(); i++) {
                String k = key(order, i);
                Outcome a = ref.get(k);
                Outcome b = got.get(k);
                String d = (a == null || b == null) ? "缺结果 (ref=" + (a != null) + ", got=" + (b != null) + ")"
                        : compare(order.get(i), a, b);
                if (d != null) {
                    bad++;
                    if (bad <= 3) System.out.println("      " + tag + " @" + order.get(i).name() + ": " + d);
                }
            }
            return bad;
        }

        String key(List<Case> order, int i) {
            return order.get(i).name() + "#" + i;
        }

        // ---------------------------------------------------------------- 取消 / 清位重试（只验正确性，不测耗时）

        void cancellationSection() {
            System.out.println("\n--- [2b] 预置 interrupt + 同一 engine 清位重试 ---");
            Case c = cases.get(0); // 固定非早退 public10 / hs=2 / discharge 用例，quick 下也保留
            v.info("case=" + c.name() + "  " + summary(c));
            v.info("范围：比较两臂异常类型/消息、中断位与 retry；不假定取消阶段、固定异常或时延");
            InterruptTrial a = interruptTrial(frozen, c, "frozen");
            InterruptTrial b = interruptTrial(candidate, c, "candidate");
            if (a == null || b == null) return; // 子线程异常/缺结果已记 FAIL，不能静默绿
            checkRaw(c, a.normal(), b.normal(), "取消前串行 frozen == candidate");
            v.info("预置 interrupt: frozen=" + a.interrupted().error() + " flag=" + a.interruptedAfter()
                    + " candidate=" + b.interrupted().error() + " flag=" + b.interruptedAfter());
            if (a.interrupted().error() == null && b.interrupted().error() == null) {
                v.info("两侧均未抛异常：本次只证明 interrupt 下行为一致与可重试，不证明已触发取消");
            }
            String diff = compare(c, a.interrupted(), b.interrupted());
            if (diff == null && a.interruptedAfter() == b.interruptedAfter()) {
                v.ok("预置 interrupt 的异常类型/消息（或正常产出）及中断位两臂一致");
            } else {
                v.fail("预置 interrupt 行为", diff == null ? "两臂中断位不同" : diff);
            }
            if (a.retryStartsClear() && b.retryStartsClear()
                    && !a.retryInterrupted() && !b.retryInterrupted()) {
                v.ok("专用线程清位后 retry 前/后 interrupt 均为 false");
            } else {
                v.fail("清位重试中断位", "frozen clear=" + a.retryStartsClear() + " after="
                        + a.retryInterrupted() + " candidate clear=" + b.retryStartsClear()
                        + " after=" + b.retryInterrupted());
            }
            checkRaw(c, a.normal(), a.retry(), "frozen 同一 engine 清位 retry == 首次串行");
            checkRaw(c, b.normal(), b.retry(), "candidate 同一 engine 清位 retry == 首次串行");
            checkRaw(c, a.retry(), b.retry(), "清位 retry frozen == candidate");
        }

        InterruptTrial interruptTrial(EngineRef ref, Case c, String tag) {
            AtomicReference<InterruptTrial> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            float[] tpl = template(c);
            boolean[][] lk = locked(c);
            Thread worker = new Thread(() -> {
                try {
                    Object engine = ref.newEngine(c.xs());
                    Outcome normal = ref.runOn(engine, c, tpl, lk);
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("首次正常运行留下 interrupt 位");
                    }
                    Outcome interrupted;
                    boolean after;
                    Thread.currentThread().interrupt(); // 仅当前专用线程，不中断任何共享池任务
                    try {
                        interrupted = ref.runOn(engine, c, tpl, lk);
                        after = Thread.currentThread().isInterrupted();
                    } finally {
                        Thread.interrupted(); // 必须在同一线程清位，再 retry
                    }
                    boolean clear = !Thread.currentThread().isInterrupted();
                    Outcome retry = ref.runOn(engine, c, tpl, lk); // runOn 每次 clone flat/pre，新建 dis
                    result.set(new InterruptTrial(normal, interrupted, after, clear, retry,
                            Thread.currentThread().isInterrupted()));
                } catch (Throwable t) {
                    failure.set(t); // 包括 runOn 之外的 Error，交由父线程记 FAIL / 非零退出
                } finally {
                    Thread.interrupted();
                }
            }, "erosion-scratch-interrupt-" + tag);
            worker.setDaemon(true);
            worker.start();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(300)); // 不以取消耗时作判据；仅防 interrupt 下挂死
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 不吞掉父线程本身的外部中断
                v.fail(tag + " 取消测试等待", String.valueOf(e));
                return null;
            }
            if (worker.isAlive()) {
                worker.interrupt(); // 只中断本探针的专用线程，不碰任何共享池任务
                v.fail(tag + " 取消测试超时", "专用线程 300s 未结束（interrupt 下引擎可能挂死）");
                return null;
            }
            if (failure.get() != null) {
                v.fail(tag + " 取消测试子线程", String.valueOf(failure.get()));
                return null;
            }
            if (result.get() == null) v.fail(tag + " 取消测试", "子线程缺少结果");
            return result.get();
        }

        void checkRaw(Case c, Outcome a, Outcome b, String tag) {
            String diff = a.error() != null || b.error() != null
                    ? "期望两次正常完成，left=" + a.error() + " right=" + b.error() : compare(c, a, b);
            if (diff == null) v.ok(tag + "（flat/discharge raw-bit）");
            else v.fail(tag, diff);
        }

        // ---------------------------------------------------------------- 变异反控

        void mutationSection() {
            System.out.println("\n--- [3] 变异反控（隔离在 build/ 下；主树/baseline 均不写） ---");
            if (!args.mutation) {
                v.info("--mutation=off → 跳过（本次结论不覆盖'断言是否有分辨力'）");
                return;
            }
            Path root = args.mutantRoot.toAbsolutePath().normalize();
            if (!safeScratch(root)) {
                v.setup("变异输出目录", "必须位于 build/ 下、且不在冻结基线或 src/ 内: " + root);
                return;
            }
            String src;
            try {
                src = Files.readString(args.source, StandardCharsets.UTF_8);
            } catch (IOException e) {
                v.setup("读取候选源码", String.valueOf(e));
                return;
            }
            staleCountMutant(root, src);
            sharedScratchMutant(root, src);
        }

        boolean safeScratch(Path p) {
            String s = p.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            return s.contains("/build/") && !p.startsWith(frozenDir) && !s.contains("/src/");
        }

        /** 变异 1：scratch 有效数不重填（num 少算 → 读到未用槽位）。应由串行矩阵抓到。 */
        void staleCountMutant(Path root, String src) {
            final String anchor = "int num = 0;";
            if (countOf(src, anchor) != 1) {
                v.setup("变异锚点 stale-count", "'" + anchor + "' 出现 " + countOf(src, anchor)
                        + " 次（期望 1）——锚点失效，变异反控无法进行");
                return;
            }
            String mutated = src.replace(anchor, "int num = 7;  // MUTANT: scratch 有效数不重填");
            EngineRef m = compileMutant(root, "stale-count", mutated);
            if (m == null) return;
            int detected = 0;
            int ran = 0;
            for (Case c : cases) {
                if (c.str() <= 0f || c.bufSize() != 64) continue;
                ran++;
                Outcome a = frozen.run(c, template(c), locked(c));
                Outcome b = m.run(c, template(c), locked(c));
                if (compare(c, a, b) != null) detected++;
                if (detected >= 2) break;
            }
            if (detected >= 1) {
                v.ok("变异 stale-count 被检出（" + detected + "/" + ran
                        + " 例逐位不同 ⇒ 矩阵断言有分辨力）");
            } else {
                v.undetected("变异 stale-count 未被检出",
                        "scratch 有效数错了产出却逐位相同 ⇒ 矩阵没真正走过 cascade 路径");
            }
        }

        /** 变异 2：scratch 变 static 单例（跨线程共享）。只有并发用例能抓到。 */
        void sharedScratchMutant(Path root, String src) {
            final String anchor = "final CascadeScratch cas = new CascadeScratch();";
            if (countOf(src, anchor) != 1) {
                v.setup("变异锚点 shared-scratch", "'" + anchor + "' 出现 " + countOf(src, anchor)
                        + " 次（期望 1）——锚点失效，变异反控无法进行");
                return;
            }
            final String classAnchor = "public class ErosionEngine {";
            String mutated = src.replace(anchor, "final CascadeScratch cas = MUTANT_SHARED;");
            if (countOf(mutated, classAnchor) != 1) {
                v.setup("变异注入点 shared-scratch", "找不到唯一类声明");
                return;
            }
            mutated = mutated.replace(classAnchor, classAnchor
                    + "\n    private static final CascadeScratch MUTANT_SHARED = new CascadeScratch();");
            EngineRef m = compileMutant(root, "shared-scratch", mutated);
            if (m == null) return;
            if (sharedScratchDetected(m)) {
                v.ok("变异 shared-scratch 被检出（并发捕捉到跨线程串状态 ⇒ 并发断言有分辨力）");
            } else {
                v.undetected("变异 shared-scratch 未被检出",
                        "共享 scratch 下并发仍与串行一致 ⇒ 并发用例没有真正并行到 cascade");
            }
        }

        boolean sharedScratchDetected(EngineRef mutant) {
            List<Case> pool = new ArrayList<>();
            for (Case c : cases) {
                if (c.bufSize() == 64 && c.str() > 0f && pool.size() < 3) pool.add(c);
            }
            if (pool.size() < 2) return false;
            List<Case> order = new ArrayList<>(pool);
            order.addAll(pool);
            Map<String, Outcome> ref = new LinkedHashMap<>();
            for (int i = 0; i < order.size(); i++) {
                ref.put(key(order, i), frozen.run(order.get(i), template(order.get(i)), locked(order.get(i))));
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                Map<String, Outcome> got = concurrent(mutant, order);
                if (mismatches(order, ref, got, "mutant#" + attempt) > 0) return true;
            }
            return false;
        }

        EngineRef compileMutant(Path root, String kind, String mutatedSource) {
            try {
                Path dir = root.resolve(kind);
                Files.createDirectories(dir);
                Path srcFile = dir.resolve("ErosionEngine.java");
                Files.writeString(srcFile, mutatedSource, StandardCharsets.UTF_8);
                Path classes = dir.resolve("classes");
                String err = Compile.engine(srcFile, classes);
                if (err != null) {
                    v.setup("编译变异 " + kind, err);
                    return null;
                }
                EngineRef ref = EngineRef.load(classes);
                if (ref.cascadeParamCount() != 7) {
                    v.setup("加载变异 " + kind, "cascadeLocal 参数=" + ref.cascadeParamCount() + "（期望 7）");
                    return null;
                }
                return ref;
            } catch (Exception e) {
                v.setup("变异 " + kind, String.valueOf(e));
                return null;
            }
        }

        // ---------------------------------------------------------------- bench / 指纹

        void benchSection() {
            System.out.println("\n--- [4] 分配 / 耗时 bench（ThreadMXBean，交错 A/B） ---");
            Bench.run(this, v);
        }

        void writeFingerprint(Map<String, String> perCase) {
            try {
                Path out = args.out.toAbsolutePath().normalize();
                if (unsafeWrite(out) || out.startsWith(frozenDir)) {
                    v.setup("导出指纹", "不得写 src/ 或冻结基线: " + out);
                    return;
                }
                if (args.toml != null) {
                    Path root = Path.of("build/hydrology-perf").toAbsolutePath().normalize();
                    if (!out.startsWith(root) || out.startsWith(root.resolve("baseline"))
                            || out.startsWith(root.resolve("candidate-v1"))) {
                        v.setup("TOML 模式指纹目录", "必须在 build/hydrology-perf/ 非冻结目录内: " + out);
                        return;
                    }
                }
                Path parent = out.getParent();
                if (parent != null) Files.createDirectories(parent);
                StringBuilder sb = new StringBuilder();
                StringBuilder joined = new StringBuilder();
                sb.append("{\n  \"probe\": \"ErosionScratchProbe\",\n  \"schema\": \"r3-1\",\n");
                sb.append("  \"java\": \"").append(esc(System.getProperty("java.version"))).append("\",\n");
                sb.append("  \"frozen\": \"").append(esc(frozenDir.toString())).append("\",\n");
                sb.append("  \"candidate\": \"").append(esc(candidateDir.toString())).append("\",\n");
                sb.append("  \"frozenClassSha256\": \"")
                  .append(sha256File(frozenDir.resolve(CLASS_REL))).append("\",\n");
                sb.append("  \"candidateClassSha256\": \"")
                  .append(sha256File(candidateDir.resolve(CLASS_REL))).append("\",\n");
                sb.append("  \"cases\": [\n");
                int i = 0;
                for (Map.Entry<String, String> e : perCase.entrySet()) {
                    sb.append("    {\"name\": \"").append(esc(e.getKey())).append("\", \"fp\": \"")
                      .append(e.getValue()).append("\"}");
                    sb.append(++i < perCase.size() ? ",\n" : "\n");
                    joined.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
                sb.append("  ],\n  \"overall\": \"")
                  .append(sha256Hex(joined.toString().getBytes(StandardCharsets.UTF_8))).append("\"\n}\n");
                if (args.toml == null) {
                    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8); // 原 CLI 行为不变
                } else {
                    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                }
                v.info("指纹已导出 → " + out + "\n         overall="
                        + sha256Hex(joined.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                v.setup("导出指纹", String.valueOf(e));
            }
        }

        // ---------------------------------------------------------------- 用例缓存

        float[] template(Case c) {
            return templateCache.computeIfAbsent(c.name() + "/" + c.seed() + "/" + c.bufSize()
                    + "/" + c.sz() + "/" + c.ox() + "/" + c.oz(), k -> Synthetic.field(c));
        }

        boolean[][] locked(Case c) {
            if (!c.locked()) return null;
            return lockedCache.computeIfAbsent(c.name(), k -> Synthetic.locked(c));
        }

        @Override
        public void close() {
            closeLoader(frozen);
            closeLoader(candidate);
            if (config != null) {
                try {
                    config.close(); // 非 autosave；加载/保存路径始终为 build/ 工作副本
                } catch (Exception ignored) {
                    // 只读证据已落盘；工作副本关闭失败不影响判据
                }
            }
        }

        private static void closeLoader(EngineRef r) {
            if (r == null) return;
            try {
                if (r.type.getClassLoader() instanceof URLClassLoader l) l.close();
            } catch (IOException ignored) {
                // 目录型 loader，关闭失败无影响
            }
        }
    }

    // ==================================================================================
    // 引擎反射封装
    // ==================================================================================

    static final class EngineRef {
        final Class<?> type;
        final Constructor<?> ctor;
        final Method pub8;
        final Method pub9;
        final Method pub10;
        final Method priv11;

        private EngineRef(Class<?> type) throws NoSuchMethodException {
            this.type = type;
            this.ctor = pickCtor(type);
            this.pub8 = type.getMethod("runErosionOnFlat", float[].class, float[].class,
                    int.class, int.class, int.class, int.class, float.class, float.class);
            this.pub9 = type.getMethod("runErosionOnFlat", float[].class, float[].class,
                    int.class, int.class, int.class, int.class, float.class, float.class, float[].class);
            this.pub10 = type.getMethod("runErosionOnFlat", float[].class, float[].class,
                    int.class, int.class, int.class, int.class, float.class, float.class,
                    float[].class, float.class);
            this.priv11 = type.getDeclaredMethod("runErosionOnFlat", float[].class, float[].class,
                    int.class, int.class, int.class, int.class, float.class, float.class,
                    boolean[][].class, float[].class, float.class);
            this.priv11.setAccessible(true);
        }

        private static Constructor<?> pickCtor(Class<?> t) throws NoSuchMethodException {
            try {
                Constructor<?> c = t.getConstructor(double.class, int.class, boolean.class);
                c.setAccessible(true);
                return c;
            } catch (NoSuchMethodException e) {
                Constructor<?> c = t.getConstructor(double.class, int.class);
                c.setAccessible(true);
                return c;
            }
        }

        static EngineRef load(Path dir) throws Exception {
            URL url = dir.toUri().toURL();
            ChildFirstLoader loader = new ChildFirstLoader(url, ErosionScratchProbe.class.getClassLoader());
            Class<?> t = Class.forName(ENGINE_CLASS, true, loader);
            return new EngineRef(t);
        }

        int cascadeParamCount() {
            for (Method m : type.getDeclaredMethods()) {
                if (m.getName().equals("cascadeLocal")) return m.getParameterCount();
            }
            return -1;
        }

        boolean hasNested(String simpleName) {
            for (Class<?> n : type.getDeclaredClasses()) {
                if (n.getSimpleName().equals(simpleName)) return true;
            }
            return false;
        }

        Object newEngine(boolean xs) {
            try {
                if (ctor.getParameterCount() == 3) return ctor.newInstance(1.0d, 12345, xs);
                return ctor.newInstance(1.0d, 12345);
            } catch (Exception e) {
                throw new IllegalStateException("newEngine: " + e, e);
            }
        }

        Outcome run(Case c, float[] template, boolean[][] locked) {
            return runOn(newEngine(c.xs()), c, template, locked);
        }

        Outcome runOn(Object engine, Case c, float[] template, boolean[][] locked) {
            int n = c.bufSize();
            float[] flat = template.clone();
            float[] pre = template.clone();
            float[] dis = c.discharge() ? new float[n * n] : null;
            long t0 = System.nanoTime();
            try {
                switch (c.entry()) {
                    case PUBLIC8 -> pub8.invoke(engine, flat, pre, n, c.sz(), c.ox(), c.oz(),
                            c.seaNorm(), c.str());
                    case PUBLIC9 -> pub9.invoke(engine, flat, pre, n, c.sz(), c.ox(), c.oz(),
                            c.seaNorm(), c.str(), dis);
                    case PUBLIC10 -> pub10.invoke(engine, flat, pre, n, c.sz(), c.ox(), c.oz(),
                            c.seaNorm(), c.str(), dis, c.hs());
                    case PRIVATE11 -> priv11.invoke(engine, flat, pre, n, c.sz(), c.ox(), c.oz(),
                            c.seaNorm(), c.str(), locked, dis, c.hs());
                }
            } catch (InvocationTargetException e) {
                Throwable t = e.getCause() == null ? e : e.getCause();
                return Outcome.failed(t.getClass().getName() + ": " + t.getMessage());
            } catch (Exception e) {
                return Outcome.failed(e.getClass().getName() + ": " + e.getMessage());
            }
            long dt = System.nanoTime() - t0;
            return new Outcome(flat, dis, null, dt);
        }
    }

    /** child-first：只劫持 {@code ErosionEngine*}，其余（GeoGenesisConfig/Forge/log4j）走父加载器。 */
    static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(URL url, ClassLoader parent) {
            super(new URL[]{url}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(ENGINE_PREFIX)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    // ==================================================================================
    // 合成地形 / locked（确定性、零 MC 依赖）
    // ==================================================================================

    static final class Synthetic {
        private Synthetic() {}

        static float[] field(Case c) {
            int n = c.bufSize();
            int pad = c.pad();
            float[] f = new float[n * n];
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    f[z * n + x] = (float) heightAt(c.seed(), c.ox() - pad + x, c.oz() - pad + z);
                }
            }
            return f;
        }

        static double heightAt(long seed, int wx, int wz) {
            double v = 0.62 * vnoise(seed, wx / 41.0, wz / 41.0)
                     + 0.24 * vnoise(seed ^ 0x9E3779B9L, wx / 13.0, wz / 13.0)
                     + 0.10 * vnoise(seed ^ 0x85EBCA6BL, wx / 4.3, wz / 4.3)
                     + 0.04 * vnoise(seed ^ 0xC2B2AE35L, wx / 1.7, wz / 1.7);
            double h = 0.34 + 0.46 * v;
            double basin = 0.30 * vnoise(seed ^ 0x27D4EB2FL, wx / 240.0, wz / 240.0);
            return h - basin;
        }

        static double vnoise(long seed, double x, double z) {
            int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
            double fx = x - x0, fz = z - z0;
            double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
            double n00 = lattice(seed, x0, z0), n10 = lattice(seed, x0 + 1, z0);
            double n01 = lattice(seed, x0, z0 + 1), n11 = lattice(seed, x0 + 1, z0 + 1);
            double a = n00 + (n10 - n00) * sx;
            double b = n01 + (n11 - n01) * sx;
            return a + (b - a) * sz;
        }

        static double lattice(long seed, int x, int z) {
            long h = seed + x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
            h ^= h >>> 30;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 27;
            h *= 0x94D049BB133111EBL;
            h ^= h >>> 31;
            return (h >>> 11) * 0x1.0p-53 * 2.0 - 1.0;
        }

        static boolean[][] locked(Case c) {
            int sz = c.sz();
            boolean[][] m = new boolean[sz][sz];
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sz; x++) {
                    boolean stripe = (x * 5 + z * 3) % 23 == 0;
                    boolean block = x >= sz / 3 && x < sz / 3 + 4 && z >= sz / 2 && z < sz / 2 + 5;
                    m[z][x] = stripe || block;
                }
            }
            return m;
        }
    }

    // ==================================================================================
    // 用例矩阵
    // ==================================================================================

    static List<Case> matrix(boolean quick) {
        List<Case> l = new ArrayList<>();
        // 1) 多 seed（地形维度，hs=2）
        for (long s : new long[]{12345L, 1L, -987654321L}) {
            l.add(new Case("seed" + s, Entry.PUBLIC10, 64, 46, 0, 0, 0.35f, 1.0f, 2.0f,
                    false, false, true, s));
        }
        // 2) hs ∈ {1.0, 2.0, 1.3}
        for (float h : new float[]{1.0f, 2.0f, 1.3f}) {
            l.add(new Case("hs" + Math.round(h * 100), Entry.PUBLIC10, 64, 46, 0, 0, 0.35f, 1.0f,
                    h, false, false, true, 12345L));
        }
        // 2b) ★ horizontalScale 生产边界（GeoGenesisConfig "Scale" defineInRange(..., 0.5, 8.0)）
        //   低成本（buf=34/sz=16，与 sz16-min 同量级，不扩生产逻辑）：只补独立工作区两端，
        //   覆盖 hs 在坡度门控 / CASCADE_MAXDIFF×hs / 步长上的两端取值。
        for (float h : new float[]{0.5f, 8.0f}) {
            l.add(new Case("hs" + Math.round(h * 100) + (h < 1f ? "-lowedge" : "-highedge"),
                    Entry.PUBLIC10, 34, 16, 0, 0, 0.35f, 1.0f, h,
                    false, false, true, 24680L));
        }
        // 3) 正 / 负 tile 原点（世界坐标哈希、片流 hash、cross-tile 几何都依赖它）
        int[][] tiles = {{768, -1472}, {-1024, 768}, {-3072, -3072}};
        for (int[] t : tiles) {
            l.add(new Case("tile" + t[0] + "_" + t[1], Entry.PUBLIC10, 64, 46, t[0], t[1],
                    0.35f, 1.0f, 2.0f, false, false, true, 12345L));
        }
        // 4) locked（私有 locked 重载）
        l.add(new Case("locked-hs2", Entry.PRIVATE11, 64, 46, 512, -512,
                0.35f, 1.0f, 2.0f, false, true, true, 777L));
        l.add(new Case("locked-hs1.3-nodis", Entry.PRIVATE11, 64, 46, -512, 512,
                0.35f, 1.0f, 1.3f, false, true, false, 778L));
        // 5) str = 0（早退）/ str < 1
        l.add(new Case("str0", Entry.PUBLIC10, 64, 46, 0, 0, 0.35f, 0.0f, 2.0f,
                false, false, true, 12345L));
        l.add(new Case("str0.25", Entry.PUBLIC10, 64, 46, 0, 0, 0.35f, 0.25f, 2.0f,
                false, false, true, 12345L));
        // 6) XS 微笔刷（独立 seed 流 + r1 十字笔刷）
        l.add(new Case("xs-hs2", Entry.PUBLIC10, 64, 46, 0, 0, 0.35f, 1.0f, 2.0f,
                true, false, true, 12345L));
        // 7) 其余 public 重载
        l.add(new Case("public8-hs1", Entry.PUBLIC8, 64, 46, 0, 0, 0.35f, 1.0f, 1.0f,
                false, false, false, 12345L));
        l.add(new Case("public9-nohs", Entry.PUBLIC9, 64, 46, 0, 0, 0.35f, 1.0f, 1.0f,
                false, false, true, 12345L));
        // 8) 边界：sz=16（入口守卫 sz<16 的临界值，pad 仍为 9）+ hs 归一化分支
        l.add(new Case("sz16-min", Entry.PUBLIC10, 34, 16, 144, -144, 0.35f, 1.0f, 2.0f,
                false, false, true, 555L));
        l.add(new Case("hs0.005-clamp", Entry.PUBLIC10, 64, 46, 0, 0, 0.35f, 1.0f, 0.005f,
                false, false, true, 12345L));
        if (!quick) {
            // 8) 128² 大缓冲（更接近生产的 droplet 数，仍然很轻）
            l.add(new Case("buf128", Entry.PUBLIC10, 128, 110, 0, 0, 0.35f, 1.0f, 2.0f,
                    false, false, true, 424242L));
        }
        return l;
    }

    // ==================================================================================
    // bench
    // ==================================================================================

    static final class Bench {
        private Bench() {}

        static void run(Run r, Verdict v) {
            ThreadMXBean bean = allocBean();
            if (bean == null) {
                v.info("ThreadMXBean 不可用 → 只测时间");
            } else if (!bean.isThreadAllocatedMemorySupported()) {
                v.info("JVM 不支持 thread allocated memory → 只测时间");
                bean = null;
            }
            Case c = pick(r.cases);
            if (c == null) {
                v.setup("bench", "没有可用用例");
                return;
            }
            Object frozenEngine = r.frozen.newEngine(c.xs());
            Object candEngine = r.candidate.newEngine(c.xs());
            float[] tpl = r.template(c);
            boolean[][] lk = r.locked(c);
            for (int i = 0; i < r.args.warmup; i++) {
                r.frozen.runOn(frozenEngine, c, tpl, lk);
                r.candidate.runOn(candEngine, c, tpl, lk);
            }
            long[] allocF = new long[r.args.reps];
            long[] allocC = new long[r.args.reps];
            long[] timeF = new long[r.args.reps];
            long[] timeC = new long[r.args.reps];
            for (int i = 0; i < r.args.reps; i++) {
                // 交错 A/B（偶数轮 frozen 先、奇数轮 candidate 先），抵消漂移
                if (i % 2 == 0) {
                    allocF[i] = measure(r, bean, frozenEngine, c, tpl, lk, true, timeF, i);
                    allocC[i] = measure(r, bean, candEngine, c, tpl, lk, false, timeC, i);
                } else {
                    allocC[i] = measure(r, bean, candEngine, c, tpl, lk, false, timeC, i);
                    allocF[i] = measure(r, bean, frozenEngine, c, tpl, lk, true, timeF, i);
                }
            }
            report(r, v, c, bean, allocF, allocC, timeF, timeC);
        }

        static Case pick(List<Case> cases) {
            Case best = null;
            for (Case c : cases) {
                if (c.str() <= 0f || c.locked()) continue;
                if (best == null || c.bufSize() > best.bufSize()) best = c;
            }
            return best;
        }

        static long measure(Run r, ThreadMXBean bean, Object engine, Case c, float[] tpl,
                            boolean[][] lk, boolean isFrozen, long[] times, int slot) {
            long a0 = bean == null ? 0L : bean.getCurrentThreadAllocatedBytes();
            Outcome o = isFrozen ? r.frozen.runOn(engine, c, tpl, lk)
                                 : r.candidate.runOn(engine, c, tpl, lk);
            long a1 = bean == null ? 0L : bean.getCurrentThreadAllocatedBytes();
            times[slot] = o.nanos();
            return o.error() == null ? a1 - a0 : -1L;
        }

        static void report(Run r, Verdict v, Case c, ThreadMXBean bean, long[] allocF, long[] allocC,
                           long[] timeF, long[] timeC) {
            System.out.printf(Locale.ROOT,
                    "  case=%s buf=%d sz=%d hs=%.2f  reps=%d warmup=%d  (单线程，调用线程计费)%n",
                    c.name(), c.bufSize(), c.sz(), c.hs(), r.args.reps, r.args.warmup);
            if (bean != null) {
                String fmt = "  %-10s %12s %12s %12s%n";
                System.out.printf(Locale.ROOT, fmt, "variant", "alloc(min)", "alloc(med)", "alloc(max)");
                System.out.printf(Locale.ROOT, fmt, "frozen", b(min(allocF)), b(median(allocF)), b(max(allocF)));
                System.out.printf(Locale.ROOT, fmt, "candidate", b(min(allocC)), b(median(allocC)), b(max(allocC)));
                double ratio = median(allocF) <= 0 ? 0 : (double) median(allocC) / (double) median(allocF);
                System.out.printf(Locale.ROOT,
                        "  alloc 中位数比 candidate/frozen = %.3f（<1 = 分配更少）%n", ratio);
            }
            String fmt = "  %-10s %12s %12s %12s%n";
            System.out.printf(Locale.ROOT, fmt, "variant", "time(min)", "time(med)", "time(max)");
            System.out.printf(Locale.ROOT, fmt, "frozen", ms(min(timeF)), ms(median(timeF)), ms(max(timeF)));
            System.out.printf(Locale.ROOT, fmt, "candidate", ms(min(timeC)), ms(median(timeC)), ms(max(timeC)));
            v.info("两侧的输入 clone / 平滑缓冲完全相同，剩余差异只应来自 cascadeLocal 的 8+8 scratch"
                    + "（每次 ~96B × 液滴步数）");
        }

        static ThreadMXBean allocBean() {
            return ManagementFactory.getThreadMXBean() instanceof ThreadMXBean b ? b : null;
        }

        static long min(long[] a) {
            long[] x = a.clone();
            Arrays.sort(x);
            return x[0];
        }

        static long max(long[] a) {
            long[] x = a.clone();
            Arrays.sort(x);
            return x[x.length - 1];
        }

        static long median(long[] a) {
            long[] x = a.clone();
            Arrays.sort(x);
            return x[x.length / 2];
        }

        static String b(long v) {
            if (v < 0) return "n/a";
            if (v < 1024) return v + "B";
            if (v < 1024 * 1024) return String.format(Locale.ROOT, "%.1fKB", v / 1024.0);
            return String.format(Locale.ROOT, "%.2fMB", v / 1048576.0);
        }

        static String ms(long ns) {
            return String.format(Locale.ROOT, "%.2fms", ns / 1e6);
        }
    }

    // ==================================================================================
    // self-test（探针自身分辨力）
    // ==================================================================================

    static final class SelfTest {
        private SelfTest() {}

        static void run(Run r, Verdict v) {
            System.out.println("\n--- [5] self-test（探针自身是否靠得住） ---");
            bitsDetector(v);
            compareDetector(v);
            hashStability(v);
            syntheticDeterminism(r, v);
        }

        static void bitsDetector(Verdict v) {
            float[] a = {0f, 1f, -0f, Float.NaN, 1e-7f};
            check(v, "bitsEqual 自身相等", bitsEqual(a, a.clone()));
            float[] b = a.clone();
            b[1] = Float.intBitsToFloat(Float.floatToRawIntBits(1f) ^ 1);
            check(v, "bitsEqual 检出 1 ULP", !bitsEqual(a, b));
            float[] c = a.clone();
            c[2] = 0.0f;
            check(v, "bitsEqual 区分 -0.0 / +0.0", !bitsEqual(a, c));
            float[] d = a.clone();
            d[3] = Float.intBitsToFloat(0x7fc00001);
            check(v, "bitsEqual 检出 NaN 载荷差异", !bitsEqual(a, d));
            float[] e = {1e-30f};
            float[] f = {1e-31f};
            check(v, "bitsEqual 检出次正规量级差异", !bitsEqual(e, f));
            float[] g = {1f, 2f};
            float[] h = {1f, 2f, 3f};
            check(v, "bitsEqual 检出长度差异", !bitsEqual(g, h));
        }

        static void compareDetector(Verdict v) {
            Case c = new Case("self", Entry.PUBLIC10, 16, 16, 0, 0, 0f, 1f, 1f,
                    false, false, true, 0L);
            float[] f = {1f, 2f};
            float[] d1 = {0f, 0f};
            float[] d2 = {0f, 1e-45f};
            Outcome a = new Outcome(f, d1, null, 0);
            Outcome b = new Outcome(f.clone(), d2, null, 0);
            check(v, "compare 检出 discharge 差异", compare(c, a, b) != null);
            check(v, "compare 同内容判等", compare(c, a, new Outcome(f.clone(), d1.clone(), null, 0)) == null);
            check(v, "compare 检出单侧异常",
                    compare(c, Outcome.failed("boom"), new Outcome(f.clone(), d1.clone(), null, 0)) != null);
        }

        static void hashStability(Verdict v) {
            float[] a = {1f, 2f, 3f};
            String h1 = sha256Floats(a);
            String h2 = sha256Floats(a.clone());
            String h3 = sha256Floats(new float[]{1f, 2f, 3.0000002f});
            check(v, "sha256Floats 同内容稳定", h1.equals(h2));
            check(v, "sha256Floats 对内容变化敏感", !h1.equals(h3));
            check(v, "sha256Floats 区分 -0.0 / +0.0",
                    !sha256Floats(new float[]{0f}).equals(sha256Floats(new float[]{-0f})));
        }

        static void syntheticDeterminism(Run r, Verdict v) {
            boolean allSame = true;
            for (Case c : r.cases) {
                if (!Arrays.equals(Synthetic.field(c), Synthetic.field(c))) allSame = false;
            }
            check(v, "合成地形逐位确定（同 case 两次生成一致）", allSame);
            Case c0 = r.cases.get(0);
            float[] s1 = Synthetic.field(c0);
            float[] s2 = Synthetic.field(new Case(c0.name(), c0.entry(), c0.bufSize(), c0.sz(),
                    c0.ox(), c0.oz(), c0.seaNorm(), c0.str(), c0.hs(), c0.xs(), c0.locked(),
                    c0.discharge(), c0.seed() + 1));
            check(v, "合成地形对 seed 敏感", !Arrays.equals(s1, s2));
        }

        static void check(Verdict v, String what, boolean ok) {
            if (ok) v.ok(what);
            else v.fail(what, "断言失败");
        }
    }

    // ==================================================================================
    // 编译（javax.tools）
    // ==================================================================================

    static final class Compile {
        private Compile() {}

        /** 编译单个源文件到 outDir（先清空 com/ 子树，避免陈旧内部类混入）。返回 null=成功。 */
        static String engine(Path source, Path outDir) {
            if (!Files.isRegularFile(source)) return "源文件不存在: " + source;
            JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
            if (jc == null) return "没有系统 Java 编译器（需要 JDK 而非 JRE）";
            try {
                Path com = outDir.resolve("com");
                if (Files.isDirectory(com)) deleteTree(com);
                Files.createDirectories(outDir);
                String cp = System.getProperty("java.class.path", "");
                DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
                try (StandardJavaFileManager fm = jc.getStandardFileManager(diags, Locale.ROOT,
                        StandardCharsets.UTF_8)) {
                    Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(source.toFile());
                    List<String> opts = new ArrayList<>(List.of(
                            "-encoding", "UTF-8", "-g", "-nowarn", "-proc:none", "-implicit:none",
                            "-classpath", cp, "-d", outDir.toString()));
                    Boolean ok = jc.getTask(null, fm, diags, opts, null, units).call();
                    if (Boolean.TRUE.equals(ok)) return null;
                    StringBuilder sb = new StringBuilder("javac 失败:");
                    int n = 0;
                    for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                        if (n++ >= 8) break;
                        sb.append("\n        ").append(d.getKind()).append(" line ")
                          .append(d.getLineNumber()).append(": ").append(d.getMessage(Locale.ROOT));
                    }
                    return sb.toString();
                }
            } catch (IOException e) {
                return "javac 异常: " + e;
            }
        }

        static void deleteTree(Path p) throws IOException {
            if (!Files.exists(p)) return;
            try (var s = Files.walk(p)) {
                List<Path> all = s.sorted((x, y) -> y.getNameCount() - x.getNameCount()).toList();
                for (Path q : all) Files.deleteIfExists(q);
            }
        }
    }

    // ==================================================================================
    // 比对 / 哈希 / 小工具
    // ==================================================================================

    static String compare(Case c, Outcome a, Outcome b) {
        if (a.error() != null || b.error() != null) {
            if (a.error() == null) return "异常不一致: frozen=ok candidate=" + b.error();
            if (b.error() == null) return "异常不一致: frozen=" + a.error() + " candidate=ok";
            return a.error().equals(b.error()) ? null
                    : "异常不一致: frozen=" + a.error() + " candidate=" + b.error();
        }
        String d = firstDiff("flat", a.flat(), b.flat());
        if (d != null) return d;
        if (c.discharge()) return firstDiff("discharge", a.discharge(), b.discharge());
        return null;
    }

    static String firstDiff(String tag, float[] x, float[] y) {
        if (x == null || y == null) {
            return (x == null && y == null) ? null : tag + " 一侧为 null";
        }
        int n = Math.min(x.length, y.length);
        int count = 0;
        int first = -1;
        for (int i = 0; i < n; i++) {
            if (Float.floatToRawIntBits(x[i]) != Float.floatToRawIntBits(y[i])) {
                if (first < 0) first = i;
                count++;
            }
        }
        if (count == 0 && x.length == y.length) return null;
        String at = first < 0 ? "length(" + x.length + " vs " + y.length + ")"
                : ("[" + first + "] frozen=" + x[first] + "(" + hex(x[first])
                   + ") candidate=" + y[first] + "(" + hex(y[first]) + ")");
        return tag + " " + count + "/" + n + " 处不同，首个 " + at;
    }

    static String hex(float f) {
        return "0x" + Integer.toHexString(Float.floatToRawIntBits(f));
    }

    static boolean bitsEqual(float[] x, float[] y) {
        return firstDiff("bits", x, y) == null;
    }

    static String summary(Case c) {
        return "entry=" + c.entry() + " buf=" + c.bufSize() + " sz=" + c.sz()
                + " origin=(" + c.ox() + "," + c.oz() + ") hs=" + c.hs() + " str=" + c.str()
                + " xs=" + c.xs() + " locked=" + c.locked() + " dis=" + c.discharge();
    }

    static String fingerprintOf(Case c, Outcome o) {
        if (o.error() != null) return "error:" + sha256Hex(o.error().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("flat=").append(sha256Floats(o.flat()));
        if (c.discharge()) sb.append(",dis=").append(sha256Floats(o.discharge()));
        return sb.toString();
    }

    static String sha256Floats(float[] a) {
        if (a == null) return "null";
        MessageDigest md = digest();
        byte[] buf = new byte[1 << 16];
        int p = 0;
        for (float f : a) {
            int bits = Float.floatToRawIntBits(f);
            buf[p++] = (byte) (bits >>> 24);
            buf[p++] = (byte) (bits >>> 16);
            buf[p++] = (byte) (bits >>> 8);
            buf[p++] = (byte) bits;
            if (p == buf.length) {
                md.update(buf, 0, p);
                p = 0;
            }
        }
        if (p > 0) md.update(buf, 0, p);
        return toHex(md.digest());
    }

    static String sha256File(Path p) {
        try {
            return toHex(digest().digest(Files.readAllBytes(p)));
        } catch (IOException e) {
            return "unreadable";
        }
    }

    static String sha256Hex(byte[] data) {
        return toHex(digest().digest(data));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static int countOf(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
