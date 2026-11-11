package com.geogenesis.worldgen.hydrology.model;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 宽度必须改变<b>实际覆盖</b>，而不只是覆盖判定之后返回的 half 数值。
 *
 * <h2>本探针钉住的三件事</h2>
 * <ol>
 *   <li><b>bank jitter 必须改变覆盖</b>（原断言，逐字保留）：同一 fixture 在不同 z 行上
 *       分别出现"jitter 本地放大"与"jitter 本地收窄"两种情形；</li>
 *   <li><b>边界 cap 必须压住 bank jitter 的扩宽</b>：小湖 {@code cap=0.5}、非最小 {@code cap=1.25}
 *       时半宽<b>先被 jitter 放大</b>再被 cap 截断；若覆盖判定漏掉 cap，覆盖会越限
 *       （命中距离落在 {@code (cap, jitteredHalf]} 区间）。未设 cap 时仍按 {@code bankHalf} 行为扩张；</li>
 *   <li><b>不是同源公式自证</b>：判据用<b>实测</b>覆盖边界（在单调列上二分出"覆盖 ↔ 不覆盖"的翻转点），
 *       再要求漏 cap 的预测在该边界上<b>给出不同结论</b>；两者若相同则直接判 FAIL（用例无区分力）。</li>
 * </ol>
 *
 * <h2>为什么用反射</h2>
 * <p>{@code SegmentIndex.widthLimits} 是包私有字段（生产由 {@code buildFromNodes} 从
 * {@code RiverWidthProfile} 的 envelope 上限填入）。探针用反射直接设置"逐段两端上限"，
 * 从而在<b>不跑生产图</b>的前提下拿到确定的 cap；被调用的仍是真实 {@code nearest()}，
 * 不是复制一份 {@code Math.min} 公式。</p>
 *
 * <p>无参数；PASS 打印 {@code WIDTH_COVERAGE_PASS} 并 {@code System.exit(0)}，
 * FAIL 抛 {@link AssertionError}（非零退出）。</p>
 */
public final class WidthCoverageProbe {

    /** 小湖接口 cap：等于最小可见半宽（{@code LakeInterfaceScale.MIN_HALF}）。 */
    private static final double CAP_SMALL = 0.5;

    /** 非最小的中间 cap：既压得住 jitter 放大，又明显高于最小可见半宽。 */
    private static final double CAP_MID = 1.25;

    /**
     * cap 用例的基准半宽。
     *
     * <p>取 4.9 而非更小值：{@code bankScale} 的世界尺度很长，在可用查询行上放大只有约 1.19 倍。
     * 若基准半宽小于约 4.2，jitter 后的半宽上不了 5.0，{@link #CAP_SMALL} 就不会比
     * {@link #CAP_MID} 更紧 ⇒ 两条 cap 用例退化成同一条、失去区分力
     * （{@code main} 里对两个实测边界之差有显式断言）。</p>
     */
    private static final double HALF_CAP_FIXTURE = 4.9;

    /** 单段的水平范围（世界块），两端都在 x∈[0,60] 上。 */
    private static final double SEG_X1 = 60.0;

    /**
     * 查询链的起点与 Tile 内上限。
     *
     * <p>构造器只把段注册进 tile 键 0（⇔ x∈[0,64) 且 y∈[0,64)），越界 {@code nearest} 直接返回
     * null；把越界当成"不覆盖"会把整条链判成全不覆盖。故查询必须留在 {@code [0,64)} 内。</p>
     */
    private static final int LO_X = 20;
    private static final int TILE_END = 63;

    private WidthCoverageProbe() { }

    public static void main(String[] args) throws Exception {
        int[] legacy = checkJitterCoverageCounts();          // 原断言 + 原计数
        CoverageBox small = checkCapHolds(CAP_SMALL, "小湖 cap=0.5");
        CoverageBox mid = checkCapHolds(CAP_MID, "非最小 cap=1.25");
        int uncapped = checkUncappedStillExpands();

        System.out.println("WIDTH_COVERAGE_PASS expanded=" + legacy[0] + " narrowed=" + legacy[1]
                + " capSmallBoundary=" + fmt(small.boundary)
                + " capMidBoundary=" + fmt(mid.boundary)
                + " capSmallNoCapBoundary=" + fmt(small.jitterOnlyBoundary)
                + " capMidNoCapBoundary=" + fmt(mid.jitterOnlyBoundary)
                + " uncappedExpanded=" + uncapped);
        System.exit(0);
    }

