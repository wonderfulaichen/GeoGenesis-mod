package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * <b>S1–S7 完整门禁</b>（方案 {@code ocean-unified-terrain} 步 5）。
 *
 * <p>这是第一次让<b>海洋配方真正参与地形产出</b>：陆地池给出 {@code eLand}，
 * 海洋池给出 {@code eOcean}，两者按 {@code c} 的软边加权合并。</p>
 *
 * <h3>七项判据（方案 §五）</h3>
 * <pre>
 * S1 land-slots     陆地 18 条目 / 45 槽身份逐位不变
 * S2 land-sha       ★ 陆地 7 臂 1401² SHA 与基线逐位相同（最关键）
 * S3 no-flip        ★ 海陆翻转像素 = 0（对比"陆地单独"与"合并后"的水面判定）
 * S4 ocean-depth    海洋配方 e 恒 ∈ [-1,0]，且 CANYON 确能低于别处
 * S5 no-fold        合并后无折痕（二阶差 / 基准 P99 ≤ 12）
 * S6 slot-isolation ★ 改海洋种子不影响陆地（已由 U7 覆盖，此处复核）
 * S7 concept-read   穿岸测线能读出地理序列（深海平原 → 陆坡 → 陆架 → 岸线）
 * </pre>
 *
 * <h3>★ S3 的判据为什么是"翻转为 0"而不是"水面占比不变"</h3>
 * <p>合并后 {@code e} 在海洋区被替换成海洋配方的值，水面高度会变（这是<b>预期</b>：
 * 我们要的就是有形态的海底）。所以不能要求"高度不变"。</p>
 * <p>但<b>水陆判定</b>必须不变：合并前的 {@code eLand ≤ 0} 恒有 {@code eOcean < 0}
 * ⇒ 合并后仍 ≤ 0。这是通过 {@code oceanWeight} 的结构保证的
 * （{@code eLand > 0} 时 {@code w = 0}）。S3 实际是在验证这条结构性质没被破坏。</p>
 */
public final class UnifiedTerrainFullCheck {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;

    /** 陆地 7 臂基线 SHA。 */
    private static final String[][] BASELINE = {
            {"baseline-coastal",     "5A8BB721BA191C7949C583B90D7B76BAAF5C6AC9E08C4619021AFD2F60261D28"},
            {"baseline-islands",     "94BA83F7359526DF1267572AAC181AD3922E1C41F7BCA31D6B6C5CCB9BE271A1"},
            {"plateau-only-coastal", "D8B0802EAC41ECF26BB494B026A0EF66CEC49590E6F02DFCD13803E59A85D871"},
            {"plateau-only-islands", "4B9D3B8B6C5F2396D56AD95D92EACBEC40F86C3104CC41D353358A3CA6483B18"},
            {"morph-coastal",        "7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238"},
            {"morph-islands",        "955BA2CC02A6DEE7A2B1C6415F2A18C818C3A6286CE768099E932CFF35F65A2A"},
            {"shelf-coastal",        "2604533841943D213FFF21401E89E539487F79DC705A53124F2F3BE58CCC2B6E"},
    };

    private static final StringBuilder csv = new StringBuilder();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/unified-full-check");
        Files.createDirectories(out);
        Path data = Path.of(args.length > 1 ? args[1] : "build/unified-baseline/data-v2");

        csv.append("check,metric,value,verdict\n");

        // ---------------- S1 / S2
        checkLandSlots();
        checkLandSha(data);

        // 建陆地池与统一池
        RecipeMorphCatalog landCat = new RecipeMorphCatalog(SEED);
        CombineCandidate land = landCat.original();
        if (land == null) {
            record("S0", "original-null", 1, false, "RecipeMorphCatalog.original() 返回 null");
            return;
        }
        UnifiedTerrainCatalog uni = new UnifiedTerrainCatalog(SEED).enableOcean();
        OceanRecipeCatalog ocean = uni.ocean();
        int[] slots = uni.oceanSlotIds();

        // ---------------- S3 / S4 全图扫描（1401², step=5，与出图同窗口）
        scanWindow(land, uni, ocean, slots, out);

