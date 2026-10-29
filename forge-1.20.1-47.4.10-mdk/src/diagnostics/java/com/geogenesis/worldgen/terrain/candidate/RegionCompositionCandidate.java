package com.geogenesis.worldgen.terrain.candidate;

/**
 * <b>参考组装方式的复刻</b>：把已定稿的地貌族按 {@code RegionModule + RegionSelector +
 * RegionLerper + TerrainBlender} 的方式拼成完整陆地。
 *
 * <p>这是"先复刻、再对比"里的<b>复刻侧</b>。上一轮我用的是自创的"统一高度带加权混合"，
 * 那不是参考的组装方式，也不能回答"参考的组装是否比我们现行生产好"。本类只照参考做：</p>
 *
 * <ol>
 *   <li><b>区域划分</b>（{@code RegionModule.java:31-91}）：1200 格抖动 Voronoi 格
 *       （{@link RegionPartition}，已逐行复刻），给出 {@code regionId} 与 {@code edge}。</li>
 *   <li><b>区域内选一种</b>（{@code RegionSelector.get}）：{@code round(id × (N−1))}
 *       从名额表里选一个 populator —— 格内是<b>纯</b>的一种地貌，不摊薄。</li>
 *   <li><b>区域边界过渡</b>（{@code RegionLerper.java:17-41}）：只有靠边的一条带
 *       {@code lerp(公共边界地形, 选中地貌, edge)}。参考的公共边界地形 = plains/steppe
 *       （{@code Heightmap.java:123-125}）。</li>
 *   <li><b>候选区域 3×3 抖动与去重</b>（{@code TerrainBlender.java:78-82 / 206-256}）：
 *       在区域格心上再做一次抖动，取 3×3 个候选按距离加权；相同 {@code TerrainNoise}
 *       去重，权重影响"候选占比"而非振幅。</li>
 * </ol>
 *
 * <h3>与参考的三处必要偏差（都写在这里，不藏在代码里）</h3>
 * <ul>
 *   <li><b>高度单位</b>：参考的 populator 输出"参考归一化高度"（×256 格 ≈ 1.08 e），
 *       我们各族的定稿输出同样口径，故直接共用 {@code E_PER_REF_UNIT = 1.08}。</li>
 *   <li><b>盆地</b>：参考没有盆地族，名额表里用本项目 B1 顶替；它的输出是 e 而非
 *       参考单位，故在 {@link Kind} 里单独标注并按 e 直接参与。</li>
 *   <li><b>高度必然下探海平面以下</b>：参考的所有族都在海平面以上（它靠独立的
 *       continent 场决定海陆），我们的族则直接产出地形高度。因此本类<b>不做海陆</b>，
 *       只出陆地类型层；海陆衔接是下一步（第 6 步）。</li>
 * </ul>
 *
 * <p>流式约束：全部 (x,z) 纯函数，仅查 3×3 邻域两次（区域 + 候选），无跨块状态。</p>
 */
public final class RegionCompositionCandidate {

    /** 参考高度 → e：1.0 参考单位 ≈ 256 格 ≈ 1.08 e。 */
    public static final double E_PER_REF_UNIT = 1.08;

    /**
     * 名额表：与参考预设 {@code Presets.java} 的 {@code terrainRegionSize/weight} 同构，
     * 但只放我们已经定稿的族。重复的条目就是"名额"——出现两次 = 面积翻倍。
     */
    private enum Kind {
        PLAINS("plains"), STEPPE("steppe"),
        HILLS_1("hills1"), HILLS_2("hills2"), DALES("dales"),
        M1("m1"), M2("m2"), M3("m3"),
        PLATEAU("plateau"),
        BADLANDS("badlands"),
        BASIN("basin");

        final String id;

        Kind(String id) { this.id = id; }
    }

    /**
     * 名额表（顺序即 {@code RegionSelector} 的槽位顺序）。
     *
     * <p>参考的实际配比里 plains 与 steppe 是高频项、山地与台地是低频项。这里按
     * "低地多、高地少"的地理事实给名额：平原类 5 席、丘陵类 4 席、山地 2 席、
     * 台地 1 席、劣地 1 席、盆地 1 席 = 14 席。</p>
     */
    private static final Kind[] SLOTS = {
            Kind.PLAINS, Kind.PLAINS, Kind.STEPPE, Kind.PLAINS, Kind.STEPPE,
            Kind.HILLS_1, Kind.HILLS_2, Kind.DALES, Kind.HILLS_1,
            Kind.M1, Kind.M2,
            Kind.PLATEAU,
            Kind.BADLANDS,
            Kind.BASIN
    };

    /** 各族的归一化高度求值器。 */
    private interface Family {
        double height01(double wx, double wz);
    }

    private final Family[] families = new Family[Kind.values().length];
    private RegionPartition partition;
    private PlainsCandidate border;
    private TerrainBlenderCandidate blender;

    public String id() { return "COMPOSE-region-ref"; }

