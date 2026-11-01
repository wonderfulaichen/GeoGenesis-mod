package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * <b>海陆统一票槽池</b>（方案 {@code ocean-unified-terrain} 步 2）。
 *
 * <p>把陆地的 18 条目（{@link RecipeMorphCatalog}，45 槽）与海洋的 6 配方
 * （{@link OceanRecipeCatalog}，14 槽）合并成<b>一个</b>可查询的票槽池，
 * 让"这片区域用哪个配方"由同一次抽签决定，而不是"先算陆地、再套海洋剖面"。</p>
 *
 * <h3>★ 核心安全约束：陆地抽签游标必须零污染</h3>
 * <p>陆地池的抽签全程依赖一个<b>顺序游标</b>：</p>
 * <pre>
 *   int base = worldSeed ^ (worldSeed >>> 32);   // CombineCandidate.seed()
 *   int[] cursor = {base};
 *   IntSupplier next = () -> cursor[0]++;        // ← selector / shuffle / 各族 seed 全从这取
 * </pre>
 * <p>只要<b>任何一次</b>额外调用 {@code next.getAsInt()}，陆地的后续抽签就会整体位移
 * ⇒ 用户已认可的 7 臂 SHA 立刻作废。</p>
 *
 * <h3>所以本类的做法是「不共享游标」而非「共用游标再分区」</h3>
 * <ul>
 *   <li>陆地池：<b>原样</b>使用 {@link CombineCandidate}，不经过本类、不传任何游标进去；</li>
 *   <li>海洋池：用<b>独立种子</b>{@code worldSeed + OceanRecipeCatalog.SEED_OFFSET}，
 *       自己算自己的随机数，与陆地游标完全无关；</li>
 *   <li>本类只负责<b>合并两者的抽签结果</b>（各占各自的槽位区间），
 *       不参与任何一侧的内部抽签。</li>
 * </ul>
 *
 * <h3>槽位分区（关键设计）</h3>
 * <pre>
 *   全局槽位  0 .. 44   → 陆地（45 槽，身份由 CombineCandidate 自己抽签决定）
 *   全局槽位 45 .. 58   → 海洋（14 槽，身份由本类按权重展开）
 * </pre>
 * <p><b>为什么陆地占前 45 槽而不是"按某种比例混排"</b>：
 * 陆地槽位的<b>内容</b>（哪个槽是哪个配方）由 {@code CombineCandidate} 的 shuffle 决定，
 * 本类无法也不应该重算。把陆地放在前段，本类只需"透传前 45 个槽的查询"，</p>
 * <p>语义上一条：给定一个世界坐标，先问"这个点落在哪个槽"：
 * 落在 0..44 ⇒ 该点的配方由陆地池回答；落在 45..58 ⇒ 由海洋池回答。</p>
 *
 * <p>⚠ <b>本轮（步 2）不做海陆边界划分</b>：本类只提供"池"的结构与查询能力，
 * 以及一个<b>显式的、可参数化的</b>海陆判定钩子。真正的海陆分区（用构造场还是
 * c 场）留到步 3，且在步 3 之前本类默认<b>不启用海洋槽</b>，保证陆地产出逐位不变。</p>
 *
 * <p>只读：不改 {@link CombineCandidate}、不改 {@link RecipeMorphCatalog}、
 * 不引用 {@code OceanAssemblyCandidate}。</p>
 */
final class UnifiedTerrainCatalog {

    // ============================================================ 槽位布局

    /** 陆地槽数（= {@link RecipeMorphCatalog#SLOT_COUNT}，冻结口径）。 */
    static final int LAND_SLOTS = RecipeMorphCatalog.SLOT_COUNT;
    /** 海洋槽数（= {@link OceanRecipeCatalog#SLOT_COUNT}）。 */
    static final int OCEAN_SLOTS = OceanRecipeCatalog.SLOT_COUNT;
    /** 全局槽总数。 */
    static final int TOTAL_SLOTS = LAND_SLOTS + OCEAN_SLOTS;

    /** 海洋槽的起始全局下标。 */
    static final int OCEAN_SLOT_BASE = LAND_SLOTS;

    /** 槽归属。 */
    enum Side { LAND, OCEAN }

    /**
     * 一个全局槽的解析结果。
     *
     * @param globalSlot 全局槽位下标（0..TOTAL_SLOTS-1）
     * @param side       归属（LAND / OCEAN）
     * @param localId    LAND 时 = 陆地条目下标（0..17）；OCEAN 时 = 海洋配方 id（0..5）
     */
    record Slot(int globalSlot, Side side, int localId) { }

