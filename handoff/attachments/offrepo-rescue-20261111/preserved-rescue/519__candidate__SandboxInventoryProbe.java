package com.geogenesis.worldgen.terrain.candidate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * <b>阶段一：沙箱配方 / 槽位 / 随机序列 / 覆盖来源普查（纯只读）</b>。
 *
 * <p>对应 {@code .openbitfun/plans/sandbox-morph-integration.plan.md} §7「阶段一」：导出
 * 18 条逻辑配方条目、45 个加权票槽、shuffle 后的槽位顺序、种子消费序列指纹、
 * 真实子配方邻接频次与三近配方位置、以及 mask 覆盖层的实际高度来源。</p>
 *
 * <h3>怎么拿到"真实"对象</h3>
 * <p>反射调用 {@code LandCompositionGallery.buildCombineRegional(SEED, 3600f)}（与
 * {@code compose-coastal} 臂同一条构建路径），再反射读取它的私有字段：
 * {@code mixable / unmixable / composites / slots / slotCats / 各权重 / border /
 * mountainBorder / modeNoise / partition / blender(mask, mountain)}。</p>
 *
 * <h3>⚠ 诚实声明（引用本探针产出时必须一并带走）</h3>
 * <ol>
 *   <li><b>{@code buildCombine} 写死 {@code SEED}</b>：{@code LandCompositionGallery:299-300}
 *       是 {@code long seed = SEED;} —— 它的形参 {@code src} 不被读，且<b>没有 seed 形参</b>。
 *       因此 {@code buildCombineRegional(arg, scale)} 的 {@code arg} <b>完全不进入</b>
 *       {@link CombineCandidate}：种子基 {@code (int)(SEED ^ SEED>>>32)}、复合 selector、
 *       区域划分 warp、区域划分 seed、Blender 遮罩 —— 全部由 {@code SEED} 决定，
 *       与 {@code arg} 无关（判据 {@code gallery.selectorIgnoresArg} 实测：
 *       {@code arg=SEED+1} 时 selector 种子仍等于 {@code base(SEED)}）。
 *       {@code arg} 的唯一可达处是 {@code regionalBorder} 里的 {@code m1.seed(arg+77)}，
 *       即边界模式用的山地族（判据 {@code gallery.argReachesBorderM1} 与手工
 *       {@code MountainsCandidate(M1_GEO).seed(arg+77)} 逐字段对拍）。
 *       <b>8 个族的 {@code seed(...)} 恒用 {@code SEED}</b>
 *       （判据 {@code gallery.fixedFamilySeed}：{@code arg} 与 {@code arg+1} 的族节点种子完全相同）。</li>
 *   <li>本探针<b>只做候选普查</b>：不证明区域拓扑、不量测「世界距离 / 240 块过渡带」、
 *       不替代 §3 的几何证明。邻接统计是 {@code size×size} 采样栅格上的<b>4 邻域像素对</b>，
 *       不是连续边界长度；{@code step=5} 意味着一条 5 块宽的边界带在统计里只占一列像素。</li>
 *   <li>第二 / 第三近的「配方」是<b>按格心所属槽位</b>定义（{@code round(id×N)}），
 *       不是「谁真的赢了边界」，因此三近配方齐全 ≠ 三向交汇；后者仍须 §3/§4 验证。</li>
 *   <li>私有常量（{@code WARP_STRENGTH}、{@code MTN_BLEND_*}）走反射读取，
 *       报告里打印实测值，不写死推断值。</li>
 *   <li>本类只写 {@code --out} 目录（默认 {@code build/terrain-upgrade/real-boundary-local/inventory/}），
 *       不改任何共享源码，不产出大图。</li>
 * </ol>
 *
 * <h3>用法</h3>
 * <pre>
 * java -cp &lt;candidate-test-classes;baseline/classes;baseline/src-main/resources;jars&gt; \
 *   com.geogenesis.worldgen.terrain.candidate.SandboxInventoryProbe \
 *   [--out=DIR] [--size=1401] [--step=5] [--cx=-140] [--cz=137] \
 *   [--comparePoints=4096] [--coverageStride=8] [--skipCensus]
 * </pre>
 * <p>有判据：任一 {@code FAIL} ⇒ {@code System.exit(2)}；异常 ⇒ {@code exit(3)}。</p>
 */
public final class SandboxInventoryProbe {

    private static final String GALLERY_CLASS =
            "com.geogenesis.worldgen.terrain.candidate.LandCompositionGallery";
    /** {@code LandCompositionGallery.SEED} 的期望值（用于判据，不用于构建）。 */
    private static final long CANONICAL_SEED = 9139912035078620160L;
    /** {@code buildCombineRegional(SEED, 3600f)} 的模式场相关长度。 */
    private static final float REGIONAL_MODE_SCALE = 3600f;

    private static final int EXPECT_MIXABLE = 5;
    private static final int EXPECT_PAIRS = 10;
    private static final int EXPECT_UNMIXABLE = 3;
    private static final int EXPECT_ENTRIES = EXPECT_MIXABLE + EXPECT_PAIRS + EXPECT_UNMIXABLE;
    private static final int EXPECT_SLOTS = 45;
    /** 全局种子游标：20(selector×2) + 1(shuffle) + 2(warp) + 1(partition) + 1(blender) = 25。 */
    private static final int EXPECT_SEED_INTS = 25;

    private static final float COMPOSITE_ALPHA_LO = 0.375f;
    private static final float COMPOSITE_ALPHA_HI = 0.625f;

    public static void main(String[] argv) {
        Locale.setDefault(Locale.ROOT);
        int code;
        try {
            code = new SandboxInventoryProbe(Args.parse(argv)).run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("[FAIL] probe aborted: " + t);
            code = 3;
        }
        System.out.flush();
        System.exit(code);
    }

    private final Args args;
    private final Checks checks = new Checks();

    private CombineCandidate combine;
    private long seed;
    private long base;

    private Object[] mixable;
    private Object[] unmixable;
    private Object[] composites;
    private Object[] slots;
    private CombineCandidate.Category[] mixableCat;
    private CombineCandidate.Category[] unmixableCat;
    private CombineCandidate.Category[] compositeCats;
    private CombineCandidate.Category[] slotCats;
    private float[] mixableWeight;
    private float[] unmixableWeight;
    private float[] compositeWeights;
    private Object borderFamily;
    private Object mountainBorderFamily;
    private Object modeNoise;
    private Object blender;
    private Object coverageFamily;
    private RegionPartition partition;
    private int partitionSeed;
    private RefNoise.Node warpX;
    private RefNoise.Node warpZ;
    private float warpStrength;

    private int slotCount;
    private int entryCount;
    private int[] entryOfSlot;
    private EntryInfo[] entries;
    private Mirror mirror;
    private float maskLow;
    private float maskHigh;
    private float maskSplit;

    private final List<SeedUse> seedUses = new ArrayList<>();
    private int shuffleSeedInt;
    private int shuffleExactSlots;

    private String coverageSummary = "";
    private String censusSummary = "";

    private SandboxInventoryProbe(Args args) {
        this.args = args;
    }

    // ==================================================================== run

    private int run() throws Exception {
        Files.createDirectories(args.out());
        long t0 = System.nanoTime();
        buildSandbox();
        readInventory();
        describeEntries();
        fingerprintSeeds();
        verifyShuffle();
        buildMirror();
        comparePoints();
        coverageCensus();
        if (args.skipCensus()) {
            censusSummary = "skipped (--skipCensus)";
            writeCensusStub();
        } else {
            neighbourhoodCensus();
        }
        writeInventoryJson();
        writeVerdictCsv();
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf(Locale.ROOT,
                "[inventory] seed=%d base=%d entries=%d slots=%d out=%s elapsed=%.1fs%n",
                seed, base, entryCount, slotCount, args.out().toAbsolutePath(), secs);
        System.out.printf(Locale.ROOT, "[coverage] %s%n", coverageSummary);
        System.out.printf(Locale.ROOT, "[census]   %s%n", censusSummary);
        for (String[] row : checks.rows) {
            System.out.printf(Locale.ROOT, "  %-30s %-4s %s%n", row[0], row[1], row[2]);
        }
        System.out.printf(Locale.ROOT, "[inventory] checks=%d failed=%d%n",
                checks.rows.size(), checks.failCount());
        return checks.allPass() ? 0 : 2;
    }

    // ========================================================== phase 1: build

    private void buildSandbox() throws Exception {
        Class<?> gallery = Class.forName(GALLERY_CLASS);
        Field seedField = gallery.getDeclaredField("SEED");
        seedField.setAccessible(true);
        seed = seedField.getLong(null);
        base = (int) (seed ^ (seed >>> 32));
        checks.add("gallery.seed", seed == CANONICAL_SEED,
                "reflected LandCompositionGallery.SEED=" + seed + " base(int)=" + base);

        Method build = gallery.getDeclaredMethod("buildCombineRegional", long.class, float.class);
        build.setAccessible(true);
        combine = (CombineCandidate) build.invoke(null, seed, REGIONAL_MODE_SCALE);
        checks.add("gallery.buildRegional", combine != null,
                "reflected buildCombineRegional(" + seed + ", " + REGIONAL_MODE_SCALE + "f)");

        // 判据：buildCombine 写死 SEED ⇒ 种子基与族种子都不随形参变化，形参唯一可达处是
        //      regionalBorder 的 m1.seed(arg+77)。
        CombineCandidate shifted =
                (CombineCandidate) build.invoke(null, seed + 1, REGIONAL_MODE_SCALE);
        checkFixedFamilySeed(shifted);
        checkSelectorIgnoresArg(shifted);
        checkArgReachesBorderM1(shifted);
    }

    /** 族配方不随 `buildCombineRegional` 形参变化（它们用写死的 SEED）。 */
    private void checkFixedFamilySeed(CombineCandidate shifted) {
        Object[] shiftedUnmix = (Object[]) read(shifted, "unmixable");
        List<String> seedsA = nodeSeeds(entryRecipeNode(unmixableOf(combine)[0]));
        List<String> seedsB = nodeSeeds(entryRecipeNode(shiftedUnmix[0]));
        checks.add("gallery.fixedFamilySeed", !seedsA.isEmpty() && seedsA.equals(seedsB),
                "family noise seeds identical for build arg seed=" + seed + " and seed=" + (seed + 1)
                        + " => buildCombine hardcodes SEED; " + seedsA.size()
                        + " `seed` fields compared on unmixable[0]");
    }

    /** `CombineCandidate` 的复合 selector 种子基等于 `base(SEED)`，与形参无关。 */
    private void checkSelectorIgnoresArg(CombineCandidate shifted) {
        Object[] shiftedComposites = (Object[]) read(shifted, "composites");
        int shiftedSelector = selectorInputSeed(shiftedComposites[EXPECT_MIXABLE]);
        checks.add("gallery.selectorIgnoresArg", shiftedSelector == base,
                "selector seed for arg seed+1 build=" + shiftedSelector
                        + " expected base(SEED)=" + base + " (i.e. CombineCandidate is built from"
                        + " SEED, not from the method argument)");
    }