    // ---- ① 原断言：bank jitter 真的改变覆盖（放大 / 收窄都出现）----

    /**
     * 原用例逐字保留：逐段半宽都写 2.0，期望 {@code effective >= 2} 即为覆盖。
     *
     * @return {@code {expanded, narrowed}}（原报告的计数口径）
     */
    private static int[] checkJitterCoverageCounts() throws Exception {
        int expanded = 0, narrowed = 0;
        for (int z = 8; z <= 55; z++) {
            double half = 2.0;
            var index = newIndex(new double[]{0, z, SEG_X1, z, half, half, 1, 1});
            for (int x = 5; x <= 55; x++) {
                double effective = ModelHydrologyProvider.SegmentIndex.bankHalf(half, x, z + 2);
                Object hit = index.nearest(x, z + 2);
                boolean expected = effective >= 2;
                if ((hit != null) != expected) {
                    throw new AssertionError("coverage ignores effective width at " + x + "," + (z + 2));
                }
                if (expected) {
                    expanded++;
                    double actual = halfOf(hit);
                    if (Math.abs(actual - effective) > 1e-12) {
                        throw new AssertionError("coverage/returned half differ");
                    }
                } else {
                    narrowed++;
                }
            }
        }
        if (ModelHydrologyProvider.BANK_JITTER > 0 && (expanded == 0 || narrowed == 0)) {
            throw new AssertionError("fixture did not cover local widening and narrowing");
        }
        return new int[]{expanded, narrowed};
    }

    // ---- ② 新断言：边界 cap 压住 bank jitter 的扩宽 ----

    /**
     * 一次 cap 用例的实测结论。
     *
     * @param boundary             设了 cap 的实测覆盖边界（到段的世界距离）
     * @param jitterOnlyBoundary   同一行上"漏掉 cap"（纯 jitter）会给出的覆盖边界
     */
    private record CoverageBox(double boundary, double jitterOnlyBoundary) { }

    /**
     * 选一行 jitter 确实把半宽顶到 cap 之上，再实测覆盖边界并与"漏 cap"的预测比对。
     *
     * <p>fixture：单段沿 x 方向（z 固定），两端半宽与两端 cap 分别为 {@link #HALF_CAP_FIXTURE} / {@code cap}；
     * 查询行取 z+2（距段 2 block，远超 {@code segmentMeetsBlock} 的 ±0.5 盒）
     * ⇒ 覆盖<b>只能</b>由半宽决定，半宽即判据。</p>
     */
    private static CoverageBox checkCapHolds(double cap, String label) throws Exception {
        int bestZ = -1;
        double bestGap = 0;
        for (int z = 8; z <= 55; z++) {
            for (int x = 25; x <= 35; x++) {
                double gap = jitteredHalf(x, z + 2) - cap;
                if (gap > bestGap) {
                    bestGap = gap;
                    bestZ = z;
                }
            }
        }
        if (bestZ < 0) {
            throw new AssertionError(label + ": 未找到 jitter 后越限的位置（jitter 没有真正扩宽，用例无区分力）");
        }
        CoverageBox box = measureBoundary(bestZ, cap, label);
        if (!(box.jitterOnlyBoundary > box.boundary + 1e-6)) {
            throw new AssertionError(label + ": 漏掉 cap 的覆盖边界没有更远 ⇒ 用例无区分力 ("
                    + fmt(box.boundary) + " vs " + fmt(box.jitterOnlyBoundary) + ")");
        }
        return box;
    }

