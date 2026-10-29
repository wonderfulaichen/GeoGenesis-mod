package com.geogenesis.worldgen.terrain.candidate;

/**
 * 山地族分区：{@code mountains_1 / mountains_2 / mountains_3} 按大尺度 Voronoi 区域分开，
 * 边缘向公共边界地形（平原）过渡。与丘陵分区同构（见 {@link HillsRegionCandidate}）。
 *
 * <p><b>为什么山地也必须走分区而不是加权混合</b>：三种山地的形态语义完全不同
 * （m1 脊线山系、m2 细胞状山块、m3 阶地山），逐点加权会把三者都摊平成"没有性格的丘陵"。
 * 参考正是靠区域选择 + 边缘过渡实现多样性的。</p>
 *
 * <p>流式约束：区域判定只查 3×3 邻域，全部 (x,z) 纯函数。</p>
 */
public final class MountainsRegionCandidate {

    /** 参考高度 → e：1.0 参考单位 ≈ 256 格 ≈ 1.08 e。 */
    public static final double E_PER_REF_UNIT = 1.08;

    private final MountainsCandidate.Variant[] kinds = {
            MountainsCandidate.Variant.M1,
            MountainsCandidate.Variant.M2,
            MountainsCandidate.Variant.M3
    };

    private RefNoise.Node[] types;
    private PlainsCandidate border;
    private RegionPartition partition;

    public String id() { return "MTN-region"; }

    public String label() { return "山地分区（m1 / m2 / m3 + 边缘混合）"; }

    public void seed(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;

        types = new RefNoise.Node[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            MountainsCandidate m = new MountainsCandidate(kinds[i]);
            m.seed(worldSeed + 7919L * i);   // 各自独立建图，避免共享种子游标
            types[i] = new NodeAdapter(m);
        }

        border = new PlainsCandidate(PlainsCandidate.Variant.PLAINS);
        border.seed(worldSeed);

        RefNoise.Node wx = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        RefNoise.Node wz = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        partition = new RegionPartition(next.getAsInt(), wx, wz);
    }

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

    /** 把 {@link MountainsCandidate} 的实例适配成 {@link RefNoise.Node}。 */
    private record NodeAdapter(MountainsCandidate candidate) implements RefNoise.Node {
        @Override public float compute(float x, float z) {
            return (float) candidate.height01(x, z);
        }
    }
}