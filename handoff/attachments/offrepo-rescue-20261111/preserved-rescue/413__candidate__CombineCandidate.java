package com.geogenesis.worldgen.terrain.candidate;

/**
 * <b>参考组装链的完整复刻</b>：{@code TerrainProvider.generateTerrain}（{@code TerrainProvider.java:28-83}）
 * 的 {@code combine} 两两复合 + {@code Heightmap.java:166} 的山形 {@code Blender} 覆盖层。
 *
 * <h3>参考到底怎么组装（源码逐步）</h3>
 * <pre>
 * // TerrainProvider.java:36-63
 * mixable   = [steppe, plains, dales, hills1, hills2, torridonian, plateau, badlands]  // weight>0 过滤
 * unmixable = [badlands, mountains1, mountains2, mountains3, volcano]
 *
 * // TerrainProvider.java:69-71 + 116-153
 * mixed  = combine(mixable)      // ← N + C(N,2) 条：8 + 28 = 36
 * result = mixed + unmixable
 * Collections.shuffle(result, new Random(terrainSeed.next()))   // ← 槽位顺序被打散
 *
 * // Heightmap.java:115-119 —— 山形遮罩（这才是"大山系"的来源）
 * mountainShape = worleyEdge(seed, 1000·hScale·2.25, DISTANCE_2_ADD, EUCLIDEAN)
 *               @ warpPerlin(333, 2, 250) @ curve(CURVE3) @ clamp(0,0.9) @ map(0,1)
 *
 * // Heightmap.java:123-125 + 166
 * terrainRegions = RegionSelector(result)
 * borders        = makeBorder(...)          // = makePlains（平原/草原）
 * terrainBlend   = RegionLerper(borders, terrainRegions)
 * land           = Blender(mountainShape, terrainBlend, mountains, 0.3, 0.8, 0.575)
 * </pre>
 *
 * <h3>三个"与想当然不同"的要点（复刻必须照做）</h3>
 * <ol>
 *   <li><b>山地不在区域选择器里。</b>它是被 {@code mountainShape} 这个 <b>2250 格波长</b>的
 *       worleyEdge 遮罩在 {@code 0.3–0.8} 区间<b>覆盖</b>上去的。所以山地是"成片的大山系"，
 *       而不是"某个 Voronoi 格子里的一片山"。</li>
 *   <li><b>{@code combine} 让类型数量爆炸。</b>8 个 mixable 两两组合出 28 个复合类型
 *       （{@code CompositeTerrain}，名字是 {@code a-b}，类别取更"高"的那个）。
 *       于是区域选择器要在 36 个槽位里选，而不是 8 个。</li>
 *   <li><b>{@code Collections.shuffle} 打散槽位。</b>所以"第几个槽位是哪类"没有语义，
 *       只有"总共几票"有意义。权重决定票数。</li>
 * </ol>
 *
 * <h3>本类相对参考的偏差（明确列出，不藏）</h3>
 * <ul>
 *   <li>用我们已定稿的 11 个族替参考的 8 个 mixable；火山、torridonian 无对应，跳过。</li>
 *   <li>参考的 {@code levels.ground}（地面基准）在这个沙箱里由 {@code HeightCurve} 承担。</li>
 *   <li>不实现 {@code VariedMountainPopulator} 的三档变化（默认预设 {@code mountainVariety=0}，
 *       本来就不走那条分支）。</li>
 * </ul>
 */
public final class CombineCandidate {

    /** {Heightmap.java:115}：{@code legacyMountainScaling=false} ⇒ 1000 × hScale × 2.25。 */
    private static final float MOUNTAIN_SHAPE_SCALE = 1000f * 1.0f * 2.25f;
    /** {Heightmap.java:166} 的参考原值，仅作对照保留。 */
    private static final float REF_MTN_BLEND_LOW = 0.3f;
    private static final float REF_MTN_BLEND_HIGH = 0.8f;

    /**
     * 本实现实际使用的山形遮罩阈值。
     *
     * <p>为什么不能用参考的 0.3/0.8：实测遮罩分布（{@code MaskDistributionProbe}，
     * 10020 块跨度、251001 点）是 P50=0.385、P75=0.531、P90=0.715。
     * 用 0.3 起混 ⇒ <b>71.8% 的地面</b>都在与山地混合，整幅图变成均匀皱褶；
     * 用 0.8 封顶 ⇒ 只有 5.1% 是纯山地。这与参考观感不符。</p>
     *
     * <p>取 0.55 / 0.78 ⇒ 约 23% 地面进入混合带、约 8% 纯山地。参考的山地面积
     * 在默认预设下也是这个量级（山地 weight 2.5 但作为覆盖层，实际占地远小于槽位比例）。</p>
     */
    private static final float MTN_BLEND_LOW = 0.55f;
    private static final float MTN_BLEND_HIGH = 0.78f;
    private static final float MTN_BLEND_SPLIT = 0.575f;
    /** {TerrainProvider.java:116-129} {@code combine} 的 selector 尺度 = regionSize/2。 */
    private static final float COMBINE_SELECTOR_SCALE = 600f;

