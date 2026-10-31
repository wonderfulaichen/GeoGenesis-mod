package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * <b>18 条目配方目录（独立只读）</b>：5 低地原始 + 10 低地两两复合 + 台地 + 劣地 + 山地。
 *
 * <p>顺序 <b>固定</b>，与 {@link LandCompositionGallery#buildCombine} 的
 * {@code mixable → combine 两两复合 → unmixable} 完全同序：</p>
 * <pre>
 *   0  PLAIN_GEO   1 STEPPE   2 DALES_GEO   3 HILLS1_GEO   4 HILLS2
 *   5  PLAIN_GEO+STEPPE      6 PLAIN_GEO+DALES_GEO    7 PLAIN_GEO+HILLS1_GEO
 *   8  PLAIN_GEO+HILLS2      9 STEPPE+DALES_GEO      10 STEPPE+HILLS1_GEO
 *  11  STEPPE+HILLS2        12 DALES_GEO+HILLS1_GEO  13 DALES_GEO+HILLS2
 *  14  HILLS1_GEO+HILLS2    15 PLATEAU_REF           16 BADLANDS      17 M1_GEO
 * </pre>
 *
 * <h3>为什么需要它</h3>
 * <p>引擎要做类型过渡，就不能把"成品高度"当外区域高度去 lerp（那会把形态抹平，且端点处
 * 与真配方不等）。所以每个条目必须把配方拆成三件<b>可分别过渡</b>的东西：</p>
 * <ul>
 *   <li>{@code raw(x,z)} —— <b>信号层</b>：剥掉最后一道振幅/偏置后的完整内部配方；</li>
 *   <li>{@code amplitude(x,z)} / {@code bias(x,z)} —— <b>线性外壳</b>，允许随位置连续变化；</li>
 *   <li>{@code clamped()} —— 外壳之后是否还有 {@code max(·,0)}（复合条目有，其余没有）。</li>
 * </ul>
 * <p>外加 {@code reference(x,z)} = <b>原配方高度</b>（"old exact reference"），
 * 供对拍与端点验收。对低地/复合它与 {@code clamp(raw·amp + bias)} 同义
 * （恒等性见下）；对台地/劣地/山地 {@code raw} 只是<b>非线性算子的输入信号</b>，
 * 该恒等式<b>不成立</b>（见 {@link #LIMITS}），此时 {@code reference} 返回原配方高度。</p>
 *
 * <h3>★ reference 的逐位口径：只对"原对象"成立，且台地的原对象不是 PlateauCandidate</h3>
 * <p>低地原子的外壳按 {@code v·amp + bias} 的 <b>float 乘 / float 加</b>（与原节点
 * {@code Add(Mul(body,amp),bias)} 同序）求值；复合按原 {@code combine} 的
 * {@code max(v + (w−v)·α, 0)}。因此：</p>
 * <ul>
 *   <li><b>低地原子</b>：与<b>该族自己的</b> {@code height01} 逐位相等（同一 float 算式）；</li>
 *   <li><b>复合</b>：与真 {@code CombineCandidate} 复合 lambda 逐位相等 ——
 *       但这条<b>只证明</b>"委托成员 {@code reference} 后与原复合一致"，
 *       <b>不证明</b> "把 {@code raw·amp+bias} 重新相乘"也逐位 ——
 *       后者是 float 舍入下的近似（实测残差见 {@code combine-identity.csv}）；</li>
 *   <li><b>台地</b>：逐位相等的是<b>修正原型</b>
 *       （{@code HillsPlateauMorphCandidate.sample(x,z,1)}，{@code Terrace} 值域 0..0.9），
 *       <b>不是</b>共享 {@code PlateauCandidate}（它用 1.05）。两者必然不同；</li>
 *   <li><b>劣地 / 山地</b>：与原配方节点逐位相等。</li>
 * </ul>
 * <p>若低地外壳改用 double，会引入 ~1e-8 的 float 舍入差 —— 故不采用。</p>
 *
 * <p>共享 {@code PlateauCandidate} 的 1.05 是<b>本地历史偏差</b>（参考元数据本身就是 0.9），
 * 通过 {@code oldReference} 如实暴露以便对账；它<b>不是</b>端点口径。</p>
 *
 * <h3>各类型的 raw 取法（口径固定，勿随手改）</h3>
 * <pre>
 * LOW       原族 {@code height} 节点剥掉最后一层 Add(偏置) 与 Mul(振幅)：
 *           只读反射读到原私有 Node，再走 record 访问器 {@code Add.a()/Mul.a()/Mul.b()}
 *           取子节点，<b>不修改</b>原对象。raw 恒为 {@code Mul.a()}（乘数在 {@code .b()}），
 *           即"外层剥掉"而非"成品高度 ÷ 振幅"。
 *           dales 的 Power/Blend/warp、hills2 的 scaler2 都在 raw 里（它们是内部配方）。
 *           故 raw·amp + bias 与该族 height01 同义（float 乘/加，实测容差 0）。
 * PLATEAU   raw = {@code PlateauCandidate.dbgBase}（valley×cubic+top，Terrace <b>之前</b>）；
 *           surface 独立一个节点；Terrace 不进 raw。
 *           reference = (terrace_0..0.9(raw)+surface)·0.475 —— 对齐<b>修正原型</b>的端点，
 *           与共享 PlateauCandidate（Terrace 值域 1.05，本地历史偏差）不同。
 * BADLANDS  raw = {@code BadlandsCandidate.dbgHills}（mask 之后的山丘场）；
 *           warpX/warpZ/scaler 三节点供引擎重建 mod1/mod2 与 shape/detail。
 * MOUNTAIN  raw = mountains_1 的 <b>去 fancy</b> 源（无 DirectionWarped / Erosion / ×0.7）：
 *           链 {@code Mul(DirectionWarped(Erosion(src,erosionSeed), direction, 2), 0.7)}，
 *           用 record 访问器取 {@code Mul.a() → DirectionWarped.input() → Erosion.input()}，
 *           即<b>固定 body</b>；{@code direction} 与 {@code erosionSeed} 供引擎重建 fancy
 *           的固定侵蚀路由（{@code Erosion(raw, erosionSeed, 2, 0.65, 128, 0.15, 3.1, 0.8)}）。
 *           自建重建链的端点对拍见 catalog-check 的 rebuild-mountain.csv。
 * COMPOSITE 两个成员 a,b（低地）与真实 selector；amp=(1−α)ampA+α ampB、
 *           bias=(1−α)biasA+α biasB、raw=((1−α)ampA rawA+α ampB rawB)/amp，
 *           最后显式 clamp=1。α = mapClamped(selector, 0.375, 0.625)。
 *           ⚠ 展开后 raw·amp+bias = (1−α)(ampA rawA+biasA) + α(ampB rawB+biasB) ——
 *           这是<b>数学恒等</b>（两边同式），即<b>两个成员线性外壳输出的加权平均</b>，
 *           与参考 combine 同义。但它在 float 下只是近似：把 catalog 的 raw 重新乘回
 *           amp 再加 bias，与成员成品高度之间会有 float 舍入残差（实测见 combine-identity.csv），
 *           故<b>不能</b>称"逐位精确"。
 *           它<b>不是</b>非线性成品高度（台地/劣地/山地）的混合 —— 那些永远不进复合；
 *           与"纯 lerp"唯一的差别是 {@code max(0)}，其触发率同样见 combine-identity.csv。
 * </pre>
 *
 * <h3>种子</h3>
 * <p>族偏移沿用 {@code LandCompositionGallery.buildCombine}（以源码为准，勿凭记忆）：
 * {@code plain 0 / steppe 0 / dales 33 / hills1 11 / hills2 22 / plateau 44 / badlands 55 / m1 77}。
 * 复合 selector 的<b>两个 int 游标</b>与 shuffle 种子按
 * {@code CombineCandidate.seed} 的真实消费顺序复刻（10×(selector, warp) = 20 个，然后 1 个 shuffle）。</p>
 *
 * <p>⚠ {@code original()} <b>只</b>在 {@code seed == }{@link #GALLERY_SEED} 时可用（真 Gallery 臂），
 * 其它种子抛 {@link UnsupportedOperationException} —— 不要拿"用别的 seed 调 Gallery"冒充 seed 生效：
 * {@code LandCompositionGallery.buildCombine} 的种子写死 {@code SEED}。
 * <b>但这不等于"非标准种子未验证"</b>：{@code RecipeMorphCatalogCheck} 会用本目录自建的 8 个原子
 * {@code Family} 独立构造一个 {@code CombineCandidate}（selector/slot 游标只由 worldSeed 决定），
 * 在<b>每个种子</b>上逐位对拍 45 槽身份与 10 个复合端点 —— 见 {@code multi-seed.csv}。</p>
 */
final class RecipeMorphCatalog {

    /** 条目的族类。复合条目属于 {@link Kind#LOW}（成员全为低地），用 {@link Entry#isComposite()} 区分。 */
    public enum Kind { LOW, PLATEAU, BADLANDS, MOUNTAIN }

    // ============================================================ 常量（固定口径）

    /** Gallery 写死的世界种子（反射读取 {@code LandCompositionGallery.SEED}，不复制字面量）。 */
    static final long GALLERY_SEED = canonicalSeed();
    /** {@code buildCombineRegional(SEED, 3600f)} —— 与 compose-regional / compose-coastal 同一条构建路径。 */
    static final float REGIONAL_MODE_SCALE = 3600f;

    static final int OFF_PLAIN = 0;
    static final int OFF_STEPPE = 0;
    static final int OFF_DALES = 33;
    static final int OFF_HILLS1 = 11;
    static final int OFF_HILLS2 = 22;
    static final int OFF_PLATEAU = 44;
    static final int OFF_BADLANDS = 55;
    static final int OFF_MOUNTAIN = 77;

    /** {@code CombineCandidate.COMBINE_SELECTOR_SCALE}。 */
    static final float SELECTOR_SCALE = 600f;
    /** {@code Noises.blend(selector, a, b, mid 0.5, range 0.25)} → α 窗口。 */
    static final float ALPHA_LO = 0.375f;
    static final float ALPHA_HI = 0.625f;

    static final int ENTRY_COUNT = 18;
    static final int SLOT_COUNT = 45;
    static final int MIXABLE = 5;

    /**
     * 三个独立条目的固定下标（与 {@code LandCompositionGallery.buildCombine} 的
     * {@code unmixable[0..2]} 同序）。引擎与探针一律用这些常量，避免散落字面量 15/16/17。
     */
    static final int IDX_PLATEAU = 15;
    static final int IDX_BADLANDS = 16;
    static final int IDX_MOUNTAIN = 17;
    /** 复合条目下标区间（含端点）。 */
    static final int IDX_FIRST_COMPOSITE = MIXABLE;
    static final int IDX_LAST_COMPOSITE = IDX_PLATEAU - 1;

    /** 低地组权重（{@code LandCompositionGallery.buildCombine} 的 {@code mixWeights}）。 */
    static final float[] LOW_WEIGHTS = {2.0f, 1.0f, 1.5f, 2.0f, 2.0f};
    /** 独立条目权重（{@code unmixWeights}）：台地 5 / 劣地 3 / 山地 9。 */
    static final float PLATEAU_WEIGHT = 5.0f;
    static final float BADLANDS_WEIGHT = 3.0f;
    static final float MOUNTAIN_WEIGHT = 9.0f;
    /** 加权票槽的期望值（{@code round(w/min)}，min = 1.0 = steppe）。 */
    static final int[] EXPECTED_VOTES = {
        2, 1, 2, 2, 2,
        2, 2, 2, 2, 1, 2, 2, 2, 2, 2,
        5, 3, 9
    };

    /** 台地：Terrace 参数（与 {@code PlateauCandidate} 同值）；值域上界取下表的修正值。 */
    static final float TERRACE_RAMP = 0.9f;
    static final float TERRACE_CLIFF = 0.15f;
    static final float TERRACE_RAMP_HEIGHT = 0.35f;
    static final float TERRACE_BLEND = 0.4f;
    static final int TERRACE_STEPS = 4;
    /** 参考元数据声明值域的上界（0.75×0.6+0.3+0.15，与冻结原型同一表达式，故同一 float）。 */
    static final float PLATEAU_TERRACE_IN_MAX = 0.75f * 0.6f + 0.3f + 0.15f;
    /**
     * 共享 {@code PlateauCandidate} 用的 1.05，仅供 {@link Entry#oldReference}。
     *
     * <p>⚠ 这<b>不是</b>参考原码的值：参考声明上界是 0.9。1.05 是本项目早期把声明值域
     * 叠加 extra 余量留下的<b>本地历史偏差</b>，只作为对账/回归的历史口径保留。</p>
     */
    static final float LEGACY_TERRACE_IN_MAX = 1.05f;
    static final float PLATEAU_AMP = 0.475f;

    /** 劣地外壳（仅作说明/元数据；raw 只喂非线性算子，恒等式不成立）。 */
    static final float BADLANDS_AMP = 0.55f;
    static final float BADLANDS_BIAS = 0.025f;
    /** 山地外壳（同上；fancy 夹在中间）。 */
    static final float MOUNTAIN_AMP = 0.7f;

    static final String LIMITS =
            "coverage=18 entries (5 lowland originals + 10 lowland composites + plateau + badlands + m1)\n"
            + "not_covered=the three-way junction rendering, full-map / production engine wiring, "
            + "ocean + coastline assembly, Minecraft, runWorldgenGate, chunk-generation cost\n"
            + "raw_is_signal=LOW and COMPOSITE: the shell identity reference == clamp(raw*amp+bias) "
            + "holds mathematically; measured residuals are in shell-identity.csv, not asserted here\n"
            + "raw_precision=reference reproduces the original objects with the original recipes' own "
            + "float operations. It is bit-exact for lowland atoms against their OWN family height01, "
            + "for composites against the real CombineCandidate lambdas, and for badlands / m1 against "
            + "their recipe nodes. It does NOT prove that re-multiplying raw*amp+bias in double is "
            + "bit-identical; that is float-rounded, residuals in combine-identity.csv. See "
            + "endpoints.csv for the per-entry tolerances actually used\n"
            + "raw_is_input=PLATEAU (debugBase + separate surface + Terrace), BADLANDS (debugHills + "
            + "warpX/warpZ/scaler), MOUNTAIN (pre-fancy source + direction/erosionSeed): the identity "
            + "raw*amp+bias == reference is FALSE there by construction, and reference returns the "
            + "original recipe height instead. The engine may also rebuild those three from "
            + "seed + 44/55/77 independently; the catalog exposes surface/terrace/warpX/warpZ/scaler/"
            + "direction/erosionSeed so it does not have to\n"
            + "plateau_reference=reference is the CORRECTED prototype endpoint: Terrace value range is "
            + "the reference-declared 0..0.9 and it is bit-exact against "
            + "HillsPlateauMorphCandidate.sample(x,z,1). It therefore differs from the shared "
            + "PlateauCandidate, which uses 1.05. 1.05 is NOT the reference recipe metadata -- the "
            + "metadata declares 0.9; 1.05 is a local historical deviation of this project. That legacy "
            + "value is still exported as Entry.oldReference for bookkeeping only and is NOT an "
            + "endpoint; the measured spread between the two is in endpoints.csv, not restated here\n"
            + "composite_algebra=raw*amp+bias == (1-a)*(ampA*rawA+biasA) + a*(ampB*rawB+biasB) is a "
            + "mathematical identity, i.e. a lerp of the members' LINEAR SHELL outputs. In float it is "
            + "only approximate (float rounding), so it is not called bit-exact. Only max(0) can "
            + "differentiate it from a plain lerp; measured clamp engagement and residuals are in "
            + "combine-identity.csv\n"
            + "composites_never_mix=plateau / badlands / mountain never participate in a composite; "
            + "those three stay their own entries\n"
            + "original()=exposes the real Gallery arm (buildCombineRegional(SEED, 3600f)) ONLY on the "
            + "canonical seed; other seeds throw UnsupportedOperationException because "
            + "LandCompositionGallery.buildCombine hardcodes SEED. That is not 'unverified on other "
            + "seeds': catalog-check builds an independent CombineCandidate from this catalog's own 8 "
            + "atoms (same selector/slot cursor, driven only by the worldSeed) and bit-compares all 45 "
            + "slots and all 10 composite endpoints on every seed in multi-seed.csv\n"
            + "seed_offsets=plain 0 / steppe 0 / dales 33 / hills1 11 / hills2 22 / plateau 44 / "
            + "badlands 55 / m1 77, verified against LandCompositionGallery.buildCombine source; "
            + "composite selectors consume 2 ints per pair from the same cursor as "
            + "CombineCandidate.buildComposites, then the shuffle takes the next int\n"
            + "rebuild_proof=badlands and m1 expose enough signals for an engine to rebuild the "
            + "nonlinear part alone: rebuilt chains are bit-exact against the untouched original "
            + "recipes on all 4 seeds (rebuild-badlands.csv, rebuild-mountain.csv). The mountain "
            + "erosion route always reads the fixed m1 body (never re-selects a neighbour), and "
            + "body_max_abs_diff > 0 proves raw is the pre-fancy body, not the finished height\n"
            + "proven=per-entry raw/amp/bias/clamp; reference is bit-exact against the intended "
            + "original object for every entry -- lowland atoms vs their own family height01, "
            + "composites vs the real CombineCandidate lambdas, plateau vs "
            + "HillsPlateauMorphCandidate.sample(x,z,1) (NOT vs PlateauCandidate), badlands / m1 vs "
            + "their recipe nodes -- on the canonical seed, and on 3 extra seeds for every atom / "
            + "plateau / badlands / mountain; the composite DESCRIBED formula (pure double algebra) is "
            + "only approximately equal (see endpoints.csv for the tolerance actually used); plus "
            + "45-slot identity against the real Gallery slot table and against an independently built "
            + "CombineCandidate on all 4 seeds, selector seed and value parity, vote counts, seed "
            + "offsets, determinism, seed sensitivity, badlands/mountain rebuild chains, and "
            + "oldReference == shared PlateauCandidate (1.05). Measured debiasing numbers live in the "
            + "CSVs, not here\n"
            + "not_proven=visual acceptability, global continuity of an engine-side blend, terrain "
            + "quality of the corrected plateau range, performance; lowland/planetary three-way "
            + "junctions are the engine owner's scope\n";

    // ============================================================ 构造

    private final long seed;
    private final Entry[] entries = new Entry[ENTRY_COUNT];
    /** 45 个加权票槽 → 条目下标（已按真实 shuffle 顺序打散）。 */
    private final int[] slotTable;
    /** 仅当 {@code seed == GALLERY_SEED} 时为真 Gallery 臂。 */
    private final CombineCandidate original;

    /**
     * @param seed 世界种子；族种子 = {@code seed + 固定偏移}
     */
    RecipeMorphCatalog(long seed) {
        this.seed = seed;
        buildAtoms();
        int[] cursor = {base(seed)};
        buildComposites(cursor);
        this.slotTable = buildSlots(cursor[0]++);
        this.original = seed == GALLERY_SEED ? galleryOriginal() : null;
    }

    // ============================================================ 原子条目

    /** 5 低地 + 台地 + 劣地 + 山地。全部反射原私有 Node，只读不改原对象。 */
    private void buildAtoms() {
        entries[0] = low(0, "PLAIN_GEO", plains(seed + OFF_PLAIN), 0.15f, -0.02f, OFF_PLAIN,
                LOW_WEIGHTS[0]);
        entries[1] = low(1, "STEPPE", steppe(seed + OFF_STEPPE), 0.08f, -0.02f, OFF_STEPPE,
                LOW_WEIGHTS[1]);
        entries[2] = low(2, "DALES_GEO", dales(seed + OFF_DALES), 0.40f, 0f, OFF_DALES,
                LOW_WEIGHTS[2]);
        entries[3] = low(3, "HILLS1_GEO", hills1(seed + OFF_HILLS1), 0.60f, 0f, OFF_HILLS1,
                LOW_WEIGHTS[3]);
        entries[4] = low(4, "HILLS2", hills2(seed + OFF_HILLS2), 0.55f, 0f, OFF_HILLS2,
                LOW_WEIGHTS[4]);
        entries[15] = plateau(seed + OFF_PLATEAU);
        entries[16] = badlands(seed + OFF_BADLANDS);
        entries[17] = mountain(seed + OFF_MOUNTAIN);
    }

    private static PlainsCandidate plains(long familySeed) {
        PlainsCandidate p = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        p.seed(familySeed);
        return p;
    }

    private static PlainsCandidate steppe(long familySeed) {
        PlainsCandidate p = new PlainsCandidate(PlainsCandidate.Variant.STEPPE);
        p.seed(familySeed);
        return p;
    }

    private static HillsCandidate dales(long familySeed) {
        HillsCandidate h = new HillsCandidate(HillsCandidate.Variant.DALES_GEO);
        h.seed(familySeed);
        return h;
    }

    private static HillsCandidate hills1(long familySeed) {
        HillsCandidate h = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        h.seed(familySeed);
        return h;
    }

    private static HillsCandidate hills2(long familySeed) {
        HillsCandidate h = new HillsCandidate(HillsCandidate.Variant.HILLS_2);
        h.seed(familySeed);
        return h;
    }

    /** 低地条目：剥掉最后一层 {@code Add(偏置)} 与 {@code Mul(振幅)}，其余全在 raw 里。 */
    private static Entry low(int index, String id, Object recipe, float expectAmp, float expectBias,
                             int seedOffset, float weight) {
        RefNoise.Node height = node(field(recipe, "height"));
        Stripped s = stripOuter(height);
        if (s.amp != expectAmp || s.bias != expectBias) {
            throw new IllegalStateException(id + " outer shell changed: amp=" + s.amp
                    + " bias=" + s.bias + " (expected " + expectAmp + "/" + expectBias + ")");
        }
        Entry e = Entry.atom(index, id, Kind.LOW, weight, s.body, seedOffset);
        e.amp = s.amp;
        e.bias = s.bias;
        e.clampStrength = 0f;
        e.origin = height;
        return e;
    }

    /** 台地条目：raw = Terrace 之前的 {@code dbgBase}；surface 独立；Terrace 不进 raw。 */
    private Entry plateau(long familySeed) {
        PlateauCandidate p = new PlateauCandidate(PlateauCandidate.Variant.REF);
        p.seed(familySeed);
        RefNoise.Node height = node(field(p, "height"));
        RefNoise.Node dbgBase = node(field(p, "dbgBase"));
        RefNoise.Node dbgTerrace = node(field(p, "dbgTerrace"));
        if (!(height instanceof RefNoise.Mul mul) || !(mul.a() instanceof RefNoise.Add add)
                || !(mul.b() instanceof RefNoise.Constant c) || c.value() != PLATEAU_AMP) {
            throw new IllegalStateException("plateau chain is not Mul(Add(terrace,surface),0.475)");
        }
        if (add.a() != dbgTerrace || !(dbgTerrace instanceof RefNoise.Terrace ter)
                || ter.input() != dbgBase) {
            throw new IllegalStateException("plateau: terrace input is not dbgBase");
        }
        Entry e = Entry.atom(15, "PLATEAU_REF", Kind.PLATEAU, PLATEAU_WEIGHT, dbgBase, OFF_PLATEAU);
        e.surface = add.b();
        e.terrace = new RefNoise.Terrace((v, z) -> v, TERRACE_RAMP, TERRACE_CLIFF,
                TERRACE_RAMP_HEIGHT, TERRACE_BLEND, TERRACE_STEPS, 0f, PLATEAU_TERRACE_IN_MAX, false);
        e.amp = PLATEAU_AMP;
        e.bias = 0f;
        e.clampStrength = 0f;
        e.refNode = null;
        return e;
    }

    /**
     * 劣地条目：raw = {@code dbgHills}（mask 之后的山丘场）。
     *
     * <p>引擎节点按 {@code BadlandsCandidate.seed} 的真实消费序给种子：
     * mask=b+0、ridge=b+1、warp(400,2)=b+2、warp(18,1)=b+3、mod1 warp(100,1)=b+4（wx=b+4, wz=b+5）、
     * scaler=b+5。故 {@code warpX=Perlin(b+4,100,1)}、{@code warpZ=Perlin(b+5,100,1)}、
     * {@code scaler=Perlin(b+5,200,3)}。</p>
     */
    private Entry badlands(long familySeed) {
        BadlandsCandidate b = new BadlandsCandidate();
        b.seed(familySeed);
        RefNoise.Node height = node(field(b, "height"));
        RefNoise.Node dbgHills = node(field(b, "dbgHills"));
        RefNoise.Node dbgMask = node(field(b, "dbgMask"));
        if (!(dbgHills instanceof RefNoise.Mul mul) || mul.b() != dbgMask) {
            throw new IllegalStateException("badlands: dbgHills is not (warped × mask)");
        }
        int q = base(familySeed);
        Entry e = Entry.atom(16, "BADLANDS", Kind.BADLANDS, BADLANDS_WEIGHT, dbgHills, OFF_BADLANDS);
        e.warpX = RefNoise.Perlin.ofScale(q + 4, 100f, 1);
        e.warpZ = RefNoise.Perlin.ofScale(q + 5, 100f, 1);
        e.scaler = RefNoise.Perlin.ofScale(q + 5, 200f, 3);
        e.amp = BADLANDS_AMP;
        e.bias = BADLANDS_BIAS;
        e.clampStrength = 0f;
        e.refNode = height;
        return e;
    }

    /**
     * 山地条目：raw = mountains_1 的<b>去 fancy</b> 源。
     *
     * <p>原链是 {@code Mul(DirectionWarped(Erosion(src, m+4), direction(m+3), 2)·, 0.7)}，
     * 反射断言结构后取 {@code Erosion.input} 作 raw，并把 direction 与 erosion 种子交给引擎。</p>
     */
    private Entry mountain(long familySeed) {
        MountainsCandidate m = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        m.seed(familySeed);
        RefNoise.Node height = node(field(m, "height"));
        if (!(height instanceof RefNoise.Mul mul) || !(mul.b() instanceof RefNoise.Constant c)
                || c.value() != MOUNTAIN_AMP
                || !(mul.a() instanceof RefNoise.DirectionWarped dw)
                || !(dw.input() instanceof RefNoise.Erosion er)) {
            throw new IllegalStateException("mountains_1: chain is not Mul(DirectionWarped(Erosion)·,0.7)");
        }
        int q = base(familySeed);
        int directionSeed = (int) field(dw.direction(), "seed");
        int erosionSeed = (int) field(er, "seed");
        if (directionSeed != q + 3 || erosionSeed != q + 4) {
            throw new IllegalStateException("mountains_1: fancy seeds " + directionSeed + "/"
                    + erosionSeed + " != " + (q + 3) + "/" + (q + 4));
        }
        Entry e = Entry.atom(17, "M1_GEO", Kind.MOUNTAIN, MOUNTAIN_WEIGHT,
                node(field(er, "input")), OFF_MOUNTAIN);
        e.direction = dw.direction();
        e.erosionSeed = erosionSeed;
        e.amp = MOUNTAIN_AMP;
        e.bias = 0f;
        e.clampStrength = 0f;
        e.refNode = height;
        return e;
    }

    // ============================================================ 复合条目

    /** {@code CombineCandidate.buildComposites}：每对消费 (selectorPerlin, warpPerlin) 两个 int。 */
    private void buildComposites(int[] cursor) {
        int k = MIXABLE;
        for (int i = 0; i < MIXABLE; i++) {
            for (int j = i + 1; j < MIXABLE; j++) {
                RefNoise.Node selector = RefNoise.Perlin.ofScale(cursor[0]++, SELECTOR_SCALE, 1);
                selector = RefNoise.warpPerlin(selector, cursor[0]++, SELECTOR_SCALE / 2f, 2,
                        SELECTOR_SCALE / 2f);
                entries[k] = Entry.composite(k, entries[i].id + "+" + entries[j].id,
                        (entries[i].weight + entries[j].weight) / 2f, entries[i], entries[j], selector);
                k++;
            }
        }
    }

    /** {@code expandByWeight} + {@code Collections.shuffle(new Random(cursor))}，逐行复刻。 */
    private int[] buildSlots(int shuffleSeed) {
        float smallest = Float.MAX_VALUE;
        for (Entry e : entries) {
            if (e.weight > 0f) {
                smallest = Math.min(smallest, e.weight);
            }
        }
        if (smallest == Float.MAX_VALUE) {
            smallest = 1f;
        }
        int[] counts = new int[ENTRY_COUNT];
        int total = 0;
        for (int i = 0; i < ENTRY_COUNT; i++) {
            counts[i] = entries[i].weight <= 0f ? 0 : Math.round(entries[i].weight / smallest);
            total += counts[i];
        }
        int[] expanded = new int[total];
        int at = 0;
        for (int i = 0; i < ENTRY_COUNT; i++) {
            for (int c = 0; c < counts[i]; c++) {
                expanded[at++] = i;
            }
        }
        List<Integer> order = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(shuffleSeed));
        int[] shuffled = new int[total];
        for (int i = 0; i < total; i++) {
            shuffled[i] = expanded[order.get(i)];
        }
        if (total != SLOT_COUNT) {
            throw new IllegalStateException("slot count " + total + " != " + SLOT_COUNT);
        }
        return shuffled;
    }

    // ============================================================ API

    /** 条目总数（恒 18）。 */
    int size() { return ENTRY_COUNT; }

    /** 票槽总数（恒 45）。 */
    int slotCount() { return slotTable.length; }

    /** 第 {@code index} 个条目（固定顺序）。 */
    Entry entry(int index) {
        if (index < 0 || index >= ENTRY_COUNT) {
            throw new IndexOutOfBoundsException("entry index " + index + " outside [0," + ENTRY_COUNT + ")");
        }
        return entries[index];
    }

    /** 第 {@code slot} 个加权票槽对应的条目下标（真实 45 槽映射）。 */
    int slotEntry(int slot) {
        if (slot < 0 || slot >= slotTable.length) {
            throw new IndexOutOfBoundsException("slot " + slot + " outside [0,"
                    + slotTable.length + ")");
        }
        return slotTable[slot];
    }

    /** 全部条目（不可变视图，顺序 = 固定顺序）。 */
    List<Entry> entries() { return List.of(entries); }

    long seed() { return seed; }

    /**
     * 真 Gallery 臂（{@code buildCombineRegional(SEED, 3600f)} 的反射结果）。
     *
     * @throws UnsupportedOperationException 非 {@link #GALLERY_SEED} 的种子 —— Gallery 的
     *         {@code buildCombine} 写死 {@code SEED}，换参数不会换种子
     */
    CombineCandidate original() {
        if (original == null) {
            throw new UnsupportedOperationException("original() only exists for the canonical Gallery"
                    + " seed; seed=" + seed + " would silently reuse the SEED-hardcoded arm");
        }
        return original;
    }

    // ============================================================ 反射 & 工具

    private static CombineCandidate galleryOriginal() {
        try {
            Class<?> gallery = Class.forName(
                    "com.geogenesis.worldgen.terrain.candidate.LandCompositionGallery");
            Method build = gallery.getDeclaredMethod("buildCombineRegional", long.class, float.class);
            build.setAccessible(true);
            return (CombineCandidate) build.invoke(null, GALLERY_SEED, REGIONAL_MODE_SCALE);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build the Gallery combine arm", e);
        }
    }

    private static long canonicalSeed() {
        try {
            Class<?> gallery = Class.forName(
                    "com.geogenesis.worldgen.terrain.candidate.LandCompositionGallery");
            Field f = gallery.getDeclaredField("SEED");
            f.setAccessible(true);
            return f.getLong(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read LandCompositionGallery.SEED", e);
        }
    }

    /** 沿类层次读私有字段（只读）。 */
    static Object field(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot read " + name + " of " + target.getClass(), e);
            }
        }
        throw new IllegalStateException("no field '" + name + "' on " + target.getClass().getName());
    }

    private static RefNoise.Node node(Object o) {
        if (!(o instanceof RefNoise.Node n)) {
            throw new IllegalStateException("expected a RefNoise.Node, got " + o);
        }
        return n;
    }

    static int base(long worldSeed) { return (int) (worldSeed ^ (worldSeed >>> 32)); }

    /** 剥掉最后一层 Add(偏置) 与 Mul(振幅) 的结果。 */
    record Stripped(RefNoise.Node body, float amp, float bias) { }

    /**
     * 只剥<b>外层各一层</b>：先 {@code Add(·, const)} 取偏置，再 {@code Mul(·, const)} 取振幅。
     *
     * <p><b>取哪一侧是判据，不是随手</b>：原族的末道恒为 {@code Add(Mul(body, amp), bias)}，
     * 故常量在 {@code .b()}、{{@code raw}} 在 {@code .a()}。这里用 record 访问器
     * （{@code RefNoise.Mul/Add} 的 {@code a()/b()}）而非反射，且 {@code .a()} 是常量时
     * <b>直接报错</b> —— 若有人把链改成 {@code Add(bias, body)}，raw 身份就不再是
     * "Mul.a()"，必须立刻失败而不是静默换一侧。</p>
     *
     * <p>不递归 —— dales 的普通 {@code ×0.4}、hills2 的 {@code ×scaler2} 都属于内部配方，必须留在 raw。</p>
     */
    static Stripped stripOuter(RefNoise.Node node) {
        RefNoise.Node cur = node;
        float bias = 0f;
        float amp = 1f;
        if (cur instanceof RefNoise.Add add) {
            if (!(add.b() instanceof RefNoise.Constant c)) {
                throw new IllegalStateException("outer Add is not Add(body, Constant): b=" + add.b());
            }
            bias = c.value();
            cur = add.a();
        }
        if (cur instanceof RefNoise.Mul mul) {
            if (!(mul.b() instanceof RefNoise.Constant c)) {
                if (mul.a() instanceof RefNoise.Constant) {
                    throw new IllegalStateException("outer Mul is Mul(Constant, body) -> raw would be b()");
                }
                throw new IllegalStateException("outer Mul is not Mul(body, Constant): b=" + mul.b());
            }
            amp = c.value();
            cur = mul.a();
        }
        return new Stripped(cur, amp, bias);
    }

    // ============================================================ 条目

    /**
     * 目录条目。身份字段 {@code final}；配方/外壳字段由构造期的族类工厂一次性赋值，
     * 构造完成后只读（{@code RecipeMorphCatalogCheck} 只调访问器）。
     */
    static final class Entry {

        private final int index;
        private final String id;
        private final Kind kind;
        private final float weight;
        private final int seedOffset;
        /** 非复合：信号节点；复合：null（由两个成员现算）。 */
        private RefNoise.Node raw;
        private float amp;
        private float bias;
        /** 0 或 1：外壳之后是否施加 {@code max(·,0)}。 */
        private float clampStrength;
        private Entry left;
        private Entry right;
        private RefNoise.Node selector;
        private RefNoise.Node terrace;
        /** 台地：旧值域 0..1.05 的 Terrace（懒建，只给 {@link #oldReference}）。 */
        private RefNoise.Node legacyTerrace;
        private RefNoise.Node surface;
        /** 台地/劣地/山地的原配方高度节点（低地/复合为 null，改用线性外壳现算）。 */
        private RefNoise.Node refNode;
        /**
         * 低地原子的<b>原 {@code height} 节点</b>（未改动的那一棵）。
         *
         * <p>用途：证明 {@code raw} 是"从原树上剥一层"得到的同一个子节点 ——
         * {@code stripOuter(origin).body() == raw}。若有人把 raw 换成"成品高度 ÷ amp"之类的
         * 数值伪装，这条身份判据立刻失败。多一次引用，不新增任何节点。</p>
         */
        private RefNoise.Node origin;
        private RefNoise.Node warpX;
        private RefNoise.Node warpZ;
        private RefNoise.Node scaler;
        private RefNoise.Node direction;
        private int erosionSeed;

        private Entry(int index, String id, Kind kind, float weight, int seedOffset) {
            this.index = index;
            this.id = id;
            this.kind = kind;
            this.weight = weight;
            this.seedOffset = seedOffset;
        }

        static Entry atom(int index, String id, Kind kind, float weight, RefNoise.Node raw,
                          int seedOffset) {
            Entry e = new Entry(index, id, kind, weight, seedOffset);
            e.raw = raw;
            return e;
        }

        static Entry composite(int index, String id, float weight, Entry a, Entry b,
                               RefNoise.Node selector) {
            Entry e = new Entry(index, id, Kind.LOW, weight, -1);
            e.left = a;
            e.right = b;
            e.selector = selector;
            e.clampStrength = 1f;
            return e;
        }

        int index() { return index; }

        String id() { return id; }

        Kind kind() { return kind; }

        float weight() { return weight; }

        int seedOffset() { return seedOffset; }

        boolean isComposite() { return left != null; }

        Entry left() { return left; }

        Entry right() { return right; }

        boolean clamped() { return clampStrength == 1f; }

        /** 信号层（复合条目按 α 现算）。 */
        double raw(float x, float z) {
            if (!isComposite()) {
                return raw.compute(x, z);
            }
            double a = alpha(x, z);
            double ampA = left.amplitude(x, z);
            double ampB = right.amplitude(x, z);
            double ampC = (1 - a) * ampA + a * ampB;
            double weighted = (1 - a) * ampA * left.raw(x, z) + a * ampB * right.raw(x, z);
            return weighted / ampC;
        }

        /** 线性外壳的振幅。 */
        double amplitude(float x, float z) {
            if (!isComposite()) {
                return amp;
            }
            double a = alpha(x, z);
            return (1 - a) * left.amplitude(x, z) + a * right.amplitude(x, z);
        }

        /** 线性外壳的偏置。 */
        double bias(float x, float z) {
            if (!isComposite()) {
                return bias;
            }
            double a = alpha(x, z);
            return (1 - a) * left.bias(x, z) + a * right.bias(x, z);
        }

        /** 复合条目的 α ∈ [0,1]（真实 selector × [0.375,0.625]）；非复合为 {@code NaN}。 */
        double alpha(float x, float z) {
            if (!isComposite()) {
                return Double.NaN;
            }
            return RefNoise.mapClamped(selector.compute(x, z), ALPHA_LO, ALPHA_HI);
        }

        /** 复合条目的选择器节点（{@code warpPerlin(perlin(600,1), 300, 2, 300)}）；非复合为 null。 */
        RefNoise.Node selector() { return selector; }

        /**
         * 原配方高度（old exact reference）：按原配方自己的 float 运算复现。
         *
         * <ul>
         *   <li>LOW 原子：{@code body·amp + bias}，乘/加都用 float（与原节点
         *       {@code Add(Mul(body,amp),bias)} 同序），故与<b>该族</b> {@code height01} 逐位相等；</li>
         *   <li>复合：{@code max(v + (w−v)·α, 0)}，{@code α} 是 float —— 与
         *       {@code CombineCandidate} 复合 lambda 同一算式。注意这里<b>委托</b>左右成员的
         *       {@code reference}，所以"逐位"只证明委托式与原复合一致，
         *       <b>不证明</b>"把 raw 重新乘回 amp 再加 bias"也逐位（那是 float 近似）；</li>
         *   <li>台地：对齐<b>修正原型</b>（{@code HillsPlateauMorphCandidate.sample(x,z,1)}，
         *       {@code Terrace} 值域 0..0.9）—— <b>不是</b>共享 {@code PlateauCandidate}（1.05）；</li>
         *   <li>劣地 / 山地：原配方节点。</li>
         * </ul>
         *
         * @return 参考高度（与 {@code height01} 同口径，尚未乘 {@code E_PER_REF_UNIT}）
         */
        double reference(double x, double z) {
            float fx = (float) x;
            float fz = (float) z;
            if (kind == Kind.PLATEAU) {
                return correctedPlateau(fx, fz);
            }
            if (refNode != null) {
                return refNode.compute(fx, fz);
            }
            if (!isComposite()) {
                return shellFloat(fx, fz, amp, bias);
            }
            // 原 combine：max(v + (w - v) * alpha, 0)，alpha 是 float。
            float alpha = alphaFloat(fx, fz);
            double v = left.reference(fx, fz);
            double w = right.reference(fx, fz);
            return Math.max(v + (w - v) * alpha, 0.0);
        }

        /**
         * <b>旧口径</b>参考高度：仅台地与"共享 {@code PlateauCandidate} 的本地历史行为"不同。
         *
         * <p>台地返回 {@code Terrace} 上界 <b>1.05</b> 的同一链，用来如实记录与共享
         * {@code PlateauCandidate} 的偏差。⚠ 1.05 <b>不是</b>参考原码口径：参考元数据声明的
         * 上界是 <b>0.9</b>，1.05 是本项目早期留下的本地偏差。其余条目与 {@link #reference} 相同。
         * <b>它不是端点</b>，引擎不得用它。</p>
         */
        double oldReference(double x, double z) {
            if (kind != Kind.PLATEAU) {
                return reference(x, z);
            }
            float fx = (float) x;
            float fz = (float) z;
            float shaped = legacyTerrace().compute(raw.compute(fx, fz), 0f);
            return (shaped + surface.compute(fx, fz)) * PLATEAU_AMP;
        }

        /** 修正值域（参考元数据声明 0..0.9）下的台地高度：{@code (terrace(raw) + surface)·0.475}。 */
        private double correctedPlateau(float fx, float fz) {
            float shaped = terrace.compute(raw.compute(fx, fz), 0f);
            return (shaped + surface.compute(fx, fz)) * PLATEAU_AMP;
        }

        /** 台地的历史口径 {@code Terrace} 节点（值域 0..1.05，本地偏差），懒建以免非台地条目分配。 */
        private RefNoise.Node legacyTerrace() {
            if (legacyTerrace == null) {
                legacyTerrace = new RefNoise.Terrace((v, z) -> v, TERRACE_RAMP, TERRACE_CLIFF,
                        TERRACE_RAMP_HEIGHT, TERRACE_BLEND, TERRACE_STEPS, 0f, LEGACY_TERRACE_IN_MAX,
                        false);
            }
            return legacyTerrace;
        }

        /**
         * 线性外壳的 float 求值：{@code body·amp + bias}，乘/加都在 float 里做。
         * <p>与原节点 {@code Add(Mul(body, amp), bias)} 同序，故对该族 {@code height01} 逐位相等。</p>
         */
        private double shellFloat(float fx, float fz, float a, float b) {
            return raw.compute(fx, fz) * a + b;
        }

        /** 复合 α 的 float 值（与 {@code CombineCandidate} 的复合 lambda 同一 float 表达式）。 */
        float alphaFloat(float fx, float fz) {
            return isComposite() ? RefNoise.mapClamped(selector.compute(fx, fz), ALPHA_LO, ALPHA_HI) : 0f;
        }

        /** 台地：{@code surface} 的值（独立于 raw 的细节层）。非台地返回 {@code NaN}。 */
        double surface(float x, float z) {
            return surface == null ? Double.NaN : surface.compute(x, z);
        }

        /** 台地：修正值域 {@code Terrace} 对<b>一个标量</b>的输出（{@code z} 位传 0）。 */
        double terraceValue(double source) {
            return terrace == null ? Double.NaN : terrace.compute((float) source, 0f);
        }

        /** 台地：surface 节点（独立于 raw）。 */
        RefNoise.Node surface() { return surface; }

        /** 台地/reference 用的 Terrace 节点（值域 0..0.9）。 */
        RefNoise.Node terrace() { return terrace; }

        /** 劣地：mod1 位移用的 X 噪声。 */
        RefNoise.Node warpX() { return warpX; }

        /** 劣地：mod1 位移用的 Z 噪声。 */
        RefNoise.Node warpZ() { return warpZ; }

        /** 劣地：mod2 的振幅噪声（100 尺度 warp 与之共用 seed=b+5）。 */
        RefNoise.Node scaler() { return scaler; }

        /** 山地：fancy 的方向扰动节点。 */
        RefNoise.Node direction() { return direction; }

        /** 山地：fancy 的侵蚀纹理种子（{@code Erosion(src, seed, 2, 0.65, 128, 0.15, 3.1, 0.8)}）。 */
        int erosionSeed() { return erosionSeed; }

        /** 低地原子：未改动的原 {@code height} 节点（非原子返回 null）；供 raw 身份判据。 */
        RefNoise.Node originNode() { return origin; }

        /**
         * 原始信号节点本身的引用（低地原子 = 剥壳后的 {@code Mul.a()}；台地 = {@code dbgBase}；
         * 劣地 = {@code dbgHills}；山地 = {@code Erosion.input}；<b>复合返回 null</b>）。
         *
         * <p>给引擎/探针自建重建链用（劣地的 mod1/mod2、山地的固定侵蚀路由）。
         * 返回的是<b>同一个节点对象</b>，只读；调用方不得持有可变状态。</p>
         */
        RefNoise.Node rawNode() { return raw; }
    }
}
