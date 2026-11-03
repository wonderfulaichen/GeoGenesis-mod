package com.geogenesis.worldgen.terrain.morph;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>18 条目地貌配方目录</b>：5 个低地原始 + 10 个低地两两复合 + 台地 + 劣地 + 山地。
 *
 * <p>顺序<b>固定</b>，与参考 {@code TerrainProvider.generateTerrain} 的
 * {@code mixable → combine 两两复合 → unmixable} 完全同序：</p>
 * <pre>
 *   0  PLAIN_GEO   1 STEPPE   2 DALES_GEO   3 HILLS1_GEO   4 HILLS2
 *   5  PLAIN_GEO+STEPPE      6 PLAIN_GEO+DALES_GEO    7 PLAIN_GEO+HILLS1_GEO
 *   8  PLAIN_GEO+HILLS2      9 STEPPE+DALES_GEO      10 STEPPE+HILLS1_GEO
 *  11  STEPPE+HILLS2        12 DALES_GEO+HILLS1_GEO  13 DALES_GEO+HILLS2
 *  14  HILLS1_GEO+HILLS2    15 PLATEAU_REF           16 BADLANDS      17 M1_GEO
 * </pre>
 *
 * <h3>为什么要把配方拆成三件</h3>
 * <p>引擎要做类型过渡，就不能把"成品高度"当外区域高度去 lerp（那会把形态抹平，且端点处
 * 与真配方不等）。所以每个条目把配方拆成三件<b>可分别过渡</b>的东西：</p>
 * <ul>
 *   <li>{@code raw} —— <b>信号层</b>：剥掉最后一道振幅/偏置后的完整内部配方；</li>
 *   <li>{@code amplitude} / {@code bias} —— <b>线性外壳</b>，允许随位置连续变化；</li>
 *   <li>{@code clamped()} —— 外壳之后是否还有 {@code max(·,0)}（复合条目有，其余没有）。</li>
 * </ul>
 *
 * <h3>★ 外壳剥法（判据，不是随手）</h3>
 * <p>各族的末道恒为 {@code Add(Mul(body, amp), bias)}，故常量在 {@code .b()}、raw 在
 * {@code .a()}。{@link #stripOuter} 只剥<b>外层各一层</b>，且 {@code .a()} 是常量时
 * <b>直接报错</b> —— 若有人把链改成 {@code Add(bias, body)}，raw 身份就不再是
 * {@code Mul.a()}，必须立刻失败而不是静默换一侧。
 * 不递归：dales 的普通 {@code ×0.4}、hills2 的 {@code ×scaler2} 都属于内部配方，必须留在 raw。</p>
 *
 * <h3>★ 台的 reference 用修正值域 0..0.9，不是共享台地的 1.05</h3>
 * <p>{@link PlateauRecipe} 内部 {@code Terrace} 的上界是 <b>1.05</b>（本项目早期留下的
 * 本地历史偏差）。morph 条目对外的一律口径是参考元数据声明的 <b>0.9</b>，
 * 所以本目录给台地单独构造一个 {@code inMax=0.9} 的 Terrace 节点。
 * 1.05 那版只作为"旧口径"暴露，引擎不得用它。</p>
 *
 * <h3>★ 种子</h3>
 * <p>族偏移沿用既有口径（以源码为准，勿凭记忆）：
 * {@code plain 0 / steppe 0 / dales 33 / hills1 11 / hills2 22 / plateau 44 / badlands 55 / m1 77}。
 * 复合 selector 的<b>两个 int 游标</b>与 shuffle 种子按 {@code CombineCandidate.seed} 的
 * 真实消费顺序复刻（10×(selector, warp) = 20 个，然后 1 个 shuffle）。</p>
 */
final class MorphCatalog {

    /** 条目的族类。复合条目属于 {@link Kind#LOW}，用 {@link Entry#isComposite()} 区分。 */
    enum Kind { LOW, PLATEAU, BADLANDS, MOUNTAIN }

    // ============================================================ 常量（固定口径）

    /**
     * 正典世界种子（本项目已实机认可的那张标准图用的种子）。
     *
     * <p>⚠ 本包对它与其它种子<b>走完全相同的代码路径</b>：正典与否不再改变任何分支。
     * 历史实现曾把正典种子接到"用反射调用一个写死 SEED 的构建方法"那条路上 ——
     * 那既不可移植（换参数不换种子），也会把整棵诊断/出图工具树拖进生产包。
     * P0 提取已删除该路径；{@link MorphAssembly} 用同一套种子算术重建了等价物，
     * 并由 P0 对拍确认正典图逐位不变。</p>
     */
    static final long CANONICAL_SEED = 9139912035078620160L;

    static final int OFF_PLAIN = 0;
    static final int OFF_STEPPE = 0;
    static final int OFF_DALES = 33;
    static final int OFF_HILLS1 = 11;
    static final int OFF_HILLS2 = 22;
    static final int OFF_PLATEAU = 44;
    static final int OFF_BADLANDS = 55;
    static final int OFF_MOUNTAIN = 77;

    /** 复合 selector 的噪声尺度（{@code TerrainProvider} 的 {@code regionSize/2}）。 */
    static final float SELECTOR_SCALE = 600f;
    /** {@code Noises.blend(selector, a, b, mid 0.5, range 0.25)} → alpha 窗口。 */
    static final float ALPHA_LO = 0.375f;
    static final float ALPHA_HI = 0.625f;

    static final int ENTRY_COUNT = 18;
    static final int SLOT_COUNT = 45;
    static final int MIXABLE = 5;

    /** 三个独立条目的固定下标（与 {@code unmixable[0..2]} 同序）。 */
    static final int IDX_PLATEAU = 15;
    static final int IDX_BADLANDS = 16;
    static final int IDX_MOUNTAIN = 17;

    /** 低地组权重（{@code buildCombine} 的 {@code mixWeights}）。 */
    static final float[] LOW_WEIGHTS = {2.0f, 1.0f, 1.5f, 2.0f, 2.0f};
    /** 独立条目权重：台地 5 / 劣地 3 / 山地 9（合计 45 票）。 */
    static final float PLATEAU_WEIGHT = 5.0f;
    static final float BADLANDS_WEIGHT = 3.0f;
    static final float MOUNTAIN_WEIGHT = 9.0f;

    /** 台地：Terrace 参数（与 {@link PlateauRecipe} 同值）；值域上界取下表的修正值。 */
    static final float TERRACE_RAMP = 0.9f;
    static final float TERRACE_CLIFF = 0.15f;
    static final float TERRACE_RAMP_HEIGHT = 0.35f;
    static final float TERRACE_BLEND = 0.4f;
    static final int TERRACE_STEPS = 4;
    /** 参考元数据声明值域的上界（0.75×0.6+0.3+0.15，与冻结原型同一表达式 ⇒ 同一 float）。 */
    static final float PLATEAU_TERRACE_IN_MAX = 0.75f * 0.6f + 0.3f + 0.15f;
    /**
     * 共享台地（{@code PlateauRecipe}）自己用的值域上界 <b>1.05</b>。
     *
     * <p>⚠ 这<b>不是</b>参考原码的值：参考声明上界是 0.9。1.05 是本项目早期把声明值域
     * 叠加 extra 余量留下的<b>本地历史偏差</b>。</p>
     *
     * <p><b>本包不读这个常量</b> —— 覆盖层直接取 {@code PlateauRecipe} 自己的高度节点，
     * 那个节点内部就是 1.05。它在这里只作<b>文档</b>：{@link #PLATEAU_TERRACE_IN_MAX}（0.9）
     * 与它（1.05）是<b>两个不同的值、两个不同的用途</b>，混用会让覆盖后高度整体偏移。
     * 若将来有人想"统一成一个值"，必须先读 {@code MorphRegionContext} 的类注释。</p>
     */
    static final float LEGACY_TERRACE_IN_MAX = 1.05f;
    static final float PLATEAU_AMP = 0.475f;

    /** 劣地外壳（仅作说明/元数据；raw 只喂非线性算子，恒等式不成立）。 */
    static final float BADLANDS_AMP = 0.55f;
    static final float BADLANDS_BIAS = 0.025f;
    /** 山地外壳（同上；fancy 夹在中间）。 */
    static final float MOUNTAIN_AMP = 0.7f;

    // ============================================================ 构造

    private final long seed;
    private final Entry[] entries = new Entry[ENTRY_COUNT];
    /** 45 个加权票槽 → 条目下标（已按真实 shuffle 顺序打散）。 */
    private final int[] slotTable;

    /**
     * @param seed 世界种子；族种子 = {@code seed + 固定偏移}
     */
    MorphCatalog(long seed) {
        this.seed = seed;
        buildAtoms();
        int[] cursor = {base(seed)};
        buildComposites(cursor);
        this.slotTable = buildSlots(cursor[0]++);
    }

    // ============================================================ 原子条目

    /** 5 低地 + 台地 + 劣地 + 山地。全部走配方的显式访问器，只读不改原对象。 */
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
        entries[IDX_PLATEAU] = plateau(seed + OFF_PLATEAU);
        entries[IDX_BADLANDS] = badlands(seed + OFF_BADLANDS);
        entries[IDX_MOUNTAIN] = mountain(seed + OFF_MOUNTAIN);
    }

    private static PlainsRecipe plains(long familySeed) {
        PlainsRecipe p = new PlainsRecipe(PlainsRecipe.Variant.PLAINS_GEO);
        p.seed(familySeed);
        return p;
    }

    private static PlainsRecipe steppe(long familySeed) {
        PlainsRecipe p = new PlainsRecipe(PlainsRecipe.Variant.STEPPE);
        p.seed(familySeed);
        return p;
    }

    private static HillsRecipe dales(long familySeed) {
        HillsRecipe h = new HillsRecipe(HillsRecipe.Variant.DALES_GEO);
        h.seed(familySeed);
        return h;
    }

    private static HillsRecipe hills1(long familySeed) {
        HillsRecipe h = new HillsRecipe(HillsRecipe.Variant.HILLS_1_GEO);
        h.seed(familySeed);
        return h;
    }

    private static HillsRecipe hills2(long familySeed) {
        HillsRecipe h = new HillsRecipe(HillsRecipe.Variant.HILLS_2);
        h.seed(familySeed);
        return h;
    }

    /** 低地条目：剥掉最后一层 {@code Add(偏置)} 与 {@code Mul(振幅)}，其余全在 raw 里。 */
    private static Entry low(int index, String id, HasHeightNode recipe, float expectAmp,
                             float expectBias, int seedOffset, float weight) {
        MorphNoise.Node height = recipe.heightNode();
        Stripped s = stripOuter(height);
        if (s.amp() != expectAmp || s.bias() != expectBias) {
            throw new IllegalStateException(id + " outer shell changed: amp=" + s.amp()
                    + " bias=" + s.bias() + " (expected " + expectAmp + "/" + expectBias + ")");
        }
        Entry e = Entry.atom(index, id, Kind.LOW, weight, s.body(), seedOffset);
        e.amp = s.amp();
        e.bias = s.bias();
        e.clampStrength = 0f;
        e.origin = height;
        return e;
    }

    /** 一个配方对外暴露它的高度节点（与 {@code height01} 同一棵树）。 */
    interface HasHeightNode {
        MorphNoise.Node heightNode();
    }

    /** 台地条目：raw = Terrace 之前的 {@code dbgBase}；surface 独立；Terrace 不进 raw。 */
    private Entry plateau(long familySeed) {
        PlateauRecipe p = new PlateauRecipe(PlateauRecipe.Variant.REF);
        p.seed(familySeed);
        MorphNoise.Node height = p.heightNode();
        MorphNoise.Node dbgBase = p.baseNode();
        MorphNoise.Node dbgTerrace = p.terraceNode();
        if (!(height instanceof MorphNoise.Mul mul) || !(mul.a() instanceof MorphNoise.Add add)
                || !(mul.b() instanceof MorphNoise.Constant c) || c.value() != PLATEAU_AMP) {
            throw new IllegalStateException("plateau chain is not Mul(Add(terrace,surface),0.475)");
        }
        if (add.a() != dbgTerrace || !(dbgTerrace instanceof MorphNoise.Terrace ter)
                || ter.input() != dbgBase) {
            throw new IllegalStateException("plateau: terrace input is not dbgBase");
        }
        Entry e = Entry.atom(IDX_PLATEAU, "PLATEAU_REF", Kind.PLATEAU, PLATEAU_WEIGHT, dbgBase,
                OFF_PLATEAU);
        e.surface = add.b();
        e.terrace = new MorphNoise.Terrace(new MorphNoise.ScalarIdentity(), TERRACE_RAMP, TERRACE_CLIFF,
                TERRACE_RAMP_HEIGHT, TERRACE_BLEND, TERRACE_STEPS, 0f, PLATEAU_TERRACE_IN_MAX, false);
        e.amp = PLATEAU_AMP;
        e.bias = 0f;
        e.clampStrength = 0f;
        return e;
    }

    /**
     * 劣地条目：raw = {@code dbgHills}（mask 之后的山丘场）。
     *
     * <p>引擎节点按 {@code BadlandsRecipe.seed} 的真实消费序给种子：
     * mask=b+0、ridge=b+1、warp(400,2)=b+2、warp(18,1)=b+3、mod1 warp(100,1)=b+4（wx=b+4, wz=b+5）、
     * scaler=b+5。故 {@code warpX=Perlin(b+4,100,1)}、{@code warpZ=Perlin(b+5,100,1)}、
     * {@code scaler=Perlin(b+5,200,3)}。</p>
     */
    private Entry badlands(long familySeed) {
        BadlandsRecipe b = new BadlandsRecipe();
        b.seed(familySeed);
        MorphNoise.Node height = b.heightNode();
        MorphNoise.Node dbgHills = b.hillsNode();
        MorphNoise.Node dbgMask = b.maskNode();
        if (!(dbgHills instanceof MorphNoise.Mul mul) || mul.b() != dbgMask) {
            throw new IllegalStateException("badlands: dbgHills is not (warped x mask)");
        }
        int q = base(familySeed);
        Entry e = Entry.atom(IDX_BADLANDS, "BADLANDS", Kind.BADLANDS, BADLANDS_WEIGHT, dbgHills,
                OFF_BADLANDS);
        e.warpX = MorphNoise.Perlin.ofScale(q + 4, 100f, 1);
        e.warpZ = MorphNoise.Perlin.ofScale(q + 5, 100f, 1);
        e.scaler = MorphNoise.Perlin.ofScale(q + 5, 200f, 3);
        e.amp = BADLANDS_AMP;
        e.bias = BADLANDS_BIAS;
        e.clampStrength = 0f;
        return e;
    }

    /**
     * 山地条目：raw = mountains_1 的<b>去 fancy</b> 源。
     *
     * <p>原链是 {@code Mul(DirectionWarped(Erosion(src, m+4), direction(m+3), 2), 0.7)}，
     * 断言结构后取 {@code Erosion.input} 作 raw，并把 direction 与 erosion 种子交给引擎。</p>
     */
    private Entry mountain(long familySeed) {
        MountainsRecipe m = new MountainsRecipe(MountainsRecipe.Variant.M1_GEO);
        m.seed(familySeed);
        MorphNoise.Node height = m.heightNode();
        if (!(height instanceof MorphNoise.Mul mul) || !(mul.b() instanceof MorphNoise.Constant c)
                || c.value() != MOUNTAIN_AMP
                || !(mul.a() instanceof MorphNoise.DirectionWarped dw)
                || !(dw.input() instanceof MorphNoise.Erosion er)) {
            throw new IllegalStateException("mountains_1: chain is not Mul(DirectionWarped(Erosion),0.7)");
        }
        int q = base(familySeed);
        int directionSeed = ((MorphNoise.Perlin) dw.direction()).seed();
        int erosionSeed = er.seed();
        if (directionSeed != q + 3 || erosionSeed != q + 4) {
            throw new IllegalStateException("mountains_1: fancy seeds " + directionSeed + "/"
                    + erosionSeed + " != " + (q + 3) + "/" + (q + 4));
        }
        Entry e = Entry.atom(IDX_MOUNTAIN, "M1_GEO", Kind.MOUNTAIN, MOUNTAIN_WEIGHT,
                er.input(), OFF_MOUNTAIN);
        e.direction = dw.direction();
        e.erosionSeed = erosionSeed;
        e.amp = MOUNTAIN_AMP;
        e.bias = 0f;
        e.clampStrength = 0f;
        return e;
    }

    // ============================================================ 复合条目

    /** {@code CombineCandidate.buildComposites}：每对消费 (selectorPerlin, warpPerlin) 两个 int。 */
    private void buildComposites(int[] cursor) {
        int k = MIXABLE;
        for (int i = 0; i < MIXABLE; i++) {
            for (int j = i + 1; j < MIXABLE; j++) {
                MorphNoise.Node selector = MorphNoise.Perlin.ofScale(cursor[0]++, SELECTOR_SCALE, 1);
                selector = MorphNoise.warpPerlin(selector, cursor[0]++, SELECTOR_SCALE / 2f, 2,
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
        java.util.Collections.shuffle(order, new Random(shuffleSeed));
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

    long seed() { return seed; }

    // ============================================================ 工具

    static int base(long worldSeed) { return (int) (worldSeed ^ (worldSeed >>> 32)); }

    /** 剥掉最后一层 Add(偏置) 与 Mul(振幅) 的结果。 */
    record Stripped(MorphNoise.Node body, float amp, float bias) { }

    /**
     * 只剥<b>外层各一层</b>：先 {@code Add(·, const)} 取偏置，再 {@code Mul(·, const)} 取振幅。
     *
     * <p>{@code .a()} 是常量时<b>直接报错</b> —— 那意味着链被改成 {@code Add(bias, body)}，
     * raw 身份不再是 {@code Mul.a()}，必须立刻失败而不是静默换一侧。</p>
     */
    static Stripped stripOuter(MorphNoise.Node node) {
        MorphNoise.Node cur = node;
        float bias = 0f;
        float amp = 1f;
        if (cur instanceof MorphNoise.Add add) {
            if (!(add.b() instanceof MorphNoise.Constant c)) {
                throw new IllegalStateException("outer Add is not Add(body, Constant): b=" + add.b());
            }
            bias = c.value();
            cur = add.a();
        }
        if (cur instanceof MorphNoise.Mul mul) {
            if (!(mul.b() instanceof MorphNoise.Constant c)) {
                if (mul.a() instanceof MorphNoise.Constant) {
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
     * 目录条目。身份字段 {@code final}；配方/外壳字段由构造期一次性赋值，
     * 构造完成后只读。
     */
    static final class Entry {

        private final int index;
        private final String id;
        private final Kind kind;
        private final float weight;
        private final int seedOffset;
        /** 非复合：信号节点；复合：null（由两个成员现算）。 */
        private MorphNoise.Node raw;
        private float amp;
        private float bias;
        /** 0 或 1：外壳之后是否施加 {@code max(·,0)}。 */
        private float clampStrength;
        private Entry left;
        private Entry right;
        private MorphNoise.Node selector;
        private MorphNoise.Node terrace;
        private MorphNoise.Node surface;
        /**
         * 低地原子的<b>原 {@code height} 节点</b>（未改动的那一棵）。
         *
         * <p>用途：证明 {@code raw} 是"从原树上剥一层"得到的同一个子节点。
         * 若有人把 raw 换成"成品高度 ÷ amp"之类的数值伪装，这条身份判据立刻失败。</p>
         */
        private MorphNoise.Node origin;
        private MorphNoise.Node warpX;
        private MorphNoise.Node warpZ;
        private MorphNoise.Node scaler;
        private MorphNoise.Node direction;
        private int erosionSeed;

        private Entry(int index, String id, Kind kind, float weight, int seedOffset) {
            this.index = index;
            this.id = id;
            this.kind = kind;
            this.weight = weight;
            this.seedOffset = seedOffset;
        }

        static Entry atom(int index, String id, Kind kind, float weight, MorphNoise.Node raw,
                          int seedOffset) {
            Entry e = new Entry(index, id, kind, weight, seedOffset);
            e.raw = raw;
            return e;
        }

        static Entry composite(int index, String id, float weight, Entry a, Entry b,
                               MorphNoise.Node selector) {
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

        boolean isComposite() { return left != null; }

        Entry left() { return left; }

        Entry right() { return right; }

        boolean clamped() { return clampStrength == 1f; }

        /** 信号层（复合条目按 alpha 现算）。 */
        double raw(double x, double z) {
            if (!isComposite()) {
                return MorphNoiseEval.eval(raw, x, z);
            }
            double a = alpha(x, z);
            double ampA = left.amplitude(x, z);
            double ampB = right.amplitude(x, z);
            double ampC = (1 - a) * ampA + a * ampB;
            double weighted = (1 - a) * ampA * left.raw(x, z) + a * ampB * right.raw(x, z);
            return weighted / ampC;
        }

        /** 线性外壳的振幅。 */
        double amplitude(double x, double z) {
            if (!isComposite()) {
                return amp;
            }
            double a = alpha(x, z);
            return (1 - a) * left.amplitude(x, z) + a * right.amplitude(x, z);
        }

        /** 线性外壳的偏置。 */
        double bias(double x, double z) {
            if (!isComposite()) {
                return bias;
            }
            double a = alpha(x, z);
            return (1 - a) * left.bias(x, z) + a * right.bias(x, z);
        }

        /** 复合条目的 alpha ∈ [0,1]（真实 selector × [0.375,0.625]）；非复合为 {@code NaN}。 */
        double alpha(double x, double z) {
            if (!isComposite()) {
                return Double.NaN;
            }
            return MorphNoiseEval.map(MorphNoiseEval.eval(selector, x, z), ALPHA_LO, ALPHA_HI);
        }

        /** 复合条目的选择器节点（{@code warpPerlin(perlin(600,1), 300, 2, 300)}）；非复合为 null。 */
        MorphNoise.Node selector() { return selector; }

        /** 台地：surface 节点（独立于 raw 的细节层）。 */
        MorphNoise.Node surfaceNode() { return surface; }

        /** 台地/reference 用的 Terrace 节点（值域 0..0.9）。 */
        MorphNoise.Node terraceNode() { return terrace; }

        /** 低地原子：未改动的原 {@code height} 节点（非原子返回 null）。 */
        MorphNoise.Node originNode() { return origin; }

        /**
         * 原始信号节点本身的引用（低地原子 = 剥壳后的 {@code Mul.a()}；台地 = {@code dbgBase}；
         * 劣地 = {@code dbgHills}；山地 = {@code Erosion.input}；<b>复合返回 null</b>）。
         */
        MorphNoise.Node rawNode() { return raw; }
    }
}
