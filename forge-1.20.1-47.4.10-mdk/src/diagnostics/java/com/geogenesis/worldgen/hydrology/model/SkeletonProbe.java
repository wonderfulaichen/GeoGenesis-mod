package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Arrays;
import java.util.List;

/**
 * ★ 确定性骨架判据探针（`gradlew runSkeletonProbe`，2026-10-10）。
 *
 * <p>用户方向（HYDROLOGY.md §〇② + 2026-10-10）：河流必须满足<b>单调下降</b>，
 * 到局部洼地结束蓄水成湖，湖泊溢出口作为新河段起点 —— 判据必须<b>硬</b>，
 * 不能"出图看一眼"。本探针两条腿：</p>
 *
 * <ol>
 *   <li><b>合成地形</b>（纯数组，零 MC 依赖）：手造树 —— 干流入湖 → 溢口续流入海、
 *       支流汇入、凸坡源头（应被汇流槽判据否决）。</li>
 *   <li><b>真实地形</b>（CellGenerator 小窗）：跑生产同源 {@code HydrologyPipeline}
 *       后对真实 level/parent 应用同一组判据。</li>
 * </ol>
 *
 * <h3>判据（任一 FAIL ⇒ exit 1）</h3>
 * <pre>
 * [1] 单调性：每条 trace 沿途 level 非升（用户硬需求"单调下降"）
 * [2] 入湖终止：end=MOUTH_LAKE ⇒ lakeMask[parent[mouth]] == true（河止于湖岸，不穿湖）
 * [3] 入海终止：end=MOUTH_SEA ⇒ sea[mouth] 或 sea[parent[mouth]]
 * [4] 无双占：除 JOIN 终点共享外，任何格不得是两条 trace 的内部节点
 * [5] 溢口续流：每个有效 basinOutlet 都有 trace 从它开始（或已作为内部节点被上游段覆盖）
 * [6] 确定性：build+shape 连跑两次 ⇒ 追踪与几何逐位相同
 * [7] 形态不越界：shape 后节点仍在窗口内（±10 块容差，meander 幅度 6 + Catmull-Rom 过冲）
 * </pre>
 */
public final class SkeletonProbe {

    private static int failures = 0;

    private static void check(boolean ok, String name, String detail) {
        if (ok) {
            System.out.println("  [PASS] " + name);
        } else {
            failures++;
            System.out.println("  [FAIL] " + name + " —— " + detail);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== SkeletonProbe：确定性骨架判据 ===");
        runSynthetic();
        runIncomingDiagnostics();
        runShortIncoming();
        runSeaRollback();
        runSeaClaims();
        runCrossingOracle();
        runEndpointJoin();
        runTraceSegmentsOracle();
        runCrossingParentOracle();
        runFlatDetour();
        runBasinEscape();
        runBlockedFlatCandidate();
        runParentForestOracle();
        if (args.length == 0 || !args[0].equals("synthetic")) runRealTerrain();
        System.out.println(failures == 0 ? "ALL PASS" : ("FAILED: " + failures + " 项"));
        System.exit(failures == 0 ? 0 : 1);
    }

    // ======================================================================
    // 合成地形
    // ======================================================================

    private static void runSynthetic() {
        System.out.println("--- [A] 合成地形 ---");
        int nx = 7, nz = 7;
        int cells = nx * nz;
        double[] level = new double[cells];
        int[] parent = new int[cells];
        boolean[] resolved = new boolean[cells];
        boolean[] lake = new boolean[cells];
        boolean[] sea = new boolean[cells];
        double[] dis = new double[cells];
        Arrays.fill(parent, -1);

        // 格 (i,j) → idx = i*nz + j（与生产一致：i = k/nz, j = k%nz）
        // 干流：(1,0)→(2,0)→(3,0)→(4,0)→(5,0)→(6,0)=海
        // 支流：(1,1)→(2,1)→(3,1)→(4,1)→(5,1)→(5,0) 汇入干流
        // 湖：干流旁 (6,3) 湖，支链 (3,3)→(3,4)→(3,5)→(3,6)=湖，溢口 (3,6)→(4,6)→(6,6)=海
        int[] riverA = {idx(1, 0, nz), idx(2, 0, nz), idx(3, 0, nz), idx(4, 0, nz), idx(5, 0, nz), idx(6, 0, nz)};
        linkChain(riverA, parent);
        int[] riverB = {idx(1, 1, nz), idx(2, 1, nz), idx(3, 1, nz), idx(4, 1, nz), idx(5, 1, nz), idx(5, 0, nz)};
        linkChain(riverB, parent);
        // 支流入湖链（3,3)..(3,5) → (3,6)=湖
        int[] riverC = {idx(3, 3, nz), idx(3, 4, nz), idx(3, 5, nz), idx(3, 6, nz)};
        linkChain(riverC, parent);
        // 溢口链：(4,6)→(5,6)→(6,6)=海（basinOutlet = (4,6)）
        int[] outletChain = {idx(4, 6, nz), idx(5, 6, nz), idx(6, 6, nz)};
        linkChain(outletChain, parent);

        // 高程：沿链单调下降；海 = 最低
        for (int i = 0; i < cells; i++) level[i] = 100;
        int[] down = {0, 2, 4, 6, 8, 10};       // 干流每步 -2
        for (int s = 0; s < riverA.length; s++) level[riverA[s]] = 100 - down[Math.min(s, down.length - 1)];
        level[riverA[5]] = 60;                    // 海格
        int[] downB = {0, 2, 4, 6, 8};
        for (int s = 0; s < 5; s++) level[riverB[s]] = 100 - downB[s] + 1;   // 支流略高于干流同级
        level[riverB[5]] = level[idx(5, 0, nz)];  // 汇合 = 干流格
        for (int s = 0; s < 3; s++) level[riverC[s]] = 95 - s * 2;
        level[idx(3, 6, nz)] = 88;                // 湖
        for (int s = 0; s < outletChain.length; s++) level[outletChain[s]] = 87 - s * 2;
        level[idx(6, 6, nz)] = 60;                // 海

        Arrays.fill(resolved, true);
        lake[idx(3, 6, nz)] = true;
        sea[idx(6, 0, nz)] = true;
        sea[idx(6, 6, nz)] = true;
        // 上游面积：干流 > 支流 > 湖链（源头排序按面积降序）
        Arrays.fill(dis, 1);
        for (int s = 0; s < riverA.length; s++) dis[riverA[s]] = 20 - s * 2;
        for (int s = 0; s < 5; s++) dis[riverB[s]] = 8;
        for (int s = 0; s < riverC.length; s++) dis[riverC[s]] = 4;
        // 凸坡源头（应被汇流槽判据否决）：(1,5) parent=(2,5)，两侧更低
        int convex = idx(1, 5, nz);
        parent[convex] = idx(2, 5, nz);
        level[idx(2, 5, nz)] = 96;
        level[idx(1, 5, nz)] = 99;
        level[idx(1, 4, nz)] = 94;    // 左侧更低
        level[idx(1, 6, nz)] = 94;    // 右侧更低 ⇒ 凸坡
        resolved[convex] = true;
        dis[convex] = 5;
        resolved[idx(2, 5, nz)] = true;

        int[] basinOutlet = {idx(4, 6, nz)};
        var params = DeterministicSkeleton.Params.defaults();

        var r1 = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                basinOutlet, nx, nz, params);
        var r2 = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                basinOutlet, nx, nz, params);
        check(r1.traces.size() == r2.traces.size(), "[6] 确定性（段数）",
                "两次构建段数 " + r1.traces.size() + " vs " + r2.traces.size());