    /**
     * 逐列扫描实测"覆盖 ↔ 不覆盖"的翻转点，并用<b>真实 {@code nearest()} 结果</b>反向校验两种预测。
     *
     * <p>不假设实现内部公式、也不假设单调性：直接沿查询链<b>全扫</b>
     * （{@code x = LO_X … TILE_END}，数据行固定），取最后一个被覆盖的列作为实测边界。</p>
     *
     * <h2>★ 为什么查询距离随 cap 变（0.5 → 0，1.25 → 1）</h2>
     * <p>覆盖判据含 {@code dist <= half}，而 capped 半宽被 cap 精确限住。若一律用 dist=2：
     * {@code cap=0.5} 时 {@code 2 > 0.5} ⇒ 即使 cap 完全正确也判"不覆盖"（会把正确的
     * 实现误判成缺陷）；若一律用 dist=0：两个 cap 都必然覆盖 ⇒ 又失去区分力。</p>
     * <p>故取 {@code dist = floor(cap)}：既保证 {@code dist ≤ cap}（覆盖可达），
     * 又让 {@code dist > 0.5} 的第二个 cap 真的被 cap 限制住（jittered 远大于它）。
     * jitter 收窄到 0.5 也不会破坏——那只会让边界更近，仍满足"覆盖 ⟺ dist ≤ cappedHalf"。</p>
     */
    private static CoverageBox measureBoundary(int z, double cap, String label) throws Exception {
        var index = newIndex(new double[]{0, z, SEG_X1, z, HALF_CAP_FIXTURE, HALF_CAP_FIXTURE, 1, 1});
        setLimits(index, new double[]{cap, cap});
        var raw = newIndex(new double[]{0, z, SEG_X1, z, HALF_CAP_FIXTURE, HALF_CAP_FIXTURE, 1, 1});
        int dist = queryDistanceFor(cap);
        int y = z + dist;
        // ⚠ 查询范围必须留在构造器注册的那个 64 块 tile 内（tile 键 0 ⇔ x,y ∈ [0,64)），
        //   越界会直接返回 null，把"没有候选"错当成"不覆盖"。
        int lastCapped = -1, lastRaw = -1;
        for (int x = LO_X; x <= TILE_END; x++) {
            if (index.nearest(x, y) != null) lastCapped = x;
            if (raw.nearest(x, y) != null) lastRaw = x;
        }
        if (lastCapped < 0) {
            throw new AssertionError(label + " z=" + z + " dist=" + dist
                    + ": 整条查询链都不覆盖（fixture 失效）");
        }
        if (lastRaw < 0) {
            throw new AssertionError(label + " z=" + z + ": 未设 cap 时也不覆盖 ⇒ 用例无区分力");
        }
        double boundary = distance(lastCapped, y, z);
        double cappedLast = cappedHalf(cap, lastCapped, y);
        if (!(cappedLast >= boundary - 1e-9)) {
            throw new AssertionError(label + " z=" + z + ": 列 x=" + lastCapped + " 被覆盖但 cappedHalf("
                    + fmt(cappedLast) + ") < 距离(" + fmt(boundary) + ") ⇒ 覆盖越限（cap 未压住 jitter）");
        }
        // 边界之外必须真的不再覆盖（否则上面那个"最后一个"没有意义）
        if (lastCapped < TILE_END && index.nearest(lastCapped + 1, y) != null) {
            throw new AssertionError(label + " z=" + z + ": 边界不唯一（扫描逻辑失效）");
        }
        // ★ 区分力：漏掉 cap 的覆盖边界必须更远，否则本 cap 用例测不出任何东西。
        double jitterOnly = distance(lastRaw, y, z);
        if (!(jitterOnly > boundary + 1e-6)) {
            throw new AssertionError(label + " z=" + z + ": 漏掉 cap 的边界没有更远 ("
                    + fmt(boundary) + " vs " + fmt(jitterOnly) + ") ⇒ 用例无区分力");
        }
        return new CoverageBox(boundary, jitterOnly);
    }

    /** 覆盖可达又必须被 cap 限制住的查询距离：{@code floor(cap)}（0.5 → 0，1.25 → 1）。 */
    private static int queryDistanceFor(double cap) {
        return (int) Math.floor(cap);
    }

    // ---- ③ 未设 cap 时仍按 bankHalf 行为扩宽 ----

    /**
     * 不设 {@code widthLimits}（=生产默认 6.0 上限）时，jitter 的放大部分必须照常生效：
     * 覆盖与否按 {@code bankHalf} 判断，返回半宽也必须等于它。
     *
     * @return 实际被"仅因 jitter 放大"而纳入覆盖的列数（用于证明用例有量）
     */
    private static int checkUncappedStillExpands() throws Exception {
        int expanded = 0;
        for (int z = 8; z <= 55 && z + 2 < 64; z++) {
            var index = newIndex(new double[]{0, z, SEG_X1, z, HALF_CAP_FIXTURE, HALF_CAP_FIXTURE, 1, 1});
            for (int x = LO_X; x <= TILE_END; x++) {
                double jittered = jitteredHalf(x, z + 2);
                if (jittered <= HALF_CAP_FIXTURE) continue;          // 只看被放大的列
                double dist = distance(x, z + 2, z);
                if (jittered < dist + 0.5) continue;                 // 放大后才覆盖的窄带
                Object hit = index.nearest(x, z + 2);
                if (hit == null) {
                    throw new AssertionError("未设 cap 时 bank jitter 的扩宽必须生效，但 " + x + "," + (z + 2)
                            + " 未被覆盖 (jittered=" + fmt(jittered) + " dist=" + fmt(dist) + ")");
                }
                expanded++;
                if (Math.abs(halfOf(hit) - jittered) > 1e-9) {
                    throw new AssertionError("未设 cap 时返回半宽必须等于 bankHalf："
                            + fmt(halfOf(hit)) + " vs " + fmt(jittered));
                }
            }
        }
        if (expanded < 5) {
            throw new AssertionError("未设 cap 的扩宽样本过少（" + expanded + "），无区分力");
        }
        return expanded;
    }

