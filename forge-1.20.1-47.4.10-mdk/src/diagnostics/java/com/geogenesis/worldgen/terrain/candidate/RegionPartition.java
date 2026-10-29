package com.geogenesis.worldgen.terrain.candidate;

/**
 * 参考 {@code cell/terrain/region/RegionModule.java:31-91} 的区域划分：抖动 Voronoi 格。
 *
 * <p>每个格心用固定抖动表 {@link RegionCellTable} 偏移 0.7，按 NATURAL 距离
 * （{@code |dx|+|dy|+dx²+dy²}）取最近与次近两个格心，据此给出：</p>
 * <ul>
 *   <li>{@link #regionId}：格心哈希值 → [0,1]，供 {@code RegionSelector} 按权重选地貌；</li>
 *   <li>{@link #edge}：距边界的归一化程度 → [0,1]，1 = 格心深处，0 = 恰在边界上，
 *       供 {@code RegionLerper} 把边缘混合回公共边界地形。</li>
 * </ul>
 *
 * <h3>为什么不是"两种地貌直接混合"</h3>
 * 参考的做法是：格内用<b>选中的那一种</b>地貌，只有靠边界的一条带才向<b>公共边界地形</b>
 * （参考里是 plains/steppe）过渡。这样区域内始终是"纯"的某一种丘陵，
 * 边界也不会出现两种地貌硬碰。直接对两种丘陵做加权混合会把两者都摊薄，
 * 正是本项目旧 {@code TypeLandShape} 的老问题。
 *
 * <h3>流式约束</h3>
 * 全部由 (x,z) 与 seed 决定：只查 3×3 邻域、不依赖遍历顺序、无整图统计、无跨块状态。
 */
final class RegionPartition {

    /** 参考预设 {@code terrainRegionSize = 1200}（{@code Presets.java:36}）。 */
    static final float REGION_SCALE = 1200f;
    /** 区域边界扭曲：{@code simplex(400, 1)} 两级，位移 ±200/2（{@code Heightmap.java:99-109}）。 */
    private static final float WARP_SCALE = 400f;
    private static final float WARP_STRENGTH = 200f;

    private final int seed;
    private final float frequency;
    private final RefNoise.Node warpX, warpZ;

    /**
     * 格子 id 取法。
     *
     * <p><b>WHITE（参考原样）</b>：{@code valCoord2D(seed, cellX, cellY)} —— 每格独立的
     * 白噪声哈希。<b>相邻格子完全不相关</b>，所以选出来的类型是互不相干的斑点，
     * 面积再大也只是圆斑/多边形块，不会连成带。</p>
     *
     * <p><b>COHERENT（本项目补充）</b>：把"格子 id"改成<b>在格心处采样一个低频噪声</b>。
     * 相邻格心的噪声值相近 ⇒ 相邻格子有<b>很高概率选中同一类型</b> ⇒ 类型沿噪声等值线
     * 连成大片。噪声各向异性（x 慢 z 快）时，等值线本身是<b>长条</b> ⇒ 类型带也拉长。</p>
     *
     * <p>这正是"原来纯随机、现在有概率连带"的实现：连带强度由噪声的<b>相关长度</b>决定，
     * 条带方向由<b>各向异性比例</b>决定，两者都是连续参数，不会在某个距离上突然断开。</p>
     */
    enum IdMode { WHITE, COHERENT }

    private final IdMode idMode;
    private final RefNoise.Node idNoise;
    private final float anisoX, anisoZ;

    RegionPartition(int seed, RefNoise.Node warpX, RefNoise.Node warpZ) {
        this(seed, warpX, warpZ, IdMode.WHITE, null, 1f, 1f);
    }

    RegionPartition(int seed, RefNoise.Node warpX, RefNoise.Node warpZ,
                    IdMode idMode, RefNoise.Node idNoise, float anisoX, float anisoZ) {
        this.seed = seed;
        this.frequency = 1f / REGION_SCALE;
        this.warpX = warpX;
        this.warpZ = warpZ;
        this.idMode = idMode;
        this.idNoise = idNoise;
        this.anisoX = anisoX;
        this.anisoZ = anisoZ;
    }