        validateTraces(r1.traces, level, parent, lake, sea, resolved, nx, nz, basinOutlet, "合成");

        // 凸坡源头必须被否决（汇流槽判据）
        boolean convexUsed = false;
        for (var t : r1.traces) {
            for (int c : t.cell) if (c == convex) convexUsed = true;
        }
        check(!convexUsed, "[合成] 凸坡源头被汇流槽判据否决", "convex 参与了追踪");

        // 形态 + 形态确定性 + 越界
        var s1 = DeterministicSkeleton.shape(r1.traces, level, 0, 0, 1, nx, nz, params, null);
        var s2 = DeterministicSkeleton.shape(r1.traces, level, 0, 0, 1, nx, nz, params, null);
        boolean same = s1.size() == s2.size();
        if (same) {
            for (int i = 0; i < s1.size(); i++) {
                if (!Arrays.equals(s1.get(i).x, s2.get(i).x)
                        || !Arrays.equals(s1.get(i).z, s2.get(i).z)) { same = false; break; }
            }
        }
        check(same, "[6] 形态确定性（shape 逐位相同）", "两次 shape 不一致");
        boolean inBounds = true;
        for (var sh : s1) {
            for (int i = 0; i < sh.size(); i++) {
                if (sh.x[i] < -10 || sh.x[i] > nx + 10 || sh.z[i] < -10 || sh.z[i] > nz + 10) {
                    inBounds = false;
                }
            }
        }
        check(inBounds, "[7] 形态不越界（±10 容差）", "有节点飞出窗口");
    }

    private static void runIncomingDiagnostics() {
        System.out.println("--- [incoming] 归因完整且诊断不改产出 ---");
        int n = 12, cells = n * n;
        double[] level = new double[cells], discharge = new double[cells];
        int[] parent = new int[cells];
        boolean[] resolved = new boolean[cells], lake = new boolean[cells], sea = new boolean[cells];
        Arrays.fill(parent, -1);
        Arrays.fill(resolved, true);
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) level[idx(i, j, n)] = 100 - i;
        for (int i = 1; i < n - 1; i++) parent[idx(i, 1, n)] = idx(i + 1, 1, n);
        sea[idx(n - 1, 1, n)] = true;
        lake[idx(1, 3, n)] = true;
        sea[idx(1, 5, n)] = true;
        parent[idx(1, 7, n)] = idx(2, 7, n);
        lake[idx(2, 7, n)] = true;
        for (int i = 1; i < 7; i++) parent[idx(i, 9, n)] = idx(i + 1, 9, n);
        var params = new DeterministicSkeleton.Params(0, 0, 0, 0, 24, 0, 6, 0, 0);
        List<Integer> incoming = List.of(-1, idx(1, 1, n), idx(2, 1, n), idx(1, 3, n),
                idx(1, 5, n), idx(1, 7, n), idx(1, 9, n));
        com.geogenesis.diagnostics.WorldGenProfiler.configure(false, 0);
        var off = DeterministicSkeleton.build(level, parent, resolved, lake, sea, discharge,
                null, n, n, params, incoming, null, false);
        com.geogenesis.diagnostics.WorldGenProfiler.configure(true, 0);
        DeterministicSkeleton.Result on;
        try {
            on = DeterministicSkeleton.build(level, parent, resolved, lake, sea, discharge,
                    null, n, n, params, incoming, null, false);
        } finally { com.geogenesis.diagnostics.WorldGenProfiler.configure(false, 0); }
        var counts = new java.util.TreeMap<String, Integer>();
        for (var d : on.incomingDiagnostics) counts.merge(d.status(), 1, Integer::sum);
        check(on.incomingDiagnostics.size() == incoming.size()
                        && counts.equals(java.util.Map.of("OUT_OF_WINDOW", 1, "ACCEPTED", 2,
                        "COVERED", 1, "LAKE", 1, "SEA", 1, "ORPHAN_DEAD_END", 1)),
                "incoming 逐入口完整归因，合法短入湖连接被接受", "counts=" + counts);
        boolean same = off.traces.size() == on.traces.size() && off.exitSeeds.equals(on.exitSeeds)
                && off.incomingStarts == on.incomingStarts;
        for (int i = 0; same && i < off.traces.size(); i++) {
            var a = off.traces.get(i); var b = on.traces.get(i);
            same = Arrays.equals(a.cell, b.cell) && a.end == b.end && a.fromOutlet == b.fromOutlet;
        }
        check(same, "诊断开/关 trace 与出口逐位相同", "产出改变");
    }

    private static void runShortIncoming() {
        System.out.println("--- [short-incoming] 接力连接不是独立源头 ---");
        int n = 9, cells = n * n; var level = new double[cells]; var dis = new double[cells];
        var parent = new int[cells]; var resolved = new boolean[cells]; var lake = new boolean[cells]; var sea = new boolean[cells];
        Arrays.fill(parent, -1); Arrays.fill(resolved, true);
        for (int k = 0; k < cells; k++) level[k] = 100 - k / n;
        int start = idx(5, 4, n), end = idx(6, 4, n); parent[start] = end;
        var params = new DeterministicSkeleton.Params(0, 0, 0, 0, 24, 0, 6, 0, 0);
        for (boolean waterIsLake : new boolean[]{true, false}) {
            lake[end] = waterIsLake; sea[end] = !waterIsLake;
            var r = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                    null, n, n, params, List.of(start), null, false);
            check(r.incomingStarts == 1 && r.traces.size() == 1 && r.traces.get(0).cell.length == 2
                    && r.traces.get(0).end == (waterIsLake ? DeterministicSkeleton.End.MOUTH_LAKE : DeterministicSkeleton.End.MOUTH_SEA),
                    "两格接力入" + (waterIsLake ? "湖" : "海") + "不得因独立源长度门限丢失", "traces=" + r.traces.size());
        }
        lake[end] = false; sea[end] = false;
        var orphan = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                null, n, n, params, List.of(start), null, false);
        check(orphan.traces.isEmpty(), "内部无合法终端接力仍拒绝，不创造悬河", "accepted");
        int nearFrame = idx(7, 4, n), frame = idx(8, 4, n); parent[nearFrame] = frame;
        var handoff = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                null, n, n, params, List.of(nearFrame), null, false);
        check(handoff.incomingStarts == 1 && handoff.exitSeeds.size() == 1, "两格到框接力保留接口，不冒充入海", "dropped");
        int joinStart = idx(7, 5, n); parent[joinStart] = nearFrame;
        var joined = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                null, n, n, params, List.of(joinStart, nearFrame, joinStart), null, false);
        check(joined.incomingStarts == 2 && joined.traces.stream().anyMatch(t -> t.end == DeterministicSkeleton.End.JOIN
                && t.cell.length == 2), "两格接力汇入既有段保留，重复入口不重复生成", "traces=" + joined.traces.size());
        sea[end] = true; dis[start] = 10; dis[end] = 11;
        var sourceParams = new DeterministicSkeleton.Params(0, 1, 0, 0, 24, 0, 6, 0, 0);
        var sourceOnly = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                null, n, n, sourceParams, List.of(), null, false);
        check(sourceOnly.sourcesTotal > 0 && sourceOnly.traces.isEmpty(), "已达面积门限的独立短源仍受长度门限", "accepted or no source");
        int singleFrame = idx(8, 0, n);
        var single = DeterministicSkeleton.build(level, parent, resolved, lake, sea, new double[cells],
                null, n, n, params, List.of(singleFrame), null, false);
        check(single.traces.isEmpty(), "单格接口不生成零长接力段", "accepted");
    }

    private static void runSeaRollback() {
        System.out.println("--- [sea-rollback] 被丢弃短链不得留下海格幽灵认领 ---");
        for (int direction : new int[]{-1, 1}) for (boolean lakeTerminal : new boolean[]{false, true}) {
            int n = 11, cells = n * n, mouth = idx(5, 5, n), shortStart = idx(5 - direction, 5, n);
            double[] level = new double[cells], dis = new double[cells]; int[] parent = new int[cells];
            boolean[] resolved = new boolean[cells], lake = new boolean[cells], sea = new boolean[cells];
            Arrays.fill(level, 200); Arrays.fill(parent, -1); Arrays.fill(resolved, true);
            lake[mouth] = lakeTerminal; sea[mouth] = !lakeTerminal; level[mouth] = 0;
            parent[shortStart] = mouth; level[shortStart] = 10; dis[shortStart] = 100;
            for (int j = 1; j <= 4; j++) { int k = idx(5, j, n); parent[k] = idx(5, j + 1, n); level[k] = 60 - j * 10; dis[k] = 10; }
            var params = new DeterministicSkeleton.Params(0, 1, 2, 0, 24, 0, 4, 0, 0);
            var aloneDis = dis.clone(); aloneDis[shortStart] = 0;
            var alone = DeterministicSkeleton.build(level, parent, resolved, lake, sea, aloneDis, null, n, n, params, List.of(), null, false);
            var together = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis, null, n, n, params, List.of(), null, false);
            boolean same = alone.traces.size() == 1 && together.traces.size() == 1;
            if (same) same = Arrays.equals(alone.traces.get(0).cell, together.traces.get(0).cell)
                    && alone.traces.get(0).end == together.traces.get(0).end;
            System.out.println("SEA_ROLLBACK direction=" + direction + " lake=" + lakeTerminal + " alone=" + alone.traces.size()
                    + " withDiscarded=" + together.traces.size());
            check(same, "短链丢弃不改后续合法入" + (lakeTerminal ? "湖" : "海") + "链 direction=" + direction, "ghost coverage changed route");
        }
    }

    private static void runSeaClaims() {
        try {
            var trace = DeterministicSkeleton.class.getDeclaredMethod("traceOne", int.class, boolean.class, boolean.class,
                    double[].class, int[].class, boolean[].class, boolean[].class, boolean[].class, boolean[].class,
                    int.class, int.class, int.class, int[].class, int[].class, int.class, DeterministicSkeleton.Params.class,
                    java.util.List.class, double[].class, DeterministicSkeleton.ParentForest.class, java.util.List.class);
            trace.setAccessible(true);
            for (boolean preclaimed : new boolean[]{false, true}) for (boolean outlet : new boolean[]{false, true}) {
                int n = 7, cells = n * n, start = idx(3, 3, n), mouth = idx(3, 4, n);
                double[] level = new double[cells]; int[] parent = new int[cells];
                boolean[] resolved = new boolean[cells], lake = new boolean[cells], sea = new boolean[cells], covered = new boolean[cells];
                Arrays.fill(level, 100); Arrays.fill(parent, -1); Arrays.fill(resolved, true);
                parent[start] = mouth; level[mouth] = 0; sea[mouth] = true; covered[mouth] = preclaimed;
                var before = covered.clone();
                var params = new DeterministicSkeleton.Params(0, 1, 0, 0, 24, 0, 6, 0, 0);
                var result = (DeterministicSkeleton.Trace) trace.invoke(null, start, outlet, false, level, parent, resolved,
                        lake, sea, covered, n, n, 0, new int[cells], new int[cells], 1, params, List.of(), null, null, null);
                boolean correct = outlet ? result != null && covered[start] && covered[mouth]
                        : result == null && Arrays.equals(before, covered);
                check(correct, "海口认领事务 outlet=" + outlet + " preclaimed=" + preclaimed, "rollback released existing claim or kept new claim");
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void runCrossingOracle() {
        try {
            var method = DeterministicSkeleton.class.getDeclaredMethod("segmentsCross", int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, int.class); method.setAccessible(true);
            int mismatches = 0, cases = 0, positives = 0;
            for (int ax = -1; ax <= 1; ax++) for (int az = -1; az <= 1; az++)
                for (int bx = -1; bx <= 1; bx++) for (int bz = -1; bz <= 1; bz++)
                    for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                            int ux = bx - ax, uz = bz - az, vx = dx - cx, vz = dz - cz;
                            int determinant = ux * vz - uz * vx;
                            double t = determinant == 0 ? -1 : ((cx - ax) * vz - (cz - az) * vx) / (double) determinant;
                            double s = determinant == 0 ? -1 : ((cx - ax) * uz - (cz - az) * ux) / (double) determinant;
                            boolean expected = t > 0 && t < 1 && s > 0 && s < 1;
                            boolean actual = (boolean) method.invoke(null, ax, az, bx, bz, cx, cz, dx, dz);
                            if (expected) positives++; if (actual != expected) mismatches++; cases++;
                        }
            System.out.println("CROSS_ORACLE cases=" + cases + " proper=" + positives + " mismatches=" + mismatches);
            check(cases == 6561 && positives > 0 && mismatches == 0,
                    "真交叉独立参数方程oracle：反向/端点/共线/退化全枚举", "mismatches=" + mismatches);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void runEndpointJoin() {
        for (int rotation = 0; rotation < 4; rotation++) {
            int n = 11, cells = n * n; double[] level = new double[cells], dis = new double[cells];
            int[] parent = new int[cells]; boolean[] resolved = new boolean[cells], lake = new boolean[cells], sea = new boolean[cells];
            Arrays.fill(level, 200); Arrays.fill(parent, -1); Arrays.fill(resolved, true);
            int[][] trunk = {{3, 3}, {4, 3}, {5, 3}, {6, 3}, {7, 3}, {8, 3}};
            int[] chain = new int[trunk.length];
            for (int i = 0; i < trunk.length; i++) {
                int x = trunk[i][0] - 5, z = trunk[i][1] - 5;
                for (int r = 0; r < rotation; r++) { int tmp = x; x = -z; z = tmp; }
                chain[i] = idx(x + 5, z + 5, n); level[chain[i]] = 100 - i * 5; dis[chain[i]] = 10;
            }
            linkChain(chain, parent); sea[chain[chain.length - 1]] = true;
            int x = -1, z = -1;
            for (int r = 0; r < rotation; r++) { int tmp = x; x = -z; z = tmp; }
            int feeder = idx(x + 5, z + 5, n); level[feeder] = 110; parent[feeder] = chain[1]; dis[feeder] = 2;
            var params = new DeterministicSkeleton.Params(0, 1, 2, 0, 24, 0, 2, 0, 0);
            var result = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis, null, n, n,
                    params, List.of(), null, false);
            var branch = result.traces.stream().filter(t -> t.sourceCell() == feeder).findFirst().orElse(null);
            boolean direct = branch != null && branch.end == DeterministicSkeleton.End.JOIN
                    && branch.cell.length == 2 && branch.mouthCell() == chain[3];
            System.out.println("ENDPOINT_JOIN rotation=" + rotation + " feeder=" + feeder
                    + " cells=" + (branch == null ? "MISSING" : Arrays.toString(branch.cell)));
            check(direct, "共享端点合法下坡汇流不受线段方向影响 rotation=" + rotation, "legal closest downstream join rejected");
        }
    }

    private static boolean properCrossOracle(int a, int b, int c, int d, int nz) {
        double ux=b/nz-a/nz,uz=b%nz-a%nz,vx=d/nz-c/nz,vz=d%nz-c%nz,wx=c/nz-a/nz,wz=c%nz-a%nz;
        double den=ux*vz-uz*vx;if(den==0)return false;
        double t=(wx*vz-wz*vx)/den,s=(wx*uz-wz*ux)/den;return t>0 && t<1 && s>0 && s<1;
    }

    private static void runTraceSegmentsOracle() {
        int n=33;var random=new java.util.Random(3807);var index=new DeterministicSkeleton.TraceSegments(n);
        var edges=new java.util.ArrayList<int[]>();boolean same=true;int positives=0;
        for(int insert=0;insert<200;insert++) {
            int a=random.nextInt(n*n),b=random.nextInt(n*n);index.add(a,b);edges.add(new int[]{a,b});
            for(int query=0;query<30;query++) {
                int c=random.nextInt(n*n),d=random.nextInt(n*n);boolean expected=false;
                for(var e:edges)expected|=properCrossOracle(c,d,e[0],e[1],n);
                same &= expected==index.crosses(c,d);if(expected)positives++;
            }
        }
        check(same && positives>0,"本链空间桶6000查询等价独立参数交叉oracle（跨桶/长段/退化）","mismatch");
    }

    private static void runCrossingParentOracle() {
        try {
            var method=DeterministicSkeleton.class.getDeclaredMethod("markCrossingParents",int.class,int.class,int.class,
                    int[].class,DeterministicSkeleton.ParentForest.class,java.util.TreeMap.class);method.setAccessible(true);
            int n=17,cells=n*n;var random=new java.util.Random(6123);boolean same=true;int marked=0;
            for(int test=0;test<16;test++) {
                int[] parent=new int[cells];java.util.Arrays.fill(parent,-1);
                for(int x=0;x<n;x++)for(int z=0;z+1<n;z++) {
                    int dx=random.nextInt(3)-1;if(x+dx>=0 && x+dx<n)parent[x*n+z]=(x+dx)*n+z+1;
                }
                var forest=new DeterministicSkeleton.ParentForest(parent);var ranges=new java.util.TreeMap<Integer,Integer>();
                var pathEdges=new java.util.ArrayList<int[]>();
                for(int add=0;add<32;add++) {
                    int x=random.nextInt(n-1),z=random.nextInt(n-1),a=x*n+z,b=(x+1)*n+z+1;
                    if(random.nextBoolean()){a=x*n+z+1;b=(x+1)*n+z;}
                    method.invoke(null,a,b,n,parent,forest,ranges);pathEdges.add(new int[]{a,b});
                    for(int c=0;c<cells;c++) {
                        boolean expected=false;
                        for(int at=c;at>=0 && parent[at]>=0;at=parent[at])for(var e:pathEdges)
                            expected|=properCrossOracle(at,parent[at],e[0],e[1],n);
                        same &= expected==forest.returnsTo(c,ranges);if(expected)marked++;
                    }
                }
            }
            check(same && marked>0,"父链几何穿回禁入区等价独立逐父边oracle：16图×32边×289格","mismatch");
        } catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }

    private static void runFlatDetour() {
        System.out.println("--- [flat-detour] 原父树无环不代表改道后无环 ---");
        int n = 9, cells = n * n;
        for (int direction : new int[]{-1, 1}) {
            double[] level = new double[cells], bed = new double[cells], dis = new double[cells];
            int[] parent = new int[cells]; boolean[] resolved = new boolean[cells];
            boolean[] lake = new boolean[cells], sea = new boolean[cells];
            Arrays.fill(level, 100); Arrays.fill(bed, 105); Arrays.fill(parent, -1); Arrays.fill(resolved, true);
            int start = idx(4, 4, n), upstream = idx(4, 4 - direction, n);
            int next = idx(4, 4 + direction, n), mouth = idx(4, 4 + 2 * direction, n);
            parent[upstream] = start; parent[start] = next; parent[next] = mouth;
            bed[start] = 99; bed[upstream] = 98; bed[next] = 99; bed[mouth] = 97; sea[mouth] = true;
            var params = new DeterministicSkeleton.Params(0, 1, 0, 0, 24, 0, 6, 0, 0);
            var r = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                    null, n, n, params, List.of(start), bed, false);
            boolean valid = r.traces.size() == 1 && r.traces.get(0).end == DeterministicSkeleton.End.MOUTH_SEA
                    && r.traces.get(0).sourceCell() == start && r.traces.get(0).mouthCell() == mouth;
            for (var trace : r.traces) {
                var seen = new java.util.HashSet<Integer>();
                for (int cell : trace.cell) valid &= seen.add(cell);
            }
            System.out.println("FLAT_DETOUR direction=" + direction + " traces=" + r.traces.size()
                    + (r.traces.isEmpty() ? "" : " end=" + r.traces.get(0).end + " cells=" + Arrays.toString(r.traces.get(0).cell)));
            check(valid, "等水位更低床面邻居若原父链返回本链，不得自JOIN或删源 direction=" + direction, "loop or lost source");
            int alternative = idx(3, 4, n);
            bed[upstream] = 101; bed[alternative] = 98; parent[alternative] = mouth;
            var allowed = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                    null, n, n, params, List.of(start), bed, false);
            check(allowed.traces.size() == 1 && allowed.traces.get(0).cell[1] == alternative
                    && allowed.traces.get(0).end == DeterministicSkeleton.End.MOUTH_SEA,
                    "不回本链的真实下坡改道仍保留 direction=" + direction, "legal detour lost");
        }
    }

    private static void runBasinEscape() {
        System.out.println("--- [basin-escape] 等水位脱困不得反复掉回盆底 ---");
        int n=15,cells=n*n;
        for(int direction:new int[]{-1,1}) for(boolean nextBasin:new boolean[]{false,true}) {
            var level=new double[cells];var bed=new double[cells];var dis=new double[cells];var parent=new int[cells];
            var resolved=new boolean[cells];var lake=new boolean[cells];var sea=new boolean[cells];
            Arrays.fill(level,100);Arrays.fill(bed,105);Arrays.fill(parent,-1);Arrays.fill(resolved,true);
            int start=idx(7,4,n),rise=idx(7+direction,4,n),exit=idx(7+2*direction,4,n);
            int trap=idx(7+direction,5,n),slope=idx(7+3*direction,4,n),normal=idx(7+4*direction,4,n);
            int second=idx(7+4*direction,5,n),mouth=idx(7+5*direction,6,n);
            parent[start]=rise;parent[rise]=exit;parent[exit]=slope;parent[trap]=exit;parent[slope]=normal;parent[normal]=mouth;parent[second]=mouth;
            bed[start]=90;bed[rise]=91;bed[exit]=92;bed[trap]=90.5;bed[slope]=80;bed[normal]=79.5;bed[second]=79;bed[mouth]=70;
            level[slope]=level[normal]=level[second]=level[mouth]=nextBasin?99:100;sea[mouth]=true;
            var params=new DeterministicSkeleton.Params(0,1,0,0,24,0,2,0,0);
            var result=DeterministicSkeleton.build(level,parent,resolved,lake,sea,dis,null,n,n,params,List.of(start),bed,false);
            var expected=nextBasin?new int[]{start,rise,exit,slope,second,mouth}:new int[]{start,rise,exit,slope,normal,mouth};
            boolean ok=result.traces.size()==1 && Arrays.equals(result.traces.get(0).cell,expected)
                    && result.traces.get(0).end==DeterministicSkeleton.End.MOUTH_SEA;
            System.out.println("BASIN_ESCAPE direction="+direction+" nextBasin="+nextBasin+" cells="
                    +(result.traces.isEmpty()?"MISSING":Arrays.toString(result.traces.get(0).cell)));
            check(ok,"脱困沿同水位父链，严格降水位后允许新盆下坡 direction="+direction+" nextBasin="+nextBasin,"repeated detour or permanent disable");
        }
    }

    private static void runBlockedFlatCandidate() {
        try {
            var trace=DeterministicSkeleton.class.getDeclaredMethod("traceOne",int.class,boolean.class,boolean.class,
                    double[].class,int[].class,boolean[].class,boolean[].class,boolean[].class,boolean[].class,
                    int.class,int.class,int.class,int[].class,int[].class,int.class,DeterministicSkeleton.Params.class,
                    java.util.List.class,double[].class,DeterministicSkeleton.ParentForest.class,java.util.List.class);
            trace.setAccessible(true);var evidence=new StringBuilder();int n=13,cells=n*n;
            for(int rotation=0;rotation<4;rotation++) for(boolean reverse:new boolean[]{false,true})
                for(boolean alternative:new boolean[]{false,true}) {
                    var level=new double[cells];var bed=new double[cells];var parent=new int[cells];
                    var resolved=new boolean[cells];var lake=new boolean[cells];var sea=new boolean[cells];var covered=new boolean[cells];
                    Arrays.fill(level,100);Arrays.fill(bed,110);Arrays.fill(parent,-1);Arrays.fill(resolved,true);
                    int start=rotatedCell(0,0,rotation,n),next=rotatedCell(0,-1,rotation,n),mouth=rotatedCell(-1,-1,rotation,n);
                    int blocked=rotatedCell(1,1,rotation,n),allowed=rotatedCell(-1,0,rotation,n);
                    int edgeA=rotatedCell(0,1,rotation,n),edgeB=rotatedCell(1,0,rotation,n);
                    covered[edgeA]=covered[edgeB]=true;
                    parent[start]=next;parent[next]=mouth;parent[blocked]=rotatedCell(2,2,rotation,n);parent[allowed]=mouth;
                    bed[start]=100;bed[next]=100;bed[mouth]=90;bed[blocked]=98;bed[allowed]=alternative?99:110;sea[mouth]=true;
                    var edge=reverse?new int[]{edgeB/n,edgeB%n,edgeA/n,edgeA%n}:new int[]{edgeA/n,edgeA%n,edgeB/n,edgeB%n};
                    check(properCrossOracle(start,blocked,edgeA,edgeB,n) && !properCrossOracle(start,allowed,edgeA,edgeB,n)
                                    && !properCrossOracle(start,next,edgeA,edgeB,n),
                            "候选交叉fixture独立oracle rotation="+rotation+" reverse="+reverse+" alt="+alternative,"fixture invalid");
                    var params=new DeterministicSkeleton.Params(0,1,0,0,24,0,2,0,0);
                    var result=(DeterministicSkeleton.Trace)trace.invoke(null,start,false,true,level,parent,resolved,lake,sea,
                            covered,n,n,0,new int[cells],new int[cells],1,params,List.of(edge),bed,new DeterministicSkeleton.ParentForest(parent),null);
                    int expected=alternative?allowed:next;
                    boolean ok=result!=null && result.end==DeterministicSkeleton.End.MOUTH_SEA
                            && Arrays.equals(result.cell,new int[]{start,expected,mouth});
                    String row="G32 FLAT_CANDIDATE rotation="+rotation+" reverse="+reverse+" alt="+alternative
                            +" expected="+expected+" actual="+(result==null?"DISCARDED":Arrays.toString(result.cell));
                    System.out.println(row);evidence.append(row).append('\n');
                    check(ok,"交叉最低候选应跳过而非整河回滚 rotation="+rotation+" reverse="+reverse+" alt="+alternative,"legal downstream lost");
                }
            // #region agent log: G32 候选筛选的实际traceOne复现，保留历史证据
            java.nio.file.Files.writeString(java.nio.file.Path.of("..","debug-agent.log").toAbsolutePath(),evidence,
                    java.nio.file.StandardOpenOption.APPEND);
            // #endregion
        } catch(ReflectiveOperationException|java.io.IOException e){throw new IllegalStateException(e);}
    }

    private static int rotatedCell(int x,int z,int rotation,int n) {
        for(int i=0;i<rotation;i++){int old=x;x=-z;z=old;}
        return (x+n/2)*n+z+n/2;
    }

    private static void runParentForestOracle() {
        var random = new java.util.Random(7319); boolean same = true;
        for (int test = 0; test < 32; test++) {
            int[] parent = new int[128];
            for (int cell = 0; cell < parent.length; cell++) parent[cell] = cell == 0 || random.nextInt(8) == 0 ? -1 : random.nextInt(cell);
            var order = new java.util.ArrayList<Integer>();
            for (int cell = 0; cell < parent.length; cell++) order.add(cell);
            java.util.Collections.shuffle(order, random); int[] permuted = new int[parent.length];
            for (int cell = 0; cell < parent.length; cell++) permuted[order.get(cell)] = parent[cell] < 0 ? -1 : order.get(parent[cell]);
            parent = permuted;
            var forest = new DeterministicSkeleton.ParentForest(parent);
            var ranges = new java.util.TreeMap<Integer, Integer>(); var marked = new java.util.BitSet(parent.length);
            for (int add = 0; add < 32; add++) {
                int mark = random.nextInt(parent.length); marked.set(mark); forest.mark(mark, ranges);
                for (int cell = 0; cell < parent.length; cell++) {
                    boolean expected = false;
                    for (int at = cell; at >= 0; at = parent[at]) expected |= marked.get(at);
                    same &= expected == forest.returnsTo(cell, ranges);
                }
            }
        }
        check(same, "父树区间并集等价独立多步父链oracle：32树×32更新×128节点", "different");
        int[] chain = new int[100000];
        for (int i = 0; i < chain.length; i++) chain[i] = i - 1;
        var deep = new DeterministicSkeleton.ParentForest(chain); var marks = new java.util.TreeMap<Integer, Integer>();
        deep.mark(70000, marks);
        check(deep.returnsTo(99999, marks) && !deep.returnsTo(69999, marks), "深10万节点树迭代索引不递归溢栈", "different");
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); new DeterministicSkeleton.ParentForest(chain); }
        catch (java.util.concurrent.CancellationException e) { cancelled = true; } finally { Thread.interrupted(); }
        check(cancelled, "父树索引取消不发布半结果", "ignored");
        var unusual = new DeterministicSkeleton.ParentForest(new int[]{1, 0, 0, 3, 99, -1, 5});
        var ranges = new java.util.TreeMap<Integer, Integer>(); unusual.mark(2, ranges);
        check(ranges.isEmpty() && unusual.returnsTo(0, ranges) && unusual.returnsTo(1, ranges)
                && unusual.returnsTo(2, ranges) && !unusual.returnsTo(3, ranges), "含环及其上游改道拒绝，合法根仍可用", "different");
        unusual.mark(3, ranges); unusual.mark(4, ranges); unusual.mark(5, ranges);
        check(unusual.returnsTo(3, ranges) && unusual.returnsTo(4, ranges) && unusual.returnsTo(6, ranges),
                "自根/越界父根/独立根的子树区间正确", "different");
    }

    // ======================================================================
    // 真实地形（生产同源 pipeline）
    // ======================================================================

    private static void runRealTerrain() {
        System.out.println("--- [B] 真实地形（seed 9139912035078620160，r=352 = 生产口径）---");
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(9139912035078620160L);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        // 生产口径：REGION=512/2 + HALO=96 = 352、STEP=4
        int radius = Integer.getInteger("geogenesis.modelRegion", 512) / 2
                + Integer.getInteger("geogenesis.modelHalo", 96);
        int step = Integer.getInteger("geogenesis.modelStep", 4);
        int cx = -140, cz = 137;
        var req = new HydrologyPipeline.Request(cx, cz, radius, step, climate);
        var p = HydrologyPipeline.run(gen, gen.params(), req);
        int n = p.nx();

        // ★ 2026-10-10【discharge 量级实测（勿再推算）】：门限扫描发现 1800→7078 河道数不变
        //   ⇒ 门限已不是约束 ⇒ 必须直接量 discharge 分布，才能定"成河门限"。
        {
            double mx = 0, sum = 0;
            int n2 = p.nx() * p.nz(), c2 = 0;
            double[] dd = p.discharge();
            java.util.List<Double> vals = new java.util.ArrayList<>();
            for (int k = 0; k < n2; k++) {
                if (!Double.isFinite(dd[k]) || dd[k] <= 0) continue;
                mx = Math.max(mx, dd[k]); sum += dd[k]; c2++;
                vals.add(dd[k]);
            }
            java.util.Collections.sort(vals);
            if (!vals.isEmpty()) {
                System.out.printf("  [discharge] 有效格=%d max=%.1f mean=%.1f P50=%.1f P90=%.1f P99=%.1f%n",
                        c2, mx, sum / Math.max(1, c2), vals.get(vals.size() / 2),
                        vals.get(vals.size() * 90 / 100), vals.get(vals.size() * 99 / 100));
            }
        }
        var params = DeterministicSkeleton.Params.defaults();
        var r1 = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(),
                p.lake(), p.sea(), p.discharge(),
                p.lakeResult() == null ? null : p.lakeResult().basinOutlet(), n, n, params);
        var r2 = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(),
                p.lake(), p.sea(), p.discharge(),
                p.lakeResult() == null ? null : p.lakeResult().basinOutlet(), n, n, params);
        System.out.println("  traces=" + r1.traces.size()
                + " sources=" + r1.sourcesTotal
                + " droppedBySpacing=" + r1.sourcesDroppedBySpacing
                + " outletStarts=" + r1.outletStarts);
        check(r1.traces.size() == r2.traces.size(), "[6] 确定性（真实地形段数）",
                "两次构建段数 " + r1.traces.size() + " vs " + r2.traces.size());

        int[] bo = p.lakeResult() == null ? null : p.lakeResult().basinOutlet();
        validateTraces(r1.traces, p.level(), p.parent(), p.lake(), p.sea(), p.resolved(), n, n, bo, "真实");

        var s1 = DeterministicSkeleton.shape(r1.traces, p.level(), cx - radius, cz - radius,
                step, n, n, params, null);
        var s2 = DeterministicSkeleton.shape(r1.traces, p.level(), cx - radius, cz - radius,
                step, n, n, params, null);
        boolean same = s1.size() == s2.size();
        if (same) {
            for (int i = 0; i < s1.size(); i++) {
                if (!Arrays.equals(s1.get(i).x, s2.get(i).x)
                        || !Arrays.equals(s1.get(i).z, s2.get(i).z)) { same = false; break; }
            }
        }
        check(same, "[6] 形态确定性（真实地形）", "两次 shape 不一致");
        // 形态仍在窗口内（窗口 ± radius，meander amp 6 + 过冲容差 10）
        boolean inBounds = true;
        for (var sh : s1) {
            for (int i = 0; i < sh.size(); i++) {
                if (sh.x[i] < cx - radius - 10 || sh.x[i] > cx + radius + 10
                        || sh.z[i] < cz - radius - 10 || sh.z[i] > cz + radius + 10) {
                    inBounds = false;
                }
            }
        }
        check(inBounds, "[7] 形态不越界（真实地形）", "有节点飞出窗口");
    }

    // ======================================================================
    // 共享判据
    // ======================================================================

    private static void validateTraces(List<DeterministicSkeleton.Trace> traces,
                                       double[] level, int[] parent,
                                       boolean[] lake, boolean[] sea, boolean[] resolved,
                                       int nx, int nz, int[] basinOutlet, String tag) {
        // [1] 单调性 + [2][3] 终止语义 + [4] 无双占
        int monoBad = 0, lakeBad = 0, seaBad = 0;
        int[] interiorHits = new int[level.length];
        for (var t : traces) {
            for (int i = 1; i < t.cell.length; i++) {
                if (level[t.cell[i]] > level[t.cell[i - 1]] + 1e-9) monoBad++;
            }
            int mouth = t.mouthCell();
            int mp = parent[mouth];
            if (t.end == DeterministicSkeleton.End.MOUTH_LAKE) {
                boolean ok = lake[mouth] || (mp >= 0 && mp < lake.length && lake[mp]);
                if (!ok) lakeBad++;
            }
            if (t.end == DeterministicSkeleton.End.MOUTH_SEA) {
                if (!sea[mouth] && !(mp >= 0 && mp < sea.length && sea[mp])) seaBad++;
            }
            // 内部节点（不含最后一个）双占检查
            for (int i = 0; i < t.cell.length - 1; i++) interiorHits[t.cell[i]]++;
        }
        int doubleHit = 0;
        for (int h : interiorHits) if (h > 1) doubleHit++;

        check(monoBad == 0, "[" + tag + "] [1] 单调下降（level 非升）",
                monoBad + " 处上升");
        check(lakeBad == 0, "[" + tag + "] [2] 入湖终止语义（止于湖岸、不穿湖）",
                lakeBad + " 条 end=MOUTH_LAKE 但下格不是湖");
        check(seaBad == 0, "[" + tag + "] [3] 入海终止语义", seaBad + " 条 end=MOUTH_SEA 但下格不是海");
        check(doubleHit == 0, "[" + tag + "] [4] 无双占（内部节点唯一）",
                doubleHit + " 个格是多条 trace 的内部节点");

        // [5] 溢口续流：每个有效 basinOutlet 是某 trace 的起点或内部节点
        if (basinOutlet != null) {
            int orphan = 0;
            StringBuilder detail = new StringBuilder();
            for (int o : basinOutlet) {
                if (o < 0 || o >= lake.length || lake[o] || sea[o]) continue;
                boolean covered = false;
                String asMouth = "";
                for (var t : traces) {
                    if (t.cell.length > 0 && t.cell[0] == o) { covered = true; break; }
                    for (int i = 1; i < t.cell.length - 1; i++) {
                        if (t.cell[i] == o) { covered = true; break; }
                    }
                    if (covered) break;
                    if (t.cell.length > 0 && t.cell[t.cell.length - 1] == o) {
                        asMouth = " 仅作他段末端(" + t.end + ")";
                    }
                }
                if (!covered) {
                    orphan++;
                    int mp = parent[o];
                    detail.append(String.format(
                            " [outlet o=(%d,%d) parent=%s parentLake=%b oResolved=%b level=%.2f%s]",
                            o / nz, o % nz, mp >= 0 ? "(" + (mp / nz) + "," + (mp % nz) + ")" : "-1",
                            mp >= 0 && mp < lake.length && lake[mp],
                            o < resolved.length && resolved[o], level[o], asMouth));
                }
            }
            check(orphan == 0, "[" + tag + "] [5] 溢口续流（溢口必有河起点）",
                    orphan + " 个溢口无 trace 覆盖:" + detail);
        }

        // ★★★ [8] 自交叉审计（2026-10-10，**有牙齿版**）★★★
        //
        // 【为什么不能测"链间交叉"（我犯过的错，如实记录）】
        //   第一版写的是"两条不同 trace 的线段是否交叉" ⇒ **恒为 0**：
        //   `covered[]` 保证「一个格只被一条链认领」⇒ 各链的格集合天然互斥
        //   ⇒ 格级线段结构上不可能相交。反向验证（临时关掉交叉守卫）仍 PASS
        //   ⇒ 证明该判据**测不出任何东西**（违反"判据必须有牙齿"）。
        //
        // 【真正的风险点】`covered` 只约束**跨链**互斥 —— **单条链自身仍可能折返自交**
        //   （skelDump 实测过：poly#8 在 z 655→669 段 14 点折返 4 次 = 图上"揉团"）。
        //   用户判据「河流/支流还会反复和其他分支交叉，类似重叠的情况」中，
        //   "同角度重叠"一类正来自这种自折返。⇒ 本判据测它。
        //
        // 【豁免】相邻段（共享端点）不算自交；间隔 ≤1 段的近邻不算（正常曲率）。
        int selfCross = 0;
        int selfTraces = 0;
        StringBuilder selfDetail = new StringBuilder();
        for (var t : traces) {
            int m = t.cell.length;
            if (m < 4) continue;
            int[] ix = new int[m], iy = new int[m];
            for (int i = 0; i < m; i++) { ix[i] = t.cell[i] / nz; iy[i] = t.cell[i] % nz; }
            boolean hit = false;
            for (int i = 1; i < m && !hit; i++) {
                for (int j = i + 2; j < m; j++) {           // j>=i+2 ⇒ 跳过共享端点的相邻段
                    if (segCross(ix[i - 1], iy[i - 1], ix[i], iy[i],
                                 ix[j - 1], iy[j - 1], ix[j], iy[j])) {
                        hit = true;
                        if (selfCross < 5) {
                            selfDetail.append(String.format(" [src(%d,%d) 段%d-%d 与 段%d-%d]",
                                    ix[0], iy[0], i - 1, i, j - 1, j));
                        }
                        break;
                    }
                }
            }
            if (hit) selfCross++;
            selfTraces++;
        }
        check(selfCross == 0, "[" + tag + "] [8] 单链无自交叉（折返重叠）",
                selfCross + "/" + selfTraces + " 条链存在自交:" + selfDetail);
    }

    /** 标准叉积相交判据（含共线接触；整点坐标 ⇒ 无浮点误差）。 */
    private static boolean segCross(int ax, int ay, int bx, int by,
                                    int cx, int cy, int dx, int dy) {
        long o1 = orient(ax, ay, bx, by, cx, cy);
        long o2 = orient(ax, ay, bx, by, dx, dy);
        long o3 = orient(cx, cy, dx, dy, ax, ay);
        long o4 = orient(cx, cy, dx, dy, bx, by);
        if (o1 == 0 && onSeg(ax, ay, bx, by, cx, cy)) return true;
        if (o2 == 0 && onSeg(ax, ay, bx, by, dx, dy)) return true;
        if (o3 == 0 && onSeg(cx, cy, dx, dy, ax, ay)) return true;
        if (o4 == 0 && onSeg(cx, cy, dx, dy, bx, by)) return true;
        return ((o1 > 0) != (o2 > 0)) && ((o3 > 0) != (o4 > 0));
    }

    private static long orient(int ax, int ay, int bx, int by, int cx, int cy) {
        return (long) (bx - ax) * (cy - ay) - (long) (by - ay) * (cx - ax);
    }

    private static boolean onSeg(int ax, int ay, int bx, int by, int px, int py) {
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx)
                && py >= Math.min(ay, by) && py <= Math.max(ay, by);
    }

    private static int idx(int i, int j, int nz) {
        return i * nz + j;
    }

    /** 把格链接成 parent（上游→下游）；链末（不在链内继续的）不改。 */
    private static void linkChain(int[] chain, int[] parent) {
        for (int i = 0; i < chain.length - 1; i++) {
            parent[chain[i]] = chain[i + 1];
        }
    }

    private SkeletonProbe() { }
}
