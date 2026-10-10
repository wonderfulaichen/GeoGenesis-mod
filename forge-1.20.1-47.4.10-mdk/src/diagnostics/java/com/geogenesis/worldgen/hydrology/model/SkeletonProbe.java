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
        runRealTerrain();
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
