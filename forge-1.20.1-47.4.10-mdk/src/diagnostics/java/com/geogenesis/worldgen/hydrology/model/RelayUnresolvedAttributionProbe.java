package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨区接力 UNRESOLVED 的<b>只读归因</b>：在指定锚点上把"为什么证不出下游"摊开。
 *
 * <p><b>要回答的问题</b>（2026-11-06 实机日志的两条）：
 * <pre>
 *   reject[BASE_JOIN_WITHOUT_DOWNSTREAM cell=68931 anchor=(294,-928)]  +  UNRESOLVED:UNRESOLVED steps=96
 *   reject[BASE_JOIN_WITHOUT_DOWNSTREAM cell=34690 anchor=(-1436,-416)] +  UNRESOLVED:CYCLE      steps=96
 * </pre>
 * 这两处会让水系在跨区处断裂（{@code End.UNRESOLVED} 的注释原话是"不假装有水"）。</p>
 *
 * <h2>读代码得到的三个候选机制（本探针负责区分它们）</h2>
 * <ol>
 *   <li><b>证据链被 96 格截断</b>：{@code EVIDENCE_CHAIN_MAX = 96} 只截断<b>记录</b>，
 *       {@code followChain} 实际可走 4096 步。所以 {@code steps=96} 只说明"走了 ≥96 格"，
 *       不说明卡在第 96 格。真伪要看链尾是否已到水面。</li>
 *   <li><b>封闭洼地</b>：链尾格在 8 邻域里<b>没有严格更低</b>的邻居 ⇒ D8 无下降方向。
 *       若 base 没把它判成 {@code MOUTH_LAKE}，后继就只能是"另一条链"或自指。</li>
 *   <li><b>平底互指成环</b>：两格等高、互相当对方后继 ⇒ {@code followChain} 的
 *       {@code seen} 集合命中 ⇒ 返回 {@code CYCLE}。这是 {@code CYCLE} 唯一来源。</li>
 * </ol>
 *
 * <h2>判据（只描述，不改）</h2>
 * <p>对链上每一格算三件事：8 邻域里严格更低的邻居数、等高邻居数、以及链内重复。
 * 结论落在 {@code VERDICT=...}：{@code SEALED_BASIN} / {@code FLAT_TIE_CYCLE} /
 * {@code CHAIN_ESCAPED}（链尾其实到了水，说明问题在别处）等。</p>
 *
 * <p><b>只读</b>：不构造世界、不落盘任何生产资产；config 只读复制到
 * {@code build/relay-attribution/} 再 parse。任一锚点取不到证据 ⇒ 非零退出。</p>
 *
 * <pre>{@code gradlew runRelayUnresolvedAttributionProbe [-PprobeArgs="294,-928 -1436,-416"]}</pre>
 */
public final class RelayUnresolvedAttributionProbe {

    private static final long DEF_SEED = 9139912035078620160L;
    private static final int[][] DEF_ANCHORS = {{294, -928}, {-1436, -416}};
    private static final double EPS = 1e-9;

    private RelayUnresolvedAttributionProbe() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of("build", "relay-attribution");
        Files.createDirectories(out);
        // Windows 控制台是 GBK，中文会变乱码；报告同时写一份 UTF-8 文件（覆盖写）。
        teeTo(out.resolve("report.txt"));

        List<int[]> anchors = parseAnchors(args);
        TerrainParams tp = loadConfig(out);
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(DEF_SEED);
        ModelHydrologyProvider prov = new ModelHydrologyProvider(gen, DEF_SEED);

        int region = staticInt(ModelHydrologyProvider.class, "REGION");
        int step = staticInt(ModelHydrologyProvider.class, "STEP");
        System.out.printf("=== RelayUnresolvedAttributionProbe seed=%d REGION=%d STEP=%d ===%n",
                DEF_SEED, region, step);

        if (hasFlag(args, "--chains-log")) {
            boolean ok = replayLogChains(prov, gen, region, step, hasFlag(args, "--zero-climb"));
            gen.closeProcessErosion();
            System.exit(ok ? 0 : 1);
        }
        if (hasFlag(args, "--order-matrix")) {
            boolean ok = orderMatrix(gen, region, anchors);
            gen.closeProcessErosion();
            System.exit(ok ? 0 : 1);
        }