    // ============================================================ 状态

    private final long worldSeed;
    private final OceanRecipeCatalog ocean;
    /**
     * 海洋槽的展开表：下标 = {@code globalSlot - OCEAN_SLOT_BASE}，
     * 值 = {@link OceanRecipeCatalog.Recipe#id}。
     */
    private final int[] oceanSlotIds;
    /** 是否启用海洋槽。步 2 默认 off ⇒ 陆地产出逐位不变。 */
    private volatile boolean oceanEnabled = false;

    /**
     * @param worldSeed 世界种子（与陆地池<b>相同</b>的那个；内部会自行加
     *                  {@link OceanRecipeCatalog#SEED_OFFSET} 派生海洋池种子）
     */
    UnifiedTerrainCatalog(long worldSeed) {
        this.worldSeed = worldSeed;
        // ★ 海洋池用独立种子：与陆地游标 base 相差 900000，永不相交
        this.ocean = new OceanRecipeCatalog(worldSeed + OceanRecipeCatalog.SEED_OFFSET);
        this.oceanSlotIds = buildOceanSlots(worldSeed + OceanRecipeCatalog.SEED_OFFSET);
    }

    long worldSeed() { return worldSeed; }

    OceanRecipeCatalog ocean() { return ocean; }

    /** 启用海洋槽。默认关：不启用时 {@link #resolve} 永不返回 OCEAN。 */
    UnifiedTerrainCatalog enableOcean() {
        this.oceanEnabled = true;
        return this;
    }

    boolean oceanEnabled() { return oceanEnabled; }

    // ============================================================ 槽展开

    /**
     * 按权重把 6 个海洋配方展开成 {@value #OCEAN_SLOTS} 个槽，并<b>打散</b>。
     *
     * <h3>为什么要打散</h3>
     * <p>若按权重顺序排（平原 5 个、陆坡 3 个、…），相邻槽位全是同类，
     * 而槽位查询是"按空间连续抽样"⇒ 会出现大片同配方区域、边界是直线。
     * 打散后同一配方的槽位分散，区域边界才自然。</p>
     *
     * <h3>为什么用独立随机源</h3>
     * <p>用 {@code java.util.Random(oceanSeed)}，<b>不用</b>陆地那个 {@code IntSupplier}。
     * 这是本类能保证"陆地零污染"的根本原因。</p>
     */
    private static int[] buildOceanSlots(long oceanSeed) {
        OceanRecipeCatalog.Recipe[] all = OceanRecipeCatalog.Recipe.values();
        // 权重 → 槽数（最大余数法，保证总和恰为 OCEAN_SLOTS）
        float sumW = 0f;
        for (OceanRecipeCatalog.Recipe r : all) sumW += OceanRecipeCatalog.weight(r);
        int[] counts = new int[all.length];
        int assigned = 0;
        for (int i = 0; i < all.length; i++) {
            counts[i] = Math.max(1, Math.round(OceanRecipeCatalog.weight(all[i]) / sumW * OCEAN_SLOTS));
            assigned += counts[i];
        }
        // 修正误差：多退少补到权重最大的配方
        while (assigned > OCEAN_SLOTS) {
            int worst = 0;
            for (int i = 1; i < all.length; i++) {
                if (counts[i] > counts[worst]) worst = i;
            }
            if (counts[worst] <= 1) break;
            counts[worst]--;
            assigned--;
        }
        while (assigned < OCEAN_SLOTS) {
            int best = 0;
            for (int i = 1; i < all.length; i++) {
                if (OceanRecipeCatalog.weight(all[i]) > OceanRecipeCatalog.weight(all[best])) best = i;
            }
            counts[best]++;
            assigned++;
        }

        int[] ids = new int[OCEAN_SLOTS];
        int k = 0;
        for (int i = 0; i < all.length; i++) {
            for (int c = 0; c < counts[i]; c++) ids[k++] = all[i].id;
        }
        // Fisher-Yates，独立随机源
        java.util.Random rng = new java.util.Random(oceanSeed ^ 0x5DEECE66DL);
        for (int i = ids.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = ids[i];
            ids[i] = ids[j];
            ids[j] = t;
        }
        return ids;
    }

