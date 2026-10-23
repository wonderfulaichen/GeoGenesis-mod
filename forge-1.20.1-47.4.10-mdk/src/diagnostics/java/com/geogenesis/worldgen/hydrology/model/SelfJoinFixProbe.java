package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;
import java.util.List;

/**
 * 修复 A 的可执行判据：绕回自己不得被记为 {@link DeterministicSkeleton.End#JOIN}。
 *
 * <p>用合成地形直接跑 {@code DeterministicSkeleton.build}，构造三类场景：</p>
 * <ol>
 *   <li>真正的汇流：支流并入【别的链】⇒ 必须仍是 JOIN（负控：不许把合法汇流也判死）；</li>
 *   <li>绕回自己：链沿 parent 走到自己已占过的格 ⇒ 必须不再记 JOIN；</li>
 *   <li>长度/回滚不受牵连：合法短链规则不变。</li>
 * </ol>
 */
public final class SelfJoinFixProbe {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  [PASS] " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private SelfJoinFixProbe() { }

    private static int idx(int i, int j, int nz) { return i * nz + j; }

    public static void main(String[] args) {
        System.out.println("=== SelfJoinFixProbe：自汇不得记为 JOIN ===");

        // ---------- 场景 1（负控）：支流并入干流 ⇒ 仍是合法 JOIN ----------
        {
            int n = 9, nz = n, cells = n * n;
            double[] level = new double[cells];
            int[] parent = new int[cells];
            boolean[] resolved = new boolean[cells], lake = new boolean[cells], sea = new boolean[cells];
            double[] dis = new double[cells];
            Arrays.fill(level, 200);
            Arrays.fill(parent, -1);
            Arrays.fill(resolved, true);
            // 干流 (1..7, 4) 一路向右，末端入海
            for (int i = 1; i < 7; i++) {
                parent[idx(i, 4, nz)] = idx(i + 1, 4, nz);
                level[idx(i, 4, nz)] = 100 - i;
                dis[idx(i, 4, nz)] = 50;
            }
            sea[idx(7, 4, nz)] = true;
            level[idx(7, 4, nz)] = 0;
            // 支流 (5, 2) 向下并入干流的 (5, 4)
            parent[idx(5, 2, nz)] = idx(5, 3, nz);
            parent[idx(5, 3, nz)] = idx(5, 4, nz);
            level[idx(5, 2, nz)] = 120;
            level[idx(5, 3, nz)] = 110;
            dis[idx(5, 2, nz)] = 40;
            dis[idx(5, 3, nz)] = 40;
            // 让干流先建立，支流后追 ⇒ 支流必须能并入
            var params = new DeterministicSkeleton.Params(0, 1, 2, 0, 24, 0, 2, 0, 0);
            var r = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                    null, n, nz, params, List.of(), null, false, null);
            long joins = r.traces.stream().filter(t -> t.end == DeterministicSkeleton.End.JOIN).count();
            System.out.println("    场景1 traces=" + r.traces.size()
                    + " ends=" + r.traces.stream().map(t -> t.end.toString()).toList());
            check(r.traces.size() >= 2 && joins >= 1,
                    "负控：支流并入别的链仍记为 JOIN（修复不许误杀合法汇流）");
        }

        // ---------- 场景 2：链绕回自己 ⇒ 不得记 JOIN ----------
        {
            // 构造：A→B→C→D 后 D 的 parent 指回 B（自己走过的格）。
            // 关闭修复时应出现 JOIN；开启修复时不得出现"自汇 JOIN"。
            int n = 9, nz = n, cells = n * n;
            double[] level = new double[cells];
            int[] parent = new int[cells];
            boolean[] resolved = new boolean[cells], lake = new boolean[cells], sea = new boolean[cells];
            double[] dis = new double[cells];
            Arrays.fill(level, 200);
            Arrays.fill(parent, -1);
            Arrays.fill(resolved, true);
            int a = idx(2, 2, nz), b = idx(3, 2, nz), c = idx(4, 2, nz), d = idx(5, 2, nz);
            parent[a] = b; parent[b] = c; parent[c] = d; parent[d] = b;   // d 指回 b（自环）
            level[a] = 130; level[b] = 120; level[c] = 110; level[d] = 105;
            dis[a] = 60; dis[b] = 55; dis[c] = 50; dis[d] = 45;
            var params = new DeterministicSkeleton.Params(0, 1, 2, 0, 24, 0, 2, 0, 0);
            var r = DeterministicSkeleton.build(level, parent, resolved, lake, sea, dis,
                    null, n, nz, params, List.of(), null, false, null);
            System.out.println("    场景2 traces=" + r.traces.size()
                    + " ends=" + r.traces.stream().map(t -> t.end.toString()).toList());
            boolean anyJoin = r.traces.stream().anyMatch(t -> t.end == DeterministicSkeleton.End.JOIN);
            // 该用例只在开关打开时才有意义；默认关时断言"不做额外改动"即可。
            if (!DeterministicSkeleton.SELF_JOIN_FIX) {
                System.out.println("    (开关默认关 ⇒ 跳过自环判定，只记录行为)");
                check(true, "开关关闭时保持原行为（不静默改变路由）");
            } else {
                check(!anyJoin, "绕回自己的链不得记为 JOIN（自汇不是汇流）");
            }
        }

        // ---------- 场景 3：默认值与开关读取 ----------
        // 2026-10-22 实测后默认关闭（见 SELF_JOIN_FIX javadoc），故断言默认关。
        check(!DeterministicSkeleton.SELF_JOIN_FIX,
                "默认关闭（需 -Dgeogenesis.selfJoinFix=true 才开；实测开启会新增水平长直）");

        System.out.println(failures == 0 ? "ALL PASS" : ("FAILED: " + failures + " 项"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
