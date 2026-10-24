package com.geogenesis.worldgen.hydrology.model;

/**
 * 湖岸出水判据探针 —— 纯函数复算，不启动 MC、不改任何行为。
 *
 * <p>契约：<b>provider 认定的湖格，在逐块放水后必须真的能放下一整块水</b>。
 * 判据链是两段式的：</p>
 * <ol>
 *   <li><b>湖域</b>：{@code height < spill − 1e-9}（水面严格高于地面）；</li>
 *   <li><b>水柱保护</b>：若 {@code floor(height) == floor(spill)}，把地面压到
 *       {@code floor(spill) − 1e-9} 以下。</li>
 * </ol>
 * <p>只做 ① 而不做 ② 会得到「湖域为真但放不下水块」的干格；<b>第一版改动正是如此</b>，
 * 整图实测 −2508 格湖→干、孤立河 17→22，已被否证。本探针把这条否证固化为断言。</p>
 */
public final class ShoreCriterionProbe {

    /** 旧判据（改前）：要求水柱至少半格。 */
    static boolean domainOld(double height, double spill) {
        return height < spill - 0.5;
    }

    /** 候选判据（改后）：湖域只要求水面严格高于地面。 */
    static boolean domainNew(double height, double spill) {
        return height < spill - 1e-9;
    }

    /**
     * 完整落块链：返回该列最终地面高度（<b>含</b>水柱保护），-1 表示不出水。
     * 与 {@code GeoGenesisTerrain} 湖列分支逐字同构。
     */
    static double applyColumn(double height, double spill, boolean useProtection) {
        if (!domainNew(height, spill)) return -1.0;
        double placed = height;
        if (useProtection && placed >= Math.floor(spill) - 1e-9) {
            placed = Math.min(spill - 0.75, Math.floor(spill) - 1e-9);
        }
        return placed;
    }

    /** 该列实际能放几个水块。 */
    static long waterBlocks(double placed, double spill) {
        if (placed < 0) return 0;
        long first = (long) Math.floor(placed) + 1;
        long last = (long) Math.floor(spill);
        return Math.max(0, last - first + 1);
    }

    private static int checks = 0;

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // ① 候选链的明确契约：凡是湖域为真的列，都至少有 1 个水块
        int wet = 0, dry = 0;
        for (double height = 60.0; height <= 72.0; height += 0.01) {
            for (double spill = height; spill <= height + 2.0; spill += 0.01) {
                double placed = applyColumn(height, spill, true);
                long blocks = waterBlocks(placed, spill);
                if (domainNew(height, spill)) {
                    check(blocks >= 1, "lake column without water block: h=" + height + " s=" + spill);
                    wet++;
                } else {
                    check(blocks == 0, "non-lake column got water: h=" + height + " s=" + spill);
                    dry++;
                }
            }
        }
        check(wet > 100000 && dry > 0, "coverage too small wet=" + wet + " dry=" + dry);

        // ② 单调性：候选湖域必须包含旧湖域（不得把原本有水的湖岸改干）
        int lost = 0, gained = 0;
        for (double height = 60.0; height <= 72.0; height += 0.01) {
            for (double spill = 60.0; spill <= 72.0; spill += 0.01) {
                boolean old = domainOld(height, spill);
                boolean now = domainNew(height, spill);
                if (old && !now) lost++;
                if (!old && now) gained++;
            }
        }
        check(lost == 0, "candidate dropped previously-wet lake columns: " + lost);
        check(gained > 0, "candidate changed nothing: " + gained);

        // ③ 反向验证 A：第一版（只放宽湖域、不要水柱保护）必须被抓出干湖格
        int firstVariantDry = 0;
        for (double height = 60.0; height <= 72.0; height += 0.01) {
            for (double spill = height; spill <= height + 2.0; spill += 0.01) {
                double placed = applyColumn(height, spill, false);
                if (domainNew(height, spill) && waterBlocks(placed, spill) == 0) firstVariantDry++;
            }
        }
        check(firstVariantDry > 0, "rejected variant not detected: no dry lake columns found");

        // ④ 反向验证 B：旧判据必须与方块放置不等价（否则修复无意义）
        int oldDivergence = 0;
        for (double height = 60.0; height <= 72.0; height += 0.01) {
            for (double spill = height; spill <= height + 1.0; spill += 0.01) {
                boolean oldWet = domainOld(height, spill)
                        && waterBlocks(applyColumn(height, spill, true), spill) >= 1;
                boolean nowWet = waterBlocks(applyColumn(height, spill, true), spill) >= 1;
                if (oldWet != nowWet) oldDivergence++;
            }
        }
        check(oldDivergence > 0, "old criterion was equivalent: fix would be a no-op");

        // ⑤ 真实缺陷样本（独立取证的 MOUTH_LAKE 终点格，像素实测为干/河）
        double[][] realCases = {
                {-522, 211.13684796837902, 211.2256590556974},
                {-406, 169.53192453953534, 169.98734118366428},
                {-392, 170.4708793501272, 170.9671445070248},
                {-168, 197.54093023572682, 197.92416121531218},
                {-158, 192.26346125409995, 192.4810052558375},
        };
        for (double[] c : realCases) {
            check(domainOld(c[1], c[2]) != domainNew(c[1], c[2]),
                    "real sample does not exercise the change at x=" + c[0]);
            long blocks = waterBlocks(applyColumn(c[1], c[2], true), c[2]);
            check(blocks >= 1, "real sample still dry at x=" + c[0]);
        }

        // ⑥ 边界：地面恰等于 spill 时不出水（不得凭空抬高水面）
        check(!domainNew(211.0, 211.0), "ground at lake level must stay dry");
        // ⑦ 边界：地面已在 floor(spill)−1 以下时保持原地面（保护不得再挖）
        double low = Math.floor(211.0) - 1.0;
        check(applyColumn(low, 211.0, true) == low, "protection must not deepen an already-wet column");

        System.out.printf("PASS ShoreCriterionProbe checks=%d wetColumns=%d gained=%d "
                        + "rejectedVariantDry=%d oldDivergence=%d%n",
                checks, wet, gained, firstVariantDry, oldDivergence);
        System.exit(0);
    }

    private ShoreCriterionProbe() { }
}