    /** 形参唯一可达处：`regionalBorder` 的 `m1.seed(arg + 77)` —— 与手工构建的 M1_GEO 对拍。 */
    private void checkArgReachesBorderM1(CombineCandidate shifted) {
        MountainsCandidate m1AtSeed = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        m1AtSeed.seed(seed + 77);
        MountainsCandidate m1AtNext = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        m1AtNext.seed(seed + 1 + 77);
        List<String> expectedBorder = nodeSeeds(tryRead(m1AtSeed, "height"));
        List<String> expectedBorderNext = nodeSeeds(tryRead(m1AtNext, "height"));
        List<String> shiftedBorderSeeds =
                nodeSeeds(entryRecipeNode(read(shifted, "mountainBorder")));
        List<String> borderSeeds = nodeSeeds(entryRecipeNode(read(combine, "mountainBorder")));
        checks.add("gallery.argReachesBorderM1",
                !borderSeeds.isEmpty() && borderSeeds.equals(expectedBorder)
                        && shiftedBorderSeeds.equals(expectedBorderNext),
                "mountainBorder seed chain == MountainsCandidate(M1_GEO).seed(arg+77):"
                        + " arg=SEED match=" + borderSeeds.equals(expectedBorder)
                        + ", arg=SEED+1 match=" + shiftedBorderSeeds.equals(expectedBorderNext)
                        + " (" + borderSeeds.size() + " seed fields); this is the ONLY place the"
                        + " build argument reaches a recipe");
    }

    private static Object[] unmixableOf(CombineCandidate c) {
        return (Object[]) read(c, "unmixable");
    }

    // ====================================================== phase 2: inventory

    private void readInventory() throws Exception {
        mixable = (Object[]) read(combine, "mixable");
        unmixable = (Object[]) read(combine, "unmixable");
        composites = (Object[]) read(combine, "composites");
        slots = (Object[]) read(combine, "slots");
        mixableCat = (CombineCandidate.Category[]) read(combine, "mixableCat");
        unmixableCat = (CombineCandidate.Category[]) read(combine, "unmixableCat");
        compositeCats = (CombineCandidate.Category[]) read(combine, "compositeCats");
        slotCats = (CombineCandidate.Category[]) read(combine, "slotCats");
        mixableWeight = (float[]) read(combine, "mixableWeight");
        unmixableWeight = (float[]) read(combine, "unmixableWeight");
        compositeWeights = (float[]) read(combine, "compositeWeights");
        borderFamily = read(combine, "border");
        mountainBorderFamily = read(combine, "mountainBorder");
        modeNoise = read(combine, "modeNoise");
        blender = read(combine, "blender");
        coverageFamily = read(blender, "mountain");
        partition = (RegionPartition) read(combine, "partition");
        partitionSeed = (int) read(partition, "seed");
        warpX = (RefNoise.Node) read(partition, "warpX");
        warpZ = (RefNoise.Node) read(partition, "warpZ");
        warpStrength = (float) readStatic(RegionPartition.class, "WARP_STRENGTH");
        maskLow = (float) readStatic(CombineCandidate.class, "MTN_BLEND_LOW");
        maskHigh = (float) readStatic(CombineCandidate.class, "MTN_BLEND_HIGH");
        maskSplit = (float) readStatic(CombineCandidate.class, "MTN_BLEND_SPLIT");
        slotCount = slots.length;
        entryCount = composites.length + unmixable.length;

        checks.add("inv.mixable", mixable.length == EXPECT_MIXABLE, "mixable=" + mixable.length);
        checks.add("inv.pairs", composites.length == EXPECT_MIXABLE + EXPECT_PAIRS,
                "composites=" + composites.length + " = 5 originals + 10 pairs");
        checks.add("inv.unmixable", unmixable.length == EXPECT_UNMIXABLE,
                "unmixable=" + unmixable.length);
        checks.add("inv.entries", entryCount == EXPECT_ENTRIES,
                "logical entries=" + entryCount + " (expected " + EXPECT_ENTRIES + ")");
        checks.add("inv.slots", slotCount == EXPECT_SLOTS,
                "weighted vote slots=" + slotCount + " (expected " + EXPECT_SLOTS + ")");
        if (entryCount != EXPECT_ENTRIES || slotCount != EXPECT_SLOTS) {
            throw new IllegalStateException("inventory shape changed: entries=" + entryCount
                    + " slots=" + slotCount + " (probe arrays are sized for 18/45)");
        }
        checks.add("inv.border", borderFamily != null && mountainBorderFamily != null,
                "border=plains recipe, mountainBorder=m1-geo recipe, modeNoise=" + (modeNoise != null));

        entryOfSlot = mapSlotsToEntries();
        int[] votes = voteCounts();
        int sum = 0;
        for (int v : votes) {
            sum += v;
        }
        checks.add("inv.votesSum", sum == slotCount, "sum(votes)=" + sum + " slots=" + slotCount);
    }

    private int[] voteCounts() {
        int[] votes = new int[entryCount];
        for (int e : entryOfSlot) {
            if (e >= 0) {
                votes[e]++;
            }
        }
        return votes;
    }

    /**
     * 用对象 identity 把每个槽位映射回逻辑条目。
     *
     * <p>条目下标：{@code 0..4} = mixable 原始族；{@code 5..14} = 两两复合；{@code 15..17} = 独立条目
     * （台地 / 劣地 / 山地）。</p>
     */
    private int[] mapSlotsToEntries() {
        IdentityHashMap<Object, Integer> byIdentity = new IdentityHashMap<>();
        for (int i = 0; i < composites.length; i++) {
            byIdentity.put(composites[i], i);
        }
        for (int i = 0; i < unmixable.length; i++) {
            byIdentity.put(unmixable[i], composites.length + i);
        }
        int[] map = new int[slots.length];
        int missing = 0;
        for (int i = 0; i < slots.length; i++) {
            Integer e = byIdentity.get(slots[i]);
            if (e == null) {
                missing++;
                map[i] = -1;
            } else {
                map[i] = e;
            }
        }
        checks.add("inv.identityMap", missing == 0,
                "unmapped slots=" + missing + " (0 => each slot is one of the " + entryCount
                        + " entries by reference identity)");
        return map;
    }

    // ==================================================== phase 3: descriptors

    private void describeEntries() throws Exception {
        int[] votes = voteCounts();
        entries = new EntryInfo[entryCount];
        for (int i = 0; i < composites.length; i++) {
            boolean atom = i < mixable.length;
            EntryInfo e = new EntryInfo(i, atom ? "ORIGINAL" : "COMPOSITE", compositeCats[i],
                    compositeWeights[i], votes[i]);
            if (atom) {
                e.describeAtom(mixable[i]);
            } else {
                entries[i] = e;
                describeCompositeInto(e, i);
                continue;
            }
            entries[i] = e;
        }
        for (int j = 0; j < unmixable.length; j++) {
            int idx = composites.length + j;
            EntryInfo e = new EntryInfo(idx, "UNMIXABLE", unmixableCat[j], unmixableWeight[j],
                    votes[idx]);
            e.describeAtom(unmixable[j]);
            entries[idx] = e;
        }

        int compositesVerified = 0;
        for (int i = mixable.length; i < composites.length; i++) {
            if (entries[i].compositeVerified) {
                compositesVerified++;
            }
        }
        checks.add("desc.compositeTree", compositesVerified == EXPECT_PAIRS,
                "composites reproduced as max(a+(b-a)*alpha,0) with reflected (sel,a,b): "
                        + compositesVerified + "/" + EXPECT_PAIRS);

        int atoms = 0;
        int atomsWithTree = 0;
        for (EntryInfo e : entries) {
            if (!e.kind.equals("COMPOSITE")) {
                atoms++;
                if (!e.treeText.isEmpty()) {
                    atomsWithTree++;
                }
            }
        }
        checks.add("desc.atomTree", atomsWithTree == atoms,
                "atom recipes with recovered noise tree: " + atomsWithTree + "/" + atoms
                        + " (recipe object -> RefNoise.Node via reflection)");

        writeEntriesCsv();
        writeCompositesCsv();
        writeSlotsCsv();
        writeRecipeTreesJson();
    }

    private void describeCompositeInto(EntryInfo e, int compositeIndex) {
        int pairIndex = compositeIndex - mixable.length;
        int i = 0;
        int j = 1;
        int seen = 0;
        outer:
        for (i = 0; i < mixable.length; i++) {
            for (j = i + 1; j < mixable.length; j++) {
                if (seen == pairIndex) {
                    break outer;
                }
                seen++;
            }
        }
        Object a = mixable[i];
        Object b = mixable[j];
        Object sel = selectorNode(composites[compositeIndex]);
        boolean ordered = compositeMatches(composites[compositeIndex], sel, a, b);
        if (!ordered && compositeMatches(composites[compositeIndex], sel, b, a)) {
            Object t = a;
            a = b;
            b = t;
            ordered = true;
            checks.warn("desc.compositeOrder#" + compositeIndex,
                    "selector mix order is (b,a) not (a,b) for this pair");
        }
        e.compositeVerified = ordered;
        e.label = labelOf(a) + " + " + labelOf(b);
        e.compositeParts = e.label;
        StringBuilder sb = new StringBuilder();
        sb.append("composite: max(a + (b-a)*alpha, 0); alpha = mapClamped(sel, ")
                .append(COMPOSITE_ALPHA_LO).append(", ").append(COMPOSITE_ALPHA_HI).append(")\n");
        sb.append("  a=").append(labelOf(a)).append('\n');
        sb.append("  b=").append(labelOf(b)).append('\n');
        sb.append("  selector=");
        List<String> seedFields = new ArrayList<>();
        if (sel != null) {
            dumpNode(sel, 1, sb, seedFields);
            e.selectorText = sb.toString();
            e.seedChain = seedFields;
            e.seedFieldCount = seedFields.size();
            e.treeText = sb.toString();
            e.treeDigest = sha256Hex(sb.toString()).substring(0, 16);
        } else {
            e.treeText = "selector unrecoverable";
            e.treeDigest = "unrecoverable";
        }
    }

    /** 18 条逻辑条目之一。 */
    private static final class EntryInfo {
        final int index;
        final String kind;
        final CombineCandidate.Category category;
        final float weight;
        final int votes;
        String label = "?";
        String recipeClass = "";
        String variant = "";
        Object recipeObject;
        String treeText = "";
        String treeDigest = "";
        int seedFieldCount;
        List<String> seedChain = List.of();
        String compositeParts = "";
        String selectorText = "";
        boolean compositeVerified;
        String identityClass = "";
        int identityHash;

        EntryInfo(int index, String kind, CombineCandidate.Category category, float weight, int votes) {
            this.index = index;
            this.kind = kind;
            this.category = category;
            this.weight = weight;
            this.votes = votes;
        }