    /** 采样结果：格心 id 与边缘程度。 */
    record Sample(float regionId, float edge) { }

    Sample sample(float x, float z) {
        float ox = (warpX.compute(x, z) - 0.5f) * WARP_STRENGTH;
        float oz = (warpZ.compute(x, z) - 0.5f) * WARP_STRENGTH;
        float px = (x + ox) * frequency;
        float pz = (z + oz) * frequency;

        int xi = RefNoise.floor(px);
        int yi = RefNoise.floor(pz);
        float nearest = Float.MAX_VALUE, nearest2 = Float.MAX_VALUE;
        int cellX = 0, cellY = 0;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = xi + dx, cy = yi + dy;
                float[] vec = RegionCellTable.CELL[hash2D(seed, cx, cy) & 0xFF];
                float vx = cx + vec[0] * 0.7f;
                float vy = cy + vec[1] * 0.7f;
                float ddx = vx - px, ddy = vy - pz;
                // DistanceFunction.NATURAL
                float dist = Math.abs(ddx) + Math.abs(ddy) + (ddx * ddx + ddy * ddy);
                if (dist < nearest) {
                    nearest2 = nearest;
                    nearest = dist;
                    cellX = cx;
                    cellY = cy;
                } else if (dist < nearest2) {
                    nearest2 = dist;
                }
            }
        }

        float id;
        if (idMode == IdMode.COHERENT) {
            // 在【格心世界坐标】上采样低频噪声：相邻格心值相近 ⇒ 类型连带
            float cxw = (cellX + 0.5f) * REGION_SCALE;
            float czw = (cellY + 0.5f) * REGION_SCALE;
            id = idNoise.compute(cxw * anisoX, czw * anisoZ);
        } else {
            id = mapValToUnit(valCoord2D(seed, cellX, cellY));
        }
        return new Sample(id, edgeValue(nearest, nearest2));
    }

    /**
     * 边缘程度：{@code DISTANCE_2_DIV} 给出 {@code d1/d2 − 1 ∈ [−1,0]}，
     * 映射为 {@code 1 − d1/d2}，再 {@code pow 1.5}，最后按参考的 {@code [0,0.5] → [0,1]} 归一。
     */
    private static float edgeValue(float d1, float d2) {
        if (!(d2 > 0f)) return 0f;
        float v = d1 / d2 - 1f;                 // [-1, 0]
        float edge = 1f - RefNoise.mapClamped(v, -1f, 0f);   // [0,1]
        edge = (float) Math.pow(edge, 1.5);
        // 参考 RegionModule.edgeValue：edgeMax=0.5, edgeMin=0
        if (edge < 0f) return 0f;
        if (edge > 0.5f) return 1f;
        return edge / 0.5f;
    }

    /** 参考 {@code NoiseUtil.valCoord2D}：返回 [-1,1]，再 map(−1,1,2) 归一。 */
    private static float valCoord2D(int seed, int x, int y) {
        int n = seed;
        n ^= 1619 * x;
        n ^= 31337 * y;
        return n * n * n * 60493 / 2.14748365E9F;
    }

    private static float mapValToUnit(float v) {
        return RefNoise.mapClamped(v, -1f, 1f);
    }

    /** 参考 {@code NoiseUtil.hash2D}（用于取抖动表索引）。 */
    private static int hash2D(int seed, int x, int y) {
        int hash = seed;
        hash ^= 1619 * x;
        hash ^= 31337 * y;
        hash = hash * hash * hash * 60493;
        hash ^= hash >> 13;
        return hash;
    }

    /**
     * 参考 {@code RegionSelector.get}：{@code round(identity × maxIndex)}。
     * <p>端点两个槽覆盖半宽区间 ⇒ 各类型概率为 {@code w/(N−1)}（端点减半），
     * 不是严格的 {@code count/Σcount}（已在参考核对中记录）。</p>
     */
    static int selectIndex(float regionId, int count) {
        int maxIndex = count - 1;
        return Math.max(0, Math.min(maxIndex, Math.round(regionId * maxIndex)));
    }
}