    /** 一个族的求值器（输出<b>相对共享地面基准</b>的高度，0 = 基准面）。 */
    public interface Family {
        double height01(double x, double z);
    }

    /**
     * 共享地面基准 {@code levels.ground}（{@code Levels.java:22-33}）在本项目的等价量。
     *
     * <p>参考里所有 populator 的 {@code cell.height} 都是
     * {@code max(ground + heightNoise, 0)}（{@code TerrainPopulator.java:16-24}）——
     * {@code ground} 是<b>全局常量</b>，所以任何族、任何类型都从同一基准起算，
     * 混合时不可能出现基准错位。</p>
     *
     * <p>本项目的 {@code HeightCurve} 以 e=0 为海平面、正向更陆。要让"基准面 = 基准面"，
     * 共享地面必须取 e 空间的一个常量，然后所有族的配方输出都当作<b>相对</b>它的增量。
     *
     * <h3>★ 2026-10-30：取值几经反复，最终回到 0.02</h3>
     * <p><b>经过</b>：用户圈出"平原深处一大片没有起伏的浅色区"⇒ 曾把它调到
     * {@code 0.2461}（参考 {@code ground} 真值），实测陆地 P50 从 93 格抬到 137 格
     * （{@code +44}），<b>整体浮起</b>，被用户当场指出"平原应该在接近海平面"⇒ 回退。</p>
     *
     * <h3>为什么 0.02 其实是对的</h3>
     * <p>真正的问题<b>不在基准厚度</b>，而在 {@code max(0)} 的位置（见 {@link #height01}
     * 的说明）。各族输出的最小值只有 <b>{@code -0.02}</b>（steppe，与参考一致），
     * 所以 {@code GROUND_E=0.02} <b>刚好</b>能把负值抬到 0，{@code max} 不触发。</p>
     * <p>此前触发是因为先对 {@code height} 做了 {@code max}、再加基准 —— 顺序错了。
     * 修正顺序后，0.02 足够。</p>
     *
     * <h3>为什么 0.03 也不行（2026-10-30 实测）</h3>
     * <p>曾以为 {@code GROUND_E >= 0.02×1.50 = 0.03} 就能让 {@code max(0)} 永不触发，实测否决：</p>
     * <pre>
     *   方案               Y=67.73平台   Y=70.09平台   假平台最大单值   与原版相同
     *   原版 (G=0.02)        2946 px        84 px     2633 px (11.1%)     —
     *   方案A (G=0.02+修正)   796 px        84 px      601 px (2.7%)    99.85%
     *   方案B (G=0.03)         82 px       750 px      591 px (2.7%)    35.10%
     * </pre>
     * <p><b>平台只是从 67.73 搬到 70.09，总量没变</b>（796→832），还改动了 65% 的像素。
     * 根因是触发 {@code max(0)} 的是 {@code regionHeight} 本身接近 0，与 {@code GROUND_E} 无关。</p>
     *
     * <p>剩余约 600 px 的极平区经 {@code SelectedFamilyProbe} 确认 <b>100% 是 steppe 族</b>：
     * 全局 {@code P10=-0.001, P50=+0.011, P90=+0.023}，跨度仅 0.024（≈8.5 格）——
     * 这是参考 {@code makeSteppe} 的设定（{@code height*0.08-0.02}），
     * <b>"草原本来就平"，不是缺陷</b>。</p>
     */
    public static final double GROUND_E = 0.02;

    /**
     * 从基准面到 e 上界的跨度。
     *
     * <p><b>2026-10-30 回退到 1.50</b>：曾随 {@code GROUND_E} 提到 0.2461 而缩到 1.14
     * （为了压住顶点）。基准回到 0.02 后，跨度也回到 1.50 —— 这是用户认可那版的口径，
     * 实测地形层次最丰富（山地占 0.02→0.93 e，平原占 0.02→0.16 e，对比 5.4 倍）。</p>
     *
     * <p>各族的原始输出并<b>不</b>铺满 0..1 —— 实测最大是山地 {@code max=0.617}，
     * 平原只有 {@code max=0.106}。若取 {@code E_SPAN=0.96}（假设铺满 0..1），
     * 整幅地形只用到垂直范围的 4%–24%，读作"一片均匀的中等皱褶"。</p>
     */
    public static final double E_SPAN = 1.50;

    /** 复合类型的成员（用于类型图着色）。 */
    public enum Category { PLAIN, HILLS, MOUNTAINS, PLATEAU, BADLANDS, BASIN }

    private final Family[] mixable;
    private final Category[] mixableCat;
    private final float[] mixableWeight;
    private final Family[] unmixable;
    private final Category[] unmixableCat;
    private final float[] unmixableWeight;
    private final Family border;

    /** 展开后的槽位（mixed + unmixable，已 shuffle）。 */
    private Family[] slots;
    private Category[] slotCats;
    /** 复合条目：{@code blend(selector, a, b, 0.5, 0.25)}。 */
    private Family[] composites;
    private Category[] compositeCats;
    private float[] compositeWeights;
    private RegionPartition partition;
    private Blender blender;