        void describeAtom(Object family) {
            identityClass = family.getClass().getName();
            identityHash = System.identityHashCode(family);
            Object recipe = firstCaptured(family);
            if (recipe == null) {
                label = "lambda-no-capture";
                return;
            }
            recipeObject = recipe;
            recipeClass = recipe.getClass().getSimpleName();
            Object v = tryRead(recipe, "variant");
            if (v instanceof Enum<?> en) {
                variant = en.name();
            }
            Object id = tryInvoke(recipe, "id");
            label = (id == null ? recipeClass : String.valueOf(id))
                    + (variant.isEmpty() ? "" : "[" + variant + "]");
            Object node = tryRead(recipe, "height");
            if (node != null) {
                StringBuilder sb = new StringBuilder();
                List<String> seedFields = new ArrayList<>();
                dumpNode(node, 0, sb, seedFields);
                treeText = sb.toString();
                seedChain = seedFields;
                seedFieldCount = seedFields.size();
                treeDigest = sha256Hex(treeText).substring(0, 16);
            }
        }
    }

    private int entryOfObject(Object family) {
        for (int i = 0; i < composites.length; i++) {
            if (composites[i] == family) {
                return i;
            }
        }
        for (int j = 0; j < unmixable.length; j++) {
            if (unmixable[j] == family) {
                return composites.length + j;
            }
        }
        return -1;
    }

    private String labelOf(Object family) {
        int idx = entryOfObject(family);
        return idx >= 0 ? entries[idx].label : "?";
    }

    /**
     * 判据：复合条目是否就是 {@code max(a + (b-a)*alpha, 0)}。
     * <p>同时确定反射拿到的两个族谁是 a、谁是 b —— alpha 非 0.5 时顺序敏感。</p>
     */
    private boolean compositeMatches(Object composite, Object sel, Object a, Object b) {
        if (!(sel instanceof RefNoise.Node selNode) || !(a instanceof CombineCandidate.Family fa)
                || !(b instanceof CombineCandidate.Family fb)
                || !(composite instanceof CombineCandidate.Family fc)) {
            return false;
        }
        int mismatches = 0;
        for (int k = 0; k < 6; k++) {
            double x = args.cx() + (k - 3) * 517.0 + 13.5;
            double z = args.cz() + ((k * 7) % 5 - 2) * 431.0 - 7.25;
            float sVal = selNode.compute((float) x, (float) z);
            float alpha = RefNoise.mapClamped(sVal, COMPOSITE_ALPHA_LO, COMPOSITE_ALPHA_HI);
            double v = fa.height01(x, z);
            double w = fb.height01(x, z);
            double expected = Math.max(v + (w - v) * alpha, 0.0);
            if (expected != fc.height01(x, z)) {
                mismatches++;
            }
        }
        return mismatches == 0;
    }

    // ================================================ noise-tree reconstruction

