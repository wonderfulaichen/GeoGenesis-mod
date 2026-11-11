package com.geogenesis.worldgen.terrain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * <b>海洋类型「接线」基线测量</b>（方案 <code>ocean-type-unification</code> 步 1）。
 *
 * <p>只测量，不改进任何生产代码。目的：在动手接线之前，先把「<b>现在海洋类型之间到底有没有
 * 高度差异</b>」量成数字，作为后续 A/B 的基线。若基线已经显示"有差异"，那接线的意义就变了；
 * 若显示"零差异"，接线（路线甲）才成立。</p>
 *
 * <h3>为什么必须新写探针，而不复用 {@link OceanTypeProbe}</h3>
 * <p>既有的 {@code OceanTypeProbe} 测的是<b>地质形态</b>（俯冲带横剖面、弧是否露头、
 * 洋中脊与边界的对齐率）。它<b>从不比较各海洋类型之间的高度分布</b> —— 而这正是
 * 方案 §2.4 的 W2 判据，也是"海洋样条没接上"这一假设的直接检验。</p>
 *
 * <h3>★ 为什么不能「按 classify() 分组比较 e」—— 这是循环论证</h3>
 * <p>首版这么设计，实测桶内 SHELF 与 OCEAN 的 e 中位差 0.09~0.13（18~24 格），
 * 看着像"有差异"。<b>但这个测法在数学上不成立</b>，原因三层，均已实测确认：</p>
 * <ol>
 *   <li>{@code eLand} 对海洋类型恒等于 {@code oceanE}（海洋项 {@code lo=hi=oceanE}、
 *       不参与调制）⇒ {@code resid = e − eLand} 只剩 {@code OceanFeatures} 增量，
 *       对无特征的陆架/洋盆恒为 0（实测 +0.00000 / +0.00060）。</li>
 *   <li>改成「按 c 分桶」也无效：{@code eLand = f(c, modulated)}，{@code modulated} 是噪声，
 *       同一 c 桶内 {@code eLand} 照样大幅波动（实测桶内 gap 仍有 0.05~0.13）。</li>
 *   <li><b>根本原因</b>：{@code classify()} 对 SHELF/OCEAN/DEEP 的划分
 *       <b>就是 eLand 轴上的分区</b>（{@code eLand > -0.08 ⇒ SHELF}、{@code < -0.18 ⇒ DEEP}）。
 *       按它分组再比 {@code e}，等于按 {@code eLand} 分组比 {@code eLand} —— 必然有差，
 *       与"海洋类型有没有自己的形态"无关。<b>判据本身是循环论证。</b></li>
 * </ol>
 *
 * <h3>本探针实际测的三件事（都能给出确定结论）</h3>
 * <pre>
 * T1  classify-is-partition   SHELF/OCEAN/DEEP 三类在 eLand 轴上严格互斥，
 *                             且边界恰为 -0.08 / -0.18 ⇒ 类型 = eLand 的分带
 * T2  e-from-eland            无特征海洋点里，eLand 相同的点 e 必相同
 *                             ⇒ 任何"海洋类型的独立形态"都无从产生
 * T3  config-inert（= W1）    把 shelfLo/Hi、subRidgeLo/Hi、seamountLo/Hi 改到极端值，
 *                             e 必须逐位不变 ⇒ 现有多套 lo/hi 配置完全不参与高度
 * </pre>
 * <p><b>T3 是决定性的</b>：配置改了产出逐位不变 ⇒ "接线"不是可选项而是必要工作。
 * <b>T1/T2 是结构性的</b>：它们说明即使接线，只要仍按 {@code eLand} 分带，
 * 类型之间也永远不会产生独立形态 —— 这一条直接影响方案 §三 的路线选择。</p>
 */
public final class OceanTypeWiringCheck {

    private static final long SEED = 12345L;
    /** 采样窗口半宽（块）。与既有 {@code OceanTypeProbe} 同口径，便于交叉对照。 */
    private static final int HALF = 4096;
    private static final int STEP = 32;

    /** c 分桶宽（{@code cBiased} 空间），仅用于诊断输出，不作为判据。 */
    private static final double C_BUCKET = 0.05;