    /**
     * 是否解除 {@link #e} 的海侧封锁（默认 {@code false} = 与旧实现逐字节相同）。
     *
     * <p>只有统一地形实验臂（海洋配方参与票槽池）才应打开；见
     * {@link #enableSeaSideUnlock()}。打开后本实例产出会改变，
     * <b>不得再用于基线对拍</b>。</p>
     */
    private boolean seaUnlocked = false;

    /** 构造剖面来源（null = 不启用构造偏置）。 */
    private com.geogenesis.worldgen.terrain.TectonicField tectonic;
    /** 山地族（构造偏置用；{@link Blender} 已持有同一个）。 */
    private Family mountainFamily;
    /** 盆地族（构造偏置用；与槽位表里那一份同源，但独立求值避免被权重稀释）。 */
    private Family basinFamily;

    private static final double TECTONIC_SIGMA = 80.0;
    /** orogen × gain 作为"切向山地"的插值权重上限。1.0 = 完全变山地。 */
    private static final double TECTONIC_OROGEN_GAIN = 1.6;
    /** rift × gain 作为"切向盆地"的插值权重上限。 */
    private static final double TECTONIC_RIFT_GAIN = 1.2;

    /** 启用构造偏置（可选；不调用则不改变任何产出）。 */
    public void enableTectonicBias(com.geogenesis.worldgen.terrain.TectonicField field,
                                   Family mountain, Family basin) {
        this.tectonic = field;
        this.mountainFamily = mountain;
        this.basinFamily = basin;
    }

    public CombineCandidate(long worldSeed,
                            Family[] mixable, Category[] mixableCat, float[] mixableWeight,
                            Family[] unmixable, Category[] unmixableCat, float[] unmixableWeight,
                            Family border) {
        this.mixable = mixable.clone();
        this.mixableCat = mixableCat.clone();
        this.mixableWeight = mixableWeight.clone();
        this.unmixable = unmixable.clone();
        this.unmixableCat = unmixableCat.clone();
        this.unmixableWeight = unmixableWeight.clone();
        this.border = border;
        this.borderModeSeed = worldSeed;
        seed(worldSeed);
    }

    /**
     * 带放置模式的构造器：{@code idMode=COHERENT} 时格子 id 由低频噪声给出
     * （相邻格子高概率同类型 ⇒ 类型连带成带）。
     */
    public CombineCandidate(long worldSeed,
                            Family[] mixable, Category[] mixableCat, float[] mixableWeight,
                            Family[] unmixable, Category[] unmixableCat, float[] unmixableWeight,
                            Family border,
                            RegionPartition.IdMode idMode, float idScale,
                            float idAnisoX, float idAnisoZ) {
        this.mixable = mixable.clone();
        this.mixableCat = mixableCat.clone();
        this.mixableWeight = mixableWeight.clone();
        this.unmixable = unmixable.clone();
        this.unmixableCat = unmixableCat.clone();
        this.unmixableWeight = unmixableWeight.clone();
        this.border = border;
        this.borderModeSeed = worldSeed;
        this.idMode = idMode;
        this.idScale = idScale;
        this.idAnisoX = idAnisoX;
        this.idAnisoZ = idAnisoZ;
        seed(worldSeed, idMode, idScale, idAnisoX, idAnisoZ);
    }

    private void seed(long worldSeed) {
        seed(worldSeed, RegionPartition.IdMode.WHITE, 5200f, 0.34f, 1f);
    }

    private void seed(long worldSeed, RegionPartition.IdMode mode,
                      float scale, float anisoX, float anisoZ) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;

        buildComposites(next);

        // result = mixed + unmixable
        int raw = composites.length + unmixable.length;
        Family[] entries = new Family[raw];
        Category[] cats = new Category[raw];
        float[] weights = new float[raw];
        for (int i = 0; i < composites.length; i++) {
            entries[i] = composites[i];
            cats[i] = compositeCats[i];
            weights[i] = compositeWeights[i];
        }
        for (int i = 0; i < unmixable.length; i++) {
            entries[composites.length + i] = unmixable[i];
            cats[composites.length + i] = unmixableCat[i];
            weights[composites.length + i] = unmixableWeight[i];
        }

        // ★ RegionSelector.getWeightedArray：权重 → 槽位展开（此前漏掉的一步）
        expandByWeight(entries, cats, weights);