    /** 递归导出 {@link RefNoise} 节点树（只读反射，字段按名排序以保证摘要稳定）。 */
    private static void dumpNode(Object node, int depth, StringBuilder sb, List<String> seedFields) {
        if (depth > 40 || sb.length() > 400_000) {
            return;
        }
        sb.append(node.getClass().getSimpleName());
        for (Field f : sortedFields(node.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object v;
            try {
                v = f.get(node);
            } catch (IllegalAccessException e) {
                sb.append('\n').append(indent(depth + 1)).append("!access:").append(f.getName());
                continue;
            }
            if (v instanceof RefNoise.Node child) {
                sb.append('\n').append(indent(depth + 1)).append(f.getName()).append('=');
                dumpNode(child, depth + 1, sb, seedFields);
            } else if (v instanceof float[] arr) {
                sb.append('\n').append(indent(depth + 1)).append(f.getName())
                        .append("=float[").append(arr.length).append("]{")
                        .append(arr.length > 8 ? ".." : Arrays.toString(arr)).append('}');
            } else if (v instanceof int[] arr) {
                sb.append('\n').append(indent(depth + 1)).append(f.getName())
                        .append("=int[").append(arr.length).append("]{")
                        .append(arr.length > 8 ? ".." : Arrays.toString(arr)).append('}');
            } else if (v != null) {
                sb.append('\n').append(indent(depth + 1)).append(f.getName()).append('=')
                        .append(stableValue(v));
                if (f.getName().equals("seed") && !seedFields.contains(
                        node.getClass().getSimpleName() + ".seed=" + v)) {
                    seedFields.add(node.getClass().getSimpleName() + ".seed=" + v);
                }
            }
        }
    }

    /**
     * 标量字段的<b>稳定</b>文本形式。
     *
     * <p>关键：{@code RefNoise.Erosion} 持有 {@code cache = ThreadLocal.withInitial(...)}，
     * 它的 {@code default toString()} 是 {@code java.lang.ThreadLocal$SuppliedThreadLocal@59690aa4}
     * —— <b>哈希随进程变化</b>。若直接拼进树文本，{@code treeDigest} 会在两次运行间漂移，
     * 让「同命令重跑逐位相同」的比对出现假差异（实测踩到过：entry#17 m1-geo 的摘要
     * {@code 0250f1ace83e151f} → {@code 787eb6aefe709d5c}，差异仅此一个 ThreadLocal 引用）。</p>
     *
     * <p>因此对无价值语义的 JDK 实现对象只写真身类名的稳定部分（去掉 {@code @hash}），
     * 对真的 {@code RefNoise} 值型字段（record / 枚举 / 包装类型）仍原样输出。</p>
     */
    private static String stableValue(Object v) {
        if (v instanceof Number || v instanceof Boolean || v instanceof String
                || v instanceof Enum<?> || v instanceof Character) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        int at = s.lastIndexOf('@');
        if (at > 0 && at == s.length() - 9 && s.length() > 9) {
            // 形如 java.lang.ThreadLocal$SuppliedThreadLocal@59690aa4：去掉身份哈希
            return s.substring(0, at) + "@<identity>";
        }
        return s;
    }

    private static String indent(int depth) {
        return "  ".repeat(depth);
    }

    private static List<Field> sortedFields(Class<?> cls) {
        List<Field> list = new ArrayList<>(Arrays.asList(cls.getDeclaredFields()));
        list.sort((a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    private static List<String> nodeSeeds(Object node) {
        List<String> out = new ArrayList<>();
        if (node != null) {
            dumpNode(node, 0, new StringBuilder(), out);
        }
        return out;
    }

    /** 取原子族对象背后的配方对象（lambda 捕获的唯一非枚举/非标量引用）。 */
    private static Object firstCaptured(Object lambda) {
        if (lambda == null) {
            return null;
        }
        Object scalarFallback = null;
        for (Field f : sortedFields(lambda.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            try {
                Object v = f.get(lambda);
                if (v == null || v instanceof Enum<?> || v instanceof Number
                        || v instanceof String || v instanceof Boolean) {
                    if (v != null && scalarFallback == null) {
                        scalarFallback = v;
                    }
                    continue;
                }
                return v;
            } catch (IllegalAccessException ignored) {
                // 不静默兜底：调用点判据会暴露
            }
        }
        return scalarFallback;
    }

    private static Object entryRecipeNode(Object family) {
        Object recipe = firstCaptured(family);
        return recipe == null ? null : tryRead(recipe, "height");
    }

    private static Object selectorNode(Object composite) {
        for (Field f : sortedFields(composite.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            try {
                Object v = f.get(composite);
                if (v instanceof RefNoise.Node) {
                    return v;
                }
            } catch (IllegalAccessException ignored) {
                // 见 compositeMatches 判据
            }
        }
        return null;
    }

    /** 复合 selector 树里被消费的<b>第一个</b> int（{@code Perlin.ofScale(next.getAsInt(),600,1)}）。 */
    private static int selectorInputSeed(Object composite) {
        Object sel = selectorNode(composite);
        if (sel == null) {
            return Integer.MIN_VALUE;
        }
        Object input = read(sel, "input");
        return (int) read(input, "seed");
    }

    /** 复合 selector 的 warp 种子（{@code warpPerlin(..., next.getAsInt(), 300f, 2, 300f)}）。 */
    private static int selectorWarpSeed(Object composite) {
        Object sel = selectorNode(composite);
        if (sel == null) {
            return Integer.MIN_VALUE;
        }
        return (int) read(read(sel, "wx"), "seed");
    }

    /** 在整棵树里收集所有 {@code Warped} 节点的 (wx.seed, wz.seed)。 */
    private static void collectWarpSeeds(Object node, List<int[]> out, int depth) {
        if (node == null || depth > 40 || out.size() > 64) {
            return;
        }
        Object wx = tryRead(node, "wx");
        Object wz = tryRead(node, "wz");
        if (wx != null && wz != null) {
            Object s1 = tryRead(wx, "seed");
            Object s2 = tryRead(wz, "seed");
            if (s1 instanceof Integer a && s2 instanceof Integer b) {
                out.add(new int[]{a, b});
            }
        }
        for (Field f : sortedFields(node.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            try {
                Object v = f.get(node);
                if (v instanceof RefNoise.Node child) {
                    collectWarpSeeds(child, out, depth + 1);
                }
            } catch (IllegalAccessException ignored) {
                // 见调用点判据
            }
        }
    }

    private static Object tryRead(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static Object tryInvoke(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object read(Object target, String name) {
        Object v = tryRead(target, name);
        if (v == null) {
            throw new IllegalStateException("reflection miss: " + target.getClass().getName()
                    + "." + name);
        }
        return v;
    }

    private static Object readStatic(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    // ==================================================== phase 4: seed sequence

    /** 一次种子消费（或被派生的种子）。 */
    private record SeedUse(int index, String purpose, int expected, int observed,
                           boolean independent, String evidence) { }

    private void fingerprintSeeds() throws IOException {
        int b = (int) base;
        int idx = 0;
        int pairIndex = 0;
        for (int i = 0; i < mixable.length; i++) {
            for (int j = i + 1; j < mixable.length; j++) {
                int k = mixable.length + pairIndex;
                seedUses.add(new SeedUse(idx++, "composites[" + k + "].selector Perlin(600,1)",
                        b + 2 * pairIndex, selectorInputSeed(composites[k]), true,
                        "Warped.input.seed"));
                seedUses.add(new SeedUse(idx++, "composites[" + k + "].selector warpPerlin(300,2)",
                        b + 2 * pairIndex + 1, selectorWarpSeed(composites[k]), true,
                        "Warped.wx.seed (wz = +1)"));
                pairIndex++;
            }
        }
        // expandByWeight 不消耗随机
        shuffleSeedInt = b + 20;
        seedUses.add(new SeedUse(idx++, "Collections.shuffle(new Random(int))", b + 20,
                shuffleSeedInt, true, "bytecode order: buildComposites -> expandByWeight -> shuffle"));
        seedUses.add(new SeedUse(idx++, "partition.warpX Perlin(400,1)", b + 21,
                (int) read(warpX, "seed"), true, "partition.warpX.seed"));
        seedUses.add(new SeedUse(idx++, "partition.warpZ Perlin(400,1)", b + 22,
                (int) read(warpZ, "seed"), true, "partition.warpZ.seed"));
        seedUses.add(new SeedUse(idx++, "RegionPartition(int seed) — WHITE cell hash", b + 23,
                partitionSeed, true, "partition.seed"));
        List<int[]> warpSeeds = new ArrayList<>();
        collectWarpSeeds(read(blender, "mask"), warpSeeds, 0);
        int blenderWarpSeed = warpSeeds.isEmpty() ? Integer.MIN_VALUE : warpSeeds.get(0)[0];
        int blenderWarpSeedZ = warpSeeds.isEmpty() ? Integer.MIN_VALUE : warpSeeds.get(0)[1];
        seedUses.add(new SeedUse(idx++, "Blender.mask warpPerlin(333,2,250)", b + 24,
                blenderWarpSeed, true, "mask chain Warped.wx.seed"));
        seedUses.add(new SeedUse(idx, "Blender.mask warpPerlin wz (=前一个 int+1，非独立消费)",
                b + 25, blenderWarpSeedZ, false, "RefNoise.warpPerlin uses seed+1 for wz"));

        int mismatches = 0;
        int independent = 0;
        for (SeedUse u : seedUses) {
            if (u.independent) {
                independent++;
            }
            if (u.expected != u.observed) {
                mismatches++;
            }
        }
        checks.add("seed.sequence", mismatches == 0,
                "global cursor replayed: " + mismatches + " mismatch(es) over " + seedUses.size()
                        + " rows (" + independent + " independent consumptions, expected "
                        + EXPECT_SEED_INTS + ")");
        checks.add("seed.sequenceCount", independent == EXPECT_SEED_INTS,
                "independent seed ints consumed=" + independent + " expected=" + EXPECT_SEED_INTS);

        StringBuilder sb = new StringBuilder(
                "index,purpose,expectedObservedSeq,observed,independent,evidence,match\n");
        for (SeedUse u : seedUses) {
            sb.append(u.index).append(',').append(csv(u.purpose)).append(',').append(u.expected)
                    .append(',').append(u.observed).append(',').append(u.independent).append(',')
                    .append(csv(u.evidence)).append(',').append(u.expected == u.observed).append('\n');
        }
        writeText("seed-fingerprint.csv", sb.toString());

        // 各族自己的独立游标（每族 seed(worldSeed)、游标从 base 起）
        StringBuilder fam = new StringBuilder("entry,label,traversalOrder,nodeSeed\n");
        for (EntryInfo e : entries) {
            if (e.kind.equals("COMPOSITE")) {
                continue;
            }
            for (int i = 0; i < e.seedChain.size(); i++) {
                fam.append(e.index).append(',').append(csv(e.label)).append(',').append(i)
                        .append(',').append(csv(e.seedChain.get(i))).append('\n');
            }
        }
        writeText("family-seed-chains.csv", fam.toString());
        writeText("seed-fingerprint.sha256", seedSequenceFingerprint() + "  global\n"
                + globalFingerprint() + "  global+families\n");
    }

    private String seedSequenceFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (SeedUse u : seedUses) {
            sb.append(u.index).append('|').append(u.purpose).append('|').append(u.observed).append('\n');
        }
        return sha256Hex(sb.toString());
    }

    private String globalFingerprint() {
        StringBuilder sb = new StringBuilder(seedSequenceFingerprint());
        for (EntryInfo e : entries) {
            sb.append(e.index).append('|').append(e.label).append('|').append(e.seedChain).append('\n');
        }
        return sha256Hex(sb.toString());
    }

    // ======================================================== phase 5: shuffle

    private void verifyShuffle() throws IOException {
        int n = slots.length;
        int[] preEntry = preShuffleEntries();
        if (preEntry.length != n) {
            checks.add("shuffle.preShuffleCount", false,
                    "expandByWeight replica produced " + preEntry.length + " slots, actual " + n);
            return;
        }
        checks.add("shuffle.preShuffleCount", true,
                "expandByWeight replica produced " + n + " slots (weights / min weight, Math.round)");
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(shuffleSeedInt));
        int[] predicted = new int[n];
        for (int i = 0; i < n; i++) {
            predicted[i] = preEntry[order.get(i)];
        }
        int exact = 0;
        int setMismatch = 0;
        StringBuilder sb = new StringBuilder("slot,predictedEntry,actualEntry,match\n");
        for (int i = 0; i < n; i++) {
            boolean m = predicted[i] == entryOfSlot[i];
            if (m) {
                exact++;
            }
            sb.append(i).append(',').append(predicted[i]).append(',').append(entryOfSlot[i])
                    .append(',').append(m).append('\n');
        }
        for (int e = 0; e < entryCount; e++) {
            if (!positionsOf(predicted, e).equals(positionsOf(entryOfSlot, e))) {
                setMismatch++;
            }
        }
        checks.add("shuffle.replay", setMismatch == 0,
                "Collections.shuffle(new Random(" + shuffleSeedInt + ")) reproduces per-entry slot"
                        + " position sets; mismatchedEntries=" + setMismatch
                        + "; exactSlotMatches=" + exact + "/" + n);
        shuffleExactSlots = exact;
        writeText("shuffle-verify.csv", sb.toString());
    }

    private static List<Integer> positionsOf(int[] arr, int entry) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == entry) {
                out.add(i);
            }
        }
        return out;
    }

    /** {@code CombineCandidate.expandByWeight}（:328-358）的逐行复刻：权重 → 票槽。 */
    private int[] preShuffleEntries() {
        float[] weights = new float[entryCount];
        System.arraycopy(compositeWeights, 0, weights, 0, composites.length);
        for (int j = 0; j < unmixable.length; j++) {
            weights[composites.length + j] = unmixableWeight[j];
        }
        float smallest = Float.MAX_VALUE;
        for (float w : weights) {
            if (w > 0f) {
                smallest = Math.min(smallest, w);
            }
        }
        if (smallest == Float.MAX_VALUE) {
            smallest = 1f;
        }
        int[] counts = new int[weights.length];
        int total = 0;
        for (int i = 0; i < weights.length; i++) {
            counts[i] = weights[i] <= 0f ? 0 : Math.round(weights[i] / smallest);
            total += counts[i];
        }
        int[] out = new int[total];
        int k = 0;
        for (int i = 0; i < counts.length; i++) {
            for (int c = 0; c < counts[i]; c++) {
                out[k++] = i;
            }
        }
        return out;
    }

    // ========================================================= phase 6: mirror

    private void buildMirror() {
        mirror = new Mirror(partitionSeed, warpX, warpZ, warpStrength, slotCount);
        checks.add("mirror.warpStrength", warpStrength > 0f,
                "reflected WARP_STRENGTH=" + warpStrength + " (units: pre-region-scale blocks)");
    }

    /** {@link RegionPartition} 的只读镜像：同算术、同 float 精度，额外保留 9 邻域的排序。 */
    static final class Mirror {
        private final int seed;
        private final float frequency;
        private final float warpStrength;
        private final RefNoise.Node warpX;
        private final RefNoise.Node warpZ;
        private final int slotCount;

        Mirror(int seed, RefNoise.Node warpX, RefNoise.Node warpZ, float warpStrength, int slotCount) {
            this.seed = seed;
            this.frequency = 1f / RegionPartition.REGION_SCALE;
            this.warpStrength = warpStrength;
            this.warpX = warpX;
            this.warpZ = warpZ;
            this.slotCount = slotCount;
        }

        /** 复用缓冲（1401² 次采样不分配）。 */
        static final class Scratch {
            final int[] cellX = new int[9];
            final int[] cellZ = new int[9];
            final float[] dist = new float[9];
            final int[] order = new int[9];
            float regionId;
            float edge;
        }

        void sample(float x, float z, Scratch s) {
            float ox = (warpX.compute(x, z) - 0.5f) * warpStrength;
            float oz = (warpZ.compute(x, z) - 0.5f) * warpStrength;
            float px = (x + ox) * frequency;
            float pz = (z + oz) * frequency;
            int xi = RefNoise.floor(px);
            int yi = RefNoise.floor(pz);
            int k = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = xi + dx;
                    int cy = yi + dy;
                    float[] vec = RegionCellTable.CELL[hash2D(seed, cx, cy) & 0xFF];
                    float vx = cx + vec[0] * 0.7f;
                    float vy = cy + vec[1] * 0.7f;
                    float ddx = vx - px;
                    float ddy = vy - pz;
                    s.cellX[k] = cx;
                    s.cellZ[k] = cy;
                    // DistanceFunction.NATURAL
                    s.dist[k] = Math.abs(ddx) + Math.abs(ddy) + (ddx * ddx + ddy * ddy);
                    s.order[k] = k;
                    k++;
                }
            }
            // 稳定性：等距时保留「先遇到者」，与原实现的严格 `<` 一致
            for (int a = 0; a < 3; a++) {
                int best = a;
                for (int b = a + 1; b < 9; b++) {
                    if (s.dist[s.order[b]] < s.dist[s.order[best]]) {
                        best = b;
                    }
                }
                int t = s.order[a];
                s.order[a] = s.order[best];
                s.order[best] = t;
            }
            s.regionId = regionId(cellX(s, 0), cellZ(s, 0));
            s.edge = edgeValue(s.dist[s.order[0]], s.dist[s.order[1]]);
        }

        static int cellX(Scratch s, int rank) {
            return s.cellX[s.order[rank]];
        }

        static int cellZ(Scratch s, int rank) {
            return s.cellZ[s.order[rank]];
        }

        /** 槽位下标：格心 id → {@code RegionSelector.get}（{@code round(id × (N-1))}）。 */
        int slotOfCell(int cellX, int cellZ) {
            return RegionPartition.selectIndex(regionId(cellX, cellZ), slotCount);
        }

        /** 格心 id（本臂为 {@code IdMode.WHITE}：每格独立哈希）。 */
        float regionId(int cellX, int cellZ) {
            return mapValToUnit(valCoord2D(seed, cellX, cellZ));
        }

        static float valCoord2D(int seed, int x, int y) {
            int n = seed;
            n ^= 1619 * x;
            n ^= 31337 * y;
            return n * n * n * 60493 / 2.14748365E9F;
        }

        static float mapValToUnit(float v) {
            return RefNoise.mapClamped(v, -1f, 1f);
        }

        static int hash2D(int seed, int x, int y) {
            int hash = seed;
            hash ^= 1619 * x;
            hash ^= 31337 * y;
            hash = hash * hash * hash * 60493;
            hash ^= hash >> 13;
            return hash;
        }

        /** {@code RegionPartition.edgeValue} 的逐行复刻。 */
        static float edgeValue(float d1, float d2) {
            if (!(d2 > 0f)) {
                return 0f;
            }
            float v = d1 / d2 - 1f;
            float edge = 1f - RefNoise.mapClamped(v, -1f, 0f);
            edge = (float) Math.pow(edge, 1.5);
            if (edge < 0f) {
                return 0f;
            }
            if (edge > 0.5f) {
                return 1f;
            }
            return edge / 0.5f;
        }
    }

    // =================================================== phase 7: point compare

    private void comparePoints() throws IOException {
        int n = args.comparePoints();
        Mirror.Scratch sc = new Mirror.Scratch();
        StringBuilder sb = new StringBuilder("i,x,z,actualRegionId,mirrorRegionId,actualEdge,"
                + "mirrorEdge,actualSlot,mirrorSlot,slotMatch,actualSelected,mirrorSelected,heightDiff\n");
        int slotMismatch = 0;
        int idMismatch = 0;
        int edgeMismatch = 0;
        int heightMismatch = 0;
        double maxEdgeDiff = 0;
        double maxHeightDiff = 0;
        Random rnd = new Random(seed ^ 0x5A5A5A5AL);
        for (int i = 0; i < n; i++) {
            int ix = rnd.nextInt(args.size());
            int iz = rnd.nextInt(args.size());
            double wx = args.cx() + (ix - args.size() / 2.0) * args.step();
            double wz = args.cz() + (iz - args.size() / 2.0) * args.step();
            RegionPartition.Sample actual = partition.sample((float) wx, (float) wz);
            mirror.sample((float) wx, (float) wz, sc);
            int actualSlot = RegionPartition.selectIndex(actual.regionId(), slots.length);
            int mirrorSlot = RegionPartition.selectIndex(sc.regionId, slots.length);
            if (mirrorSlot != actualSlot) {
                slotMismatch++;
            }
            if (sc.regionId != actual.regionId()) {
                idMismatch++;
            }
            if (sc.edge != actual.edge()) {
                edgeMismatch++;
                maxEdgeDiff = Math.max(maxEdgeDiff, Math.abs((double) sc.edge - actual.edge()));
            }
            double actualSel = combine.debugSelected(wx, wz);
            double predicted = ((CombineCandidate.Family) slots[mirrorSlot]).height01(wx, wz);
            double diff = Math.abs(actualSel - predicted);
            if (diff > 0.0) {
                heightMismatch++;
                maxHeightDiff = Math.max(maxHeightDiff, diff);
            }
            sb.append(i).append(',').append(fmt1(wx)).append(',').append(fmt1(wz)).append(',')
                    .append(sc.regionId).append(',').append(actual.regionId()).append(',')
                    .append(sc.edge).append(',').append(actual.edge()).append(',')
                    .append(actualSlot).append(',').append(mirrorSlot).append(',')
                    .append(actualSlot == mirrorSlot).append(',')
                    .append(actualSel).append(',').append(predicted).append(',')
                    .append(diff).append('\n');
        }
        int stride = Math.max(1, args.size() / 40);
        int latticeChecked = 0;
        int latticeMismatch = 0;
        for (int iz = 0; iz < args.size(); iz += stride) {
            for (int ix = 0; ix < args.size(); ix += stride) {
                double wx = args.cx() + (ix - args.size() / 2.0) * args.step();
                double wz = args.cz() + (iz - args.size() / 2.0) * args.step();
                RegionPartition.Sample actual = partition.sample((float) wx, (float) wz);
                mirror.sample((float) wx, (float) wz, sc);
                latticeChecked++;
                if (actual.regionId() != sc.regionId || actual.edge() != sc.edge) {
                    latticeMismatch++;
                }
            }
        }
        checks.add("mirror.pointwise", slotMismatch == 0 && idMismatch == 0 && edgeMismatch == 0,
                "random points=" + n + " slotMismatch=" + slotMismatch + " regionIdMismatch="
                        + idMismatch + " edgeMismatch=" + edgeMismatch + " maxEdgeDiff="
                        + maxEdgeDiff);
        checks.add("mirror.lattice", latticeMismatch == 0,
                "gallery-lattice points=" + latticeChecked + " mismatches=" + latticeMismatch);
        checks.add("mirror.selectedHeight", heightMismatch == 0,
                "combine.debugSelected == slots[mirrorSlot].height01 on " + n + " points,"
                        + " nonBitEqual=" + heightMismatch + " maxDiff=" + maxHeightDiff);
        writeText("compare-points.csv", sb.toString());
    }

    // =================================================== phase 8: coverage census

    /** 覆盖层普查：mask 决定标签，高度源用 identity + 数值对拍证明。 */
    private void coverageCensus() throws IOException {
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        plateau.seed(seed + 44);
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        m1.seed(seed + 77);
        checkCoverageIdentity();

        CoverageTally tally = scanCoverage(plateau, m1);
        emitCoverageChecks(tally);
        writeText("coverage-census.csv", coverageCensusCsv(tally));
        writeText("coverage-cross.csv", coverageCrossCsv(tally));
        coverageSummary = coverageSummaryText(tally);
    }

    /** `Blender.mountain` 的 identity 必须是台地族（高度源），而标签是 MOUNTAINS。 */
    private void checkCoverageIdentity() {
        int covEntry = entryOfObject(coverageFamily);
        boolean identityOk = covEntry >= 0
                && entries[covEntry].category == CombineCandidate.Category.PLATEAU
                && entries[covEntry].label.startsWith("PLATEAU");
        checks.add("cover.identity", identityOk,
                "Blender.mountain identity -> entry#" + covEntry
                        + " label=" + (covEntry >= 0 ? entries[covEntry].label : "?")
                        + " category=" + (covEntry >= 0 ? entries[covEntry].category : "?")
                        + " || mask>=midpoint labels MOUNTAINS (Blender.category)");
    }

    /** 覆盖层采样统计（一次扫描的全部计数器）。 */
    private static final class CoverageTally {
        final long[] band = new long[5];
        final long[] labelByBand = new long[5 * 6];
        long labeledMountain;
        long labeledPure;
        long labeledBlend;
        long labeledRegionOnly;
        long samples;
        long plateauExact;
        long plateauMismatch;
        long m1Exact;
        double maxPlateauDiffInPure;
        boolean pureCoverageIsPlateau = true;
    }

    /** 按 stride 扫描窗口，同时统计 mask 分带、类型标签与「高度源假定」对拍。 */
    private CoverageTally scanCoverage(PlateauCandidate plateau, MountainsCandidate m1) {
        CoverageTally t = new CoverageTally();
        float midpoint = maskLow + (maskHigh - maskLow) * maskSplit;
        int stride = Math.max(1, args.coverageStride());
        for (int iz = 0; iz < args.size(); iz += stride) {
            double wz = args.cz() + (iz - args.size() / 2.0) * args.step();
            for (int ix = 0; ix < args.size(); ix += stride) {
                double wx = args.cx() + (ix - args.size() / 2.0) * args.step();
                sampleCoveragePoint(t, midpoint, plateau, m1, wx, wz);
            }
        }
        return t;
    }

    private void sampleCoveragePoint(CoverageTally t, float midpoint, PlateauCandidate plateau,
                                     MountainsCandidate m1, double wx, double wz) {
        float mask = combine.debugMountainMask(wx, wz);
        t.samples++;
        int b = maskBand(mask, midpoint);
        t.band[b]++;
        t.band[4]++;
        CombineCandidate.Category cat = combine.category(wx, wz);
        t.labelByBand[b * 6 + cat.ordinal()]++;
        if (cat == CombineCandidate.Category.MOUNTAINS) {
            t.labeledMountain++;
            if (b == 3) {
                t.labeledPure++;
            } else if (b == 2) {
                t.labeledBlend++;
            } else {
                t.labeledRegionOnly++;
            }
        }
        double h = combine.height01(wx, wz);
        double regionH = combine.debugRegionHeight(wx, wz);
        double hp = plateau.height01(wx, wz);
        double hm = m1.height01(wx, wz);
        if (h == blenderApplyReplay(mask, regionH, hp)) {
            t.plateauExact++;
        } else {
            t.plateauMismatch++;
        }
        if (h == blenderApplyReplay(mask, regionH, hm)) {
            t.m1Exact++;
        }
        if (b == 3) {
            double d = Math.abs(h - hp);
            t.maxPlateauDiffInPure = Math.max(t.maxPlateauDiffInPure, d);
            if (d != 0.0) {
                t.pureCoverageIsPlateau = false;
            }
        }
    }

    /** mask → 分带编号：0 纯区域 / 1 区域→标签中点 / 2 中点→纯覆盖 / 3 纯覆盖。 */
    private int maskBand(float mask, float midpoint) {
        if (mask < maskLow) {
            return 0;
        }
        if (mask < midpoint) {
            return 1;
        }
        return mask < maskHigh ? 2 : 3;
    }

    /** 逐位复刻 `Blender.apply` 的分支结构，覆盖族高度作为参数传入（唯一未知量）。 */
    private double blenderApplyReplay(float mask, double regionH, double coverageH) {
        if (mask < maskLow) {
            return regionH;
        }
        if (mask > maskHigh) {
            return coverageH;
        }
        float alpha = (mask - maskLow) / (maskHigh - maskLow);
        return regionH + (coverageH - regionH) * alpha;
    }

    private void emitCoverageChecks(CoverageTally t) {
        checks.add("cover.pureCoverageIsPlateau",
                t.pureCoverageIsPlateau && (t.band[3] > 0 || t.band[2] > 0),
                "pure coverage band (mask>=" + maskHigh + "): samples=" + t.band[3]
                        + " maxDiff=" + t.maxPlateauDiffInPure
                        + (t.band[3] == 0
                        ? " (band EMPTY in this window => the check is carried by the blend band"
                        + " instead, see cover.heightSourceIsPlateau)"
                        : " bit-for-bit"));
        checks.add("cover.heightSourceIsPlateau", t.plateauMismatch == 0,
                "Blender.apply replayed with the coverage family substituted: coverage=plateau"
                        + " matches " + t.plateauExact + "/" + t.samples
                        + " (mismatch=" + t.plateauMismatch + "); coverage=m1-geo would match only "
                        + t.m1Exact + "/" + t.samples
                        + " => heights are the plateau recipe everywhere the mask takes over,"
                        + " while the type label there is MOUNTAINS");
    }

    private String coverageCensusCsv(CoverageTally t) {
        float midpoint = maskLow + (maskHigh - maskLow) * maskSplit;
        StringBuilder sb = new StringBuilder("band,maskRange,samples,share,labelTags\n");
        String[] names = {"0:regionOnly(mask<" + maskLow + ")", "1:region->mid",
                "2:mid->pureCoverage", "3:pureCoverage(mask>=" + maskHigh + ")", "4:total"};
        double[] lo = {-1, maskLow, midpoint, maskHigh, -1};
        double[] hi = {maskLow, midpoint, maskHigh, 2, -1};
        for (int b = 0; b < 5; b++) {
            sb.append(names[b]).append(',').append(fmt1(lo[b])).append("..").append(fmt1(hi[b]))
                    .append(',').append(t.band[b]).append(',').append(share(t.band[b], t.band[4]))
                    .append(',');
            StringBuilder tags = new StringBuilder();
            for (CombineCandidate.Category c : CombineCandidate.Category.values()) {
                long v = t.labelByBand[b * 6 + c.ordinal()];
                if (v > 0) {
                    tags.append(c).append(':').append(v).append(' ');
                }
            }
            sb.append(csv(tags.toString().trim())).append('\n');
        }
        return sb.toString();
    }

    private String coverageCrossCsv(CoverageTally t) {
        StringBuilder cross = new StringBuilder("item,count,shareOfSamples\n");
        cross.append("labeledMOUNTAINS_total,").append(t.labeledMountain).append(',')
                .append(share(t.labeledMountain, t.samples)).append('\n');
        cross.append("labeledMOUNTAINS_pureCoverage,").append(t.labeledPure).append(',')
                .append(share(t.labeledPure, t.samples)).append('\n');
        cross.append("labeledMOUNTAINS_blendBand,").append(t.labeledBlend).append(',')
                .append(share(t.labeledBlend, t.samples)).append('\n');
        cross.append("labeledMOUNTAINS_regionOnly,").append(t.labeledRegionOnly).append(',')
                .append(share(t.labeledRegionOnly, t.samples)).append('\n');
        cross.append("heightMatchesCoverageEqualsPlateau,").append(t.plateauExact).append(',')
                .append(share(t.plateauExact, t.samples)).append('\n');
        cross.append("heightMatchesCoverageEqualsM1,").append(t.m1Exact).append(',')
                .append(share(t.m1Exact, t.samples)).append('\n');
        return cross.toString();
    }

    private String coverageSummaryText(CoverageTally t) {
        return "samples=" + t.samples + " bands(region/low/mid/pure)=" + t.band[0] + "/"
                + t.band[1] + "/" + t.band[2] + "/" + t.band[3]
                + " labeledMOUNTAINS=" + t.labeledMountain + " (pure=" + t.labeledPure
                + ", blend=" + t.labeledBlend + ", regionOnly=" + t.labeledRegionOnly + ")"
                + " coverage=plateau matches=" + t.plateauExact
                + " coverage=m1 matches=" + t.m1Exact;
    }

    // ============================================== phase 9: neighbourhood census

    private void neighbourhoodCensus() throws IOException {
        CensusAccumulator acc = new CensusAccumulator(args.size(), entryCount);
        scanCensusGrid(acc);
        acc.writeOutputs(this);
        censusSummary = acc.summaryText();
    }

    /** 逐像素扫描窗口；行/列推进由本方法负责，统计并入 {@link CensusAccumulator}。 */
    private void scanCensusGrid(CensusAccumulator acc) {
        int size = args.size();
        Mirror.Scratch sc = new Mirror.Scratch();
        for (int iz = 0; iz < size; iz++) {
            double wz = args.cz() + (iz - size / 2.0) * args.step();
            for (int ix = 0; ix < size; ix++) {
                double wx = args.cx() + (ix - size / 2.0) * args.step();
                mirror.sample((float) wx, (float) wz, sc);
                acc.observe(sc, wx, wz, ix, iz);
            }
            acc.endRow();
        }
    }

    private static int minDistinct(long[] hist) {
        for (int d = 1; d <= 9; d++) {
            if (hist[d] > 0) {
                return d;
            }
        }
        return 0;
    }

    private void writeCensusStub() throws IOException {
        writeText("census-summary.csv", "metric,value\nskipped,true\n");
    }

    /**
     * 邻域普查累加器：持有全部计数器与 18×18 配方对矩阵，负责把一次采样并入统计。
     *
     * <p>拆成独立类只是为了把一个超长扫描方法按职责切开 —— 语义与原单方法逐位相同。</p>
     */
    private final class CensusAccumulator {
        final int size;
        final int n;
        final long[][] pairPixels;
        final long[][] nearPixels;
        final double[][] nearGapSum;
        final double[][] pairGapSum;
        final float[][] pairGapMin;
        final float[][] pairGapMax;
        final double[][] pairX;
        final double[][] pairZ;
        final double[][] pairXMin;
        final double[][] pairXMax;
        final double[][] pairZMin;
        final double[][] pairZMax;
        final long[] distinctHist = new long[10];
        final long[] rankPixels;
        final Map<String, long[]> tripleCounts = new HashMap<>();
        long entryFlips;
        long sameCellEntryFlips;
        long nearestEqualsSecond;
        long nearestDiffersSecond;
        long nearestSameButThirdDiffers;
        long tripleDistinct;
        long pixelCount;

        /** 上一行 / 当前行的最近格配方与格心（跨行邻接与同格换配方判定用）。 */
        int[] prevRow;
        int[] curRow;
        int[] prevRowCellX;
        int[] prevRowCellZ;
        int[] curRowCellX;
        int[] curRowCellZ;

        CensusAccumulator(int size, int n) {
            this.size = size;
            this.n = n;
            this.pairPixels = new long[n][n];
            this.nearPixels = new long[n][n];
            this.nearGapSum = new double[n][n];
            this.pairGapSum = new double[n][n];
            this.pairGapMin = new float[n][n];
            this.pairGapMax = new float[n][n];
            this.pairX = new double[n][n];
            this.pairZ = new double[n][n];
            this.pairXMin = new double[n][n];
            this.pairXMax = new double[n][n];
            this.pairZMin = new double[n][n];
            this.pairZMax = new double[n][n];
            this.rankPixels = new long[3 * n];
            fillNaN();
            this.prevRow = new int[size];
            this.curRow = new int[size];
            this.prevRowCellX = new int[size];
            this.prevRowCellZ = new int[size];
            this.curRowCellX = new int[size];
            this.curRowCellZ = new int[size];
        }

        private void fillNaN() {
            for (double[][] m : List.of(pairXMin, pairXMax, pairZMin, pairZMax)) {
                for (double[] row : m) {
                    Arrays.fill(row, Double.NaN);
                }
            }
            for (float[][] m : List.of(pairGapMin, pairGapMax)) {
                for (float[] row : m) {
                    Arrays.fill(row, Float.NaN);
                }
            }
        }

        /** 并入一个采样点：{@code ix}/{@code iz} 是栅格下标，{@code wx}/{@code wz} 是世界块坐标。 */
        void observe(Mirror.Scratch sc, double wx, double wz, int ix, int iz) {
            int[] e = new int[3];
            for (int rank = 0; rank < 3; rank++) {
                e[rank] = entryOfCellCell(Mirror.cellX(sc, rank), Mirror.cellZ(sc, rank));
                rankPixels[rank * n + e[rank]]++;
            }
            curRow[ix] = e[0];
            curRowCellX[ix] = Mirror.cellX(sc, 0);
            curRowCellZ[ix] = Mirror.cellZ(sc, 0);
            pixelCount++;
            distinctHist[Math.min(9, distinctOf(sc))]++;
            tallyRecipePairRanks(e);
            float gap = sc.dist[sc.order[1]] - sc.dist[sc.order[0]];
            if (ix > 0) {
                tallyHorizontal(wx, wz, ix, gap, e[0]);
            }
            if (iz > 0) {
                tallyVertical(wx, wz, ix, gap, e[0]);
            }
        }

        /** 最近 / 次近 / 第三近三条配方之间的关系计数。 */
        private void tallyRecipePairRanks(int[] e) {
            if (e[0] == e[1]) {
                nearestEqualsSecond++;
                if (e[0] != e[2]) {
                    nearestSameButThirdDiffers++;
                }
            } else {
                nearestDiffersSecond++;
                int lo = Math.min(e[0], e[1]);
                int hi = Math.max(e[0], e[1]);
                nearPixels[lo][hi]++;
            }
            if (e[0] != e[1] && e[1] != e[2] && e[0] != e[2]) {
                tripleDistinct++;
                String key = tripleKey(e[0], e[1], e[2]);
                tripleCounts.computeIfAbsent(key, k -> new long[1])[0]++;
            }
        }

        private void tallyHorizontal(double wx, double wz, int ix, float gap, int entry) {
            int leftEntry = curRow[ix - 1];
            tallyEdge(pairPixels, pairGapSum, pairGapMin, pairGapMax, pairX, pairZ,
                    pairXMin, pairXMax, pairZMin, pairZMax, leftEntry, entry, gap, wx, wz);
            if (leftEntry != entry) {
                entryFlips++;
                if (curRowCellX[ix - 1] == curRowCellX[ix]
                        && curRowCellZ[ix - 1] == curRowCellZ[ix]) {
                    sameCellEntryFlips++;
                }
            }
        }

        private void tallyVertical(double wx, double wz, int ix, float gap, int entry) {
            tallyEdge(pairPixels, pairGapSum, pairGapMin, pairGapMax, pairX, pairZ,
                    pairXMin, pairXMax, pairZMin, pairZMax, prevRow[ix], entry, gap, wx, wz);
            if (prevRow[ix] != entry && prevRowCellX[ix] == curRowCellX[ix]
                    && prevRowCellZ[ix] == curRowCellZ[ix]) {
                sameCellEntryFlips++;
            }
        }

        /** 行末交换双缓冲（引用交换，与原实现的 {@code int[] t = prevRow; prevRow = curRow; ...} 等价）。 */
        void endRow() {
            int[] tRow = prevRow;
            prevRow = curRow;
            curRow = tRow;
            int[] tCellX = prevRowCellX;
            prevRowCellX = curRowCellX;
            curRowCellX = tCellX;
            int[] tCellZ = prevRowCellZ;
            prevRowCellZ = curRowCellZ;
            curRowCellZ = tCellZ;
        }

        void writeOutputs(SandboxInventoryProbe probe) throws IOException {
            probe.writeAdjacencyCsv(pairPixels, nearPixels, nearGapSum, pairGapSum, pairGapMin,
                    pairGapMax, pairX, pairZ, pairXMin, pairXMax, pairZMin, pairZMax);
            probe.writeTriplesCsv(tripleCounts, tripleDistinct);
            probe.writeRankCsv(rankPixels, pixelCount);
            probe.writeVoteVsObservedCsv(rankPixels, pixelCount);
            probe.writeText("census-summary.csv", summaryCsv());
        }

        String summaryCsv() {
            long transitions = sumPairs(pairPixels);
            StringBuilder sb = new StringBuilder("metric,value\n");
            sb.append("windowPixels,").append(pixelCount).append('\n');
            sb.append("size,").append(size).append('\n');
            sb.append("stepBlocks,").append(args.step()).append('\n');
            sb.append("recipeTransitionPixelPairs,").append(transitions).append('\n');
            sb.append("entryFlipsRightDown,").append(entryFlips).append('\n');
            sb.append("entryFlipsSameCell,").append(sameCellEntryFlips).append('\n');
            sb.append("nearestEqualsSecondRecipe,").append(nearestEqualsSecond).append('\n');
            sb.append("nearestDiffersSecondRecipe,").append(nearestDiffersSecond).append('\n');
            sb.append("nearestEqualsSecondButThirdDiffers,").append(nearestSameButThirdDiffers)
                    .append('\n');
            sb.append("pixelsWith3DistinctNearRecipes,").append(tripleDistinct).append('\n');
            sb.append("distinctRecipesMin,").append(minDistinct(distinctHist)).append('\n');
            for (int d = 1; d <= 9; d++) {
                sb.append("distinctRecipesAmong9WindowCells_").append(d).append(',')
                        .append(distinctHist[d]).append('\n');
            }
            sb.append("note_distinctRecipesAmong9WindowCells,")
                    .append(csv("counts the 9 jittered Voronoi cells of the query 3x3 window"
                            + " (span up to 2400 blocks), NOT the candidates within a 120-block"
                            + " reach; it shows recipe scattering, not transition topology"))
                    .append('\n');
            sb.append("note_nearestEqualsSecondRecipe,")
                    .append(csv("pixels whose two nearest cells map to the SAME logical recipe"
                            + " (same-recipe edge, should stay seamless); share="
                            + share(nearestEqualsSecond, pixelCount)))
                    .append('\n');
            return sb.toString();
        }

        String summaryText() {
            return "pixels=" + pixelCount + " transitionPairs=" + sumPairs(pairPixels)
                    + " entryFlips=" + entryFlips + " (sameCell=" + sameCellEntryFlips + ")"
                    + " nearest==second=" + nearestEqualsSecond
                    + " nearest!=second=" + nearestDiffersSecond
                    + " threeDistinct=" + tripleDistinct
                    + " minDistinctIn3x3=" + minDistinct(distinctHist);
        }
    }

    private void tallyEdge(long[][] pixels, double[][] gapSum, float[][] gapMin, float[][] gapMax,
                           double[][] sx, double[][] sz, double[][] xMin, double[][] xMax,
                           double[][] zMin, double[][] zMax,
                           int a, int b, float gap, double wx, double wz) {
        if (a < 0 || b < 0 || a == b) {
            return;
        }
        int i = Math.min(a, b);
        int j = Math.max(a, b);
        pixels[i][j]++;
        gapSum[i][j] += gap;
        gapMin[i][j] = Float.isNaN(gapMin[i][j]) ? gap : Math.min(gapMin[i][j], gap);
        gapMax[i][j] = Float.isNaN(gapMax[i][j]) ? gap : Math.max(gapMax[i][j], gap);
        sx[i][j] += wx;
        sz[i][j] += wz;
        xMin[i][j] = Double.isNaN(xMin[i][j]) ? wx : Math.min(xMin[i][j], wx);
        xMax[i][j] = Double.isNaN(xMax[i][j]) ? wx : Math.max(xMax[i][j], wx);
        zMin[i][j] = Double.isNaN(zMin[i][j]) ? wz : Math.min(zMin[i][j], wz);
        zMax[i][j] = Double.isNaN(zMax[i][j]) ? wz : Math.max(zMax[i][j], wz);
    }

    /**
     * 票权 vs 观测最近格占比。
     *
     * <p>票权 = {@code round(w/minW)} 的槽位份额；观测 = “该条目是最近格”的像素份额。
     * 两者<b>不应</b>相等：{@code RegionSelector.get} 用 {@code round(id×(N−1))}，
     * 端点两个槽各占半宽区间 ⇒ 概率 ∝ 槽宽而非票数（已在 {@code RegionPartition} 注释记录）。
     * 本表把这条偏差量化，供“票数 ≠ 面积”的讨论用。</p>
     */
    private void writeVoteVsObservedCsv(long[] rankPixels, long pixels) throws IOException {
        StringBuilder sb = new StringBuilder(
                "entry,label,category,votes,voteShare,nearestCells,nearestShare,nearestOverVote\n");
        for (int e = 0; e < entryCount; e++) {
            long near = rankPixels[e];
            sb.append(e).append(',').append(csv(entries[e].label)).append(',')
                    .append(entries[e].category).append(',').append(entries[e].votes).append(',')
                    .append(share(entries[e].votes, slotCount)).append(',')
                    .append(near).append(',')
                    .append(share(near, pixels)).append(',')
                    .append(entries[e].votes == 0 ? "NaN"
                            : fmt6((near * 1.0 / pixels) / (entries[e].votes * 1.0 / slotCount)))
                    .append('\n');
        }
        writeText("census-vote-vs-observed.csv", sb.toString());
    }

    private void writeAdjacencyCsv(long[][] pixels, long[][] nearPixels, double[][] nearGapSum,
                                   double[][] gapSum, float[][] gapMin,
                                   float[][] gapMax, double[][] sx, double[][] sz,
                                   double[][] xMin, double[][] xMax, double[][] zMin,
                                   double[][] zMax) throws IOException {
        long transitions = sumPairs(pixels);
        long nearTransitions = sumPairs(nearPixels);
        StringBuilder sb = new StringBuilder("entryA,labelA,catA,votesA,entryB,labelB,catB,votesB,"
                + "pixelPairs,shareOfTransitions,nearestSecondPixels,shareOfNearestSecond,"
                + "gapNatMean,gapNatMin,gapNatMax,meanX,meanZ,"
                + "bboxXMin,bboxXMax,bboxZMin,bboxZMax\n");
        for (int i = 0; i < entryCount; i++) {
            for (int j = i + 1; j < entryCount; j++) {
                if (pixels[i][j] == 0 && nearPixels[i][j] == 0) {
                    continue;
                }
                long p = pixels[i][j];
                sb.append(i).append(',').append(csv(entries[i].label)).append(',')
                        .append(entries[i].category).append(',').append(entries[i].votes).append(',')
                        .append(j).append(',').append(csv(entries[j].label)).append(',')
                        .append(entries[j].category).append(',').append(entries[j].votes).append(',')
                        .append(p).append(',').append(share(p, transitions)).append(',')
                        .append(nearPixels[i][j]).append(',').append(share(nearPixels[i][j], nearTransitions))
                        .append(',')
                        .append(p == 0 ? "NaN" : fmt6(gapSum[i][j] / p)).append(',')
                        .append(gapMin[i][j]).append(',').append(gapMax[i][j]).append(',')
                        .append(p == 0 ? "NaN" : fmt1(sx[i][j] / p)).append(',')
                        .append(p == 0 ? "NaN" : fmt1(sz[i][j] / p)).append(',')
                        .append(fmt1(xMin[i][j])).append(',').append(fmt1(xMax[i][j])).append(',')
                        .append(fmt1(zMin[i][j])).append(',').append(fmt1(zMax[i][j])).append('\n');
            }
        }
        writeText("census-adjacency.csv", sb.toString());
    }

    private void writeTriplesCsv(Map<String, long[]> counts, long total) throws IOException {
        List<Map.Entry<String, long[]>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder("entryA,entryB,entryC,labelA,labelB,labelC,pixels,share\n");
        for (Map.Entry<String, long[]> e : list) {
            String[] id = e.getKey().split("\\|");
            int a = Integer.parseInt(id[0]);
            int b = Integer.parseInt(id[1]);
            int c = Integer.parseInt(id[2]);
            sb.append(a).append(',').append(b).append(',').append(c).append(',')
                    .append(csv(entries[a].label)).append(',').append(csv(entries[b].label))
                    .append(',').append(csv(entries[c].label)).append(',')
                    .append(e.getValue()[0]).append(',')
                    .append(share(e.getValue()[0], Math.max(1, total))).append('\n');
        }
        writeText("census-triples.csv", sb.toString());
    }

    private void writeRankCsv(long[] rankPixels, long pixels) throws IOException {        StringBuilder sb = new StringBuilder("rank(0=nearest),entry,label,votes,pixels,share\n");
        for (int rank = 0; rank < 3; rank++) {
            for (int e = 0; e < entryCount; e++) {
                long v = rankPixels[rank * entryCount + e];
                sb.append(rank).append(',').append(e).append(',').append(csv(entries[e].label))
                        .append(',').append(entries[e].votes).append(',').append(v).append(',')
                        .append(share(v, pixels)).append('\n');
            }
        }
        writeText("census-near-rank.csv", sb.toString());
    }

    private long sumPairs(long[][] m) {
        long s = 0;
        for (long[] row : m) {
            for (long v : row) {
                s += v;
            }
        }
        return s;
    }

    private int distinctOf(Mirror.Scratch sc) {
        int[] ids = new int[9];
        int found = 0;
        for (int k = 0; k < 9; k++) {
            int e = entryOfCellCell(sc.cellX[k], sc.cellZ[k]);
            boolean seen = false;
            for (int t = 0; t < found; t++) {
                if (ids[t] == e) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                ids[found++] = e;
            }
        }
        return found;
    }

    /**
     * 格心 → <b>逻辑条目</b>下标：格心 id → 槽位（{@code RegionPartition.selectIndex}）→ 条目。
     * <p>槽位与条目不是一回事：45 个票槽才映射回 18 条逻辑配方。</p>
     */
    private int entryOfCellCell(int cellX, int cellZ) {
        return entryOfSlot[mirror.slotOfCell(cellX, cellZ)];
    }

    private static String tripleKey(int a, int b, int c) {
        int[] v = {a, b, c};
        Arrays.sort(v);
        return v[0] + "|" + v[1] + "|" + v[2];
    }

    // ========================================================= phase 10: output

    private void writeInventoryJson() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendJsonHeader(sb);
        appendJsonEnv(sb);
        appendJsonCounts(sb);
        appendJsonMaskAndCoverage(sb);
        appendJsonEntries(sb);
        appendJsonVerdict(sb);
        sb.append("  ]\n}\n");
        writeText("inventory.json", sb.toString());
    }

    private void appendJsonHeader(StringBuilder sb) {
        sb.append("  \"probe\": \"SandboxInventoryProbe\",\n");
        sb.append("  \"phase\": \"1 — inventory only; NOT a topology or transition-width proof\",\n");
        sb.append("  \"seed\": ").append(seed).append(",\n");
        sb.append("  \"seedIntBase\": ").append(base).append(",\n");
        sb.append("  \"seedSource\": \"reflected LandCompositionGallery.SEED; buildCombine hardcodes it\",\n");
        sb.append("  \"regionalModeScale\": ").append(REGIONAL_MODE_SCALE).append(",\n");
        sb.append("  \"buildCall\": \"LandCompositionGallery.buildCombineRegional(seed,3600f) via reflection\",\n");
        sb.append("  \"window\": {\"size\": ").append(args.size())
                .append(", \"stepBlocks\": ").append(args.step())
                .append(", \"centerBlocks\": [").append(fmt1(args.cx())).append(", ")
                .append(fmt1(args.cz())).append("], \"originBlocks\": [")
                .append(fmt1(args.cx() - (args.size() / 2) * (double) args.step())).append(", ")
                .append(fmt1(args.cz() - (args.size() / 2) * (double) args.step())).append("]},\n");
        sb.append("  \"windowNote\": \"gallery loop uses cx + (x - size/2)*step => 0.5-step offset"
                + " vs half-block centers\",\n");
    }

    /** 运行环境（非稳定字段：不同机器/时间必然不同，比对指纹时应剔除）。 */
    private void appendJsonEnv(StringBuilder sb) {
        sb.append("  \"mixable\": ").append(mixable.length).append(",\n");
        sb.append("  \"javaVersion\": \"").append(esc(System.getProperty("java.version")))
                .append("\",\n");
        sb.append("  \"javaHome\": \"").append(esc(System.getProperty("java.home"))).append("\",\n");
        sb.append("  \"classpath\": \"").append(esc(System.getProperty("java.class.path")))
                .append("\",\n");
        sb.append("  \"argv\": [");
        for (int i = 0; i < args.raw().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(esc(args.raw().get(i))).append('"');
        }
        sb.append("],\n");
    }

    private void appendJsonCounts(StringBuilder sb) {
        sb.append("  \"composites\": ").append(composites.length)
                .append(",\n  \"unmixable\": ").append(unmixable.length)
                .append(",\n  \"entries\": ").append(entryCount)
                .append(",\n  \"slots\": ").append(slotCount).append(",\n");
        sb.append("  \"shuffleSeedInt\": ").append(shuffleSeedInt).append(",\n");
        sb.append("  \"shuffleExactSlotMatches\": ").append(shuffleExactSlots).append(",\n");
        sb.append("  \"seedSequenceFingerprintSha256\": \"")
                .append(seedSequenceFingerprint()).append("\",\n");
        sb.append("  \"globalFingerprintSha256\": \"").append(globalFingerprint()).append("\",\n");
    }

    private void appendJsonMaskAndCoverage(StringBuilder sb) {
        sb.append("  \"maskBlend\": {\"low\": ").append(maskLow).append(", \"high\": ").append(maskHigh)
                .append(", \"split\": ").append(maskSplit).append(", \"labelMidpoint\": ")
                .append(maskLow + (maskHigh - maskLow) * maskSplit).append("},\n");
        int covEntry = entryOfObject(coverageFamily);
        sb.append("  \"coverageFamily\": {\"entryIndex\": ").append(covEntry)
                .append(", \"label\": \"").append(esc(covEntry >= 0 ? entries[covEntry].label : "?"))
                .append("\", \"recipeCategory\": \"")
                .append(covEntry >= 0 ? entries[covEntry].category : "?").append('"')
                .append(", \"heightSource\": \"plateau recipe (unmixable[0] = Blender.mountain)\"")
                .append(", \"typeLabelWhenMaskHigh\": \"MOUNTAINS\"")
                .append(", \"inconsistency\": \"mask>=labelMidpoint labels MOUNTAINS while the height"
                        + " source is the plateau recipe; retained as-is in this phase\"")
                .append("},\n");
        sb.append("  \"coverageCensus\": \"").append(esc(coverageSummary)).append("\",\n");
        sb.append("  \"census\": \"").append(esc(censusSummary)).append("\",\n");
    }

    private void appendJsonEntries(StringBuilder sb) {
        sb.append("  \"entryTable\": [\n");
        for (int i = 0; i < entries.length; i++) {
            EntryInfo e = entries[i];
            sb.append("    {\"index\": ").append(e.index)
                    .append(", \"kind\": \"").append(e.kind).append('"')
                    .append(", \"label\": \"").append(esc(e.label)).append('"')
                    .append(", \"category\": \"").append(e.category).append('"')
                    .append(", \"weight\": ").append(e.weight)
                    .append(", \"votes\": ").append(e.votes)
                    .append(", \"identityClass\": \"").append(esc(e.identityClass)).append('"')
                    .append(", \"identityHash\": ").append(e.identityHash)
                    .append(", \"recipeClass\": \"").append(esc(e.recipeClass)).append('"')
                    .append(", \"variant\": \"").append(esc(e.variant)).append('"')
                    .append(", \"treeDigest\": \"").append(e.treeDigest).append('"')
                    .append(", \"seedFieldCount\": ").append(e.seedFieldCount)
                    .append(", \"compositeFormulaVerified\": ")
                    .append(e.kind.equals("COMPOSITE") ? String.valueOf(e.compositeVerified) : "null")
                    .append("}").append(i + 1 < entries.length ? "," : "").append('\n');
        }
        sb.append("  ],\n");
    }

    private void appendJsonVerdict(StringBuilder sb) {
        sb.append("  \"verdict\": [\n");
        for (int i = 0; i < checks.rows.size(); i++) {
            String[] r = checks.rows.get(i);
            sb.append("    {\"id\": \"").append(esc(r[0])).append("\", \"result\": \"").append(r[1])
                    .append("\", \"detail\": \"").append(esc(r[2])).append('"')
                    .append('}').append(i + 1 < checks.rows.size() ? "," : "").append('\n');
        }
    }

    private void writeEntriesCsv() throws IOException {
        StringBuilder sb = new StringBuilder("entry,kind,label,recipeClass,variant,category,weight,"
                + "votes,shareOfSlots,treeDigest,seedFields,identityClass,identityHash\n");
        for (EntryInfo e : entries) {
            sb.append(e.index).append(',').append(e.kind).append(',').append(csv(e.label)).append(',')
                    .append(csv(e.recipeClass)).append(',').append(csv(e.variant)).append(',')
                    .append(e.category).append(',').append(e.weight).append(',').append(e.votes)
                    .append(',').append(share(e.votes, slotCount)).append(',')
                    .append(e.treeDigest).append(',').append(e.seedFieldCount).append(',')
                    .append(csv(e.identityClass)).append(',').append(e.identityHash).append('\n');
        }
        writeText("entries.csv", sb.toString());
    }

    private void writeCompositesCsv() throws IOException {
        StringBuilder sb = new StringBuilder("compositeIndex,pairIndex,labelA,labelB,category,weight,"
                + "votes,selectorDigest,selectorSeedFields,formulaVerified\n");
        int pairIndex = 0;
        for (int i = mixable.length; i < composites.length; i++) {
            EntryInfo e = entries[i];
            String[] parts = e.compositeParts.split(" \\+ ");
            sb.append(i).append(',').append(pairIndex++).append(',')
                    .append(csv(parts.length > 0 ? parts[0] : "")).append(',')
                    .append(csv(parts.length > 1 ? parts[1] : "")).append(',')
                    .append(e.category).append(',').append(e.weight).append(',')
                    .append(e.votes).append(',').append(e.treeDigest).append(',')
                    .append(e.seedFieldCount).append(',').append(e.compositeVerified).append('\n');
        }
        writeText("composites.csv", sb.toString());
    }

    private void writeSlotsCsv() throws IOException {
        StringBuilder sb = new StringBuilder("slot,entry,label,category,recipeWeight,votesOfEntry\n");
        for (int i = 0; i < slots.length; i++) {
            int e = entryOfSlot[i];
            EntryInfo info = e >= 0 ? entries[e] : null;
            sb.append(i).append(',').append(e).append(',')
                    .append(csv(info == null ? "?" : info.label)).append(',')
                    .append(slotCats[i]).append(',')
                    .append(info == null ? "?" : String.valueOf(info.weight)).append(',')
                    .append(info == null ? "?" : String.valueOf(info.votes)).append('\n');
        }
        writeText("slots.csv", sb.toString());
    }

    private void writeRecipeTreesJson() throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        for (int i = 0; i < entries.length; i++) {
            EntryInfo e = entries[i];
            sb.append("  \"").append(i).append("\": {\"label\": \"").append(esc(e.label))
                    .append("\", \"kind\": \"").append(e.kind)
                    .append("\", \"digest\": \"").append(e.treeDigest)
                    .append("\", \"tree\": \"").append(esc(e.treeText)).append("\"}")
                    .append(i + 1 < entries.length ? "," : "").append('\n');
        }
        sb.append("}\n");
        writeText("recipe-trees.json", sb.toString());
    }

    private void writeVerdictCsv() throws IOException {
        StringBuilder sb = new StringBuilder("id,result,detail\n");
        for (String[] r : checks.rows) {
            sb.append(csv(r[0])).append(',').append(r[1]).append(',').append(csv(r[2])).append('\n');
        }
        writeText("verdict.csv", sb.toString());
    }

    // ============================================================== utilities

    private void writeText(String name, String content) throws IOException {
        Files.writeString(args.out().resolve(name), content, StandardCharsets.UTF_8);
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace('\n', ' ').replace('\r', ' ');
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String esc(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    private static String fmt1(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt6(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String share(long a, long total) {
        return String.format(Locale.ROOT, "%.6f", total == 0 ? 0.0 : a * 1.0 / total);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "sha256-unavailable";
        }
    }

    // ================================================================== types

    /** 判据收集器：{@code PASS / WARN / FAIL}；任一 FAIL ⇒ 非零退出。 */
    static final class Checks {
        final List<String[]> rows = new ArrayList<>();

        void add(String id, boolean pass, String detail) {
            rows.add(new String[]{id, pass ? "PASS" : "FAIL", detail});
        }

        void warn(String id, String detail) {
            rows.add(new String[]{id, "WARN", detail});
        }

        int failCount() {
            int n = 0;
            for (String[] r : rows) {
                if ("FAIL".equals(r[1])) {
                    n++;
                }
            }
            return n;
        }

        boolean allPass() {
            return failCount() == 0;
        }
    }

    record Args(Path out, int size, int step, double cx, double cz, int comparePoints,
                int coverageStride, boolean skipCensus, List<String> raw) {

        static Args parse(String[] argv) {
            Path out = Path.of("build", "terrain-upgrade", "real-boundary-local", "inventory");
            int size = 1401;
            int step = 5;
            double cx = -140;
            double cz = 137;
            int comparePoints = 4096;
            int coverageStride = 8;
            boolean skipCensus = false;
            for (String a : argv) {
                if (a.startsWith("--out=")) {
                    out = Path.of(a.substring(6));
                } else if (a.startsWith("--size=")) {
                    size = Integer.parseInt(a.substring(7));
                } else if (a.startsWith("--step=")) {
                    step = Integer.parseInt(a.substring(7));
                } else if (a.startsWith("--cx=")) {
                    cx = Double.parseDouble(a.substring(5));
                } else if (a.startsWith("--cz=")) {
                    cz = Double.parseDouble(a.substring(5));
                } else if (a.startsWith("--comparePoints=")) {
                    comparePoints = Integer.parseInt(a.substring(16));
                } else if (a.startsWith("--coverageStride=")) {
                    coverageStride = Integer.parseInt(a.substring(17));
                } else if (a.equals("--skipCensus")) {
                    skipCensus = true;
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            return new Args(out, size, step, cx, cz, comparePoints, coverageStride, skipCensus,
                    List.of(argv));
        }
    }
}
