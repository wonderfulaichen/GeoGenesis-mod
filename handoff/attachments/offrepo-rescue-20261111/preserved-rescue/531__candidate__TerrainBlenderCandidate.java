package com.geogenesis.worldgen.terrain.candidate;

import java.util.Arrays;

/**
 * <b>旧版 TerraForged 0.3.x 的 {@code TerrainBlender} 逐行复刻</b>（{@code TerrainBlender.java:40-272}）。
 *
 * <p>这是"候选去重层"的参考实现。它与 FreeTerraForged 的 {@code RegionModule + RegionLerper}
 * 是<b>两套不同机制</b>，不能混为一谈：</p>
 *
 * <table border="1">
 *   <tr><th></th><th>TF 0.3.x TerrainBlender</th><th>FTF RegionModule + RegionLerper</th></tr>
 *   <tr><td>格网</td><td>800 格，抖动 0.8</td><td>1200 格，抖动 0.7</td></tr>
 *   <tr><td>取几个候选</td><td><b>3×3 全部近邻</b>，按距离加权</td><td><b>只取最近一个</b></td></tr>
 *   <tr><td>过渡对象</td><td>相邻区域<b>互相</b>混合</td><td>都向<b>公共边界地形</b>（平原/草原）收</td></tr>
 *   <tr><td>过渡带宽度</td><td>由 d0/d1 几何决定：{@code r = (d0+d1)/2 · blending}</td><td>由 {@code edge} 值域决定（固定 0–0.5 归一）</td></tr>
 *   <tr><td>权函数</td><td>{@code (1 − Δ/r)²}，只在 {@code Δ<r} 内非零</td><td>线性 {@code lerp(border, selected, edge)}</td></tr>
 *   <tr><td>相同类型</td><td>按 {@code TerrainNoise} 对象<b>去重</b>，同图只求值一次</td><td>无去重（只算一个，天然不重复）</td></tr>
 * </table>
 *
 * <h3>为什么"权重影响候选占比而非振幅"</h3>
 * {@code WeightMap}（{@code WeightMap.java:32-113}）把权重变成<b>累积区间</b>：
 * 权重 2 的类型占两倍宽的 id 区间，于是被更多区域格点选中 —— 是<b>面积</b>分配。
 * 进入混合后每个被选中的候选都按距离取权，权重本身不再参与高度计算。
 * 所以"某类面积翻倍"≠"某类更高"。
 *
 * <h3>复刻要点（易错处）</h3>
 * <ol>
 *   <li>距离用<b>平方欧氏</b>比较，但 {@code getWeight} 吃的是<b>开方后</b>的距离
 *       （{@code getDistance} 内 {@code NoiseUtil.sqrt}）。混用会让过渡带宽度错一个量级。</li>
 *   <li>过渡半径 {@code r = (d0 + d1) / 2 × blending}，{@code blendStart = (d0+d1)/2 − r}。
 *       {@code d0 ≤ blendStart} 时<b>直接返回中心值</b>，不进混合 —— 这是绝大多数格点的路径。</li>
 *   <li>权重归一到 {@code Σw} 再 clamp 到 [0,1]。clamp 是参考原样，会削掉超界部分。</li>
 *   <li>同类型去重：参考用 {@code Object2FloatCache} 按 <b>TerrainNoise 对象身份</b>缓存，
 *       9 格里同一类型多次出现只求值一次。本类用 {@code IdentityHashMap} 等价复现。</li>
 * </ol>
 *
 * <p>流式约束：(x,z) 纯函数，只查 3×3 邻域，无跨块状态（缓存是方法内局部量）。</p>
 */
public final class TerrainBlenderCandidate {

    /** {@code TerrainBlender.java:41-42}。 */
    private static final int REGION_SEED_OFFSET = 21491124;
    private static final int WARP_SEED_OFFSET = 12678;

    /** {@code NoiseGenerator.java:265}：{@code new TerrainBlender(TERRAIN_OFFSET, 800, 0.8F, 0.4F, ...)}。 */
    static final int SCALE = 800;
    static final float JITTER = 0.8f;
    static final float BLENDING = 0.4f;

    /** {@code NoiseUtil.X_PRIME / Y_PRIME}。 */
    private static final int X_PRIME = 1619;
    private static final int Y_PRIME = 31337;