        // 参考随后 Collections.shuffle(result, new Random(terrainSeed.next()))
        int total = slots.length;
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) order.add(i);
        java.util.Collections.shuffle(order, new java.util.Random(next.getAsInt()));
        Family[] shuffled = new Family[total];
        Category[] shuffledCats = new Category[total];
        for (int i = 0; i < total; i++) {
            shuffled[i] = slots[order.get(i)];
            shuffledCats[i] = slotCats[order.get(i)];
        }
        slots = shuffled;
        slotCats = shuffledCats;

        RefNoise.Node wx = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        RefNoise.Node wz = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        partition = buildPartition(next, wx, wz, worldSeed, mode, scale, anisoX, anisoZ);

        blender = new Blender(worldSeed, next);
    }

    /**
     * 区域格子 id 的取法。
     *
     * <p>{@link RegionPartition.IdMode#WHITE} = 参考原样（每格独立哈希）；
     * {@link RegionPartition.IdMode#COHERENT} = 格心采样低频噪声（相邻格连带成带）。</p>
     */
    private RegionPartition.IdMode idMode = RegionPartition.IdMode.WHITE;
    /** COHERENT 下的噪声相关长度（wu）；越大 ⇒ 连带范围越广、带越粗。 */
    private float idScale = 5200f;
    /** COHERENT 下的各向异性：x 取样变慢 ⇒ 带沿 x 拉长。1/1 = 各向同性。 */
    private float idAnisoX = 0.34f, idAnisoZ = 1f;

    /**
     * 启用连带放置（相邻格子高概率同类型）。<b>必须在 {@code seed()} 之前调用</b> ——
     * 区域划分在 seed 时一次性构建，事后再改字段不会重建（首版就踩了这个坑）。
     */
    public CombineCandidate coherentPlacement(float scale, float anisoX, float anisoZ) {
        this.idMode = RegionPartition.IdMode.COHERENT;
        this.idScale = scale;
        this.idAnisoX = anisoX;
        this.idAnisoZ = anisoZ;
        return this;
    }

    private RegionPartition buildPartition(java.util.function.IntSupplier next,
                                           RefNoise.Node wx, RefNoise.Node wz, long worldSeed,
                                           RegionPartition.IdMode mode, float scale,
                                           float anisoX, float anisoZ) {
        if (mode == RegionPartition.IdMode.WHITE) {
            return new RegionPartition(next.getAsInt(), wx, wz);
        }
        // 低频噪声：在格心世界坐标上采样（格距 1200wu），相关长度 scale。
        //
        // ★ 必须把噪声的实际值域拉满：RefNoise.Perlin 声明 0..1，但单次求值只用中间一段
        //   （实测 P50≈0.385、P5–P95≈0.15–0.80）。不拉伸时 round(id×N) 会挤在少数几个
        //   槽位上 ⇒ 整幅图退化成单一类型（首版实测 95% 是丘陵）。
        //   与盆地盐滩同一处理：Clamp 定窗口 + Map 拉伸。
        // ★ 必须把 scale 原样传给 ofScale：它内部已经做了 1/scale。
        //   首版写成 ofScale(seed, 1f/scale, 3) ⇒ frequency = 1800/wu ⇒ 相邻格心完全随机采样。
        RefNoise.Node raw = RefNoise.Perlin.ofScale((int) (worldSeed ^ (worldSeed >>> 32)) + 90210,
                scale, 3);
        // ★ 噪声是钟形分布（P50≈0.385），不展宽的话 round(id×N) 会挤在中间几个槽位
        //   ⇒ 整幅图退化成少数类型。用 0.30–0.70 的窗口拉开到满值域。
        RefNoise.Node idNoise = new RefNoise.Mapped(
                new RefNoise.Clamped(raw, 0.30f, 0.70f), 0.30f, 0.70f, 0f, 1f);
        return new RegionPartition(next.getAsInt(), wx, wz, mode, idNoise, anisoX, anisoZ);
    }

    /**
     * {@code RegionSelector.getWeightedArray}（{@code RegionSelector.java:30-64}）逐行复刻。
     *
     * <pre>
     * smallest = min(weight)                      // 权重为 0 的条目直接剔除
     * count_i  = round(weight_i / smallest)
     * 槽位列表 = 每个条目重复 count_i 次
     * </pre>
     *
     * <p>这条规则让"权重"变成<b>面积</b>：权重 2.5 的山地占 2.5 倍槽位。
     * 上一版每个复合类型只给 1 个槽，等于把所有类型强行等分 ——
     * 后果是低地复合类型压倒性地多，大尺度上整幅图变成均匀皱褶、没有地貌区。</p>
     */
    private void expandByWeight(Family[] entries, Category[] cats, float[] weights) {
        float smallest = Float.MAX_VALUE;
        for (float w : weights) {
            if (w <= 0f) continue;
            smallest = Math.min(smallest, w);
        }
        if (smallest == Float.MAX_VALUE) smallest = 1f;

        int total = 0;
        int[] counts = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            counts[i] = weights[i] <= 0f ? 0 : Math.round(weights[i] / smallest);
            total += counts[i];
        }
        if (total == 0) {
            // 参考的兜底：全部权重为 0 时退回原始列表
            slots = entries.clone();
            slotCats = cats.clone();
            return;
        }
        slots = new Family[total];
        slotCats = new Category[total];
        int k = 0;
        for (int i = 0; i < entries.length; i++) {
            for (int c = 0; c < counts[i]; c++) {
                slots[k] = entries[i];
                slotCats[k] = cats[i];
                k++;
            }
        }
    }

    /** {@code combine(input, operator)}：N 个原始 + C(N,2) 个两两复合。 */
    private void buildComposites(java.util.function.IntSupplier next) {
        int n = mixable.length;
        int pairs = n * (n - 1) / 2;
        composites = new Family[n + pairs];
        compositeCats = new Category[n + pairs];
        compositeWeights = new float[n + pairs];
        int k = n;
        for (int i = 0; i < n; i++) {
            composites[i] = mixable[i];
            compositeCats[i] = mixableCat[i];
            compositeWeights[i] = mixableWeight[i];
            for (int j = i + 1; j < n; j++) {
                Family a = mixable[i], b = mixable[j];
                // {TerrainProvider.java:118-122}
                RefNoise.Node selector = RefNoise.Perlin.ofScale(next.getAsInt(), COMBINE_SELECTOR_SCALE, 1);
                selector = RefNoise.warpPerlin(selector, next.getAsInt(), COMBINE_SELECTOR_SCALE / 2f, 2,
                        COMBINE_SELECTOR_SCALE / 2f);
                RefNoise.Node sel = selector;
                composites[k] = (x, z) -> {
                    // Noises.blend(selector, a, b, mid=0.5, range=0.25) 后 max(0)
                    float s = sel.compute((float) x, (float) z);
                    float al = RefNoise.mapClamped(s, 0.375f, 0.625f);   // [0.5±0.125] 归一
                    double v = a.height01(x, z);
                    double w = b.height01(x, z);
                    return Math.max(v + (w - v) * al, 0.0);
                };
                compositeCats[k] = dominant(mixableCat[i], mixableCat[j]);
                // {TerrainProvider.java:127}：weight = (w1 + w2) / 2
                compositeWeights[k] = (mixableWeight[i] + mixableWeight[j]) / 2f;
                k++;
            }
        }
    }

    /** {@code TerrainCategory.getDominant}：ordinal 更大者胜（我们的类别按高度排）。 */
    private static Category dominant(Category a, Category b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** 区域层：{@code RegionLerper(borders, RegionSelector(slots))}。 */
    private double regionHeight(double x, double z) {
        RegionPartition.Sample s = partition.sample((float) x, (float) z);
        int idx = RegionPartition.selectIndex(s.regionId(), slots.length);
        double selected = slots[idx].height01(x, z);
        double edge = s.edge();
        if (edge >= 1.0) return selected;
        double b = borderAt(x, z);
        return b + (selected - b) * edge;
    }

    /**
     * 边界地形：按<b>连续位置场</b>在"平原 border"与"山地 border"之间插值。
     *
     * <h3>为什么模式不能按 Voronoi 单元逐格查表</h3>
     * <p>{@code RegionLerper} 之所以无缝，是因为<b>两侧在 {@code edge=0} 时收敛到同一个
     * border</b>。若 border 按"当前所属单元"取值，跨单元分界线时最近单元切换 ⇒ 模式切换
     * ⇒ 两侧 border 不同 ⇒ {@code edge=0} 处直接跳变 ⇒ 沿 Voronoi 边界印出直线疤痕
     * （本项目历史上"平行带/直线网"的同型问题）。</p>
     *
     * <p>改用<b>位置函数</b> {@code m(x,z)} 后，分界线两侧读到同一个 {@code m}
     * ⇒ 处处连续。而 {@code m} 的相关长度远大于格距（1200wu），所以
     * <b>成片的单元共享同一模式</b> ⇒ 视觉上就是"一部分 Voronoi 单元边界是山地"。</p>
     *
     * @return 边界地形高度（0..1 归一化）
     */
    private double borderAt(double x, double z) {
        double plain = border.height01(x, z);
        if (mountainBorder == null) return plain;
        double m = mountainMode(x, z);
        if (m <= 1e-4) return plain;
        double mtn = mountainBorder.height01(x, z);
        return plain + (mtn - plain) * m;
    }

    /**
     * 边界模式权重 ∈ [0,1]：0 = 边界取平原（参考原样），1 = 边界取山地（worldgen 语义）。
     *
     * <p>用 smoothstep 塑形使大部分区域落在两端的饱和区（明确的"平原模式"或"山地模式"），
     * 只有中间一段是渐变 —— 既得到成片的带状山系，又不会在过渡处产生阶跃。</p>
     */
    private double mountainMode(double x, double z) {
        if (modeNoise == null) return 0.0;
        float v = modeNoise.compute((float) x, (float) z);   // 已展宽到 [0,1]
        float t = (v - MODE_LOW) / (MODE_HIGH - MODE_LOW);
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return t * t * (3f - 2f * t);
    }

    /** smoothstep 的两个端点（作用在已展宽的 [0,1] 模式噪声上）。 */
    private static final float MODE_LOW = 0.52f;
    private static final float MODE_HIGH = 0.78f;

    /** 启用"按区域概率选择边界模式"。{@code scale} 为模式场的相关长度（wu）。 */
    public CombineCandidate regionalBorder(Family mountainBorderRecipe, float scale) {
        this.mountainBorder = mountainBorderRecipe;
        int s = (int) (borderModeSeed ^ (borderModeSeed >>> 32)) + 60613;
        RefNoise.Node raw = RefNoise.Perlin.ofScale(s, scale, 3);
        this.modeNoise = new RefNoise.Mapped(
                new RefNoise.Clamped(raw, 0.30f, 0.70f), 0.30f, 0.70f, 0f, 1f);
        return this;
    }

    /**
     * <b>边界反转</b>：把 {@code RegionLerper} 的"边界地形"从平原换成山地。
     *
     * <p>参考的 {@code RegionLerper.java:16-41} 是 {@code lerp(border, selected, edge)}，
     * 而 {@code border = makeBorder()} = <b>平原</b>（{@code Populators.java:419-421}）。
     * 即 {@code edge=0}（恰在 Voronoi 边界上）取平原 = <b>最低</b> ⇒ 每个类型单元被
     * 一圈低地包住 ⇒ 视觉上必然是"一个个圆斑/多边形块"。</p>
     *
     * <p>worldgen-master 的做法正好相反（{@code elevation.rs:279}）：
     * {@code offset = peak * gaussian(dist, mw)}，{@code dist=0}（边界上）<b>最高</b>
     * ⇒ 山脊沿边界延伸 ⇒ 视觉上是<b>带</b>。</p>
     *
     * <p>本开关把边界地形换成山地，即采用 worldgen 的语义。<b>不改 Voronoi</b>
     * —— 参考和我们的 Voronoi 都是各向同性圆形格，band 从来不是 Voronoi 给的。</p>
     */
    private boolean invertBorder = false;
    private Family borderRecipe;

    /** "按区域概率选择边界模式"用：山地 border 与模式场。 */
    private Family mountainBorder;
    private RefNoise.Node modeNoise;
    private long borderModeSeed;

    /** 启用边界反转（边界处 = 山地，单元内部 = 选中类型）。 */
    public CombineCandidate invertBorder(Family mountainRidge) {
        this.invertBorder = true;
        this.borderRecipe = mountainRidge;
        return this;
    }

    // ==================== 构造偏置（σ=80 剖面） ====================

    /**
     * 构造剖面 → 类型偏置。σ 取 80（探针实测：伸长比 3.01、分量 7、长尾比 5.8 最干净）。
     *
     * <p><b>为什么用"按强度插值高度"而不是"切换类型"</b>：类型切换是离散的，
     * 在 {@code orogen} 跨越阈值的等值线上会产生阶跃 —— 正是本项目反复踩的坑
     * （{@code btype} 硬分支、{@code rate} 分片常数都是同类问题）。
     * 改为 {@code lerp(区域高度, 构造类型高度, w)}，{@code w} 是连续的构造强度，
     * 效果等价于"构造强的地方类型偏向山地"，但处处连续。</p>
     *
     * <p>{@code orogen}/{@code rift} 只用连续量 {@code stress} + {@code dist}，
     * <b>不用</b> {@code btype}/{@code rate}（项目已明令禁止）。</p>
     */
    private double tectonicBias(double x, double z) {
        if (tectonic == null) return regionHeight(x, z);
        com.geogenesis.worldgen.terrain.TectonicField.Sample s = tectonic.sample(x, z);
        double d = s.dist();
        double g = Math.exp(-(d * d) / (2.0 * TECTONIC_SIGMA * TECTONIC_SIGMA));
        if (g <= 1e-6) return regionHeight(x, z);

        double orogen = com.geogenesis.worldgen.terrain.TectonicField.smoothPos(
                s.stress(), com.geogenesis.worldgen.terrain.TectonicField.STRESS_POS_EPS) * g;
        double rift = com.geogenesis.worldgen.terrain.TectonicField.smoothPos(
                -s.stress(), com.geogenesis.worldgen.terrain.TectonicField.STRESS_POS_EPS) * g;
        double base = regionHeight(x, z);

        double h = base;
        if (orogen > 0) {
            double mtn = mountainFamily.height01(x, z);
            h = h + (mtn - h) * clamp01(orogen * TECTONIC_OROGEN_GAIN);
        }
        if (rift > 0) {
            double bsn = basinFamily.height01(x, z);
            h = h + (bsn - h) * clamp01(rift * TECTONIC_RIFT_GAIN);
        }
        return h;
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /**
     * 最终陆地高度的等价量（<b>未</b>加 {@code GROUND_E}，返回 e 空间增量）。
     *
     * <h3>★ 2026-10-30 修正：{@code max(0)} 的位置错了</h3>
     * <p>参考 {@code TerrainPopulator.apply}（{@code Populators.java:21}）是：</p>
     * <pre>
     *   cell.height = Math.max(base + height, 0.0F);   // ← max 包住 base+height
     * </pre>
     * <p>而本类此前写成：</p>
     * <pre>
     *   height01 = Math.max(blender.apply(x,z), 0)     // ← max 只包 height
     *   e        = GROUND_E + height01 * E_SPAN        // ← 基准在削平【之后】才加
     * </pre>
     * <p><b>后果</b>：{@code steppe} 族输出 {@code [-0.02, +0.06]}（与参考一致），
     * 负值部分被 {@code max(0)} 先削平，之后再加 {@code GROUND_E} 已经救不回来
     * ⇒ 图上出现大片 {@code Y=GROUND_E 对应值}的平顶。
     * 调 {@code GROUND_E} 只会让平台<b>换位置</b>（实测 67.73 → 121.19），平台本身不消失。</p>
     *
     * <p>现在改为返回<b>未截断</b>的原始增量，由 {@link #e} 在加完基准后再截断，
     * 与参考同构。负值深度最多 {@code -0.02}（steppe），
     * 相对 {@code GROUND_E=0.2461} 有 12 倍余量 ⇒ {@code max} 实际不触发。</p>
     */
    public double height01(double x, double z) {
        return blender.apply(x, z);
    }

    /**
     * 换算到 e：{@code max(GROUND_E + h·E_SPAN, 0)}。
     *
     * <p>与参考 {@code Math.max(base + height, 0)} <b>同构</b>：基准与增量<b>先相加再截断</b>，
     * 而不是先截断增量。这是 2026-10-30 修正的核心 —— 见 {@link #height01} 的说明。</p>
     *
     * <h3>★ 2026-11-01：海侧封锁可按需解除（步 3）</h3>
     * <p>默认行为<b>完全不变</b>（{@code seaUnlocked == false} ⇒ 仍是原来的
     * {@code Math.max(..., 0.0)}）。只有显式调用 {@link #enableSeaSideUnlock()}
     * 之后，负值才会被放行 —— 用于"海洋配方参与同一票槽池"的统一地形实验。</p>
     *
     * <h3>为什么"按槽位区分"而不是全局删 {@code max}</h3>
     * <p>这个 {@code max} 在陆地侧不是有害的：它是参考 {@code Populators.apply} 的
     * {@code Math.max(base + height, 0)} 的等价物，用来兜住 {@code steppe} 族的负值
     * （实测下限 {@code -0.02}）。<b>全局删除会让陆地出现 {@code e<0} 的坑，
     * 直接改变用户已认可的 7 臂基准。</b></p>
     * <p>所以只在本类这一处提供开关，且<b>开关关闭时逐字节等于旧实现</b>；
     * 其余各处（{@code blender} 内部的 {@code max}、各 Gallery 的
     * {@code Math.max(GROUND_E + ...)}）<b>本轮一律不动</b>。</p>
     *
     * <h3>为什么开关放在 {@code e()} 而不是 {@link #height01}</h3>
     * <p>{@code height01} 是"族输出"，其截断在 {@code Blender}/{@code Family} 内部，
     * 改它会影响 {@code debugSelected} / {@code category} 等一整片诊断；
     * {@code e()} 是"换算出口"，只影响最终高度，改动面最小、最容易守门。</p>
     */
    public double e(double x, double z) {
        double v = GROUND_E + height01(x, z) * E_SPAN;
        return seaUnlocked ? v : Math.max(v, 0.0);
    }

    /** 是否已解除海侧封锁（默认 false；见 {@link #enableSeaSideUnlock()}）。 */
    public boolean seaUnlocked() {
        return seaUnlocked;
    }

    /**
     * 解除海侧封锁：允许 {@link #e} 返回负值。
     *
     * <p><b>⚠ 调用后本实例的产出会改变</b>，不得再用于"基线对拍"。
     * 只有统一地形实验臂（海洋配方参与票槽池）才应该打开它。</p>
     *
     * @return this（便于链式调用）
     */
    public CombineCandidate enableSeaSideUnlock() {
        this.seaUnlocked = true;
        return this;
    }

    /**
     * 解除封锁时的"海侧换算"：把海洋配方的 {@code height01 ∈ [-1,0]} 映射到 e。
     *
     * <p>与 {@link #e} 的区别：{@code e} 用的是<b>陆地</b>口径
     * （{@code GROUND_E + h·E_SPAN}，{@code E_SPAN=1.50}），
     * 而海洋配方需要的是 {@code e = h}（0=海平面，-1=世界底），
     * 因为 {@code HeightCurve} 的海侧映射本来就是
     * {@code e ∈ [-1,0] → [minY, seaLevel]}。</p>
     *
     * <p>若对海洋配方误用 {@code e()}，同样 1 单位 h 会变成 1.50 e
     * ⇒ 海深放大 1.5 倍（叠加 236 vs 127 的量纲差后更严重）。</p>
     */
    public static double seaE(double height01) {
        return height01 < -1.0 ? -1.0 : (height01 > 0.0 ? 0.0 : height01);
    }

    /** 未加共享基准的直译口径（{@code h × 1.08}），仅用于与上一版对照。 */
    public double absoluteE(double x, double z) {
        return height01(x, z) * 1.08;
    }

    /** 主导类别（类型图着色用）。 */
    public Category category(double x, double z) {
        return blender.category(x, z);
    }

    // ==================== 诊断访问器（供 FlatSpotCauseProbe 归因）====================

    /** 区域层原始高度（{@code RegionLerper} 输出，<b>未经</b> {@code max(...,0)} 截断）。 */
    public double debugRegionHeight(double x, double z) {
        return regionHeight(x, z);
    }

    /** {@code Blender.apply} 原始输出（− 山形遮罩混合，同样未截断）。 */
    public double debugBlenderApply(double x, double z) {
        return blender.apply(x, z);
    }

    /** 山形遮罩值 [0,1]。 */
    public float debugMountainMask(double x, double z) {
        return blender.mask(x, z);
    }

    /** 区域边缘程度 [0,1]（1 = 纯族区）。 */
    public float debugEdge(double x, double z) {
        return partition.sample((float) x, (float) z).edge();
    }

    /** 边界地形高度（{@code borderAt}）。 */
    public double debugBorderAt(double x, double z) {
        return borderAt(x, z);
    }

    /** 选中族的原始高度（未经 {@code edge} 混合）。 */
    public double debugSelected(double x, double z) {
        RegionPartition.Sample s = partition.sample((float) x, (float) z);
        int idx = RegionPartition.selectIndex(s.regionId(), slots.length);
        return slots[idx].height01(x, z);
    }

    /**
     * 类型图的构造带宽判据。
     *
     * <p>{@link Blender#category} 只看山形遮罩与槽位投票，<b>不知道 border 模式</b>——
     * 于是"按区域选择边界模式"改了高度却改了类型图（上一版实测：地形出带、类型图不变）。
     * 本方法补上这条判据：<b>border 模式接近山地 + 靠近 Voronoi 边界</b>时判为山地带。</p>
     *
     * <p>判据里用 {@code 1 − edge}（edge=1 格心、0 边界），与 height 的 border 权重同源，
     * 所以类型图的位置与地形上的山脊位置一致。</p>
     */
    public Category categoryWithBorders(double x, double z) {
        Category base = blender.category(x, z);
        if (mountainBorder == null) return base;
        // 山形遮罩已经判为山地 → 保持
        if (base == Category.MOUNTAINS) return base;
        double m = mountainMode(x, z);
        if (m < BORDER_CAT_MIN_MODE) return base;
        RegionPartition.Sample s = partition.sample((float) x, (float) z);
        // 靠近边界（edge 小）且模式偏山地 ⇒ 判为构造带
        double nearEdge = 1.0 - clamp01(s.edge() / BORDER_CAT_EDGE_SPAN);
        return (m * nearEdge) >= BORDER_CAT_THRESHOLD ? Category.MOUNTAINS : base;
    }

    /** 类型图判据：模式权重下限、边界影响跨度、综合阈值。 */
    private static final double BORDER_CAT_MIN_MODE = 0.35;
    private static final double BORDER_CAT_EDGE_SPAN = 0.55;
    private static final double BORDER_CAT_THRESHOLD = 0.22;

    /** 诊断：山形遮罩值（0 = 纯区域，1 = 纯山地）。 */
    public float mountainMask(double x, double z) {
        return blender.mask(x, z);
    }

    /**
     * {Heightmap.java:115-119 + 166}：山形遮罩 + Blender 覆盖。
     *
     * <pre>
     * mask = worleyEdge(2250, DISTANCE_2_ADD, EUCLIDEAN)
     *      @ warpPerlin(seed, 333, 2, 250)
     *      @ curve(CURVE3) @ clamp(0, 0.9) @ map(0, 1)
     * </pre>
     */
    private final class Blender {
        private final RefNoise.Node mask;
        private final Family mountain;

        Blender(long worldSeed, java.util.function.IntSupplier next) {
            int base = (int) (worldSeed ^ (worldSeed >>> 32));
            RefNoise.Node mc = new RefNoise.WorleyEdgeAdd(base + 4241, 1f / MOUNTAIN_SHAPE_SCALE);
            mc = RefNoise.warpPerlin(mc, next.getAsInt(), 333f, 2, 250f);
            // Noises.curve(CURVE3) = 对已归一化到 0..1 的值套 hermite
            mc = new RefNoise.Curve3(mc);
            mc = new RefNoise.Clamped(mc, 0f, 0.9f);
            mc = new RefNoise.Mapped(mc, 0f, 1f, 0f, 1f);
            this.mask = mc;
            // 山地"族"：本沙箱用单一山地变体代表参考的三档（默认预设 mountainVariety=0，
            // 参考此时是三条独立 unmixable 条目，族形态一致，只差种子）
            this.mountain = unmixable.length > 0 ? unmixable[0] : border;
        }

        double apply(double x, double z) {
            float select = mask.compute((float) x, (float) z);
            double regionH = tectonicBias(x, z);
            if (select < MTN_BLEND_LOW) return regionH;
            double mtnH = mountain.height01(x, z);
            if (select > MTN_BLEND_HIGH) return mtnH;
            float alpha = (select - MTN_BLEND_LOW) / (MTN_BLEND_HIGH - MTN_BLEND_LOW);
            return regionH + (mtnH - regionH) * alpha;
        }

        Category category(double x, double z) {
            float select = mask.compute((float) x, (float) z);
            float midpoint = MTN_BLEND_LOW + (MTN_BLEND_HIGH - MTN_BLEND_LOW) * MTN_BLEND_SPLIT;
            if (select >= midpoint) return Category.MOUNTAINS;
            RegionPartition.Sample s = partition.sample((float) x, (float) z);
            return slotCats[RegionPartition.selectIndex(s.regionId(), slots.length)];
        }

        float mask(double x, double z) {
            return mask.compute((float) x, (float) z);
        }
    }
}