        boolean found = true;
        for (int[] a : anchors) {
            found &= attribute(prov, gen, region, step, a[0], a[1]);
        }
        System.out.println();
        System.out.printf("总判定: %s%n", found ? "COLLECTED（证据已采集，结论见各锚点 VERDICT）"
                : "MISSING（有锚点取不到证据）");
        gen.closeProcessErosion();
        System.exit(found ? 0 : 1);
    }

    /** 把 stdout 同时写到 UTF-8 报告文件（控制台编码不可控，报告必须可读）。 */
    private static void teeTo(Path file) {
        try {
            java.io.PrintStream console = System.out;
            java.io.PrintStream report = new java.io.PrintStream(
                    new java.io.FileOutputStream(file.toFile(), false), true, "UTF-8");
            System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
                @Override
                public void write(int b) throws java.io.IOException {
                    report.write(b);
                    console.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws java.io.IOException {
                    report.write(b, off, len);
                    console.write(b, off, len);
                }

                @Override
                public void flush() throws java.io.IOException {
                    report.flush();
                    console.flush();
                }
            }, true, "UTF-8"));
        } catch (java.io.IOException e) {
            System.out.println("报告文件写入不可用：" + e);
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用<b>实机日志里的原始链</b>回放归因：不重算骨架，只沿生产当时走过的 cell 序列
     * 采样高度，回答"这条链为什么到不了水"。
     *
     * <p>数据来源 {@code run/logs/debug-*.log[.gz]} 里的
     * {@code [HYDRO-WIRE-REJECT] reject[BASE_JOIN_WITHOUT_DOWNSTREAM cell=.. anchor=(x,z)]}
     * 与紧随其后的 {@code [HYDRO-WIRE-MERGE] anchor=.. .. steps=.. path=a>b>c}。</p>
     */
    private static boolean replayLogChains(ModelHydrologyProvider prov, CellGenerator gen,
                                          int region, int step, boolean zeroClimb) throws Exception {
        Path log = Path.of("run", "logs", "debug-1.log.gz");
        if (!Files.isRegularFile(log)) {
            System.out.println("找不到日志：" + log.toAbsolutePath());
            return false;
        }
        List<String> lines = new ArrayList<>();
        try (var in = Files.newInputStream(log);
             var gz = new java.util.zip.GZIPInputStream(in);
             var rd = new java.io.BufferedReader(new java.io.InputStreamReader(gz,
                     java.nio.charset.StandardCharsets.UTF_8))) {
            String l;
            while ((l = rd.readLine()) != null) {
                lines.add(l);
            }
        }

        Pattern reject = Pattern.compile(
                "reject\\[BASE_JOIN_WITHOUT_DOWNSTREAM cell=(\\d+) anchor=\\((-?\\d+),(-?\\d+)\\)\\]");
        Pattern merge = Pattern.compile(
                "\\[HYDRO-WIRE-MERGE\\] anchor=(\\d+) next=(-?\\d+) (\\S+) steps=(\\d+) path=([0-9>]+)");

        Map<Integer, String[]> mergeByAnchor = new LinkedHashMap<>();
        for (String l : lines) {
            Matcher m = merge.matcher(l);
            if (m.find()) {
                mergeByAnchor.put(Integer.parseInt(m.group(1)),
                        new String[]{m.group(3), m.group(4), m.group(5)});
            }
        }

        boolean any = false;
        boolean ok = true;
        for (String l : lines) {
            Matcher m = reject.matcher(l);
            if (!m.find()) {
                continue;
            }
            any = true;
            int cell = Integer.parseInt(m.group(1));
            int ax = Integer.parseInt(m.group(2));
            int az = Integer.parseInt(m.group(3));
            int rx = Math.floorDiv(ax, region);
            int rz = Math.floorDiv(az, region);
            int[] frame = prov.windowFrameForProbe(ax, az);
            int ox = frame[0], oz = frame[1], fstep = frame[2], n = frame[3];
            System.out.println();
            System.out.printf("=== LOG CHAIN anchor=(%d,%d) cell=%d region=(%d,%d) window=(%d,%d,%d,%d) ===%n",
                    ax, az, cell, rx, rz, ox, oz, fstep, n);
            String[] ev = mergeByAnchor.get(cell);
            if (ev == null) {
                System.out.println("  日志里没有该 anchor 的 merge 证据行");
                ok = false;
                continue;
            }
            String[] parts = ev[2].split(">");
            List<Integer> cells = new ArrayList<>(parts.length);
            for (String p : parts) {
                cells.add(Integer.parseInt(p));
            }
            if (zeroClimb) {
                DeterministicSkeleton.Result sk = prov.finalSkeletonForProbe(rx, rz);
                zeroClimbWalk(Integer.parseInt(parts[0]), cells, ox, oz, fstep, n, gen, sk);
            } else {
                ok &= dumpPath(ev[0], Integer.parseInt(ev[1]), cells, ox, oz, fstep, n, gen);
            }
        }
        if (!any) {
            System.out.println("日志里没有 BASE_JOIN_WITHOUT_DOWNSTREAM 记录");
            return false;
        }
        return ok;
    }

    /** 沿给定 cell 路径采样：高度、是否还有更低邻居、路径内是否重复（成环）。 */
    private static boolean dumpPath(String resolution, int steps, List<Integer> cells,
                                    int ox, int oz, int step, int n, CellGenerator gen) {
        System.out.printf("  resolution=%s steps=%d 记录格数=%d%n", resolution, steps, cells.size());
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        int cycleAt = -1;
        long cycleLen = 0;
        for (int i = 0; i < cells.size(); i++) {
            Integer prev = seen.put(cells.get(i), i);
            if (prev != null) {
                cycleAt = prev;
                cycleLen = i - prev;
                break;
            }
        }
        System.out.println("  路径内重复：" + (cycleAt < 0
                ? "无（环在 96 格之外，或根本是显式 UNRESOLVED 终点）"
                : "第 " + cycleAt + " 格重复，环长=" + cycleLen));

        int noLower = 0;
        int tie = 0;
        double hMin = Double.POSITIVE_INFINITY;
        double hMax = Double.NEGATIVE_INFINITY;
        int shown = 0;
        for (int i = 0; i < cells.size(); i++) {
            int c = cells.get(i);
            int wx = ox + (c / n) * step;
            int wz = oz + (c % n) * step;
            double h = gen.erodedHeight(wx, wz);
            hMin = Math.min(hMin, h);
            hMax = Math.max(hMax, h);
            int lower = 0, equal = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    double hn = gen.erodedHeight(wx + dx * step, wz + dz * step);
                    if (hn < h - EPS) {
                        lower++;
                    } else if (Math.abs(hn - h) <= EPS) {
                        equal++;
                    }
                }
            }
            if (lower == 0) {
                if (equal > 0) {
                    tie++;
                } else {
                    noLower++;
                }
                if (shown++ < 8) {
                    System.out.printf("    无更低邻居: [%d] cell=%d world=(%d,%d) h=%.6f equal=%d%n",
                            i, c, wx, wz, h, equal);
                }
            }
        }
        System.out.printf("  高度范围 [%.6f, %.6f]，跨度 %.6f 格%n", hMin, hMax, hMax - hMin);
        System.out.printf("  无严格更低邻居的格：%d（其中纯等高 %d）/ %d%n",
                noLower + tie, tie, cells.size());

        // ★ 关键：后继是否真的"严格下降"。路由跑在 B 的 e 场上（terrainEQuick），
        //   而不是最终 erodedHeight；链能上坡/成环的根因只能在这里看出来。
        int up = 0;
        int flat = 0;
        double worstUp = 0;
        double eMin = Double.POSITIVE_INFINITY;
        double eMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < cells.size(); i++) {
            double e = gen.terrainEQuick(worldX(cells.get(i), ox, n, step),
                    worldZ(cells.get(i), oz, n, step));
            eMin = Math.min(eMin, e);
            eMax = Math.max(eMax, e);
            if (i + 1 >= cells.size()) {
                continue;
            }
            double eNext = gen.terrainEQuick(worldX(cells.get(i + 1), ox, n, step),
                    worldZ(cells.get(i + 1), oz, n, step));
            double d = eNext - e;
            if (d > EPS) {
                up++;
                worstUp = Math.max(worstUp, d);
            } else if (Math.abs(d) <= EPS) {
                flat++;
            }
        }
        System.out.printf("  B 场 e 范围 [%.6f, %.6f]%n", eMin, eMax);
        System.out.printf("  后继步进：上坡 %d 步 / 平 %d 步 / 共 %d 步（最大上坡 %+.6f e）%n",
                up, flat, Math.max(0, cells.size() - 1), worstUp);
        System.out.println("  VERDICT=" + (noLower + tie > 0
                ? "含 D8 无下降格（封闭洼地/平底平台）"
                : (up > 0 ? "后继非严格下降：链上出现上坡步 ⇒ 成环/上溯在结构上可能"
                        : "链上每步都下降且每格都有更低邻居（问题不在局部梯度）")));
        return true;
    }

    private static int worldX(int cell, int ox, int n, int step) {
        return ox + (cell / n) * step;
    }

    /**
     * <b>零爬坡重走</b>：从日志里那条链的首步出发，只走床面/高度严格更低的 8 邻，
     * 看能不能走到水体。用来判定审计的 {@code UNRESOLVED} 是误拒还是真缺陷。
     *
     * <p>判据来源全部是生产数据：终点是否落在 {@code End.MOUTH_LAKE}/{@code MOUTH_SEA}
     * 的 trace 格上、{@code e <= 0}（海）、或走出窗口（需邻区续解）；都没有更低邻居
     * 才叫"真洼地"。严格下降不可能成环，所以本走法天然不会 CYCLE —— 这正好把
     * "环"与"地形上无出口"两件事分开。</p>
     */
    private static void zeroClimbWalk(int start, List<Integer> auditCells, int ox, int oz,
                                      int step, int n, CellGenerator gen,
                                      DeterministicSkeleton.Result sk) {
        java.util.Set<Integer> lake = new java.util.HashSet<>();
        java.util.Set<Integer> sea = new java.util.HashSet<>();
        if (sk != null) {
            for (DeterministicSkeleton.Trace t : sk.traces) {
                java.util.Set<Integer> bucket = null;
                if (t.end == DeterministicSkeleton.End.MOUTH_LAKE) {
                    bucket = lake;
                } else if (t.end == DeterministicSkeleton.End.MOUTH_SEA) {
                    bucket = sea;
                }
                if (bucket != null) {
                    for (int c : t.cell) {
                        bucket.add(c);
                    }
                }
            }
        }
        System.out.printf("  零爬坡重走：起点 cell=%d world=(%d,%d) Y=%.6f eB=%.6f"
                        + "（生产 lake 格=%d sea 格=%d）%n",
                start, worldX(start, ox, n, step), worldZ(start, oz, n, step),
                gen.erodedHeight(worldX(start, ox, n, step), worldZ(start, oz, n, step)),
                gen.terrainEQuick(worldX(start, ox, n, step), worldZ(start, oz, n, step)),
                lake.size(), sea.size());

        java.util.Set<Integer> visited = new java.util.LinkedHashSet<>();
        int cur = start;
        int steps = 0;
        String kind = null;
        while (steps < 4096) {
            if (!visited.add(cur)) {
                kind = "CYCLE_IMPOSSIBLE（严格下降却成环 ⇒ 床面读取有误）";
                break;
            }
            int wx = worldX(cur, ox, n, step);
            int wz = worldZ(cur, oz, n, step);
            double e = gen.terrainEQuick(wx, wz);
            if (e <= 0.0) {
                kind = "SEA（e<=0）";
                break;
            }
            if (sea.contains(cur)) {
                kind = "SEA（终点在 MOUTH_SEA trace 上）";
                break;
            }
            if (lake.contains(cur)) {
                kind = "WATER_LAKE（终点在 MOUTH_LAKE trace 上）";
                break;
            }
            int next = steepestLower(cur, ox, oz, step, n, gen);
            if (next < 0) {
                kind = "PIT（无更低 8 邻 ⇒ 按设计即蓄水成湖）";
                break;
            }
            cur = next;
            steps++;
        }
        if (kind == null) {
            kind = "STEP_LIMIT";
        }
        int inAudit = 0;
        for (int c : visited) {
            if (auditCells.contains(c)) {
                inAudit++;
            }
        }
        System.out.printf("  → 走了 %d 步，终点 cell=%d world=(%d,%d) Y=%.6f eB=%.6f%n",
                steps, cur, worldX(cur, ox, n, step), worldZ(cur, oz, n, step),
                gen.erodedHeight(worldX(cur, ox, n, step), worldZ(cur, oz, n, step)),
                gen.terrainEQuick(worldX(cur, ox, n, step), worldZ(cur, oz, n, step)));
        System.out.printf("  → 与审计链重合格数=%d / 审计链 %d 格%n", inAudit, auditCells.size());
        System.out.println("  ZERO_CLIMB_VERDICT=" + kind
                + (kind.startsWith("PIT") || kind.startsWith("SEA") || kind.startsWith("WATER_LAKE")
                ? " ⇒ 地形上能到达水体/蓄水洼地 ⇒ 审计的 UNRESOLVED 属误拒（后继关系成环，不是没有出口）"
                : " ⇒ 不能据此判误拒"));
    }

    /** 8 邻里最陡下降的一格（严格更低）；平局按固定扫描序，保证确定性。 */
    private static int steepestLower(int cell, int ox, int oz, int step, int n,
                                     CellGenerator gen) {
        int ci = cell / n, cj = cell % n;
        double h = gen.erodedHeight(worldX(cell, ox, n, step), worldZ(cell, oz, n, step));
        int best = -1;
        double bestH = h;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) {
                    continue;
                }
                int a = ci + di, b = cj + dj;
                if (a < 0 || b < 0 || a >= n || b >= n) {
                    continue;
                }
                double hn = gen.erodedHeight(worldX(a * n + b, ox, n, step),
                        worldZ(a * n + b, oz, n, step));
                if (hn < bestH - EPS) {
                    bestH = hn;
                    best = a * n + b;
                }
            }
        }
        return best;
    }

    private static int worldZ(int cell, int oz, int n, int step) {
        return oz + (cell % n) * step;
    }

    /**
     * 顺序矩阵：同一块 3×3 区域，按 forward / reverse / random 三种顺序各解一遍，
     * 比较每区裁定是否一致。既量化"顺序是否影响裁定"，也给退化区一次非退化复现的机会。
     *
     * <p>每次用<b>全新 provider</b>（缓存必须空），共享同一个 {@link CellGenerator}
     * （地形与侵蚀是纯函数，不影响顺序维度）。</p>
     */
    private static boolean orderMatrix(CellGenerator gen, int region, List<int[]> anchors) {
        boolean allSame = true;
        for (int[] a : anchors) {
            int rx = Math.floorDiv(a[0], region);
            int rz = Math.floorDiv(a[1], region);
            List<int[]> block = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    block.add(new int[]{rx + dx, rz + dz});
                }
            }
            List<List<int[]>> orders = new ArrayList<>();
            orders.add(new ArrayList<>(block));                                  // forward
            List<int[]> rev = new ArrayList<>(block);
            java.util.Collections.reverse(rev);
            orders.add(rev);                                                     // reverse
            List<int[]> rnd = new ArrayList<>(block);
            java.util.Collections.shuffle(rnd, new java.util.Random(20261106L)); // random（固定种子）
            orders.add(rnd);
            String[] names = {"forward", "reverse", "random"};

            System.out.println();
            System.out.printf("=== ORDER MATRIX anchor=(%d,%d) region=(%d,%d) 3x3=%d 区 ===%n",
                    a[0], a[1], rx, rz, block.size());
            Map<String, List<String>> sigByOrder = new LinkedHashMap<>();
            Map<String, List<String>> statByOrder = new LinkedHashMap<>();
            for (int oi = 0; oi < orders.size(); oi++) {
                ModelHydrologyProvider p = newProvider(gen);
                Map<String, String> sig = new LinkedHashMap<>();
                Map<String, String> stat = new LinkedHashMap<>();
                for (int[] rc : orders.get(oi)) {
                    DeterministicSkeleton.Result sk = p.finalSkeletonForProbe(rc[0], rc[1]);
                    String key = rc[0] + ":" + rc[1];
                    sig.put(key, signature(sk));
                    stat.put(key, sk == null ? "<null>"
                            : "role=" + sk.passRole + " traces=" + sk.traces.size()
                              + " ports=" + sk.ports.size()
                              + " wiredIn=" + sk.wiredIncoming + " rejects=" + sk.wireRejects.size()
                              + " merge=" + sk.mergeEvidence.size());
                }
                sigByOrder.put(names[oi], new ArrayList<>(sig.values()));
                statByOrder.put(names[oi], new ArrayList<>(stat.values()));
                System.out.printf("  %-8s 解完，区块顺序=%s%n", names[oi], orderText(orders.get(oi)));
            }

            int diff = 0;
            for (int i = 0; i < block.size(); i++) {
                String k = block.get(i)[0] + ":" + block.get(i)[1];
                List<String> row = new ArrayList<>();
                for (String n : names) {
                    row.add(sigByOrder.get(n).get(i));
                }
                boolean same = row.get(0).equals(row.get(1)) && row.get(0).equals(row.get(2));
                if (!same) {
                    diff++;
                }
                System.out.printf("  region %-9s %-9s %s%n", k,
                        same ? "ORDER_SAME" : "ORDER_DIFF", row.get(0));
                if (!same) {
                    for (int n = 0; n < names.length; n++) {
                        System.out.printf("      %-8s %s%n", names[n], row.get(n));
                    }
                }
                System.out.printf("      stats: forward[%s] reverse[%s] random[%s]%n",
                        statByOrder.get("forward").get(i),
                        statByOrder.get("reverse").get(i),
                        statByOrder.get("random").get(i));
            }
            System.out.printf("  小结：%d/%d 个区的裁定随顺序变化%n", diff, block.size());
            allSame &= diff == 0;
        }
        System.out.println();
        System.out.printf("总判定: %s%n", allSame
                ? "ALL_ORDER_SAME（三种顺序下每区裁定一致）"
                : "ORDER_DEPENDENT（存在随顺序变化的区，见上）");
        return true;
    }

    private static String orderText(List<int[]> order) {
        StringBuilder sb = new StringBuilder();
        for (int[] rc : order) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(rc[0]).append(':').append(rc[1]);
        }
        return sb.toString();
    }

    private static boolean attribute(ModelHydrologyProvider prov, CellGenerator gen,
                                     int region, int step, int ax, int az) throws Exception {
        int rx = Math.floorDiv(ax, region);
        int rz = Math.floorDiv(az, region);
        System.out.println();
        System.out.printf("=== ANCHOR (%d,%d) region=(%d,%d) ===%n", ax, az, rx, rz);

        DeterministicSkeleton.Result sk = prov.finalSkeletonForProbe(rx, rz);
        if (sk == null) {
            System.out.println("  finalSkeletonForProbe 返回 null（该区未求解） ⇒ VERDICT=NO_REGION");
            return false;
        }
        int[] frame = prov.windowFrameForProbe(ax, az);
        int ox = frame[0], oz = frame[1], fstep = frame[2], n = frame[3];
        int cx = (int) Math.round((ax - ox) / (double) fstep);
        int cz = (int) Math.round((az - oz) / (double) fstep);
        int anchorCell = cx * n + cz;
        System.out.printf("  window=(ox=%d,oz=%d,step=%d,n=%d) anchorCell=%d%n", ox, oz, fstep, n, anchorCell);
        System.out.printf("  result: passRole=%s ports=%d pendingExits=%d wiredIncoming=%d"
                        + " mergedIncoming=%d wireRejects=%d mergeEvidence=%d%n",
                sk.passRole, sk.ports.size(), sk.pendingExits.size(), sk.wiredIncoming,
                sk.mergedIncoming, sk.wireRejects.size(), sk.mergeEvidence.size());

        for (WireReject r : sk.wireRejects) {
            System.out.println("  reject: " + r);
        }
        for (String s : prov.wireAssemblyRejectsForProbe(rx, rz)) {
            System.out.println("  assemblyReject: " + s);
        }

        // 本次运行里该区的全部端口裁定（不只是目标锚点）——用来判断锚点是否根本没进本轮 pass2。
        System.out.printf("  全部 mergeEvidence（%d 条）：%n", sk.mergeEvidence.size());
        for (DeterministicSkeleton.MergeEvidence ev : sk.mergeEvidence) {
            System.out.printf("    anchor=%d next=%d %s steps=%d%n",
                    ev.anchor(), ev.next(), ev.resolution(), ev.steps());
        }

        List<DeterministicSkeleton.MergeEvidence> hit = new ArrayList<>();
        for (DeterministicSkeleton.MergeEvidence ev : sk.mergeEvidence) {
            if (ev.anchor() == anchorCell) {
                hit.add(ev);
            }
        }
        if (hit.isEmpty()) {
            System.out.println("  未找到 anchorCell=" + anchorCell
                    + " 的 mergeEvidence ⇒ 该锚点在本轮求解里没有进入 pass2 的 incoming 集合");
            System.out.println("  VERDICT=NOT_PRESENT_IN_THIS_ORDER（与实机日志不同 ⇒ 先看下面的顺序对照）");
        } else {
            for (DeterministicSkeleton.MergeEvidence ev : hit) {
                dumpEvidence(ev, gen, ox, oz, fstep, n);
            }
        }

        compareOrders(gen, region, rx, rz);
        return true;
    }

    /**
     * 顺序对照：同一个区，<b>冷启动直接求</b> vs <b>先把 8 个邻居按逆序求完再求</b>，
     * 两次的 reject/merge 集合是否一致。
     *
     * <p>跨区接力是 pass1/pass2 + typed wire，天然可能依赖"邻居是否已解"。M3 矩阵里
     * {@code reverse/random/concurrent/reopen} 四臂<b>从未在新地形上跑过</b>，
     * 所以"顺序是否影响裁定"目前没有证据。这里做最小对照。</p>
     */
    private static void compareOrders(CellGenerator gen, int region, int rx, int rz) {
        String cold = signature(newProvider(gen).finalSkeletonForProbe(rx, rz));
        ModelHydrologyProvider warm = newProvider(gen);
        for (int dx = 1; dx >= -1; dx--) {
            for (int dz = 1; dz >= -1; dz--) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                warm.finalSkeletonForProbe(rx + dx, rz + dz);
            }
        }
        String warmSig = signature(warm.finalSkeletonForProbe(rx, rz));
        boolean same = cold.equals(warmSig);
        System.out.println("  顺序对照：cold=" + cold);
        System.out.println("             warm=" + warmSig);
        System.out.println("  顺序对照结论：" + (same ? "ORDER_SAME（裁定与邻居求解顺序无关）"
                : "ORDER_DEPENDENT（同一区在不同邻居已解状态下给出不同裁定）"));
    }

    private static ModelHydrologyProvider newProvider(CellGenerator gen) {
        return new ModelHydrologyProvider(gen, DEF_SEED);
    }

    /** 裁定签名：排序后的 reject/merge 列表，用于跨顺序比较。 */
    private static String signature(DeterministicSkeleton.Result sk) {
        if (sk == null) {
            return "<null>";
        }
        List<String> parts = new ArrayList<>();
        for (WireReject r : sk.wireRejects) {
            parts.add("reject:" + r.status() + "@" + r.anchorCell());
        }
        for (DeterministicSkeleton.MergeEvidence ev : sk.mergeEvidence) {
            parts.add("merge:" + ev.resolution() + "@" + ev.anchor());
        }
        java.util.Collections.sort(parts);
        return parts.isEmpty() ? "<empty>" : String.join(" ", parts);
    }

    private static boolean dumpEvidence(DeterministicSkeleton.MergeEvidence ev, CellGenerator gen,
                                        int ox, int oz, int step, int n) {
        System.out.printf("  mergeEvidence: resolution=%s steps=%d next=%d resolved=%b%n",
                ev.resolution(), ev.steps(), ev.next(), ev.resolved());
        int[] chain = ev.chain();
        int used = Math.min(ev.steps(), chain.length);

        // 链头/链尾（世界坐标 + 高度）
        printCell("  head", chain, 0, used, ox, oz, step, n, gen);
        printCell("  tail", chain, used - 1, used, ox, oz, step, n, gen);

        // 链内重复 = 环
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        int cycleStart = -1;
        int cycleLen = 0;
        for (int i = 0; i < used; i++) {
            Integer prev = seen.put(chain[i], i);
            if (prev != null) {
                cycleStart = prev;
                cycleLen = i - prev;
                break;
            }
        }
        if (cycleStart >= 0) {
            System.out.printf("  chain 内成环：首次重复于第 %d 格，环长=%d%n", cycleStart, cycleLen);
            for (int i = cycleStart; i < Math.min(cycleStart + cycleLen, used); i++) {
                printCell("    cycle", chain, i, used, ox, oz, step, n, gen);
            }
        }

        // 逐格邻域诊断：严格更低 / 等高 / 更高
        int zeroLower = 0;
        int tieOnly = 0;
        List<String> sealed = new ArrayList<>();
        for (int i = 0; i < used; i++) {
            int cell = chain[i];
            int wx = ox + (cell / n) * step;
            int wz = oz + (cell % n) * step;
            double h = gen.erodedHeight(wx, wz);
            int lower = 0, equal = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    double hn = gen.erodedHeight(wx + dx * step, wz + dz * step);
                    if (hn < h - EPS) {
                        lower++;
                    } else if (Math.abs(hn - h) <= EPS) {
                        equal++;
                    }
                }
            }
            if (lower == 0) {
                if (equal > 0) {
                    tieOnly++;
                } else {
                    zeroLower++;
                }
                if (sealed.size() < 6) {
                    sealed.add(String.format("(%d,%d) h=%.6f lower=0 equal=%d", wx, wz, h, equal));
                }
            }
        }

        double hStart = height(gen, chain[0], ox, oz, step, n);
        double hEnd = height(gen, chain[used - 1], ox, oz, step, n);
        System.out.printf("  heights: start=%.6f end=%.6f Δ=%+.6f%n", hStart, hEnd, hEnd - hStart);
        System.out.printf("  邻域：严格更低邻居=0 的格 %d / %d（其中纯等高 %d）%n",
                zeroLower + tieOnly, used, tieOnly);
        for (String s : sealed) {
            System.out.println("    无下降方向: " + s);
        }

        String verdict;
        if (ev.resolved()) {
            verdict = "RESOLVED_BUT_NOT_CONSUMED（审计说接上了，问题在端口消费侧）";
        } else if (ev.resolution().endsWith("CYCLE")) {
            verdict = cycleStart >= 0
                    ? (tieOnly > 0 ? "FLAT_TIE_CYCLE（平底互指成环，见 cycle 行）" : "CYCLE_UNEXPLAINED")
                    : "CYCLE_BEYOND_96（环长 >96，证据链记录不足，需扩链才能定位）";
        } else if (ev.resolution().endsWith("UNRESOLVED")) {
            verdict = zeroLower > 0
                    ? "SEALED_BASIN（链尾/中途有 0 下降方向的封闭格，base 未判 MOUTH_LAKE）"
                    : (tieOnly > 0 ? "FLAT_TIE_PLATEAU（存在等高无下降格）" : "UNRESOLVED_UNEXPLAINED");
        } else {
            verdict = "OTHER:" + ev.resolution();
        }
        if (used == 96) {
            verdict += "  [EVIDENCE_CAPPED_96：记录被 96 格截断，真实链更长]";
        }
        System.out.println("  VERDICT=" + verdict);
        return true;
    }

    private static void printCell(String tag, int[] chain, int i, int used,
                                  int ox, int oz, int step, int n, CellGenerator gen) {
        if (i < 0 || i >= used) {
            return;
        }
        int cell = chain[i];
        int wx = ox + (cell / n) * step;
        int wz = oz + (cell % n) * step;
        System.out.printf("%s[%d] cell=%d world=(%d,%d) h=%.6f%n",
                tag, i, cell, wx, wz, gen.erodedHeight(wx, wz));
    }

    private static double height(CellGenerator gen, int cell, int ox, int oz, int step, int n) {
        return gen.erodedHeight(ox + (cell / n) * step, oz + (cell % n) * step);
    }

    private static List<int[]> parseAnchors(String[] args) {
        List<int[]> out = new ArrayList<>();
        for (String s : args) {
            String[] p = s.split(",");
            if (p.length == 2) {
                out.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())});
            }
        }
        if (out.isEmpty()) {
            for (int[] a : DEF_ANCHORS) {
                out.add(a);
            }
        }
        return out;
    }

    private static TerrainParams loadConfig(Path out) {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.isRegularFile(src)) {
            throw new IllegalStateException("缺少生产配置：" + src.toAbsolutePath());
        }
        try {
            Path work = out.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            return GeoGenesisConfig.INSTANCE.buildParams();
        } catch (Exception e) {
            throw new IllegalStateException("生产配置加载失败", e);
        }
    }

    private static int staticInt(Class<?> type, String field) {
        try {
            var f = type.getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 " + type.getSimpleName() + "." + field + " 失败", e);
        }
    }
}