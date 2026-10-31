package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * {@link RecipeMorphCatalog} 的独立判据程序：{@code main(args[0] = outDir)}。
 *
 * <h3>判据（任一失败 ⇒ {@code System.exit(1)}）</h3>
 * <ol>
 *   <li><b>结构</b>：18 条目 / 固定顺序 / 权重 / 18 票数 / 45 槽 —— 与
 *       {@code LandCompositionGallery.buildCombine} 的阵容逐项对拍。</li>
 *   <li><b>端点可直取</b>（staging / direct restore）：每个条目与其"应还原的对象"对拍 ——
 *       低地原子对独立重建的原族 {@code height01}、复合对真 {@code CombineCandidate.composites[k]}、
 *       台地对<b>修正原型</b>的台地端（{@code HillsPlateauMorphCandidate.sample(x,z,1)}，
 *       <b>不是</b>共享 {@code PlateauCandidate}）、劣地/山地对原配方节点。这四条要求<b>逐位</b>
 *       （容差 {@code 0}），因为 {@code reference} 按对应的 float 运算求值；
 *       复合另加一条"描述式"判据（纯 double 代数，容差 {@code 2e-7}），用于证明公式可推广到任意种子。
 *       ⚠ 复合那条逐位判据只证明"委托成员 {@code reference} 与原复合一致"，
 *       <b>不证明</b>"把 raw 重新乘回 amp"也逐位 —— 后者记为近似（见 combine-identity.csv）。</li>
 *   <li><b>台地历史口径</b>：{@code oldReference} 必须逐位等于共享 {@code PlateauCandidate}
 *       （它用 1.05），且声明值域 0..0.9 的修正口径必须与之不同。⚠ 1.05 是<b>本项目历史偏差</b>，
 *       参考元数据声明的是 0.9；本判据钉住的是"复现了共享实现那一版"，不是"1.05 是对的"。</li>
 *   <li><b>辅助访问器语义</b>：{@code surface/terraceValue} 只对台地有值、其余 {@code NaN}；
 *       固定下标 15/16/17 = 台地/劣地/山地；非台地的 {@code oldReference == reference}。</li>
 *   <li><b>raw 结构</b>：低地 {@code raw} 必须是原 {@code height} 树上"剥一层外层"的<b>同一个子节点</b>
 *       （{@code stripOuter(origin).body() == raw}），且内部算子（dales 的 Power/Blend/PowerCurve、
 *       hills2 的 PerlinRidge）仍在 —— 证明是剥外壳而不是"成品高度 ÷ amp"。</li>
 *   <li><b>真实 45 槽身份</b>：逐个槽位把 {@link RecipeMorphCatalog#slotEntry} 与真
 *       {@code CombineCandidate} 的 {@code slots[]} 按<b>对象身份</b>映射出的条目下标对拍。</li>
 *   <li><b>selector 对拍</b>：10 个复合条目的 selector 值、α、以及两个真实种子 int
 *       （{@code Perlin(600,1)} 与 {@code warpPerlin wx}）与真复合逐点对拍。</li>
 *   <li><b>多 seed</b>：4 个种子各自构独立目录；票数、45 槽、确定性、种子敏感性、
 *       {@code original()} 在非标准种子必须抛 {@code UnsupportedOperationException}；
 *       <b>非标准种子也做真实对拍</b> —— 用本目录自建的 8 个原子 {@code Family}
 *       独立构造一个 {@code CombineCandidate}（同 selector/slot 游标），对拍 45 槽身份
 *       与 10 个复合端点（逐位）。</li>
 *   <li><b>重建链端点</b>：用 {@code raw + amplitude/bias/clamp} 与暴露的信号
 *       （{@code surface/terrace}、{@code warpX/warpZ/scaler}、{@code direction/erosionSeed}）
 *       自建劣地/山地完整链，端点必须逐位等于原配方。</li>
 *   <li><b>严格契约</b>：越界下标、非法 seed 的 {@code original()} 必须报错。</li>
 * </ol>
 *
 * <p>只读：不修改任何既有源码/基准；输出全部落在 {@code args[0]}（默认
 * {@code build/terrain-upgrade/recipe-junctions/catalog-check}）。</p>
 */
public final class RecipeMorphCatalogCheck {

    /** 与定稿探针同批 seed；首个必须等于 Gallery 的 {@code SEED}（否则对拍无意义）。 */
    private static final long[] SEEDS = {RecipeMorphCatalog.GALLERY_SEED, 1L, -817263L, -42L};
    private static final double ATOM_TOLERANCE = 2e-7;
    private static final int POINTS_ATOM = 300;
    private static final int POINTS_COMPOSITE = 300;

    private static final String[] EXPECTED_IDS = {
        "PLAIN_GEO", "STEPPE", "DALES_GEO", "HILLS1_GEO", "HILLS2",
        "PLAIN_GEO+STEPPE", "PLAIN_GEO+DALES_GEO", "PLAIN_GEO+HILLS1_GEO",
        "PLAIN_GEO+HILLS2", "STEPPE+DALES_GEO", "STEPPE+HILLS1_GEO", "STEPPE+HILLS2",
        "DALES_GEO+HILLS1_GEO", "DALES_GEO+HILLS2", "HILLS1_GEO+HILLS2",
        "PLATEAU_REF", "BADLANDS", "M1_GEO"
    };
    private static final RecipeMorphCatalog.Kind[] EXPECTED_KINDS = {
        RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW,
        RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW,
        RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW,
        RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW,
        RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW, RecipeMorphCatalog.Kind.LOW,
        RecipeMorphCatalog.Kind.LOW,
        RecipeMorphCatalog.Kind.PLATEAU, RecipeMorphCatalog.Kind.BADLANDS,
        RecipeMorphCatalog.Kind.MOUNTAIN
    };

    private static int checks;
    private static Path out;

    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            out = args.length > 0 ? Path.of(args[0])
                    : Path.of("build/terrain-upgrade/recipe-junctions/catalog-check");
            Files.createDirectories(out);
            run();
            System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAIL " + t);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        RecipeMorphCatalog catalog = new RecipeMorphCatalog(RecipeMorphCatalog.GALLERY_SEED);
        write("structure.csv", verifyStructure(catalog));
        write("endpoints.csv", verifyEndpoints(catalog));
        write("slot-identity.csv", verifySlotIdentity(catalog));
        write("selector.csv", verifySelectors(catalog));
        write("raw-structure.csv", verifyRawStructure(catalog));
        write("shell-identity.csv", verifyShellIdentity(catalog));
        write("combine-identity.csv", verifyCombineIdentity(catalog));
        write("multi-seed.csv", verifyMultiSeed());
        write("rebuild-badlands.csv", verifyBadlandsRebuild(catalog));
        write("rebuild-mountain.csv", verifyMountainRebuild(catalog));
        write("metadata.csv", verifyMetadata(catalog));
        write("limits.csv", verifyLimits());
        write("contracts.csv", verifyContracts(catalog));
        write("accessors.csv", verifyAccessors(catalog));
        Files.writeString(out.resolve("limits.txt"), RecipeMorphCatalog.LIMITS);
        Files.writeString(out.resolve("metrics.txt"), String.format(Locale.ROOT,
                "checks=%d%nseeds=%s%nentries=%d%nslots=%d%npoints_per_entry=%d%n"
                        + "atom_tolerance=%.3e%ncomposite_points=%d%n"
                        + "gallery_seed=%d%noriginal_only_for=%d%n"
                        + "compiled_against=frozen baseline classpath"
                        + " build/terrain-upgrade/baseline/classpath-jars.txt%n"
                        + "java=21 javac --release17 -encoding UTF-8 -nowarn%n"
                        + "jvm=-Xmx2g -Dfile.encoding=UTF-8 (no terrain override properties)%n",
                checks, Arrays.toString(SEEDS), RecipeMorphCatalog.ENTRY_COUNT,
                RecipeMorphCatalog.SLOT_COUNT, POINTS_ATOM, ATOM_TOLERANCE, POINTS_COMPOSITE,
                RecipeMorphCatalog.GALLERY_SEED, RecipeMorphCatalog.GALLERY_SEED));
    }

    // ============================================================ 1. 结构

    private static List<String> verifyStructure(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "index,id,kind,weight,votes,expectedVotes,seedOffset,clamped,isComposite"));
        require(catalog.size() == RecipeMorphCatalog.ENTRY_COUNT, "entry count " + catalog.size());
        require(catalog.slotCount() == RecipeMorphCatalog.SLOT_COUNT,
                "slot count " + catalog.slotCount());
        int[] votes = new int[RecipeMorphCatalog.ENTRY_COUNT];
        for (int s = 0; s < catalog.slotCount(); s++) {
            int e = catalog.slotEntry(s);
            require(e >= 0 && e < votes.length, "slot " + s + " -> bad entry " + e);
            votes[e]++;
        }
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            RecipeMorphCatalog.Entry e = catalog.entry(i);
            require(e.index() == i, "entry[" + i + "] reports index " + e.index());
            require(EXPECTED_IDS[i].equals(e.id()), "entry[" + i + "] id=" + e.id()
                    + " expected " + EXPECTED_IDS[i]);
            require(EXPECTED_KINDS[i] == e.kind(), "entry[" + i + "] kind=" + e.kind());
            require(votes[i] == RecipeMorphCatalog.EXPECTED_VOTES[i], "entry[" + i + "] votes="
                    + votes[i] + " expected " + RecipeMorphCatalog.EXPECTED_VOTES[i]);
            require(e.isComposite() == inCompositeRange(i), "entry[" + i + "] isComposite="
                    + e.isComposite());
            require(e.clamped() == inCompositeRange(i), "entry[" + i + "] clamped=" + e.clamped());
            csv.add(String.format(Locale.ROOT, "%d,%s,%s,%.4f,%d,%d,%d,%s,%s", i, e.id(), e.kind(),
                    e.weight(), votes[i], RecipeMorphCatalog.EXPECTED_VOTES[i], e.seedOffset(),
                    e.clamped(), e.isComposite()));
        }
        int sum = 0;
        for (int v : votes) sum += v;
        require(sum == RecipeMorphCatalog.SLOT_COUNT, "vote sum " + sum);
        require(catalog.entries().size() == RecipeMorphCatalog.ENTRY_COUNT, "entries() size");
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            require(catalog.entries().get(i) == catalog.entry(i), "entries() order at " + i);
        }
        verifySeedOffsets(catalog);
        return csv;
    }

    /** 低地种子偏移必须是 0/0/33/11/22，独立条目 44/55/77。 */
    private static void verifySeedOffsets(RecipeMorphCatalog catalog) {
        int[] expected = {0, 0, 33, 11, 22, 44, 55, 77};
        int[] got = {
            catalog.entry(0).seedOffset(), catalog.entry(1).seedOffset(),
            catalog.entry(2).seedOffset(), catalog.entry(3).seedOffset(),
            catalog.entry(4).seedOffset(), catalog.entry(RecipeMorphCatalog.IDX_PLATEAU).seedOffset(),
            catalog.entry(RecipeMorphCatalog.IDX_BADLANDS).seedOffset(),
            catalog.entry(RecipeMorphCatalog.IDX_MOUNTAIN).seedOffset()
        };
        require(Arrays.equals(expected, got), "seed offsets " + Arrays.toString(got)
                + " expected " + Arrays.toString(expected));
        for (int i = RecipeMorphCatalog.IDX_FIRST_COMPOSITE;
             i <= RecipeMorphCatalog.IDX_LAST_COMPOSITE; i++) {
            require(catalog.entry(i).seedOffset() == -1,
                    "composite " + i + " offset " + catalog.entry(i).seedOffset());
        }
    }

    /** 下标是否落在复合条目区间（5..14）。 */
    private static boolean inCompositeRange(int i) {
        return i >= RecipeMorphCatalog.IDX_FIRST_COMPOSITE && i <= RecipeMorphCatalog.IDX_LAST_COMPOSITE;
    }

    // ============================================================ 2. 端点直取

    /**
     * 18 个端点的 direct restore（每个条目可能有多条判据行）：
     * <ul>
     *   <li>LOW 原子：对<b>独立重建</b>的原族 {@code height01}，<b>逐位</b>（容差 0）——
     *       外壳按 float 乘/加算，故能逐位；</li>
     *   <li>LOW 复合：① 对真 {@code CombineCandidate.composites[k]}，逐位（容差 0）；
     *       ② 对"描述式" {@code (1−α)ampA rawA + α ampB rawB} 独立推导，容差 {@code 2e-7}
     *       （纯 double 代数，故只能到常数级）；</li>
     *   <li>PLATEAU：对冻结原型 {@code HillsPlateauMorphCandidate.sample(x,z,1)} 的台地端，逐位；</li>
     *   <li>BADLANDS / MOUNTAIN：对原 {@code BadlandsCandidate}/{@code MountainsCandidate} 节点，逐位。</li>
     * </ul>
     */
    private static List<String> verifyEndpoints(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "index,id,kind,identityClass,tolerance,points,max_abs_diff,exact_matches,status"));
        CombineCandidate real = catalog.original();
        long seed = RecipeMorphCatalog.GALLERY_SEED;
        HillsPlateauMorphCandidate plateauEndpoint = new HillsPlateauMorphCandidate(seed);
        Family[] families = new Family[RecipeMorphCatalog.ENTRY_COUNT];
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            families[i] = familyOf(catalog.entry(i), seed);
        }
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            RecipeMorphCatalog.Entry e = catalog.entry(i);
            for (Endpoint t : endpointsFor(e, seed, real, plateauEndpoint, families)) {
                endpointsRow(csv, e, t, seed + i * 7717L);
            }
        }
        verifyPlateauOldReference(csv, catalog);
        return csv;
    }

    /** 跑一个端点判据，并把结果写成一行 CSV。 */
    private static void endpointsRow(List<String> csv, RecipeMorphCatalog.Entry e, Endpoint target,
                                     long pointSeed) {
        Random r = new Random(pointSeed);
        int points = e.isComposite() ? POINTS_COMPOSITE : POINTS_ATOM;
        double worst = 0;
        int exact = 0;
        for (int p = 0; p < points; p++) {
            double x = r.nextDouble() * 200000 - 100000;
            double z = r.nextDouble() * 200000 - 100000;
            double got = e.reference(x, z);
            require(Double.isFinite(got), e.id() + " non-finite reference");
            double diff = Math.abs(got - target.fn().at(x, z));
            if (diff == 0.0) exact++;
            worst = Math.max(worst, diff);
        }
        double tolerance = target.exact() ? 0.0 : ATOM_TOLERANCE;
        require(worst <= tolerance, e.id() + " endpoint mismatch vs " + target.identityClass()
                + " max=" + worst + " > " + tolerance);
        csv.add(String.format(Locale.ROOT, "%d,%s,%s,%s,%.3e,%d,%.3e,%d,PASS", e.index(), e.id(),
                e.kind(), target.identityClass(), tolerance, points, worst, exact));
    }

    /**
     * 端点比较目标 + 是否要求逐位相等。
     *
     * <p>{@code DESCRIBED_COMPOSITE_FORMULA} 是复合条目的<b>描述式</b>独立推导
     * （{@code (1−α)ampA rawA + α ampB rawB} 再乘 amp 加 bias 后 {@code max(0)}）：
     * 它证明 catalog 的复合公式能推广到任意种子，但因为全程 double 代数，
     * 与"原复合的 float 舍入"只到 {@code 2e-7} 级别，故不作逐位判据。</p>
     */
    private record Endpoint(String identityClass, boolean exact, At fn) {
        interface At { double at(double x, double z); }
    }

    /**
     * 独立重建的低地族（不经 catalog）。
     *
     * <p>{@code full} 必须按<b>原节点的 float 顺序</b>算 {@code body·amp + bias}
     * （float 乘、float 加），否则会引入 ~1e-8 的 double 舍入差、无法逐位对拍。</p>
     */
    private record Family(float amp, float bias, Body body) {
        interface Body { float compute(float x, float z); }

        double full(double x, double z) {
            float fx = (float) x;
            float fz = (float) z;
            return body.compute(fx, fz) * amp + bias;
        }
    }

    /** 按 index 独立重建低地族：amp/bias 取自各原配方的末道常量，body 取自原节点树。 */
    private static Family familyOf(RecipeMorphCatalog.Entry e, long seed) {
        if (e.kind() != RecipeMorphCatalog.Kind.LOW || e.isComposite()) {
            return null;
        }
        return familyField(e.index(), seed);
    }

    private static Family plainsFamily(long familySeed, PlainsCandidate.Variant variant,
                                       float amp, float bias) {
        PlainsCandidate p = new PlainsCandidate(variant);
        p.seed(familySeed);
        RecipeMorphCatalog.Stripped s =
                RecipeMorphCatalog.stripOuter((RefNoise.Node) reflect(p, "height"));
        require(s.amp() == amp && s.bias() == bias,
                variant + " outer shell changed: " + s.amp() + "/" + s.bias());
        RefNoise.Node body = s.body();
        return new Family(amp, bias, body::compute);
    }

    private static Family hillsFamily(long familySeed, HillsCandidate.Variant variant, float amp) {
        HillsCandidate h = new HillsCandidate(variant);
        h.seed(familySeed);
        RecipeMorphCatalog.Stripped s =
                RecipeMorphCatalog.stripOuter((RefNoise.Node) reflect(h, "height"));
        require(s.amp() == amp && s.bias() == 0f,
                variant + " outer shell changed: " + s.amp() + "/" + s.bias());
        RefNoise.Node body = s.body();
        return new Family(amp, 0f, body::compute);
    }

    /** 某条目在给定种子下的<b>全部</b>端点判据（复合条目 = 真复合 + 描述式两条）。 */
    private static List<Endpoint> endpointsFor(RecipeMorphCatalog.Entry e, long seed,
                                               CombineCandidate real,
                                               HillsPlateauMorphCandidate plateauEndpoint,
                                               Family[] families) {
        switch (e.kind()) {
            case LOW -> {
                if (!e.isComposite()) {
                    Family f = families[e.index()];
                    return List.of(new Endpoint("ORIGINAL_FAMILY_LINEAR_FLOAT", true, f::full));
                }
                List<Endpoint> both = new ArrayList<>(2);
                if (real != null) {
                    both.add(new Endpoint("REAL_COMPOSITE", true,
                            realComposite(real, e.index())::height01));
                }
                both.add(new Endpoint("DESCRIBED_COMPOSITE_FORMULA", false,
                        compositeFrom(e, families)));
                return both;
            }
            case PLATEAU -> {
                return List.of(new Endpoint("FROZEN_PROTOTYPE_PLATEAU_END", true,
                        (x, z) -> plateauEndpoint.sample(x, z, 1.0)));
            }
            case BADLANDS -> {
                BadlandsCandidate b = new BadlandsCandidate();
                b.seed(seed + RecipeMorphCatalog.OFF_BADLANDS);
                return List.of(new Endpoint("ORIGINAL_RECIPE_NODE", true, b::height01));
            }
            default -> {
                MountainsCandidate m = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
                m.seed(seed + RecipeMorphCatalog.OFF_MOUNTAIN);
                return List.of(new Endpoint("ORIGINAL_RECIPE_NODE", true, m::height01));
            }
        }
    }

    /**
     * 复合条目的<b>描述式</b>独立推导（不用 catalog 的 Entry，也不调真复合 lambda）：
     * <pre>
     *   α    = mapClamped(selector, 0.375, 0.625)          // selector 取自 catalog（已在 selector.csv 对拍种子+取值）
     *   amp  = (1−α)·ampA + α·ampB
     *   bias = (1−α)·biasA + α·biasB
     *   raw  = ((1−α)·ampA·rawA + α·ampB·rawB) / amp
     *   h    = max(raw·amp + bias, 0)
     * </pre>
     * 展开后即 {@code max((1−α)·(rawA·ampA+biasA) + α·(rawB·ampB+biasB), 0)} —— 与真复合
     * {@code max(a + (b−a)α, 0)} 代数等价，但不是"两份成品高度作为外区域过渡高度"。
     */
    private static Endpoint.At compositeFrom(RecipeMorphCatalog.Entry e, Family[] families) {
        Family a = families[e.left().index()];
        Family b = families[e.right().index()];
        RefNoise.Node selector = e.selector();
        return (x, z) -> {
            float fx = (float) x;
            float fz = (float) z;
            float alpha = RefNoise.mapClamped(selector.compute(fx, fz),
                    RecipeMorphCatalog.ALPHA_LO, RecipeMorphCatalog.ALPHA_HI);
            double amp = (1 - alpha) * a.amp() + alpha * b.amp();
            double bias = (1 - alpha) * a.bias() + alpha * b.bias();
            double raw = ((1 - alpha) * a.amp() * a.body().compute(fx, fz)
                    + alpha * b.amp() * b.body().compute(fx, fz)) / amp;
            return Math.max(raw * amp + bias, 0.0);
        };
    }

    private static CombineCandidate.Family realComposite(CombineCandidate real, int k) {
        Object[] composites = (Object[]) reflect(real, "composites");
        return (CombineCandidate.Family) composites[k];
    }

    /**
     * 台地的<b>历史口径</b>必须被如实记录，同时证明它真的是"共享实现的行为"：
     * <ol>
     *   <li>{@code oldReference} 对共享 {@code PlateauCandidate.Variant.REF} 的 {@code height01}
     *       —— 应<b>逐位相等</b>（说明我们重建的 1.05 Terrace 与共享实现是同一个算子）；</li>
     *   <li>{@code reference}（参考元数据声明的 0..0.9）对 {@code oldReference} —— 必须<b>不同</b>，
     *       否则修正值域根本没生效（或上界被写回 1.05）。</li>
     * </ol>
     * <p>⚠ 1.05 <b>不是</b>参考原码口径：参考元数据声明的上界是 0.9，1.05 是本项目早期留下的
     * 本地历史偏差。本判据钉住的是"我们复现了共享实现那一版"，<b>不是</b>"1.05 是对的"。
     * 两者偏差的实际幅值与命中比例由 CSV 报告，不在注释里写死。</p>
     */
    private static void verifyPlateauOldReference(List<String> csv, RecipeMorphCatalog catalog) {
        PlateauCandidate legacy = new PlateauCandidate(PlateauCandidate.Variant.REF);
        legacy.seed(RecipeMorphCatalog.GALLERY_SEED + RecipeMorphCatalog.OFF_PLATEAU);
        RecipeMorphCatalog.Entry plateau = catalog.entry(RecipeMorphCatalog.IDX_PLATEAU);
        Random r = new Random(424242L);
        int differing = 0;
        double maxOldVsShared = 0;
        double maxCorrectedVsOld = 0;
        int points = 4000;
        for (int i = 0; i < points; i++) {
            double x = r.nextDouble() * 40000 - 20000;
            double z = r.nextDouble() * 40000 - 20000;
            double old = plateau.oldReference(x, z);
            double corrected = plateau.reference(x, z);
            double shared = legacy.height01(x, z);
            maxOldVsShared = Math.max(maxOldVsShared, Math.abs(old - shared));
            maxCorrectedVsOld = Math.max(maxCorrectedVsOld, Math.abs(corrected - old));
            if (corrected != old) differing++;
        }
        require(maxOldVsShared == 0.0, "oldReference does not reproduce the shared"
                + " PlateauCandidate.REF (local 1.05 historical range): max=" + maxOldVsShared);
        require(differing > 0, "corrected plateau range produced zero difference vs oldReference"
                + " -> the reference-declared 0..0.9 override is not in effect");
        csv.add(String.format(Locale.ROOT,
                "15,PLATEAU_REF,PLATEAU,OLD_REFERENCE_VS_SHARED_LOCAL_1.05,0.000e+00,%d,%.3e,%d,PASS",
                points, maxOldVsShared, points));
        csv.add(String.format(Locale.ROOT,
                "15,PLATEAU_REF,PLATEAU,CORRECTED_VS_OLD_REFERENCE,na,%d,%.6f,%d,INFO",
                points, maxCorrectedVsOld, differing));
        System.out.printf(Locale.ROOT,
                "plateau oldReference==shared max=%.3e; corrected differs on %d/%d points max=%.6f%n",
                maxOldVsShared, differing, points, maxCorrectedVsOld);
    }

    // ============================================================ 3. 45 槽身份

    private static List<String> verifySlotIdentity(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of("slot,catalogEntry,realEntry,id,match"));
        CombineCandidate real = catalog.original();
        Object[] composites = (Object[]) reflect(real, "composites");
        Object[] unmixable = (Object[]) reflect(real, "unmixable");
        Object[] slots = (Object[]) reflect(real, "slots");
        IdentityHashMap<Object, Integer> byIdentity = new IdentityHashMap<>();
        for (int i = 0; i < composites.length; i++) {
            byIdentity.put(composites[i], i);
        }
        for (int j = 0; j < unmixable.length; j++) {
            byIdentity.put(unmixable[j], composites.length + j);
        }
        require(slots.length == RecipeMorphCatalog.SLOT_COUNT, "real slot table length "
                + slots.length);
        int mismatches = 0;
        for (int s = 0; s < slots.length; s++) {
            Integer realIndex = byIdentity.get(slots[s]);
            require(realIndex != null, "real slot " + s + " is not one of the 18 entries");
            int got = catalog.slotEntry(s);
            boolean ok = got == realIndex;
            if (!ok) mismatches++;
            csv.add(s + "," + got + "," + realIndex + ","
                    + catalog.entry(got).id() + "," + ok);
        }
        require(mismatches == 0, "slot identity mismatches=" + mismatches);
        return csv;
    }

    // ============================================================ 4. selector

    private static List<String> verifySelectors(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "index,id,selectorSeed,catalogSeed,selectorWarpSeed,catalogWarpSeed,"
                        + "points,max_abs_selector_diff,max_abs_alpha_diff,min_alpha,max_alpha"));
        CombineCandidate real = catalog.original();
        Object[] composites = (Object[]) reflect(real, "composites");
        int base = (int) (RecipeMorphCatalog.GALLERY_SEED ^ (RecipeMorphCatalog.GALLERY_SEED >>> 32));
        for (int k = 5; k <= 14; k++) {
            RecipeMorphCatalog.Entry e = catalog.entry(k);
            RefNoise.Node realSelector = selectorOf(composites[k]);
            require(realSelector != null, "cannot recover real selector for entry " + k);
            int realSeed = (int) reflect(reflect(realSelector, "input"), "seed");
            int realWarpSeed = (int) reflect(reflect(realSelector, "wx"), "seed");
            int catalogSeed = (int) reflect(reflect(e.selector(), "input"), "seed");
            int catalogWarpSeed = (int) reflect(reflect(e.selector(), "wx"), "seed");
            int pairIndex = k - 5;
            require(realSeed == base + 2 * pairIndex, "real selector seed order changed at " + k
                    + ": " + realSeed + " != " + (base + 2 * pairIndex));
            require(catalogSeed == realSeed && catalogWarpSeed == realWarpSeed,
                    "selector seed mismatch at " + k + ": catalog=" + catalogSeed + "/"
                            + catalogWarpSeed + " real=" + realSeed + "/" + realWarpSeed);
            Random r = new Random(RecipeMorphCatalog.GALLERY_SEED + k * 131L);
            double maxSel = 0, maxAlpha = 0, lo = 1, hi = 0;
            for (int p = 0; p < POINTS_COMPOSITE; p++) {
                double x = r.nextDouble() * 200000 - 100000;
                double z = r.nextDouble() * 200000 - 100000;
                float a = realSelector.compute((float) x, (float) z);
                float b = e.selector().compute((float) x, (float) z);
                maxSel = Math.max(maxSel, Math.abs((double) a - b));
                double alphaReal = RefNoise.mapClamped(a, RecipeMorphCatalog.ALPHA_LO,
                        RecipeMorphCatalog.ALPHA_HI);
                double alphaCatalog = e.alpha((float) x, (float) z);
                maxAlpha = Math.max(maxAlpha, Math.abs(alphaReal - alphaCatalog));
                lo = Math.min(lo, alphaCatalog);
                hi = Math.max(hi, alphaCatalog);
            }
            require(maxSel == 0.0, "selector value drift at " + k + " max=" + maxSel);
            require(maxAlpha == 0.0, "alpha drift at " + k + " max=" + maxAlpha);
            require(lo >= 0.0 && hi <= 1.0, "alpha outside [0,1] at " + k + " " + lo + ".." + hi);
            csv.add(String.format(Locale.ROOT, "%d,%s,%d,%d,%d,%d,%d,%.3e,%.3e,%.6f,%.6f",
                    k, e.id(), realSeed, catalogSeed, realWarpSeed, catalogWarpSeed,
                    POINTS_COMPOSITE, maxSel, maxAlpha, lo, hi));
        }
        return csv;
    }

    /** 复合 lambda 里捕获的<b>唯一</b> {@link RefNoise.Node} = selector。 */
    private static RefNoise.Node selectorOf(Object composite) {
        List<java.lang.reflect.Field> fields =
                new ArrayList<>(Arrays.asList(composite.getClass().getDeclaredFields()));
        fields.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (java.lang.reflect.Field f : fields) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            try {
                if (f.get(composite) instanceof RefNoise.Node n) return n;
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("cannot read " + f.getName(), ex);
            }
        }
        return null;
    }

    // ============================================================ 5. 多 seed

    private static List<String> verifyMultiSeed() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,entries,slots,votesOk,deterministic,seedSensitive,originalThrows,"
                        + "endpointMaxDiff,independentSlots,independentSlotMismatches,"
                        + "independentCompositePoints,independentCompositeMaxDiff,status"));
        double[] firstSample = null;
        for (long seed : SEEDS) {
            RecipeMorphCatalog c = new RecipeMorphCatalog(seed);
            RecipeMorphCatalog again = new RecipeMorphCatalog(seed);
            require(c.size() == RecipeMorphCatalog.ENTRY_COUNT, "seed " + seed + " entry count");
            require(c.slotCount() == RecipeMorphCatalog.SLOT_COUNT, "seed " + seed + " slot count");
            int[] votes = new int[RecipeMorphCatalog.ENTRY_COUNT];
            for (int s = 0; s < c.slotCount(); s++) votes[c.slotEntry(s)]++;
            boolean votesOk = Arrays.equals(votes, RecipeMorphCatalog.EXPECTED_VOTES);
            require(votesOk, "seed " + seed + " votes " + Arrays.toString(votes));
            require(slotsMatch(c, again), "seed " + seed + " slot table not deterministic");
            double[] sample = sampleAll(c, seed);
            boolean deterministic = Arrays.equals(sample, sampleAll(again, seed));
            require(deterministic, "seed " + seed + " sampling not deterministic");
            boolean sensitive = firstSample != null && !Arrays.equals(sample, firstSample);
            if (firstSample == null) {
                firstSample = sample;
            }
            double endpointWorst = endpointSweep(c, seed);
            boolean throwsUnsupported = originalThrows(c, seed);
            double[] independent = compareWithIndependent(c, seed);
            if (seed == RecipeMorphCatalog.GALLERY_SEED) {
                require(!throwsUnsupported, "canonical seed must expose original()");
            } else {
                require(throwsUnsupported, "seed " + seed + " original() did not throw");
                require(sensitive, "seed " + seed + " produced identical samples");
            }
            csv.add(String.format(Locale.ROOT, "%d,%d,%d,%s,%s,%s,%s,%.3e,%d,%.0f,%.0f,%.3e,PASS",
                    seed, c.size(), c.slotCount(), votesOk, deterministic, sensitive,
                    throwsUnsupported, endpointWorst, c.slotCount(), independent[0], independent[1],
                    independent[2]));
            System.out.printf(Locale.ROOT,
                    "multi-seed %d votesOk=%s det=%s sens=%s original=%s endpointMaxDiff=%.3e"
                            + " independentSlots=%d/45 compositeMaxDiff=%.3e%n",
                    seed, votesOk, deterministic, sensitive, throwsUnsupported, endpointWorst,
                    c.slotCount(), independent[2]);
        }
        return csv;
    }

    /**
     * 任意种子下的 18 端点直取（staging / direct restore），复算 {@link #endpointsFor} 的<b>全部</b>目标：
     * <ul>
     *   <li>LOW 原子：独立重建的族 {@code height01}（float 外壳）—— 逐位；</li>
     *   <li>COMPOSITE：描述式 {@code (1−α)ampA rawA + α ampB rawB} 乘 amp 加 bias 后 {@code max(0)}
     *       —— 容差 {@code 2e-7}（纯 double 代数；非标准种子下 Gallery 那条逐位判据不可用）；</li>
     *   <li>PLATEAU：冻结原型台地端（原型种子 = 该 seed）—— 逐位；</li>
     *   <li>BADLANDS / MOUNTAIN：原配方节点（该 seed + 固定偏移）—— 逐位。</li>
     * </ul>
     */
    private static double endpointSweep(RecipeMorphCatalog c, long seed) {
        HillsPlateauMorphCandidate plateauEndpoint = new HillsPlateauMorphCandidate(seed);
        Family[] families = new Family[RecipeMorphCatalog.ENTRY_COUNT];
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            families[i] = familyOf(c.entry(i), seed);
        }
        double worst = 0;
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            RecipeMorphCatalog.Entry e = c.entry(i);
            for (Endpoint target : endpointsFor(e, seed, null, plateauEndpoint, families)) {
                Random r = new Random(seed + i * 104729L);
                for (int p = 0; p < 60; p++) {
                    double x = r.nextDouble() * 200000 - 100000;
                    double z = r.nextDouble() * 200000 - 100000;
                    double diff = Math.abs(e.reference(x, z) - target.fn().at(x, z));
                    require(Double.isFinite(diff), "non-finite endpoint diff at entry " + i);
                    if (target.exact()) {
                        require(diff == 0.0, "seed " + seed + " entry " + i + " not bit-exact vs "
                                + target.identityClass() + ": " + diff);
                    }
                    worst = Math.max(worst, diff);
                }
            }
        }
        require(worst <= ATOM_TOLERANCE, "seed " + seed + " endpoint sweep worst=" + worst);
        return worst;
    }

    private static boolean slotsMatch(RecipeMorphCatalog a, RecipeMorphCatalog b) {
        for (int s = 0; s < a.slotCount(); s++) {
            if (a.slotEntry(s) != b.slotEntry(s)) return false;
        }
        return true;
    }

    /** 每条目一个点上的 reference（含 composite），用于确定性/敏感性比较。 */
    private static double[] sampleAll(RecipeMorphCatalog c, long seed) {
        double[] values = new double[RecipeMorphCatalog.ENTRY_COUNT * 3];
        Random r = new Random(seed ^ 0x51EDL);
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            RecipeMorphCatalog.Entry e = c.entry(i);
            double x = r.nextDouble() * 8000 - 4000;
            double z = r.nextDouble() * 8000 - 4000;
            values[i * 3] = e.reference(x, z);
            values[i * 3 + 1] = e.raw((float) x, (float) z);
            values[i * 3 + 2] = e.amplitude((float) x, (float) z) + e.bias((float) x, (float) z);
            for (int k = 0; k < 3; k++) {
                require(Double.isFinite(values[i * 3 + k]), "non-finite sample at entry " + i);
            }
        }
        return values;
    }

    private static boolean originalThrows(RecipeMorphCatalog c, long seed) {
        try {
            c.original();
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }

    // ============================================================ 5.3 独立构造 Combine（任意种子）

    /**
     * 用本目录的<b>8 个原子 {@code Family}</b> 独立构造一个 {@code CombineCandidate}
     * （阵容/权重/边界与 {@code LandCompositionGallery.buildCombine} 同值），
     * 以便在<b>任意种子</b>下真实对拍，而不是"非标准种子报未测"。
     *
     * <p>为什么这是独立的：{@code CombineCandidate} 的 selector/slot/shuffle 游标只由
     * {@code worldSeed} 决定（{@code seed()} 里 {@code base(seed)} 起算），与传进来的
     * {@code Family} 是谁无关。所以只要原子与 catalog 同源，本臂的 45 槽与复合值
     * 就是 catalog <b>应当</b>复现的真值 —— 而 {@code original()} 那条 Gallery 路径
     * 写死 {@code SEED}，不能用于别的种子。</p>
     */
    private static CombineCandidate independentCombine(long seed) {
        CombineCandidate.Family[] mixable = {
            familyField(0, seed)::full, familyField(1, seed)::full, familyField(2, seed)::full,
            familyField(3, seed)::full, familyField(4, seed)::full
        };
        CombineCandidate.Category[] mixCats = {
            CombineCandidate.Category.PLAIN, CombineCandidate.Category.PLAIN,
            CombineCandidate.Category.HILLS, CombineCandidate.Category.HILLS,
            CombineCandidate.Category.HILLS
        };
        CombineCandidate.Category[] unmixCats = {
            CombineCandidate.Category.PLATEAU, CombineCandidate.Category.BADLANDS,
            CombineCandidate.Category.MOUNTAINS
        };
        CombineCandidate.Family[] unmixable = {
            plateauAt(seed + RecipeMorphCatalog.OFF_PLATEAU)::height01,
            badlandsAt(seed + RecipeMorphCatalog.OFF_BADLANDS)::height01,
            mountainAt(seed + RecipeMorphCatalog.OFF_MOUNTAIN)::height01
        };
        PlainsCandidate border = plainsAt(seed + RecipeMorphCatalog.OFF_PLAIN,
                PlainsCandidate.Variant.PLAINS_GEO);
        CombineCandidate c = new CombineCandidate(seed, mixable, mixCats,
                RecipeMorphCatalog.LOW_WEIGHTS.clone(), unmixable, unmixCats,
                new float[]{RecipeMorphCatalog.PLATEAU_WEIGHT, RecipeMorphCatalog.BADLANDS_WEIGHT,
                        RecipeMorphCatalog.MOUNTAIN_WEIGHT},
                border::height01);
        c.regionalBorder(mountainAt(seed + RecipeMorphCatalog.OFF_MOUNTAIN)::height01,
                RecipeMorphCatalog.REGIONAL_MODE_SCALE);
        return c;
    }

    /** 低地族 {@code full} 的独立重建（float 外壳），按条目下标索引。 */
    private static Family familyField(int index, long seed) {
        return switch (index) {
            case 0 -> plainsFamily(seed + RecipeMorphCatalog.OFF_PLAIN,
                    PlainsCandidate.Variant.PLAINS_GEO, 0.15f, -0.02f);
            case 1 -> plainsFamily(seed + RecipeMorphCatalog.OFF_STEPPE,
                    PlainsCandidate.Variant.STEPPE, 0.08f, -0.02f);
            case 2 -> hillsFamily(seed + RecipeMorphCatalog.OFF_DALES,
                    HillsCandidate.Variant.DALES_GEO, 0.40f);
            case 3 -> hillsFamily(seed + RecipeMorphCatalog.OFF_HILLS1,
                    HillsCandidate.Variant.HILLS_1_GEO, 0.60f);
            case 4 -> hillsFamily(seed + RecipeMorphCatalog.OFF_HILLS2,
                    HillsCandidate.Variant.HILLS_2, 0.55f);
            default -> throw new IllegalArgumentException("not a lowland index: " + index);
        };
    }

    private static PlainsCandidate plainsAt(long familySeed, PlainsCandidate.Variant variant) {
        PlainsCandidate p = new PlainsCandidate(variant);
        p.seed(familySeed);
        return p;
    }

    private static PlateauCandidate plateauAt(long familySeed) {
        PlateauCandidate p = new PlateauCandidate(PlateauCandidate.Variant.REF);
        p.seed(familySeed);
        return p;
    }

    private static BadlandsCandidate badlandsAt(long familySeed) {
        BadlandsCandidate b = new BadlandsCandidate();
        b.seed(familySeed);
        return b;
    }

    private static MountainsCandidate mountainAt(long familySeed) {
        MountainsCandidate m = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        m.seed(familySeed);
        return m;
    }

    /**
     * 任意种子下的<b>真实</b>对拍（本目录独立构 Combine）：
     * 45 槽身份 + 10 个复合端点（逐位）。
     *
     * @return {槽不匹配数, 复合端点数, 复合端点最大绝对差}
     */
    private static double[] compareWithIndependent(RecipeMorphCatalog c, long seed) {
        CombineCandidate real = independentCombine(seed);
        Object[] composites = (Object[]) reflect(real, "composites");
        Object[] unmixable = (Object[]) reflect(real, "unmixable");
        Object[] slots = (Object[]) reflect(real, "slots");
        IdentityHashMap<Object, Integer> byIdentity = new IdentityHashMap<>();
        for (int i = 0; i < composites.length; i++) byIdentity.put(composites[i], i);
        for (int j = 0; j < unmixable.length; j++) {
            byIdentity.put(unmixable[j], composites.length + j);
        }
        require(slots.length == RecipeMorphCatalog.SLOT_COUNT,
                "independent seed " + seed + " slot length " + slots.length);
        int slotMismatches = 0;
        for (int s = 0; s < slots.length; s++) {
            Integer realIndex = byIdentity.get(slots[s]);
            require(realIndex != null, "independent slot " + s + " is not one of the 18 entries");
            if (c.slotEntry(s) != realIndex) slotMismatches++;
        }
        Family[] families = new Family[RecipeMorphCatalog.ENTRY_COUNT];
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            families[i] = familyOf(c.entry(i), seed);
        }
        double worst = 0;
        int points = 0;
        for (int k = RecipeMorphCatalog.IDX_FIRST_COMPOSITE;
             k <= RecipeMorphCatalog.IDX_LAST_COMPOSITE; k++) {
            CombineCandidate.Family realComposite = (CombineCandidate.Family) composites[k];
            Endpoint described = new Endpoint("DESCRIBED_COMPOSITE_FORMULA", false,
                    compositeFrom(c.entry(k), families));
            Random r = new Random(seed + k * 65537L);
            for (int p = 0; p < 60; p++) {
                double x = r.nextDouble() * 200000 - 100000;
                double z = r.nextDouble() * 200000 - 100000;
                double got = c.entry(k).reference(x, z);
                // ① 与独立构的真复合逐位
                require(got == realComposite.height01(x, z), "seed " + seed + " composite " + k
                        + " != independent CombineCandidate (bit-exact expected), diff="
                        + Math.abs(got - realComposite.height01(x, z)));
                // ② 描述式代数只能到常数级
                worst = Math.max(worst, Math.abs(got - described.fn().at(x, z)));
                points++;
            }
        }
        require(slotMismatches == 0, "seed " + seed + " independent slot mismatches="
                + slotMismatches);
        require(worst <= ATOM_TOLERANCE, "seed " + seed + " described composite worst=" + worst);
        return new double[]{slotMismatches, points, worst};
    }

    // ============================================================ 5.4 重建链端点

    /**
     * <b>劣地重建链端点</b>：只用 catalog 暴露的东西（{@code raw} = {@code dbgHills}、
     * {@code warpX/warpZ}、{@code scaler}、外壳 0.55/0.025），按
     * {@code BadlandsCandidate.seed} 的同一算式重建 {@code mod1/low/high/detail/mod2/shape}，
     * 与从未被修改的原节点逐位对拍。
     *
     * <p>这条判据是给引擎用的：引擎拿不到 {@code BadlandsCandidate}，只能靠这几个信号
     * 重建非线性部分。若 catalog 的种子偏移（b+4 / b+5）或 scaler 尺度（200）写错，
     * 端点立刻不等。</p>
     */
    private static List<String> verifyBadlandsRebuild(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "seed,points,max_abs_diff,detail_max_abs_diff,shape_max_abs_diff,status"));
        for (long seed : SEEDS) {
            RecipeMorphCatalog c = new RecipeMorphCatalog(seed);
            RecipeMorphCatalog.Entry e = c.entry(RecipeMorphCatalog.IDX_BADLANDS);
            BadlandsCandidate original = badlandsAt(seed + RecipeMorphCatalog.OFF_BADLANDS);
            RefNoise.Node raw = e.rawNode();
            RefNoise.Node warpX = e.warpX();
            RefNoise.Node warpZ = e.warpZ();
            RefNoise.Node scaler = e.scaler();
            require(raw != null && warpX != null && warpZ != null && scaler != null,
                    "badlands signals missing for seed " + seed);
            RefNoise.Node steps4 = new RefNoise.Steps((v, z) -> v, 4, 0.6f, 0.7f, false);
            RefNoise.Node steps10 = new RefNoise.Steps((v, z) -> v, 10, 0.6f, 0.7f, false);
            RefNoise.Node shapeSteps = new RefNoise.Steps((v, z) -> v, 4, 0.65f, 0.75f, true);
            Random r = new Random(seed + 20261031L);
            double worst = 0, worstDetail = 0, worstShape = 0;
            int points = 200;
            for (int p = 0; p < points; p++) {
                float x = (float) (r.nextDouble() * 200000 - 100000);
                float z = (float) (r.nextDouble() * 200000 - 100000);
                float hills = raw.compute(x, z);
                // mod1：warp(hills, warpX, warpZ, 50) × 0.4（Warped 内部取 (v−0.5)×pow）
                float wx = x + (warpX.compute(x, z) - 0.5f) * 50f;
                float wz = z + (warpZ.compute(x, z) - 0.5f) * 50f;
                float mod1 = raw.compute(wx, wz) * 0.4f;
                float low = steps4.compute(hills, 0f) * 0.6f + mod1;
                float high = steps10.compute(hills, 0f) * 0.6f + mod1;
                float detail = (low + high) * 0.5f + 0.5f;
                float mod2 = hills * (scaler.compute(x, z) * 0.4f);
                float shape = (shapeSteps.compute(hills, 0f) * 0.6f + mod2) * 0.6f;
                float rebuilt = shape * detail * 0.55f + 0.025f;
                worst = Math.max(worst, Math.abs(rebuilt - original.height01(x, z)));
                worstDetail = Math.max(worstDetail, Math.abs(detail - original.debugDetail(x, z)));
                worstShape = Math.max(worstShape, Math.abs(shape - original.debugShape(x, z)));
            }
            require(worst == 0.0 && worstDetail == 0.0 && worstShape == 0.0,
                    "seed " + seed + " badlands rebuild not bit-exact: height=" + worst
                            + " detail=" + worstDetail + " shape=" + worstShape);
            csv.add(String.format(Locale.ROOT, "%d,%d,%.3e,%.3e,%.3e,PASS", seed, points,
                    worst, worstDetail, worstShape));
        }
        return csv;
    }

    /**
     * <b>山地重建链端点</b>：catalog 给的 raw 是<b>去 fancy</b> 的 body，引擎要用
     * {@code direction + erosionSeed} 按 {@code makeFancy} 的固定侵蚀路由重建：
     * <pre>
     *   Erosion(raw, erosionSeed, 2, 0.65, 128, 0.15, 3.1, 0.8)      // 强度 0.65，固定路由
     *   DirectionWarped(·, direction, 2.0)
     *   × 0.7
     * </pre>
     * 三件都必须逐位等于原 {@code MountainsCandidate} 端点 —— 尤其是侵蚀路由
     * 必须<b>始终指向 raw</b>（不能随过渡参数重选最低邻点，那是本项目反复踩的坑）。
     */
    private static List<String> verifyMountainRebuild(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "seed,erosionSeed,points,max_abs_diff,body_max_abs_diff,status"));
        for (long seed : SEEDS) {
            RecipeMorphCatalog c = new RecipeMorphCatalog(seed);
            RecipeMorphCatalog.Entry e = c.entry(RecipeMorphCatalog.IDX_MOUNTAIN);
            MountainsCandidate original = mountainAt(seed + RecipeMorphCatalog.OFF_MOUNTAIN);
            RefNoise.Node raw = e.rawNode();
            RefNoise.Node direction = e.direction();
            int erosionSeed = e.erosionSeed();
            require(raw != null && direction != null, "mountain signals missing for seed " + seed);
            int expectedSeed = RecipeMorphCatalog.base(seed + RecipeMorphCatalog.OFF_MOUNTAIN) + 4;
            require(erosionSeed == expectedSeed, "seed " + seed + " erosionSeed=" + erosionSeed
                    + " expected " + expectedSeed);
            RefNoise.Node rebuilt = new RefNoise.Mul(
                    new RefNoise.DirectionWarped(
                            new RefNoise.Erosion(raw, erosionSeed, 2, 0.65f, 128f, 0.15f, 3.1f, 0.8f),
                            direction, 2f),
                    new RefNoise.Constant(RecipeMorphCatalog.MOUNTAIN_AMP));
            Random r = new Random(seed + 5150L);
            double worst = 0, worstBody = 0;
            int points = 200;
            for (int p = 0; p < points; p++) {
                float x = (float) (r.nextDouble() * 200000 - 100000);
                float z = (float) (r.nextDouble() * 200000 - 100000);
                worst = Math.max(worst, Math.abs(rebuilt.compute(x, z) - original.height01(x, z)));
                worstBody = Math.max(worstBody, Math.abs(raw.compute(x, z) - original.height01(x, z)));
            }
            require(worst == 0.0, "seed " + seed + " mountain rebuild not bit-exact: " + worst);
            require(worstBody > 0.0,
                    "seed " + seed + ": raw already equals the finished mountain height ->"
                            + " raw is not the pre-fancy body");
            csv.add(String.format(Locale.ROOT, "%d,%d,%d,%.3e,%.3e,PASS", seed, erosionSeed, points,
                    worst, worstBody));
        }
        return csv;
    }

    // ============================================================ 5.4 raw 结构
    /**
     * <b>低地 raw 必须是"剥掉外层 amp/bias 的完整内部配方"</b>，而不是"成品高度除以 amp"。
     *
     * <p>三条判据：</p>
     * <ol>
     *   <li><b>子节点身份</b>：{@code stripOuter(origin).body() == raw}，其中 {@code origin} 是
     *       catalog 建条目时读到的<b>原 height 节点</b>。这直接排除"把 height 除以 amp 再减 bias"
     *       之类的数值伪装 —— 那种做法会造出新节点，身份必不等。</li>
     *   <li><b>内部算子仍在</b>：dales 的 {@code Power}/{@code Blend}/{@code PowerCurve}、
     *       hills2 的 {@code PerlinRidge}（scaler2）、两族共有的 {@code Warped}/{@code Billow}
     *       都必须出现在 raw 的节点树里 —— 它们属于内部配方，剥外壳不得触碰。</li>
     *   <li><b>外层确已剥离</b>：raw 的最外层不能是 {@code Add(·,Constant)} 或
     *       {@code Mul(·,Constant)}（否则说明 amp/bias 还留在 raw 里）。</li>
     * </ol>
     */
    private static List<String> verifyRawStructure(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "index,id,childIdentityOfOrigin,nodes,outerLayerStripped,internalOperators,ok"));
        String[][] required = {
            {}, {}, {"Power", "Blend", "PowerCurve"}, {}, {"PerlinRidge"}
        };
        for (int i = 0; i < RecipeMorphCatalog.MIXABLE; i++) {
            RecipeMorphCatalog.Entry e = catalog.entry(i);
            RefNoise.Node raw = (RefNoise.Node) reflect(e, "raw");
            RefNoise.Node origin = e.originNode();
            require(origin != null, e.id() + " has no origin node (cannot prove the raw split)");
            boolean sameObject = RecipeMorphCatalog.stripOuter(origin).body() == raw;
            boolean outerStripped = !isShell(raw);
            Set<String> names = new LinkedHashSet<>();
            collectClassNames(raw, names, 0);
            boolean internal = true;
            for (String need : required[i]) {
                internal &= containsNodeClass(names, need);
            }
            int nodes = countNodes(raw, 0);
            require(sameObject, e.id() + " raw is not the child node of its original height tree"
                    + " (a divided-out shell would fail this identity check)");
            require(outerStripped, e.id() + " raw still starts with an amp/bias layer");
            require(internal, e.id() + " raw lost internal operators: " + Arrays.toString(required[i])
                    + " not all present in " + names);
            csv.add(String.format(Locale.ROOT, "%d,%s,%s,%d,%s,%s,true", i, e.id(), sameObject, nodes,
                    outerStripped, Arrays.toString(required[i])));
        }
        return csv;
    }

    /** 最外层是否还是 {@code Add(·,Constant)} / {@code Mul(·,Constant)}。 */
    private static boolean isShell(RefNoise.Node node) {
        if (node instanceof RefNoise.Add add) {
            return add.a() instanceof RefNoise.Constant || add.b() instanceof RefNoise.Constant;
        }
        if (node instanceof RefNoise.Mul mul) {
            return mul.a() instanceof RefNoise.Constant || mul.b() instanceof RefNoise.Constant;
        }
        return false;
    }

    /** 递归收集节点树里的类简名（只读反射，按字段名排序保证稳定）。 */
    private static void collectClassNames(Object node, Set<String> names, int depth) {
        if (node == null || depth > 40 || !(node instanceof RefNoise.Node)) {
            return;
        }
        names.add(node.getClass().getSimpleName());
        for (Field f : sortedFields(node.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            try {
                Object v = f.get(node);
                if (v instanceof RefNoise.Node) collectClassNames(v, names, depth + 1);
            } catch (IllegalAccessException ignored) {
                // 访问不到的字段不参与判据（节点树里没有这种字段；若出现会让 nodes 计数偏低）
            }
        }
    }

    private static boolean containsNodeClass(Set<String> names, String need) {
        for (String n : names) {
            if (n.contains(need)) return true;
        }
        return false;
    }

    private static int countNodes(Object node, int depth) {
        if (node == null || depth > 40 || !(node instanceof RefNoise.Node)) {
            return 0;
        }
        int total = 1;
        for (Field f : sortedFields(node.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            try {
                total += countNodes(f.get(node), depth + 1);
            } catch (IllegalAccessException ignored) {
                // 见 collectClassNames
            }
        }
        return total;
    }

    private static List<Field> sortedFields(Class<?> cls) {
        List<Field> list = new ArrayList<>(Arrays.asList(cls.getDeclaredFields()));
        list.sort((a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    // ============================================================ 5.5 外壳自洽
    /**
     * 低地/复合条目的<b>访问器自洽性</b>（不是"与原对象逐位"的证明）：
     * {@code clamp(raw·amplitude + bias)} 与 {@code reference} 的差，实测记录。
     *
     * <h3>⚠ 这条判据能证明什么、不能证明什么</h3>
     * <ul>
     *   <li><b>能证明</b>：三个外壳访问器（{@code raw/amplitude/bias}）与 {@code reference}
     *       彼此一致 —— 引擎照这四个量重建，就与 {@code reference} 给同一个数；</li>
     *   <li><b>不能证明</b>："把 catalog 的 raw 重新乘回 amp"与<b>原对象</b>逐位相等。
     *       因为 {@code reference} 对原子本身就走同一 float 算式、对复合则<b>委托</b>左右成员的
     *       {@code reference}，所以本判据的残差主要是 double 重算 vs float 算式的舍入差
     *       （实测非零，见 CSV）。原对象的逐位判据在 {@code endpoints.csv}。</li>
     * </ul>
     * <p>台地/劣地/山地<b>不适用</b>（raw 只是非线性算子的输入）。</p>
     */
    private static List<String> verifyShellIdentity(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "index,id,kind,clamped,points,max_abs_diff,status"));
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            RecipeMorphCatalog.Entry e = catalog.entry(i);
            if (e.kind() != RecipeMorphCatalog.Kind.LOW) {
                csv.add(i + "," + e.id() + "," + e.kind() + ",false,0,na,NOT_APPLICABLE");
                continue;
            }
            Random r = new Random(RecipeMorphCatalog.GALLERY_SEED + i * 31337L);
            double worst = 0;
            for (int p = 0; p < POINTS_ATOM; p++) {
                float x = (float) (r.nextDouble() * 200000 - 100000);
                float z = (float) (r.nextDouble() * 200000 - 100000);
                double shell = e.raw(x, z) * e.amplitude(x, z) + e.bias(x, z);
                double rebuilt = e.clamped() ? Math.max(shell, 0.0) : shell;
                worst = Math.max(worst, Math.abs(rebuilt - e.reference(x, z)));
            }
            require(worst <= ATOM_TOLERANCE, e.id() + " accessor self-consistency broken: " + worst);
            csv.add(String.format(Locale.ROOT, "%d,%s,%s,%s,%d,%.3e,PASS", i, e.id(), e.kind(),
                    e.clamped(), POINTS_ATOM, worst));
        }
        return csv;
    }

    /**
     * <b>复合条目的代数身份</b>（数学恒等，不代表 float 下逐位）。
     *
     * <p>展开 catalog 的复合公式：</p>
     * <pre>
     *   raw·amp + bias
     *     = [(1−α)ampA rawA + α ampB rawB] + [(1−α)biasA + α biasB]
     *     = (1−α)·(ampA rawA + biasA) + α·(ampB rawB + biasB)
     * </pre>
     * <p>这是<b>恒等式</b>（两边同一式），即两个成员"线性外壳输出"的加权平均，与参考
     * {@code combine} 的 {@code lerp(a,b,α)} 同义。据此判据为
     * {@code reference ≈ max((1−α)·linA + α·linB, 0)}（{@code linX} = 成员的未截断外壳输出）。</p>
     *
     * <p>⚠ 该比较的两边都在 double 里重算，而 {@code reference} 走 float 算式
     * ⇒ 差<b>不为 0</b>，只能作为常数级近似判据（容差 {@code 2e-7}）。
     * 残差实测值写在 CSV 里，<b>不在本注释里写死数字</b>。</p>
     *
     * <p>同时统计 {@code max(0)} 的触发次数：这是复合高度与"纯 lerp"唯一可能不同的地方。
     * 触发次数同样由 CSV 报告（可以为 0，那说明该配对在采样上退化为纯 lerp）。</p>
     */
    private static List<String> verifyCombineIdentity(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "index,id,points,max_abs_diff_vs_linear_lerp,clamp_engaged,clamp_rate,status"));
        for (int i = RecipeMorphCatalog.IDX_FIRST_COMPOSITE;
             i <= RecipeMorphCatalog.IDX_LAST_COMPOSITE; i++) {
            RecipeMorphCatalog.Entry e = catalog.entry(i);
            RecipeMorphCatalog.Entry a = e.left();
            RecipeMorphCatalog.Entry b = e.right();
            Random r = new Random(RecipeMorphCatalog.GALLERY_SEED + i * 977L);
            double worst = 0;
            int clamped = 0;
            for (int p = 0; p < POINTS_COMPOSITE; p++) {
                float x = (float) (r.nextDouble() * 200000 - 100000);
                float z = (float) (r.nextDouble() * 200000 - 100000);
                double alpha = e.alpha(x, z);
                double lerp = (1 - alpha) * lin(a, x, z) + alpha * lin(b, x, z);
                double rebuilt = Math.max(lerp, 0.0);
                if (lerp < 0.0) clamped++;
                worst = Math.max(worst, Math.abs(e.reference(x, z) - rebuilt));
            }
            require(worst <= ATOM_TOLERANCE, e.id() + " combine algebra broken: " + worst);
            csv.add(String.format(Locale.ROOT, "%d,%s,%d,%.3e,%d,%.4f,PASS", i, e.id(),
                    POINTS_COMPOSITE, worst, clamped, clamped / (double) POINTS_COMPOSITE));
        }
        return csv;
    }

    /** 成员的低地线性外壳输出（未截断，double 精度）。 */
    private static double lin(RecipeMorphCatalog.Entry e, float x, float z) {
        return e.raw(x, z) * e.amplitude(x, z) + e.bias(x, z);
    }

    // ============================================================ 5.6 metadata / 解析限制

    /**
     * <b>每个条目的真实配方 metadata</b>（不是"看起来像"的标签）：
     * 种子偏移、外壳常量、clamp、以及从真实节点树里读出的 selector 种子与复合成员下标。
     *
     * <p>selector 的两个种子 int 直接从 catalog 的节点树读（{@code Warped.input.seed} 与
     * {@code Warped.wx.seed}），所以这列是<b>真实消费到的种子</b>，不是按公式推算；
     * 同一值已在 selector.csv 与真复合逐位对拍。</p>
     */
    private static List<String> verifyMetadata(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of(
                "index,id,kind,seedOffset,seed,amp,bias,clamped,isComposite,left,right,"
                        + "selectorSeed,selectorWarpSeed,selectorScale,alphaLo,alphaHi,rawNodeClass"));
        for (int i = 0; i < RecipeMorphCatalog.ENTRY_COUNT; i++) {
            RecipeMorphCatalog.Entry e = catalog.entry(i);
            String selectorSeed = "";
            String selectorWarpSeed = "";
            if (e.isComposite()) {
                Object selector = e.selector();
                selectorSeed = String.valueOf(reflect(reflect(selector, "input"), "seed"));
                selectorWarpSeed = String.valueOf(reflect(reflect(selector, "wx"), "seed"));
            }
            RefNoise.Node rawNode = e.rawNode();
            csv.add(String.format(Locale.ROOT,
                    "%d,%s,%s,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                    i, e.id(), e.kind(), e.seedOffset(),
                    e.seedOffset() < 0 ? Long.MIN_VALUE : catalog.seed() + e.seedOffset(),
                    String.format(Locale.ROOT, "%.6f", e.amplitude(1234.5f, -6789.25f)),
                    String.format(Locale.ROOT, "%.6f", e.bias(1234.5f, -6789.25f)),
                    e.clamped(), e.isComposite(),
                    e.left() == null ? "" : String.valueOf(e.left().index()),
                    e.right() == null ? "" : String.valueOf(e.right().index()),
                    selectorSeed, selectorWarpSeed,
                    e.isComposite() ? String.format(Locale.ROOT, "%.1f", RecipeMorphCatalog.SELECTOR_SCALE) : "",
                    e.isComposite() ? String.format(Locale.ROOT, "%.3f", RecipeMorphCatalog.ALPHA_LO) : "",
                    e.isComposite() ? String.format(Locale.ROOT, "%.3f", RecipeMorphCatalog.ALPHA_HI) : "",
                    rawNode == null ? "" : rawNode.getClass().getSimpleName()));
            require(e.isComposite() == (e.left() != null && e.right() != null),
                    e.id() + " composite member wiring inconsistent");
            if (!e.isComposite()) {
                require(rawNode != null, e.id() + " has no raw node");
                require(Double.isFinite(e.amplitude(0f, 0f)) && Double.isFinite(e.bias(0f, 0f)),
                        e.id() + " non-finite constant shell");
            }
        }
        return csv;
    }

    /**
     * <b>解析限制</b>以 CSV 承载（机读），逐条声明"已证 / 未证"，与 {@code limits.txt} 同源但不重复措辞。
     * <p>每行都必须与实际产出文件一致：{@code evidence} 列写的是本目录里真实存在的文件名。</p>
     */
    private static List<String> verifyLimits() {
        List<String> csv = new ArrayList<>(List.of("axis,status,evidence"));
        addLimit(csv, "18_entry_identity", "PROVEN", "structure.csv,metadata.csv");
        addLimit(csv, "canonical_seed_bit_exact_against_intended_original", "PROVEN",
                "endpoints.csv (lowland atoms vs own family height01; composites vs real "
                        + "CombineCandidate lambdas; plateau vs the CORRECTED prototype endpoint, "
                        + "not vs PlateauCandidate; badlands/m1 vs their recipe nodes)");
        addLimit(csv, "other_seeds_bit_exact_atoms_plateau_badlands_mountain", "PROVEN", "multi-seed.csv");
        addLimit(csv, "other_seeds_real_composite_and_45_slot", "PROVEN",
                "multi-seed.csv (independent CombineCandidate from this catalog's atoms)");
        addLimit(csv, "composite_described_formula", "PROVEN<=2e-7",
                "endpoints.csv DESCRIBED_COMPOSITE_FORMULA rows");
        addLimit(csv, "composite_equals_member_shell_lerp", "PROVEN_MATHEMATICAL_IDENTITY_ONLY",
                "combine-identity.csv (float-rounded; residuals and clamp engagement measured there)");
        addLimit(csv, "badlands_rebuild_chain", "PROVEN_EXACT", "rebuild-badlands.csv");
        addLimit(csv, "mountain_rebuild_chain_fixed_erosion_route", "PROVEN_EXACT",
                "rebuild-mountain.csv");
        addLimit(csv, "raw_is_stripped_child_not_divided_height", "PROVEN",
                "raw-structure.csv (object identity of stripOuter(origin).body())");
        addLimit(csv, "plateau_corrected_range_0_0.9", "PROVEN", "endpoints.csv,accessors.csv");
        addLimit(csv, "plateau_legacy_range_1.05", "RECORDED_NOT_ENDPOINT_LOCAL_DEVIATION",
                "endpoints.csv INFO row (1.05 is this project's historical deviation; the reference "
                        + "recipe metadata declares 0.9)");
        addLimit(csv, "three_way_junction_rendering", "NOT_COVERED", "engine owner scope");
        addLimit(csv, "staged_local_profiles_and_images", "NOT_COVERED", "engine owner scope");
        addLimit(csv, "full_map_wiring_and_baseline", "NOT_TOUCHED", "no src/main or Gallery edit");
        addLimit(csv, "visual_acceptability", "NOT_PROVEN", "no image produced");
        addLimit(csv, "engine_blend_global_continuity", "NOT_PROVEN", "pointwise checks only");
        addLimit(csv, "performance", "NOT_MEASURED", "no timing run");
        return csv;
    }

    private static void addLimit(List<String> csv, String axis, String status, String evidence) {
        // CSV 引号：evidence 里会出现逗号（"structure.csv,metadata.csv"），不引会让列数漂移。
        csv.add(axis + "," + status + ",\"" + evidence + "\"");
    }

    // ============================================================ 6. 严格契约
    private static List<String> verifyContracts(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of("case,threw,message"));
        reject(csv, "entry_negative", () -> catalog.entry(-1));
        reject(csv, "entry_at_count", () -> catalog.entry(RecipeMorphCatalog.ENTRY_COUNT));
        reject(csv, "slot_negative", () -> catalog.slotEntry(-1));
        reject(csv, "slot_at_count", () -> catalog.slotEntry(RecipeMorphCatalog.SLOT_COUNT));
        RecipeMorphCatalog other = new RecipeMorphCatalog(RecipeMorphCatalog.GALLERY_SEED + 1);
        reject(csv, "original_off_canonical_seed", other::original);
        return csv;
    }

    /**
     * 辅助访问器的语义契约（不抛异常的那几条）：
     * <ul>
     *   <li>{@code surface / terraceValue} 只对台地有值，其余条目 {@code NaN}
     *       —— 防止引擎误把 {@code NaN} 当高度用而静默产出坏地形；</li>
     *   <li>固定下标常量 15/16/17 必须真是台地/劣地/山地；</li>
     *   <li>非台地的 {@code oldReference == reference}（旧口径只对台地不同）。</li>
     * </ul>
     */
    private static List<String> verifyAccessors(RecipeMorphCatalog catalog) {
        List<String> csv = new ArrayList<>(List.of("case,observed,expected,ok"));
        RecipeMorphCatalog.Entry plateau = catalog.entry(RecipeMorphCatalog.IDX_PLATEAU);
        RecipeMorphCatalog.Entry badlands = catalog.entry(RecipeMorphCatalog.IDX_BADLANDS);
        RecipeMorphCatalog.Entry mountain = catalog.entry(RecipeMorphCatalog.IDX_MOUNTAIN);
        accessor(csv, "index_constants",
                plateau.kind() + "/" + badlands.kind() + "/" + mountain.kind(),
                "PLATEAU/BADLANDS/MOUNTAIN",
                plateau.kind() == RecipeMorphCatalog.Kind.PLATEAU
                        && badlands.kind() == RecipeMorphCatalog.Kind.BADLANDS
                        && mountain.kind() == RecipeMorphCatalog.Kind.MOUNTAIN);
        double plateauSurface = plateau.surface(1234.5f, -6789.25f);
        accessor(csv, "plateau_surface_finite", String.valueOf(plateauSurface), "finite",
                Double.isFinite(plateauSurface));
        double terraceValue = plateau.terraceValue(0.37);
        accessor(csv, "plateau_terrace_finite", String.valueOf(terraceValue), "finite",
                Double.isFinite(terraceValue));
        double otherSurface = catalog.entry(0).surface(1f, 2f);
        accessor(csv, "low_surface_nan", String.valueOf(otherSurface), "NaN",
                Double.isNaN(otherSurface));
        double otherTerrace = mountain.terraceValue(0.5);
        accessor(csv, "mountain_terrace_nan", String.valueOf(otherTerrace), "NaN",
                Double.isNaN(otherTerrace));
        double pOldVsNew = plateau.oldReference(777.5, -321.25);
        accessor(csv, "plateau_old_vs_new_present", String.valueOf(pOldVsNew), "finite",
                Double.isFinite(pOldVsNew));
        double lowOld = catalog.entry(3).oldReference(777.5, -321.25);
        double lowNew = catalog.entry(3).reference(777.5, -321.25);
        accessor(csv, "low_old_equals_new", String.valueOf(lowOld - lowNew), "0.0",
                Double.compare(lowOld, lowNew) == 0);
        // 台地 / 劣地 / 山地必须各自暴露引擎重建非线性所需的信号
        accessor(csv, "badlands_signals", "warpX/warpZ/scaler", "non-null",
                badlands.warpX() != null && badlands.warpZ() != null && badlands.scaler() != null);
        accessor(csv, "mountain_signals", "direction/erosionSeed", "non-null",
                mountain.direction() != null && mountain.erosionSeed() != 0);
        accessor(csv, "plateau_surface_node", "surface()/terrace()", "non-null",
                plateau.surface() != null && plateau.terrace() != null);
        return csv;
    }

    private static void accessor(List<String> csv, String name, String observed, String expected,
                                 boolean ok) {
        csv.add(name + "," + observed + "," + expected + "," + ok);
        require(ok, "accessor contract '" + name + "' failed: " + observed + " vs " + expected);
    }

    private static void reject(List<String> csv, String name, Runnable action) {
        boolean threw = false;
        try {
            action.run();
        } catch (IndexOutOfBoundsException | UnsupportedOperationException expected) {
            threw = true;
        }
        csv.add(name + "," + threw + ",expected contract violation");
        require(threw, "negative control '" + name + "' did not throw");
    }

    // ============================================================ 工具

    private static Object reflect(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot read " + name, e);
            }
        }
        throw new IllegalStateException("no field '" + name + "' on " + target.getClass().getName());
    }

    private static void write(String name, List<String> lines) throws Exception {
        Files.writeString(out.resolve(name), String.join("\n", lines) + "\n");
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