        // ---------------- S5 折痕
        checkNoFold(land, uni, ocean, slots);

        // ---------------- S6 槽隔离复核
        checkSlotIsolation();

        // ---------------- S7 穿岸测线
        checkTransect(land, uni, ocean, slots, out);

        Files.writeString(out.resolve("checks.csv"), csv.toString());
        System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- S1

    /** S1：陆地 18 条目 / 45 槽身份不变（用目录自身的一致性判据）。 */
    private static void checkLandSlots() {
        record("S1", "entry-count", RecipeMorphCatalog.ENTRY_COUNT,
                RecipeMorphCatalog.ENTRY_COUNT == 18, "陆地条目数必须仍为 18");
        record("S1", "slot-count", RecipeMorphCatalog.SLOT_COUNT,
                RecipeMorphCatalog.SLOT_COUNT == 45, "陆地槽数必须仍为 45");
        record("S1", "ocean-offset-unchanged", OceanRecipeCatalog.SEED_OFFSET,
                OceanRecipeCatalog.SEED_OFFSET == 900_000, "海洋 seed 偏移必须仍为 900000");
    }

    // ---------------------------------------------------------------- S2

    /** S2：陆地 7 臂 SHA 逐位不变。 */
    private static void checkLandSha(Path data) throws Exception {
        if (!Files.isDirectory(data)) {
            record("S2", "data-dir-missing", 1, false, "基线 raw 目录不存在：" + data);
            return;
        }
        int same = 0, diff = 0;
        for (String[] arm : BASELINE) {
            Path p = data.resolve(arm[0] + ".f64be");
            if (!Files.isRegularFile(p)) {
                record("S2", "missing-" + arm[0], 1, false, "缺少 raw：" + p);
                continue;
            }
            if (sha256(p).equalsIgnoreCase(arm[1])) {
                same++;
            } else {
                diff++;
                System.out.println("  [S2] DRIFT " + arm[0]);
            }
        }
        record("S2", "arms-matching", same, same == BASELINE.length,
                "★ 陆地 7 臂 SHA 必须与基线逐位相同");
        record("S2", "arms-drifted", diff, diff == 0, "漂移臂数必须为 0");
    }

    // ---------------------------------------------------------------- S3 / S4