    public String label() { return "参考组装复刻（区域划分 + 公共边界过渡）"; }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;

        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        PlainsCandidate steppe = new PlainsCandidate(PlainsCandidate.Variant.STEPPE);
        HillsCandidate h1 = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        HillsCandidate h2 = new HillsCandidate(HillsCandidate.Variant.HILLS_2);
        HillsCandidate dales = new HillsCandidate(HillsCandidate.Variant.DALES_GEO);
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        MountainsCandidate m2 = new MountainsCandidate(MountainsCandidate.Variant.M2_GEO);
        MountainsCandidate m3 = new MountainsCandidate(MountainsCandidate.Variant.M3_GEO);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        BadlandsCandidate badlands = new BadlandsCandidate();
        BasinCandidate basin = new BasinCandidate(BasinCandidate.Variant.B1);
        plains.seed(worldSeed);
        steppe.seed(worldSeed);
        h1.seed(worldSeed + 101L);
        h2.seed(worldSeed + 202L);
        dales.seed(worldSeed + 303L);
        m1.seed(worldSeed + 404L);
        m2.seed(worldSeed + 505L);
        m3.seed(worldSeed + 606L);
        plateau.seed(worldSeed + 707L);
        badlands.seed(worldSeed + 808L);
        basin.seed(worldSeed + 909L);

        families[Kind.PLAINS.ordinal()] = plains::height01;
        families[Kind.STEPPE.ordinal()] = steppe::height01;
        families[Kind.HILLS_1.ordinal()] = h1::height01;
        families[Kind.HILLS_2.ordinal()] = h2::height01;
        families[Kind.DALES.ordinal()] = dales::height01;
        families[Kind.M1.ordinal()] = m1::height01;
        families[Kind.M2.ordinal()] = m2::height01;
        families[Kind.M3.ordinal()] = m3::height01;
        families[Kind.PLATEAU.ordinal()] = plateau::height01;
        families[Kind.BADLANDS.ordinal()] = badlands::height01;
        families[Kind.BASIN.ordinal()] = basin::e;   // 盆地直接给 e，见类注释

        border = plains;
        RefNoise.Node wx = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        RefNoise.Node wz = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        partition = new RegionPartition(next.getAsInt(), wx, wz);

        // 同一名额表展开成 blender 的族数组（槽位顺序一致，面积配比才一致）。
        // blender 内部是纯高度混合，所有族必须同口径（0..1），故盆地先归一化。
        TerrainBlenderCandidate.Family[] fam = new TerrainBlenderCandidate.Family[SLOTS.length];
        for (int i = 0; i < SLOTS.length; i++) {
            Kind k = SLOTS[i];
            fam[i] = (x, z) -> (float) normalizedHeight(k, x, z);
        }
        blender = new TerrainBlenderCandidate(worldSeed, fam);
    }

    /** 族的 0..1 归一化高度（盆地由 e 线性拉进同一区间）。 */
    private double normalizedHeight(Kind kind, double wx, double wz) {
        double v = families[kind.ordinal()].height01(wx, wz);
        if (kind != Kind.BASIN) return v;
        double u = (v - BASIN_E_LO) / (BASIN_E_HI - BASIN_E_LO);
        return u < 0 ? 0 : (u > 1 ? 1 : u);
    }

    /** 盆地 B1 的 e 区间（实测 Y 54–65 对应 e 约 -0.06…+0.02）。 */
    private static final double BASIN_E_LO = -0.06;
    private static final double BASIN_E_HI = 0.02;

    /** 合成后的 e（未做海陆，陆地类型层专用）。 */
    public double e(double wx, double wz) {
        if (partition == null) throw new IllegalStateException("seed() not called");
        RegionPartition.Sample s = partition.sample((float) wx, (float) wz);
        Kind kind = SLOTS[RegionPartition.selectIndex(s.regionId(), SLOTS.length)];
        double selected = familyE(kind, wx, wz);
        double edge = s.edge();
        if (edge >= 1.0) return selected;
        double b = border.height01(wx, wz) * E_PER_REF_UNIT;
        return b + (selected - b) * edge;
    }

    /** 第二种组装：旧版 TerraForged 的 {@code TerrainBlender}（3×3 候选按距离加权）。 */
    public double eBlender(double wx, double wz) {
        float h01 = blender.height01((float) wx, (float) wz);
        return h01 * E_PER_REF_UNIT * E_SCALE_TO_E;
    }

    /** 第二种组装的主导族下标（类型图用）。 */
    public int blenderSlot(double wx, double wz) {
        return blender.debugFamily((float) wx, (float) wz);
    }

    /** 该类的地形 e。盆地直接给 e，其余按参考单位换算。 */
    private double familyE(Kind kind, double wx, double wz) {
        double v = families[kind.ordinal()].height01(wx, wz);
        return kind == Kind.BASIN ? v : v * E_PER_REF_UNIT * E_SCALE_TO_E;
    }

    /**
     * 参考单位 → e 的额外缩放。
     *
     * <p>{@code E_PER_REF_UNIT} 已给出 1.0 参考单位 ≈ 1.08 e；这里再乘 1.0 表示不额外缩放。
     * 单独列出来是为了让"参考族的高度区间是否适配我们的 HeightCurve"这件事有一个
     * 显式的旋钮，而不是散落在各调用处。</p>
     */
    private static final double E_SCALE_TO_E = 1.0;

    /** 诊断：当前点选中的族下标（{@link #slotKind(int)} 可反查槽位）。 */
    public int debugSlot(double wx, double wz) {
        RegionPartition.Sample s = partition.sample((float) wx, (float) wz);
        return RegionPartition.selectIndex(s.regionId(), SLOTS.length);
    }

    /** 诊断：槽位 → 族 id（出类型图用）。 */
    public static String slotKind(int slot) {
        return SLOTS[Math.max(0, Math.min(SLOTS.length - 1, slot))].id;
    }
}