package com.geogenesis.worldgen.terrain.morph;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>组装骨架</b>：从一个世界种子一次性导出 morph 链<b>真正需要</b>的四件东西。
 *
 * <pre>
 *   partition   — 抖动 Voronoi 分区（格心 id + 边界距离）
 *   slots       — 45 张加权票 → 条目下标（已 shuffle 打散）
 *   mask        — 山形遮罩节点（WorleyEdgeAdd 2250 格 @ warp @ curve3 @ clamp @ map）
 *   coverage    — 覆盖层用的"共享台地"族（{@code unmixable[0]}）
 *   blendLow/High — 覆盖层的 select 阈值 0.55 / 0.78
 * </pre>
 *
 * <h3>★ 为什么构造时要"空转"20 个 int</h3>
 * <p>参考的 {@code TerrainProvider.generateTerrain} 在决定分区/遮罩之前，先为 10 个低地复合对
 * 各取两个 int（selector、warp）。morph 链<b>不用</b>那 10 个复合条目（它用 18 个条目各自的
 * 线性外壳做过渡），但<b>必须照样消费这 20 个 int</b> —— 否则后面 shuffle、分区、遮罩的种子
 * 全部前移，产出直接换图。{@link #consumeCompositeSeeds} 就是这段"空转"，注释即契约。</p>
 *
 * <h3>★ 种子游标（顺序即产出）</h3>
 * <pre>
 *   base          = (int)(seed ^ (seed &gt;&gt;&gt; 32))
 *   composites    : base+0 … base+19        （10 对 × 2，空转，见上）
 *   shuffle       : base+20                 （Collections.shuffle 的 Random 种子）
 *   warpX / warpZ : base+21 / base+22       （分区 warp，Perlin(400,1)）
 *   partition     : base+23                 （分区自身 seed）
 *   mask warp     : base+24                 （warpPerlin(333, 2, 250)）
 * </pre>
 * <p>族种子不在这个游标上：它们是 {@code seed + 固定偏移}（0/0/33/11/22/44/55/77），
 * 由 {@link MorphCatalog} 自己派发。</p>
 *
 * <h3>★ 票槽为什么是 45</h3>
 * <p>{@code RegionSelector.getWeightedArray} 把"权重"折算成槽位数：
 * {@code count_i = round(w_i / min(w))}。低地 5 条各 2/1/1.5/2/2（min=1）、10 个复合取均值、
 * 独立三条 5/3/9。合计 {@code 2+1+2+2+2 + 2+2+2+2+1+2+2+2+2+2 + 5+3+9 = 45}。
 * 权重因此等价于<b>面积</b>：权重 9 的山地占 9 倍槽位。</p>
 */
final class MorphAssembly {

    /** 山形遮罩的 worley 细胞尺度（块）：{@code 1000 × hScale × 2.25}，hScale=1。 */
    static final float MOUNTAIN_SHAPE_SCALE = 1000f * 1.0f * 2.25f;
    /** 覆盖层 select 的下阈值（本实现实测值，不是参考的 0.3 —— 见 {@link #blendLow()}）。 */
    static final float MTN_BLEND_LOW = 0.55f;
    /** 覆盖层 select 的上阈值。 */
    static final float MTN_BLEND_HIGH = 0.78f;
    /** 票槽总数（见类注释的算术）。 */
    static final int SLOT_COUNT = 45;
    /** 默认区域尺度（块），与参考预设 {@code terrainRegionSize = 1200} 同值。 */
    static final float REGION_SCALE = 1200f;

    /**
     * 一个地貌族的求值器：输出<b>相对共享地面基准</b>的高度（0 = 基准面）。
     *
     * <p>只暴露这一个方法，是为了让"覆盖层拿到的就是参考里 {@code unmixable[0]} 那个族"
     * 这件事在类型上就成立 —— 调用方不需要（也无法）知道族内部的噪声图。</p>
     */
    interface Family {
        double height01(double x, double z);
    }

    private final long seed;
    private final MorphRegionPartition partition;
    private final int[] slotTable;
    private final MorphNoise.Node mask;
    private final Family coverage;

    /**
     * @param seed     世界种子（分区/遮罩/台地覆盖全部由它派生）
     * @param coverage 覆盖层族（共享台地 {@code height01}）；由 {@link #canonical} 统一构造
     */
    private MorphAssembly(long seed, MorphRegionPartition partition, int[] slotTable,
                          MorphNoise.Node mask, Family coverage) {
        this.seed = seed;
        this.partition = partition;
        this.slotTable = slotTable;
        this.mask = mask;
        this.coverage = coverage;
    }

    /**
     * 按世界种子构建一份组装骨架。同一 {@code seed} 永远给出逐位相同的四件套。
     */
    static MorphAssembly of(long seed) {
        int base = (int) (seed ^ (seed >>> 32));

        // ---- 空转 20 个 int：复刻参考为 10 个低地复合对准备的种子（见类注释）----
        int cursor = base;
        cursor = consumeCompositeSeeds(cursor);

        // ---- shuffle ----
        int[] slots = buildSlots(cursor++);

        // ---- 分区 warp + 分区 ----
        MorphNoise.Node warpX = MorphNoise.Perlin.ofScale(cursor++, 400f, 1);
        MorphNoise.Node warpZ = MorphNoise.Perlin.ofScale(cursor++, 400f, 1);
        MorphRegionPartition partition = new MorphRegionPartition(cursor++, warpX, warpZ);

        // ---- 山形遮罩 ----
        MorphNoise.Node mc = new MorphNoise.WorleyEdgeAdd(base + 4241, 1f / MOUNTAIN_SHAPE_SCALE);
        mc = MorphNoise.warpPerlin(mc, cursor, 333f, 2, 250f);
        mc = new MorphNoise.Curve3(mc);
        mc = new MorphNoise.Clamped(mc, 0f, 0.9f);
        MorphNoise.Node mask = new MorphNoise.Mapped(mc, 0f, 1f, 0f, 1f);

        // ---- 覆盖族：共享台地（Terrace 值域 1.05，见 MorphRegionContext 的说明）----
        //
        // ⚠ <b>必须以 double 坐标求值</b>：覆盖层在地形 y 上直接可见，float 求值在远处会塌格。
        // 这里取台地的 height 节点走 MorphNoiseEval（与 mask/条目的求值路径同一条），
        // 而不是 height01(float) —— 后者是"预览用的 float 臂"，不参与高度链。
        PlateauRecipe plateau = new PlateauRecipe(PlateauRecipe.Variant.REF);
        plateau.seed(seed + MorphCatalog.OFF_PLATEAU);
        MorphNoise.Node coverageNode = plateau.heightNode();

        return new MorphAssembly(seed, partition, slots, mask,
                (x, z) -> MorphNoiseEval.eval(coverageNode, x, z));
    }

    /**
     * 空转参考为 10 个低地复合对消费的 20 个种子。
     *
     * <p>⚠ <b>不要"?清理"这段</b>：它不产出任何对象，但决定了后面每一个噪声的种子。
     * 删掉它 = shuffle/分区/遮罩全部前移 20 ⇒ 换图（且只换一点点，很难看出来）。</p>
     *
     * @return 推进 20 后的游标
     */
    private static int consumeCompositeSeeds(int cursor) {
        int n = MorphCatalog.MIXABLE;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                cursor++;   // selector
                cursor++;   // warpPerlin
            }
        }
        return cursor;
    }

    /**
     * 权重 → 45 个票槽 → shuffle。
     *
     * <p>{@code Collections.shuffle(list, new Random(seed))} 必须用 JDK 的同一个实现：
     * 自己写 Fisher-Yates 会在 {@code seed} 相同的情况下给出不同排列（JDK 的
     * {@code shuffle} 走 {@code nextInt(bound)}，与朴素 {@code nextInt()%bound} 不等价）。</p>
     */
    private static int[] buildSlots(int shuffleSeed) {
        float[] weights = new float[MorphCatalog.ENTRY_COUNT];
        for (int i = 0; i < MorphCatalog.MIXABLE; i++) {
            weights[i] = MorphCatalog.LOW_WEIGHTS[i];
        }
        int k = MorphCatalog.MIXABLE;
        for (int i = 0; i < MorphCatalog.MIXABLE; i++) {
            for (int j = i + 1; j < MorphCatalog.MIXABLE; j++) {
                weights[k++] = (MorphCatalog.LOW_WEIGHTS[i] + MorphCatalog.LOW_WEIGHTS[j]) / 2f;
            }
        }
        weights[MorphCatalog.IDX_PLATEAU] = MorphCatalog.PLATEAU_WEIGHT;
        weights[MorphCatalog.IDX_BADLANDS] = MorphCatalog.BADLANDS_WEIGHT;
        weights[MorphCatalog.IDX_MOUNTAIN] = MorphCatalog.MOUNTAIN_WEIGHT;

        float smallest = Float.MAX_VALUE;
        for (float w : weights) {
            if (w > 0f) smallest = Math.min(smallest, w);
        }
        if (smallest == Float.MAX_VALUE) smallest = 1f;

        int total = 0;
        int[] counts = new int[weights.length];
        for (int i = 0; i < weights.length; i++) {
            counts[i] = weights[i] <= 0f ? 0 : Math.round(weights[i] / smallest);
            total += counts[i];
        }
        if (total != SLOT_COUNT) {
            throw new IllegalStateException("slot count " + total + " != " + SLOT_COUNT);
        }
        int[] expanded = new int[total];
        int at = 0;
        for (int i = 0; i < counts.length; i++) {
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
        return shuffled;
    }

    // ============================================================ 访问器

    long seed() { return seed; }

    MorphRegionPartition partition() { return partition; }

    /** 45 个票槽 → 条目下标（只读，请勿修改）。 */
    int slotEntry(int slot) {
        if (slot < 0 || slot >= slotTable.length) {
            throw new IndexOutOfBoundsException("slot " + slot + " outside [0," + slotTable.length + ")");
        }
        return slotTable[slot];
    }

    int slotCount() { return slotTable.length; }

    /**
     * 每张票槽的类别标签（长度 = {@link #slotCount()}），只用于分类查询/类型图着色。
     *
     * <p>类别由<b>条目自身</b>决定（复合取成员中 ordinal 更大者），与
     * {@link MorphRegionContext} 独立算出的那份必须逐槽相同 —— 那条断言是两套分类不分叉的保险。</p>
     */
    MorphRegionContext.SlotCategory[] slotCategories() {
        MorphRegionContext.SlotCategory[] cats =
                new MorphRegionContext.SlotCategory[slotTable.length];
        for (int i = 0; i < slotTable.length; i++) {
            cats[i] = MorphRegionContext.categoryOfEntry(slotTable[i]);
        }
        return cats;
    }

    /** 山形遮罩节点（覆盖层的 select 值）。 */
    MorphNoise.Node mask() { return mask; }

    /** 覆盖族（共享台地 {@code height01}，Terrace 值域 1.05）。 */
    Family coverage() { return coverage; }

    /**
     * 覆盖层 select 的下阈值。
     *
     * <p>为什么不是参考的 0.3：实测遮罩分布 P50=0.385 / P75=0.531 / P90=0.715。
     * 用 0.3 起混 ⇒ <b>71.8% 的地面</b>都在与山地混合，整幅图变成均匀皱褶；
     * 用 0.8 封顶 ⇒ 只有 5.1% 是纯山地。取 0.55/0.78 ⇒ 约 23% 进入混合带、约 8% 纯山地。</p>
     */
    float blendLow() { return MTN_BLEND_LOW; }

    /** 覆盖层 select 的上阈值。 */
    float blendHigh() { return MTN_BLEND_HIGH; }
}