    /** 槽展开表副本（诊断用；打散后的顺序）。 */
    int[] oceanSlotIds() { return oceanSlotIds.clone(); }

    /** 每个海洋配方占几个槽（诊断用，应与 {@code EXPECTED} 一致）。 */
    int[] oceanSlotCounts() {
        int[] c = new int[OceanRecipeCatalog.ENTRY_COUNT];
        for (int id : oceanSlotIds) c[id]++;
        return c;
    }

    // ============================================================ 查询

    /**
     * 把"全局槽位下标"解析成具体配方。
     *
     * @param globalSlot 0..{@code TOTAL_SLOTS-1}
     * @param landSlotId 当落在陆地区间时，由调用方提供的<b>陆地条目下标</b>
     *                   （由 {@code CombineCandidate} 自己查；本类不重算，避免游标污染）
     * @return 解析结果；若海洋槽未启用而落在海洋区间，则返回 {@code null}
     */
    Slot resolve(int globalSlot, int landSlotId) {
        if (globalSlot < 0 || globalSlot >= TOTAL_SLOTS) {
            throw new IllegalArgumentException("globalSlot out of range: " + globalSlot);
        }
        if (globalSlot < LAND_SLOTS) {
            return new Slot(globalSlot, Side.LAND, landSlotId);
        }
        if (!oceanEnabled) return null;
        int idx = globalSlot - OCEAN_SLOT_BASE;
        return new Slot(globalSlot, Side.OCEAN, oceanSlotIds[idx]);
    }

    /** 便捷：该全局槽是否属于海洋区间。 */
    boolean isOceanSlot(int globalSlot) {
        return globalSlot >= OCEAN_SLOT_BASE && globalSlot < TOTAL_SLOTS;
    }

    /**
     * 给定世界坐标求全局槽位，<b>不</b>使用任何会推进陆地游标的操作。
     *
     * <h3>★ 为什么本方法只做"分配"不做"抽样"</h3>
     * <p>槽位→空间的映射（{@code RegionPartition}）属于陆地池的内部状态，
     * 由 {@code CombineCandidate} 在构造时用游标建好。本类若重建一份，
     * 既会推进游标、也可能与陆地实际使用的分区不一致。</p>
     * <p>所以本方法<b>接受调用方传入</b>的"归一化槽位坐标"（{@code u ∈ [0,1)}），
     * 由它算全局下标：</p>
     * <pre>
     *   globalSlot = floor(u * TOTAL_SLOTS)
     * </pre>
     * <p>陆地池自己的分区仍然只产生 0..44 的值 —— 两者通过
     * {@code u = landSlot / LAND_SLOTS} 建立联系。步 3 再接真正的空间分区。</p>
     */
    int globalSlotOf(double u) {
        double c = u < 0 ? 0 : (u >= 1 ? 0.999999 : u);
        return (int) Math.floor(c * TOTAL_SLOTS);
    }

    // ============================================================ 海陆分区

    /**
     * 海洋域的 continentality 阈值（{@code cBiased} 空间）。
     *
     * <p>取 {@code 0.0}：与生产 {@code ContinentField} 的默认 {@code continentThreshold=0.0}
     * 一致（{@code c ≥ 0} 为陆、{@code c < 0} 为海）。</p>
     */
    static final double OCEAN_CUT = 0.0;

    /**
     * 软边宽度（{@code cBiased} 空间）：在 {@code [−W, +W]} 内从"纯海"过渡到"纯陆"。
     *
     * <h3>★ 为什么必须有软边而不是硬切</h3>
     * <p>{@code AGENTS.md} 硬约束 ④：任何在阈值处硬切换的混合都会在等值线上产生阶跃
     * （本项目回归过三次）。所以海/陆的<b>混合权重</b>必须是连续的，</p>
     * <p>取 {@code 0.02}：与生产 {@code landFactor = smoothstep(−0.01, +0.01, eLand)}
     * 的带宽同量级，保证归入海洋的那批点本身已经在"接近海岸"的范围里。</p>
     */
    static final double OCEAN_SOFT_W = 0.02;