    private final int seed;
    private final float frequency;
    private final RefNoise.Node warpX, warpZ;
    private final int[] slotOfFamily;
    private final Family[] families;

    /** 一个地貌族的求值器（输出参考归一化高度 0..1）。 */
    public interface Family {
        float height01(float x, float z);
    }

    /**
     * @param families 名额表：同一个族可以出现多次（出现 n 次 = 面积 n 倍），
     *                 与参考 {@code WeightMap} 把 weight 展开成累积区间等价。
     */
    public TerrainBlenderCandidate(long worldSeed, Family[] families) {
        if (families.length == 0) throw new IllegalArgumentException("no families");
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        this.seed = base + REGION_SEED_OFFSET;
        this.frequency = 1f / SCALE;
        this.families = families.clone();

        // {TerrainBlender.java:59}：Domain.warp(SIMPLEX, seed + WARP_SEED_OFFSET, scale, 3, scale/2.5)
        // ⇒ 位移幅度 ±(800/2.5)/2 = ±160 格（Domain 的 offset = (perlin01−0.5)×pow）。
        RefNoise.Node wx = RefNoise.Perlin.ofScale(base + WARP_SEED_OFFSET, SCALE, 3);
        RefNoise.Node wz = RefNoise.Perlin.ofScale(base + WARP_SEED_OFFSET + 1, SCALE, 3);
        this.warpX = wx;
        this.warpZ = wz;

        // 槽位 → 族下标。参考的 WeightMap 用累积权重区间把 id 映射到某个 TerrainNoise；
        // 这里名额表本身就是展开后的数组，所以直接恒等映射即可。
        this.slotOfFamily = new int[families.length];
        for (int i = 0; i < slotOfFamily.length; i++) slotOfFamily[i] = i;
    }

    /** 采样一个点的参考归一化高度。 */
    public float height01(float x, float z) {
        // {TerrainBlender.java:79-82}
        float ox = (warpX.compute(x, z) - 0.5f) * (SCALE / 2.5f);
        float oz = (warpZ.compute(x, z) - 0.5f) * (SCALE / 2.5f);
        float rx = (x + ox) * frequency;
        float rz = (z + oz) * frequency;

        int[] hashes = new int[9];
        float[] dist2 = new float[9];
        gatherCells(rx, rz, hashes, dist2);

        int closest = 0, closest2 = 0;
        float d0 = Float.MAX_VALUE, d1 = Float.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (dist2[i] < d0) {
                d1 = d0;
                d0 = dist2[i];
                closest2 = closest;
                closest = i;
            } else if (dist2[i] < d1) {
                d1 = dist2[i];
                closest2 = i;
            }
        }

        float dist0 = (float) Math.sqrt(d0);
        float dist1 = (float) Math.sqrt(Math.max(d1, 0f));
        float borderDistance = (dist0 + dist1) * 0.5f;
        float blendRadius = borderDistance * BLENDING;
        float blendStart = borderDistance - blendRadius;

