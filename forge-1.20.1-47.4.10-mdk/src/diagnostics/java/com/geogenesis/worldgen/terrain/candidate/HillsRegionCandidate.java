package com.geogenesis.worldgen.terrain.candidate;

/**
 * 丘陵族分区：把 {@code hills_1 / hills_2 / dales} 三种参考配方按<b>大尺度 Voronoi 区域</b>分开，
 * 边缘向公共边界地形过渡 —— 与参考 {@code Heightmap.java:123-125} 的组装方式一致：
 *
 * <pre>
 *   terrainRegions = RegionSelector([steppe, plains, hills1, hills2, dales, ...])
 *   terrainBorders = makeBorder(...)          // plains/steppe
 *   terrainBlend   = RegionLerper(borders, regions)
 * </pre>
 *
 * <p>{@code RegionLerper}（{@code region/RegionLerper.java:17-41}）在边缘把选中的地貌
 * 线性混合回边界地形：{@code h = lerp(border, selected, edge)}。这样：</p>
 * <ul>
 *   <li>区域内部是<b>纯</b>的一种丘陵，不会被另两种摊薄；</li>
 *   <li>区域边界是"丘陵 → 平原"的自然收尾，不是两种丘陵硬碰。</li>
 * </ul>
 *
 * <p>权重：三种丘陵等权（参考预设里 hills 权重 2.0、dales 1.5，但我们要的是"三种都出现"，
 * 故先等分；要改比例只需换 {@code RegionSelector} 的名额表）。</p>
 *
 * <p>流式约束：区域判定只查 3×3 邻域，全部 (x,z) 纯函数。</p>
 */
public final class HillsRegionCandidate {

    /** 参考高度 → e：1.0 参考单位 ≈ 256 格 ≈ 1.08 e。 */
    public static final double E_PER_REF_UNIT = 1.08;

    private final HillsCandidate.Variant[] kinds = {
            HillsCandidate.Variant.HILLS_1,
            HillsCandidate.Variant.HILLS_2,
            HillsCandidate.Variant.DALES
    };

    private RefNoise.Node[] types;
    private PlainsCandidate border;
    private RegionPartition partition;

    public String id() { return "HILLS-region"; }

    public String label() { return "丘陵分区（hills_1 / hills_2 / dales + 边缘混合）"; }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;

        types = new RefNoise.Node[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            types[i] = HillsCandidate.build(kinds[i], next);
        }

        // 边界地形：参考 makeBorder → makePlains
        border = new PlainsCandidate(PlainsCandidate.Variant.PLAINS);
        border.seed(worldSeed);

        // 区域边界扭曲：simplex(400,1) ×2（此处以 Perlin 等价替代，见 RegionPartition 注释）
        RefNoise.Node wx = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        RefNoise.Node wz = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        partition = new RegionPartition(next.getAsInt(), wx, wz);
    }

    /** 归一化高度：区域内用选中的丘陵，边缘 lerp 回边界地形。 */
    public double height01(double wx, double wz) {
        if (partition == null) throw new IllegalStateException("seed() not called");
        RegionPartition.Sample s = partition.sample((float) wx, (float) wz);
        int idx = RegionPartition.selectIndex(s.regionId(), types.length);
        double selected = types[idx].compute((float) wx, (float) wz);
        double edge = s.edge();
        if (edge >= 1.0) return selected;
        double b = border.height01(wx, wz);
        return b + (selected - b) * edge;
    }

    public double e(double wx, double wz) { return height01(wx, wz) * E_PER_REF_UNIT; }

    /** 诊断：当前点被选中哪一种（0=hills_1, 1=hills_2, 2=dales）。 */
    public int debugKind(double wx, double wz) {
        return RegionPartition.selectIndex(partition.sample((float) wx, (float) wz).regionId(), types.length);
    }

    /** 诊断：边缘程度（1 = 区域内部纯地形，0 = 恰在边界）。 */
    public double debugEdge(double wx, double wz) {
        return partition.sample((float) wx, (float) wz).edge();
    }
}