    /**
     * 海侧权重 {@code ∈ [0,1]}：{@code 1 = 纯海洋，0 = 纯陆地}。
     *
     * <h3>★ 为什么用 {@code c}（独立场）而不是 {@code e}（已算出的高度）</h3>
     * <p>这是本方案最容易重犯的错误。前一轮实测（{@code ocean-type-unification} §〇b.1）
     * 已证明：{@code classify()} 按 {@code eLand} 切类型，而 {@code eLand} 又由类型决定
     * ⇒ <b>循环论证</b>，测出来的"差异"全是分组变量自己的贡献。</p>
     * <p>所以分区判据必须是一个<b>不依赖地形高度</b>的场。{@code c}（大陆性 FBM）
     * 只由世界种子决定，与任何配方无关 ⇒ 用它分区，海洋配方才有独立意义。</p>
     *
     * <h3>★ 为什么把判据从 {@code c} 换成 {@code e < 0}</h3>
     * <p>首版直接用 {@code c < 0}。实测发现：陆地池的 {@code e} 在 {@code c ≈ 0} 附近
     * 可能已经是正值（{@code GROUND_E=0.02} 起步），而 {@code c} 略负处的陆地点
     * {@code e} 仍 &gt; 0 ⇒ <b>海洋配方会把"本来就是陆"的点画成海</b>，制造大量假岛。
     * 这与"海陆比由类型场与 c 场双场决定"的既有架构冲突。</p>
     * <p>改为用 <b>{@code e < 0}</b> 作门槛（即"这片在陆地池里本来就是海"），
     * 再用 {@code c} 的软边做过渡。两者结合：
     * <b>只有陆地池本来就画成海的区域，才允许海洋配方接管</b> ⇒ 海陆比结构性不变。</p>
     *
     * @param eLand 陆地池在 <b>未解锁海侧封锁</b> 下的 {@code e}（即 {@code max(...,0)} 的结果）
     * @param c     大陆性
     */
    double oceanWeight(double eLand, double c) {
        if (!oceanEnabled) return 0.0;
        // ① 陆地池已经判为陆（e>0）⇒ 完全不给海洋配方，杜绝"把陆画成海"
        if (eLand > 0.0) return 0.0;
        // ② 陆地池判为海（e==0，因为 max 截断）⇒ 由 c 决定过渡
        double t = (OCEAN_CUT + OCEAN_SOFT_W - c) / (2.0 * OCEAN_SOFT_W);
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3 - 2 * t);
    }

    /**
     * 合并海陆：{@code e = (1−w)·eLand + w·eOcean}。
     *
     * <p>两端都连续：{@code w=0} 处完全等于陆地产出、{@code w=1} 处完全等于海洋配方。
     * 这正是 {@code AGENTS.md} 要求的"平滑渐隐"，不会在 {@code c = OCEAN_CUT} 处留折痕。</p>
     *
     * @param eLandWeighted 陆地 e（<b>已含</b>其 {@code max(...,0)} 截断）
     * @param eOcean        海洋配方 e（负值，来自 {@link CombineCandidate#seaE}）
     * @param w             {@link #oceanWeight} 的输出
     */
    static double merge(double eLandWeighted, double eOcean, double w) {
        if (w <= 0.0) return eLandWeighted;
        if (w >= 1.0) return eOcean;
        return eLandWeighted * (1.0 - w) + eOcean * w;
    }

    // ============================================================ 诊断

    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("UnifiedTerrainCatalog seed=").append(worldSeed)
          .append(" oceanEnabled=").append(oceanEnabled).append('\n');
        sb.append(String.format(Locale.ROOT,
                "  land slots  : [0, %d)   count=%d  (frozen, see RecipeMorphCatalog)%n",
                LAND_SLOTS, LAND_SLOTS));
        sb.append(String.format(Locale.ROOT,
                "  ocean slots : [%d, %d)  count=%d  seedOffset=%d%n",
                OCEAN_SLOT_BASE, TOTAL_SLOTS, OCEAN_SLOTS, OceanRecipeCatalog.SEED_OFFSET));
        int[] counts = oceanSlotCounts();
        sb.append("  ocean slot distribution:\n");
        for (OceanRecipeCatalog.Recipe r : OceanRecipeCatalog.Recipe.values()) {
            sb.append(String.format(Locale.ROOT, "    [%d] %-18s slots=%d  weight=%.1f%n",
                    r.id, r, counts[r.id], OceanRecipeCatalog.weight(r)));
        }
        sb.append("  ocean slot order (shuffled): ");
        for (int id : oceanSlotIds) sb.append(id).append(' ');
        sb.append('\n');
        return sb.toString();
    }

    private UnifiedTerrainCatalog() { throw new AssertionError(); }
}
