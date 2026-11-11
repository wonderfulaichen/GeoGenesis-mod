package com.geogenesis.worldgen.hydrology.model;

/**
 * 边界带端点反例：低侧与高侧必须对称包含端点，否则同一接缝行会被两侧同时排除。
 *
 * <p>只用纯算术，不依赖生产缓存或随机地形；生产实际漏列由 ProductionOwnershipProbe 取证。</p>
 */
public final class SeamBandBoundaryProbe {

    private static final int LO = 512;
    private static final int HI = LO + 512;
    private static final int HALO = 96;
    /** 邻区窗口最后一行：originZ=-96 与半径 352、STEP 2 ⇒ -96 + 352*2。 */
    private static final int BOUNDARY = 608;

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  [PASS] " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private SeamBandBoundaryProbe() { }

    /** 生产旧判据：低侧 `<`、高侧 `>=`。 */
    static boolean oldBand(int v) {
        return v < LO + HALO || v >= HI - HALO;
    }

    /** 生产新判据：两侧都闭区间。 */
    static boolean newBand(int v) {
        return v <= LO + HALO || v >= HI - HALO;
    }

    /** 相邻 region 的侧向过滤（低侧偏移 -1）。旧为 `>=`，新为 `>`。 */
    static boolean oldSideFilterSkips(int v) {
        return v >= LO + HALO;
    }

    static boolean newSideFilterSkips(int v) {
        return v > LO + HALO;
    }

    public static void main(String[] args) {
        System.out.println("=== SeamBandBoundaryProbe：边界带端点对称性 ===");
        check(BOUNDARY == LO + HALO, "接缝端点 608 恰为 regLoZ+HALO");
        check(BOUNDARY == -96 + 352 * 2, "该行正是北区窗口（originZ=-96）能画到的最后一行");

        check(!oldBand(BOUNDARY), "负控：旧 `<` 判据把 z=608 排除在带外（生产 294 列漏列的直接原因）");
        check(newBand(BOUNDARY), "闭区间判据纳入 z=608");

        check(newBand(HI - HALO), "高侧带端点仍纳入（不得为修低侧而收窄高侧）");
        check(newBand(LO) && newBand(HI - 1), "region 两端极限值都在带内，无空洞");
        check(!newBand(LO + HALO + 1), "带外一格仍不触发邻区查询（不把局部补缝扩成整窗并集）");

        check(oldSideFilterSkips(BOUNDARY), "负控：旧侧向过滤 `>=` 在端点仍跳过低侧邻区");
        check(!newSideFilterSkips(BOUNDARY), "侧向过滤 `>` 后端点可取低侧邻区");
        check(!newBand(LO + HALO + 1), "端点更内一格不进带，侧向过滤不参与（不会多取邻区）");

        System.out.println(failures == 0 ? "ALL PASS" : ("FAILED: " + failures + " 项"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