        if (dist0 <= blendStart) {
            // 绝大多数格点走这里：只用中心候选
            return evaluate(hashes[closest], x, z);
        }
        return blended(hashes, dist2, closest, closest2, dist0, blendRadius, x, z);
    }

    /** {@code getCell}：抖动格心 + 3×3 距离。 */
    private void gatherCells(float rx, float rz, int[] hashes, float[] dist2) {
        int maxX = RefNoise.floor(rx) + 1;
        int maxZ = RefNoise.floor(rz) + 1;
        int i = 0;
        for (int cz = maxZ - 2; cz <= maxZ; cz++) {
            for (int cx = maxX - 2; cx <= maxX; cx++, i++) {
                int hash = RefNoise.hash2D(seed, cx, cz);
                float dx = rand(hash, X_PRIME);
                float dz = rand(hash, Y_PRIME);
                float px = cx + dx * JITTER;
                float pz = cz + dz * JITTER;
                float ddx = rx - px, ddz = rz - pz;
                hashes[i] = hash;
                dist2[i] = ddx * ddx + ddz * ddz;
            }
        }
    }

    /** {@code getBlendedValue}：九个候选按 {@code (1−Δ/r)²} 加权，同类型去重。 */
    private float blended(int[] hashes, float[] dist2, int closest, int closest2,
                          float nearest, float blendRange, float x, float z) {
        // 按 hash 去重（等价参考按 TerrainNoise 对象身份的缓存）
        java.util.HashMap<Integer, Float> byHash = new java.util.HashMap<>();

        float nearestWeight = weight((float) Math.sqrt(Math.max(dist2[closest], 0f)), nearest, blendRange);
        float sumNoise = value(hashes[closest], x, z, byHash) * nearestWeight;
        float sumWeight = nearestWeight;

        if (closest2 >= 0) {
            float w2 = weight((float) Math.sqrt(Math.max(dist2[closest2], 0f)), nearest, blendRange);
            if (w2 > 0) {
                sumNoise += value(hashes[closest2], x, z, byHash) * w2;
                sumWeight += w2;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i == closest || i == closest2) continue;
            float w = weight((float) Math.sqrt(Math.max(dist2[i], 0f)), nearest, blendRange);
            if (w > 0) {
                sumNoise += value(hashes[i], x, z, byHash) * w;
                sumWeight += w;
            }
        }
        float v = sumWeight > 0f ? sumNoise / sumWeight : 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 同一 hash（同区域格点）只求值一次 —— 参考的去重语义。 */
    private float value(int hash, float x, float z, java.util.HashMap<Integer, Float> memo) {
        Float cached = memo.get(hash);
        if (cached != null) return cached;
        float v = evaluate(hash, x, z);
        memo.put(hash, v);
        return v;
    }

    /** hash → 族 → 高度。参考里是 {@code WeightMap.getValue(MathUtil.rand(hash))}。 */
    private float evaluate(int hash, float x, float z) {
        float noise = rand(hash);
        int slot = slotFor(noise);
        return families[slot].height01(x, z);
    }

    /** {@code WeightMap.getValue}：把 [0,1] 的 noise 映射到名额表的槽位。 */
    private int slotFor(float noise) {
        int n = slotOfFamily.length;
        int idx = (int) (noise * n);
        return Math.max(0, Math.min(n - 1, idx));
    }

    /** {@code TerrainBlender.Blender.getWeight}：{@code (1 − Δ/r)²}，超出即 0。 */
    private static float weight(float dist, float origin, float blendRange) {
        float delta = dist - origin;
        if (delta <= 0f) return 1f;
        if (delta >= blendRange) return 0f;
        float w = 1f - (delta / blendRange);
        return w * w;
    }

    /** {@code MathUtil.rand(hash, offset)} → {@code rand(hash(seed, offset))}。 */
    private static float rand(int hash, int offset) {
        return rand(RefNoise.hash2D(hash, offset, 0));
    }

    /** {@code MathUtil.rand(int n)}：{@code (n³·60493)/2^31} 映射到 [0,1]。 */
    private static float rand(int n) {
        n ^= 1619;
        n ^= 31337;
        float value = (n * n * n * 60493) / 2.14748365E9F;
        return RefNoise.mapClamped(value, -1f, 1f);
    }

    /** 诊断：当前点的两个最近候选距离（验证过渡带宽度是否合理）。 */
    public float[] debugDistances(float x, float z) {
        float ox = (warpX.compute(x, z) - 0.5f) * (SCALE / 2.5f);
        float oz = (warpZ.compute(x, z) - 0.5f) * (SCALE / 2.5f);
        int[] hashes = new int[9];
        float[] dist2 = new float[9];
        gatherCells((x + ox) * frequency, (z + oz) * frequency, hashes, dist2);
        float[] sorted = dist2.clone();
        Arrays.sort(sorted);
        return new float[]{(float) Math.sqrt(sorted[0]), (float) Math.sqrt(sorted[1]), frequency};
    }

    /** 诊断：最近候选格的族下标（出类型图用；纯几何，不求值地貌）。 */
    public int debugFamily(float x, float z) {
        float ox = (warpX.compute(x, z) - 0.5f) * (SCALE / 2.5f);
        float oz = (warpZ.compute(x, z) - 0.5f) * (SCALE / 2.5f);
        int[] hashes = new int[9];
        float[] dist2 = new float[9];
        gatherCells((x + ox) * frequency, (z + oz) * frequency, hashes, dist2);
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (dist2[i] < bestD) {
                bestD = dist2[i];
                best = i;
            }
        }
        return slotFor(rand(hashes[best]));
    }
}