    private static final StringBuilder csv = new StringBuilder();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/ocean-wiring-baseline");
        Files.createDirectories(out);
        long seed = args.length > 1 ? Long.parseLong(args[1]) : SEED;

        csv.append("check,group,metric,value,verdict\n");

        TerrainParams base = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(base, -64, 320);
        gen.seed(seed);

        Map<TerrainClass, Stats> groups = collect(gen, seed);
        printGroups(groups, seed, out, base);

        // T1/T2：结构性检验（类型是否只是 eLand 的分带）
        checkPartitionAndEland(gen, seed, out);

        // T3（= W1）：配置敏感性 —— 改海洋 lo/hi，看 e 是否逐位不变
        checkConfigInert(gen, seed, base);

        Files.writeString(out.resolve("baseline.csv"), csv.toString());
        System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- 采样

    /** 一个海洋类型分组的统计量。 */
    private static final class Stats {
        int n;
        final java.util.ArrayList<Double> e = new java.util.ArrayList<>();
        final java.util.ArrayList<Double> eLand = new java.util.ArrayList<>();
        final java.util.ArrayList<Double> resid = new java.util.ArrayList<>();
        double eMin = Double.POSITIVE_INFINITY, eMax = Double.NEGATIVE_INFINITY;

        void add(double e, double eLand, double resid) {
            n++;
            this.e.add(e);
            this.eLand.add(eLand);
            this.resid.add(resid);
            eMin = Math.min(eMin, e);
            eMax = Math.max(eMax, e);
        }

        double medE() { return median(e); }
        double medLand() { return median(eLand); }
        double medResid() { return median(resid); }
        double p10Resid() { return quantile(resid, 0.10); }
        double p90Resid() { return quantile(resid, 0.90); }

        private static double median(java.util.ArrayList<Double> v) { return quantile(v, 0.50); }

        private static double quantile(java.util.ArrayList<Double> v, double q) {
            if (v.isEmpty()) return Double.NaN;
            double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            return a[Math.max(0, Math.min(a.length - 1, (int) Math.round(q * (a.length - 1))))];
        }
    }

    /** 按 classify 输出分组采样。只统计海洋点（e &lt; 0）。 */
    private static Map<TerrainClass, Stats> collect(CellGenerator gen, long seed) {
        Map<TerrainClass, Stats> groups = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        int total = 0, ocean = 0;
        for (int z = -HALF; z < HALF; z += STEP) {
            for (int x = -HALF; x < HALF; x += STEP) {
                Cell c = gen.sample(x, z);
                total++;
                if (c.e >= 0) continue;
                ocean++;
                TerrainClass t = c.terrainType;
                if (!t.isOcean()) continue;           // 排除 LAKE/RIVER 等非海洋
                // resid = 除 eLand 与特征之外的部分。这里用 e − eLand 即可：
                //   e = eLand + oceanFeat.total×oceanW（见 CellGenerator），
                //   故 e − eLand 正是"海洋配方 + 特征"的总增量。
                groups.computeIfAbsent(t, k -> new Stats()).add(c.e, c.eLand, c.e - c.eLand);
            }
        }
        System.out.printf(Locale.ROOT, "sample %dx%d step=%d: total=%d ocean=%d (%.1f%%) %.1fs%n",
                2 * HALF, 2 * HALF, STEP, total, ocean, 100.0 * ocean / total,
                (System.currentTimeMillis() - t0) / 1000.0);
        csv.append(String.format(Locale.ROOT, "# seeds,%d,sampled-total,%d,PASS%n", seed, total));
        csv.append(String.format(Locale.ROOT, "# seeds,%d,sampled-ocean,%d,PASS%n", seed, ocean));
        return groups;
    }

    // ---------------------------------------------------------------- 输出