    // ---- 工具 ----

    /** 与生产同一行为：{@code clampHalf(half * bankScale(x,z))}（段端点半宽恒定）。 */
    private static double jitteredHalf(int x, int y) {
        return ModelHydrologyProvider.SegmentIndex.bankHalf(HALF_CAP_FIXTURE, x, y);
    }

    /** 正确实现应给出的半宽：先 jitter 再被 cap 截断（{@code nearest} 内部 {@code min(limit, …)} 的语义）。 */
    private static double cappedHalf(double cap, int x, int y) {
        return Math.min(cap, jitteredHalf(x, y));
    }

    /** 查询点到水平段 {@code (0,z)-(60,z)} 的距离。 */
    private static double distance(int x, int y, int segZ) {
        double t = Math.max(0, Math.min(1, x / SEG_X1));
        return Math.hypot(x - t * SEG_X1, y - segZ);
    }

    /** 反射设置逐段两端上限：{@code {limit0, limit1}}（包私有字段，生产由 buildFromNodes 填）。 */
    private static void setLimits(Object index, double[] limits) throws Exception {
        Field f = index.getClass().getDeclaredField("widthLimits");
        f.setAccessible(true);
        f.set(index, limits);
    }

    private static ModelHydrologyProvider.SegmentIndex newIndex(double[] seg) throws Exception {
        Class<?> type = ModelHydrologyProvider.SegmentIndex.class;
        Constructor<?> ctor = type.getDeclaredConstructor(double[].class, Map.class);
        ctor.setAccessible(true);
        return (ModelHydrologyProvider.SegmentIndex) ctor.newInstance(seg, tilesForProbe());
    }

    /**
     * ★ 段必须注册在 <b>x 与 y 两个方向</b>都能命中的 tile 里。
     *
     * <p>{@code nearest} 用 {@code (floorDiv(x,64)<<32) ^ floorDiv(y,64)} 查候选表，
     * 而 tile 键是高 32 位放 x、低 32 位放 y。旧用例把段塞进 {@code 0L}（= x∈[0,64) 且 y∈[0,64)），
     * 但查询点的 x 可达 55，于是 {@code floorDiv(55,64)=0} 仍为 0 —— 问题在于
     * <b>查询 y=z+2 当 z≥63 时才跨 tile</b>；真正致命的是旧用例把 {@code 0L} 当"唯一 tile"写死，
     * 一旦查询落在别的 tile 就必然返回 null，覆盖断言便退化成"只看 effective 数值"。
     * 这里按查询范围 {@code x∈[0,64), y∈[0,64)} 显式注册，保证 {@code nearest} 真的返回候选。</p>
     */
    private static Map<Long, List<Integer>> tilesForProbe() {
        Map<Long, List<Integer>> tiles = new HashMap<>();
        tiles.put(tileKey(20, 20), List.of(0));           // 段的代表性格；下方按需补齐
        for (int tx = 0; tx <= 1; tx++) {
            for (int ty = 0; ty <= 1; ty++) tiles.putIfAbsent(tileKey(tx * 64 + 1, ty * 64 + 1), List.of(0));
        }
        return tiles;
    }

    private static long tileKey(int x, int y) {
        return ((long) Math.floorDiv(x, 64) << 32) ^ (Math.floorDiv(y, 64) & 0xffffffffL);
    }

    private static Method halfGetter;

    private static double halfOf(Object hit) throws Exception {
        if (halfGetter == null) {
            halfGetter = hit.getClass().getDeclaredMethod("half");
            halfGetter.setAccessible(true);
        }
        return (double) halfGetter.invoke(hit);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