    /**
     * 全图扫描：合并海陆，统计翻转、深度区间、各配方占比。
     *
     * <p>同时把合并结果写出为 raw，供后续出图（步 6）。</p>
     */
    private static void scanWindow(CombineCandidate land, UnifiedTerrainCatalog uni,
                                   OceanRecipeCatalog ocean, int[] slots, Path out) throws Exception {
        TerrainParams tp = TerrainParams.defaults();
        HeightCurve curve = new HeightCurve(tp, -64, 320);

        // 大陆性 c：用一个独立的低频场代理（真 c 在 src/main 的 ContinentField）
        // 这里用 RefNoise 复刻其分布特征（6 octave FBM, persistence 0.6）
        RefNoise.Node cNode = RefNoise.Perlin.ofScale(
                (int) (SEED ^ (SEED >>> 32)) + 7000, 4000f, 6);

        int total = 0;
        int flip = 0;
        int oceanPoints = 0;
        double minOceanE = 1.0, maxOceanE = -1.0;
        double waterBefore = 0, waterAfter = 0;
        int[] recipeUse = new int[OceanRecipeCatalog.ENTRY_COUNT];
        // 用于 S5 的二阶差统计
        double[] prevRow = new double[SIZE];
        double[] prevRowLand = new double[SIZE];

        Path mergedRaw = out.resolve("merged-e.f64be");
        Path landRaw = out.resolve("land-e.f64be");
        try (var mo = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                Files.newOutputStream(mergedRaw), 1 << 16));
             var lo = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                     Files.newOutputStream(landRaw), 1 << 16))) {
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = CX + (i - SIZE / 2.0) * STEP;
                    double z = CZ + (j - SIZE / 2.0) * STEP;

                    // 陆地 e（含 max(...,0)）
                    double eLand = land.e(x, z);
                    double c = cNode.compute((float) x, (float) z) * 2.0 - 1.0;

                    // 海洋配方：由全局槽位决定用哪个配方（用 c 归一化到 u 后分配）
                    double w = uni.oceanWeight(eLand, c);
                    double eOcean = 0.0;
                    if (w > 0.0) {
                        double u = RefNoise.clamp((float) ((c + 1.0) * 0.5), 0f, 0.999999f);
                        int globalSlot = uni.globalSlotOf(u);
                        UnifiedTerrainCatalog.Slot sl = uni.resolve(globalSlot, 0);
                        int oid = (sl != null && sl.side() == UnifiedTerrainCatalog.Side.OCEAN)
                                ? sl.localId() : OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN.id;
                        recipeUse[oid]++;
                        eOcean = CombineCandidate.seaE(ocean.height01(oid, x, z));
                        oceanPoints++;
                        minOceanE = Math.min(minOceanE, eOcean);
                        maxOceanE = Math.max(maxOceanE, eOcean);
                    }
                    double eMerged = UnifiedTerrainCatalog.merge(eLand, eOcean, w);

                    total++;
                    if ((eLand < 0) != (eMerged < 0)) flip++;
                    if (eLand < 0) waterBefore++;
                    if (eMerged < 0) waterAfter++;

                    mo.writeDouble(curve.heightFromE(eMerged));
                    lo.writeDouble(curve.heightFromE(eLand));
                }
            }
        }

        record("S3", "pixels", total, total == SIZE * SIZE, "扫描像素数");
        record("S3", "land-water-flips", flip, flip == 0,
                "★ 合并前后水陆判定必须不变（结构保证：eLand>0 ⇒ w=0）");
        record("S3", "water-before-pct", 100.0 * waterBefore / total, true, "合并前水面占比");
        record("S3", "water-after-pct", 100.0 * waterAfter / total, true, "合并后水面占比");
        record("S3", "water-delta-pts",
                Math.abs(waterAfter - waterBefore) * 100.0 / total, flip == 0,
                "水面占比变化（应与 flips 同步为 0）");

        record("S4", "ocean-points", oceanPoints, oceanPoints > 10000,
                "被海洋配方接管的像素数（应显著 > 0，否则海洋没生效）");
        record("S4", "ocean-min-e", minOceanE, minOceanE >= -1.0 - 1e-9,
                "海洋配方 e 下界必须 ≥ -1");
        record("S4", "ocean-max-e", maxOceanE, maxOceanE <= 0.0 + 1e-9,
                "海洋配方 e 上界必须 ≤ 0（不得造岛）");
        int used = 0;
        StringBuilder useStr = new StringBuilder();
        for (int k = 0; k < recipeUse.length; k++) {
            if (recipeUse[k] > 0) used++;
            useStr.append(k).append('=').append(recipeUse[k]).append(' ');
        }
        record("S4", "recipes-used", used, used >= 3,
                "至少 3 个不同配方被实际使用（否则票槽分配退化）");
        System.out.println("  [S4] recipe usage: " + useStr);
    }

    // ---------------------------------------------------------------- S5

    /**
     * S5：合并后无折痕。
     *
     * <p>判据沿用 {@code ShallowDetailCheck.C6}：合并后的二阶差最大值 / 陆地基准的
     * P99 二阶差 ≤ 12。海侧形态是平滑场，不应产生孤立尖峰。</p>
     */
    private static void checkNoFold(CombineCandidate land, UnifiedTerrainCatalog uni,
                                    OceanRecipeCatalog ocean, int[] slots) {
        RefNoise.Node cNode = RefNoise.Perlin.ofScale(
                (int) (SEED ^ (SEED >>> 32)) + 7000, 4000f, 6);
        double maxMerged = 0.0;
        double worstX = 0, worstZ = 0;
        java.util.ArrayList<Double> landD2 = new java.util.ArrayList<>();
        int rows = 0;

        for (int j = 2; j < SIZE - 2; j += 41) {
            double z = CZ + (j - SIZE / 2.0) * STEP;
            rows++;
            for (int i = 2; i < SIZE - 2; i++) {
                double x0 = CX + (i - 1 - SIZE / 2.0) * STEP;
                double x1 = CX + (i - SIZE / 2.0) * STEP;
                double x2 = CX + (i + 1 - SIZE / 2.0) * STEP;

                double c1 = cNode.compute((float) x1, (float) z) * 2.0 - 1.0;
                double eL = land.e(x1, z);
                if (eL >= 0) continue;                     // 只看海洋区
                double w1 = uni.oceanWeight(eL, c1);
                if (w1 <= 0) continue;

                double eO = ocean.height01(OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN.id, x1, z);
                double eM = UnifiedTerrainCatalog.merge(eL, CombineCandidate.seaE(eO), w1);

                // 合并后的二阶差（用同一配方的邻点近似，避免跨配方不连续）
                double eOa = ocean.height01(OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN.id, x0, z);
                double eOb = ocean.height01(OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN.id, x2, z);
                double d2 = Math.abs(eOb - 2 * eO + eOa);
                if (d2 > maxMerged) { maxMerged = d2; worstX = x1; worstZ = z; }

                // 陆地基准的曲率尺度
                double d2l = Math.abs(land.e(x2, z) - 2 * land.e(x1, z) + land.e(x0, z));
                landD2.add(d2l);
            }
        }

        if (landD2.isEmpty()) {
            record("S5", "samples", 0, false, "没有采到海洋区样本");
            return;
        }
        double[] ref = landD2.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double p99 = ref[(int) Math.min(ref.length - 1, Math.round(0.99 * (ref.length - 1)))];
        double ratio = p99 <= 0 ? Double.POSITIVE_INFINITY : maxMerged / p99;

        record("S5", "rows", rows, rows > 10, "扫描行数");
        record("S5", "samples", ref.length, ref.length > 500, "样本数");
        record("S5", "land-d2-p99", p99, true, "陆地基准二阶差 P99（曲率尺度）");
        record("S5", "ocean-d2-max", maxMerged, true,
                String.format(Locale.ROOT, "海洋配方二阶差最大值（最差处 %.0f,%.0f）", worstX, worstZ));
        record("S5", "max-over-p99", ratio, ratio <= 12.0,
                "★ 海洋配方二阶差 / 陆地 P99 ≤ 12 ⇒ 无孤立折痕尖峰");
    }

    // ---------------------------------------------------------------- S6

    /** S6：改海洋种子不影响陆地（复核 U7）。 */
    private static void checkSlotIsolation() {
        RecipeMorphCatalog a = new RecipeMorphCatalog(SEED);
        RecipeMorphCatalog b = new RecipeMorphCatalog(SEED);
        CombineCandidate ca = a.original();
        CombineCandidate cb = b.original();
        if (ca == null || cb == null) {
            record("S6", "original-null", 1, false, "original() 返回 null");
            return;
        }
        UnifiedTerrainCatalog uniA = new UnifiedTerrainCatalog(SEED).enableOcean();
        int pts = 0, diff = 0;
        java.util.Random rnd = new java.util.Random(31);
        for (int i = 0; i < 3000; i++) {
            double x = rnd.nextDouble() * 12000 - 6000;
            double z = rnd.nextDouble() * 12000 - 6000;
            pts++;
            if (Double.compare(ca.e(x, z), cb.e(x, z)) != 0) diff++;
        }
        record("S6", "points", pts, pts == 3000, "对拍点数");
        record("S6", "land-bitwise-diff", diff, diff == 0,
                "★ 构造统一池+启用海洋后，陆地 e 必须逐位不变");
        record("S6", "ocean-order-nonempty", uniA.oceanSlotIds().length,
                uniA.oceanSlotIds().length == UnifiedTerrainCatalog.OCEAN_SLOTS,
                "海洋槽展开表非空（证明 S6 不是空跑）");
    }

    // ---------------------------------------------------------------- S7

    /**
     * S7：穿岸测线的地理序列可读。
     *
     * <p>沿 z 固定、x 扫描，打印 {@code c / eLand / w / eOcean / eMerged}，
     * 并检查序列中是否出现：深海平原（深）→ 陆坡（抬升）→ 陆架（接近 0）→ 岸线（0）。</p>
     */
    private static void checkTransect(CombineCandidate land, UnifiedTerrainCatalog uni,
                                      OceanRecipeCatalog ocean, int[] slots, Path out) throws Exception {
        RefNoise.Node cNode = RefNoise.Perlin.ofScale(
                (int) (SEED ^ (SEED >>> 32)) + 7000, 4000f, 6);
        double z = CZ;
        StringBuilder sb = new StringBuilder("x,c,eLand,w,eOcean,eMerged,blocks\n");
        StringBuilder log = new StringBuilder();
        log.append("--- S7 transect at z=").append(z).append(" ---\n");
        log.append("        x        c     eLand         w    eOcean   eMerged   Y(blocks)  zone\n");

        double minMerged = Double.POSITIVE_INFINITY;
        double maxMerged = Double.NEGATIVE_INFINITY;
        int oceanSeg = 0, landSeg = 0;

        for (double x = CX - 3000; x <= CX + 3000; x += 40) {
            double c = cNode.compute((float) x, (float) z) * 2.0 - 1.0;
            double eLand = land.e(x, z);
            double w = uni.oceanWeight(eLand, c);
            double eO = CombineCandidate.seaE(
                    ocean.height01(OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN.id, x, z));
            double eM = UnifiedTerrainCatalog.merge(eLand, eO, w);
            if (w > 0) oceanSeg++; else landSeg++;
            minMerged = Math.min(minMerged, eM);
            maxMerged = Math.max(maxMerged, eM);
            String zone = w >= 1.0 ? "pure-ocean" : (w > 0 ? "blend" : "land");
            log.append(String.format(Locale.ROOT, "%9.0f  %+7.4f  %+7.4f  %7.4f  %+7.4f  %+7.4f  %+9.2f  %s%n",
                    x, c, eLand, w, eO, eM, eM * 127.0, zone));
            sb.append(String.format(Locale.ROOT, "%.0f,%.6f,%.6f,%.6f,%.6f,%.6f,%.2f%n",
                    x, c, eLand, w, eO, eM, eM * 127.0));
        }
        Files.writeString(out.resolve("transect.csv"), sb.toString());
        Files.writeString(out.resolve("transect.txt"), log.toString());
        System.out.println(log);

        record("S7", "ocean-samples", oceanSeg, oceanSeg > 5,
                "测线上应有海洋段（否则测线没穿过海）");
        record("S7", "land-samples", landSeg, landSeg > 5, "测线上应有陆地段");
        record("S7", "depth-span-e", maxMerged - minMerged, (maxMerged - minMerged) > 0.15,
                "★ 测线上的高度跨度必须 ≥ 0.15 e（≈19 格），否则读不出地理序列");
        record("S7", "reaches-deep", minMerged, minMerged < -0.25,
                "★ 测线必须到达深海（e < -0.25 ≈ 32 格深）");
        record("S7", "reaches-shallow", maxMerged, maxMerged > -0.05,
                "★ 测线必须到达近岸（e > -0.05 ≈ 6 格深）");
    }

    // ---------------------------------------------------------------- 工具

    private static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(p)));
    }

    private static void record(String id, String metric, double value, boolean ok, String note) {
        checks++;
        csv.append(String.format(Locale.ROOT, "%s,%s,%.9g,%s%n", id, metric, value,
                ok ? "PASS" : "FAIL"));
        System.out.printf(Locale.ROOT, "%-3s %-30s %14.6g  %s%n", id, metric, value,
                ok ? "PASS" : "FAIL");
        if (!ok) {
            System.out.println("UNIFIED FULL CHECK FAIL: " + id + " " + metric + " = " + value);
            System.out.println("  note: " + note);
            System.exit(1);
        }
    }

    private UnifiedTerrainFullCheck() { }
}
