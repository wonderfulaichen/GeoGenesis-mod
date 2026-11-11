package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * <h1>「汇流（join）」与「发现（discovery）」接口实验</h1>
 *
 * <p>计划 {@code test-join-and-discovery} 项。诊断专用：不改 main、不改 G42 探针、不改配置。</p>
 *
 * <h2>积分器复用</h2>
 * <p>滚窗积分器 = G42 {@code SharedLakeSeamProbe} 的 {@code private static rollingTrace / rollingResume /
 * rollingSites}，经<b>反射薄适配器</b>（{@link G42}）直调同一字节码，只读 record。本类<b>无</b>任何
 * 方向/推进/单调性/换窗逻辑的副本。</p>
 *
 * <h2>接收状态：本探针检验的是<b>候选</b>，不是唯一正解</h2>
 * <p>「同一世界列应有同一接收状态」只是<b>一种</b>候选契约（column-identity）。计划<b>并不</b>排除
 * 共享面通量、共同动量合成、或其他合并算子。本探针只回答三件事：</p>
 * <ul>
 *   <li>[B] 原 G42 携带动量续追时，同列两来路是否<b>尚未</b>共享后缀（实测反例）；</li>
 *   <li>[C] 换成无记忆 canonical recipient 后是否<b>才</b>有共同后缀（隔离原因）；</li>
 *   <li>[D] 有界源索引能否支撑「任意下游先查询」——分清<b>已能</b>与<b>尚缺</b>。</li>
 * </ul>
 * <p>不采用 first-occupied-wins，不把「离得近」当汇流，不定义生产合并算子/水面/盆接口。</p>
 *
 * <h2>判定分层</h2>
 * <ul>
 *   <li><b>硬判据</b>（{@code hardFail}）：G42 滚窗回归、有界事件场构造性完备、源索引与独立枚举同集。
 *       任一红 ⇒ 退出码 1。</li>
 *   <li><b>开放缺口</b>（{@code openGap}）：已如实定位但尚未具备的能力（G42 任意世界完整源目录）。
 *       独立于候选 verdict，单独把退出码置 2。</li>
 *   <li><b>候选 verdict</b>（{@code VERDICTS}）：汇流/后缀类结论，<b>不</b>进退出码。</li>
 * </ul>
 *
 * <p>用法：{@code java LocalFlowJoinProbe [seed]}；{@code -DlocalFlow.output=<dir>} 覆盖输出目录。</p>
 */
public final class LocalFlowJoinProbe {

    static final Path DEFAULT_OUT = Path.of("build", "local-flow-reassessment", "join");
    static final long DEF_SEED = 9139912035078620160L;
    /** 与 G42 同口径的工作窗几何（诊断参数）。 */
    static final double HALF = 32, TRIGGER = 8;
    /** 「查询窗内出生」的半径，仅用于 query-only-births 漏源负控。 */
    static final int BIRTH_WINDOW = 32;

    static final StringBuilder LOG = new StringBuilder();
    static final List<String> VERDICTS = new ArrayList<>();
    static Path OUT = DEFAULT_OUT;
    static int hardFail, candidateReject, openGap;
    static TerrainParams TP;
    static CellGenerator GEN;
    static long SEED;
    static G42 g42;
    static Map<SharedBoundaryDrainage.WorldCell, Run> COLD = Map.of();

    private LocalFlowJoinProbe() { }

    public static void main(String[] args) {
        int code;
        try { code = run(args); }
        catch (Throwable t) { note("FATAL " + t); t.printStackTrace(System.out); code = 3; }
        System.out.println("EXIT=" + code);
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        SEED = args.length > 0 ? Long.parseLong(args[0].trim()) : DEF_SEED;
        OUT = Path.of(System.getProperty("localFlow.output", DEFAULT_OUT.toString()));
        Files.createDirectories(OUT);
        note("=== LocalFlowJoinProbe ===");
        note("seed=" + SEED + " out=" + OUT.toAbsolutePath() + " diagnosticOnly=true NO_PRODUCTION_EDIT=true");
        ok(loadConfig(), "用户 toml 复制为工作副本并 cfg.load()（原件只读、绝不写回）", cfgPath().toString());
        GEN = buildGen(SEED);
        note(String.format(Locale.ROOT, "terrain hs=%.4f sea=%d minY=%d maxY=%d fingerprint=%d",
                TP.horizontalScale(), TP.seaLevel(), TP.minY(), TP.maxY(), fingerprint()));
        g42 = new G42();
        note("G42 adapter: rollingTrace/rollingResume/rollingSites 经反射复用 private 成员，零副本");
        sectionRegression();
        sectionJoinReal();
        sectionJoinCanonical();
        sectionDiscoveryLocal();
        sectionDiscoveryG42();
        return finish();
    }

    // ================= 配置 / 地形 ===============================================

    static Path cfgPath() { return Path.of("run", "config", "geogenesis-common.toml"); }

    static boolean loadConfig() {
        Path src = cfgPath();
        if (!Files.isRegularFile(src)) { note("[cfg] 缺少 " + src.toAbsolutePath()); TP = TerrainParams.defaults(); return false; }
        try {
            Path work = OUT.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            TP = GeoGenesisConfig.INSTANCE.buildParams();
            note("[cfg] workCopy=" + work.toAbsolutePath() + " bytes=" + Files.size(work) + " 原件未写回");
            return true;
        } catch (Throwable e) {
            note("[cfg] 工作副本加载失败 ⇒ defaults 兜底并计 FAIL: " + e);
            TP = TerrainParams.defaults();
            return false;
        }
    }

    static long fingerprint() {
        try { return GeoGenesisConfig.configFingerprint(); } catch (Throwable e) { return 0L; }
    }

    static CellGenerator buildGen(long seed) {
        TerrainParams tp = TP == null ? TerrainParams.defaults() : TP;
        CellGenerator g = new CellGenerator(tp, tp.minY(), tp.maxY());
        g.seed(seed);
        return g;
    }

    /** 生产侵蚀高度采样（零 MC 水文管线，仅地形）。 */
    static WindowOutlets.Terrain realTerrain(CellGenerator gen) {
        double hs = Math.max(0.01, gen.params().horizontalScale());
        return (x, z) -> gen.erodedHeight(x / hs, z / hs);
    }

    // ================= 判定 / 输出小工具 =========================================

    static void note(String s) { LOG.append(s).append('\n'); }

    /** 硬判据：唯一能置退出码 1 的入口。 */
    static void ok(boolean cond, String name, String detail) {
        if (cond) { LOG.append("  [PASS] ").append(name).append('\n'); return; }
        hardFail++;
        LOG.append("  [FAIL] ").append(name).append(" —— ").append(detail).append('\n');
    }

    /** 候选 verdict：只记录，不参与退出码。 */
    static void candidate(String name, boolean holds, String detail) {
        if (holds) { LOG.append("  [CANDIDATE ACCEPTED] ").append(name).append('\n'); VERDICTS.add(name + "=ACCEPTED"); return; }
        candidateReject++;
        LOG.append("  [CANDIDATE REJECTED] ").append(name).append(" —— ").append(detail).append('\n');
        VERDICTS.add(name + "=REJECTED");
    }

    static void verdict(String name, String value, String why) {
        LOG.append("  [VERDICT] ").append(name).append('=').append(value).append("  (").append(why).append(")\n");
        VERDICTS.add(name + "=" + value);
    }

    /** 开放缺口：已定位、尚缺的能力。独立计数，单独把退出码置 2（不与候选 verdict 混）。 */
    static void gap(String name, String why) {
        openGap++;
        LOG.append("  [OPEN GAP / NOT_ESTABLISHED] ").append(name).append(" —— ").append(why).append('\n');
        VERDICTS.add(name + "=NOT_ESTABLISHED");
    }

