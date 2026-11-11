package com.geogenesis.worldgen.hydrology.model;

/**
 * 接力起点容错（移植 legacy bestHandoffStart）的可执行判据。
 *
 * <p>只测纯函数 {@link DeterministicSkeleton#bestHandoffStart}：
 * 种子有下坡必须原样保留（不能乱挪），种子在局部极小必须能被救出（唯一目的），
 * 救不出时必须返回原格（不伪造），并避开已占用格。</p>
 *
 * <p>★ 判据是【坡降率】，不是"候选 bed 必须比坑底更低"——坑底通常就是局部最低点，
 * 后者永远选不出。这条正是第一版实现踩的坑，用例必须能抓住它。</p>
 */
public final class HandoffStartProbe {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  [PASS] " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private HandoffStartProbe() { }

    private static int idx(int i, int j, int nz) { return i * nz + j; }

    public static void main(String[] args) {
        System.out.println("=== HandoffStartProbe：接力起点容错 ===");
        int n = 7, nz = n, cells = n * n;

        // ① 陡坡：种子本身有严格下坡 ⇒ 必须原样返回（不得乱挪）。
        double[] slope = new double[cells];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) slope[idx(i, j, nz)] = 100 - i * 3 - j;
        int seedA = idx(3, 3, nz);
        check(DeterministicSkeleton.bestHandoffStart(seedA, slope, n, nz, new boolean[cells]) == seedA,
                "种子格有严格下坡时原样保留（不乱挪起点）");

        // ② 局部极小 —— 这是唯一要治的场景。
        //    中心 (3,3)=10 是坑底（8 邻全更高，且高于坑底）；
        //    (4,3)=11 自身无下坡（(5,3)=20 更高），(2,3)=12 自身无下坡；
        //    (3,4)=13 自身无下坡；只有 (3,2)=14 → (3,1)=4 有真实下坡。
        //    要求：救出到 (3,2)，而不是因为"它比坑底高"就放弃。
        double[] pit = new double[cells];
        java.util.Arrays.fill(pit, 90);
        int seedB = idx(3, 3, nz);
        pit[seedB] = 10;
        pit[idx(4, 3, nz)] = 11;  pit[idx(5, 3, nz)] = 20;
        pit[idx(2, 3, nz)] = 12;  pit[idx(1, 3, nz)] = 22;
        pit[idx(3, 4, nz)] = 13;  pit[idx(3, 5, nz)] = 23;
        int healer = idx(3, 2, nz);
        pit[healer] = 14;         pit[idx(3, 1, nz)] = 4;   // 唯一有下坡的方向
        int outB = DeterministicSkeleton.bestHandoffStart(seedB, pit, n, nz, new boolean[cells]);
        System.out.println("    pit seed=" + seedB + " chosen=" + outB + " expected=" + healer);
        check(DeterministicSkeleton.strictD8Next(seedB, pit, n, nz) < 0,
                "负控：该种子在原判据下确实无下坡（否则用例无效）");
        check(outB == healer,
                "局部极小种子被救出到唯一「自身有下坡」的邻格（即使它比坑底高）");

        // ③ 救不出：整片平台 ⇒ 必须如实返回原格，绝不伪造可走起点。
        double[] flat = new double[cells];
        java.util.Arrays.fill(flat, 42);
        int seedC = idx(2, 4, nz);
        check(DeterministicSkeleton.bestHandoffStart(seedC, flat, n, nz, new boolean[cells]) == seedC,
                "平台无法救出时返回原格（不伪造终点）");

        // ④ 改选必须避开已占用格（否则起点落地即 JOIN，白追）。
        boolean[] covered = new boolean[cells];
        covered[healer] = true;
        int outD = DeterministicSkeleton.bestHandoffStart(seedB, pit, n, nz, covered);
        check(outD != healer, "已占用的候选被跳过");

        // ⑤ 边界安全：角落种子不得越界。
        int corner = idx(0, 0, nz);
        double[] bowl = new double[cells];
        java.util.Arrays.fill(bowl, 50);
        bowl[corner] = 10;
        bowl[idx(0, 1, nz)] = 11;
        bowl[idx(0, 2, nz)] = 5;
        int outE = DeterministicSkeleton.bestHandoffStart(corner, bowl, n, nz, new boolean[cells]);
        check(outE >= 0 && outE < cells, "角落种子改选后仍在窗口内");

        // ⑥ 确定性：同输入两次调用结果相同。
        check(DeterministicSkeleton.bestHandoffStart(seedB, pit, n, nz, new boolean[cells])
                        == DeterministicSkeleton.bestHandoffStart(seedB, pit, n, nz, new boolean[cells]),
                "同输入两次调用结果一致（确定性）");

        // ⑦ bed 为 null（调用方未提供）时必须原样返回，不得 NPE。
        check(DeterministicSkeleton.bestHandoffStart(seedB, null, n, nz, new boolean[cells]) == seedB,
                "bed=null 时原样返回（不 NPE）");

        System.out.println(failures == 0 ? "ALL PASS" : ("FAILED: " + failures + " 项"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