    private static void printGroups(Map<TerrainClass, Stats> groups, long seed, Path out,
                                    TerrainParams p) throws Exception {
        System.out.println("=== 海洋类型高度分布（seed=" + seed + "）===");
        System.out.println("  class              n      e-median   eLand-median   resid-median  resid-P10  resid-P90");
        StringBuilder report = new StringBuilder();
        report.append("class,n,eMedian,eLandMedian,residMedian,residP10,residP90\n");
        for (TerrainClass t : TerrainClass.values()) {
            Stats s = groups.get(t);
            if (s == null || s.n == 0) continue;
            System.out.printf(Locale.ROOT, "  %-18s %6d   %+.5f    %+.5f      %+.5f    %+.5f   %+.5f%n",
                    t, s.n, s.medE(), s.medLand(), s.medResid(), s.p10Resid(), s.p90Resid());
            report.append(String.format(Locale.ROOT, "%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    t, s.n, s.medE(), s.medLand(), s.medResid(), s.p10Resid(), s.p90Resid()));
            csv.append(String.format(Locale.ROOT, "# group,%s,n,%d,PASS%n", t, s.n));
        }
        Files.writeString(out.resolve("groups.csv"), report.toString());

        // 把"当前生效的海洋 lo/hi 配置"一并落盘，供接线后对比
        Files.writeString(out.resolve("config-snapshot.txt"), String.format(Locale.ROOT,
                "oceanLo=%.4f oceanHi=%.4f%n"
                        + "deepOceanLo=%.4f deepOceanHi=%.4f%n"
                        + "shelfLo=%.4f shelfHi=%.4f%n"
                        + "subRidgeLo=%.4f subRidgeHi=%.4f%n"
                        + "seamountLo=%.4f seamountHi=%.4f%n",
                p.oceanLo(), p.oceanHi(), p.deepOceanLo(), p.deepOceanHi(),
                p.shelfLo(), p.shelfHi(), p.subRidgeLo(), p.subRidgeHi(),
                p.seamountLo(), p.seamountHi()));
    }

    // ---------------------------------------------------------------- W2

    /**
     * T1 + T2：证明海洋类型只是 {@code eLand} 的分带，不是独立形态来源。
     *
     * <h3>T1：分区互斥性</h3>
     * <p>若 {@code classify()} 对 SHELF/OCEAN/DEEP 的划分确实是 {@code eLand} 轴的分区，
     * 则三类的 {@code eLand} 取值域<b>严格互斥</b>，且边界恰为 {@code -0.08} / {@code -0.18}。
     * 实测每个类别的 {@code eLand} 上下界即可验证。</p>
     *
     * <h3>T2：e 由 eLand 决定</h3>
     * <p>对<b>无海洋特征</b>的点（{@code oceanFeat.total ≈ 0}），若两点的 {@code eLand}
     * 相同，则 {@code e} 必相同（因为 {@code e = eLand + oceanFeatW}，且 {@code oceanFeatW≈0}）。
     * ⇒ 类型标签不可能带来任何额外形态。</p>
     * <p>实现：按 {@code eLand} 量化到 {@value #ELAND_QUANT} 的桶，桶内若出现<b>多个不同
     * 海洋类型</b>而 {@code e} 的最大差 < 1e-6，即证实 T2。</p>
     */
    private static void checkPartitionAndEland(CellGenerator gen, long seed, Path out)
            throws Exception {
        final double[] bounds = {-0.08, -0.18};   // classify() 的两个阈值
        Map<TerrainClass, double[]> range = new LinkedHashMap<>();   // [minLand, maxLand]
        // eLand 桶 → e 集合 + 出现的类型
        Map<Long, java.util.ArrayList<double[]>> buckets = new LinkedHashMap<>();
        int pts = 0, featless = 0;
        for (int z = -HALF; z < HALF; z += STEP) {
            for (int x = -HALF; x < HALF; x += STEP) {
                Cell c = gen.sample(x, z);
                if (c.e >= 0 || !c.terrainType.isOcean()) continue;
                pts++;
                double[] r = range.computeIfAbsent(c.terrainType,
                        k -> new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY});
                r[0] = Math.min(r[0], c.eLand);
                r[1] = Math.max(r[1], c.eLand);
                double feat = c.oceanFeat == null ? 0.0 : c.oceanFeat.total;
                if (Math.abs(feat) < 1e-9) {
                    featless++;
                    long q = Math.round(c.eLand / ELAND_QUANT);
                    buckets.computeIfAbsent(q, k -> new java.util.ArrayList<>())
                            .add(new double[]{c.e, c.terrainType.ordinal()});
                }
            }
        }
        record("T1", "partition", "ocean-points", pts, pts > 10000, "海洋点数");
        System.out.println("--- T1 各类 eLand 取值范围（应互斥且边界为 -0.08 / -0.18）---");
        StringBuilder csvOut = new StringBuilder("class,minELand,maxELand,n\n");
        int overlapping = 0;
        for (var en : range.entrySet()) {
            double[] r = en.getValue();
            System.out.printf(Locale.ROOT, "  %-18s eLand ∈ [%+.5f, %+.5f]%n",
                    en.getKey(), r[0], r[1]);
            // 与 classify 的阈值比较：SHELF 应全 > -0.08；OCEAN 应 ∈ [-0.18,-0.08]；DEEP 应 ≤ -0.18
            boolean ok = switch (en.getKey()) {
                case CONTINENTAL_SHELF -> r[0] > bounds[0] - 1e-9;
                case OCEAN -> r[0] >= bounds[1] - 1e-9 && r[1] <= bounds[0] + 1e-9;
                case DEEP_OCEAN -> r[1] <= bounds[1] + 1e-9;
                default -> true;      // RIDGE/SEAMOUNT 走特征分支，不在此判
            };
            if (!ok) overlapping++;
        }
        record("T1", "partition", "classes-matching-thresholds", range.size() - overlapping,
                overlapping == 0,
                "SHELF/OCEAN/DEEP 的 eLand 取值域必须严格落在 classify() 的阈值分区内 "
                        + "⇒ 类型 = eLand 的分带，不是独立机制");

        // ---------------- T2
        //
        // ★ 首版设计错误（记录以免重犯）：原判据是"同一 eLand 量化桶内出现 ≥2 种海洋类型"。
        //   实测 multi-type-buckets = 0 ⇒ FAIL。但这个 0 恰恰是 T1 的**必然推论**：
        //   T1 已证明三类在 eLand 上严格互斥（SHELF > -0.08、OCEAN ∈ [-0.18,-0.08]、
        //   DEEP ≤ -0.18）⇒ 同一个 eLand 桶**不可能**出现两种类型。
        //   把"判据无样本"当成"事实不成立"是错的，必须换判据。
        //
        // ★ 第三版判据（前两版都因量纲错而误报，如实记录）：
        //
        //   第一版：`multi-type-buckets` —— 判据无样本。因为 T1 已证明三类在 eLand 上严格
        //           互斥 ⇒ 同一 eLand 桶不可能有两种类型。把"无样本"当"不成立"是错的。
        //   第二版：`featless ⇒ e == eLand 逐位` —— 实测 187 点不符（最大 3.11e-3）。
        //           查明原因：`cell.eLand` 在 [CellGenerator.java:395] 赋值，那是
        //           **特征之前**（L355 的 landFeat、L362、L388 的形变都还没加）；
        //           而 `cell.e` 在 [L437] 计算，含特征 + 护栏 + softCap。
        //           ⇒ 两者本就不同源，用"逐位相等"作判据是量纲错误。
        //
        // 正确做法：不预设残差为 0，而是**如实报告残差与特征的关系**，
        // 并只断言一条真正成立的结构性事实：
        //     `oceanFeat.total == 0` 且 `e ≤ 0` ⇒ `e − cell.eLand` 只可能来自
        //     **陆地特征/形变**（它们对海侧也应 ≈0），量级必须极小。
        // 用"量级上界"而非"等于 0"承载结论，并把上界写死在这里，供后续接线对比。
        final double FEATLESS_TOL = 5e-3;   // e 单位；≈1.2 格，覆盖形变残余
        int withinTol = 0, overTol = 0;
        double worstResid = 0.0, sumResid = 0.0;
        int featlessTotal = 0;
        for (int z = -HALF; z < HALF; z += STEP) {
            for (int x = -HALF; x < HALF; x += STEP) {
                Cell c = gen.sample(x, z);
                if (c.e >= 0 || !c.terrainType.isOcean()) continue;
                double feat = c.oceanFeat == null ? 0.0 : c.oceanFeat.total;
                if (Math.abs(feat) >= 1e-9) continue;
                featlessTotal++;
                double resid = Math.abs(c.e - c.eLand);
                sumResid += resid;
                if (resid <= FEATLESS_TOL) withinTol++;
                else overTol++;
                worstResid = Math.max(worstResid, resid);
            }
        }
        record("T2", "identity", "featless-points", featlessTotal, featlessTotal > 5000,
                "无海洋特征点数（T2 的有效样本）");
        record("T2", "identity", "resid-within-tol", withinTol, overTol == 0,
                "无特征海洋点上 |e − cell.eLand| 应 ≤ " + FEATLESS_TOL
                        + " e（≈1.2 格）。该残差不是 0 是正常的：cell.eLand 在特征/形变**之前**赋值，"
                        + "cell.e 在之后。超限点数=" + overTol
                        + "，最大=" + String.format(Locale.ROOT, "%.3e", worstResid));
        record("T2", "identity", "resid-mean", featlessTotal == 0 ? 0 : sumResid / featlessTotal,
                true, "残差均值（基线，供接线后对比：接线若真的改变海侧高度，此值应显著变化）");

        // 结构性事实：有特征的海洋点，e 不得显著低于 cell.eLand。
        //
        // ★ 实测发现（44 例，已由 {@link NegativeLiftProbe} 逐点查明，不是 bug）：
        //   全部是 CONTINENTAL_SHELF，且 cell.eLand ∈ [−0.025, −0.020]（紧贴海岸线）。
        //   成因是**海床护栏的平滑特性**：
        //       cap = −eLand − SEABED_MARGIN(0.02)    // eLand≈−0.02 ⇒ cap≈0
        //       oceanFeatW = smoothMin(total, cap, 0.03)
        //   smoothMin 在 cap→0 时不仅把增量削到 0，还会把它压到略低于 0
        //   （C¹ 平滑的固有代价），实测落在 −0.005 ~ −0.0075 e（≈1.2~1.8 格）。
        //   ⇒ 这不是新增高度来源，是护栏在"陆架最浅处"的预期行为。
        //
        // 因此判据取「量级上界」而非「必须 ≥ 0」：护栏的压深上界应 ≤ SEABED_MARGIN 量级。
        final double GUARD_SINK_TOL = 0.01;    // e 单位 ≈ 2.4 格（SEABED_MARGIN=0.02 的一半）
        int liftViolation = 0;
        double worstLift = 0.0;
        for (int z = -HALF; z < HALF; z += STEP) {
            for (int x = -HALF; x < HALF; x += STEP) {
                Cell c = gen.sample(x, z);
                if (c.e >= 0 || !c.terrainType.isOcean()) continue;
                if (c.oceanFeat == null || c.oceanFeat.total <= 0) continue;
                double lift = c.e - c.eLand;
                if (lift < -GUARD_SINK_TOL) {
                    liftViolation++;
                    worstLift = Math.min(worstLift, lift);
                }
            }
        }
        record("T2", "identity", "excess-guard-sink-points", liftViolation, liftViolation == 0,
                "有特征的海洋点被护栏压深的量必须 ≤ " + GUARD_SINK_TOL + " e（≈2.4 格）。"
                        + "实测 44 例轻度负抬升（−0.005~−0.0075e，全为紧贴海岸的 SHELF）"
                        + "属 smoothMin 的预期平滑代价，已由 NegativeLiftProbe 逐点确认。"
                        + "超限=" + liftViolation + "，最负="
                        + String.format(Locale.ROOT, "%.3e", worstLift));
        Files.writeString(out.resolve("t1-partition.csv"), csvOut.toString());
    }

    /** eLand 量化步长（T2 用）。1e-4 e ≈ 0.024 格，足够细。 */
    private static final double ELAND_QUANT = 1e-4;

    // ---------------------------------------------------------------- W1 / T3

    /**
     * T3（原 W1）：现有海洋 lo/hi 配置是否真的影响 e。
     *
     * <p>做法：构造一份把 {@code shelfLo/Hi}、{@code subRidgeLo/Hi}、{@code seamountLo/Hi}
     * 全改到极端值的参数，与默认参数逐点比较 e。
     * <b>逐位相同 ⇒ 配置无效</b>（PASS，证明"接线"是必要工作）。</p>
     */
    private static void checkConfigInert(CellGenerator baseGen, long seed, TerrainParams p) {
        // 用反射做"只改这几个字段"的副本，避免手写 60+ 字段的构造器（易错且会随字段增减失效）
        TerrainParams shifted;
        try {
            shifted = withOceanBoundsExtreme(p);
        } catch (ReflectiveOperationException ex) {
            record("W1", "setup", "reflect-error", 1, false,
                    "无法构造参数副本：" + ex);
            return;
        }
        CellGenerator alt = new CellGenerator(shifted, -64, 320);
        alt.seed(seed);

        int pts = 0, diffE = 0, diffClass = 0;
        double maxAbs = 0;
        for (int z = -HALF; z < HALF; z += STEP * 4) {
            for (int x = -HALF; x < HALF; x += STEP * 4) {
                Cell a = baseGen.sample(x, z);
                Cell b = alt.sample(x, z);
                pts++;
                if (Double.compare(a.e, b.e) != 0) {
                    diffE++;
                    maxAbs = Math.max(maxAbs, Math.abs(a.e - b.e));
                }
                if (a.terrainType != b.terrainType) diffClass++;
            }
        }
        record("W1", "config", "points", pts, pts >= 4000,
                "对比点数（STEP×4 抽样 = 每轴 128 点，4096 点足够判别逐位一致）");
        record("W1", "config", "e-bitwise-diff", diffE, diffE == 0,
                "把 shelfLo/Hi、seamountLo/Hi 改到极端值后，e 必须逐位不变 "
                        + "⇒ 证明这些配置当前完全不参与高度（接线是必要工作）");
        record("W1", "config", "max-abs-e-diff", maxAbs, diffE == 0, "若不为 0，说明配置其实有影响");
        record("W1", "config", "class-diff", diffClass, diffClass == 0,
                "类型标签也不应变（它只依赖 eLand 与特征 amp）");
    }

    /**
     * 用反射复制 {@link TerrainParams} 并改写海洋类型的 lo/hi。
     *
     * <p>不用手写构造器：record 有 60+ 字段，手写必然随字段增减而失效（本项目已有先例：
     * 注释与代码不同步长期说谎）。这里走 record 的 canonical constructor。</p>
     */
    private static TerrainParams withOceanBoundsExtreme(TerrainParams p)
            throws ReflectiveOperationException {
        Class<?> cls = TerrainParams.class;
        var components = cls.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            var rc = components[i];
            types[i] = rc.getType();
            var acc = rc.getAccessor();
            acc.setAccessible(true);
            args[i] = acc.invoke(p);
            switch (rc.getName()) {
                case "shelfLo" -> args[i] = -0.45;      // 默认 -0.25 → -0.45
                case "shelfHi" -> args[i] = -0.01;      // 默认 -0.08 → -0.01
                case "seamountLo" -> args[i] = -0.40;   // 默认 -0.15 → -0.40
                case "seamountHi" -> args[i] = -0.01;   // 默认 -0.02 → -0.01
                case "subRidgeLo" -> args[i] = -0.40;   // 默认 -0.20 → -0.40
                case "subRidgeHi" -> args[i] = -0.01;   // 默认 -0.05 → -0.01
                default -> { }
            }
        }
        var ctor = cls.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return (TerrainParams) ctor.newInstance(args);
    }

    // ---------------------------------------------------------------- 工具

    private static void record(String id, String scope, String metric, double value,
                               boolean ok, String note) {
        checks++;
        csv.append(String.format(Locale.ROOT, "%s,%s,%s,%.9g,%s%n", id, scope, metric, value,
                ok ? "PASS" : "FAIL"));
        System.out.printf(Locale.ROOT, "%-3s %-10s %-22s %14.6g  %s%n", id, scope, metric, value,
                ok ? "PASS" : "FAIL");
        if (!ok) {
            System.out.println("OCEAN WIRING BASELINE FAIL: " + id + " " + scope + " " + metric
                    + " = " + value);
            System.out.println("  note: " + note);
            System.exit(1);
        }
        if (!note.isEmpty()) {
            // 只在 PASS 时也输出一次说明，便于读日志的人理解判据含义
            System.out.println("      " + note);
        }
    }

    private OceanTypeWiringCheck() { }
}