    static void row(StringBuilder sb, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            String v = String.valueOf(cells[i]);
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0) sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            else sb.append(v);
        }
        sb.append('\n');
    }

    static void write(String name, CharSequence body) throws Exception {
        Files.writeString(OUT.resolve(name), body, StandardCharsets.UTF_8);
    }

    static int finish() throws Exception {
        int code = hardFail > 0 ? 1 : openGap > 0 ? 2 : 0;
        note("");
        note("--- 判定汇总 ---");
        for (String v : VERDICTS) note("  " + v);
        note("hardFailures=" + hardFail + " openGaps=" + openGap + " candidateRejected=" + candidateReject);
        note("EXIT_SEMANTICS: 0=硬判据全绿且无开放缺口；1=硬判据红；2=硬判据全绿但存在开放缺口");
        note("CANDIDATE_VERDICTS_DO_NOT_GATE_EXIT=true");
        note("EXIT=" + code);
        write("metrics.txt", LOG);
        write("verdicts.txt", String.join("\n", VERDICTS) + "\n");
        System.out.print(LOG);
        return code;
    }

    // ================= 本地记录（反射适配后的形状） ===============================

    record St(double x, double z, double h, double dx, double dz, int step) { }
    record Mv(int node, St state) { }
    record Run(SharedBoundaryDrainage.WorldCell source, List<St> nodes, String terminal,
               List<Mv> moves, long samples) { }
    record X(int axis, int boundary, double x, double z) { }

    // ================= G42 反射薄适配器 =========================================

    /** 只做「调 private 方法 / 读 record 访问器」，不含任何积分逻辑。 */
    static final class G42 {
        private final Class<?> probe;
        private final Method trace, resume, sites;
        private final Method runSource, runNodes, runTerminal, runMoves, runSamples;
        private final Method mvNode, mvState;
        private final Method stX, stZ, stH, stDx, stDz, stStep;
        private final Constructor<?> samplerCtor, stateCtor;
        private final Method samplerHeight;

        G42() throws Exception {
            String pkg = "com.geogenesis.worldgen.hydrology.model.";
            probe = Class.forName(pkg + "SharedLakeSeamProbe");
            Class<?> runCls = Class.forName(pkg + "SharedLakeSeamProbe$RollingRun");
            Class<?> stateCls = Class.forName(pkg + "SharedLakeSeamProbe$RollingState");
            Class<?> moveCls = Class.forName(pkg + "SharedLakeSeamProbe$RollingMove");
            Class<?> samplerCls = Class.forName(pkg + "SharedLakeSeamProbe$RollingSampler");
            Class<?> cell = SharedBoundaryDrainage.WorldCell.class;
            trace = open("rollingTrace", cell, WindowOutlets.Terrain.class, double.class, double.class,
                    double.class, double.class, boolean.class, double.class, int.class, boolean.class);
            resume = open("rollingResume", cell, WindowOutlets.Terrain.class, stateCls, double.class,
                    double.class, double.class, double.class, boolean.class, double.class, int.class, boolean.class);
            sites = open("rollingSites");
            runSource = acc(runCls, "source");
            runNodes = acc(runCls, "nodes");
            runTerminal = acc(runCls, "terminal");
            runMoves = acc(runCls, "moves");
            runSamples = acc(runCls, "samples");
            mvNode = acc(moveCls, "node");
            mvState = acc(moveCls, "state");
            stX = acc(stateCls, "x");
            stZ = acc(stateCls, "z");
            stH = acc(stateCls, "height");
            stDx = acc(stateCls, "dx");
            stDz = acc(stateCls, "dz");
            stStep = acc(stateCls, "step");
            samplerCtor = samplerCls.getDeclaredConstructor(WindowOutlets.Terrain.class,
                    double.class, double.class, double.class, double.class, boolean.class);
            samplerCtor.setAccessible(true);
            samplerHeight = samplerCls.getDeclaredMethod("height", double.class, double.class);
            samplerHeight.setAccessible(true);
            stateCtor = stateCls.getDeclaredConstructor(double.class, double.class, double.class,
                    double.class, double.class, int.class);
            stateCtor.setAccessible(true);
        }

        private Method open(String name, Class<?>... types) throws Exception {
            Method m = probe.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        }

        private static Method acc(Class<?> cls, String name) throws Exception {
            Method m = cls.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        }

        @SuppressWarnings("unchecked")
        List<SharedBoundaryDrainage.WorldCell> sites() throws Exception {
            return (List<SharedBoundaryDrainage.WorldCell>) sites.invoke(null);
        }

        Run trace(SharedBoundaryDrainage.WorldCell source, WindowOutlets.Terrain terrain, double half,
                  double trigger, double offsetX, double offsetZ, boolean moving, double sea,
                  int limit, boolean reset) throws Exception {
            return read(trace.invoke(null, source, terrain, half, trigger, offsetX, offsetZ, moving, sea, limit, reset));
        }

        /** 从给定状态续追（{@code rollingResume}）。{@code stripMomentum} 仅用于单变量隔离。 */
        Run resume(SharedBoundaryDrainage.WorldCell source, WindowOutlets.Terrain terrain, St state,
                   boolean stripMomentum, double sea, int limit) throws Exception {
            Object s = stateCtor.newInstance(state.x(), state.z(), state.h(),
                    stripMomentum ? 0.0 : state.dx(), stripMomentum ? 0.0 : state.dz(), state.step());
            return read(resume.invoke(null, source, terrain, s, HALF, TRIGGER, 0.0, 0.0, true, sea, limit, false));
        }

        /** 工作窗外取样必须被拒（不偷用无界地形调用）。 */
        boolean windowGuardBlocked(WindowOutlets.Terrain terrain) throws Exception {
            Object sampler = samplerCtor.newInstance(terrain, 0.0, 0.0, 24.0, 8.0, true);
            try {
                samplerHeight.invoke(sampler, 25.0, 0.0);
                return false;
            } catch (java.lang.reflect.InvocationTargetException e) {
                return e.getCause() != null && "RollingLimit".equals(e.getCause().getClass().getSimpleName());
            }
        }

        private Run read(Object rollingRun) throws Exception {
            SharedBoundaryDrainage.WorldCell src = (SharedBoundaryDrainage.WorldCell) runSource.invoke(rollingRun);
            List<St> nodes = new ArrayList<>();
            for (Object o : (List<?>) runNodes.invoke(rollingRun)) nodes.add(state(o));
            List<Mv> moves = new ArrayList<>();
            for (Object o : (List<?>) runMoves.invoke(rollingRun)) moves.add(new Mv((Integer) mvNode.invoke(o), state(mvState.invoke(o))));
            return new Run(src, List.copyOf(nodes), (String) runTerminal.invoke(rollingRun),
                    List.copyOf(moves), (Long) runSamples.invoke(rollingRun));
        }

        private St state(Object o) throws Exception {
            return new St((Double) stX.invoke(o), (Double) stZ.invoke(o), (Double) stH.invoke(o),
                    (Double) stDx.invoke(o), (Double) stDz.invoke(o), (Integer) stStep.invoke(o));
        }
    }

    // ================= [A] G42 滚窗回归（硬判据） ================================

    static void sectionRegression() throws Exception {
        note("");
        note("=== [A] G42 滚窗回归：已有积分器在真实 erodedHeight 上的保状态（硬判据） ===");
        List<SharedBoundaryDrainage.WorldCell> sites = g42.sites();
        ok(!sites.isEmpty(), "读取实际 rollingSites（反射取，不硬编码副本）", "empty");
        note("  rollingSites=" + sites);
        WindowOutlets.Terrain terrain = realTerrain(GEN);
        StringBuilder csv = new StringBuilder("siteX,siteZ,variant,nodes,terminal,moves,heightCalls,stateKept,strictPrefixOfRolling\n");
        boolean same = true, prefix = true, kept = true, gain = true;
        for (SharedBoundaryDrainage.WorldCell s : sites) {
            Run roll = g42.trace(s, terrain, HALF, TRIGGER, 0, 0, true, TP.seaLevel(), 4096, false);
            Run oracle = g42.trace(s, terrain, 10000, 9000, 0, 0, false, TP.seaLevel(), 4096, false);
            Run fixed = g42.trace(s, terrain, HALF, TRIGGER, 0, 0, false, TP.seaLevel(), 4096, false);
            COLD = append(COLD, s, roll);
            same &= identical(roll, oracle);
            boolean pref = isPrefix(fixed, roll) && "WORK_WINDOW_LIMIT".equals(fixed.terminal());
            prefix &= pref;
            boolean k = roll.moves().stream().allMatch(m -> roll.nodes().get(m.node()).equals(m.state()));
            kept &= k;
            gain &= !roll.moves().isEmpty() && roll.nodes().size() > fixed.nodes().size();
            row(csv, s.x(), s.z(), "ROLLING", roll.nodes().size(), roll.terminal(), roll.moves().size(), roll.samples(), k, pref);
            row(csv, s.x(), s.z(), "FIXED", fixed.nodes().size(), fixed.terminal(), fixed.moves().size(), fixed.samples(), true, pref);
            row(csv, s.x(), s.z(), "UNWINDOWED_CONTROL", oracle.nodes().size(), oracle.terminal(), oracle.moves().size(), oracle.samples(), true, true);
            note("  site=" + s.x() + "," + s.z() + " rollingNodes=" + roll.nodes().size()
                    + " fixedNodes=" + fixed.nodes().size() + " moves=" + roll.moves().size() + " terminal=" + roll.terminal());
        }
        write("rolling-regression.csv", csv);
        ok(same, "滚动轨迹逐节点/终态等于无窗 oracle（换窗不改结果）", "differ");
        ok(prefix, "固定窗真实截断 WORK_WINDOW_LIMIT 且为滚动严格前缀", "not truncated or not prefix");
        ok(kept, "换窗保留位置/方向/高度/累计步数（moves.state 与 node 逐位相同）", "state was reset");
        ok(gain, "确有真实多窗续算收益（rolling 节点数 > 固定窗 且 moves > 0）", "no gain");
        regressionPhase(terrain, sites);
        regressionNegative(terrain, sites);
        verdict("regression.G42-rolling", hardFail == 0 ? "PASS" : "FAIL", "见上 4 条硬判据");
    }

    static Map<SharedBoundaryDrainage.WorldCell, Run> append(Map<SharedBoundaryDrainage.WorldCell, Run> m,
                                                             SharedBoundaryDrainage.WorldCell k, Run v) {
        Map<SharedBoundaryDrainage.WorldCell, Run> out = new LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }

    /** 窗口相位 / 推进阈值 / 半宽不变性。 */
    static void regressionPhase(WindowOutlets.Terrain terrain, List<SharedBoundaryDrainage.WorldCell> sites) throws Exception {
        note("  -- 相位 / 阈值 / 半宽不变性 --");
        StringBuilder csv = new StringBuilder("siteX,siteZ,half,trigger,offsetX,nodes,moves,sameAsBase\n");
        boolean same = true;
        for (SharedBoundaryDrainage.WorldCell s : sites) {
            for (int trigger : new int[]{6, 14, 20}) for (int off : new int[]{-6, 0, 6}) {
                Run r = g42.trace(s, terrain, HALF, trigger, off, -off, true, TP.seaLevel(), 4096, false);
                boolean eq = identical(r, COLD.get(s));
                same &= eq;
                row(csv, s.x(), s.z(), HALF, trigger, off, r.nodes().size(), r.moves().size(), eq);
            }
            Run wide = g42.trace(s, terrain, 40, 30, 11, -11, true, TP.seaLevel(), 4096, false);
            same &= identical(wide, COLD.get(s));
            row(csv, s.x(), s.z(), 40, 30, 11, wide.nodes().size(), wide.moves().size(), identical(wide, COLD.get(s)));
        }
        write("rolling-phase.csv", csv);
        ok(same, "改变窗口相位/推进阈值/半宽 ⇒ 完整轨迹逐位不变", "different");
    }

    /** 重置方向负控 / 逆序热查询 / 独立冷重放 / 窗外采样拒绝 / 平地不造出口。 */
    static void regressionNegative(WindowOutlets.Terrain terrain, List<SharedBoundaryDrainage.WorldCell> sites) throws Exception {
        note("  -- 负控 --");
        SharedBoundaryDrainage.WorldCell s0 = sites.get(0);
        Run reset = g42.trace(s0, terrain, HALF, TRIGGER, 0, 0, true, TP.seaLevel(), 4096, true);
        ok(!identical(reset, COLD.get(s0)), "换窗清方向必须改变轨迹（保状态判据有牙齿）", "reset 与保状态相同 ⇒ 负控空转");
        List<SharedBoundaryDrainage.WorldCell> probe = sites.subList(0, Math.min(2, sites.size()));
        List<SharedBoundaryDrainage.WorldCell> reversed = new ArrayList<>(probe);
        Collections.reverse(reversed);
        boolean warm = true;
        for (SharedBoundaryDrainage.WorldCell s : reversed) {
            warm &= identical(g42.trace(s, terrain, HALF, 20, 7, -7, true, TP.seaLevel(), 4096, false), COLD.get(s));
        }
        ok(warm, "逆序热查询 + 平移窗口/阈值 ⇒ 完整轨迹逐位一致", "differ");
        WindowOutlets.Terrain cold = realTerrain(buildGen(SEED));
        boolean replay = true;
        for (SharedBoundaryDrainage.WorldCell s : reversed) {
            replay &= identical(g42.trace(s, cold, HALF, TRIGGER, 0, 0, true, TP.seaLevel(), 4096, false), COLD.get(s));
        }
        ok(replay, "独立冷 CellGenerator 逆序重放 ⇒ 同轨迹（无跨查询/跨实例状态泄漏）", "differ");
        ok(g42.windowGuardBlocked(terrain), "工作窗外采样被拒绝（不偷用无界地形调用）", "guard allowed out-of-window sample");
        Run flat = g42.trace(new SharedBoundaryDrainage.WorldCell(-8, 0), (x, z) -> 100.0, 24, 8, 0, 0, true, 0, 180, false);
        ok(flat.nodes().size() == 1 && flat.terminal().endsWith("PENDING"), "真平地不能靠移动窗口强造出口或海", "flat moved");
    }

    static boolean identical(Run a, Run b) {
        return a.nodes().equals(b.nodes()) && a.terminal().equals(b.terminal());
    }

    static boolean isPrefix(Run shortRun, Run full) {
        return shortRun.nodes().size() <= full.nodes().size()
                && full.nodes().subList(0, shortRun.nodes().size()).equals(shortRun.nodes());
    }

    // ================= [B] 真实地形：列碰撞候选的单变量隔离 ======================

    static void sectionJoinReal() throws Exception {
        note("");
        note("=== [B] 真实 erodedHeight：列碰撞候选下的单变量隔离（候选 verdict，不进退出码） ===");
        note("  ★ 口径：两条轨迹的浮点轨迹「落在同一整数列」只是【列碰撞候选】；");
        note("    本项目并未实现真正的汇合判定（无统一接收状态、无面通量合并）⇒ 不称其为真汇合。");
        note("  ★ 隔离手法：在该列取各自的中途状态（mid-step，非终点），把位置/高度/step 归一，");
        note("    只保留各自在该点的【方向动量】作为唯一自变量，再用 G42 rollingResume 续追。");
        Map<SharedBoundaryDrainage.WorldCell, Run> runs = realRuns();
        List<List<SharedBoundaryDrainage.WorldCell>> groups = columnGroups(runs);
        note("  columnGroups（按共用整数列聚类）=" + describe(groups));
        List<List<SharedBoundaryDrainage.WorldCell>> real = new ArrayList<>();
        for (List<SharedBoundaryDrainage.WorldCell> g : groups) if (g.size() >= 2) real.add(g);
        ok(!real.isEmpty(), "真实轨迹至少存在一组列碰撞候选（否则此处无对象可测）", "no collision group");
        WindowOutlets.Terrain terrain = realTerrain(GEN);
        StringBuilder csv = new StringBuilder("group,aX,aZ,bX,bZ,collisionColX,collisionColZ,collisionStepA,collisionStepB,"
                + "headingAngleDeg,momentumSharedNodes,strippedSharedNodes,momentumNodesA,momentumNodesB\n");
        int pairs = 0, informativePairs = 0, diverging = 0, converging = 0, bestStripped = 0;
        for (int gi = 0; gi < real.size(); gi++) {
            for (int i = 0; i < real.get(gi).size(); i++) for (int j = i + 1; j < real.get(gi).size(); j++) {
                SharedBoundaryDrainage.WorldCell sa = real.get(gi).get(i), sb = real.get(gi).get(j);
                int[] col = collisionColumn(runs.get(sa), runs.get(sb));
                if (col == null) continue;
                St ma = momentumAt(runs.get(sa), col[0], col[1]), mb = momentumAt(runs.get(sb), col[0], col[1]);
                if (ma == null || mb == null) continue;
                pairs++;
                if (Math.hypot(ma.dx() - mb.dx(), ma.dz() - mb.dz()) <= 0.05) continue;
                informativePairs++;
                St base = new St(col[0], col[1], terrain.height(col[0], col[1]), 0, 0, 0);
                Run ka = g42.resume(sa, terrain, new St(base.x(), base.z(), base.h(), ma.dx(), ma.dz(), 0), false, TP.seaLevel(), 4096);
                Run kb = g42.resume(sb, terrain, new St(base.x(), base.z(), base.h(), mb.dx(), mb.dz(), 0), false, TP.seaLevel(), 4096);
                Run za = g42.resume(sa, terrain, base, true, TP.seaLevel(), 4096);
                Run zb = g42.resume(sb, terrain, base, true, TP.seaLevel(), 4096);
                int mRun = tailSharedStates(ka, kb), zRun = tailSharedStates(za, zb);
                if (mRun == 0) diverging++;
                if (zRun >= 2) converging++;
                bestStripped = Math.max(bestStripped, zRun);
                row(csv, gi, sa.x(), sa.z(), sb.x(), sb.z(), col[0], col[1], ma.step(), mb.step(),
                        angleBetween(ma, mb), mRun, zRun, ka.nodes().size(), kb.nodes().size());
            }
        }
        write("join-suffix-real.csv", csv);
        note("  collisionCandidates=" + pairs + " informativePairs=" + informativePairs
                + " momentumDiverged=" + diverging + " strippedConverged=" + converging
                + " bestStrippedSharedNodes=" + bestStripped);
        ok(informativePairs > 0, "存在动量确实不同的列碰撞候选（单变量对照非空转）", "no informative pair");
        boolean momentumKeepsPrefix = informativePairs > 0 && diverging == 0;
        candidate("join.G42-momentum-guarantees-common-suffix", momentumKeepsPrefix,
                "反例 " + diverging + "/" + informativePairs + " 个候选上携带动量时后缀为 0 节点"
                        + "（清零动量则有 " + bestStripped + " 个共同节点）⇒ 源动量不是共同后缀的充分条件");
        note("  ★ 该结论只覆盖【列碰撞候选】；真实汇合（统一接收状态 / 面通量合并）本项目尚未实现。");
        note("  ★ 也不排除其它候选：共享面通量、共同动量合成等合并算子本轮均未检验。");
        writeProfiles(runs, terrain);
    }

    static Map<SharedBoundaryDrainage.WorldCell, Run> realRuns() {
        Map<SharedBoundaryDrainage.WorldCell, Run> out = new LinkedHashMap<>();
        for (var e : COLD.entrySet()) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** 两条轨迹共用的整数列里【最靠上游】的一个 = 碰撞候选点（不是终点、不是距离最近）。 */
    static int[] collisionColumn(Run a, Run b) {
        LinkedHashSet<Long> inA = new LinkedHashSet<>();
        for (St s : a.nodes()) inA.add(columnKey(s));
        for (St s : b.nodes()) {
            if (inA.contains(columnKey(s))) return new int[]{(int) Math.floor(s.x()), (int) Math.floor(s.z())};
        }
        return null;
    }

    static long columnKey(St s) {
        return ((long) (int) Math.floor(s.x()) << 32) ^ ((int) Math.floor(s.z()) & 0xffffffffL);
    }

    static St momentumAt(Run run, int cx, int cz) {
        for (St s : run.nodes()) if ((int) Math.floor(s.x()) == cx && (int) Math.floor(s.z()) == cz) return s;
        return null;
    }

    /** 两条续追轨迹自尾对齐的逐位公共节点数（量化后缀，非布尔）。 */
    static int tailSharedStates(Run a, Run b) {
        int k = 0;
        while (k < a.nodes().size() && k < b.nodes().size()
                && a.nodes().get(a.nodes().size() - 1 - k).equals(b.nodes().get(b.nodes().size() - 1 - k))) k++;
        return k;
    }

    static double angleBetween(St a, St b) {
        double n = Math.hypot(a.dx(), a.dz()) * Math.hypot(b.dx(), b.dz());
        if (!(n > 1e-16)) return 0.0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (a.dx() * b.dx() + a.dz() * b.dz()) / n))));
    }

    static List<List<SharedBoundaryDrainage.WorldCell>> columnGroups(Map<SharedBoundaryDrainage.WorldCell, Run> runs) {
        List<SharedBoundaryDrainage.WorldCell> items = new ArrayList<>(runs.keySet());
        LinkedHashSet<SharedBoundaryDrainage.WorldCell> used = new LinkedHashSet<>();
        List<List<SharedBoundaryDrainage.WorldCell>> out = new ArrayList<>();
        for (SharedBoundaryDrainage.WorldCell s : items) {
            if (!used.add(s)) continue;
            List<SharedBoundaryDrainage.WorldCell> g = new ArrayList<>();
            g.add(s);
            for (SharedBoundaryDrainage.WorldCell o : items) {
                if (used.contains(o)) continue;
                if (collisionColumn(runs.get(s), runs.get(o)) != null) { g.add(o); used.add(o); }
            }
            out.add(g);
        }
        return out;
    }

    static String describe(List<List<SharedBoundaryDrainage.WorldCell>> groups) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append("g").append(i).append('=');
            for (SharedBoundaryDrainage.WorldCell c : groups.get(i)) sb.append('(').append(c.x()).append(',').append(c.z()).append(')');
        }
        return sb.toString();
    }

    /** 真实 erodedHeight 的纵剖面 + crest/bed 横剖面。 */
    static void writeProfiles(Map<SharedBoundaryDrainage.WorldCell, Run> runs, WindowOutlets.Terrain terrain) throws Exception {
        note("  -- 真实 erodedHeight 纵剖面 + crest/bed 横剖面 --");
        StringBuilder csv = new StringBuilder("siteX,siteZ,kind,index,step,centerX,centerZ,centerHeight,crestHeight,bedHeight,dirX,dirZ\n");
        boolean monotone = true, bracket = true;
        for (var e : runs.entrySet()) {
            List<St> n = e.getValue().nodes();
            double prev = Double.POSITIVE_INFINITY;
            for (St s : n) { monotone &= s.h() < prev; prev = s.h(); }
            int stride = Math.max(1, n.size() / 20);
            for (int i = 0; i < n.size(); i += stride) {
                St s = n.get(i);
                row(csv, e.getKey().x(), e.getKey().z(), "LONGITUDINAL", i, s.step(), s.x(), s.z(), s.h(), "", "", s.dx(), s.dz());
                double len = Math.hypot(s.dx(), s.dz());
                if (!(len > 1e-12)) continue;
                double px = -s.dz() / len, pz = s.dx() / len;
                double crest = Double.NEGATIVE_INFINITY, bed = Double.POSITIVE_INFINITY;
                for (int t = -6; t <= 6; t++) {
                    double h = terrain.height(s.x() + t * px, s.z() + t * pz);
                    crest = Math.max(crest, h); bed = Math.min(bed, h);
                    row(csv, e.getKey().x(), e.getKey().z(), "CROSS", i, t, s.x() + t * px, s.z() + t * pz, h, crest, bed, px, pz);
                }
                bracket &= crest >= s.h() - 1e-9 && bed <= s.h() + 1e-9;
            }
        }
        write("profiles.csv", csv);
        ok(monotone, "真实轨迹纵剖面严格下降（滚动积分器的下降契约）", "a node did not descend");
        ok(bracket, "横剖面 crest/bed 夹住中心（采样方向与极值自洽）", "crest/bed did not bracket center");
    }

    // ================= [C] 局部 canonical recipient（无记忆）对照 ================

    /**
     * 4 邻格严格唯一最小、横向平滑的合成峡谷；<b>谷心在 (ox,oz)</b>，下游朝 +z。
     * 平移只改世界坐标，不改任何几何关系 ⇒ 可用于「接收点本身在负象限」的验证。
     */
    static WindowOutlets.Terrain funnelAt(double ox, double oz) {
        return (x, z) -> 100.0 - 0.5 * (z - oz) - 60.0 * Math.exp(-((x - ox) * (x - ox)) / 32.0);
    }

    static WindowOutlets.Terrain funnel() { return funnelAt(0, 0); }

    static LocalFlowEvents.Event birth(long cfg, int round, int x, int z) {
        return new LocalFlowEvents.Event(new LocalFlowEvents.Id(SEED, cfg, -1, 0, round), new LocalFlowEvents.Point(x, z), 1);
    }

    /** 三条 canonical 臂：round = 0/1/2，出生点由参数给定（不依赖哈希，便于平移验证）。 */
    static List<LocalFlowEvents.Trace> arms(LocalFlowEvents flow, LocalFlowEvents.Point... starts) {
        List<LocalFlowEvents.Trace> out = new ArrayList<>();
        for (int i = 0; i < starts.length; i++) out.add(flow.trajectory(birth(0x5EEDL, i, starts[i].x(), starts[i].z())));
        return out;
    }

    static void sectionJoinCanonical() throws Exception {
        note("");
        note("=== [C] LocalFlowEvents canonical recipient（无记忆、按世界列取后继）对照 ===");
        note("  两/三入流：各自轨迹、联合 query、正序/逆序 query 的完整结果必须逐位一致；");
        note("  汇合列的贡献必须是各臂【相加】，不是 first-occupied 的单臂覆盖。");
        note("  ★ Stop 三分类：LIFETIME=走满步数的完整轨迹；LOCAL_MINIMUM=真 sink；");
        note("    GEOMETRY_PENDING=平局未决 ⇒ 其前缀【只作诊断】，绝不当完整合法水路。");
        LocalFlowEvents flow = new LocalFlowEvents(new LocalFlowEvents.Spec(SEED, 0x5EEDL, 8, 1, 400), funnel());
        LocalFlowEvents.Point s1 = new LocalFlowEvents.Point(-6, 16);
        LocalFlowEvents.Point s2 = new LocalFlowEvents.Point(6, 16);
        LocalFlowEvents.Point s3 = new LocalFlowEvents.Point(9, 20);
        List<LocalFlowEvents.Trace> ts = arms(flow, s1, s2, s3);
        LocalFlowEvents.Trace tA = ts.get(0), tB = ts.get(1), tC = ts.get(2);
        note("  canonical stops a=" + tA.stop() + " b=" + tB.stop() + " c=" + tC.stop()
                + " lengths=" + tA.points().size() + "/" + tB.points().size() + "/" + tC.points().size());
        ok(tA.points().size() >= 50 && tB.points().size() >= 50 && tC.points().size() >= 50,
                "canonical 路径足够长（对照非空转）", "too short");
        ok(resolved(tA.stop()) && resolved(tB.stop()) && resolved(tC.stop()),
                "三臂均无平局未决（后缀判据只建立在已解决的轨迹上）",
                "stops=" + tA.stop() + "/" + tB.stop() + "/" + tC.stop() + " ⇒ pending 前缀不当完整水路");
        List<LocalFlowEvents.Point> a = tA.points(), b = tB.points(), c = tC.points();
        int ab = sharedFromMeet(a, b), ac = sharedFromMeet(a, c), bc = sharedFromMeet(b, c);
        note("  canonical sharedSuffix a|b=" + ab + " a|c=" + ac + " b|c=" + bc + "（自最早共用列起算）");
        candidate("join.canonical-two-inflow-common-suffix", ab >= 5, "a|b 共同后缀仅 " + ab);
        candidate("join.canonical-three-tributary-common-suffix", ac >= 5 && bc >= 5, "a|c=" + ac + " b|c=" + bc);
        joinQueryConsistency(flow, ts, s1, s2, s3);
        pendingVsSinkControl();
        canonicalVsMomentum(s1, s2, s3, ab);
        translatedNegativeFixture();
        verdict("join.canonical-recipient-common-suffix", ab >= 5 ? "ACCEPTED_LOCAL_ONLY" : "REJECTED",
                "只在无记忆 canonical 规则下成立；与 G42 自然路线无关，未定义生产合并算子");
    }

    /** 已解决 = 不是「平局未决」。pending 前缀只作诊断，不能当完整合法水路。 */
    static boolean resolved(LocalFlowEvents.Stop s) { return s != LocalFlowEvents.Stop.GEOMETRY_PENDING; }

    static LocalFlowEvents.Stop stopOf(LocalFlowEvents flow, LocalFlowEvents.Event e) {
        return flow.trajectory(e).stop();
    }

    /**
     * 平局未决 vs 真 sink 的区分：两者路径长度都 = 1，仅凭 {@code path} 无法分辨，
     * 必须依赖显式 {@code Stop}；且 pending 前缀不得被当成完整合法水路。
     */
    static void pendingVsSinkControl() throws Exception {
        note("  -- [C1] 平局未决 vs 真 sink：两者路径长度都=1，只能靠 Stop 分辨 --");
        WindowOutlets.Terrain tie = (x, z) -> (z == 0 && Math.abs(x) == 1) ? 0.0 : (x == 0 && z == 0) ? 1.0 : 10.0;
        LocalFlowEvents trace1 = new LocalFlowEvents(new LocalFlowEvents.Spec(SEED, 0x71EL, 8, 1, 50), tie);
        LocalFlowEvents.Trace tieTrace = trace1.trajectory(birth(0x71EL, 0, 0, 0));
        WindowOutlets.Terrain sink = (x, z) -> (x == 0 && z == 0) ? 0.0 : 10.0;
        LocalFlowEvents trace2 = new LocalFlowEvents(new LocalFlowEvents.Spec(SEED, 0x51EL, 8, 1, 50), sink);
        LocalFlowEvents.Trace sinkTrace = trace2.trajectory(birth(0x51EL, 0, 0, 0));
        note("  tie  : stop=" + tieTrace.stop() + " points=" + tieTrace.points().size());
        note("  sink : stop=" + sinkTrace.stop() + " points=" + sinkTrace.points().size());
        ok(tieTrace.stop() == LocalFlowEvents.Stop.GEOMETRY_PENDING,
                "平局未决被判为 GEOMETRY_PENDING（不冒充真 sink）", "stop=" + tieTrace.stop());
        ok(sinkTrace.stop() == LocalFlowEvents.Stop.LOCAL_MINIMUM,
                "真局部极小被判为 LOCAL_MINIMUM（与平局未决可区分）", "stop=" + sinkTrace.stop());
        ok(tieTrace.points().size() == sinkTrace.points().size(),
                "两者路径长度相同 ⇒ 只靠 path 分不开，这正是必须显式 Stop 的原因",
                "lengths=" + tieTrace.points().size() + "/" + sinkTrace.points().size());
        ok(!resolved(tieTrace.stop()),
                "★ pending 前缀不当完整合法水路：平局未决必须被判为未解决",
                "GEOMETRY_PENDING 被当成已解决 ⇒ 会把未决终态当水路终点");
    }

    /** {@code Result.stops} 报告：分类必须覆盖全部事件，且与查询顺序无关。 */
    static void stopsReport(LocalFlowEvents flow, LocalFlowEvents.Rect rect) {
        LocalFlowEvents.Result fwd = flow.query(rect, 16, false);
        LocalFlowEvents.Result rev = flow.query(rect, 16, true);
        Map<LocalFlowEvents.Stop, Integer> stops = fwd.stops();
        int total = stops.values().stream().mapToInt(Integer::intValue).sum();
        int pending = stops.getOrDefault(LocalFlowEvents.Stop.GEOMETRY_PENDING, 0);
        note("  Result.stops=" + stops + " sum=" + total + " events=" + fwd.events()
                + " pendingPrefixes=" + pending + "（pending 只作诊断，不算完整合法水路）");
        ok(total == fwd.events(), "每个事件都有且仅有一个 Stop 分类（停止原因无遗漏）",
                "sum=" + total + " events=" + fwd.events());
        ok(stops.equals(rev.stops()), "Stop 分类与查询顺序无关（正序 == 逆序）", "order dependent");
    }

    /**
     * 三条轨迹<b>最早共有</b>的世界列 = 三臂共同 recipient（ABC 真正汇入点）。
     * 取最早而非最晚，才能保证三条路径此后都覆盖该列之后的下游段。
     */
    static LocalFlowEvents.Point tripleCommon(List<LocalFlowEvents.Point> a, List<LocalFlowEvents.Point> b,
                                             List<LocalFlowEvents.Point> c) {
        LinkedHashSet<LocalFlowEvents.Point> inB = new LinkedHashSet<>(b);
        LinkedHashSet<LocalFlowEvents.Point> inC = new LinkedHashSet<>(c);
        for (LocalFlowEvents.Point p : a) if (inB.contains(p) && inC.contains(p)) return p;
        return null;
    }

    /** first-occupied 的产出：每列被哪条<b>事件身份</b>占用（身份才是判据，不是数值大小）。 */
    record Claimed(Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> field,
                   Map<LocalFlowEvents.Point, LocalFlowEvents.Id> owner) { }

    /**
     * first-occupied（先到优先）的<b>可执行变异</b>：同一列若已被先处理的臂占用，后者直接跳过。
     * 同时记录「占用者身份」，因为两臂在合成场上数值可能相同 —— 只有身份能区分顺序依赖。
     */
    static Claimed firstOccupied(LocalFlowEvents flow, LocalFlowEvents.Rect win, List<LocalFlowEvents.Event> ordered) {
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> out = new TreeMap<>();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Id> owner = new TreeMap<>();
        for (LocalFlowEvents.Event e : ordered) {
            Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> one = flow.accumulate(win, List.of(e), 16, false).field();
            for (var kv : one.entrySet()) {
                if (owner.containsKey(kv.getKey())) continue;
                owner.put(kv.getKey(), e.id());
                out.put(kv.getKey(), kv.getValue());
            }
        }
        return new Claimed(out, owner);
    }

    /**
     * 负坐标两/三入流：完整 field 必须满足
     * ① 联合累计 == 各臂相加；② 正/逆序一致；③ 共同 recipient 上 = A+B(+C) 且各臂 >0；
     * ④ first-occupied 变异必须与「相加」不同，且其归属随顺序翻转（负控有牙齿）。
     */
    static void joinQueryConsistency(LocalFlowEvents flow, List<LocalFlowEvents.Trace> ts,
                                     LocalFlowEvents.Point s1, LocalFlowEvents.Point s2,
                                     LocalFlowEvents.Point s3) throws Exception {
        LocalFlowEvents.Event eA = birth(0x5EEDL, 0, s1.x(), s1.z());
        LocalFlowEvents.Event eB = birth(0x5EEDL, 1, s2.x(), s2.z());
        LocalFlowEvents.Event eC = birth(0x5EEDL, 2, s3.x(), s3.z());
        LocalFlowEvents.Stop sA = stopOf(flow, eA), sB = stopOf(flow, eB), sC = stopOf(flow, eC);
        note("  armStops A=" + sA + " B=" + sB + " C=" + sC + "（pending 前缀只作诊断，不当完整水路）");
        ok(resolved(sA) && resolved(sB) && resolved(sC),
                "三臂 Stop 均为已解决（无平局未决，否则汇合几何未定）", "A=" + sA + " B=" + sB + " C=" + sC);
        LocalFlowEvents.Rect win = new LocalFlowEvents.Rect(-24, -8, 24, 32);
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> oneA = one(flow, win, eA);
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> oneB = one(flow, win, eB);
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> oneC = one(flow, win, eC);
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> two = flow.accumulate(win, List.of(eA, eB), 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> three = flow.accumulate(win, List.of(eA, eB, eC), 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> twoRev = flow.accumulate(win, List.of(eB, eA), 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> threeRev = flow.accumulate(win, List.of(eC, eB, eA), 16, false).field();
        ok(!two.isEmpty() && !three.isEmpty(), "两/三入流联合累计非空（负坐标对照非空转）", "empty");
        ok(two.equals(twoRev) && three.equals(threeRev), "两/三入流：正序 vs 逆序 完整结果逐位一致", "order dependent");
        ok(addFields(oneA, oneB).equals(two),
                "两入流：联合累计 == 各自累计相加（共同 recipient，非 first-occupied 覆盖）",
                "joint != sum(arms) ⇒ 存在覆盖式合并");
        ok(addFields(addFields(oneA, oneB), oneC).equals(three),
                "三入流：联合累计 == 三臂各自累计相加", "joint != sum(arms)");
        pairedRecipient(oneA, oneB, two);
        tripleRecipient(ts, oneA, oneB, oneC, three);
        firstOccupiedControl(flow, win, eA, eB, eC, three);
    }

    static Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> one(LocalFlowEvents flow, LocalFlowEvents.Rect win,
                                                                 LocalFlowEvents.Event e) {
        return flow.accumulate(win, List.of(e), 16, false).field();
    }

    /** 两臂共同 recipient：visits/mass 严格等于 A+B，且两臂各自 >0。 */
    static void pairedRecipient(Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> oneA,
                                Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> oneB,
                                Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> two) {
        LocalFlowEvents.Point shared = earliestCommon(oneA.keySet(), oneB.keySet());
        ok(shared != null, "两入流确有共同下游列（recipient 由世界列定义，非距离最近）", "none");
        if (shared == null) return;
        LocalFlowEvents.Amount a = oneA.get(shared), b = oneB.get(shared), ab = two.get(shared);
        note("  两臂 recipient=" + shared + "  A=" + a + "  B=" + b + "  A+B=" + ab);
        ok(a != null && b != null && ab != null, "汇合列上两臂各自与联合均有贡献（可比较）", "absent");
        if (a == null || b == null || ab == null) return;
        ok(a.visits() > 0 && b.visits() > 0,
                "汇合列上 A、B 两臂各自都贡献（不是单臂独占）", "A=" + a.visits() + " B=" + b.visits());
        ok(ab.visits() == a.visits() + b.visits() && ab.mass() == a.mass() + b.mass(),
                "汇合列 visits/mass 严格等于两臂之和（相加而非覆盖）",
                "joint=" + ab.visits() + " vs " + a.visits() + "+" + b.visits());
    }

    /**
     * ★ 三臂真正共同列：ABC.visits/mass 必须<b>严格等于</b> A+B+C，且三臂各自 >0。
     * 旧版只断言 {@code ABC >= AB}，这在第三臂尚未汇入该列时也成立 ⇒ 判据过弱。
     */
    static void tripleRecipient(List<LocalFlowEvents.Trace> ts,
                                Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> oneA,
                                Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> oneB,
                                Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> oneC,
                                Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> three) {
        LocalFlowEvents.Point t3 = tripleCommon(ts.get(0).points(), ts.get(1).points(), ts.get(2).points());
        ok(t3 != null, "三臂存在真正共有的世界列（不是只有 AB 共有）", "triple intersection empty");
        if (t3 == null) return;
        LocalFlowEvents.Amount a = oneA.get(t3), b = oneB.get(t3), c = oneC.get(t3), abc = three.get(t3);
        note("  三臂 recipient=" + t3 + "  A=" + a + "  B=" + b + "  C=" + c + "  A+B+C=" + abc);
        ok(a != null && b != null && c != null && abc != null,
                "三臂共同列上三臂各自与联合均有贡献（可比较）", "absent");
        if (a == null || b == null || c == null || abc == null) return;
        ok(a.visits() > 0 && b.visits() > 0 && c.visits() > 0,
                "三臂共同列上 A、B、C <b>各自</b>都贡献（非仅 A+B，第三臂确已汇入）",
                "A=" + a.visits() + " B=" + b.visits() + " C=" + c.visits());
        long sumV = a.visits() + b.visits() + c.visits(), sumM = a.mass() + b.mass() + c.mass();
        ok(abc.visits() == sumV && abc.mass() == sumM,
                "三臂共同列 visits/mass <b>严格等于</b> A+B+C（不是仅 ≥AB）",
                "ABC=" + abc.visits() + "/" + abc.mass() + " vs " + sumV + "/" + sumM);
    }

    /**
     * first-occupied 变异负控（硬判据，有牙齿）：
     * ① 在真实多源列上，正序 vs 逆序 first-occupied 的归属<b>必须身份不同</b>；
     * ② 该变异结果必须与「联合相加」不同（否则负控无法区分两者）。
     */
    static void firstOccupiedControl(LocalFlowEvents flow, LocalFlowEvents.Rect win,
                                     LocalFlowEvents.Event eA, LocalFlowEvents.Event eB, LocalFlowEvents.Event eC,
                                     Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> three) {
        note("  -- [C2] first-occupied（先到优先）变异负控 --");
        Claimed fwd = firstOccupied(flow, win, List.of(eA, eB, eC));
        Claimed rev = firstOccupied(flow, win, List.of(eC, eB, eA));
        int shared = 0, flips = 0;
        for (var kv : fwd.owner().entrySet()) {
            LocalFlowEvents.Id r = rev.owner().get(kv.getKey());
            if (r == null) continue;
            shared++;
            if (!r.equals(kv.getValue())) flips++;
        }
        note("  firstOccupied sharedCells=" + shared + " identityFlips=" + flips
                + "（身份 = 事件的 Id，不用数值大小判序依赖）");
        ok(shared > 0, "first-occupied 变异在两序下覆盖同一批列（可比较，非空转）", "shared=0");
        ok(flips > 0,
                "★ 负控：真实多源列上 first-occupied 的<b>占用者身份</b>随处理顺序改变 ⇒ 该规则不可作接收规则",
                "flips=0 于 " + shared + " 个共有列 ⇒ 负控空转，无法否证 first-occupied-wins");
        ok(!fwd.field().equals(three),
                "★ 负控：first-occupied 结果 ≠ 联合相加结果 ⇒ 两者确为不同规则（断言能分辨）",
                "first-occupied == 相加 ⇒ 判据分不开覆盖式与相加式合并");
    }

    /** 两个 key 集合的最早共同列（按 Point 全序取最小，保证确定性）。 */
    static LocalFlowEvents.Point earliestCommon(java.util.Collection<LocalFlowEvents.Point> x,
                                                java.util.Collection<LocalFlowEvents.Point> y) {
        LinkedHashSet<LocalFlowEvents.Point> inY = new LinkedHashSet<>(y);
        LocalFlowEvents.Point best = null;
        for (LocalFlowEvents.Point p : x) if (inY.contains(p) && (best == null || p.compareTo(best) < 0)) best = p;
        return best;
    }

    static Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> addFields(Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> x,
                                                                       Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> y) {
        TreeMap<LocalFlowEvents.Point, LocalFlowEvents.Amount> out = new TreeMap<>(x);
        for (var e : y.entrySet()) out.merge(e.getKey(), e.getValue(), LocalFlowJoinProbe::addAmounts);
        return out;
    }

    /** 两个贡献相加：visits/mass/动量全部线性叠加（这是「共同 recipient」而非「覆盖」的判据）。 */
    static LocalFlowEvents.Amount addAmounts(LocalFlowEvents.Amount p, LocalFlowEvents.Amount q) {
        return new LocalFlowEvents.Amount(p.visits() + q.visits(), p.mass() + q.mass(),
                p.momentumX() + q.momentumX(), p.momentumZ() + q.momentumZ());
    }

    static TreeMap<LocalFlowEvents.Point, LocalFlowEvents.Amount> restrict(
            Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> f, LocalFlowEvents.Rect r) {
        TreeMap<LocalFlowEvents.Point, LocalFlowEvents.Amount> out = new TreeMap<>();
        for (var e : f.entrySet()) if (r.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** 同一合成汇流上：canonical（无记忆）有后缀，动量臂有 0 后缀 ⇒ 隔离原因。 */
    static void canonicalVsMomentum(LocalFlowEvents.Point s1, LocalFlowEvents.Point s2,
                                    LocalFlowEvents.Point s3, int canonicalSuffix) throws Exception {
        WindowOutlets.Terrain f = funnel();
        Run ma = g42.trace(new SharedBoundaryDrainage.WorldCell(s1.x(), s1.z()), f, HALF, TRIGGER, 0, 0, true, -1e9, 400, false);
        Run mb = g42.trace(new SharedBoundaryDrainage.WorldCell(s2.x(), s2.z()), f, HALF, TRIGGER, 0, 0, true, -1e9, 400, false);
        Run mc = g42.trace(new SharedBoundaryDrainage.WorldCell(s3.x(), s3.z()), f, HALF, TRIGGER, 0, 0, true, -1e9, 400, false);
        ok(ma.nodes().size() >= 50 && mb.nodes().size() >= 50, "动量轨迹也有足够节点（对照非空转）", "momentum run too short");
        int mab = tailSharedStates(ma, mb), mac = tailSharedStates(ma, mc);
        note("  momentum sharedSuffix a|b=" + mab + " a|c=" + mac + "（同一合成汇流，同一自尾对齐规则）");
        candidate("join.momentum-two-inflow-common-suffix", mab >= 5,
                "同一合成汇流上动量臂共同后缀=" + mab + " ⇒ 动量阻止了后缀合并");
        note("  ⇒ 隔离出原因：同一地形/汇流/对齐规则下 canonical=" + canonicalSuffix + "，动量臂=" + mab);
        note("  ⚠ canonical 规则是「世界列决定后继」的身份规则，不是 first-occupied-wins，也不把距离近当汇流；");
        note("    它不定义生产合并算子、不平决平局、不证水面/盆接口，也不排除共享面通量等其它候选。");
    }

    /**
     * ★ 平移负坐标 fixture：同一几何整体移到 (-64,-64)，
     * 要求**接收点本身**在负象限，且后缀长度 / joint map（按平移量对齐）与原值等值。
     *
     * <p>仅凭 sourceX 为负不足以称「负坐标汇流」——出生点在负、接收点仍可能在正象限。</p>
     */
    static void translatedNegativeFixture() throws Exception {
        note("  -- [C3] 平移负坐标 fixture：接收点本身在负象限，joint map 必须平移等值 --");
        double dx = -64, dz = -64;
        LocalFlowEvents base = new LocalFlowEvents(new LocalFlowEvents.Spec(SEED, 0x5EEDL, 8, 1, 400), funnel());
        LocalFlowEvents moved = new LocalFlowEvents(new LocalFlowEvents.Spec(SEED, 0x5EEDL, 8, 1, 400), funnelAt(dx, dz));
        LocalFlowEvents.Point p1 = new LocalFlowEvents.Point(-6, 16), p2 = new LocalFlowEvents.Point(6, 16);
        LocalFlowEvents.Point q1 = shifted(p1, dx, dz), q2 = shifted(p2, dx, dz);
        ok(q1.x() < 0 && q1.z() < 0 && q2.x() < 0 && q2.z() < 0,
                "平移后两个出生点都在负象限", q1 + " " + q2);
        List<LocalFlowEvents.Trace> tb = arms(base, p1, p2);
        List<LocalFlowEvents.Trace> tm = arms(moved, q1, q2);
        int baseSuffix = sharedFromMeet(tb.get(0).points(), tb.get(1).points());
        int movedSuffix = sharedFromMeet(tm.get(0).points(), tm.get(1).points());
        LocalFlowEvents.Point baseMeet = earliestCommon(tb.get(0).points(), tb.get(1).points());
        LocalFlowEvents.Point movedMeet = earliestCommon(tm.get(0).points(), tm.get(1).points());
        note("  baseMeet=" + baseMeet + " movedMeet=" + movedMeet + " suffix " + baseSuffix + " vs " + movedSuffix);
        ok(baseMeet != null && movedMeet != null,
                "两侧各自都有共同接收列（可比较）", baseMeet + "/" + movedMeet);
        ok(movedMeet != null && movedMeet.x() < 0 && movedMeet.z() < 0,
                "★ 平移后【接收点本身】在负象限（不是只有出生点为负）", "movedMeet=" + movedMeet);
        ok(baseMeet != null && movedMeet != null && movedMeet.equals(shifted(baseMeet, dx, dz)),
                "接收列恰为原接收列的整体平移（几何关系不变）",
                baseMeet + " + (" + dx + "," + dz + ") = " + (baseMeet == null ? "-" : shifted(baseMeet, dx, dz))
                        + " ≠ " + movedMeet);
        ok(baseSuffix == movedSuffix && baseSuffix >= 5,
                "共同后缀长度在平移下不变（同后缀，非仅同号）", baseSuffix + " vs " + movedSuffix);
        LocalFlowEvents.Rect win = new LocalFlowEvents.Rect(-24, -8, 24, 32);
        LocalFlowEvents.Rect mwin = new LocalFlowEvents.Rect((int) (win.x0() + dx), (int) (win.z0() + dz),
                (int) (win.x1() + dx), (int) (win.z1() + dz));
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> jb = base.accumulate(win,
                List.of(birth(0x5EEDL, 0, p1.x(), p1.z()), birth(0x5EEDL, 1, p2.x(), p2.z())), 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> jm = moved.accumulate(mwin,
                List.of(birth(0x5EEDL, 0, q1.x(), q1.z()), birth(0x5EEDL, 1, q2.x(), q2.z())), 16, false).field();
        ok(!jb.isEmpty() && shiftMap(jb, dx, dz).equals(jm),
                "联合累计 map 在平移下逐格等值（joint map 平移不变）",
                "base=" + jb.size() + " moved=" + jm.size());
    }

    static LocalFlowEvents.Point shifted(LocalFlowEvents.Point p, double dx, double dz) {
        return new LocalFlowEvents.Point((int) Math.round(p.x() + dx), (int) Math.round(p.z() + dz));
    }

    static Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> shiftMap(
            Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> f, double dx, double dz) {
        TreeMap<LocalFlowEvents.Point, LocalFlowEvents.Amount> out = new TreeMap<>();
        for (var e : f.entrySet()) out.put(shifted(e.getKey(), dx, dz), e.getValue());
        return out;
    }

    /** 自最早共用列起算的下游公共后缀（严格下降路径不重复列 ⇒ 最早共用列即候选汇合点）。 */
    static int sharedFromMeet(List<LocalFlowEvents.Point> a, List<LocalFlowEvents.Point> b) {
        java.util.HashMap<LocalFlowEvents.Point, Integer> where = new java.util.HashMap<>();
        for (int j = 0; j < b.size(); j++) where.putIfAbsent(b.get(j), j);
        for (int i = 0; i < a.size(); i++) {
            Integer j = where.get(a.get(i));
            if (j == null) continue;
            int k = 0;
            while (i + k < a.size() && j + k < b.size() && a.get(i + k).equals(b.get(j + k))) k++;
            return k;
        }
        return 0;
    }

    // ================= [D1] 局部事件场：有界完备 ================================

    static void sectionDiscoveryLocal() throws Exception {
        note("");
        note("=== [D1] 局部事件场（LocalFlowEvents）：有界完备 + 构造性负控（硬判据） ===");
        note("  口径：事件身份 = (seed, config, 世界出生格, 轮次)，出生点哈希落在该格内；每步四邻 ≤1 格。");
        note("  ⇒ 查询目标只需枚举其矩形外扩 L 的出生格：这就是【无限坐标上的按需定义】，");
        note("    不需要（也不可能）枚举全世界所有源。");
        LocalFlowEvents.Spec spec = new LocalFlowEvents.Spec(SEED, 0x00C0FFEEL, 4, 2, 12);
        LocalFlowEvents flow = new LocalFlowEvents(spec, (x, z) -> 1000.0 + x);
        LocalFlowEvents.Rect rect = aligned(-40, -40, 40, 40, spec.spacing());
        note("  rect=" + rect + "（对齐到 spacing ⇒ 每格 start 必在矩形内）");
        boolean enumOk = boundedEnumeration(flow, spec, rect);
        boolean orderOk = boundedOrderIndependence(flow, rect);
        boolean domainOk = boundedDomainIndependence(flow, spec, rect);
        boolean haloOk = boundedHaloControls(flow, spec, rect);
        stopsReport(flow, rect);
        ok(enumOk && orderOk && domainOk && haloOk,
                "局部事件场在有界矩形内构造性完备（枚举/顺序/域/漏窗外源 四项全绿）", "见上方 FAIL 明细");
        verdict("discovery.local-event-field", enumOk && orderOk && domainOk && haloOk ? "ACCEPTED" : "REJECTED",
                "固定世界有限事件场 + halo=L 已构成无限坐标上的按需定义");
    }

    /** 矩形对齐到 spacing ⇒ 每格 start 必在矩形内，计数判据才有意义。 */
    static LocalFlowEvents.Rect aligned(int x0, int z0, int x1, int z1, int spacing) {
        return new LocalFlowEvents.Rect(Math.floorDiv(x0, spacing) * spacing, Math.floorDiv(z0, spacing) * spacing,
                Math.floorDiv(x1, spacing) * spacing + spacing - 1,
                Math.floorDiv(z1, spacing) * spacing + spacing - 1);
    }

    static int expectedEvents(LocalFlowEvents.Rect r, int spacing, int rounds) {
        int cellsX = Math.floorDiv(r.x1(), spacing) - Math.floorDiv(r.x0(), spacing) + 1;
        int cellsZ = Math.floorDiv(r.z1(), spacing) - Math.floorDiv(r.z0(), spacing) + 1;
        return cellsX * cellsZ * rounds;
    }

    static boolean boundedEnumeration(LocalFlowEvents flow, LocalFlowEvents.Spec spec, LocalFlowEvents.Rect rect) throws Exception {
        List<LocalFlowEvents.Event> all = flow.events(rect);
        int expected = expectedEvents(rect, spec.spacing(), spec.rounds());
        ok(all.size() == expected, "有界全集恰为 格数×轮次 个事件（无遗漏/无重复）", "size=" + all.size() + " expected=" + expected);
        LinkedHashSet<LocalFlowEvents.Id> ids = new LinkedHashSet<>();
        boolean sorted = true, inRect = true;
        LocalFlowEvents.Id prev = null;
        for (LocalFlowEvents.Event e : all) {
            ids.add(e.id());
            sorted &= prev == null || prev.compareTo(e.id()) < 0;
            prev = e.id();
            inRect &= rect.contains(e.start());
        }
        ok(ids.size() == all.size(), "事件 id 互异（同一事件只计一次）", "duplicate ids");
        ok(sorted, "事件按 id 全序枚举（枚举顺序与结果无关）", "unsorted");
        ok(inRect, "每个枚举事件的出生点都在请求矩形内（不注入域外事件）", "start outside rect");
        StringBuilder csv = new StringBuilder("eventIndex,idCellX,idCellZ,idRound,startX,startZ,mass\n");
        for (int i = 0; i < all.size(); i++) {
            LocalFlowEvents.Event e = all.get(i);
            row(csv, i, e.id().cellX(), e.id().cellZ(), e.id().round(), e.start().x(), e.start().z(), e.mass());
        }
        write("events-bounded.csv", csv);
        return ids.size() == all.size() && sorted && inRect && all.size() == expected;
    }

    static boolean boundedOrderIndependence(LocalFlowEvents flow, LocalFlowEvents.Rect rect) throws Exception {
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> fwd = flow.query(rect, 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> rev = flow.query(rect, 16, true).field();
        ok(!fwd.isEmpty(), "有界场非空（负控非空转）", "empty field");
        ok(fwd.equals(rev), "正序/逆序累计 ⇒ 逐格 Amount 完全相同", "order dependent");
        int same = 0;
        for (int owner : new int[]{1, 7, 33}) if (flow.query(rect, owner, false).field().equals(fwd)) same++;
        ok(same == 3, "owner 大小 1/7/33 ⇒ 场不变（owner 只裁剪归属，不改轨迹）", "owner dependent");
        LocalFlowEvents.Rect up = new LocalFlowEvents.Rect(-40, -40, 0, 0);
        LocalFlowEvents.Rect down = new LocalFlowEvents.Rect(0, 0, 40, 40);
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> d1 = flow.query(down, 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> u1 = flow.query(up, 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> d2 = flow.query(down, 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> u2 = flow.query(up, 16, false).field();
        ok(!d1.isEmpty(), "下游矩形确有事件流经（任意下游先查询非空转）", "downstream empty");
        ok(d1.equals(d2) && u1.equals(u2), "先查下游 vs 先查上游 ⇒ 两个矩形结果一致（查询是纯函数）", "query-order dependent");
        return fwd.equals(rev) && same == 3 && !d1.isEmpty() && d1.equals(d2) && u1.equals(u2);
    }

    /** 更大「真正不同域」独立枚举后裁剪回原矩形 ⇒ 与原查询逐格相同。 */
    static boolean boundedDomainIndependence(LocalFlowEvents flow, LocalFlowEvents.Spec spec,
                                             LocalFlowEvents.Rect rect) throws Exception {
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> base = flow.query(rect, 16, false).field();
        LocalFlowEvents.Rect big = aligned(rect.x0() - 24, rect.z0() - 24, rect.x1() + 24, rect.z1() + 24, spec.spacing());
        ok(restrict(flow.query(big, 16, false).field(), rect).equals(base),
                "更大真正不同域 " + big + " 独立枚举后裁剪 ⇒ 与原矩形逐格相同", "domain size changed the field");
        int bigger = flow.events(big).size();
        ok(bigger == expectedEvents(big, spec.spacing(), spec.rounds()) && bigger > flow.events(rect).size(),
                "更大域事件数 = 其自身 格数×轮次 且 > 原域（对照域确实不同）",
                "bigger=" + bigger + " original=" + flow.events(rect).size());
        return restrict(flow.query(big, 16, false).field(), rect).equals(base);
    }

    /** 漏窗外源：距离 L 必须零贡献；距离 L-1 必须恰好贡献一次。 */
    static boolean boundedHaloControls(LocalFlowEvents flow, LocalFlowEvents.Spec spec, LocalFlowEvents.Rect rect) {
        int L = spec.lifetime(), z = 0;
        LocalFlowEvents.Event far = new LocalFlowEvents.Event(
                new LocalFlowEvents.Id(1, 1, 0, 0, 0), new LocalFlowEvents.Point(rect.x1() + L, z), 1);
        LocalFlowEvents.Event edge = new LocalFlowEvents.Event(
                new LocalFlowEvents.Id(1, 1, 0, 0, 1), new LocalFlowEvents.Point(rect.x1() + L - 1, z), 1);
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> farOnly = flow.accumulate(rect, List.of(far), 16, false).field();
        Map<LocalFlowEvents.Point, LocalFlowEvents.Amount> edgeOnly = flow.accumulate(rect, List.of(edge), 16, false).field();
        ok(farOnly.isEmpty(), "距离 L 的外源在 L 步内不可能到达（每步 ≤1 格 ⇒ 最大到达 L-1）", "far=" + farOnly.size());
        LocalFlowEvents.Amount at = edgeOnly.get(new LocalFlowEvents.Point(rect.x1(), z));
        ok(edgeOnly.size() == 1 && at != null && at.visits() == 1, "距离 L-1 的外源恰好贡献 1 次（halo 紧且必要）", "edge=" + edgeOnly.size());
        return farOnly.isEmpty() && edgeOnly.size() == 1 && at != null && at.visits() == 1;
    }

    // ================= [D2] G42 有界源索引 vs 独立枚举 ==========================

    static void sectionDiscoveryG42() throws Exception {
        note("");
        note("=== [D2] G42 有界源索引：任意下游先查询 == 独立枚举（硬判据） ===");
        note("  口径：源目录 = 有限已知源全集（本探针用 G42 实际 rollingSites）。");
        note("  索引 = 世界列 → 经过该列的源集合（一次构建，与查询顺序/历史无关）。");
        note("  判据：任意下游列，索引命中 == 独立枚举命中 == 逆序枚举命中；");
        note("        query-only-births（只认查询窗外出生的源）必须漏源 ⇒ 漏窗外源负控有牙齿。");
        Map<SharedBoundaryDrainage.WorldCell, Run> runs = realRuns();
        Map<Long, LinkedHashSet<SharedBoundaryDrainage.WorldCell>> index = buildSourceIndex(runs);
        note("  indexColumns=" + index.size() + " sources=" + runs.size());
        boolean hardOk = indexVersusEnumeration(runs, index);
        sourceDirectoryGap(runs);
        crossEdgeControls();
        ok(hardOk, "任意下游先查询（索引）与完整源枚举给出同一源集合", "index != enumeration");
    }

    /** 世界列 → 源集合。一次构建，不依赖查询顺序。 */
    static Map<Long, LinkedHashSet<SharedBoundaryDrainage.WorldCell>> buildSourceIndex(
            Map<SharedBoundaryDrainage.WorldCell, Run> runs) {
        Map<Long, LinkedHashSet<SharedBoundaryDrainage.WorldCell>> index = new LinkedHashMap<>();
        for (var e : runs.entrySet()) {
            for (St s : e.getValue().nodes()) {
                index.computeIfAbsent(columnKey(s), k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }
        return index;
    }

    /** 索引 vs 独立枚举（正序/逆序/冷重放），并挑多源列做漏窗外源 + first-occupied 负控。 */
    static boolean indexVersusEnumeration(Map<SharedBoundaryDrainage.WorldCell, Run> runs,
                                          Map<Long, LinkedHashSet<SharedBoundaryDrainage.WorldCell>> index) throws Exception {
        List<SharedBoundaryDrainage.WorldCell> sources = new ArrayList<>(runs.keySet());
        List<SharedBoundaryDrainage.WorldCell> reversed = new ArrayList<>(sources);
        Collections.reverse(reversed);
        StringBuilder csv = new StringBuilder("columnX,columnZ,indexCount,enumCount,reverseCount,equal,"
                + "windowOnlyCount,windowMissesSource,firstOccupiedIdentityFlips\n");
        int probed = 0, multi = 0, equalAll = 0, windowMissed = 0, windowMissedMulti = 0;
        int foProbed = 0, foFlips = 0;
        for (var e : index.entrySet()) {
            if (probed >= 400) break;
            probed++;
            long key = e.getKey();
            SharedBoundaryDrainage.WorldCell sample = e.getValue().iterator().next();
            int cx = (int) (key >> 32), cz = (int) (key & 0xffffffffL);
            St col = columnAt(runs.get(sample), cx, cz);
            if (col == null) continue;
            LinkedHashSet<SharedBoundaryDrainage.WorldCell> viaIndex = new LinkedHashSet<>(e.getValue());
            LinkedHashSet<SharedBoundaryDrainage.WorldCell> fwd = hitsAt(col, runs, sources);
            LinkedHashSet<SharedBoundaryDrainage.WorldCell> rev = hitsAt(col, runs, reversed);
            boolean eq = viaIndex.equals(fwd) && fwd.equals(rev);
            if (eq) equalAll++;
            if (viaIndex.size() >= 2) multi++;
            LinkedHashSet<SharedBoundaryDrainage.WorldCell> windowOnly = birthWindowOnly(col, runs, sources);
            boolean missed = !windowOnly.equals(fwd);
            if (missed) windowMissed++;
            if (missed && viaIndex.size() >= 2) windowMissedMulti++;
            boolean flip = false;
            if (viaIndex.size() >= 2) {
                foProbed++;
                flip = firstOccupiedFlips(col, runs, sources, reversed);
                if (flip) foFlips++;
            }
            row(csv, cx, cz, viaIndex.size(), fwd.size(), rev.size(), eq, windowOnly.size(), missed, flip);
        }
        write("discovery-index.csv", csv);
        note("  probedColumns=" + probed + " multiSourceColumns=" + multi + " indexEqualsEnum=" + equalAll
                + " windowOnlyMissedSource=" + windowMissed + " (multiSource=" + windowMissedMulti + ")");
        note("  firstOccupied: multiSourceColumnsProbed=" + foProbed + " identityFlips=" + foFlips);
        ok(probed > 0 && equalAll == probed, "所有抽查列：索引 == 正序枚举 == 逆序枚举", equalAll + "/" + probed);
        ok(multi > 0, "存在多源共用列（索引不是单源退化情形）", "multi=" + multi);
        ok(windowMissed > 0, "★ 负控：query-only-births 必然漏掉窗外出生的贡献源（漏源负控有牙齿）",
                "windowOnly 与完整集全同 ⇒ 负控空转，无法证明漏源会被发现");
        ok(foProbed > 0, "存在可做 first-occupied 负控的多源列", "foProbed=0 ⇒ 负控无法执行");
        ok(foFlips > 0,
                "★ 负控：多源列上 reverse-index 取 first 必须给出<b>不同身份</b> ⇒ first-occupied-wins 被否证",
                "flips=0 于 " + foProbed + " 个多源列 ⇒ 负控空转，无法否证先到优先");
        return probed > 0 && equalAll == probed;
    }

    /**
     * 真实多源列上的 first-occupied 身份负控：正序与逆序各自「取第一个占用者」，
     * 必须得到<b>不同</b>源身份。相等 ⇒ 该列无法区分两序 ⇒ 负控空转。
     */
    static boolean firstOccupiedFlips(St q, Map<SharedBoundaryDrainage.WorldCell, Run> runs,
                                      List<SharedBoundaryDrainage.WorldCell> fwd,
                                      List<SharedBoundaryDrainage.WorldCell> rev) {
        SharedBoundaryDrainage.WorldCell a = birthWindowOnlyOrderedFirst(q, runs, fwd);
        SharedBoundaryDrainage.WorldCell b = birthWindowOnlyOrderedFirst(q, runs, rev);
        if (a == null || b == null) {
            a = firstHit(q, runs, fwd);
            b = firstHit(q, runs, rev);
        }
        return a != null && b != null && !a.equals(b);
    }

    /** 正序/逆序下「第一个命中该列的源」= first-occupied 的归属。 */
    static SharedBoundaryDrainage.WorldCell firstHit(St q, Map<SharedBoundaryDrainage.WorldCell, Run> runs,
                                                     List<SharedBoundaryDrainage.WorldCell> order) {
        for (SharedBoundaryDrainage.WorldCell s : order) if (passes(runs.get(s), q)) return s;
        return null;
    }

    /** 同上，但只在查询窗内出生的源里取 first（更接近「先到优先」的实际误用形态）。 */
    static SharedBoundaryDrainage.WorldCell birthWindowOnlyOrderedFirst(
            St q, Map<SharedBoundaryDrainage.WorldCell, Run> runs, List<SharedBoundaryDrainage.WorldCell> order) {
        double qx = q.x(), qz = q.z();
        for (SharedBoundaryDrainage.WorldCell s : order) {
            if (Math.max(Math.abs(s.x() - qx), Math.abs(s.z() - qz)) > BIRTH_WINDOW) continue;
            if (passes(runs.get(s), q)) return s;
        }
        return null;
    }

    static St columnAt(Run run, int cx, int cz) {
        for (St s : run.nodes()) if ((int) Math.floor(s.x()) == cx && (int) Math.floor(s.z()) == cz) return s;
        return null;
    }

    /** 只在「精确共用同一整数列」时命中，避免把邻近轨迹误当同一河段。 */
    static boolean passes(Run run, St q) {
        return columnAt(run, (int) Math.floor(q.x()), (int) Math.floor(q.z())) != null;
    }

    static LinkedHashSet<SharedBoundaryDrainage.WorldCell> hitsAt(St q, Map<SharedBoundaryDrainage.WorldCell, Run> runs,
                                                                 List<SharedBoundaryDrainage.WorldCell> order) {
        LinkedHashSet<SharedBoundaryDrainage.WorldCell> out = new LinkedHashSet<>();
        for (SharedBoundaryDrainage.WorldCell s : order) if (passes(runs.get(s), q)) out.add(s);
        return out;
    }

    /** 故意「只看查询窗内出生的源」——用于证明漏掉窗外源会漏发现。 */
    static LinkedHashSet<SharedBoundaryDrainage.WorldCell> birthWindowOnly(
            St q, Map<SharedBoundaryDrainage.WorldCell, Run> runs, List<SharedBoundaryDrainage.WorldCell> order) {
        LinkedHashSet<SharedBoundaryDrainage.WorldCell> out = new LinkedHashSet<>();
        double qx = q.x(), qz = q.z();
        for (SharedBoundaryDrainage.WorldCell s : order) {
            if (Math.max(Math.abs(s.x() - qx), Math.abs(s.z() - qz)) > BIRTH_WINDOW) continue;
            if (passes(runs.get(s), q)) out.add(s);
        }
        return out;
    }

    /**
     * 尚缺的能力：G42 任意世界坐标的<b>完整源目录</b>（生产级源枚举规则）。
     * 本探针只有 5 个硬编码起点 ⇒ 这是开放缺口，不是硬判据红。
     */
    static void sourceDirectoryGap(Map<SharedBoundaryDrainage.WorldCell, Run> runs) {
        note("  -- 源目录完整性（开放缺口，独立于候选 verdict） --");
        note("  已知源目录 = " + runs.keySet());
        note("  ⇒ 「任意世界坐标的完整源目录」需要生产级源枚举规则（源准入/间距/seed 派生），");
        note("    本轮 G42 侧只有有限硬编码起点，尚无该目录 ⇒ 记为 OPEN GAP，不宣称无限发现已成立。");
        gap("discovery.g42-arbitrary-world-source-directory",
                "只有有限已知源全集；缺少生产级任意世界坐标源枚举，故『任意下游 chunk 完整发现』仍不成立");
    }

    /** 负坐标角点 + 多次跨边。 */
    static void crossEdgeControls() throws Exception {
        Run diag = g42.trace(new SharedBoundaryDrainage.WorldCell(-40, -40), (x, z) -> 1000.0 - x - z,
                24, 8, 0, 0, true, -1e9, 200, false);
        List<X> xs = crossings(diag.nodes(), 32);
        long ax0 = xs.stream().filter(c -> c.axis() == 0).count();
        long ax1 = xs.stream().filter(c -> c.axis() == 1).count();
        ok(ax0 >= 2 && ax1 >= 2 && xs.size() >= 4, "负坐标斜穿：两轴各 ≥2 次边界穿越（多次跨边）", "axis0=" + ax0 + " axis1=" + ax1);
        boolean corner = false;
        for (X a : xs) for (X b : xs) {
            if (a.axis() == 0 && b.axis() == 1 && a.boundary() == b.boundary() && a.x() == b.x() && a.z() == b.z()) corner = true;
        }
        ok(corner, "对角角点两轴登记到同一插值点（负坐标角点不误判）", "no shared corner point");
        StringBuilder csv = new StringBuilder("axis,boundary,x,z\n");
        for (X c : xs) row(csv, c.axis(), c.boundary(), c.x(), c.z());
        write("crossings.csv", csv);
    }

    static List<X> crossings(List<St> nodes, int size) {
        List<X> out = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            St a = nodes.get(i - 1), b = nodes.get(i);
            for (int axis = 0; axis < 2; axis++) {
                double av = axis == 0 ? a.x() : a.z(), bv = axis == 0 ? b.x() : b.z();
                int at = (int) Math.floor(av / size), bt = (int) Math.floor(bv / size);
                if (at == bt) continue;
                if (Math.abs(at - bt) != 1) throw new IllegalArgumentException("nonlocal segment axis=" + axis);
                int boundary = Math.max(at, bt) * size;
                double f = (boundary - av) / (bv - av);
                out.add(new X(axis, boundary, a.x() + f * (b.x() - a.x()), a.z() + f * (b.z() - a.z())));
            }
        }
        return out;
    }
}
